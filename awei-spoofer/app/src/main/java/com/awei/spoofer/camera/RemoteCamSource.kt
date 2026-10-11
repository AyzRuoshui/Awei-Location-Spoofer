package com.awei.spoofer.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.rtmp.RtmpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.awei.common.Proto
import com.awei.spoofer.xposed.XLog
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

/**
 * 远程摄像头源（Hook 进程内）：
 * 目标 App 打开摄像头（首个输出面注册）时才连接远程手机——懒连接，
 * 避免每个被 Hook 的进程启动即建连、远程被授权请求轰炸。
 *
 * 链路：TCP → 授权（cam_request/cam_grant）→ H.264 → MediaCodec 硬解
 * → RGBA ImageReader → 最新帧 Bitmap 缓存 → center-crop 绘制到全部输出面。
 * 最后一个输出面注销 60s 后空闲超时：直接断开 TCP（远程端感知 EOF 后
 * 停止相机采集，cam_release 帧为预留消息，当前链路上不发送）。
 */
object RemoteCamSource {

    @Volatile
    private var addr: String = ""

    @Volatile
    private var token: String = ""

    // 中转服务器地址：非空=中转模式（addr 存远程 ID，先经服务器拨号再跑原协议）
    @Volatile
    private var relayServer: String? = null

    @Volatile
    private var appLabel: String = ""

    @Volatile
    var lastFrame: Bitmap? = null
        private set

    @Volatile
    private var streamW = 0

    @Volatile
    private var streamH = 0

    private val outputs = ConcurrentHashMap<Surface, Boolean>()

    // 每个输出面一个 EGL 渲染器（GLConsumer 类面上 Canvas 投帧不上屏，见 EglSurfaceRenderer 头注）
    private val eglRenderers = ConcurrentHashMap<Surface, EglSurfaceRenderer>()
    // EGL 初始化失败的面：本会话内降级 Canvas，不再每帧重试
    private val eglDisabled = ConcurrentHashMap<Surface, Boolean>()
    // EGL 连续供帧失败计数（达到阈值降级 Canvas，防空转重建刷日志）
    private val eglFailStreak = ConcurrentHashMap<Surface, Int>()

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var currentSocket: Socket? = null

    @Volatile
    private var deniedUntil = 0L

    // ---- 解码状态（worker 线程独占 + 绘制线程读 lastFrame）----
    private var decoder: MediaCodec? = null
    private var reader: ImageReader? = null
    private var readerThread: HandlerThread? = null
    private var nextPtsUs = 0L
    @Volatile
    private var lastFrameAt = 0L

    @Volatile
    private var idleDeadline = 0L

    // release() 置位：worker 收尾退出（不用 interrupt 退出——interrupt 只作"醒来重评估"唤醒）
    @Volatile
    private var disposed = false

    // 唤醒代数：attachOutput/配置更新时 +1 并 interrupt。worker 用快照比对区分
    // "被唤醒需立即重评估"与"正常失败退避"——不依赖线程中断标志（中断会被 sleep 消费）
    @Volatile
    private var wakeGen = 0L

    // 本轮周期要求快速重连（断流看门狗触发），worker 不退避直接重连
    @Volatile
    private var cycleFastRetry = false

    // 网络源（RTSP/RTMP/HTTP）需要的 Context（目标 App 的 applicationContext，ExoPlayer 构建用）
    @Volatile
    private var appCtx: Context? = null

    // 网络源实际视频尺寸（Player.Listener.onVideoSizeChanged 上报，按此重建 reader）
    @Volatile
    private var netVideoSize: Pair<Int, Int>? = null

    fun isNetworkUrl(u: String): Boolean =
        u.startsWith("rtsp://") || u.startsWith("rtmp://") ||
            u.startsWith("http://") || u.startsWith("https://")

    /** 配置更新（地址/令牌/目标 App 名变化时重连）；relayServer 非空=中转模式 */
    fun setup(ctx: Context, newAddr: String, newToken: String, label: String?, relayServer: String? = null) {
        appCtx = ctx.applicationContext
        val changed = newAddr != addr || newToken != token || relayServer != this.relayServer
        if (label != null) appLabel = label
        this.relayServer = relayServer
        if (changed && newAddr.isNotBlank()) {
            addr = newAddr
            token = newToken
            netVideoSize = null
            deniedUntil = 0L // 填错配对码的冷却不该拦住"改对配置后立刻重试"
            closeCurrentSocket()
            // 唤醒可能阻塞在旧连接读/退避睡眠里的 worker，立刻按新地址重评估
            wakeGen++
            try {
                worker?.interrupt()
            } catch (t: Throwable) {
            }
            XLog.i("远程源配置更新: $newAddr")
        }
        if (outputs.isNotEmpty()) ensureWorker()
    }

