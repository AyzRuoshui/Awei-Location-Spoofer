package com.awei.spoofer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hook 配置存储：主 App 写入，SpooferProvider 提供给 Hook 进程读取。
 */
object HookConfigStore {

    const val AUTHORITY = "com.awei.spoofer.config"

    fun sp(context: Context) = context.getSharedPreferences("hook_config", Context.MODE_PRIVATE)

    fun save(context: Context, enabled: Boolean, mode: Int, lat: Double, lng: Double,
             routeRaw: List<Pair<Double, Double>>, speedKmh: Float,
             variable: Boolean, loop: Boolean, startAt: Long = System.currentTimeMillis(),
             gcj: Boolean = false,
             holdAtMs: Long = Prefs.holdAtMs, holdAccumMs: Long = Prefs.holdAccumMs) {
        val obj = JSONObject()
        obj.put("enabled", enabled)
        obj.put("mode", mode)
        obj.put("lat", lat)
        obj.put("lng", lng)
        val arr = JSONArray()
        for (p in routeRaw) {
            arr.put(JSONArray().put(p.first).put(p.second))
        }
        obj.put("route", arr)
        obj.put("speedKmh", speedKmh.toDouble())
        obj.put("variable", variable)
        obj.put("loop", loop)
        obj.put("startAt", startAt)
        obj.put("gcj", gcj)
        // 1.1.14 移动开关状态（待命/起跑/暂停）：Hook 端据此冻结/推进 elapsed
        obj.put("holdAt", holdAtMs)
        obj.put("holdAccum", holdAccumMs)
        sp(context).edit().putString("json", obj.toString()).apply()
        // 1.1.14 配置即时推送：Hook 进程的 ContentObserver 秒级响应（15s 轮询降为
        // 兜底）——待命/起跑/暂停切换等轮询会有最长 15s 的"一个在走一个站着"窗口
        try {
            context.contentResolver.notifyChange(
                android.net.Uri.parse("content://$AUTHORITY/current"), null
            )
        } catch (t: Throwable) {
        }
        SpLogger.i("Config",
            "下发 Hook 配置 enabled=$enabled mode=${if (mode == MockConfig.MODE_ROUTE) "轨迹" else "固定点"} " +
                "lat=%.5f lng=%.5f route=${routeRaw.size}点 speed=$speedKmh gcj=$gcj".format(lat, lng))
    }

    fun json(context: Context): String? = sp(context).getString("json", null)

    // ---- 虚拟摄像头配置 ----
    fun setCamera(context: Context, enabled: Boolean, videoUri: String?, mirror: Boolean? = null) {
        val e = sp(context).edit()
            .putBoolean("cam_enabled", enabled)
            .putString("cam_video", videoUri)
        if (mirror != null) e.putBoolean("cam_mirror", mirror)
        e.apply()
        SpLogger.i("Cam", "摄像头配置 enabled=$enabled video=${videoUri != null} mirror=${mirror == true}")
    }

    fun cameraEnabled(context: Context): Boolean = sp(context).getBoolean("cam_enabled", false)
    fun cameraVideoUri(context: Context): String? = sp(context).getString("cam_video", null)
    fun cameraMirror(context: Context): Boolean = sp(context).getBoolean("cam_mirror", false)

    // ---- 虚拟摄像头源：本地视频 / 远程手机（双机模式）/ 网络直播流（RTSP/RTMP/HTTP）----
    const val CAM_SOURCE_FILE = "file"
    const val CAM_SOURCE_IMAGE = "image"
    const val CAM_SOURCE_REMOTE = "remote"
    const val CAM_SOURCE_NETWORK = "network"

    // 远程源的连接方式：局域网直连（addr=IP:端口）/ 服务器中转（addr=远程 ID）
    const val CAM_LINK_LAN = "lan"
    const val CAM_LINK_RELAY = "relay"

