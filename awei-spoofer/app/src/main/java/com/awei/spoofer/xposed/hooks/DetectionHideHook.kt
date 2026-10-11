package com.awei.spoofer.xposed.hooks

import android.app.AppOpsManager
import android.location.Location
import android.os.Bundle
import com.awei.spoofer.xposed.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 深度隐藏 mock 痕迹：Location 对象身份层 + Settings/AppOps 兜底路径。
 *
 * 覆盖 [LocationHook]（坐标 getter + isMock/isFromMockProvider getter）与
 * [DetectionHook]（AppOpsManager 公开 check* / note*、Settings.Secure.getString 主路径、
 * PackageManager 反扫描、GnssStatus getter、GNSS 原始观测量阻断）未触及的泄露路径：
 *
 * 1. Location 写入侧：setMock / setIsFromMockProvider 参数强制置 false——
 *    堵住"外部/内部代码调 setMock(true) 或反射 setIsFromMockProvider(true)"的写入路径，
 *    让对象内部状态也不可能被真设上
 * 2. Location Bundle 元数据：getExtras / setExtras / set(Location) 剥离
 *    "mockLocation" key——检测方读 Location.getExtras().getBoolean("mockLocation") 是
 *    经典指纹，LocationHook 已用 HiddenApiBypass 清 flag 但不动 extras
 * 3. Location.getProvider 归一 gps：伪装引擎/测试 provider 名（如 "mock"/"fake"）
 *    归到合法集内，掩盖 provider 命名这个独立检测向量
 * 4. Settings.Secure.getStringForUser 兜底：DetectionHook 只覆盖 getString，多用户/
 *    系统应用走 getStringForUser 变体（SettingsBase 静态方法，Secure 继承）
 * 5. AppOpsManager unsafeCheckOp / unsafeCheckOpNoThrow（@hide）：DetectionHook 只覆盖
 *    公开 check* / note*，个别 OEM SDK 用 unsafeCheck* 绕过
 * 6. 服务端 com.android.server(.appop).AppOpsService：只有 system 作用域才生效，
 *    普通 App 作用域 findClass 找不到静默跳过（预期行为，不是 bug）
 * 7. SettingsProvider.call GET_secure mock_location binder 直连：检测方绕开
 *    Settings.Secure 走 ContentResolver.call(Settings.Secure.AUTHORITY, ...) 直达
 *    provider 的绕过路径
 * 8. ContentResolver.query settings/secure URI 直查绕过
 *
 * **1.1.16 激活语义改为常开（不看 deliverable）**：本模块全是"存在性/标记"类拦截——
 * mock 标记剥离、mock 设置键隐藏、mock op 探测伪装，均为独立于"是否在模拟"的暴露面
 *（装了工具/开过开发者选项就是检测依据，App 启动即扫）。常开的语义成本≈0：
 * 真实 GNSS 位置本就无 mockLocation key、真实 provider 名本就在合法集、目标 App 自查
 * mock op 真值本就是 ERRORED。数据类伪造（坐标/传感器/卫星等）不在本模块，仍归
 * deliverable() 门禁。主 App 自身进程不挂 hook，不受影响。
 *
 * 挂载规范（vc37 铁律）：每个挂载独立 try/catch + 成功分组计数上浮日志——
 * 单个 @hide 方法缺失（如 setMock 在 API 31 以下、SettingsProvider.call 在新版本签名变化）
 * 不能拖垮其余挂载。
 */
object DetectionHideHook {

    private var hooked = false

    /** Location extras Bundle 里标记 mock 的 key（AOSP Location.EXTRA_MOCK_LOCATION） */
    private const val MOCK_KEY = "mockLocation"

    /** 合法 provider 集（LocationHook.FAKE_PROVIDERS 一致）——其他归一到 "gps" */
    private val LEGIT_PROVIDERS = setOf("gps", "network", "passive", "fused")

    /** AppOpsManager.OP_MOCK_LOCATION 的运行时整数值（AOSP 稳定，DetectionHook 也用 58） */
    private const val OP_MOCK_LOCATION = 58

