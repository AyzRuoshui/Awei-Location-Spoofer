package com.awei.remotecam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.awei.common.Proto
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 远程摄像头前台服务：
 * - TCP 服务器（默认端口 17521）等待本机 App Hook 进程连接
 * - 授权流：cam_request → 高优先级通知（同意/拒绝），30s 超时视为拒绝
 * - 同意后启动 CamSession（相机+编码），多客户端扇出
 */
class CamServerService : Service() {

    companion object {
        const val ACTION_START = "com.awei.remotecam.START"
        const val ACTION_STOP = "com.awei.remotecam.STOP"
        const val ACTION_GRANT = "com.awei.remotecam.GRANT"
        const val ACTION_DENY = "com.awei.remotecam.DENY"
        const val ACTION_STOP_STREAM = "com.awei.remotecam.STOP_STREAM"
        const val ACTION_RELAY = "com.awei.remotecam.RELAY"
        const val EXTRA_RID = "rid"

        private const val CH_SERVER = "server"
        private const val CH_AUTH = "auth"
        private const val NID_SERVER = 1
        private const val NID_AUTH_BASE = 1000
        private const val NID_STREAM = 2

        @Volatile
        var running = false
            private set

        @Volatile
        var statusLine = "未启动"
            private set

        /** 运行中的服务实例（RelayLink 回调创建会话用） */
        @Volatile
        var instance: CamServerService? = null
            private set

        private val logBuf = ArrayDeque<String>()
        private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

        fun logLine(s: String) {
            synchronized(logBuf) {
                logBuf.addLast("${fmt.format(Date())}  $s")
                while (logBuf.size > 200) logBuf.removeFirst()
            }
        }

        fun logText(): String = synchronized(logBuf) { logBuf.joinToString("\n") }
    }