    fun setCameraSource(
        context: Context, source: String, addr: String?, token: String?,
        linkMode: String = CAM_LINK_LAN, relayServer: String? = null,
    ) {
        sp(context).edit()
            .putString("cam_source", source)
            .putString("cam_link_mode", linkMode)
            .putString("cam_remote_addr", addr?.trim())
            .putString("cam_remote_token", token?.trim())
            .putString("cam_relay_server", relayServer?.trim())
            .apply()
        SpLogger.i("Cam", "摄像头源=$source link=$linkMode addr=${addr ?: "-"} relay=${relayServer ?: "-"} token=${if (token.isNullOrBlank()) "-" else "***"}")
    }

    fun cameraSource(context: Context): String =
        sp(context).getString("cam_source", CAM_SOURCE_FILE) ?: CAM_SOURCE_FILE

    fun cameraLinkMode(context: Context): String =
        sp(context).getString("cam_link_mode", CAM_LINK_LAN) ?: CAM_LINK_LAN

    fun cameraRemoteAddr(context: Context): String? = sp(context).getString("cam_remote_addr", null)

    fun cameraRemoteToken(context: Context): String? = sp(context).getString("cam_remote_token", null)

    fun cameraRelayServer(context: Context): String? =
        sp(context).getString("cam_relay_server", null)?.takeIf { it.isNotBlank() }
            ?: com.awei.common.Proto.RELAY_DEFAULT

    // ---- WiFi 身份伪装（伪造自洽假 AP，替代清空；参考 XposedFakeLocation）----
    // 扫描结果仍清空（防真实 BSSID 反查），但连接信息给一个自洽假 AP：
    // 对"扫描为空就报错/降级"的 App 更稳
    fun wifiSsid(context: Context): String = sp(context).getString("wifi_ssid", "AndroidAP") ?: "AndroidAP"
    fun wifiBssid(context: Context): String = sp(context).getString("wifi_bssid", "02:00:00:00:00:00") ?: "02:00:00:00:00:00"
    fun wifiRssi(context: Context): Int = sp(context).getInt("wifi_rssi", -60)

    fun setWifiIdentity(context: Context, ssid: String, bssid: String, rssi: Int) {
        sp(context).edit()
            .putString("wifi_ssid", ssid.take(32))
            .putString("wifi_bssid", bssid)
            .putInt("wifi_rssi", rssi.coerceIn(-127, 0))
            .apply()
    }

    data class HookCfg(
        val enabled: Boolean,
        val mode: Int,
        val lat: Double,
        val lng: Double,
        val route: List<Pair<Double, Double>>,
        val speedKmh: Float,
        val variable: Boolean,
        val loop: Boolean,
        val startAt: Long,
        val gcj: Boolean,
        // 1.1.14 移动开关：holdAtMs>0=暂停/待命中（elapsed 冻结在 holdAtMs-startAt-accum）
        val holdAtMs: Long,
        val holdAccumMs: Long,
    )

    fun parse(json: String?): HookCfg? {
        if (json.isNullOrEmpty()) return null
        return try {
            val o = JSONObject(json)
            val route = ArrayList<Pair<Double, Double>>()
            val arr = o.optJSONArray("route") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONArray(i)
                route.add(Pair(p.getDouble(0), p.getDouble(1)))
            }
            HookCfg(
                enabled = o.optBoolean("enabled", false),
                mode = o.optInt("mode", 0),
                lat = o.optDouble("lat", 0.0),
                lng = o.optDouble("lng", 0.0),
                route = route,
                speedKmh = o.optDouble("speedKmh", 10.0).toFloat(),
                // 默认与 Prefs.getVariableSpeed 对齐（true）：残缺/旧版 JSON 走兜底时
                // 不至于两引擎变速口径分裂（vc33 起变速是默认推荐项）
                variable = o.optBoolean("variable", true),
                loop = o.optBoolean("loop", true),
                startAt = o.optLong("startAt", 0L),
                gcj = o.optBoolean("gcj", false),
                holdAtMs = o.optLong("holdAt", 0L),
                holdAccumMs = o.optLong("holdAccum", 0L),
            )
        } catch (e: Exception) {
            null
        }
    }
}
