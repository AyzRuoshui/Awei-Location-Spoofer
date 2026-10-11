package com.awei.spoofer.track

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class RoutePoint(val lat: Double, val lng: Double, val cumDistM: Double, val bearing: Float)

/** 生成的跑步环线：视觉坐标系点列 + 实际总里程（路网拟合后与设定值有偏差，UI 需如实展示） */
data class LoopRoute(val points: List<Pair<Double, Double>>, val totalM: Double)

object RoutePlanner {

    /**
     * 两点路线规划。foot=true 走步行路网（跑步/步行场景更真实：不受单行道限制、
     * 会穿公园步道），失败自动降级 driving 再降级直线（回退会写日志，调用方应
     * 把降级状态透出给用户）。
     */
    suspend fun plan(
        startLat: Double, startLng: Double, endLat: Double, endLng: Double,
        foot: Boolean = false,
    ): List<RoutePoint> =
        withContext(Dispatchers.IO) {
            val profiles = if (foot) listOf("foot", "driving") else listOf("driving")
            for (p in profiles) {
                try {
                    return@withContext densify(
                        viaOsrm(listOf(Pair(startLat, startLng), Pair(endLat, endLng)), p)
                    )
                } catch (e: Exception) {
                    com.awei.spoofer.data.SpLogger.w(
                        "Track", "OSRM $p 档规划失败(${e.javaClass.simpleName}: ${e.message ?: ""})"
                    )
                }
            }
            com.awei.spoofer.data.SpLogger.w("Track", "道路规划全部失败，回退直线（穿行路径，打卡场景慎用）")
            straightLine(startLat, startLng, endLat, endLng)
        }

    /**
     * 一键跑步环线：以中心点生成带抖动的圆周航点（8 点，半径/角度抖动模拟
     * 真实环线的自然不规则），按路网拟合成沿道路的闭环。
     * **里程拟合**：城市路网的实际里程≈几何圆周的 2.2~5 倍且逐点吸附混沌（实测
     * 北京/上海/深圳/成都 2km~11km 不等），单次请求无法命中目标里程——用半径
     * 阶梯连测 5 次（2.2~5.0 倍系数）取最接近者，偏差 ≤15% 提前采纳；全部失败
     * 回退几何平滑环（无网也可用）。
     * 传入/返回均为调用方约定的坐标系（OSRM 需要 WGS-84，由调用方转换）。
     * 实际里程在 totalM 中如实返回，UI 须展示实际值。
     */
    fun loop(centerLat: Double, centerLng: Double, distanceKm: Double): LoopRoute {
        val target = distanceKm * 1000.0
        val seed0 = System.currentTimeMillis()
        val ladder = doubleArrayOf(4.0, 2.8, 5.0, 2.2, 3.4)
        var best: LoopRoute? = null
        for ((i, k) in ladder.withIndex()) {
            // 页面销毁会 interrupt 生成线程（TrackFragment）：档间检查提前终止剩余
            // 联网尝试（单档最长 ~8s，HttpURLConnection 不响应中断，无法档内中止）
            if (Thread.currentThread().isInterrupted) throw InterruptedException("环线生成已取消")
            val radius = target / (2.0 * kotlin.math.PI * k)
            try {
                // 首选步行路网（公共 demo 服务器 foot 实际等同 driving；自建/带 profile
                // 的服务器才会体现差异），后续档位直接走 driving
                val profile = if (i == 0) "foot" else "driving"
                val pts = viaOsrm(ringWaypoints(centerLat, centerLng, radius, seed0 + i), profile)
                if (pts.size < 4) throw IllegalStateException("loop route bad")
                val total = TrackMath.densify(pts).last().cumDistM
                val cand = LoopRoute(pts, total)
                if (best == null ||
                    abs(total - target) < abs(best!!.totalM - target)
                ) best = cand
                if (abs(total - target) / target <= 0.15) break
            } catch (e: InterruptedException) {
                throw e // 取消不是"该档位失败"，必须上抛终止整个生成
            } catch (e: Exception) {
                // 该档位失败（NoRoute/超时）：换下一个半径/档位
            }
        }
        best?.let { return it }
        com.awei.spoofer.data.SpLogger.w("Track", "环线路网拟合失败，回退几何环")
        return LoopRoute(smoothRing(centerLat, centerLng, target / (2.0 * kotlin.math.PI)), target)
    }

