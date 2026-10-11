package com.awei.spoofer.xposed.hooks

import android.app.AppOpsManager
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import com.awei.spoofer.xposed.HookState
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 反检测与 GPS 环境一致性：
 * 1. AppOpsManager checkOp/noteOp("android:mock_location") → "未 mock"真值口径（1.1.16 起常开）
 * 2. GnssStatus getter → 伪造卫星数/信噪比/方位/仰角（防 GPS 芯片级检测）
 * 3. NMEA 报文伪装（GGA/RMC 与 GnssFaker 同源）＋ GNSS 原始观测量注册阻断
 *
 * 1.1.16 门禁口径：存在性/身份类（AppOps mock 探测、Settings mock 键、PackageManager
 * 反扫描）**常开**——"装没装本模块、LSPosed 启没启用"是独立于"是否在模拟"的检测依据；
 * 数据类伪造（GnssStatus/NMEA/原始观测量阻断）保持 deliverable() 门禁（未模拟时放行真实）。
 */
object DetectionHook {

    fun hook() {
        hookAppOps()
        hookGnssStatus()
        hookGnssRawListeners()
        hookNmea()
        hookSettings()
        hookPackageManager()
    }

    /**
     * NMEA listener 代理：目标 App 注册 NMEA 监听后，
     * 定时收到与伪装位置一致的 GGA/RMC 报文（GPS 关闭时同样有效）。
     */
    private fun hookNmea() {
        // addNmeaListener / removeNmeaListener 是 API 26+：低版本挂载必抛
        // IllegalArgumentException，直接 return（对比 hookGnssStatus 的 SDK_INT<24 门禁）
        if (Build.VERSION.SDK_INT < 26) return
        val lmClass = LocationManager::class.java

        class NmeaProxy(private val listener: Any?) {
            // lazy：hook 安装期可能早于主线程 Looper 就绪（同 XposedInit 的坑），
            // 首次使用在 start()（App 注册 NMEA 监听时），Looper 必已就绪
            private val handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
            private var stopped = false
            private var idx = 0
            // 现代接口 OnNmeaMessageListener.onNmeaMessage(msg, elapsedNanos)；
            // 旧接口 GpsStatus.NmeaListener.onNmeaReceived(utcMillis, msg)——参数顺序相反
            private val onNmea = listOf("onNmeaMessage", "onNmeaReceived")
                .map { name -> listener?.javaClass?.methods?.firstOrNull { it.name == name } }
                .firstOrNull { it != null }

            private val tick = object : Runnable {
                override fun run() {
                    if (stopped || listener == null) return
                    try {
                        HookState.current()?.let { fp ->
                            val msg = if (idx++ % 2 == 0) NmeaGenerator.gga(fp) else NmeaGenerator.rmc(fp)
                            when (onNmea?.name) {
                                "onNmeaMessage" ->
                                    onNmea.invoke(listener, msg, android.os.SystemClock.elapsedRealtimeNanos())
                                "onNmeaReceived" ->
                                    onNmea.invoke(listener, System.currentTimeMillis(), msg)
                            }
                        }
                    } catch (t: Throwable) {
                    }
                    handler.postDelayed(this, 1000)
                }
            }

            fun start() {
                handler.postDelayed(tick, 800)
            }

            fun stop() {
                stopped = true
                handler.removeCallbacks(tick)
            }
        }

        val proxies = java.util.concurrent.ConcurrentHashMap<Any, NmeaProxy>()
        // 系统（TelephonyRegistry）登记的是下面的动态代理：original→代理 必须存下来，
        // removeNmeaListener 时换回——否则系统 remove(original) 查无此登记，真注销不发生：
        // 已注销的监听器在用户开启伪装后"复活"收伪造报文，重复注册还会叠加多份代理
        val sysProxies = java.util.concurrent.ConcurrentHashMap<Any, Any>()

        // 两处 hookAllMethods 共用一个外层 try/catch：addNmeaListener / removeNmeaListener 同
        // API 26+ 生命周期，一个失败另一个必失败，独立 try/catch 无收益；外层包住保证不
        // 拖垮后续 hookSettings / hookPackageManager（vc37 逐模块独立铁律）
        try {
        XposedBridge.hookAllMethods(lmClass, "addNmeaListener", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                for (i in param.args.indices) {
                    val a = param.args[i] ?: continue
                    val cn = a.javaClass.name
                    val nmeaInterface = a.javaClass.interfaces.firstOrNull {
                        it.name.endsWith("OnNmeaMessageListener") || it.name.endsWith("NmeaListener")
                    } ?: (if (cn.endsWith("OnNmeaMessageListener") || cn.endsWith("NmeaListener"))
                        a.javaClass else null) ?: continue
                    // 同一 listener 重复注册时复用已有 NmeaProxy：否则每次注册都新增一个
                    // 1s 定时器，伪造报文成倍重复且旧实例永远停不掉
                    proxies.getOrPut(a) { NmeaProxy(a).also { it.start() } }
                    // 同一 listener 复用同一个动态代理：防止重复注册在系统侧叠加 N 份登记
                    param.args[i] = sysProxies.getOrPut(a) {
                        java.lang.reflect.Proxy.newProxyInstance(
                            a.javaClass.classLoader ?: lmClass.classLoader,
                            arrayOf(nmeaInterface)
                        ) { _, method, margs ->
                            if (method.name == "onNmeaMessage" || method.name == "onNmeaReceived") {
                                if (HookState.current() != null) null
                                else try {
                                    method.invoke(a, *(margs ?: emptyArray()))
                                    null
                                } catch (t: Throwable) {
                                    null
                                }
                            } else {
                                try {
                                    method.invoke(a, *(margs ?: emptyArray()))
                                } catch (t: Throwable) {
                                    null
                                }
                            }
                        }
                    }
                }
            }
        })