    /** SettingsProvider 的 GET_secure 方法名（binder 直连路径用） */
    private const val CALL_METHOD_GET_SECURE = "GET_secure"

    fun hook() {
        if (hooked) return
        hooked = true

        var ok = 0
        val total = 8

        runCatching { hookLocationSetters() }.onSuccess { ok++ }
        runCatching { hookLocationExtras() }.onSuccess { ok++ }
        runCatching { hookLocationProvider() }.onSuccess { ok++ }
        runCatching { hookSettingsSecureFallback() }.onSuccess { ok++ }
        runCatching { hookAppOpsUnsafe() }.onSuccess { ok++ }
        runCatching { hookAppOpsService() }.onSuccess { ok++ }
        runCatching { hookSettingsProviderCall() }.onSuccess { ok++ }
        runCatching { hookContentResolverQuery() }.onSuccess { ok++ }

        XLog.i("DetectionHideHook 安装完成（$ok/$total 组，常开）")
    }

    // ---------- 1. Location setter：写入侧强制置 false ----------
    // isMock/isFromMockProvider getter 已由 LocationHook 无条件置 false（覆盖"读"侧），
    // 这里补"写"侧：外部代码调 setMock(true) 或反射 setIsFromMockProvider(true) 都无效。
    // setMock/setIsFromMockProvider 始终置 false（不看伪装态），避免"关伪装时对象残留
    // mock 标记"跨态污染——真实系统对象本来就 false，置 false 无害

