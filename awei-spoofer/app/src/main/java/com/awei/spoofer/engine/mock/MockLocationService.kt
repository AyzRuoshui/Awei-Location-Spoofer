package com.awei.spoofer.engine.mock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.awei.spoofer.R
import com.awei.spoofer.data.HookConfigStore
import com.awei.spoofer.data.MockConfig
import com.awei.spoofer.data.Prefs
import com.awei.spoofer.data.SpLogger
import com.awei.spoofer.track.TrackEngine
import com.awei.spoofer.ui.FloatWindowController
import com.awei.spoofer.ui.MainActivity

/**
 * 模拟服务（满血版唯一入口）：跑 TrackEngine 产生轨迹帧，经 HookConfigStore 下发给
 * Hook 进程执行（Xposed 注入在目标 App 内完成），同时 MockLocationEngine 做
 * test provider 系统级兜底（1.1.10 恢复的双路保障）。1.1.14 起路线模式启动默认
 * 待命（原地保持），起跑/暂停由浮窗或轨迹页按钮控制（ACTION_TOGGLE_HOLD）。
 */
class MockLocationService : Service() {

    private lateinit var engine: TrackEngine
    private var activeCfg: MockConfig? = null
    // 本次运行的时间锚（TrackEngine startAt / Hook startAt 同源；被杀重启时恢复续跑）
    private var startAtAnchor = 0L
    // 上次正常启动的时刻（elapsedRealtime），启动去抖用
    private var lastStartElapsed = 0L
    // 通知节流（1.1.16）：2Hz 全量 notify 无谓耗电/刷 binder，1s 足够
    private var lastNotifAt = 0L

