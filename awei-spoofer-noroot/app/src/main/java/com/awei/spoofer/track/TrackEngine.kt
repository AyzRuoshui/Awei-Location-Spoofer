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

    private val jitter = GpsJitter()
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
    // 摇杆模式位置累加器：视觉坐标系（与 Prefs.currentLat 同系），首次 tick 时初始化
    private var joyLat = 0.0
    private var joyLng = 0.0
    private var joyInited = false

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
            handler.postDelayed(this, if (terminalHold) 2000 else 500)
        }
    }

    private fun emit(c: MockConfig, nowMs: Long) {
        val elapsed = (nowMs - startAtMs) / 1000.0
        // 波形相位源 = startAt/1000（两引擎共享的时间锚）：不同会话速度/海拔曲线不重样
        val phase = startAtMs / 1000.0
        val baseMps = c.speedKmh / 3.6
        val acc = jitter.wanderAccuracy(tSec = elapsed)

        if (c.mode == MockConfig.MODE_JOYSTICK) {
            if (!joyInited) {
                // 初始化：优先用当前模拟位置（续跑/摇杆切回时无缝），其次固定点，其次默认北京
                joyLat = if (Prefs.currentLat != 0.0) Prefs.currentLat
                    else Prefs.getFixedPoint(context)?.first ?: 39.9042
                joyLng = if (Prefs.currentLng != 0.0) Prefs.currentLng
                    else Prefs.getFixedPoint(context)?.second ?: 116.4074
                joyInited = true
            }
            val (angle, mag) = Prefs.joystickVec ?: (0f to 0f)
            val dist = baseMps * 0.5 * mag  // 500ms tick
            if (dist > 0) {
                val (nLat, nLng) = TrackMath.stepFrom(joyLat, joyLng, angle.toDouble(), dist)
                joyLat = nLat
                joyLng = nLng
            }
            // 抖动跟速度关联：摇杆松手（mag=0）抖动最小，满推抖动大
            val (dLat, dLng) = jitter.offsetMeters(acc, joyLat, baseMps * mag)
            val vLat = joyLat + dLat
            val vLng = joyLng + dLng
            // 视觉坐标（高德源=GCJ-02）：注入前反算 WGS-84
            val (wLat, wLng) = if (c.gcjSource) CoordUtil.gcjToWgs(vLat, vLng) else Pair(vLat, vLng)
            val loc = Location("gps")
            loc.latitude = wLat
            loc.longitude = wLng
            loc.accuracy = acc.toFloat()
            loc.speed = (baseMps * mag).toFloat()
            loc.bearing = angle
            // 摇杆推进时海拔满幅，松手时趋零（与运动态一致）
            loc.altitude = TrackMath.altitudeAt(elapsed, phase, moving = mag > 0)
            stamp(loc)
            Prefs.updateCurrent(vLat, vLng, c.speedKmh * mag)
            onPoint?.invoke(loc)
            return
        }

        if (c.mode == MockConfig.MODE_FIXED) {
            val (dLat, dLng) = jitter.offsetMeters(acc, c.lat, 0.0)
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
            // 静止态海拔幅度 ×0.1（FIXED 静止点吃满 ±5.5m 是主动送检）
            loc.altitude = TrackMath.altitudeAt(elapsed, phase, moving = false)
            stamp(loc)
            Prefs.updateCurrent(vLat, vLng, 0f)
            onPoint?.invoke(loc)
        } else {
            if (route.isEmpty()) return
            val total = route.last().cumDistM
            // 1.0.12 线形单向（单向打卡场景，同步自满血版 1.1.16）：首尾差距极大的"一条线"
            // 不回卷——单向跑完到终点原地保持（跑完打卡时位置留在终点区），判定压过 loop 开关
            val effectiveLoop = c.loopRoute && !TrackMath.isLinearRoute(route)
            // 预计算总时间（非 effectiveLoop）：RAMP_S/2 补偿斜坡段里程损失，减速段在终点前 DECEL_S 秒生效
            val totalTime = if (effectiveLoop) 0.0 else TrackMath.RAMP_S / 2.0 + total / baseMps
            val dist = TrackMath.distanceTraveled(baseMps, c.variableSpeed, elapsed, phase, totalTime)
            // 到达终点（含 totalTime 预算偏小时兜底：减速段结束但 dist 未到 total——
            // 钳到 total 进 emitHold，位置最后跳 ≤几米，但速度已降到 0，不突停）
            if (!effectiveLoop && (dist >= total || (totalTime > 0 && elapsed >= totalTime))) {
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
            val speedMps = TrackMath.speedAt(baseMps, c.variableSpeed, elapsed, phase, totalTime)
            emitRoute(pos, speedMps, acc, c.gcjSource, elapsed, phase)
        }
    }

    private fun emitRoute(
        pos: Triple<Double, Double, Double>, speedMps: Double, acc: Double,
        gcjSource: Boolean, elapsed: Double, phase: Double,
    ) {
        val (dLat, dLng) = jitter.offsetMeters(acc, pos.first, speedMps)
        val vLat = pos.first + dLat
        val vLng = pos.second + dLng
        val (wLat, wLng) = if (gcjSource) CoordUtil.gcjToWgs(vLat, vLng) else Pair(vLat, vLng)
        val loc = Location("gps")
        loc.latitude = wLat
        loc.longitude = wLng
        loc.accuracy = acc.toFloat()
        loc.speed = jitter.jitterSpeed(speedMps).toFloat()
        // 航向自然摆动：零方差+段间阶梯是纯几何合成的强特征，加 AR(1) 随机游走
        loc.bearing = jitter.bearingJitter(pos.third.toFloat(), speedMps)
        loc.altitude = TrackMath.altitudeAt(elapsed, phase, moving = true)
        stamp(loc)
        Prefs.updateCurrent(vLat, vLng, loc.speed * 3.6f)
        onPoint?.invoke(loc)
    }

    /** 终点保持帧：位置钉在终点（带微小漂移），速度 0 */
    private fun emitHold(
        pos: Triple<Double, Double, Double>, acc: Double,
        gcjSource: Boolean, elapsed: Double, phase: Double,
    ) {
        val (dLat, dLng) = jitter.offsetMeters(acc, pos.first, 0.0)
        val vLat = pos.first + dLat
        val vLng = pos.second + dLng
        val (wLat, wLng) = if (gcjSource) CoordUtil.gcjToWgs(vLat, vLng) else Pair(vLat, vLng)
        val loc = Location("gps")
        loc.latitude = wLat
        loc.longitude = wLng
        loc.accuracy = acc.toFloat()
        loc.speed = 0f
        loc.bearing = pos.third.toFloat()
        // 静止态海拔幅度 ×0.1（仍有微噪声防"恒定海拔"穿帮，但不吃满 ±5.5m 主动送检）
        loc.altitude = TrackMath.altitudeAt(elapsed, phase, moving = false)
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
        joyInited = false
        handler.removeCallbacksAndMessages(null)
    }
}
