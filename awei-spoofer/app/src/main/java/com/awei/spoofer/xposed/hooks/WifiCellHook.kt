package com.awei.spoofer.xposed.hooks

import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.NeighboringCellInfo
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import com.awei.spoofer.xposed.HookState
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * WiFi / 基站一致性伪装（参考 XposedFakeLocation 的应用层方案）：
 * - WiFi 扫描结果伪造（1.1.13）：会话稳定假 AP 列表（含"已连接"的那个）——
 *   真实 BSSID 不泄露，且不再是"扫描为空却显示已连接"的自相矛盾态
 * - WiFi 连接信息脱敏：伪造自洽 WifiInfo（假 AP BSSID 与设备 MAC 严格分离）
 * - 基站信息伪造（1.1.13）：假服务小区 + 0-2 邻区（此前清空与 getCellLocation
 *   返假小区自相矛盾）；App 做不了基站交叉验证时回落 GPS（即我们的伪造位置）
 * 仅在伪装 active 时生效。
 */
object WifiCellHook {

    // 原回调 → 包装壳（API 31+ registerTelephonyCallback 替换映射；弱键防泄漏）
    private val cbSwapMap: MutableMap<TelephonyCallback, TelephonyCallback> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap())

    // 伪造 WiFi 身份（连接信息）：自洽假 AP，比空壳 <unknown ssid> 更不易触发
    // App 的"扫描为空/连接信息异常"降级逻辑。
    @Volatile var fakeSsid: String = "AndroidAP"
    // 空 = 自动生成（会话派生，1.1.13）；显式配置才用配置值。占位符 02:00:00:00:00:00
    // 视同未配置——它是全网 mock 工具的经典占位 BSSID，检测方指纹库头号条目
    @Volatile var fakeBssid: String = ""
    @Volatile var fakeRssi: Int = -60

    /** 经典占位 BSSID（XposedFakeLocation 默认值）——已成指纹，视同"未配置" */
    private const val PLACEHOLDER_BSSID = "02:00:00:00:00:00"

    // 会话派生缓存（1.1.13）：假 AP BSSID / 设备 MAC（严格分离）/ 基站 LAC/CID
    //（此前 LAC/CID 全用户固定 14258/37550——跨用户指纹；现随会话锚派生，跨用户不同）
    @Volatile private var genBssid: String? = null
    @Volatile private var genMac: String? = null
    @Volatile private var genLac = -1
    @Volatile private var genCid = -1
    // 1.1.16：缓存跟随会话锚失效——同进程第 2 个会话（startAt 变）必须重派生，
    // 否则"跨会话不同"的去指纹目标在长驻进程里打折
    @Volatile private var genSeed = 0L

    fun applyIdentity(ssid: String, bssid: String, rssi: Int) {
        fakeSsid = ssid.take(32)
        fakeBssid = if (bssid == PLACEHOLDER_BSSID) "" else bssid
        fakeRssi = rssi.coerceIn(-127, 0)
    }

    fun hook() {
        hookWifi()
        hookCell()
        XposedBridge.log("awei: WifiCellHook installed")
    }

    private fun isActive(): Boolean = try {
        // 统一按"能算出伪造位置"判定而非裸 active：路线缺失等"定位侧已放行真实"
        // 的场景下，WiFi/基站叙事同步放行，避免半伪装矛盾态（见 HookState.deliverable）
        HookState.deliverable()
    } catch (t: Throwable) {
        false
    }

    /** 会话锚变化时清缓存（1.1.16）：同进程跨会话身份必须换新 */
    private fun ensureSessionCache() {
        val seed = HookState.sessionSeed()
        if (genSeed != seed) {
            genBssid = null
            genMac = null
            genLac = -1
            genCid = -1
            genSeed = seed
        }
    }

    /** 生效的假 AP BSSID：显式配置优先，否则会话派生（1.1.13，弃 02:00 占位符） */
    private fun effectiveBssid(): String {
        if (fakeBssid.isNotBlank()) return fakeBssid
        ensureSessionCache()
        genBssid?.let { return it }
        val v = deriveMac(0xA9DEL)
        genBssid = v
        return v
    }

    /** 设备自身 MAC：与假 AP BSSID 严格分离——设备 MAC = 热点 BSSID 物理不可能（自杀指纹） */
    private fun deviceMac(): String {
        ensureSessionCache()
        genMac?.let { return it }
        val v = deriveMac(0xB4CDL)
        genMac = v
        return v
    }

    /** 会话派生伪 MAC（1.1.13）：本地管理位置位的随机单播地址，外观合法、跨会话不同 */
    private fun deriveMac(salt: Long): String {
        val r = java.util.Random(HookState.sessionSeed() xor salt)
        val b = IntArray(6) { r.nextInt(256) }
        b[0] = (b[0] and 0xFC) or 0x02 // 本地管理位 + 单播
        return b.joinToString(":") { String.format(java.util.Locale.US, "%02x", it) }
    }

    /** 会话派生基站 LAC（1.1.13，弃全局固定值） */
    private fun cellLac(): Int {
        ensureSessionCache()
        if (genLac < 0) {
            genLac = 10000 + java.util.Random(HookState.sessionSeed() xor 0x1ACL).nextInt(55000)
        }
        return genLac
    }

    /** 会话派生基站 CID（1.1.13，弃全局固定值） */
    private fun cellCid(): Int {
        ensureSessionCache()
        if (genCid < 0) {
            genCid = 10000 + java.util.Random(HookState.sessionSeed() xor 0xC1DL).nextInt(50000)
        }
        return genCid
    }

    // ---------- WiFi ----------

    private fun hookWifi() {
        val wmClass = WifiManager::class.java

        // 扫描结果伪造（1.1.13）：会话稳定假 AP 列表（含"已连接"的那个）。
        // 此前清空列表：真实 BSSID 确不泄露，但"扫描为空却显示已连接"自相矛盾，
        // 空扫描列表本身也是 mock/权限异常的标准指纹
        XposedBridge.hookAllMethods(wmClass, "getScanResults", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!isActive()) return
                param.result = fakeScanList()
            }
        })

        // API 30+ 连接信息伪造空壳；低版本靠 WifiInfo getter hook 脱敏
        if (Build.VERSION.SDK_INT >= 30) {
            XposedBridge.hookAllMethods(wmClass, "getConnectionInfo", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!isActive()) return
                    val fake = buildFakeWifiInfo()
                    if (fake != null) param.result = fake
                }
            })
        }

        // WifiInfo getter 兜底（全版本；API 30 下 getConnectionInfo 原值也经此脱敏）
        try {
            val wiClass = WifiInfo::class.java
            XposedHelpers.findAndHookMethod(wiClass, "getBSSID", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!isActive()) return
                    param.result = effectiveBssid()
                }
            })
            XposedHelpers.findAndHookMethod(wiClass, "getSSID", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!isActive()) return
                    param.result = "\"$fakeSsid\""
                }
            })
            XposedHelpers.findAndHookMethod(wiClass, "getRssi", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!isActive()) return
                    param.result = fakeRssi
                }
            })
            // 真实设备 MAC 是稳定设备指纹：会话派生假 MAC，与假 AP BSSID 严格分离
            //（1.1.13：此前两者同值——设备 MAC = 热点 BSSID 物理不可能，双重穿帮）
            XposedHelpers.findAndHookMethod(wiClass, "getMacAddress", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!isActive()) return
                    param.result = deviceMac()
                }
            })
            // SupplicantState 默认 DISCONNECTED 与假 AP 矛盾（App 读 isConnected 降级）→ COMPLETED
            //（WifiInfo.Builder 无 setSupplicantState setter，只能 getter hook 兜底；getFrequency 同理）
            XposedHelpers.findAndHookMethod(wiClass, "getSupplicantState", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!isActive()) return
                    param.result = android.net.wifi.SupplicantState.COMPLETED
                }
            })
            // 频率默认 0 异常（App 可能据此判 WiFi 不可用）→ 2.4GHz ch1 常见值
            XposedHelpers.findAndHookMethod(wiClass, "getFrequency", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!isActive()) return
                    param.result = 2412
                }
            })
        } catch (t: Throwable) {
        }
    }

    private fun buildFakeWifiInfo(): WifiInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                WifiInfo.Builder()
                    .setSsid("\"$fakeSsid\"".toByteArray())
                    .setBssid(effectiveBssid())
                    .setRssi(fakeRssi)
                    .setNetworkId(0)
                    .build()
            } else {
                null
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 会话稳定假 AP 扫描列表（1.1.13）：3-8 个（含"已连接"的 fakeSsid），同会话多次
     * 读取完全一致、跨会话不同。构造失败（ROM 差异）退回空列表 = 旧行为。
     */
    private fun fakeScanList(): List<Any> {
        return try {
            val out = ArrayList<Any>()
            val r = java.util.Random(HookState.sessionSeed() xor 0x5CA4L)
            val n = 3 + r.nextInt(6)
            val prefixes = arrayOf("ChinaNet-", "TP-LINK_", "HUAWEI-", "CMCC-", "Xiaomi_", "Tenda_")
            val freqs = intArrayOf(2412, 2437, 2462, 5180)
            for (i in 0 until n) {
                val sr = android.net.wifi.ScanResult()
                sr.SSID = if (i == 0) {
                    fakeSsid
                } else {
                    prefixes[r.nextInt(prefixes.size)] +
                        String.format(java.util.Locale.US, "%04X", r.nextInt(65536))
                }
                sr.BSSID = if (i == 0) effectiveBssid() else deriveMac(0x500L + i)
                sr.level = if (i == 0) fakeRssi else -(50 + r.nextInt(31))
                // 首项=已连接假 AP：频率固定 2412 与 WifiInfo.getFrequency 自洽（1.1.16）
                sr.frequency = if (i == 0) 2412 else freqs[r.nextInt(freqs.size)]
                sr.capabilities = "[WPA2-PSK-CCMP][ESS]"
                out.add(sr)
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    // ---------- 基站 ----------

    private fun hookCell() {
        val tmClass = TelephonyManager::class.java

        // ---- pull 侧清空 ----
        // 全部清空：风控无法基站定位/交叉验证，回落 GPS（即伪造位置）
        // 1.1.13：返"假服务小区 + 0-2 邻区"而非空列表——此前 getCellLocation 有服务
        // 小区但 getAllCellInfo 恒空（服务小区"存在又不存在"），交叉读取即自相矛盾
        XposedBridge.hookAllMethods(tmClass, "getAllCellInfo", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!isActive()) return
                param.result = fakeCellInfoList()
            }
        })
        XposedBridge.hookAllMethods(tmClass, "getCellLocation", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!isActive()) return
                // 伪造非空 GsmCellLocation：返回 null 会让未判空的旧版 App 在目标进程 NPE
                try {
                    val gcl = android.telephony.gsm.GsmCellLocation()
                    gcl.setLacAndCid(cellLac(), cellCid()) // 会话派生（1.1.13）
                    param.result = gcl
                } catch (t: Throwable) {
                    param.result = null
                }
            }
        })
        XposedBridge.hookAllMethods(tmClass, "getNeighboringCellInfo", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!isActive()) return
                param.result = emptyList<NeighboringCellInfo>()
            }
        })

        // ---- SIM/PLMN 身份（与假位置自洽：默认中国值；跨地伪装可配待办）----
        // 真机未 hook 时 GPS 境外 + PLMN 国内 = 交叉校验穿帮；返自洽中国值。
        // @hide 隐私值（getSubscriberId/getLine1Number/getSimSerialNumber）不 hook——
        // App 难拿 + 返 null 最安全；跨地伪装的可配 PLMN 记入待办
        try {
            XposedBridge.hookAllMethods(tmClass, "getSimOperator", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!isActive()) return
                    param.result = "46000" // 中国移动 MCC+MNC
                }
            })
            XposedBridge.hookAllMethods(tmClass, "getNetworkOperator", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!isActive()) return
                    param.result = "46000"
                }
            })
            XposedBridge.hookAllMethods(tmClass, "getSimCountryIso", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!isActive()) return
                    param.result = "cn"
                }
            })
            XposedBridge.hookAllMethods(tmClass, "getNetworkCountryIso", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!isActive()) return
                    param.result = "cn"
                }
            })
            XposedBridge.hookAllMethods(tmClass, "getNetworkType", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!isActive()) return
                    param.result = 13 // NETWORK_TYPE_LTE
                }
            })
            XposedBridge.hookAllMethods(tmClass, "getPhoneType", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!isActive()) return
                    param.result = 1 // PHONE_TYPE_GSM
                }
            })
        } catch (t: Throwable) {
        }

        // ---- push 侧拦截（pull 清了 push 漏掉，App 经回调仍能拿真基站）----
        // listen()：直接从事件掩码里摘掉 cell 相关位——系统不再投递基站事件给原 listener，
        // 其他事件（通话状态等）不受影响（手法参考 XposedFakeLocation）
        XposedBridge.hookAllMethods(tmClass, "listen", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isActive()) return
                if (param.args.size < 2) return
                val events = param.args[1] as? Int ?: return
                val cleared = events and
                    PhoneStateListener.LISTEN_CELL_LOCATION.inv() and
                    PhoneStateListener.LISTEN_CELL_INFO.inv()
                if (cleared != events) param.args[1] = cleared
            }
        })

        // registerTelephonyCallback（API 31+）：TelephonyCallback 是"无方法的具体类"，
        // AOSP 注册实现（TelephonyRegistryManager）第一步就 callback.init(executor)——类方法调用，
        // 动态代理（只实现 marker 接口）替换进去会 IncompatibleClassChangeError 崩掉目标 App。
        // 改用真实子类包装：仅当 callback 只实现 CellInfoListener 这一个 marker 时才替换成
        // "空列表转发壳"；混合回调不包装（否则 call state 等其他事件会整类丢失）
        if (Build.VERSION.SDK_INT >= 31) {
            XposedBridge.hookAllMethods(tmClass, "registerTelephonyCallback", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isActive()) return
                    for (i in param.args.indices) {
                        val cb = param.args[i] as? TelephonyCallback ?: continue
                        // 2 参公开重载内部委托 3 参重载会二次进入本 hook：防套娃包装
                        if (cb is CellInfoSuppressCallback) continue
                        val existing = cbSwapMap[cb]
                        if (existing != null) {
                            param.args[i] = existing
                            continue
                        }
                        val ifaces = try {
                            cb.javaClass.interfaces
                        } catch (t: Throwable) {
                            continue
                        }
                        if (ifaces.none { it.name.endsWith("CellInfoListener") }) continue
                        if (ifaces.any {
                                it.name.startsWith("android.telephony.TelephonyCallback$") &&
                                    !it.name.endsWith("CellInfoListener")
                            }
                        ) continue
                        val wrapper = CellInfoSuppressCallback(cb)
                        cbSwapMap[cb] = wrapper
                        param.args[i] = wrapper
                    }
                }
            })
            // App 注销的是它手里的原对象，系统登记的是包装壳：换回去才能真正注销
            XposedBridge.hookAllMethods(tmClass, "unregisterTelephonyCallback", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val cb = param.args.firstOrNull() as? TelephonyCallback ?: return
                    cbSwapMap.remove(cb)?.let { param.args[0] = it }
                }
            })
        }

        // requestCellInfoUpdate（API 29+）：异步单次请求，回调换成"空列表"壳
        if (Build.VERSION.SDK_INT >= 29) {
            XposedBridge.hookAllMethods(tmClass, "requestCellInfoUpdate", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isActive()) return
                    for (i in param.args.indices) {
                        val cb = param.args[i] ?: continue
                        if (cb !is TelephonyManager.CellInfoCallback) continue
                        param.args[i] = object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: MutableList<android.telephony.CellInfo>) {
                    try {
                        val m = cb.javaClass.methods.firstOrNull { it.name == "onCellInfo" } ?: return
                        // 投递时再判定：注册发生在 active、随后关闭伪装时也能恢复真实数据
                        val deliver = if (HookState.deliverable()) {
                            java.util.ArrayList<android.telephony.CellInfo>()
                        } else {
                            cellInfo
                        }
                        m.invoke(cb, deliver)
                    } catch (t: Throwable) {
                    }
                }

                            override fun onError(errorCode: Int, detail: Throwable?) {
                                try {
                                    cb.javaClass.methods.firstOrNull { it.name == "onError" }
                                        ?.invoke(cb, errorCode, detail)
                                } catch (t: Throwable) {
                                }
                            }
                        }
                    }
                }
            })
        }
    }

    /**
     * 会话稳定假小区列表（1.1.13）：1 个注册服务小区（与 getCellLocation 同 LAC/CID）
     * + 0-2 个同 LAC 邻区。字段经反射写入（CellIdentityGsm 构造签名跨版本有 int/String
     * 两代），构造失败退回空列表 = 旧行为。
     */
    private fun fakeCellInfoList(): List<Any> {
        return try {
            val out = ArrayList<Any>()
            val r = java.util.Random(HookState.sessionSeed() xor 0xCE11L)
            val lac = cellLac()
            val cid = cellCid()
            val neighbors = r.nextInt(3)
            for (i in 0..neighbors) {
                val ci = buildCellInfoGsm(
                    lac, if (i == 0) cid else cid + i, i == 0, -(65 + r.nextInt(15))
                ) ?: return emptyList()
                out.add(ci)
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun buildCellInfoGsm(lac: Int, cid: Int, registered: Boolean, dbm: Int): Any? {
        return try {
            // CellInfoGsm/CellSignalStrengthGsm/CellIdentityGsm 的无参构造在公开 SDK
            // 是 package-private（@hide）——一律反射 newInstance + 字段写入
            fun newInst(cn: String): Any {
                val c = Class.forName(cn).getDeclaredConstructor()
                c.isAccessible = true
                return c.newInstance()
            }
            val id = newInst("android.telephony.CellIdentityGsm")
            // 字段名 AOSP 两代并存（旧 mMcc/mMNC、新 mMccStr/mMncStr）：逐字段容错
            for ((k, v) in listOf(
                "mMcc" to "460", "mMccStr" to "460", "mMNC" to "00", "mMncStr" to "00"
            )) {
                try {
                    XposedHelpers.setObjectField(id, k, v)
                } catch (t: Throwable) {
                }
            }
            for ((k, v) in listOf("mLac" to lac, "mCid" to cid, "mPsc" to 0)) {
                try {
                    XposedHelpers.setIntField(id, k, v)
                } catch (t: Throwable) {
                }
            }
            val ci = newInst("android.telephony.CellInfoGsm")
            XposedHelpers.setObjectField(ci, "mCellIdentityGsm", id)
            XposedHelpers.setBooleanField(ci, "mRegistered", registered)
            try {
                XposedHelpers.setLongField(
                    ci, "mTimeStamp", android.os.SystemClock.elapsedRealtimeNanos()
                )
            } catch (t: Throwable) {
            }
            try {
                val ss = newInst("android.telephony.CellSignalStrengthGsm")
                XposedHelpers.setIntField(ss, "mSignalStrength", dbm)
                XposedHelpers.setIntField(ss, "mLevel", 3)
                XposedHelpers.setObjectField(ci, "mCellSignalStrengthGsm", ss)
            } catch (t: Throwable) {
            }
            ci
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * API 31+ 基站回调抑制壳：TelephonyCallback 的真实子类（动态代理替代不了——AOSP 注册
     * 路径 TelephonyRegistryManager.registerTelephonyCallback 第一步就 callback.init(executor)，
     * 对回调对象做类方法调用，只实现 marker 接口的代理会 IncompatibleClassChangeError 崩掉
     * 目标 App）。激活时把真实基站列表换成空列表投递给原回调；未激活时原样转发（关闭伪装即恢复）
     */
    private class CellInfoSuppressCallback(private val orig: TelephonyCallback) :
        TelephonyCallback(), TelephonyCallback.CellInfoListener {

        override fun onCellInfoChanged(cellInfo: MutableList<android.telephony.CellInfo>) {
            val deliver = if (isActive()) {
                java.util.ArrayList<android.telephony.CellInfo>()
            } else {
                cellInfo
            }
            try {
                (orig as? TelephonyCallback.CellInfoListener)?.onCellInfoChanged(deliver)
            } catch (t: Throwable) {
            }
        }
    }
}