    private fun hookLocationSetters() {
        val cls = Location::class.java
        // setMock 是 API 31+ 公开方法；setIsFromMockProvider 是 @hide（LocationHook
        // 用 HiddenApiBypass 调用它清 flag）。低版本/无 @hide 方法时静默
        for (name in arrayOf("setMock", "setIsFromMockProvider")) {
            try {
                XposedBridge.hookAllMethods(cls, name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args.isEmpty()) return
                        param.args[0] = false
                    }
                })
            } catch (t: Throwable) {
                // API 不足（setMock 需 API 31+）或缺方法：静默
            }
        }
    }

    // ---------- 2. Location extras Bundle 剥离 ----------
    // 检测方读 Location.getExtras().getBoolean("mockLocation") 是经典指纹；
    // setExtras / getExtras / set(Location) 三条路径都要过一遍。
    // 常开零成本：真实 GNSS 位置本就无 mockLocation key，剥离是恒等变换

    private fun hookLocationExtras() {
        val cls = Location::class.java

        XposedHelpers.findAndHookMethod(cls, "getExtras", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                (param.result as? Bundle)?.let { stripMockKey(it) }
            }
        })

        XposedHelpers.findAndHookMethod(
            cls, "setExtras", Bundle::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    (param.args.getOrNull(0) as? Bundle)?.let { stripMockKey(it) }
                }
            }
        )

        // Location.set(Location other) 拷贝 other 的全部字段（含 extras）。
        // LocationHook 已覆写 self 的坐标 + HiddenApiBypass 清 flag，这里补 extras 剥离
        XposedHelpers.findAndHookMethod(cls, "set", Location::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val self = param.thisObject as? Location ?: return
                try {
                    self.extras?.let { stripMockKey(it) }
                } catch (t: Throwable) {
                }
            }
        })
    }

    private fun stripMockKey(extras: Bundle) {
        try {
            if (extras.containsKey(MOCK_KEY)) extras.remove(MOCK_KEY)
        } catch (t: Throwable) {
        }
    }

    // ---------- 3. Location.getProvider 归一 ----------
    // 引擎/测试 provider 名（"mock"/"fake"/自定义）不在合法集内时归到 "gps"，
    // 掩盖 provider 命名这个独立检测向量。真实 provider 名本就在合法集，常开零成本

    private fun hookLocationProvider() {
        XposedHelpers.findAndHookMethod(Location::class.java, "getProvider", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val p = param.result as? String ?: return
                if (p !in LEGIT_PROVIDERS) param.result = "gps"
            }
        })
    }

    // ---------- 4. Settings.Secure.getStringForUser 兜底 ----------
    // DetectionHook.hookSettings 已覆盖 getString；这里补 getStringForUser 变体——
    // 多用户/系统应用读取时走 SettingsBase 静态方法。参数布局随签名可能变动，扫 String 参数匹配。
    // mock_location_app 恒按"从未设置过"返 null：这条设置值直接点名本包，属存在性泄露

    private fun hookSettingsSecureFallback() {
        val cls = android.provider.Settings.Secure::class.java
        try {
            XposedBridge.hookAllMethods(cls, "getStringForUser", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    for (v in param.args) {
                        if (v == "mock_location") {
                            param.result = "0"
                            return
                        }
                        if (v == "mock_location_app") {
                            param.result = null
                            return
                        }
                    }
                }
            })
        } catch (t: Throwable) {
            // 变体不存在（低版本 API）或已被其他模块覆盖：静默
        }
    }

    // ---------- 5. AppOpsManager unsafeCheck* ----------
    // DetectionHook.hookAppOps 已覆盖公开 checkOp / checkOpNoThrow / noteOp / noteOpNoThrow；
    // 这里补 @hide 的 unsafeCheckOp / unsafeCheckOpNoThrow（个别 OEM SDK 用它绕过）。
    // 常开口径同 DetectionHook.hookAppOps：目标 App 自查自身 op 真值本就是 ERRORED

    private fun hookAppOpsUnsafe() {
        val cls = AppOpsManager::class.java
        for (name in arrayOf("unsafeCheckOp", "unsafeCheckOpNoThrow")) {
            try {
                XposedBridge.hookAllMethods(cls, name, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val op = param.args.firstOrNull() ?: return
                        // unsafeCheckOp 有 (String opName,...) 与 (int op,...) 两种重载：
                        // 原写法 op == OP_MOCK_LOCATION 对 String 重载恒 false → @hide 绕过
                        // 路径只堵住 int 重载一半。复用 DetectionHook.hookAppOps 的 when 形态
                        val opName = when (op) {
                            is String -> op
                            is Int -> if (op == OP_MOCK_LOCATION) "android:mock_location" else null
                            else -> null
                        }
                        if (opName == "android:mock_location") {
                            // 经 AOSP 字节码实证 unsafeCheckOp 全重载返 int，无 String 变体；
                            // mock_location defaultMode=ERRORED(2) 是真机真值，直接返
                            param.result = AppOpsManager.MODE_ERRORED
                        }
                    }
                })
            } catch (t: Throwable) {
            }
        }
    }

    // ---------- 6. 服务端 AppOpsService ----------
    // 只有 system 作用域（LSPosed 勾 com.android.server / zygote）才生效；
    // 普通 App 作用域 findClass 拿不到 → 静默跳过（预期行为，非 bug）
    // 覆盖 API≤28 的 com.android.server.AppOpsService 与 API≥29 的
    // com.android.server.appop.AppOpsService；checkOperation* 系列方法签名随版本变化大，
    // hookAllMethods 全挂、扫 int 参数匹配 OP_MOCK_LOCATION

    private fun hookAppOpsService() {
        val targets = arrayOf(
            "com.android.server.AppOpsService",           // API ≤ 28
            "com.android.server.appop.AppOpsService",     // API ≥ 29
        )
        for (cn in targets) {
            val cls = try {
                XposedHelpers.findClass(cn, null)
            } catch (t: Throwable) {
                null
            } ?: continue
            for (name in arrayOf("checkOperation", "checkOperationImpl", "checkOperationRaw")) {
                try {
                    XposedBridge.hookAllMethods(cls, name, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            // 扫所有 int 参数找 mock_location 的 op 值
                            if (param.args.any { it == OP_MOCK_LOCATION }) {
                                param.result = AppOpsManager.MODE_ERRORED
                            }
                        }
                    })
                } catch (t: Throwable) {
                }
            }
        }
    }

    // ---------- 7. SettingsProvider.call GET_secure 改写 ----------
    // 检测方绕开 Settings.Secure.getString 走 ContentResolver.call(
    //   Settings.Secure.AUTHORITY, "GET_secure", "mock_location", null) 直达 provider，
    // 返回 Bundle{value: "1"}——补 binder 直连路径的最后一环

    private fun hookSettingsProviderCall() {
        val cls = try {
            XposedHelpers.findClass("com.android.providers.settings.SettingsProvider", null)
        } catch (t: Throwable) {
            null
        } ?: return

        try {
            XposedBridge.hookAllMethods(cls, "call", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // 新签名 (context, authority, method, readPerm, writePerm, arg, extras)
                    // 旧签名 (method, arg, extras) 也兼容——扫 String 参数匹配
                    var isGetSecure = false
                    var mockKey: String? = null
                    for (v in param.args) {
                        val s = v as? String ?: continue
                        if (s == CALL_METHOD_GET_SECURE) isGetSecure = true
                        else if (s == "mock_location" || s == "mock_location_app") mockKey = s
                    }
                    if (!isGetSecure || mockKey == null) return
                    val bundle = param.result as? Bundle ?: return
                    when (mockKey) {
                        "mock_location" -> bundle.putString("value", "0")
                        "mock_location_app" -> bundle.putString("value", null)  // 按从未设置过返回
                    }
                }
            })
        } catch (t: Throwable) {
            XLog.w("SettingsProvider.call 挂载失败: ${t.javaClass.simpleName}")
        }
    }

    // ---------- 8. ContentResolver.query settings/secure 拦截 ----------
    // 检测方绕开 Settings.Secure.getString 走 ContentResolver.query 直接查 URI：
    // cr.query(Settings.Secure.CONTENT_URI, null, "name=?", ["mock_location"], null)——
    // 直接 URI 查询走 SQLite database 路径绕过 getString 系 hook。未覆盖：无 selection
    // 的全量查询（需包 Cursor 过滤行，复杂且实际攻击者极少用，记入待办）；selection 含
    // LIKE 'mock%' 的通配查询
    //
    // 1.1.17 自洽修正：此前一律返回空 MatrixCursor——但真机 mock_location 行恒存在
    //（未开 mock 时 value="0"，与 DetectionHook.hookSettings 的 getString 返 "0" 同
    // 口径），空结果反而是"这行被人抹了"的穿帮信号。现按真机口径：mock_location 返
    // 一行 value="0"；mock_location_app 真机未选 mock 时无此行，保持空表（自洽）
    private fun hookContentResolverQuery() {
        val cls = android.content.ContentResolver::class.java
        try {
            XposedBridge.hookAllMethods(cls, "query", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val uri = param.args.getOrNull(0) as? android.net.Uri ?: return
                    if (uri.authority != "settings") return
                    if (uri.path?.contains("secure") != true) return
                    // 老签名：(uri, projection, selection, selectionArgs, sortOrder[, CancellationSignal])
                    // selectionArgs 在 args[3]；新签名 (uri, projection, Bundle queryArgs, ...)
                    val selArgs = (param.args.getOrNull(3) as? Array<*>)?.mapNotNull { it as? String }
                        ?: (param.args.getOrNull(2) as? android.os.Bundle)?.getStringArray(
                            "android:query-arg-sql-selection-args"
                        )?.toList()
                    val hitMock = selArgs?.contains("mock_location") == true
                    val hitMockApp = selArgs?.contains("mock_location_app") == true
                    if (!hitMock && !hitMockApp) return
                    val projection = param.args.getOrNull(1) as? Array<out String>
                    val cols = projection ?: arrayOf("name", "value")
                    val c = android.database.MatrixCursor(cols)
                    if (hitMock) {
                        // 真机未开 mock 的设备该行存在且 value="0"：按投影逐列填
                        c.addRow(cols.map { col ->
                            when (col) {
                                "name" -> "mock_location"
                                "value" -> "0"
                                else -> null
                            }
                        })
                    }
                    // hitMockApp：真机未选 mock 应用时无此行 → 空表即真值
                    param.result = c
                }
            })
        } catch (t: Throwable) {
        }
    }
}