    /** 环线航点：N=8 圆周点（角度/半径抖动）+ 首点闭合 */
    private fun ringWaypoints(
        centerLat: Double, centerLng: Double, radiusM: Double, seed: Long,
    ): List<Pair<Double, Double>> {
        val rnd = kotlin.random.Random(seed)
        val n = 8
        val startAngle = rnd.nextDouble() * 2.0 * kotlin.math.PI
        val out = ArrayList<Pair<Double, Double>>(n)
        for (i in 0 until n) {
            val angle = startAngle + i * 2.0 * kotlin.math.PI / n + (rnd.nextDouble() - 0.5) * 0.25
            val r = radiusM * (1.0 + (rnd.nextDouble() - 0.5) * 0.3)
            val dLat = r * sin(angle) / 111_320.0
            val dLng = r * cos(angle) / (111_320.0 * cos(Math.toRadians(centerLat)).coerceAtLeast(0.2))
            out.add(Pair(centerLat + dLat, centerLng + dLng))
        }
        return out + Pair(out[0].first, out[0].second)
    }

    /** 几何平滑环（无网兜底）：32 边形，每顶点半径 ±6% 抖动，首点精确闭合 */
    private fun smoothRing(
        centerLat: Double, centerLng: Double, radiusM: Double,
    ): List<Pair<Double, Double>> {
        val rnd = kotlin.random.Random(System.currentTimeMillis())
        val n = 32
        val out = ArrayList<Pair<Double, Double>>(n + 1)
        for (i in 0 until n) {
            val angle = i * 2.0 * kotlin.math.PI / n
            val r = radiusM * (1.0 + (rnd.nextDouble() - 0.5) * 0.12)
            val dLat = r * sin(angle) / 111_320.0
            val dLng = r * cos(angle) / (111_320.0 * cos(Math.toRadians(centerLat)).coerceAtLeast(0.2))
            out.add(Pair(centerLat + dLat, centerLng + dLng))
        }
        out.add(out[0]) // 精确闭合：避免回卷接缝处一次几十米的瞬移
        return out
    }

    /** 多航点 OSRM 请求：coords 为 (lat,lng) 列表（WGS-84），返回原始 polyline (lat,lng) */
    private fun viaOsrm(coords: List<Pair<Double, Double>>, profile: String): List<Pair<Double, Double>> {
        val coord = coords.joinToString(";") { "%f,%f".format(it.second, it.first) }
        val url = URL("https://router.project-osrm.org/route/v1/$profile/$coord?overview=full&geometries=geojson")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            if (root.getString("code") != "Ok") throw IllegalStateException("osrm bad code")
            val coordsArr = root.getJSONArray("routes").getJSONObject(0)
                .getJSONObject("geometry").getJSONArray("coordinates")
            val pts = ArrayList<Pair<Double, Double>>(coordsArr.length())
            for (i in 0 until coordsArr.length()) {
                pts.add(Pair(coordsArr.getJSONArray(i).getDouble(1), coordsArr.getJSONArray(i).getDouble(0)))
            }
            if (pts.size < 2) throw IllegalStateException("osrm short route")
            return pts
        } finally {
            conn.disconnect()
        }
    }

    private fun straightLine(sLat: Double, sLng: Double, eLat: Double, eLng: Double): List<RoutePoint> {
        val dist = haversineM(sLat, sLng, eLat, eLng)
        val steps = (dist / 100.0).toInt().coerceIn(2, 2000)
        val pts = ArrayList<Pair<Double, Double>>(steps + 1)
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            pts.add(Pair(sLat + (eLat - sLat) * t, sLng + (eLng - sLng) * t))
        }
        return densify(pts)
    }

    private fun densify(raw: List<Pair<Double, Double>>): List<RoutePoint> {
        val out = ArrayList<RoutePoint>(raw.size)
        var cum = 0.0
        var prev: Pair<Double, Double>? = null
        for (p in raw) {
            val prevP = prev
            if (prevP != null) {
                cum += haversineM(prevP.first, prevP.second, p.first, p.second)
            }
            val brg = prevP?.let { bearingDeg(it.first, it.second, p.first, p.second) } ?: 0f
            out.add(RoutePoint(p.first, p.second, cum, brg.toFloat()))
            prev = p
        }
        return out
    }

    fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    fun bearingDeg(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val y = sin(Math.toRadians(lng2 - lng1)) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
            sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(Math.toRadians(lng2 - lng1))
        val brg = Math.toDegrees(atan2(y, x))
        return ((brg + 360.0) % 360.0).toFloat()
    }
}
