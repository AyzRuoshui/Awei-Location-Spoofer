package com.awei.spoofer.xposed.hooks

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.awei.spoofer.xposed.HookState
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap

/**
 * 核心 Hook：
 * 1. Location 全部 getter → 伪造坐标（覆盖所有拿到 Location 的路径，含 GMS/高德 AMapLocation）
 * 2. isFromMockProvider / isMock → false
 * 3. LocationManager 请求 API → 回调流替换为伪造位置流（保证 GPS 关闭时仍持续回调）
 */
object LocationHook {

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val proxies = ConcurrentHashMap<LocationListener, FakeListener>()

    fun hook(classLoader: ClassLoader) {
        hookLocationGetters()
        hookLocationManager()
        XposedBridge.log("awei: LocationHook installed")
    }

    // ---------- Location getter ----------

    private fun fakeOrNull(): HookState.FakePos? {
        return try {
            HookState.current()
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 按 Location 实例的实际类型决定喂哪个坐标系。
     * android.location.Location 契约是 WGS-84，但子类可重定义坐标系：
     * 高德 AMapLocation 的 getLatitude/getLongitude 是 GCJ-02（AMap SDK 输出即火星坐标）。
     * 一律喂 fp(WGS) 会让高德系 App 把 WGS 数值当 GCJ 显示，整体偏一个火星偏移量
     *（微信同款症状，1.1.12 随 Tencent 坐标系修复一并收口）。
     *
     * 同时叠 provider 维度偏移（1.1.13）：真世界 gps/network/fused/passive 四源永不同点
     * 同值，此前四源共用同一坐标——"network 与 GPS 完全重合"是 mock 标准指纹
     *（免Root版引擎1 vc23 已做四源差异化，此处补齐满血版）。
     */
    private fun coordsFor(self: Any?, fp: HookState.FakePos): Pair<Double, Double> {
        val (oLat, oLng) = providerOffset((self as? Location)?.provider, fp.lat)
        val lat = fp.lat + oLat
        val lng = fp.lng + oLng
        if (isAmapLocation(self)) {
            return try {
                com.awei.spoofer.track.CoordUtil.wgsToGcj(lat, lng)
            } catch (t: Throwable) {
                Pair(lat, lng)
            }
        }
        return Pair(lat, lng)
    }

    /**
     * provider 维度固定偏移（1.1.13）：network/passive 8-25m、fused 8-20m、gps 零偏移
     *（gps 自身逐秒漂移由 HookState 的 GpsJitter 负责）。偏移从 sessionSeed 派生：
     * 会话内恒定（同一 provider 每次读数偏移一致），跨会话/跨用户不同
     */
    private fun providerOffset(provider: String?, latDeg: Double): Pair<Double, Double> {
        val p = provider ?: "gps"
        if (p == "gps") return Pair(0.0, 0.0)
        val r = java.util.Random(HookState.sessionSeed() xor p.hashCode().toLong())
        val dist = 8.0 + r.nextDouble() * (if (p == "fused") 12.0 else 17.0)
        val ang = r.nextDouble() * 2.0 * Math.PI
        val dLat = dist * Math.cos(ang) / 111_320.0
        val dLng = dist * Math.sin(ang) /
            (111_320.0 * Math.cos(Math.toRadians(latDeg)).coerceAtLeast(0.2))
        return Pair(dLat, dLng)
    }

    /**
     * 按 provider 分档精度（1.1.13）：gps 3-9m 正弦波动（GpsJitter.wanderAccuracy 同款
     * 公式，随机噪声换秒级确定性微抖——同一 Location 两次 getAccuracy 读数必须一致）；
     * network/passive 15-40m、fused 8-20m 会话内固定。此前 getAccuracy 恒 6.0f：
     * "network 定位精度 6m"不物理，与"四源同点"叠加是双重指纹
     */
    private fun accuracyFor(provider: String?): Float {
        val p = provider ?: "gps"
        val seed = HookState.sessionSeed()
        val sec = ((System.currentTimeMillis() - seed) / 1000L).coerceAtLeast(0L)
        return when (p) {
            "network", "passive" ->
                15f + java.util.Random(seed xor (p.hashCode().toLong() shl 8)).nextInt(26).toFloat()
            "fused" ->
                8f + java.util.Random(seed xor 0x1F).nextInt(13).toFloat()
            else -> {
                val wave = 1.8 * Math.sin(sec / 17.0 * 2.0 * Math.PI) +
                    1.0 * Math.sin(sec / 3.3 * 2.0 * Math.PI)
                val micro = ((sec * 2654435761L).toInt() % 9 - 4) * 0.1
                (6.0 + wave + micro).coerceIn(3.0, 9.0).toFloat()
            }
        }
    }

    // ---- 1.1.16 派生精度字段（fork 1.0.8 P6 同款口径，会话确定性推导）----

    private fun speedAccFor(fp: HookState.FakePos): Float =
        0.5f + 0.1f * fp.speedMps.toFloat()

    private fun bearingAccFor(fp: HookState.FakePos): Float =
        if (fp.speedMps < 0.5) 180f
        else (5f + 0.5f * fp.speedMps.toFloat()).coerceAtMost(30f)

    private fun verticalAccFor(provider: String?): Float {
        val acc = accuracyFor(provider)
        val sec = ((System.currentTimeMillis() - HookState.sessionSeed()) / 1000L).coerceAtLeast(0L)
        val micro = ((sec * 2654435761L).toInt() % 7 - 3) * 0.02 // ±0.06 倍确定性微抖
        return (acc * (2.1 + micro)).toFloat()
    }

    /** 四源时间戳新鲜度差（1.1.13）：网络定位比 GPS "旧"，融合居中——同一时刻时间戳也是指纹 */
    private fun timeSkewMs(provider: String?): Long = when (provider ?: "gps") {
        "network", "passive" -> 800L
        "fused" -> 400L
        else -> 300L
    }

    /** 高德 AMapLocation（含其子类）判定：沿继承链比对类名，不引反射缓存 */
    private fun isAmapLocation(o: Any?): Boolean {
        var c: Class<*>? = o?.javaClass
        var depth = 0
        while (c != null && depth++ < 6) {
            if (c.name.startsWith("com.amap.api.location.AMapLocation")) return true
            c = c.superclass
        }
        return false
    }

    private fun hookLocationGetters() {
        val cls = Location::class.java

        XposedHelpers.findAndHookMethod(cls, "getLatitude", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                fakeOrNull()?.let { param.result = coordsFor(param.thisObject, it).first }
            }
        })
        XposedHelpers.findAndHookMethod(cls, "getLongitude", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                fakeOrNull()?.let { param.result = coordsFor(param.thisObject, it).second }
            }
        })
        XposedHelpers.findAndHookMethod(cls, "getAltitude", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                fakeOrNull()?.let { param.result = it.altitude }
            }
        })
        XposedHelpers.findAndHookMethod(cls, "getSpeed", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                fakeOrNull()?.let { param.result = it.speedMps.toFloat() }
            }
        })
        XposedHelpers.findAndHookMethod(cls, "getBearing", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                fakeOrNull()?.let { param.result = it.bearing.toFloat() }
            }
        })
        XposedHelpers.findAndHookMethod(cls, "getAccuracy", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                fakeOrNull()?.let {
                    // 1.1.13：按实例 provider 分档（此前恒 6.0f，四源同精度穿帮）
                    param.result = accuracyFor((param.thisObject as? Location)?.provider)
                }
            }
        })

        // mock 标记清除（getter 层 + 对象内部状态双层）
        XposedHelpers.findAndHookMethod(cls, "isFromMockProvider", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.result = false
            }
        })
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                // 注意正确方法名是 getSpeedAccuracyMetersPerSecond（此前误写 MpsPerSecond 导致 hook 从未生效）
                // 1.1.16 派生精度动态化（fork 1.0.8 P6 同款口径）：恒定 1.0/8.0/10.0 是
                // 指纹（真机三项随运动状态/精度波动），且与 engine1 注入侧动态值两套并存
                // 自相矛盾——全部从 fp 确定性推导，同一读数多次调用结果一致
                XposedHelpers.findAndHookMethod(cls, "getSpeedAccuracyMetersPerSecond", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        fakeOrNull()?.let { param.result = speedAccFor(it) }
                    }
                })
                XposedHelpers.findAndHookMethod(cls, "getBearingAccuracyDegrees", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        fakeOrNull()?.let { param.result = bearingAccFor(it) }
                    }
                })
                XposedHelpers.findAndHookMethod(cls, "getVerticalAccuracyMeters", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        fakeOrNull()?.let {
                            param.result = verticalAccFor((param.thisObject as? Location)?.provider)
                        }
                    }
                })
            } catch (t: Throwable) {
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                XposedHelpers.findAndHookMethod(cls, "getMslAltitudeMeters", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // MSL 与椭球海拔用同一波动值（此前 30/25 两套并存自相矛盾）
                        fakeOrNull()?.let { param.result = it.altitude }
                    }
                })
                XposedHelpers.findAndHookMethod(cls, "getMslAltitudeAccuracyMeters", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        fakeOrNull()?.let {
                            param.result = verticalAccFor((param.thisObject as? Location)?.provider)
                        }
                    }
                })
            } catch (t: Throwable) {
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                XposedHelpers.findAndHookMethod(cls, "isMock", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = false
                    }
                })
            } catch (t: Throwable) {
            }
        }

        // Location.set(Location)：应用把一个 Location 整体拷进自己的对象时
        // 绕过 getter 直拷真实值（部分 SDK 用它转移结果）——拷完后把 this 的
        // 坐标覆写为伪造值（不加入参对象：入参可能被 App 其他地方持有）
        try {
            XposedHelpers.findAndHookMethod(cls, "set", Location::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val fp = fakeOrNull() ?: return
                    val self = param.thisObject as? Location ?: return
                    try {
                        val (cLat, cLng) = coordsFor(self, fp)
                        self.latitude = cLat
                        self.longitude = cLng
                        clearMockFlag(self)
                    } catch (t: Throwable) {
                    }
                }
            })
        } catch (t: Throwable) {
        }
    }

    // ---------- LocationManager ----------

    /** 支持伪造的 provider 集合（gps/network/fused/passive 全覆盖，含融合定位） */
    private val FAKE_PROVIDERS = setOf("gps", "network", "fused", "passive")

    private fun hookLocationManager() {
        val lmClass = LocationManager::class.java

        XposedHelpers.findAndHookMethod(
            lmClass, "getLastKnownLocation", String::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val prov = param.args.firstOrNull() as? String ?: "gps"
                    fakeOrNull()?.let { param.result = buildFakeLocation(it, prov) }
                }
            }
        )

        // requestLocationUpdates 全重载：替换参数中的 LocationListener
        XposedBridge.hookAllMethods(lmClass, "requestLocationUpdates", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val prov = param.args.filterIsInstance<String>().firstOrNull() ?: "gps"
                for (i in param.args.indices) {
                    val a = param.args[i]
                    if (a is LocationListener) {
                        param.args[i] = proxyFor(a, prov)
                    }
                }
            }
        })

        // requestSingleUpdate（已废弃但部分 App 仍用）：单次定位请求，
        // 同 requestLocationUpdates 一样把 LocationListener 包成 FakeListener；
        // 不拦则 App 走单次请求绕过伪造流拿到真位置
        XposedBridge.hookAllMethods(lmClass, "requestSingleUpdate", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val prov = param.args.filterIsInstance<String>().firstOrNull() ?: "gps"
                for (i in param.args.indices) {
                    val a = param.args[i]
                    if (a is LocationListener) {
                        param.args[i] = proxyFor(a, prov)
                    }
                }
            }
        })

        // getCurrentLocation (API 30+)：Consumer<Location> 一次性回调。
        // 必须在 before 阶段替换 Consumer 为代理：吞掉系统真实回调，
        // 改发伪造位置。若只在 after 补发，系统真实位置后到会覆盖伪造值（穿帮）。
        XposedBridge.hookAllMethods(lmClass, "getCurrentLocation", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val prov = param.args.filterIsInstance<String>().firstOrNull() ?: "gps"
                for (i in param.args.indices) {
                    val a = param.args[i] ?: continue
                    val isConsumer = try {
                        Class.forName("java.util.function.Consumer").isInstance(a)
                    } catch (t: Throwable) {
                        false
                    }
                    if (!isConsumer) continue
                    val orig = a as java.util.function.Consumer<Any>
                    param.args[i] = java.util.function.Consumer<Any> { real ->
                        try {
                            if (fakeOrNull() == null) {
                                // 伪装未开启或配置无效（如路线缺失）：放行系统真实结果，
                                // 避免回调黑洞（既无真也无假，App 定位转圈）
                                orig.accept(real)
                            } else {
                                // 吞真实位置，稍后发伪造值（模拟定位耗时）
                                handler.postDelayed({
                                    try {
                                        fakeOrNull()?.let {
                                            orig.accept(buildFakeLocation(it, prov))
                                        }
                                    } catch (t: Throwable) {
                                    }
                                }, 500)
                            }
                        } catch (t: Throwable) {
                        }
                    }
                }
            }
        })

        // isProviderEnabled：伪装开启时报告 gps/network/fused 可用，
        // 避免 App 因"定位已关闭"而不发起请求（GPS 关闭场景）
        XposedBridge.hookAllMethods(lmClass, "isProviderEnabled", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!HookState.deliverable()) return
                val p = param.args.firstOrNull() as? String ?: return
                if (p in FAKE_PROVIDERS) param.result = true
            }
        })

        // removeUpdates：清理代理定时器，并把参数换回系统登记的代理——
        // 注册时 arg 被替换为 FakeListener，系统登记的是代理；App 注销传的是原始
        // listener，不换回的话系统 remove(original) 查无此登记，真注销不发生：
        // 关闭伪装后"已注销的 App"会继续收到真实定位（幽灵回调），重复注册还会双登记
        XposedBridge.hookAllMethods(lmClass, "removeUpdates", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                for (i in param.args.indices) {
                    val a = param.args[i]
                    if (a is FakeListener) {
                        // App 拿到的本来就是代理（未 remove 原始引用的场景）
                        a.stop()
                        a.original?.let { o -> proxies.remove(o) }
                    } else if (a is LocationListener) {
                        proxies.remove(a)?.let { p ->
                            param.args[i] = p
                            p.stop()
                        }
                    }
                }
            }
        })

        // 地理围栏拦截：active 时注册成功但不生效，
        // 防止真实位置触发围栏（打卡类 App 的围栏检测）
        XposedBridge.hookAllMethods(lmClass, "addProximityAlert", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (fakeOrNull() == null) return
                param.result = true
            }
        })
        // addGeofence 返回 void：必须用 setResult() 触发 returnEarly 语义，
        // 直接赋值 result=null 无法区分"未设置"，原方法会照常执行
        XposedBridge.hookAllMethods(lmClass, "addGeofence", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (fakeOrNull() == null) return
                param.setResult(null)
            }
        })
    }

    private fun proxyFor(original: LocationListener, provider: String): LocationListener {
        // computeIfAbsent 原子化 get-then-put：两线程同时为同一 listener 注册时
        // 各建一个 FakeListener 会让先建者的 1Hz 定时器永远停不掉（重复回调+泄漏）
        return proxies.computeIfAbsent(original) { FakeListener(it, provider) }
    }

    fun buildFakeLocation(fp: HookState.FakePos, provider: String = "gps"): Location {
        val loc = Location(provider)
        val (cLat, cLng) = coordsFor(loc, fp) // provider 偏移 + AMap 坐标系分流
        loc.latitude = cLat
        loc.longitude = cLng
        loc.accuracy = accuracyFor(provider)
        loc.speed = fp.speedMps.toFloat()
        loc.bearing = fp.bearing.toFloat()
        loc.altitude = fp.altitude
        val skew = timeSkewMs(provider)
        loc.time = System.currentTimeMillis() - skew
        loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - skew * 1_000_000L
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                loc.verticalAccuracyMeters = verticalAccFor(provider)
                loc.speedAccuracyMetersPerSecond = speedAccFor(fp)
                loc.bearingAccuracyDegrees = bearingAccFor(fp)
            } catch (t: Throwable) {
            }
        }
        // 1.1.16：补 satellites extras（vc21"SDK 读卫星数判 GPS 可信度，缺失触发降级"
        // 教训补到 hook 侧）：与 engine1 注入口径一致（gps=GnssFaker 同源同秒、
        // network/passive=0、fused 不设），与 GnssStatus/NMEA 交叉验证同源自洽
        try {
            when (provider) {
                "gps" -> {
                    val n = DetectionHook.GnssFaker.count()
                    val b = android.os.Bundle()
                    b.putInt("satellites", n)
                    b.putInt("satellitesvalue", n)
                    loc.extras = b
                }
                "network", "passive" -> {
                    val b = android.os.Bundle()
                    b.putInt("satellites", 0)
                    b.putInt("satellitesvalue", 0)
                    loc.extras = b
                }
            }
        } catch (t: Throwable) {
        }
        clearMockFlag(loc)
        return loc
    }

    /** 用 HiddenApiBypass 清除 Location 对象内部的 mock 标记（比 getter hook 更深一层） */
    private fun clearMockFlag(loc: Location) {
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(
                loc.javaClass, loc, "setIsFromMockProvider", false
            )
        } catch (t: Throwable) {
        }
    }

    /**
     * 伪造位置流：1Hz 回调 onLocationChanged。
     * active 时系统真实回调被忽略（位置流由定时器统一伪造）；
     * inactive 时转发系统真实回调，避免伪装关闭后 App 永远收不到定位。
     */
    private class FakeListener(
        val original: LocationListener?,
        val providerName: String,
    ) : LocationListener {

        private val handler by lazy { Handler(Looper.getMainLooper()) }
        @Volatile private var stopped = false

        private val tick = object : Runnable {
            override fun run() {
                if (stopped) return
                try {
                    HookState.current()?.let {
                        original?.onLocationChanged(buildFakeLocation(it, providerName))
                    }
                } catch (t: Throwable) {
                }
                handler.postDelayed(this, 1000)
            }
        }

        init {
            handler.postDelayed(tick, 300)
        }

        fun stop() {
            stopped = true
            handler.removeCallbacksAndMessages(null)
        }

        override fun onLocationChanged(location: Location) {
            if (stopped) return
            if (fakeOrNull() == null) {
                // 伪装已关闭或配置无效（路线缺失等）：转发系统真实位置
                try {
                    original?.onLocationChanged(location)
                } catch (t: Throwable) {
                }
                return
            }
            // active 时忽略真实帧，避免双倍回调/时间戳异常
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
            // API 21-29 需要实现（编译期 SDK 30+ 是 default 方法）
        }

        override fun onProviderEnabled(provider: String) {
            try {
                original?.onProviderEnabled(provider)
            } catch (t: Throwable) {
            }
        }

        override fun onProviderDisabled(provider: String) {
            try {
                original?.onProviderDisabled(provider)
            } catch (t: Throwable) {
            }
        }
    }
}