    override fun onCreate() {
        super.onCreate()
        hasInstance = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        engine = TrackEngine(this)
        engine.onPoint = { loc ->
            // 系统层面注入 mock provider（兜底）：即使 Hook 没注入到目标 App 进程，
            // 微信等 App 通过系统 LocationManager 仍能读到伪造位置。
            // 双路保障：mock provider（系统层面）+ Hook（进程内覆盖）
            MockLocationEngine.injectAll(
                this, loc.latitude, loc.longitude,
                loc.speed, loc.bearing, loc.accuracy, loc.altitude
            )
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastNotifAt >= 1000L) {
                lastNotifAt = now
                updateNotification()
            }
        }
        engine.onRoutePlanned = { _, startAt ->
            SpLogger.i("Track", "路线就绪 ${engine.currentRoute.size} 点，开始行进")
            activeCfg?.let { cfg ->
                HookConfigStore.save(
                    this, enabled = true, mode = MockConfig.MODE_ROUTE,
                    lat = cfg.lat, lng = cfg.lng,
                    routeRaw = engine.currentRoute.map { Pair(it.lat, it.lng) },
                    speedKmh = cfg.speedKmh, variable = cfg.variableSpeed,
                    loop = cfg.loopRoute, startAt = startAt,
                    gcj = cfg.gcjSource
                )
            }
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
                SpLogger.i("Mock", "用户停止模拟")
                engine.stop()
                MockLocationEngine.removeProviders(this)
                disableHookConfig()
                Prefs.isRunning = false
                Prefs.terminalHold = false
                Prefs.clearRunStartAt(this)
                Prefs.setHoldState(this, 0L, 0L)
                FloatWindowController.hide(this)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_HOLD -> {
                // 1.1.14 移动开关：待命/暂停=原地保持（elapsed 冻结），起跑/继续=推进。
                // 状态写 Prefs + 重下发 Hook 配置（HookConfigStore.save 内置 notifyChange
                // 即时推送），浮窗与轨迹页按钮共用本入口
                if (!Prefs.isRunning) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val now = System.currentTimeMillis()
                if (Prefs.holdAtMs > 0) {
                    val pausedMs = now - Prefs.holdAtMs
                    Prefs.setHoldState(this, 0L, Prefs.holdAccumMs + pausedMs)
                    SpLogger.i("Mock", "起跑/继续（本次待命 ${pausedMs / 1000}s）")
                } else {
                    Prefs.setHoldState(this, now, Prefs.holdAccumMs)
                    SpLogger.i("Mock", "暂停——原地保持（再点起跑继续）")
                }
                activeCfg?.let { saveHookConfig(it) }
                updateNotification()
                FloatWindowController.refresh()
                return START_STICKY
            }
            else -> {
                // 双启动守卫：1.2s 内重复 start（如启动按钮连点/双触发）会重跑
                // su 授权 + 重建 provider + 重置时间锚，轨迹跳变、日志重复
                val now = android.os.SystemClock.elapsedRealtime()
                if (hasInstance && Prefs.isRunning && now - lastStartElapsed < 1200L) {
                    SpLogger.i("Mock", "忽略重复启动（距上次启动 ${now - lastStartElapsed}ms）")
                    return START_STICKY
                }
                lastStartElapsed = now
                Prefs.loadHoldState(this) // 恢复持久化的待命/暂停状态（进程重建后内存清零）
                val cfg = Prefs.buildConfig(this)
                if (cfg == null) {
                    rejectStart("启动被拒: 未选择目标位置（先去地图页选点）")
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
                activeCfg = cfg
                if (!startFg()) return START_NOT_STICKY
                // Root 自动授权 mock_location + 注册 test provider（系统层面兜底）：
                // 即使 Hook 没注入到目标 App（微信有加固 / LSPosed 隐藏版问题），
                // 系统层面的 mock provider 仍让目标 App 通过 LocationManager 读到伪造位置。
                // 1.1.16 移后台线程：su 在 Root 设备上可能弹 Magisk 授权框阻塞 waitFor——
                // onStartCommand 跑在主线程会 ANR；injectAll 对尚未注册的 provider 本就
                // 跳过，前几帧不注入无影响
                Thread {
                    try {
                        MockLocationEngine.ensureMockPermission(this)
                        MockLocationEngine.setupProviders(this)
                    } catch (t: Throwable) {
                        // 注册线程在后台裸跑：异常会无声杀死线程，注册结果、浮窗刷新
                        // 全部失踪——旧版这里零兜底，通道死了连一条日志都没有
                        SpLogger.e("Mock", "test provider 注册线程异常: ${t.javaClass.simpleName}: ${t.message}")
                    }
                    // 注册结果落定后刷浮窗（全失败→红字告警）：注册线程可能早于
                    // 浮窗 show() 完成，靠刷新而非一次性推送才能无视时序竞态
                    FloatWindowController.refresh()
                }.start()
                Prefs.isRunning = true
                Prefs.terminalHold = false
                // 浮窗移动开关（1.1.14）：有"上层显示"权限即常驻；无权限时轨迹页按钮备用
                if (cfg.mode == MockConfig.MODE_ROUTE) {
                    SpLogger.i(
                        "Mock",
                        "浮窗: " + if (FloatWindowController.show(this)) "已显示" else "未授权/失败（用轨迹页「起跑/暂停」按钮）"
                    )
                }
                SpLogger.i("Mock", "服务启动 模式=${if (cfg.mode == MockConfig.MODE_ROUTE) "轨迹" else "固定点"} " +
                    "speed=${cfg.speedKmh}km/h variable=${cfg.variableSpeed} loop=${cfg.loopRoute}")
                // 1.1.14 路线模式启动默认待命：钉在起点静止（速度 0），起跑由浮窗/轨迹页
                // 按钮控制——解决"先开目标 App 再开定位器=原地瞬移到目标点 / 先开定位器=
                // 没起跑就在动"的时序死结。被杀重启恢复沿用持久化的 hold 状态
                if (cfg.mode == MockConfig.MODE_ROUTE && !restartRestore) {
                    Prefs.setHoldState(this, startAtAnchor, 0L)
                    SpLogger.i("Mock", "路线模式待命中（原地保持）——点浮窗/「起跑」开始行进")
                }
                if (cfg.mode == MockConfig.MODE_ROUTE) {
                    // 手绘路线只在"起终点未设全"时使用（与 Prefs.buildConfig 的回退
                    // 优先级一致）——否则画过一次手绘后，用户再设起终点想走路网规划
                    // 会被旧手绘静默覆盖（表现：怎么改起终点都走老路线）
                    val drawn = if (!Prefs.hasRoute(this)) Prefs.getDrawnRoute(this) else null
                    if (drawn != null) {
                        SpLogger.i("Track", "使用手绘路线 ${drawn.size} 点")
                        engine.startWithRoute(drawn, cfg, startAtAnchor)
                    } else {
                        SpLogger.i("Track", "OSRM 联网规划起点(%.5f,%.5f)→终点(%.5f,%.5f)".format(cfg.startLat, cfg.startLng, cfg.endLat, cfg.endLng))
                        engine.start(cfg, startAtAnchor)
                    }
                } else {
                    SpLogger.i("Track", "固定点(%.5f,%.5f)".format(cfg.lat, cfg.lng))
                    engine.start(cfg, startAtAnchor)
                    // 固定点模式立即下发；路线模式等 onRoutePlanned 回调携带完整 polyline 后下发，
                    // 避免 Hook 进程在规划完成前按起终点直线插值造成位置跳变
                    saveHookConfig(cfg)
                }
            }
        }
        return START_STICKY
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
            // FGS 类型/权限校验失败等，停止服务避免 5 秒强制崩溃；失败原因上浮日志，
            // 否则用户只看到"没反应"
            SpLogger.e("Mock", "前台服务启动失败: $t")
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
            Prefs.holdAtMs > 0 -> "⏸ 待命/暂停中（原地保持）——点浮窗或轨迹页起跑"
            Prefs.terminalHold -> "已到达终点，保持原地模拟"
            Prefs.planningRoute -> "路线规划中..."
            else -> "%.5f, %.5f  %.0f km/h".format(Prefs.currentLat, Prefs.currentLng, Prefs.currentSpeedKmh)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun saveHookConfig(cfg: MockConfig) {
        val routeRaw = if (cfg.mode == MockConfig.MODE_ROUTE)
            engine.currentRoute.map { Pair(it.lat, it.lng) } else emptyList()
        HookConfigStore.save(
            this, enabled = true, mode = cfg.mode, lat = cfg.lat, lng = cfg.lng,
            routeRaw = routeRaw, speedKmh = cfg.speedKmh,
            variable = cfg.variableSpeed, loop = cfg.loopRoute,
            startAt = startAtAnchor,
            gcj = cfg.gcjSource
        )
    }

    private fun disableHookConfig() {
        // 注（1.1.16）：ACTION_STOP 路径先调本函数、后 setHoldState(0,0)，disabled JSON
        // 会带上尚未清零的旧 holdAt 值——enabled=false 下 HookState 不产出位置，无害
        val cfg = activeCfg
        HookConfigStore.save(
            this, enabled = false, mode = cfg?.mode ?: MockConfig.MODE_FIXED,
            lat = cfg?.lat ?: 0.0, lng = cfg?.lng ?: 0.0,
            routeRaw = emptyList(), speedKmh = cfg?.speedKmh ?: 10f,
            variable = false, loop = true
        )
    }

    override fun onDestroy() {
        engine.stop()
        engine.destroy()
        MockLocationEngine.removeProviders(this)
        disableHookConfig()
        Prefs.isRunning = false
        Prefs.terminalHold = false
        Prefs.setHoldState(this, 0L, 0L)
        FloatWindowController.hide(this)
        SpLogger.i("Mock", "服务销毁")
        hasInstance = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "mock_location"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.awei.spoofer.STOP"
        const val ACTION_TOGGLE_HOLD = "com.awei.spoofer.TOGGLE_HOLD"

        @Volatile
        var hasInstance: Boolean = false
            private set

        fun start(context: Context) {
            // ContextCompat 版本：startForegroundService 是 API 26 引入，minSdk 21 下
            // Android 5.x-7.x 直接调用会 NoSuchMethodError（低版本 startService 即前台语义）
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, MockLocationService::class.java)
            )
        }

        /** 移动开关（1.1.14）：待命 ↔ 起跑/暂停 切换（浮窗与轨迹页按钮共用） */
        fun toggleHold(context: Context) {
            val i = Intent(context, MockLocationService::class.java).setAction(ACTION_TOGGLE_HOLD)
            try {
                context.startService(i)
            } catch (e: Exception) {
            }
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
