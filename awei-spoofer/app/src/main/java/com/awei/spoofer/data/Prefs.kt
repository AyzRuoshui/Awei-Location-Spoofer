package com.awei.spoofer.data

import android.content.Context
import android.content.SharedPreferences

data class MockConfig(
    val mode: Int = MODE_FIXED,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val startLat: Double = 0.0,
    val startLng: Double = 0.0,
    val endLat: Double = 0.0,
    val endLng: Double = 0.0,
    // 默认慢跑 10km/h：项目以模拟步行/跑步为主（旧默认 40 是驾车档）
    val speedKmh: Float = 10f,
    val variableSpeed: Boolean = true,
    val loopRoute: Boolean = true,
    // 选点坐标是否为 GCJ-02 视觉值（高德瓦片源=true；ArcGIS/OSM=WGS-84 直接用）
    val gcjSource: Boolean = false,
) {
    companion object {
        const val MODE_FIXED = 0
        const val MODE_ROUTE = 1
    }
}

object Prefs {

    private fun sp(context: Context): SharedPreferences =
        context.getSharedPreferences("awei_spoofer", Context.MODE_PRIVATE)

    // ---- 地图选点 ----
    fun setFixedPoint(context: Context, lat: Double, lng: Double) {
        sp(context).edit().putString("fp", "$lat,$lng").apply()
        SpLogger.i("Map", "设置固定点 (%.5f, %.5f)".format(lat, lng))
    }

    fun getFixedPoint(context: Context): Pair<Double, Double>? {
        val s = sp(context).getString("fp", null) ?: return null
        val p = s.split(",")
        return Pair(p[0].toDoubleOrNull() ?: return null, p[1].toDoubleOrNull() ?: return null)
    }

    fun setRoutePoints(context: Context, start: Pair<Double, Double>, end: Pair<Double, Double>) {
        sp(context).edit()
            .putString("rp_start", "${start.first},${start.second}")
            .putString("rp_end", "${end.first},${end.second}").apply()
        SpLogger.i("Map", "设置起终点 (%.5f,%.5f)→(%.5f,%.5f)".format(start.first, start.second, end.first, end.second))
    }

    fun getRouteStart(context: Context): Pair<Double, Double>? {
        val s = sp(context).getString("rp_start", null) ?: return null
        val p = s.split(",")
        return Pair(p[0].toDoubleOrNull() ?: return null, p[1].toDoubleOrNull() ?: return null)
    }

    fun getRouteEnd(context: Context): Pair<Double, Double>? {
        val s = sp(context).getString("rp_end", null) ?: return null
        val p = s.split(",")
        return Pair(p[0].toDoubleOrNull() ?: return null, p[1].toDoubleOrNull() ?: return null)
    }

    // (0,0) 是长按设起点时的占位值，不算已设完终点（否则会规划"起点→几内亚湾"的跨球路线）
    private fun validPoint(p: Pair<Double, Double>?): Boolean =
        p != null && !(p.first == 0.0 && p.second == 0.0)

    fun hasRoute(context: Context): Boolean =
        validPoint(getRouteStart(context)) && validPoint(getRouteEnd(context))

    fun clearRoute(context: Context) {
        sp(context).edit().remove("rp_start").remove("rp_end").apply()
    }

    // ---- 轨迹参数 ----
    fun setSpeed(context: Context, kmh: Float) = sp(context).edit().putFloat("speed", kmh).apply()
    fun getSpeed(context: Context): Float = sp(context).getFloat("speed", 10f)
    fun setVariableSpeed(context: Context, v: Boolean) = sp(context).edit().putBoolean("variable", v).apply()
    fun getVariableSpeed(context: Context): Boolean = sp(context).getBoolean("variable", true)
    fun setLoop(context: Context, v: Boolean) = sp(context).edit().putBoolean("loop", v).apply()
    fun getLoop(context: Context): Boolean = sp(context).getBoolean("loop", true)

    // ---- 状态共享（Service <-> UI）----
    // 跨线程可见性：写方在轨迹 HandlerThread / 服务线程，读方是 UI 主线程 1s 轮询。
    // 全部 @Volatile（Double 在 32 位 ART 上非 volatile 理论允许撕裂，最坏一帧错坐标）
    @Volatile
    var isRunning: Boolean = false
    @Volatile
    var terminalHold: Boolean = false
    @Volatile
    var currentLat: Double = 0.0
        private set
    @Volatile
    var currentLng: Double = 0.0
        private set
    @Volatile
    var currentSpeedKmh: Float = 0f
        private set
    @Volatile
    var planningRoute: Boolean = false

