package com.awei.spoofer.engine.mock

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

object MockLocationEngine {

    fun isMockLocationEnabled(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val uid = android.os.Process.myUid()
            // 注意：checkOpNoThrow(String,...) 是 API 29 才有的公开重载，低版本必须走反射 int 版本
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.checkOpNoThrow("android:mock_location", uid, context.packageName)
            } else {
                @Suppress("DEPRECATION")
                val m = appOps.javaClass.getMethod(
                    "checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java
                )
                m.invoke(appOps, 58, uid, context.packageName) as Int
            }
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Throwable) {
            false
        }
    }

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 开发者选项"模拟位置信息应用"当前选中的包名（系统维护的 secure setting）。
     * 与 isMockLocationEnabled(AppOps) 双读：区分"还没选/选成别的 App/选了但没生效"，给用户不同引导文案
     */
    fun mockLocationApp(context: Context): String? = try {
        android.provider.Settings.Secure.getString(context.contentResolver, "mock_location_app")
    } catch (t: Throwable) {
        null
    }

    fun setupProviders(context: Context) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        removeProviders(context)
        // 覆盖全部常用 provider：很多 App（含高德/地图类）默认请求 fused 融合定位，
        // 只 mock gps 会导致 App 走 fused/network 拿到真实位置
        val ok = ArrayList<String>()
        val fail = ArrayList<String>()
        for (name in SUPPORTED_PROVIDERS) {
            if (addProvider(lm, name, highPower = name == "gps")) ok.add(name) else fail.add(name)
        }
        com.awei.spoofer.data.SpLogger.i("Mock", "test provider 注册成功=${ok} 失败=${fail}")
    }

    val SUPPORTED_PROVIDERS = arrayOf("gps", "network", "fused", "passive")

    private fun addProvider(lm: LocationManager, name: String, highPower: Boolean): Boolean {
        return try {
            val isGps = name == "gps"
            val isNetwork = name == "network"
            lm.addTestProvider(
                name,
                isNetwork,          // requiresNetwork
                isGps,              // requiresSatellite
                isNetwork,          // requiresCell
                true,               // hasMonetaryCost
                true,               // supportsAltitude
                true,               // supportsSpeed
                true,               // supportsBearing
                if (highPower) Criteria.POWER_HIGH else Criteria.POWER_LOW,
                if (highPower) Criteria.ACCURACY_FINE else Criteria.ACCURACY_COARSE
            )
            // 关键：addTestProvider 注册的 provider 默认处于"禁用"状态，
            // 不置 enabled 的话 App 查 isProviderEnabled 返回 false，
            // 会判定定位不可用而走真实网络定位（此前缺失导致所有 App 模拟失败）
            lm.setTestProviderEnabled(name, true)
            true
        } catch (e: Throwable) {
            false
        }
    }

    private var injectCount = 0L

    /** 最近注入的 WGS-84 坐标（自检对比用） */
    @Volatile
    var lastInjectedWgs: String = ""
        private set

    // 网络类 provider 的偏移基线（进程内随机一次）+ 时间缓变调制（防零方差常向量指纹）：
    // 真实 GPS-网络偏差会随卫星几何/基站切换漂移，进程内冻结的恒定偏移方向+幅度
    // 会被配对 GPS+network fix 的协方差统计识别（运动反作弊常用手法）。
    // 基线随机一次定中心，sin 不同周期调制方向±0.4rad + 幅度±25%——
    // 零进程状态、向量随时间画椭圆而非钉死一点
    private val netBaseDist: Double
    private val netBaseAng: Double
    // fused 偏移基线：融合层应贴合 GPS（偏移 1-3m），同样加缓变调制
    private val fusedBaseDist: Double
    private val fusedBaseAng: Double

    init {
        val rnd = java.util.Random()
        netBaseDist = 8.0 + rnd.nextDouble() * 17.0 // 8~25 米
        netBaseAng = rnd.nextDouble() * 2.0 * Math.PI
        fusedBaseDist = 1.0 + rnd.nextDouble() * 2.0 // 1~3 米
        fusedBaseAng = rnd.nextDouble() * 2.0 * Math.PI
    }

    /** 网络偏移当前（距离, 方向）：基线 + sin 缓变（~300s 方向漂移 + ~170s 幅度波动） */
    private fun netOffset(nowMs: Long): Pair<Double, Double> {
        val t = nowMs / 1000.0
        val ang = netBaseAng + 0.4 * Math.sin(2.0 * Math.PI * t / 300.0)
        val dist = netBaseDist * (1.0 + 0.25 * Math.sin(2.0 * Math.PI * t / 173.0))
        return Pair(dist, ang)
    }

    /** fused 偏移当前（距离, 方向）：基线 + sin 缓变（幅度更小，贴合 GPS） */
    private fun fusedOffset(nowMs: Long): Pair<Double, Double> {
        val t = nowMs / 1000.0
        val ang = fusedBaseAng + 0.3 * Math.sin(2.0 * Math.PI * t / 230.0)
        val dist = fusedBaseDist * (1.0 + 0.2 * Math.sin(2.0 * Math.PI * t / 97.0))
        return Pair(dist, ang)
    }

    /** 同时向所有已注册的 test provider 注入同一位置 */
    fun injectAll(context: Context, lat: Double, lng: Double,
                  speedMps: Float, bearing: Float, accuracyM: Float, altitude: Double) {
        if (!hasLocationPermission(context)) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val nowMs = System.currentTimeMillis()
        var anyFail = false
        // gps：主位置（高精度、时间戳新 300ms 回拨）
        if (!injectTo(lm, "gps", lat, lng, speedMps, bearing, accuracyM, altitude, 300L)) anyFail = true
        lastInjectedWgs = "%.5f,%.5f".format(lat, lng)
        // network/passive：模拟"网络定位比 GPS 粗"的自然形态
        //（缓变偏移 8-25m + 精度更差 15-40m + 时间戳略旧 800ms + speed/bearing=0
        // 真实网络源不报速度航向，四源逐字节相同是 mock 特征）。经度换算用 cos(纬度)
        val (netDist, netAng) = netOffset(nowMs)
        val netAcc = (accuracyM * 2.5f + 10f).coerceIn(15f, 40f)
        val netLat = netDist * Math.cos(netAng) / 111_320.0
        val netLng = netDist * Math.sin(netAng) /
            (111_320.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        for (provider in arrayOf("network", "passive")) {
            if (!injectTo(lm, provider, lat + netLat, lng + netLng,
                    0f, 0f, netAcc, altitude, 800L)) anyFail = true
        }
        // fused：融合层应贴合 GPS（缓变偏移 1-3m + 回拨 350-450ms——
        // 必须晚于 GPS 300ms，融合层处理有延迟不能比 GPS 更新鲜）
        val (fDist, fAng) = fusedOffset(nowMs)
        val fusedAcc = (accuracyM * 1.1f).coerceAtMost(12f) // 略宽于 GPS 但远优于 network
        val fusedLat = fDist * Math.cos(fAng) / 111_320.0
        val fusedLng = fDist * Math.sin(fAng) /
            (111_320.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        val fusedBackoff = 350L + (injectCount % 2) * 100L // 350-450ms 交替（略晚于 GPS）
        if (!injectTo(lm, "fused", lat + fusedLat, lng + fusedLng,
                speedMps, bearing, fusedAcc, altitude, fusedBackoff)) anyFail = true
        injectCount++
        // 注入为 2Hz 循环：每 60 次（约半分钟）汇总一条，避免日志刷屏
        if (injectCount % 60 == 1L) {
            com.awei.spoofer.data.SpLogger.i("Mock",
                "注入中(#$injectCount) (%.5f,%.5f) %.0fkm/h${if (anyFail) " ⚠部分provider失败" else ""}".format(lat, lng, speedMps * 3.6))
        } else if (anyFail && injectCount % 10 == 0L) {
            com.awei.spoofer.data.SpLogger.w("Mock", "部分 provider 注入失败(#$injectCount)")
        }
    }

    fun inject(context: Context, provider: String, lat: Double, lng: Double,
               speedMps: Float, bearing: Float, accuracyM: Float, altitude: Double): Boolean {
        if (!hasLocationPermission(context)) return false
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return injectTo(lm, provider, lat, lng, speedMps, bearing, accuracyM, altitude, 300L)
    }

    private fun injectTo(lm: LocationManager, provider: String, lat: Double, lng: Double,
                         speedMps: Float, bearing: Float, accuracyM: Float, altitude: Double,
                         timeBackoffMs: Long): Boolean {
        return try {
            val loc = android.location.Location(provider)
            loc.latitude = lat
            loc.longitude = lng
            loc.speed = speedMps
            loc.bearing = bearing
            loc.accuracy = accuracyM
            loc.altitude = altitude
            loc.time = System.currentTimeMillis() - timeBackoffMs
            loc.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos() - timeBackoffMs * 1_000_000L
            // 不在 App 侧显式打 isMock 标记：系统在服务端自行决定 mock 标记，
            // 自打标记只会让 SDK 的 isMock/isFromMockProvider 检测百发百中（此前显式设置=自证身份）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 派生精度字段动态化：三个恒定值（10m/1.0/8°）本身是可统计指纹
                // vertical 通常 2-2.3× 水平精度（真机典型范围）
                loc.verticalAccuracyMeters = accuracyM * (2.0f + 0.3f * Math.random().toFloat())
                // speedAcc 随速度（静止 0.5、10km/h ~0.78、15km/h ~0.92）
                loc.speedAccuracyMetersPerSecond = 0.5f + 0.1f * speedMps
                // bearingAcc 静止 180°（不确定）、运动 5-15°
                loc.bearingAccuracyDegrees = if (speedMps < 0.5f) 180f else (5f + 0.5f * speedMps).coerceAtMost(30f)
            }
            // MSL 海拔写入：setMslAltitude 是 @hide 方法（不在 compileSdk 35 公开 SDK，
            // 与 vc41 坑 #38 SURFACE_SECURE 同型），noroot 版无 HiddenApiBypass 依赖也无
            // hook 能力伪造 getMslAltitude —— MSL 写入对本版无解。altitude 字段（公开
            // setter）已设，getMslAltitude() 恒返 0 的矛盾不影响主功能（检测方看的是 mock
            // 标记不是 MSL 自洽）。满血版走 LocationHook.kt 的 getMslAltitudeMeters hook 伪造。
            // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            //     loc.setMslAltitude(altitude)  // @hide，公开 SDK 不可直引
            // }
            // 卫星数 extras 分源差异化：
            // - gps：动态 8-12 颗（10 秒粒度抖动）
            // - network/passive：0 颗（网络源不读卫星）
            // - fused：不设 extras（fused 不读卫星）
            when (provider) {
                "gps" -> {
                    val n = satCount()
                    loc.extras = android.os.Bundle().apply {
                        putInt("satellites", n)
                        putInt("satellitesvalue", n)
                    }
                }
                "network", "passive" -> {
                    // 真实 network/passive Location 的 extras 不含 satellites 键
                    // （"键存在且=0" 比"键缺失"更可疑——检测方按 containsKey 判即穿帮）
                }
                // "fused" 不设 extras
            }
            lm.setTestProviderLocation(provider, loc)
            true
        } catch (e: Throwable) {
            false
        }
    }

    /** GPS 卫星颗数动态抖动：8-12 颗，10 秒粒度（跨会话不同 + 同会话可复现） */
    private fun satCount(): Int {
        val seed = System.currentTimeMillis() / 10000
        val rnd = java.util.Random(seed)
        return 8 + rnd.nextInt(5)
    }

    fun removeProviders(context: Context) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        for (name in SUPPORTED_PROVIDERS) {
            try {
                lm.removeTestProvider(name)
            } catch (e: Throwable) {
            }
        }
    }
}
