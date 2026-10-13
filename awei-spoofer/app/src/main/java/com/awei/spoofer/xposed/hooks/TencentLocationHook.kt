package com.awei.spoofer.xposed.hooks

import com.awei.spoofer.xposed.HookState
import com.awei.spoofer.xposed.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Proxy

/**
 * 腾讯位置服务 SDK Hook（第 15 模块）。
 *
 * 微信等腾讯系 App 的定位主路径走 TencentLocationManager，不经过系统
 * LocationManager，LocationHook 无法覆盖。这是"微信里定位不生效"
 * 的最常见根因。
 *
 * 方案：hook TencentLocationManager.requestLocationUpdates /
 * requestSingleFreshLocation / removeLocationUpdates，before 阶段把 listener
 * 参数替换为代理 listener。代理 listener 用 TencentLocation 动态代理替换原始
 * location 对象，getter 返回伪造值。
 *
 * **自驱 1Hz 投递（1.1.14）**：旧实现搭真实回调的车——腾讯 SDK 室内走网络定位，
 * 回调稀疏（秒级到十几秒）且会失败（error≠0 / loc==null 直通），目标 App 会
 * "定位跟不上"甚至断更卡"正在确认定位"。现改为自驱 1Hz 合成投递（与
 * LocationHook.FakeListener 同模式）：真实回调只用来缓存 backing 对象
 *（TencentLocation 是接口，合成帧的透传 getter 需要真实对象垫底），不再决定节拍。
 *
 * 类找不到（App 不集成腾讯 SDK）时静默跳过，零副作用。
 */
object TencentLocationHook {

    // TencentLocation 坐标系常量（接口静态字段，反射读取防版本差异；读不到按公开文档值 1/2）
    @Volatile private var typeGcj = 1
    @Volatile private var typeWgs = 2

    // ---- 自驱 1Hz 投递（1.1.14）----
    private class PumpEntry(
        val orig: Any,
        val listenerClass: Class<*>,
        val locationClass: Class<*>,
        val classLoader: ClassLoader,
        val single: Boolean,
    )