    // ---- 移动开关（1.1.14 待命/起跑/暂停）----
    // holdAtMs>0 = 暂停/待命中：行进时长冻结在 (holdAtMs-run_start-holdAccumMs)；
    // 恢复时把本次暂停时长累入 holdAccumMs。两引擎经 TrackMath.elapsedMs 同一公式消费
    @Volatile
    var holdAtMs: Long = 0L
    @Volatile
    var holdAccumMs: Long = 0L

    fun setHoldState(context: Context, holdAt: Long, accum: Long) {
        holdAtMs = holdAt
        holdAccumMs = accum
        sp(context).edit().putLong("hold_at", holdAt).putLong("hold_accum", accum).apply()
    }

    fun loadHoldState(context: Context) {
        holdAtMs = sp(context).getLong("hold_at", 0L)
        holdAccumMs = sp(context).getLong("hold_accum", 0L)
    }

    // ---- 时间锚持久化（服务被杀 → STICKY 重启后按时间线续跑，不重走起点）----
    fun setRunStartAt(context: Context, ts: Long) =
        sp(context).edit().putLong("run_start_at", ts).apply()

    fun getRunStartAt(context: Context): Long = sp(context).getLong("run_start_at", 0L)

    fun clearRunStartAt(context: Context) = sp(context).edit().remove("run_start_at").apply()

    fun updateCurrent(lat: Double, lng: Double, speedKmh: Float) {
        currentLat = lat
        currentLng = lng
        currentSpeedKmh = speedKmh
    }

    fun buildConfig(context: Context): MockConfig? {
        val uiMode = sp(context).getInt("ui_mode", MockConfig.MODE_FIXED)
        // 只看 UI 模式开关：路线模式下起终点齐全用起终点，否则回退手绘
        // （此前误加 hasRoute 条件，而手绘保存时会清掉起终点，导致手绘永远进不去路线分支）
        val routeMode = uiMode == MockConfig.MODE_ROUTE
        // 高德瓦片源的选点坐标是 GCJ-02 视觉值，注入前需反算 WGS-84（否则被 SDK 二次加偏）
        val gcj = getMapSource(context) == 0
        return if (routeMode) {
            val s = getRouteStart(context)
            val e = getRouteEnd(context)
            if (s != null && e != null && validPoint(s) && validPoint(e)) {
                MockConfig(
                    mode = MockConfig.MODE_ROUTE, startLat = s.first, startLng = s.second,
                    endLat = e.first, endLng = e.second,
                    speedKmh = getSpeed(context),
                    variableSpeed = getVariableSpeed(context),
                    loopRoute = getLoop(context),
                    gcjSource = gcj
                )
            } else {
                // 起终点不完整时回退手绘路线（Service 层检测到手绘会直接使用）
                val drawn = getDrawnRoute(context) ?: return null
                MockConfig(
                    mode = MockConfig.MODE_ROUTE, startLat = drawn.first().first, startLng = drawn.first().second,
                    endLat = drawn.last().first, endLng = drawn.last().second,
                    speedKmh = getSpeed(context),
                    variableSpeed = getVariableSpeed(context),
                    loopRoute = getLoop(context),
                    gcjSource = gcj
                )
            }
        } else {
            val fp = getFixedPoint(context) ?: return null
            // 防御 (0,0) 无效点（海里）
            if (fp.first == 0.0 && fp.second == 0.0) return null
            MockConfig(mode = MockConfig.MODE_FIXED, lat = fp.first, lng = fp.second, speedKmh = getSpeed(context), gcjSource = gcj)
        }
    }

    fun saveUiMode(context: Context, mode: Int) = sp(context).edit().putInt("ui_mode", mode).apply()
    fun getUiMode(context: Context): Int = sp(context).getInt("ui_mode", MockConfig.MODE_FIXED)

    // ---- 地图源 ----
    fun setMapSource(context: Context, v: Int) = sp(context).edit().putInt("map_source", v).apply()
    fun getMapSource(context: Context): Int = sp(context).getInt("map_source", 0)

    // ---- 手绘轨迹 ----
    fun setDrawnRoute(context: Context, pts: List<Pair<Double, Double>>) {
        sp(context).edit()
            .putString("drawn_route", pts.joinToString("|") { "${it.first},${it.second}" })
            .apply()
        SpLogger.i("Map", "保存手绘路线 ${pts.size} 点")
    }

    fun getDrawnRoute(context: Context): List<Pair<Double, Double>>? {
        val s = sp(context).getString("drawn_route", null) ?: return null
        return s.split("|").mapNotNull { seg ->
            val xy = seg.split(",")
            val a = xy.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            val b = xy.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            Pair(a, b)
        }.takeIf { it.size >= 2 }
    }

    fun clearDrawnRoute(context: Context) {
        sp(context).edit().remove("drawn_route").apply()
    }
}