    /** 完全卸载（配置禁用/切回本地视频源） */
    fun release() {
        disposed = true
        addr = ""
        detachAllOutputs()
        closeCurrentSocket()
        XLog.i("远程源已卸载")
    }

    fun attachOutput(surface: Surface): Boolean {
        if (!surface.isValid) return false
        outputs[surface] = true
        idleDeadline = 0L
        ensureWorker()
        // 唤醒可能在退避/空闲 sleep 的 worker，立刻重评估（否则重新打开相机最长要等 60s 才有画面）
        wakeGen++
        try {
            worker?.interrupt()
        } catch (t: Throwable) {
        }
        return true
    }

    fun detachOutput(surface: Surface) {
        outputs.remove(surface)
        releaseEglRenderer(surface)
        if (outputs.isEmpty()) scheduleIdleRelease()
    }

    fun detachAllOutputs() {
        for (s in outputs.keys) releaseEglRenderer(s)
        outputs.clear()
        scheduleIdleRelease()
    }

    private fun releaseEglRenderer(s: Surface) {
        val r = eglRenderers.remove(s) ?: return
        eglDisabled.remove(s)
        eglFailStreak.remove(s)
        // EGL 上下文在绘制线程（reader 回调线程）上 current，投递到同线程释放最稳；
        // 线程不在时直接释放（EGL 对跨线程 destroy 会延迟生效，不崩溃）
        val rt = readerThread
        if (rt != null && rt.isAlive) {
            Handler(rt.looper).post {
                try {
                    r.release()
                } catch (t: Throwable) {
                }
            }
        } else {
            try {
                r.release()
            } catch (t: Throwable) {
            }
        }
    }

    private fun scheduleIdleRelease() {
        idleDeadline = System.currentTimeMillis() + 60_000L
    }

    private fun closeCurrentSocket() {
        try {
            currentSocket?.close()
        } catch (t: Throwable) {
        }
    }

    // ---------------- worker：连接/授权/收流 ----------------

    private fun ensureWorker() {
        val w = worker
        if (w != null && w.isAlive) return
        synchronized(this) {
            val cur = worker
            if (cur != null && cur.isAlive) return
            disposed = false
            val t = Thread({ workerLoop() }, "awei-remote-cam")
            t.isDaemon = true
            worker = t
            t.start()
        }
    }

    private fun workerLoop() {
        var backoff = 5_000L
        while (!disposed) {
            val myWake = wakeGen
            // 无输出面（没有 App 在用相机）→ 轮询等待新注册或空闲超时断开
            if (outputs.isEmpty()) {
                val deadline = idleDeadline
                if (deadline > 0L && System.currentTimeMillis() >= deadline) {
                    closeCurrentSocket()
                    XLog.i("远程源空闲超时，已断开")
                    lastFrame = null
                    return
                }
                sleepInterruptible(if (deadline > 0L) 500 else 800)
                continue
            }
            if (addr.isBlank()) {
                sleepInterruptible(10_000)
                continue
            }
            val denyWait = deniedUntil - System.currentTimeMillis()
            if (denyWait > 0) {
                XLog.w("远程授权被拒，${denyWait / 1000}s 后才可重试")
                sleepInterruptible(denyWait.coerceAtMost(60_000L))
                continue
            }

            val cycleStart = System.currentTimeMillis()
            cycleFastRetry = false
            try {
                if (isNetworkUrl(addr)) {
                    // 网络直播流（RTSP/RTMP/HTTP）：无 TCP 授权握手，ExoPlayer 直接拉流
                    runNetworkCycle(addr)
                } else {
                    runCycle()
                }
            } catch (t: Throwable) {
                // 周期内部任何未捕获异常不得杀死 worker 线程（否则无人重连/清理）
                if (outputs.isNotEmpty()) XLog.w("远程源周期异常: ${t.javaClass.simpleName}")
            }
            // 被唤醒（新输出面/配置更新）或已无事可做 → 立即重评估，不退避
            if (wakeGen != myWake || outputs.isEmpty() || disposed) continue
            // 本轮周期有实质传输（>15s）视为成功：退避复位，断流后 5s 内即可重连。
            // 必须在 fastRetry 判断之前——看门狗重连的长周期同样算"有实质传输"，
            // 否则长会话经看门狗断流后仍会吃到陈旧的大退避
            if (System.currentTimeMillis() - cycleStart > 15_000L) backoff = 5_000L
            if (cycleFastRetry) continue
            val b = backoff
            backoff = (backoff * 2).coerceAtMost(60_000L)
            sleepInterruptible(b)
        }
    }

