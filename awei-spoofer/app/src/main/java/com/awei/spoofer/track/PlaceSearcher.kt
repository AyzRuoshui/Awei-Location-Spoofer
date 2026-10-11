package com.awei.spoofer.track

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 地名搜索（地理编码）：免费无 key 服务（与 OSRM 同类开放服务）。
 * - 主用 Photon（komoot）：实测国内直连可用、中文结果好
 * - 兜底 Nominatim（OSM 官方）：国内常不可达，但境外/部分网络环境下地址解析更好
 *
 * 返回坐标统一为 WGS-84 物理坐标；选点时由调用方按当前地图源转视觉坐标
 * （高德=GCJ-02）后再落标记/存储，与选点坐标约定一致（见 CoordUtil 注释）。
 */
object PlaceSearcher {

    data class Hit(val name: String, val detail: String, val lat: Double, val lng: Double)

    fun search(query: String): List<Hit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        try {
            val r = photon(q)
            if (r.isNotEmpty()) return r
        } catch (t: Throwable) {
            // 1.1.17：此前两级 catch 空吞——Photon 持续故障完全不可见，排障靠猜
            com.awei.spoofer.data.SpLogger.w("Search", "Photon 失败(${t.javaClass.simpleName})，转 Nominatim 兜底")
        }
        try {
            return nominatim(q)
        } catch (t: Throwable) {
            com.awei.spoofer.data.SpLogger.w("Search", "Nominatim 也失败(${t.javaClass.simpleName})")
        }
        return emptyList()
    }

    private fun nominatim(q: String): List<Hit> {
        val url = URL(
            "https://nominatim.openstreetmap.org/search?q=${URLEncoder.encode(q, "UTF-8")}" +
                "&format=jsonv2&limit=8&accept-language=zh-CN"
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        // Nominatim 使用政策要求可识别的 User-Agent
        conn.setRequestProperty("User-Agent", "AweiSpoofer/0.8 (personal location tool)")
        try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body)
            val out = ArrayList<Hit>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val lat = o.optDouble("lat", Double.NaN)
                val lon = o.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                val display = o.optString("display_name", "")
                val name = o.optString("name", "").ifBlank {
                    display.substringBefore(",").trim()
                }
                if (name.isBlank()) continue
                out.add(Hit(name, display, lat, lon))
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    private fun photon(q: String): List<Hit> {
        val url = URL(
            "https://photon.komoot.io/api/?q=${URLEncoder.encode(q, "UTF-8")}&limit=8"
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        conn.setRequestProperty("User-Agent", "AweiSpoofer/0.8 (personal location tool)")
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val features = root.optJSONArray("features") ?: return emptyList()
            val out = ArrayList<Hit>(features.length())
            for (i in 0 until features.length()) {
                val f = features.getJSONObject(i)
                val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                if (coords.length() < 2) continue
                val lon = coords.optDouble(0, Double.NaN)
                val lat = coords.optDouble(1, Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                val p = f.optJSONObject("properties") ?: continue
                val name = p.optString("name", "").trim()
                if (name.isBlank()) continue
                val detail = listOf("city", "county", "state", "country")
                    .mapNotNull { p.optString(it, "").takeIf { s -> s.isNotBlank() } }
                    .joinToString("，")
                out.add(Hit(name, detail.ifBlank { name }, lat, lon))
            }
            return out
        } finally {
            conn.disconnect()
        }
    }
}