        XposedBridge.hookAllMethods(lmClass, "removeNmeaListener", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                for (i in param.args.indices) {
                    val a = param.args[i] ?: continue
                    proxies.remove(a)?.stop()
                    // 系统登记的是动态代理：换回去才能真注销
                    sysProxies.remove(a)?.let { param.args[i] = it }
                }
            }
        })
        } catch (t: Throwable) {
            XposedBridge.log("awei: hookNmea addNmeaListener/removeNmeaListener hook failed: $t")
        }
    }

    /**
     * Settings.Secure.getString 中 mock 相关键探测伪装
     */
    private fun hookSettings() {
        try {
            XposedBridge.hookAllMethods(
                android.provider.Settings.Secure::class.java, "getString", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // 1.1.5 修复：原 key 索引硬编码 args[1] 对未来 AOSP 签名变化不够鲁棒，
                        // 且若有不同签名的重载出现会漏。改为扫 args 找首个 String 参数
                        // （getString 重载多：2 参 (ContentResolver,String) / 3 参带 default 值等，
                        // key 始终在 String 类型位置）。与 DetectionHideHook.getStringForUser 同模式
                        val key = param.args.firstOrNull { it is String } as? String ?: return
                        when (key) {
                            "mock_location" ->
                                // 返回 "0" 比 null 更接近真机：真实未开 mock 的设备读这个键就是 "0"
                                param.result = "0"
                            "mock_location_app" ->
                                // PackageManager 抹不掉这条系统设置路径：检测方直接读它拿包名，
                                // 伪装开启时按"从未设置过"返回 null（真实未选 mock 的设备即如此）
                                param.result = null
                        }
                    }
                }
            )
        } catch (t: Throwable) {
        }
    }

    /**
     * PackageManager 反扫描：标准检测库（MockLocationDetector 一系）靠
     * "遍历全部 App 的 ACCESS_MOCK_LOCATION 权限"识别出本模块——把本包从结果里抹干净
     */
    private fun hookPackageManager() {
        val self = com.awei.spoofer.xposed.XposedInit.SELF_PKG
        // 注意：ACCESS_MOCK_LOCATION 在新 SDK 已从 Manifest.permission 移除，只能用字符串
        val MOCK_PERM = "android.permission.ACCESS_MOCK_LOCATION"

        // PackageManager 是抽象类，运行时实例是 ApplicationPackageManager（override 了这些方法）：
        // 只挂抽象类声明对被覆写的方法不生效，必须连 impl 类一起挂（两处都挂不会双触发——
        // 虚分派只会命中其中一个 ArtMethod）
        val implClass = try {
            XposedHelpers.findClass("android.app.ApplicationPackageManager", null)
        } catch (t: Throwable) {
            null
        }
        for (target in listOfNotNull(android.content.pm.PackageManager::class.java, implClass)) {
            val isImpl = target === implClass
            // PackageManager 与 getInstalledApplications/getInstalledPackages 在抽象类上是
            // abstract 声明：主流 LSPosed hookAllMethods 会直接抛 IllegalArgumentException。
            // impl 类（ApplicationPackageManager）挂载才是主力（虚分派必经 impl 的 ArtMethod）；
            // 抽象类挂载尽力而为，失败不影响 impl——每个挂载独立 try/catch，一个失败不断链
            try {
                XposedBridge.hookAllMethods(target, "getPackageInfo", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val pi = param.result as? android.content.pm.PackageInfo ?: return
                        if (pi.packageName != self) return
                        // 1. ACCESS_MOCK_LOCATION 权限条目擦除（原逻辑——本构建 manifest
                        // 已无该声明，perms 多为 null；保留作纵深防御，未来若重新声明也覆盖）
                        val perms = pi.requestedPermissions
                        if (perms != null) {
                            val flags = pi.requestedPermissionsFlags
                            val keep = perms.indices.filter { perms[it] != MOCK_PERM }
                            if (keep.size != perms.size) {
                                pi.requestedPermissions = Array(keep.size) { perms[keep[it]] }
                                if (flags != null && flags.size == perms.size) {
                                    pi.requestedPermissionsFlags = IntArray(keep.size) { flags[keep[it]] }
                                }
                            }
                        }
                        // 2. 路径字段擦除（1.1.5 新增）：防 base.apk 路径泄露——
                        // ApplicationInfo.sourceDir = "/data/app/com.awei.spoofer-*/base.apk"，
                        // splitSourceDirs / nativeLibraryDir 同理。原 hook 只删权限条目
                        // 路径原样保留，检测方可读路径即证明本包存在
                        pi.applicationInfo?.let { ai ->
                            ai.sourceDir = null
                            ai.publicSourceDir = null
                            ai.splitSourceDirs = null
                            ai.splitPublicSourceDirs = null
                            ai.dataDir = null
                            ai.nativeLibraryDir = null
                        }
                        // 3. providers/receivers 过滤（1.1.5 新增）：检测方
                        // getPackageInfo(GET_PROVIDERS) 拿到的 ProviderInfo[] 含
                        // com.awei.spoofer.config / com.awei.spoofer.logs 两个 authority——
                        // 过滤掉后包内看起来没有任何 ContentProvider
                        pi.providers?.let { arr ->
                            val filtered = arr.filter { it.packageName != self }
                            if (filtered.size != arr.size) pi.providers = filtered.toTypedArray()
                        }
                        pi.receivers?.let { arr ->
                            val filtered = arr.filter { it.packageName != self }
                            if (filtered.size != arr.size) pi.receivers = filtered.toTypedArray()
                        }
                    }
                })
            } catch (t: Throwable) {
                // impl 类挂载失败才值得记（抽象类失败是预期内的运行时差异）
                if (isImpl) XposedBridge.log("awei: PM impl getPackageInfo hook failed: $t")
            }

            for (name in arrayOf("getInstalledApplications", "getInstalledPackages")) {
                try {
                    XposedBridge.hookAllMethods(target, name, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val list = param.result as? MutableList<Any> ?: return
                            val filtered = list.filter { el ->
                                val pkg = when (el) {
                                    is android.content.pm.ApplicationInfo -> el.packageName
                                    is android.content.pm.PackageInfo -> el.packageName
                                    else -> null
                                }
                                pkg != self
                            }
                            if (filtered.size != list.size) param.result = filtered.toMutableList()
                        }
                    })
                } catch (t: Throwable) {
                    if (isImpl) XposedBridge.log("awei: PM impl $name hook failed: $t")
                }
            }
        }
    }

    private fun hookAppOps() {
        val cls = AppOpsManager::class.java
        for (name in arrayOf("checkOpNoThrow", "checkOp", "noteOpNoThrow", "noteOp")) {
            try {
                XposedBridge.hookAllMethods(cls, name, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // 1.1.16 常开（不看 deliverable）：目标 App 自查自身 op 时真实值
                        // 本就是 ERRORED（它没拿 mock 授权），替换零语义成本；对本包 uid 的
                        // 探测则始终藏起"持 mock 权限"这一存在性事实
                        val op = param.args.firstOrNull() ?: return
                        val opName = when (op) {
                            is String -> op
                            is Int -> if (op == 58) "android:mock_location" else null
                            else -> null
                        }
                        if (opName == "android:mock_location") {
                            // 经 AOSP 字节码实证（api-29/35/17 真实框架 jar）：所有
                            // checkOp/noteOp/unsafeCheckOp 重载均返 int，无 String 重载；
                            // mock_location 的 defaultMode = MODE_ERRORED(2) 正是真机未 mock
                            // 时的真值（非异常特征）。直接返 MODE_ERRORED 最贴近真机。
                            // 1.1.2 曾误判"有 String 重载"加 returnType 分支（永不触发的死
                            // 代码），1.1.4 删——返 null 反而偏离真机真值
                            param.result = AppOpsManager.MODE_ERRORED
                        }
                    }
                })
            } catch (t: Throwable) {
            }
        }
    }

    private fun hookGnssStatus() {
        if (Build.VERSION.SDK_INT < 24) return
        val cls = GnssStatus::class.java

        // 动态伪造（GnssFaker）：按 (会话种子 + 整秒 + 卫星序号) 派生确定性值，
        // 同秒内多次 getter 一致、跨秒变化、跨会话不同——破除此前"恒 10 颗 /
        // 确定性公式 / 只 GPS"的跨会话指纹（参考 HookState 固定 seed 漂移序列
        // 一模一样的同类教训）
        val hooks = mapOf<String, (Int) -> Any?>(
            "getSatelliteCount" to { _ -> GnssFaker.count() },
            "getSvid" to { i -> GnssFaker.svid(i) },
            "getCn0DbHz" to { i -> GnssFaker.cn0(i) },
            "getAzimuthDegrees" to { i -> GnssFaker.azimuth(i) },
            "getElevationDegrees" to { i -> GnssFaker.elevation(i) },
            "getConstellationType" to { i -> GnssFaker.constellation(i) },
            "usedInFix" to { i -> GnssFaker.usedInFix(i) },
            "hasEphemerisData" to { _ -> true },
            "hasAlmanacData" to { _ -> true },
            "hasCarrierFrequencyHz" to { _ -> false },
        )
        for ((name, fn) in hooks) {
            try {
                XposedBridge.hookAllMethods(cls, name, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!HookState.deliverable()) return
                        val idx = param.args.firstOrNull() as? Int ?: 0
                        param.result = fn(idx)
                    }
                })
            } catch (t: Throwable) {
            }
        }
        XposedBridge.log("awei: DetectionHook installed")
    }

    /**
     * GnssStatus 动态伪造器：按 (HookState.fakerSeed() + 卫星序号) 派生确定性伪随机值。
     * 纯函数无缓存——fakerSeed() 同秒内返同值（基于整秒 elapsedSec），故同一秒内
     * getSatelliteCount + 多次 getSvid/getCn0/... 调用读到的卫星集自洽；跨秒/跨会话
     * 种子变化，破除跨会话统计指纹。开销可忽略（GnssStatus getter 调用稀疏）。
     *
     * 1.1.13 重构为"单星记录"：一次确定性抽样出 (星座,SVID,CN0,仰角,方位,used) 全字段，
     * 各 getter 从同一记录取值——修两类跨字段穿帮：①SVID 此前全星座恒 1-32（GLONASS/北斗
     * 合法区间根本不是 1-32，(星座,SVID) 组合可判伪）②CN0 与仰角零相关（低仰角满格信噪比
     * 是人造特征）。GnssFaker 提升为公开：NmeaGenerator 的 GGA 卫星数/HDOP 与本对象同源
     * （报文 vs GnssStatus 交叉验证口径一致）。
     */
    object GnssFaker {
        private const val PRIME = 0x9E3779B1L

        private class Sat(
            val constellation: Int, val svid: Int, val cn0: Float,
            val azimuth: Float, val elevation: Float, val used: Boolean,
        )

        /** 单星全字段记录：抽样顺序固定，各 getter 独立重放也得到同一记录 */
        private fun sat(i: Int): Sat {
            val r = java.util.Random(HookState.fakerSeed() xor (i.toLong() * PRIME))
            val constellation = when (r.nextInt(10)) {
                0, 1 -> 2  // GLONASS
                2, 3 -> 5  // BEIDOU
                4 -> 6     // GALILEO
                else -> 1  // GPS
            }
            // SVID 按星座出合法区间（AOSP GnssStatus.getSvid() 口径）：
            // GPS 1-32 / GLONASS 65-96(FCN+64) / 北斗 201-237 / Galileo 1-36
            //（OS ICD SVID 1-50 的在用子集，与真机 HAL 实测口径一致）
            val svid = when (constellation) {
                2 -> 65 + r.nextInt(32)
                5 -> 201 + r.nextInt(37)
                6 -> 1 + r.nextInt(36)
                else -> 1 + r.nextInt(32)
            }
            val elevation = (5 + r.nextInt(76)).toFloat()               // 5-80°
            val cn0 = 20f + elevation * 0.22f + r.nextInt(6).toFloat()  // ~21-40 dB-Hz，随仰角正相关
            val azimuth = r.nextInt(360).toFloat()
            val used = r.nextInt(10) != 0
            return Sat(constellation, svid, cn0, azimuth, elevation, used)
        }

        fun count(): Int = 9 + java.util.Random(HookState.fakerSeed()).nextInt(3) // 9-11

        fun svid(i: Int): Int = sat(i).svid

        fun cn0(i: Int): Float = sat(i).cn0

        fun azimuth(i: Int): Float = sat(i).azimuth

        fun elevation(i: Int): Float = sat(i).elevation

        /** 混合星座：GPS(1) 权重 6/10，GLONASS(2)/BEIDOU(5)/GALILEO(6) 各占余量 */
        fun constellation(i: Int): Int = sat(i).constellation

        /** 多数卫星参与定位，约 1/10 不参与（真实 GNSS 如此，恒 true 反而不自然） */
        fun usedInFix(i: Int): Boolean = sat(i).used

        /** GGA 报文 HDOP，与 count() 同种子（NMEA 与 GnssStatus 交叉验证同口径） */
        fun hdop(): Float {
            val r = java.util.Random(HookState.fakerSeed() xor 0x5F5E105L)
            return (0.8 + r.nextInt(8) / 10.0).toFloat() // 0.8-1.5
        }
    }

    /**
     * GNSS 原始观测量监听阻断（参考 XposedFakeLocation / GpsSetter 开源分支）：
     * 伪距/钟差（GnssMeasurements）、导航电文、天线信息、批量定位四类原始数据
     * 无法低成本伪造自洽，直接在注册处拒绝（返回 false）——检测方拿不到原始观测量
     * 就无法做芯片级一致性校验；未开启伪装时全量透传。各方法随 API 24/28/30/31
     * 分批出现，hookAllMethods 按名挂载 + try/catch，缺哪个都不影响其他。
     */
    private fun hookGnssRawListeners() {
        val lmClass = LocationManager::class.java
        val blocked = arrayOf(
            "registerGnssMeasurementsCallback",
            "registerGnssMeasurementCallback",      // API 24-27 旧名（单数）
            "registerGnssNavigationMessageCallback",
            "registerGnssAntennaInfoListener",
            "registerGnssBatchedLocationCallback",
            // 1.1.16：registerGnssStatusCallback 移出阻断名单——javap（api-35 android.jar）
            // 实证它返回 boolean（1.1.13 注释误记 void），setResult(false) 一直在拒注册：
            // GnssStatus 属可伪造数据面，注册被拒 = GnssFaker 假天空图永远送不出去 +
            // "有定位无卫星"叙事矛盾。放行注册，数据面交 hookGnssStatus 伪造覆盖。
            // 注意：不要加 registerGnssNmeaCallback——会与我们自己的伪造 NMEA 注入打架
            // （DetectionHook.hookNmea 定时发 GGA/RMC，若注册处也阻断则伪造报文发不出去）
        )
        for (name in blocked) {
            try {
                XposedBridge.hookAllMethods(lmClass, name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (HookState.current() == null) return
                        // 1.1.13 真阻断 + 1.1.16 修正：本家族（Measurements 新旧两名/
                        // NavigationMessage/AntennaInfo/BatchedLocation）全部返回 boolean
                        // （false = "GNSS 原始数据不可用"）——setResult(false) 触发 returnEarly
                        // 即拒掉注册；此前注释误记"全是 void"致本 hook 长期空壳。
                        // registerGnssStatusCallback 同为 boolean（1.1.13 又误记 void 而被
                        // 一并误阻断），1.1.16 已移出名单——它是可伪造数据面，
                        // 交给 hookGnssStatus 覆盖
                        val returnsBoolean =
                            (param.method as? java.lang.reflect.Method)?.returnType == java.lang.Boolean.TYPE
                        if (returnsBoolean) {
                            param.setResult(false)
                        }
                    }
                })
            } catch (t: Throwable) {
            }
        }
    }
}
