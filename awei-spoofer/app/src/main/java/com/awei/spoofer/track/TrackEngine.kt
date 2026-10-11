package com.awei.spoofer.track

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import com.awei.spoofer.data.MockConfig
import com.awei.spoofer.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TrackEngine(private val context: Context) {

    // 专用后台线程：tick 不与地图渲染/主线程抢时间片，
    // 避免"卡住不动→恢复后位置猛跳（闪现/极快）"
    private var workerThread: android.os.HandlerThread? = null
    private val handler: Handler

    init {
        workerThread = android.os.HandlerThread("awei-track").apply { start() }
        handler = Handler(workerThread!!.looper)
    }

    /** 服务销毁时调用：退出 tick 线程（否则服务每重建一次泄漏一条线程） */
    fun destroy() {
        stop()
        try {
            workerThread?.quitSafely()
        } catch (t: Throwable) {
        }
        workerThread = null
    }

    // 1.1.14：seed 必须用会话时间锚 startAtMs（与 HookState 的 GpsJitter(c.startAt)
    // 同源同序列）——随机种子 = 两进程漂移序列不同 = 两 App 显示点错位
    private var jitter = GpsJitter(0L)
    private var running = false
    // 启动代数：start 可在 OSRM 规划窗口（最长 8s）内被再次调用，
    // 旧协程回调必须凭代数落地，否则双 tick 链并行（速度翻倍）+ 旧路线覆盖新路线
    private var startGen = 0L
    // 非 loop 路线到达终点后保持原地注入（true），不再推进里程——
    // "停在终点"才是用户对不循环路线的预期，走到终点就拆 mock 会让 App 瞬间跳回真实位置
    @Volatile
    private var terminalHold = false
    private var route: List<TrackMath.Pt> = emptyList()
    private var startAtMs = 0L
    private var cfg: MockConfig? = null

    var onPoint: ((Location) -> Unit)? = null
    var onRoutePlanned: ((Int, Long) -> Unit)? = null
    var onFinished: (() -> Unit)? = null

    val isRunning: Boolean get() = running
    var currentRoute: List<TrackMath.Pt> = emptyList()
        private set

    /**
     * startAtMs：时间锚。0=取当前时间；服务被杀后 STICKY 重启时传入持久化的
     * 原锚点，轨迹按时间线续跑（而不是从起点重走穿帮）。
     */
    fun start(config: MockConfig, startAtMs: Long = 0L) {
        stop()
        val gen = ++startGen
        cfg = config
        running = true
        terminalHold = false
        this.startAtMs = if (startAtMs > 0) startAtMs else System.currentTimeMillis()
        jitter = GpsJitter(this.startAtMs)
        if (config.mode == MockConfig.MODE_ROUTE) {
            Prefs.planningRoute = true
            CoroutineScope(Dispatchers.IO).launch {
                // OSRM 是 WGS-84 服务：高德源选点是 GCJ-02 视觉值，请求前反算；
                // ≤15km/h（步行/跑步）优先步行路网（不受单行道限制、可穿公园步道）
                val (sLat, sLng) = if (config.gcjSource) {
                    val (a, b) = CoordUtil.gcjToWgs(config.startLat, config.startLng); Pair(a, b)
                } else Pair(config.startLat, config.startLng)
                val (eLat, eLng) = if (config.gcjSource) {
                    val (a, b) = CoordUtil.gcjToWgs(config.endLat, config.endLng); Pair(a, b)
                } else Pair(config.endLat, config.endLng)
                val planned = RoutePlanner.plan(sLat, sLng, eLat, eLng, foot = config.speedKmh <= 15f)
                handler.post {
                    if (gen != startGen) return@post
                    if (!running || cfg?.mode != MockConfig.MODE_ROUTE) return@post
                    // 路线存储统一用视觉坐标（与地图绘制/选点一致），注入时再统一反算
                    route = TrackMath.densify(planned.map {
                        if (config.gcjSource) CoordUtil.wgsToGcj(it.lat, it.lng) else Pair(it.lat, it.lng)
                    })
                    currentRoute = route
                    Prefs.planningRoute = false
                    onRoutePlanned?.invoke(planned.size, startAtMs)
                    handler.postDelayed(tickRunnable, 1000)
                }
            }
        } else {
            handler.postDelayed(tickRunnable, 500)
        }
    }

    fun startWithRoute(points: List<Pair<Double, Double>>, config: MockConfig, startAtMs: Long = 0L) {
        stop()
        // 手绘路线无 OSRM 回调，但也要推进代数：作废此前 start() 还在途的规划回调
        ++startGen
        cfg = config
        running = true
        terminalHold = false
        this.startAtMs = if (startAtMs > 0) startAtMs else System.currentTimeMillis()
        jitter = GpsJitter(this.startAtMs)
        // 手绘路线无需联网规划，直接加密点后按路线模式运行
        route = TrackMath.densify(points)
        currentRoute = route
        onRoutePlanned?.invoke(points.size, this.startAtMs)
        handler.postDelayed(tickRunnable, 1000)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            val c = cfg ?: return
            // tick 是 2Hz 长期循环：单帧未预期异常不能带崩 HandlerThread（默认行为=杀进程），
            // catch 后照常排下一拍
            try {
                emit(c, now)
            } catch (t: Throwable) {
                com.awei.spoofer.data.SpLogger.e("Track", "tick 异常（跳过本帧）: ${t.javaClass.simpleName}")
            }
            // 2Hz 注入：GPS 源更"新鲜"，定位 SDK 的源选择/融合逻辑更倾向 GPS；
            // 终点保持阶段降到 0.5Hz（人已停下，无需高频刷新）
            handler.postDelayed(this, if (terminalHold || Prefs.holdAtMs > 0) 2000 else 500)
        }
    }

    private fun emit(c: MockConfig, nowMs: Long) {
        // 有效行进时长（1.1.14 hold 语义）：暂停/待命时冻结——与 HookState.current 同一公式
        val elapsed = TrackMath.elapsedMs(nowMs, startAtMs, Prefs.holdAtMs, Prefs.holdAccumMs) / 1000.0
        // 波形相位源 = startAt/1000（两引擎共享的时间锚）：不同会话速度/海拔曲线不重样
        val phase = startAtMs / 1000.0
        val baseMps = c.speedKmh / 3.6
        val acc = jitter.wanderAccuracy(tSec = elapsed)

        if (c.mode == MockConfig.MODE_FIXED) {
            val (dLat, dLng) = jitter.offsetMeters(6.0, c.lat) // 6.0 与 HookState 统一（两 App 显示点重合）
            // 视觉坐标（高德源=GCJ-02）：通知/状态显示用视觉值；注入前反算 WGS-84
            val vLat = c.lat + dLat
            val vLng = c.lng + dLng
            val (wLat, wLng) = if (c.gcjSource) CoordUtil.gcjToWgs(vLat, vLng) else Pair(vLat, vLng)
            val loc = Location("gps")
            loc.latitude = wLat
            loc.longitude = wLng
            loc.accuracy = acc.toFloat()
            loc.speed = 0f
            loc.bearing = 0f
            loc.altitude = TrackMath.altitudeAt(elapsed, phase)
            stamp(loc)
            Prefs.updateCurrent(vLat, vLng, 0f)
            onPoint?.invoke(loc)
        } else {
            if (route.isEmpty()) return
            val dist = TrackMath.distanceTraveled(baseMps, c.variableSpeed, elapsed, phase)
            val total = route.last().cumDistM
            // 1.1.16 线形单向（单向打卡场景）：首尾差距极大的"一条线"不回卷——单向跑完
            // 到终点原地保持（跑完打卡时位置留在终点区），判定压过 loop 开关；
            // 与 HookState.current 同一纯函数判定（两进程一致）
            val effectiveLoop = c.loopRoute && !TrackMath.isLinearRoute(route)
            if (!effectiveLoop && dist >= total) {
                // 到达终点：保持原地注入（速度 0），不再推进里程；仅首次通知 UI
                val pos = TrackMath.positionAt(route, total, loop = false)!!
                emitHold(pos, acc, c.gcjSource, elapsed, phase)
                if (!terminalHold) {
                    terminalHold = true
                    com.awei.spoofer.data.SpLogger.i(
                        "Track", "轨迹到达终点（总里程 %.0f 米），保持原地模拟".format(total)
                    )
                    onFinished?.invoke()
                }
                return
            }
            val pos = TrackMath.positionAt(route, dist, loop = effectiveLoop) ?: return
            val speedMps = TrackMath.speedAt(baseMps, c.variableSpeed, elapsed, phase)
            emitRoute(pos, speedMps, acc, c.gcjSource, elapsed, phase)
        }
    }

    private fun emitRoute(
        pos: Triple<Double, Double, Double>, speedMps: Double, acc: Double,
        gcjSource: Boolean, elapsed: Double, phase: Double,
    ) {
        val (dLat, dLng) = jitter.offsetMeters(6.0, pos.first) // 6.0 与 HookState 统一
        val vLat = pos.first + dLat
        val vLng = pos.second + dLng
        val (wLat, wLng) = if (gcjSource) CoordUtil.gcjToWgs(vLat, vLng) else Pair(vLat, vLng)
        val loc = Location("gps")
        loc.latitude = wLat
        loc.longitude = wLng
        loc.accuracy = acc.toFloat()
        loc.speed = jitter.jitterSpeed(speedMps).toFloat()
        loc.bearing = pos.third.toFloat()
        loc.altitude = TrackMath.altitudeAt(elapsed, phase)
        stamp(loc)
        Prefs.updateCurrent(vLat, vLng, loc.speed * 3.6f)
        onPoint?.invoke(loc)
    }

    /** 终点保持帧：位置钉在终点（带微小漂移），速度 0 */
    private fun emitHold(
        pos: Triple<Double, Double, Double>, acc: Double,
        gcjSource: Boolean, elapsed: Double, phase: Double,
    ) {
        val (dLat, dLng) = jitter.offsetMeters(6.0, pos.first) // 6.0 与 HookState 统一
        val vLat = pos.first + dLat
        val vLng = pos.second + dLng
        val (wLat, wLng) = if (gcjSource) CoordUtil.gcjToWgs(vLat, vLng) else Pair(vLat, vLng)
        val loc = Location("gps")
        loc.latitude = wLat
        loc.longitude = wLng
        loc.accuracy = acc.toFloat()
        loc.speed = 0f
        loc.bearing = pos.third.toFloat()
        loc.altitude = TrackMath.altitudeAt(elapsed, phase)
        stamp(loc)
        Prefs.updateCurrent(vLat, vLng, 0f)
        onPoint?.invoke(loc)
    }

    private fun stamp(loc: Location) {
        loc.time = System.currentTimeMillis()
        loc.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
    }

    fun stop() {
        running = false
        terminalHold = false
        Prefs.planningRoute = false
        handler.removeCallbacksAndMessages(null)
    }
}
