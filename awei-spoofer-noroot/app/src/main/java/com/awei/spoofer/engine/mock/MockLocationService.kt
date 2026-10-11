package com.awei.spoofer.engine.mock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.awei.spoofer.R
import com.awei.spoofer.data.MockConfig
import com.awei.spoofer.data.Prefs
import com.awei.spoofer.data.SpLogger
import com.awei.spoofer.track.TrackEngine
import com.awei.spoofer.ui.MainActivity

class MockLocationService : Service() {

    private lateinit var engine: TrackEngine
    // 本次运行的时间锚（TrackEngine startAt 同源；被杀重启时恢复续跑）
    private var startAtAnchor = 0L

    override fun onCreate() {
        super.onCreate()
        hasInstance = true
        sInstance = this
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        engine = TrackEngine(this)
        engine.onPoint = { loc ->
            // 注入到所有 mock provider（gps/network/fused/passive），
            // 覆盖 App 的融合定位请求
            MockLocationEngine.injectAll(
                this, loc.latitude, loc.longitude,
                loc.speed, loc.bearing, loc.accuracy, loc.altitude
            )
            // 状态展示用视觉坐标：TrackEngine.emit 已写入（高德源=GCJ-02，与地图选点
            // 同系），这里不再用 WGS 覆盖——否则用户对照高德地图会误判"模拟失败"
            updateNotification()
        }
        engine.onRoutePlanned = { _, _ ->
            SpLogger.i("Track", "路线就绪 ${engine.currentRoute.size} 点，开始行进")
            scheduleSelfCheck()
        }
        engine.onFinished = {
            // 非 loop 路线到达终点：保持原地继续模拟（引擎已切终点保持帧），
            // 不再 stopSelf——走到终点就拆 mock 会让目标 App 瞬间跳回真实位置
            SpLogger.i("Track", "到达终点，保持原地模拟（点停止结束）")
            Prefs.terminalHold = true
            updateNotification()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopped = true
                SpLogger.i("Mock", "用户停止模拟")
                engine.stop()
                Prefs.isRunning = false
                Prefs.terminalHold = false
                Prefs.clearRunStartAt(this)
                resetSelfCheck()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                stopped = false
                val cfg = Prefs.buildConfig(this)
                if (cfg == null) {
                    rejectStart("启动被拒: 未选择目标位置（先去地图页选点）")
                    return START_NOT_STICKY
                }
                if (!MockLocationEngine.isMockLocationEnabled(this)) {
                    // 免Root版硬前置：没有模拟位置授权无法注入
                    rejectStart("启动被拒: 未选模拟位置应用（开发者选项→模拟位置信息应用→选本 App）")
                    return START_NOT_STICKY
                }
                // 服务被杀 → STICKY 重启（intent==null）：恢复持久化的原时间锚，
                // 轨迹按时间线续跑，而不是回到起点重走（重启前后位置对得上）。
                // 恢复信号必须用持久化的 run_start_at：isRunning 是内存静态，
                // 进程被杀后必为 false，用它判断恢复分支永远进不去；
                // 用户主动停止已 clearRunStartAt，故"锚存在+intent==null"只会是被杀重启
                val restartRestore = intent == null && Prefs.getRunStartAt(this) > 0
                startAtAnchor = if (restartRestore) {
                    SpLogger.i("Mock", "服务被系统重启，恢复时间锚续跑（不重走起点）")
                    Prefs.getRunStartAt(this)
                } else {
                    System.currentTimeMillis().also { Prefs.setRunStartAt(this, it) }
                }
                if (!startFg()) return START_NOT_STICKY
                Prefs.isRunning = true
                Prefs.terminalHold = false
                MockLocationEngine.setupProviders(this)
                val modeName = when (cfg.mode) {
                    MockConfig.MODE_ROUTE -> "轨迹"
                    MockConfig.MODE_JOYSTICK -> "摇杆"
                    else -> "固定点"
                }
                SpLogger.i("Mock", "服务启动 模式=$modeName speed=${cfg.speedKmh}km/h variable=${cfg.variableSpeed} loop=${cfg.loopRoute}")
                if (cfg.mode == MockConfig.MODE_JOYSTICK) {
                    SpLogger.i("Track", "摇杆模式：位置累加器首次 tick 从当前模拟位置/固定点初始化")
                    Prefs.clearJoystickVec()
                    engine.start(cfg, startAtAnchor)
                    scheduleSelfCheck()
                } else if (cfg.mode == MockConfig.MODE_ROUTE) {
                    // 手绘路线只在"起终点未设全"时使用（与 Prefs.buildConfig 的回退
                    // 优先级一致）——否则画过一次手绘后，用户再设起终点想走路网规划
                    // 会被旧手绘静默覆盖（表现：怎么改起终点都走老路线）
                    val drawn = if (!Prefs.hasRoute(this)) Prefs.getDrawnRoute(this) else null
                    if (drawn != null) {
                        SpLogger.i("Track", "使用手绘路线 ${drawn.size} 点")
                        // startWithRoute 同步触发 onRoutePlanned→自检已启动；
                        // 不再覆盖"路线规划中"文案（手绘没有规划阶段，误导用户）
                        engine.startWithRoute(drawn, cfg, startAtAnchor)
                    } else {
                        SpLogger.i("Track", "OSRM 联网规划起点(%.5f,%.5f)→终点(%.5f,%.5f)".format(cfg.startLat, cfg.startLng, cfg.endLat, cfg.endLng))
                        engine.start(cfg, startAtAnchor)
                        // 路线模式不在此处自检：规划期间零注入会让自检必败误报，
                        // 延后到 onRoutePlanned 触发
                        selfCheckState = SELF_RUNNING
                        selfCheckText = "路线规划中，完成后自检…"
                    }
                } else {
                    SpLogger.i("Track", "固定点(%.5f,%.5f)".format(cfg.lat, cfg.lng))
                    engine.start(cfg, startAtAnchor)
                    scheduleSelfCheck()
                }
            }
        }
        return START_STICKY
    }

    /** 端到端自检：本 App 请求系统定位，验证 mock 链路是否真正打通（结果进日志 + companion 状态供 UI）。
     *  用代数守卫防止竞态：用户在超时窗口内点"重新体检"时，旧一轮的超时/回调不得覆盖新一轮结果 */
    private var selfCheckGen = 0
    // 服务停止/销毁标志：在途的 3s/10s 延时任务据此作废
    //（resetSelfCheck 只 ++gen，但 selfCheck 入口 ++gen 使 gen==selfCheckGen 再次为真，
    // 无法阻止延时任务执行——stopped 标志在 selfCheck 入口直接 return）
    @Volatile
    private var stopped = false

    /** 3 秒后端到端自检：用本 App 自己请求系统定位，验证 mock 链路是否真正打通。 */
    private fun scheduleSelfCheck() {
        selfCheckState = SELF_RUNNING
        selfCheckText = "自检中…（启动后约 5~15 秒出结果）"
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            selfCheck()
        }, 3000)
    }

    private fun selfCheck() {
        if (stopped) return // 服务已停止/销毁，在途延时任务作废
        val gen = ++selfCheckGen
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            for (p in arrayOf("gps", "network", "fused")) {
                val last = try {
                    lm.getLastKnownLocation(p)
                } catch (t: Throwable) {
                    null
                }
                SpLogger.i("SelfCheck", "getLastKnownLocation($p)=" +
                    (last?.let { "(%.5f,%.5f)".format(it.latitude, it.longitude) } ?: "null"))
            }
            // 注入参考值（WGS-84）：读回坐标应与其一致（与地图选点差几百米是坐标系正常差异）
            SpLogger.i("SelfCheck", "注入参考(WGS-84): ${MockLocationEngine.lastInjectedWgs}")
            // minSdk 21：Context.checkSelfPermission 是 API 23+，低版本直接 NoSuchMethodError
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                SpLogger.w("SelfCheck", "无定位权限，跳过实时自检")
                if (gen == selfCheckGen) {
                    selfCheckState = SELF_FAIL
                    selfCheckText = "无定位权限，自检跳过（先完成上面的定位权限）"
                }
                return
            }
            var received = false
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(l: android.location.Location) {
                    if (received) return
                    if (gen != selfCheckGen) {
                        // 已被新一轮自检取代：注销自己，不写状态
                        try { lm.removeUpdates(this) } catch (t: Throwable) {}
                        return
                    }
                    received = true
                    SpLogger.i("SelfCheck",
                        "✅ 链路打通：系统回调 (%.5f,%.5f) provider=${l.provider}".format(l.latitude, l.longitude))
                    selfCheckState = SELF_OK
                    selfCheckText = "链路打通，系统已能读回模拟位置"
                    try { lm.removeUpdates(this) } catch (t: Throwable) {}
                }
                override fun onProviderDisabled(provider: String) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            }
            lm.requestLocationUpdates("gps", 1000, 0f, listener, android.os.Looper.getMainLooper())
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!received && gen == selfCheckGen) {
                    SpLogger.e("SelfCheck", "❌ 10 秒内系统未回调 GPS 位置——test provider 链路未打通，把日志发给开发者")
                    selfCheckState = SELF_FAIL
                    selfCheckText = "10 秒未读回模拟位置，链路未打通（可点「重新体检」重试，仍失败请导日志）"
                }
                try { lm.removeUpdates(listener) } catch (t: Throwable) {}
            }, 10000)
        } catch (t: Throwable) {
            SpLogger.e("SelfCheck", "自检异常: $t")
            if (gen == selfCheckGen) {
                selfCheckState = SELF_FAIL
                selfCheckText = "自检异常: ${t.javaClass.simpleName}"
            }
        }
    }

    private fun resetSelfCheck() {
        // 作废在途的 3s/10s 延时任务：停止/销毁后不得再写状态或重挂定位监听器
        selfCheckGen++
        selfCheckState = SELF_IDLE
        selfCheckText = ""
    }

    /**
     * startForegroundService 契约保护：派发后必须 startForeground 一次，直接 stopSelf
     * 在 API 26-30 上会触发 "did not then call startForeground" 崩溃。拒启路径先挂
     * 最小通知再停（挂失败只剩 stopSelf——Android 12+ 对该场景已豁免）
     */
    private fun rejectStart(reason: String) {
        SpLogger.w("Mock", reason)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIF_ID, buildNotification())
            }
        } catch (t: Throwable) {
        }
        stopSelf()
    }

    private fun startFg(): Boolean {
        return try {
            val notif = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            true
        } catch (t: Throwable) {
            // FGS 类型/权限校验失败等，停止服务避免 5 秒强制崩溃；失败原因上浮日志与自检状态，
            // 否则用户只看到"没反应"
            SpLogger.e("Mock", "前台服务启动失败: $t")
            selfCheckState = SELF_FAIL
            selfCheckText = "前台服务启动失败: ${t.javaClass.simpleName}"
            stopSelf()
            false
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(notifText())
            .setOngoing(true)
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun notifText(): String {
        return when {
            Prefs.terminalHold -> "已到达终点，保持原地模拟"
            Prefs.planningRoute -> "路线规划中..."
            else -> "%.5f, %.5f  %.0f km/h".format(Prefs.currentLat, Prefs.currentLng, Prefs.currentSpeedKmh)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    override fun onDestroy() {
        stopped = true
        engine.stop()
        engine.destroy()
        MockLocationEngine.removeProviders(this)
        Prefs.isRunning = false
        Prefs.terminalHold = false
        Prefs.clearJoystickVec()
        resetSelfCheck()
        SpLogger.i("Mock", "服务销毁，test provider 已卸载")
        hasInstance = false
        sInstance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "mock_location"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.awei.spoofer.noroot.STOP"

        @Volatile
        var hasInstance: Boolean = false
            private set

        // ---- 端到端自检状态（UI 体检页轮询展示）----
        const val SELF_IDLE = 0
        const val SELF_RUNNING = 1
        const val SELF_OK = 2
        const val SELF_FAIL = 3

        @Volatile
        var selfCheckState: Int = SELF_IDLE
            private set
        @Volatile
        var selfCheckText: String = ""
            private set

        @Volatile
        private var sInstance: MockLocationService? = null

        /** UI"重新体检"：服务在跑时重跑一次自检（未运行则无操作，由 UI 提示先启动） */
        fun rerunSelfCheck() {
            val s = sInstance ?: return
            // 路线规划窗口内零注入，此时自检必超时误报"链路未打通"
            //（初始流程专门把自检延后到规划完成，手动重测同样要守这条规则）
            if (Prefs.planningRoute) {
                selfCheckState = SELF_RUNNING
                selfCheckText = "路线规划中，完成后自动自检…"
                return
            }
            selfCheckState = SELF_RUNNING
            selfCheckText = "自检中…"
            android.os.Handler(android.os.Looper.getMainLooper()).post { s.selfCheck() }
        }

        fun start(context: Context) {
            // ContextCompat 版本：startForegroundService 是 API 26 引入，minSdk 21 下
            // Android 5.x-7.x 直接调用会 NoSuchMethodError（低版本 startService 即前台语义）
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, MockLocationService::class.java)
            )
        }

        fun stop(context: Context) {
            val i = Intent(context, MockLocationService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(i)
            } catch (e: Exception) {
            }
        }
    }
}
