package com.awei.spoofer.engine.mock

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.awei.spoofer.data.SpLogger

object MockLocationEngine {

    fun isMockLocationEnabled(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val uid = android.os.Process.myUid()
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

    fun mockLocationApp(context: Context): String? = try {
        android.provider.Settings.Secure.getString(context.contentResolver, "mock_location_app")
    } catch (t: Throwable) {
        null
    }

    /**
     * Root 自动授权：用 su 执行 appops set + settings put secure，
     * 自动完成"选模拟位置应用"步骤，用户无需手动去开发者选项操作。
     * 满血版独有（免Root版无 Root 权限，走手动选择流程）。
     * 失败时 setupProviders 会 SecurityException 静默跳过，不影响 Hook 路径。
     */
    fun ensureMockPermission(context: Context): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = process.outputStream
            os.write("appops set ${context.packageName} android:mock_location allow\n".toByteArray())
            os.write("settings put secure mock_location_app ${context.packageName}\n".toByteArray())
            os.write("exit\n".toByteArray())
            os.flush()
            os.close()
            // 1.1.16：3s 超时——su 弹 Magisk 授权框时等待可能长期阻塞（调用方虽已在
            // 后台线程，超时兜底防线程挂死）。API 21 兼容：Process.waitFor(long,TimeUnit)
            // 是 API 26+ 不能用，改轮询 exitValue；超时 destroy 按失败处理
            var exitCode = -1
            val deadline = System.currentTimeMillis() + 3000
            // 1.1.31：边等边收 su 的输出——Magisk 拒绝授权/环境异常时原因写在这些流里，
            // 旧版只报一个 exit code（exit=13 到底是什么原因全靠猜）
            val outBuf = StringBuilder()
            fun drainStreams() {
                try {
                    val ins = process.inputStream
                    while (ins.available() > 0) outBuf.append(ins.read().toChar())
                    val es = process.errorStream
                    while (es.available() > 0) outBuf.append(es.read().toChar())
                } catch (t: Throwable) {
                }
            }
            while (System.currentTimeMillis() < deadline) {
                drainStreams()
                try {
                    exitCode = process.exitValue()
                    break
                } catch (e: IllegalThreadStateException) {
                    Thread.sleep(50)
                }
            }
            drainStreams()
            if (exitCode != 0) {
                try {
                    process.destroy()
                } catch (t: Throwable) {
                }
            }
            if (exitCode == 0) {
                SpLogger.i("Mock", "Root 自动授权 mock_location 成功")
                true
            } else {
                val detail = outBuf.toString().trim().replace('\n', ' ')
                val why = if (exitCode == -1) " 3s 超时——Magisk 授权框可能未确认" else ""
                SpLogger.w("Mock",
                    "Root 授权失败(exit=$exitCode)$why${if (detail.isNotEmpty()) " 输出: ${detail.take(200)}" else ""}")
                false
            }
        } catch (e: Throwable) {
            SpLogger.w("Mock", "su 不可用: ${e.message} (${e.javaClass.simpleName})")
            false
        }
    }

    fun setupProviders(context: Context) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        removeProviders(context)
        val ok = ArrayList<String>()
        val fail = ArrayList<String>()
        registeredProviders.clear()
        // 1.1.31：逐 provider 留痕（哪个注册上了、哪个挂了、异常是什么）——
        // 旧版只有一行汇总，故障时区分不了"ROM 不支持"和"Root 授权丢失"
        for (name in SUPPORTED_PROVIDERS) {
            val added = addProvider(lm, name, highPower = name == "gps")
            SpLogger.i("Mock", "test provider 注册: $name → ${if (added) "成功" else "失败"}")
            if (added) {
                ok.add(name)
                registeredProviders.add(name)
            } else fail.add(name)
        }
        SpLogger.i("Mock", "test provider 注册成功=$ok 失败=$fail")
        if (fail.isNotEmpty()) {
            SpLogger.i("Mock", "未注册的 provider 不再注入（passive 在多数 ROM 上不可注册属正常）")
        }
        // 1.1.30：全失败（典型=Root 授权丢失/被拒）曾是纯静默故障——模拟照常"运行中"，
        // 但未被 Hook 覆盖的进程（如小程序运行时）读不到假位置（真机实证）
        lastSetupAllFailed = ok.isEmpty()
    }

    /** 实际注册成功的 provider：injectAll 只注入这些，避免对未注册 provider 反复空打刷警告 */
    private val registeredProviders = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 最近一次 setupProviders 是否全失败（浮窗红字告警依据；注册成功即复位）。
     *  判据用注册结果而非 Root 结果：mock_location 授权设置可能残留生效，Root 挂了
     *  注册仍可能成功——告警只该在注入通道真死时出现 */
    @Volatile
    var lastSetupAllFailed: Boolean = false
        private set

    val SUPPORTED_PROVIDERS = arrayOf("gps", "network", "fused", "passive")

    private fun addProvider(lm: LocationManager, name: String, highPower: Boolean): Boolean {
        return try {
            val isGps = name == "gps"
            val isNetwork = name == "network"
            lm.addTestProvider(
                name,
                isNetwork, isGps, isNetwork, true,
                true, true, true,
                if (highPower) Criteria.POWER_HIGH else Criteria.POWER_LOW,
                if (highPower) Criteria.ACCURACY_FINE else Criteria.ACCURACY_COARSE
            )
            lm.setTestProviderEnabled(name, true)
            true
        } catch (e: Throwable) {
            SpLogger.w("Mock", "test provider $name 注册异常: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private var injectCount = 0L

    // 1.1.31 注入通道心跳：运行中每 60s 一条「通道自检」；通道死（Root 授权丢失/
    // 零注册）时照打不误——旧版零注入纯静默，唯一旁证是「注入中」行消失，排查靠猜
    @Volatile
    private var lastHeartbeatAt = 0L
    @Volatile
    private var lastInjectSuccessAt = 0L
    @Volatile
    private var lastSkipLogAt = 0L

    // per-provider 注入失败计数（节流用）：成功即移除，恢复时打一条
    private val injectFailCounts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun heartbeat(nowMs: Long) {
        if (nowMs - lastHeartbeatAt < 60_000L) return
        lastHeartbeatAt = nowMs
        val age = if (lastInjectSuccessAt == 0L) "从未" else "${(nowMs - lastInjectSuccessAt) / 1000}s前"
        SpLogger.i("Mock", "通道自检: 注册=$registeredProviders 注入=#$injectCount 最近成功=$age")
    }

    private fun skipLog(why: String) {
        val now = System.currentTimeMillis()
        if (now - lastSkipLogAt < 30_000L) return
        lastSkipLogAt = now
        SpLogger.w("Mock", "注入跳过: $why")
    }

    @Volatile
    var lastInjectedWgs: String = ""
        private set

    // 网络类 provider 的偏移基线（进程内随机一次）+ 时间缓变调制（防零方差常向量指纹）：
    // 真实 GPS-网络偏差会随卫星几何/基站切换漂移，进程内冻结的恒定偏移方向+幅度
    // 会被配对 GPS+network fix 的协方差统计识别（运动反作弊常用手法）。
    // 基线随机一次定中心，sin 不同周期调制方向±0.4rad + 幅度±25%——
    // 零进程状态、向量随时间画椭圆而非钉死一点
    //（1.1.17 反向同步自免Root版 1.0.13——该修复此前只落在 fork，满血版兜底路径
    // 是全机可见的 test provider 注入，指纹质量同样要紧）
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

    fun injectAll(context: Context, lat: Double, lng: Double,
                  speedMps: Float, bearing: Float, accuracyM: Float, altitude: Double) {
        val nowMs = System.currentTimeMillis()
        heartbeat(nowMs)
        if (!hasLocationPermission(context)) {
            skipLog("无定位权限")
            return
        }
        if (registeredProviders.isEmpty()) {
            skipLog("无已注册 provider——注入通道未建立（Root 授权丢了？去 Magisk 重新允许后重启模拟）")
            return
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var anyFail = false
        val failNames = ArrayList<String>()
        // gps：主位置（高精度、时间戳新 300ms 回拨）
        if ("gps" in registeredProviders) {
            if (injectTo(lm, "gps", lat, lng, speedMps, bearing, accuracyM, altitude, 300L)) {
                lastInjectSuccessAt = nowMs
            } else {
                anyFail = true
                failNames.add("gps")
            }
        }
        lastInjectedWgs = "%.5f,%.5f".format(lat, lng)
        // network/passive：模拟"网络定位比 GPS 粗"的自然形态
        //（缓变偏移 8-25m + 精度更差 15-40m + 时间戳略旧 800ms + speed/bearing=0——
        // 真实网络源不报速度航向，四源逐字节相同是 mock 特征）。经度换算用 cos(纬度)
        val netAcc = (accuracyM * 2.5f + 10f).coerceIn(15f, 40f)
        val (netDist, netAng) = netOffset(nowMs)
        val netLat = netDist * Math.cos(netAng) / 111_320.0
        val netLng = netDist * Math.sin(netAng) /
            (111_320.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        for (provider in arrayOf("network", "passive")) {
            if (provider !in registeredProviders) continue
            if (!injectTo(lm, provider, lat + netLat, lng + netLng,
                    0f, 0f, netAcc, altitude, 800L)) {
                anyFail = true
                failNames.add(provider)
            }
        }
        // fused：融合层应贴合 GPS（缓变偏移 1-3m + 回拨 350-450ms——
        // 必须晚于 GPS 300ms，融合层处理有延迟不能比 GPS 更新鲜，倒挂是跨 provider
        // 时间戳排序指纹）
        val fusedAcc = (accuracyM * 1.1f).coerceAtMost(12f)
        val (fDist, fAng) = fusedOffset(nowMs)
        val fusedLat = fDist * Math.cos(fAng) / 111_320.0
        val fusedLng = fDist * Math.sin(fAng) /
            (111_320.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        val fusedBackoff = 350L + (injectCount % 2) * 100L // 350-450ms 交替（略晚于 GPS）
        if ("fused" in registeredProviders) {
            if (!injectTo(lm, "fused", lat + fusedLat, lng + fusedLng,
                    speedMps, bearing, fusedAcc, altitude, fusedBackoff)) {
                anyFail = true
                failNames.add("fused")
            }
        }
        injectCount++
        if (injectCount % 60 == 1L) {
            SpLogger.i("Mock",
                "注入中(#$injectCount) (%.5f,%.5f) %.0fkm/h${if (anyFail) " ⚠部分provider失败" else ""}".format(lat, lng, speedMps * 3.6))
        } else if (anyFail && injectCount % 10 == 0L) {
            SpLogger.w("Mock", "部分 provider 注入失败(#$injectCount): $failNames")
        }
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                loc.verticalAccuracyMeters = accuracyM * (2.0f + 0.3f * Math.random().toFloat())
                loc.speedAccuracyMetersPerSecond = 0.5f + 0.1f * speedMps
                loc.bearingAccuracyDegrees = if (speedMps < 0.5f) 180f else (5f + 0.5f * speedMps).coerceAtMost(30f)
            }
            // 卫星数 extras 分源差异化：
            // - gps：动态 8-12 颗（10 秒粒度抖动）
            // - network/passive：不设 satellites 键（"键存在且=0" 比"键缺失"更可疑——
            //   检测方按 containsKey 判即穿帮）
            // - fused：不设 extras（fused 不读卫星）
            when (provider) {
                "gps" -> {
                    val n = satCount()
                    loc.extras = android.os.Bundle().apply {
                        putInt("satellites", n)
                        putInt("satellitesvalue", n)
                    }
                }
            }
            lm.setTestProviderLocation(provider, loc)
            if (injectFailCounts.remove(provider) != null) {
                SpLogger.i("Mock", "注入恢复: $provider（此前异常已消失）")
            }
            true
        } catch (e: Throwable) {
            // 1.1.31：注入异常逐条留痕（哪个 provider、什么异常）——旧版静默吞掉，
            // Root 授权中途被撤时只剩一句模糊的"部分provider失败"。节流：首发 +
            // 每 30 次一条，防授权被撤后 2Hz×4 源刷爆日志
            val c = (injectFailCounts[provider] ?: 0L) + 1L
            injectFailCounts[provider] = c
            if (c == 1L || c % 30L == 0L) {
                SpLogger.w("Mock", "注入异常 $provider(#$c): ${e.javaClass.simpleName}: ${e.message}")
            }
            false
        }
    }

    private fun satCount(): Int {
        val seed = System.currentTimeMillis() / 10000
        val rnd = java.util.Random(seed)
        return 8 + rnd.nextInt(5)
    }

    fun removeProviders(context: Context) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val had = registeredProviders.toList()
        registeredProviders.clear()
        for (name in SUPPORTED_PROVIDERS) {
            try {
                lm.removeTestProvider(name)
            } catch (e: Throwable) {
            }
        }
        // 1.1.31：拆通道留痕——停止/销毁路径旧版一行日志都没有
        if (had.isNotEmpty()) SpLogger.i("Mock", "test provider 已移除: $had")
    }
}