    private val main = Handler(Looper.getMainLooper())
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val conns = CopyOnWriteArrayList<ClientConn>()
    // @Volatile：sessionLock 内写，但 keyframe 处理（连接读线程）/grant 检查跨线程读
    @Volatile
    private var session: CamSession? = null
    private val sessionLock = Any()
    private var wakeLock: PowerManager.WakeLock? = null
    private val ridSeq = AtomicInteger(1)
    private var relayLink: RelayLink? = null
    private var relayThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELAY -> {
                ensureStarted()
                // 强制重建：开关切换或服务器地址变更都要按最新配置重连
                stopRelayLink()
                if (Settings.relayEnabled(this)) ensureRelayLink()
            }
            ACTION_GRANT -> {
                val rid = intent.getIntExtra(EXTRA_RID, -1)
                // ensureSession 会同步开相机（部分设备数百 ms~秒级），放后台防主线程 ANR
                Thread {
                    val conn = conns.firstOrNull { it.pendingRid == rid }
                    if (conn != null) grant(conn) else logLine("同意请求超时（rid=$rid 已失效）")
                    nm().cancel(NID_AUTH_BASE + rid)
                }.start()
            }
            ACTION_DENY -> {
                val rid = intent.getIntExtra(EXTRA_RID, -1)
                val conn = conns.firstOrNull { it.pendingRid == rid }
                if (conn != null) deny(conn, "user")
                nm().cancel(NID_AUTH_BASE + rid)
            }
            ACTION_STOP_STREAM -> {
                conns.forEach { it.close("手动停止") }
                stopSession()
            }
            else -> ensureStarted()
        }
        return START_STICKY
    }

    private fun nm() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // ---------- 生命周期 ----------

    private fun ensureStarted() {
        if (running) return
        running = true
        createChannels()
        startFgs("服务已启动，等待连接")
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "awei:remotecam").apply {
                setReferenceCounted(false)
                // 不限时：6 小时一次性 acquire 到点后 CPU 可休眠断流且无任何日志；
                // shutdown/release 路径已正确释放，泄漏风险由服务生命周期兜住
                acquire()
            }
        acceptThread = Thread({ acceptLoop() }, "awei-accept").apply { start() }
        statusLine = "运行中 · ${NetUtil.lanIps().firstOrNull() ?: "无网络"}:${Settings.port(this)}"
        logLine("服务启动 ${statusLine} 配对码 ${Settings.token(this)}")
        updateFgs()
        ensureRelayLink()
    }

    // 认证失败限速：配对码=唯一凭据，6 位数字无代价重试可在小时内穷举。
    // 按 peer 记忆失败次数，重试延迟 1s 起翻倍、60s 封顶（1,2,4…64→60s），
    // 成功验证即清零。仅内存态：重启即清（对持续攻击者等于每次重启重置，可接受）
    private val authFails = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()

    private fun penaltyDelayMs(count: Int): Long =
        (1000L shl minOf(count, 6)).coerceAtMost(60_000L)

    private fun shutdown() {
        running = false
        try {
            server?.close()
        } catch (t: Throwable) {
        }
        server = null
        conns.forEach { it.close("服务停止") }
        conns.clear()
        stopSession()
        stopRelayLink()
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (t: Throwable) {
        }
        wakeLock = null
        statusLine = "未启动"
        logLine("服务已停止")
    }

    override fun onDestroy() {
        shutdown()
        instance = null
        super.onDestroy()
    }

    private fun acceptLoop() {
        try {
            val ss = ServerSocket(Settings.port(this))
            server = ss
            while (running) {
                val sock = ss.accept()
                if (!running) {
                    sock.close()
                    break
                }
                // socket 参数在 accept 侧设置；ClientConn 只管流（LAN 与中转透传通用）
                sock.tcpNoDelay = true
                sock.soTimeout = 60_000 // 客户端 15s 心跳（sendLoop ping）；超时视为断连
                val conn = ClientConn(
                    DataInputStream(sock.getInputStream()),
                    DataOutputStream(sock.getOutputStream()),
                    sock.inetAddress?.hostAddress ?: "未知",
                ) {
                    try {
                        sock.close()
                    } catch (t: Throwable) {
                    }
                }
                conns.add(conn)
                Thread({ conn.run() }, "awei-conn").apply { start() }
                logLine("新连接 ${sock.inetAddress?.hostAddress}")
            }
        } catch (t: Throwable) {
            if (running) {
                // 绑定失败（端口被占等）不能装作运行中：置失败状态并停止服务
                logLine("服务器启动失败: $t")
                statusLine = "启动失败：$t"
                main.post {
                    shutdown()
                    stopSelf()
                }
            }
        }
    }

    // ---------- 中转链路 ----------

    private fun ensureRelayLink() {
        if (!Settings.relayEnabled(this)) return
        val srv = Settings.relayServer(this)
        if (srv.isBlank()) {
            logLine("中转服务器未配置，中转不生效")
            return
        }
        if (relayThread?.isAlive == true) return
        val link = RelayLink(srv, Settings.deviceId(this))
        relayLink = link
        relayThread = Thread({ link.loop() }, "awei-relay-link").apply { start() }
        logLine("中转链路启动: $srv")
    }

    private fun stopRelayLink() {
        relayLink?.stop()
        // 旧链路可能阻塞在 connect/read/退避 sleep：interrupt 让它立刻退出，
        // 避免换代后新旧两条线程并存（状态互踩、旧会话误杀新链路）
        relayThread?.interrupt()
        relayLink = null
        relayThread = null
    }

    /** 该链路是否仍是当前生效链路（RelayLink 换代守卫用） */
    fun isCurrentLink(link: RelayLink): Boolean = relayLink === link

    /** 中转拨入：把服务器透传连接包成本地会话（授权/编码/扇出与 LAN 直连完全一致）。
     *  link 必须传"收到 incoming 的那条链路"本身：会话收尾经它关闭自己的 socket，
     *  换代竞态下才不会错杀新链路 */
    fun newRelaySession(link: RelayLink, dis: DataInputStream, dos: DataOutputStream, peer: String): ClientConn {
        val conn = ClientConn(dis, dos, peer) { link.closeSocket() }
        conns.add(conn)
        return conn
    }

    // ---------- 会话 ----------

    private fun ensureSession() {
        synchronized(sessionLock) {
            // 服务正在停止时不应创建新会话：ACTION_GRANT 线程在 shutdown 之后抢到锁的
            // 窄竞态窗口内，不检查 running 会创建无人管理的 CamSession（相机泄漏）
            if (!running) return
            if (session != null) return
            val s = CamSession(
                Settings.portrait(this),
            ) { msg ->
                logLine(msg)
                main.post { conns.forEach { it.close("会话错误") }; stopSession() }
            }
            session = s
            s.start()
            updateFgs()
        }
    }

    private fun stopSession() {
        synchronized(sessionLock) {
            session?.stop()
            session = null
            updateFgs()
        }
    }

    private fun grant(conn: ClientConn) {
        conn.cancelPending()
        ensureSession()
        val s = session
        if (s == null || !s.isAlive) {
            // start 失败（会话还在异步清场、session 字段未及置 null）时不能对死会话
            // 发 cam_grant——主端"授权成功却黑屏"。按 camera_fail 拒绝让主端立即重试
            deny(conn, "camera_fail")
            return
        }
        conn.granted()
        updateFgs()
    }

    private fun deny(conn: ClientConn, reason: String) {
        conn.denyPending(reason)
    }

    private fun onConnClosed(conn: ClientConn) {
        conns.remove(conn)
        logLine("连接断开（剩余 ${conns.size}）")
        main.post { scheduleIdleStop() }
    }

    /** 无已授权客户端时延迟停采集（cam_release 与连接断开共用） */
    private fun scheduleIdleStop() {
        if (conns.none { it.isGranted }) {
            main.postDelayed({
                if (running && conns.none { it.isGranted }) {
                    stopSession()
                    logLine("无授权客户端，相机已停止")
                }
            }, 10_000)
        }
    }

    // ---------- 通知 ----------

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = nm()
            nm.createNotificationChannel(
                NotificationChannel(CH_SERVER, "服务状态", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_AUTH, "摄像头授权请求", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "本机 App 请求使用此手机摄像头时的授权弹窗"
                }
            )
        }
    }

    private fun baseBuilder(channel: String): Notification.Builder {
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channel)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
    }

    private fun startFgs(text: String) {
        val n = baseBuilder(CH_SERVER)
            .setContentTitle("阿伟远程摄像头")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NID_SERVER, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(NID_SERVER, n)
            }
        } catch (t: Throwable) {
            // Android 14+ 相机权限被收/FGS 类型校验失败时直接崩，这里降级为停止服务
            logLine("前台服务启动失败: $t")
            statusLine = "启动失败：$t"
            main.post {
                shutdown()
                stopSelf()
            }
        }
    }

    private fun updateFgs() {
        val granted = conns.count { it.isGranted }
        val text = if (granted > 0) {
            "正在向 $granted 个设备提供画面（点通知停止）"
        } else {
            "等待连接 · 配对码 ${Settings.token(this)}"
        }
        statusLine = text
        val pi = PendingIntent.getService(
            this, 99,
            Intent(this, CamServerService::class.java).setAction(ACTION_STOP_STREAM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = baseBuilder(CH_SERVER)
            .setContentTitle("阿伟远程摄像头")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "停止画面", pi)
            .build()
        nm().notify(NID_SERVER, n)
    }

    private fun authNotification(rid: Int, appLabel: String) {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        fun action(a: String, title: String): Notification.Action {
            val pi = PendingIntent.getService(
                this, rid * 10 + if (a == ACTION_GRANT) 1 else 2,
                Intent(this, CamServerService::class.java).setAction(a).putExtra(EXTRA_RID, rid),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return Notification.Action.Builder(0, title, pi).build()
        }
        val n = baseBuilder(CH_AUTH)
            .setContentTitle("摄像头授权请求")
            .setContentText("「$appLabel」请求使用此手机摄像头")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setDeleteIntent(action(ACTION_DENY, "删除").actionIntent)
            .addAction(action(ACTION_GRANT, "同意"))
            .addAction(action(ACTION_DENY, "拒绝"))
            .build()
        nm().notify(NID_AUTH_BASE + rid, n)
    }

    // ---------- 连接 ----------

    /**
     * 单条客户端会话：LAN 直连（accept 侧建）与中转拨入（RelayLink 透传流建）共用。
     * socket 参数（tcpNoDelay/soTimeout）由连接的提供方设置，这里只读写流。
     * 非 private：RelayLink 经 CamServerService.instance 会话工厂拿到它。
     */
    inner class ClientConn(
        private val dis: DataInputStream,
        private val dos: DataOutputStream,
        private val peer: String,
        private val closeFn: () -> Unit,
    ) {

        @Volatile
        var pendingRid = -1
            private set

        @Volatile
        var isGranted = false
            private set

        @Volatile
        private var requestRid = -1

        // hello 令牌校验通过前不允许任何业务消息（否则 LAN 内设备可跳过配对直接 cam_request）
        @Volatile
        private var authenticated = false

        private var out: DataOutputStream? = null
        private val sendQueue = LinkedBlockingQueue<Pair<Int, ByteArray>>(120)
        @Volatile
        private var closed = false
        private var detach: (() -> Unit)? = null
        private var sendThread: Thread? = null

        fun run() {
            try {
                out = dos
                sendThread = Thread({ sendLoop(dos) }, "awei-send").apply { start() }

                while (!closed) {
                    val frame = try {
                        Proto.read(dis)
                    } catch (t: Throwable) {
                        break
                    } ?: break
                    val (type, payload) = frame
                    when (type) {
                        Proto.TYPE_JSON -> handleJson(Proto.parseJson(payload) ?: continue)
                        else -> continue // 客户端不发二进制
                    }
                }
            } catch (t: Throwable) {
            } finally {
                closeInternal("连接结束")
                onConnClosed(this)
            }
        }

        private fun handleJson(j: JSONObject) {
            when (j.optString("t")) {
                "hello" -> {
                    // 认证失败限速：该 peer 处于惩罚窗口内则先拖满剩余时间再校验
                    //（攻击者每试一次都变慢，穷举成本从"小时级"抬到不可行）
                    authFails[peer]?.let { (count, lastAt) ->
                        val wait = lastAt + penaltyDelayMs(count) - System.currentTimeMillis()
                        if (wait > 0) {
                            logLine("认证失败限速：$peer 延迟 ${wait / 1000}s 后校验")
                            try {
                                Thread.sleep(minOf(wait, 60_000L))
                            } catch (t: Throwable) {
                            }
                        }
                    }
                    val ok = j.optString("token") == Settings.token(this@CamServerService)
                    if (ok) {
                        authenticated = true
                        authFails.remove(peer)
                        send(Proto.TYPE_JSON, Proto.helloOk(Build.MODEL).toString().toByteArray())
                        logLine("设备验证通过：${j.optString("device")} ($peer)")
                    } else {
                        authFails[peer] = Pair(
                            (authFails[peer]?.first ?: 0) + 1, System.currentTimeMillis()
                        )
                        // 同步写出拒绝帧再关连接：走发送队列可能赶在关闭前没写出，客户端只能干等超时。
                        // 与 sendLoop 的直写 ping 分属两线程：synchronized(dos) 防两帧字节交错
                        try {
                            out?.let { synchronized(it) {
                                Proto.write(it, Proto.TYPE_JSON, "{\"t\":\"auth_fail\"}".toByteArray())
                            } }
                        } catch (t: Throwable) {
                        }
                        logLine("配对码错误，拒绝连接 $peer")
                        closeInternal("auth_fail")
                    }
                }
                "cam_request" -> {
                    if (!authenticated) return unauthed()
                    // 服务端自生成内部请求序号：通知 ID/授权匹配/超时判定全用它——
                    // 客户端 rid 是任意可控值，直接拿来做匹配键会被伪造连接顶掉真授权
                    val rid = ridSeq.getAndIncrement()
                    val app = j.optString("app")
                    pendingRid = rid
                    requestRid = j.optInt("rid")
                    if (Settings.autoApprove(this@CamServerService)) {
                        logLine("自动同意：$app")
                        grant(this)
                    } else {
                        logLine("授权请求：$app（30 秒超时）")
                        authNotification(rid, app)
                        main.postDelayed({
                            if (pendingRid == rid && !isGranted) {
                                logLine("授权超时：$app")
                                deny(this, "timeout")
                            }
                        }, 30_000)
                    }
                }
                "cam_release" -> {
                    if (!authenticated) return unauthed()
                    logLine("对方释放摄像头")
                    release()
                    main.post { scheduleIdleStop() }
                }
                "keyframe" -> {
                    if (!authenticated) return unauthed()
                    session?.requestKeyframe()
                }
                "ping" -> {
                    if (!authenticated) return unauthed()
                    send(Proto.TYPE_JSON, Proto.pong().toString().toByteArray())
                }
            }
        }

        private fun unauthed() {
            logLine("未验证连接发来业务消息，拒绝 $peer")
            closeInternal("auth_fail")
        }

        fun granted() {
            // 重授权防御：同一连接第二次 cam_request 被授予时先解绑旧扇出（防双 attach 重复帧）
            release()
            isGranted = true
            pendingRid = -1
            ensureSession()
            val s = session
            if (s == null || !s.isAlive) {
                isGranted = false
                send(Proto.TYPE_JSON, Proto.camDeny(requestRid, "camera_fail").toString().toByteArray())
                return
            }
            send(Proto.TYPE_JSON, Proto.camGrant(requestRid, s.outWidth, s.outHeight).toString().toByteArray())
            detach = s.attach(
                send = { type, payload -> send(type, payload) },
                isAlive = { !closed }
            )
        }

        fun denyPending(reason: String) {
            pendingRid = -1
            send(Proto.TYPE_JSON, Proto.camDeny(requestRid, reason).toString().toByteArray())
        }

        fun cancelPending() {
            pendingRid = -1
        }

        private fun release() {
            isGranted = false
            detach?.invoke()
            detach = null
        }

        fun send(type: Int, payload: ByteArray) {
            if (closed) return
            try {
                val item = Pair(type, payload)
                synchronized(sendQueue) {
                    if (!sendQueue.offer(item)) {
                        // 队满丢最旧（保低延迟），但 CSD 绝不能丢——丢了客户端
                        // 解码器起不来，只能等下轮 attach 补发（黑屏到重连）
                        val dropIdx = sendQueue.indexOfFirst { it.first != Proto.TYPE_CSD }
                        if (dropIdx >= 0) {
                            var i = 0
                            val it2 = sendQueue.iterator()
                            while (it2.hasNext()) {
                                it2.next()
                                if (i++ == dropIdx) {
                                    it2.remove()
                                    break
                                }
                            }
                        } else {
                            sendQueue.poll()
                        }
                        sendQueue.offer(item)
                    }
                }
            } catch (t: Throwable) {
            }
        }

        private fun sendLoop(dos: DataOutputStream) {
            var lastPingAt = System.currentTimeMillis()
            try {
                while (!closed) {
                    // 稳态收流期主端只收不发（视频帧只进来），本端必须周期性主动
                    // ping 保活：主端回的 pong 是唯一持续上行，喂饱本端读超时
                    // （LAN 60s / 中转透传 45s），否则每分钟被读超时掐断一次
                    val now = System.currentTimeMillis()
                    if (now - lastPingAt > 15_000) {
                        lastPingAt = now
                        // 与 hello 拒绝帧的直写分属两线程：synchronized 防帧字节交错
                        synchronized(dos) {
                            Proto.write(dos, Proto.TYPE_JSON,
                                "{\"t\":\"ping\"}".toByteArray())
                        }
                    }
                    val f = sendQueue.poll(1, TimeUnit.SECONDS) ?: continue
                    Proto.write(dos, f.first, f.second)
                }
            } catch (t: Throwable) {
                closeInternal("发送失败")
            }
        }

        fun close(reason: String) {
            closeInternal(reason)
        }

        private fun closeInternal(reason: String) {
            if (closed) return
            closed = true
            release()
            try {
                closeFn()
            } catch (t: Throwable) {
            }
        }
    }
}