    private val pumps = java.util.concurrent.CopyOnWriteArrayList<PumpEntry>()
    private val proxyMap: MutableMap<Any, Any> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap())
    // 最近真实 TencentLocation：合成帧未覆盖方法的透传 backing（接口代理需要实例垫底）
    @Volatile private var lastReal: Any? = null
    @Volatile private var pumpOn = false
    // lazy：模块类装载期不得触碰 Looper（坑 #49）
    private val pumpHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    fun hook(classLoader: ClassLoader) {
        val locationClass = try {
            classLoader.loadClass("com.tencent.map.geolocation.TencentLocation")
        } catch (t: Throwable) {
            return // 无腾讯 SDK
        }
        val listenerClass = try {
            classLoader.loadClass("com.tencent.map.geolocation.TencentLocationListener")
        } catch (t: Throwable) {
            return
        }
        val managerClass = try {
            classLoader.loadClass("com.tencent.map.geolocation.TencentLocationManager")
        } catch (t: Throwable) {
            return
        }
        try {
            typeGcj = locationClass.getField("COORDINATE_TYPE_GCJ02").getInt(null)
            typeWgs = locationClass.getField("COORDINATE_TYPE_WGS84").getInt(null)
        } catch (t: Throwable) {
            // 字段缺失：保持文档默认值 GCJ02=1 / WGS84=2
        }

        var ok = 0
        for (name in arrayOf("requestLocationUpdates", "requestSingleFreshLocation")) {
            val single = name.contains("Single")
            try {
                XposedBridge.hookAllMethods(managerClass, name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // 1.1.32：移除 !HookState.deliverable() 门禁——用户先开小程序再开启伪装时，
                        // listener 若未被包装则永远进不了 pumps，伪装开启后自驱泵空转；
                        // wrapListener 内部已有未激活透传逻辑，此处无条件包壳
                        val args = param.args ?: return
                        for (i in args.indices) {
                            val arg = args[i] ?: continue
                            if (listenerClass.isInstance(arg) && !Proxy.isProxyClass(arg.javaClass)) {
                                val p = proxyMap[arg] ?: wrapListener(arg, listenerClass, locationClass, classLoader).also {
                                    proxyMap[arg] = it
                                    if (pumps.none { e -> e.orig === arg }) {
                                        pumps.add(PumpEntry(arg, listenerClass, locationClass, classLoader, single))
                                    }
                                    startPump()
                                }
                                args[i] = p
                                break
                            }
                        }
                    }
                })
                ok++
            } catch (t: Throwable) {
                XLog.w("TencentHook $name failed: ${t.message}")
            }
        }
        // 1.1.14 removeLocationUpdates 摘除：注册进 manager 的是代理，App 注销传原对象
        // 换回去才能真注销；同时停掉该 listener 的自驱投递（防幽灵回调）
        try {
            XposedBridge.hookAllMethods(managerClass, "removeLocationUpdates", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val args = param.args ?: return
                    for (i in args.indices) {
                        val a = args[i] ?: continue
                        if (listenerClass.isInstance(a)) {
                            pumps.removeAll { it.orig === a }
                            proxyMap.remove(a)?.let { args[i] = it }
                        }
                    }
                }
            })
            ok++
        } catch (t: Throwable) {
            XLog.w("TencentHook removeLocationUpdates failed: ${t.message}")
        }
        XLog.i("TencentLocationHook installed (ok/$ok, coordType gcj=$typeGcj wgs=$typeWgs)")
    }

    /** 自驱泵启动（幂等）；登记清空后 tick 自停 */
    private fun startPump() {
        if (pumpOn) return
        pumpOn = true
        pumpHandler.postDelayed(pumpTick, 300)
        XLog.i("Tencent 自驱投递启动（1Hz，不依赖 SDK 回调节拍）")
    }

    private val pumpTick = object : Runnable {
        override fun run() {
            try {
                if (pumps.isEmpty()) {
                    pumpOn = false
                    return
                }
                val fp = HookState.current()
                if (fp != null) {
                    for (e in pumps.toList()) {
                        try {
                            val fake = fakeLocation(lastReal, e.locationClass, e.classLoader, fp)
                            callOnChanged(e.orig, e.listenerClass, fake, 0, "")
                            if (e.single) pumps.remove(e) // 单次请求投一帧即摘除
                        } catch (t: Throwable) {
                        }
                    }
                }
            } catch (t: Throwable) {
            }
            pumpHandler.postDelayed(this, 1000)
        }
    }

    /** 创建代理 listener：拦截 onLocationChanged 缓存 backing，其他方法透传给原始 listener。 */
    private fun wrapListener(
        original: Any,
        listenerClass: Class<*>,
        locationClass: Class<*>,
        classLoader: ClassLoader
    ): Any {
        return Proxy.newProxyInstance(classLoader, arrayOf(listenerClass)) { _, method, args ->
            when (method.name) {
                "onLocationChanged" -> {
                    val origLoc = args?.getOrNull(0)
                    if (origLoc != null) lastReal = origLoc
                    val fp = HookState.current()
                    if (fp == null) {
                        // 伪装未激活：透传真实帧
                        callOnChanged(
                            original, listenerClass, origLoc,
                            (args?.getOrNull(1) as? Int) ?: 0,
                            (args?.getOrNull(2) as? String) ?: ""
                        )
                    }
                    // 激活时吞真实帧（含 error/null 回调）：节拍由 pump 统一自驱，防双份投递
                    null
                }
                "hashCode" -> original.hashCode()
                "equals" -> (args?.getOrNull(0) as Any?) === original
                "toString" -> original.toString()
                else -> {
                    try {
                        method.invoke(original, *(args ?: arrayOfNulls(0)))
                    } catch (t: Throwable) {
                        null
                    }
                }
            }
        } ?: original
    }

    /** 调用原始 listener 的 onLocationChanged（参数类型可能是 int 或 Integer，遍历匹配）。 */
    private fun callOnChanged(
        listener: Any,
        listenerClass: Class<*>,
        location: Any?,
        error: Int,
        reason: String
    ) {
        try {
            for (m in listenerClass.methods) {
                if (m.name == "onLocationChanged" && m.parameterTypes.size == 3) {
                    m.invoke(listener, location, error, reason)
                    return
                }
            }
            XLog.w("TencentHook onLocationChanged not found")
        } catch (t: Throwable) {
            XLog.w("TencentHook onLocationChanged failed: ${t.message}")
        }
    }

    /** 透传方法缺 backing 对象时的安全默认值（接口代理 original 可为 null） */
    private fun defaultFor(rt: Class<*>): Any? = when {
        rt == String::class.java -> ""
        rt == java.lang.Boolean.TYPE || rt == java.lang.Boolean::class.java -> false
        rt == Integer.TYPE || rt == Integer::class.java -> 0
        rt == java.lang.Long.TYPE || rt == java.lang.Long::class.java -> 0L
        rt == java.lang.Float.TYPE || rt == java.lang.Float::class.java -> 0f
        rt == java.lang.Double.TYPE || rt == java.lang.Double::class.java -> 0.0
        rt == android.os.Bundle::class.java -> android.os.Bundle()
        else -> null
    }

    /** 创建 TencentLocation 动态代理：getter 返回伪造值，其他方法调用原始对象。
     *  lat/lng 必须落在 getCoordinateType() 声明的坐标系里：fp 是 WGS-84，而腾讯
     *  默认请求 GCJ-02——直接塞 WGS 值并自称 GCJ02，微信会把它画在腾讯地图
     *  （GCJ-02 瓦片）的「WGS 数字位置」上，整体偏一个火星偏移量
     *（大连实测约 450m：1.1.11 微信位置偏到操场西边的根因）。 */
    private fun fakeLocation(
        original: Any?,
        locationClass: Class<*>,
        classLoader: ClassLoader,
        fp: HookState.FakePos
    ): Any {
        val coordType = try {
            locationClass.getMethod("getCoordinateType").invoke(original) as? Int ?: typeGcj
        } catch (t: Throwable) {
            typeGcj
        }
        val (tLat, tLng) = if (coordType == typeWgs) {
            Pair(fp.lat, fp.lng)
        } else {
            try {
                com.awei.spoofer.track.CoordUtil.wgsToGcj(fp.lat, fp.lng)
            } catch (t: Throwable) {
                Pair(fp.lat, fp.lng)
            }
        }
        return Proxy.newProxyInstance(classLoader, arrayOf(locationClass)) { _, method, args ->
            when (method.name) {
                "getLatitude" -> tLat
                "getLongitude" -> tLng
                // 1.1.16：恒 5.0f 是指纹——按 LocationHook gps 档同款公式（3-9m 确定性
                // 微抖，同一读数多次调用一致），与系统侧 getAccuracy 口径统一
                "getAccuracy" -> {
                    val sec = ((System.currentTimeMillis() - HookState.sessionSeed()) / 1000L).coerceAtLeast(0L)
                    val wave = 1.8 * Math.sin(sec / 17.0 * 2.0 * Math.PI) +
                        1.0 * Math.sin(sec / 3.3 * 2.0 * Math.PI)
                    val micro = ((sec * 2654435761L).toInt() % 9 - 4) * 0.1
                    (6.0 + wave + micro).coerceIn(3.0, 9.0).toFloat()
                }
                "getAltitude" -> fp.altitude
                "getSpeed" -> fp.speedMps.toFloat()
                "getBearing" -> fp.bearing.toFloat()
                // 返回类型随 SDK 版本可能是 String 或 int：按 method 声明类型给值，防代理拆箱 CCE
                "getProvider", "getProviderName" -> when {
                    method.returnType == String::class.java -> "gps"
                    method.returnType == Integer.TYPE || method.returnType == Integer::class.java -> 1
                    else -> if (original == null) defaultFor(method.returnType) else try {
                        method.invoke(original, *(args ?: arrayOfNulls(0)))
                    } catch (t: Throwable) {
                        defaultFor(method.returnType)
                    }
                }
                "getTime" -> System.currentTimeMillis()
                // 1.1.32：开机相对时间戳（毫秒/纳秒）。微信 AppBrandLocationManager 收到回调时
                // 会对比 elapsedRealtime 判断是否为过期/重复帧。旧实现未拦截，original==null 时
                // 恒返回 0L，有 original 时返回旧的固定时间戳不递增，微信静默判定为旧帧全量 drop
                //（导致小程序常驻"正在确认定位，请稍候"，跑步里程不走，暂停再开始闪现跃迁的致命根因）
                "getElapsedRealtime", "getElapsedRealTime", "getElapsedRealtimeMillis" ->
                    android.os.SystemClock.elapsedRealtime()
                "getElapsedRealtimeNanos" ->
                    android.os.SystemClock.elapsedRealtimeNanos()
                // 腾讯 SDK 专属 isMockGps 返回 int (0=非mock, 1=mock)，isMock 返回 boolean/int
                "isMockGps" -> 0
                "isMock" -> if (method.returnType == java.lang.Boolean.TYPE || method.returnType == java.lang.Boolean::class.java) false else 0
                // 额外信息与卫星数：微信读取 Extra 中的卫星数判定搜星状态，补齐防止 NPE 或搜星数不足
                "getExtra", "getExtras" -> {
                    val bundle = if (original != null) {
                        try {
                            (method.invoke(original, *(args ?: arrayOfNulls(0))) as? android.os.Bundle)?.let {
                                android.os.Bundle(it)
                            }
                        } catch (t: Throwable) {
                            null
                        }
                    } else null
                    (bundle ?: android.os.Bundle()).apply {
                        putInt("satellites", 10)
                        putInt("satellitesvalue", 10)
                    }
                }
                "getGPSRssi", "getGpsRssi" -> 3
                "getSourceProvider" -> if (method.returnType == String::class.java) "gps" else 1
                "getCoordinateType" -> coordType
                "hashCode" -> System.identityHashCode(original)
                "equals" -> (args?.getOrNull(0) as Any?) === original
                "toString" -> "FakeTencentLocation"
                else -> {
                    if (original == null) {
                        defaultFor(method.returnType)
                    } else try {
                        method.invoke(original, *(args ?: arrayOfNulls(0)))
                    } catch (t: Throwable) {
                        defaultFor(method.returnType)
                    }
                }
            }
        } ?: (original as Any)
    }
}