    /**
     * 网络源周期：ExoPlayer 渲到 RGBA ImageReader（复用解码/绘制基建），断流/退出由外层重连。
     * 真实视频尺寸经 onVideoSizeChanged 上报后重建 reader（ImageReader 尺寸固定，解码尺寸
     * 不匹配会收不到帧，必须按实际尺寸重建）。
     */
    private fun runNetworkCycle(url: String) {
        val ctx = appCtx
        if (ctx == null) {
            XLog.w("网络源缺少上下文，10s 后重试")
            sleepInterruptible(10_000)
            return
        }
        if (streamW <= 0) {
            // 初始猜测尺寸，真实尺寸由 onVideoSizeChanged 纠正
            streamW = 1280
            streamH = 720
        }
        prepareDecoder(streamW, streamH)
        val ht = HandlerThread("awei-net-player").apply { start() }
        var player: ExoPlayer? = null
        val built = CountDownLatch(1)
        Handler(ht.looper).post {
            try {
                val dsFactory = if (url.startsWith("rtmp://")) {
                    DefaultDataSource.Factory(ctx, RtmpDataSource.Factory())
                } else {
                    DefaultDataSource.Factory(ctx)
                }
                val p = ExoPlayer.Builder(ctx, DefaultRenderersFactory(ctx))
                    .setMediaSourceFactory(DefaultMediaSourceFactory(dsFactory))
                    .build()
                p.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            netVideoSize = Pair(videoSize.width, videoSize.height)
                        }
                    }
                })
                p.setVideoSurface(reader?.surface)
                p.volume = 0f // 静音：直播流的声音不该从目标设备外放
                p.setMediaItem(MediaItem.fromUri(url))
                p.playWhenReady = true
                p.prepare()
                player = p
            } catch (t: Throwable) {
                XLog.e("网络源启动失败: $t")
            } finally {
                built.countDown()
            }
        }
        awaitLatch(built)
        if (player == null) {
            try {
                ht.quitSafely()
            } catch (t: Throwable) {
            }
            teardownDecoder()
            return
        }
        XLog.i("网络源已启动: $url")
        lastFrameAt = System.currentTimeMillis()
        while (!disposed && outputs.isNotEmpty()) {
            val want = netVideoSize
            if (want != null && (want.first != streamW || want.second != streamH)) {
                // 解码尺寸与 reader 不一致：重建 reader 后重新挂面
                netVideoSize = null
                val detached = CountDownLatch(1)
                Handler(ht.looper).post {
                    try {
                        player?.setVideoSurface(null)
                    } catch (t: Throwable) {
                    }
                    detached.countDown()
                }
                awaitLatch(detached)
                teardownDecoder()
                prepareDecoder(want.first, want.second)
                streamW = want.first
                streamH = want.second
                val reattached = CountDownLatch(1)
                Handler(ht.looper).post {
                    try {
                        player?.setVideoSurface(reader?.surface)
                    } catch (t: Throwable) {
                    }
                    reattached.countDown()
                }
                awaitLatch(reattached)
                XLog.i("网络源 reader 已按实际尺寸重建 ${want.first}x${want.second}")
                continue
            }
            val silent = System.currentTimeMillis() - lastFrameAt
            if (silent > 30_000) {
                XLog.w("网络源断流 ${silent / 1000}s，重连")
                break
            }
            sleepInterruptible(1000)
        }
        val released = CountDownLatch(1)
        Handler(ht.looper).post {
            try {
                player?.release()
            } catch (t: Throwable) {
            }
            released.countDown()
        }
        awaitLatch(released)
        try {
            ht.quitSafely()
        } catch (t: Throwable) {
        }
        teardownDecoder()
    }

    /** sleep 被中断=醒来重评估：消费掉中断标志而不是重新置位——重新置位会让后续每次 sleep
     *  立即抛出，把"被唤醒"的循环变成 100% CPU 热自旋。线程生命周期由 disposed 控制 */
    private fun sleepInterruptible(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
        }
    }

    /** 不可中断的 latch 等待：中断只会唤醒一次重试（配合"中断=唤醒"语义，不丢失同步） */
    private fun awaitLatch(l: java.util.concurrent.CountDownLatch) {
        while (true) {
            try {
                l.await()
                return
            } catch (e: InterruptedException) {
            }
        }
    }

    /** 一次完整连接周期；返回 false 表示需要退避重试 */
    private fun runCycle(): Boolean {
        val relay = relayServer
        val sock = Socket()
        currentSocket = sock
        try {
            sock.tcpNoDelay = true
            val dis: DataInputStream
            val dos: DataOutputStream
            if (relay != null) {
                // 中转模式：addr 存远程 ID，先连中转服务器拨号，dial_ok 后同一连接跑原协议
                val rp = Proto.parseAddr(relay)
                if (rp == null) {
                    XLog.e("中转服务器地址无效: $relay")
                    return false
                }
                sock.connect(InetSocketAddress(rp.first, rp.second), 8_000)
                sock.soTimeout = 20_000
                dis = DataInputStream(sock.getInputStream())
                dos = DataOutputStream(sock.getOutputStream())
                XLog.i("中转已连接 ${rp.first}:${rp.second}，拨入 ID=$addr")
                Proto.writeJson(dos, Proto.dial(addr, appLabel.ifBlank { "unknown" }))
                var dialed = false
                while (!dialed) {
                    val j = nextJson(dis) ?: throw java.io.IOException("dial 应答中断")
                    when (j.optString("t")) {
                        "dial_ok" -> dialed = true
                        "dial_fail" -> {
                            val reason = j.optString("reason")
                            val tip = when (reason) {
                                "offline" -> "（远程设备不在线）"
                                "badid" -> "（ID 不存在，核对远程手机显示的远程 ID）"
                                "busy" -> "（远程设备正忙，稍后再试）"
                                else -> ""
                            }
                            XLog.e("中转拨入失败: $reason$tip")
                            deniedUntil = System.currentTimeMillis() + 60_000L
                            return false
                        }
                        "ping" -> Proto.writeJson(dos, Proto.pong())
                    }
                }
            } else {
                val parsed = Proto.parseAddr(addr)
                if (parsed == null) {
                    XLog.e("远程地址无效: $addr")
                    return false
                }
                val (host, port) = parsed
                sock.connect(InetSocketAddress(host, port), 8_000)
                sock.soTimeout = 20_000
                dis = DataInputStream(sock.getInputStream())
                dos = DataOutputStream(sock.getOutputStream())
                XLog.i("远程已连接 $host:$port")
            }

            // 握手
            Proto.writeJson(dos, Proto.hello(token, appLabel.ifBlank { "unknown" }))
            val hello = nextJson(dis) ?: throw java.io.IOException("hello 超时")
            if (hello.optString("t") != "hello_ok") {
                XLog.e("远程拒绝握手: ${hello.optString("t")}（配对码错误?）")
                deniedUntil = System.currentTimeMillis() + 120_000L
                return false
            }

            // 授权请求
            val rid = (System.currentTimeMillis() % 100000).toInt()
            Proto.writeJson(dos, Proto.camRequest(rid, appLabel))
            XLog.i("已发送摄像头授权请求（等待远程手机确认）")
            var granted = false
            var streamW = 0
            var streamH = 0
            while (!granted) {
                val j = nextJson(dis) ?: throw java.io.IOException("授权等待中断")
                when (j.optString("t")) {
                    "cam_grant" -> {
                        granted = true
                        streamW = j.optInt("w", 0)
                        streamH = j.optInt("h", 0)
                    }
                    "cam_deny" -> {
                        XLog.w("远程拒绝了授权: ${j.optString("reason")}")
                        deniedUntil = System.currentTimeMillis() + 120_000L
                        return false
                    }
                    "ping" -> Proto.writeJson(dos, Proto.pong())
                }
            }
            XLog.i("远程授权通过，开始收流 ${streamW}x${streamH}")
            // 旧版远程端可能不上报分辨率：解码输出面必须有确定尺寸才能建 ImageReader
            if (streamW <= 0 || streamH <= 0) {
                streamW = 1280
                streamH = 720
                XLog.w("远程端未上报分辨率，按 1280x720 兜底")
            }
            prepareDecoder(streamW, streamH)
            // 看门狗从周期起点计时：若解码器初始化失败（lastFrameAt 恒 0），
            // 旧判定 lastFrameAt>0 && silent>20s 永假 → 双方"健康"却永久黑屏
            lastFrameAt = System.currentTimeMillis()

            // 收流主循环
            while (!sock.isClosed && outputs.isNotEmpty()) {
                val frame = try {
                    Proto.read(dis)
                } catch (e: SocketTimeoutException) {
                    // 心跳（同时做断流看门狗）
                    val silent = System.currentTimeMillis() - lastFrameAt
                    if (lastFrameAt > 0 && silent > 20_000) {
                        XLog.w("远程流中断 ${silent / 1000}s，重连")
                        Proto.writeJson(dos, Proto.keyframe())
                        cycleFastRetry = true // 立即重连（worker 不退避）
                        return true
                    }
                    Proto.writeJson(dos, Proto.ping())
                    continue
                } ?: break
                when (frame.first) {
                    Proto.TYPE_CSD -> feedDecoder(frame.second, true)
                    Proto.TYPE_H264 -> {
                        feedDecoder(frame.second, false)
                        lastFrameAt = System.currentTimeMillis()
                    }
                    Proto.TYPE_JSON -> Proto.parseJson(frame.second)?.let { j ->
                        if (j.optString("t") == "ping") Proto.writeJson(dos, Proto.pong())
                        if (j.optString("t") == "stream_stop") {
                            XLog.w("远程停止了画面: ${j.optString("reason")}")
                            return false
                        }
                    }
                }
            }
            return !outputs.isEmpty() // 还有输出面 → 重连
        } catch (t: Throwable) {
            if (outputs.isNotEmpty()) XLog.e("远程连接异常: ${t.javaClass.simpleName} $t")
            return outputs.isNotEmpty()
        } finally {
            teardownDecoder()
            try {
                sock.close()
            } catch (t: Throwable) {
            }
            currentSocket = null
        }
    }

    private fun nextJson(dis: DataInputStream): JSONObject? {
        val deadline = System.currentTimeMillis() + 35_000
        while (System.currentTimeMillis() < deadline) {
            val frame = try {
                Proto.read(dis)
            } catch (e: SocketTimeoutException) {
                continue
            } ?: return null
            if (frame.first == Proto.TYPE_JSON) {
                return Proto.parseJson(frame.second)
            }
        }
        return null
    }

    // ---------------- 解码与绘制 ----------------

    private fun prepareDecoder(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        streamW = w
        streamH = h
        val ht = HandlerThread("awei-remote-dec").apply { start() }
        readerThread = ht
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader = r
        // 内部面登记：ImageHook 的 acquire 抽帧对本 reader（远程帧解码渲入）是纯浪费，跳过
        com.awei.spoofer.xposed.hooks.Camera2Hook.registerInternalSurface(r.surface)
        r.setOnImageAvailableListener({ rd ->
            var img: android.media.Image? = null
            try {
                img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
                val bmp = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888)
                // rowStride 感知拷贝（1.1.17）：个别机型 RGBA 行跨度≠宽×4，整读画斜纹
                YuvUtil.copyRgbaPlane(img.planes[0], img.width, img.height, bmp)
                lastFrame = bmp // 旧帧交给 GC（拍照可能正持有）
                lastFrameAt = System.currentTimeMillis() // 看门狗：TCP/网络源共用
                drawAll(bmp)
            } catch (t: Throwable) {
            } finally {
                try {
                    img?.close()
                } catch (t: Throwable) {
                }
            }
        }, Handler(ht.looper))
        XLog.i("解码输出就绪 ${w}x${h}")
    }

    private fun feedDecoder(payload: ByteArray, isCsd: Boolean) {
        val dec = decoder ?: run {
            if (!isCsd) return
            if (streamW <= 0) {
                // 兼容未上报分辨率的旧远程端：解码输出会按码流实际尺寸缩放
                streamW = 1280
                streamH = 720
            }
            try {
                val fmt = MediaFormat.createVideoFormat("video/avc", streamW, streamH)
                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(payload))
                val d = MediaCodec.createDecoderByType("video/avc")
                d.configure(fmt, reader?.surface, null, 0)
                d.start()
                decoder = d
                XLog.i("H.264 解码器已启动")
            } catch (t: Throwable) {
                XLog.e("解码器启动失败: $t")
            }
            return
        }
        try {
            val idx = dec.dequeueInputBuffer(15_000)
            if (idx >= 0) {
                val buf = dec.getInputBuffer(idx)
                if (buf != null) {
                    buf.clear()
                    buf.put(payload)
                    val pts = if (isCsd) 0L else nextPtsUs
                    dec.queueInputBuffer(
                        idx, 0, payload.size, pts,
                        if (isCsd) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                    )
                    if (!isCsd) nextPtsUs += 1_000_000L / 30
                }
            }
        } catch (t: Throwable) {
        }
    }

    private fun teardownDecoder() {
        try {
            decoder?.stop()
        } catch (t: Throwable) {
        }
        try {
            decoder?.release()
        } catch (t: Throwable) {
        }
        decoder = null
        try {
            reader?.close()
        } catch (t: Throwable) {
        }
        reader = null
        try {
            readerThread?.quitSafely()
        } catch (t: Throwable) {
        }
        readerThread = null
        nextPtsUs = 0L
        lastFrameAt = 0L
        // 清掉陈旧帧：断线到重连期间拍照/绘制取旧帧会穿帮（时间/位置对不上）
        lastFrame = null
    }

    /** 最新帧绘制到全部输出面（center-crop 适配 + 可选镜像） */
    private fun drawAll(bmp: Bitmap) {
        if (outputs.isEmpty()) return
        val mirror = StreamManager.mirror
        for (s in outputs.keys) {
            if (!s.isValid) continue
            try {
                // EGL 首选（1.1.25）：GLConsumer 类目标面上 Canvas 会"投帧不上屏"（MI 6 实测）
                if (!eglDisabled.containsKey(s)) {
                    var fresh = false
                    var r = eglRenderers[s]
                    if (r == null) {
                        val nr = EglSurfaceRenderer(s, bmp, mirror)
                        if (nr.init()) {
                            eglRenderers[s] = nr
                            r = nr
                            fresh = true
                            XLog.i("远程源：输出面走 EGL14/GLES20 硬件渲染 ${nr.surfaceWidth}x${nr.surfaceHeight}")
                        } else {
                            nr.release()
                            eglDisabled[s] = true
                            XLog.w("远程源：输出面 EGL 初始化失败，该面降级 Canvas 兜底")
                        }
                    }
                    if (r != null) {
                        // 新建时 init 已上传首帧，免二次上传
                        val ok = (fresh || r.updateFrame(bmp, mirror)) && r.drawFrame()
                        if (ok) {
                            eglFailStreak.remove(s)
                            continue
                        }
                        // 供帧失败（面失效/驱动异常）：释放后下一帧重建；连续失败降级 Canvas 防空转
                        r.release()
                        eglRenderers.remove(s)
                        val streak = (eglFailStreak[s] ?: 0) + 1
                        eglFailStreak[s] = streak
                        if (streak >= 5) {
                            eglDisabled[s] = true
                            eglFailStreak.remove(s)
                            XLog.w("远程源：输出面 EGL 连续供帧失败，降级 Canvas 兜底")
                        }
                        continue
                    }
                }
                // Canvas 兜底（仅 EGL 不可用时走）
                val canvas = s.lockCanvas(null) ?: continue
                try {
                    val cw = canvas.width.toFloat()
                    val ch = canvas.height.toFloat()
                    if (cw <= 0 || ch <= 0) continue
                    // center-crop：短边撑满
                    val scale = maxOf(cw / bmp.width, ch / bmp.height)
                    val dw = bmp.width * scale
                    val dh = bmp.height * scale
                    val left = (cw - dw) / 2f
                    val top = (ch - dh) / 2f
                    if (mirror) {
                        canvas.save()
                        canvas.scale(-1f, 1f, cw / 2f, 0f)
                        canvas.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), null)
                        canvas.restore()
                    } else {
                        canvas.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), null)
                    }
                } finally {
                    try {
                        s.unlockCanvasAndPost(canvas)
                    } catch (t: Throwable) {
                    }
                }
            } catch (t: Throwable) {
            }
        }
    }
}
