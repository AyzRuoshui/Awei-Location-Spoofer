package com.awei.spoofer.xposed.hooks

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import com.awei.spoofer.xposed.XLog
import com.awei.spoofer.xposed.XposedInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 存在性反检测：PackageManager 侧门全堵。
 *
 * [DetectionHook.hookPackageManager] 已覆盖 `getPackageInfo`（擦 ACCESS_MOCK_LOCATION
 * 权限条目 + 路径字段擦除 + providers/receivers 过滤）与 `getInstalledApplications` /
 * `getInstalledPackages`（过滤自身）。本模块补齐其余"查本包是否存在"的 PackageManager 入口：
 *
 * 1. `getApplicationInfo` / `getApplicationInfoAsUser` — 擦 metaData 里 `xposedmodule` /
 *    `xposeddescription` / `xposedminversion` / `xposedscope` 四个 key（LSPosed 模块 manifest
 *    声明这四件套，是"扫 Xposed 模块"标准手法：遍历包 → GET_META_DATA → 看 xposedmodule）
 * 2. `queryIntentActivities` / `queryIntentActivitiesAsUser` — MainActivity exported+MAIN/LAUNCHER
 *    一条 Intent 反查即穿透 + `ActivityInfo.sourceDir` 泄露 base.apk 路径
 * 3. `resolveActivity` / `resolveActivityAsUser` — 命中自身返 null
 * 4. `getPackageUid` / `getPackageUidAsUser` — 命中自身返 -1（UID_NOT_FOUND）
 * 5. `getApplicationEnabledSetting` — 命中自身抛 NameNotFoundException（AOSP 文档语义：
 *    非存在包应抛此异常）
 * 6. `getInstalledPackagesAsUser` / `getInstalledApplicationsAsUser` — 过滤自身（DetectionHook
 *    只挂不带 AsUser 后缀的基类变体）
 * 7. `getPackagesForUid` — 过滤返回数组中 `com.awei.spoofer` 条目（防已拿到 uid 的检测方反查包名）
 * 8. `getPackageGids` — 命中自身返 null
 * 9. `queryContentProviders` / `queryContentProviderInfo` — 过滤 ProviderInfo[] 自身
 *    （挡 `com.awei.spoofer.config` / `com.awei.spoofer.logs` authority 枚举）
 *
 * **1.1.16 激活语义改为常开（不看 deliverable）**：存在性是独立于"是否在模拟"的事实——
 * "装了本模块 + LSPosed 启用了"本身就是检测/封号依据，App 启动即扫描而用户尚未启动
 * 模拟的窗口里透传真值等于全暴露。主 App 自身进程不受影响（SELF_PKG 分支不挂任何 hook）。
 * 拦截面只命中 SELF_PKG 条目，对目标 App 的其他查询零影响。
 *
 * 挂载规范（vc37 铁律）：每个挂载独立 try/catch + 成功计数上浮日志。
 */
object PackageHideHook {

    private var hooked = false

    private val SELF_PKG = XposedInit.SELF_PKG

    /** LSPosed 模块标识 meta-data key 集 */
    private val XPOSED_META_KEYS = arrayOf(
        "xposedmodule", "xposeddescription", "xposedminversion", "xposedscope"
    )

    fun hook() {
        if (hooked) return
        hooked = true

        // impl 类必挂：PackageManager 是抽象类，ApplicationPackageManager 才是运行时实例，
        // 虚分派只命中 impl 的 ArtMethod（参考 DetectionHook.hookPackageManager 的两处都挂）
        val implClass = try {
            XposedHelpers.findClass("android.app.ApplicationPackageManager", null)
        } catch (t: Throwable) {
            null
        }

        var ok = 0
        val total = 9

        runCatching { hookGetApplicationInfo(implClass) }.onSuccess { ok++ }
        runCatching { hookQueryIntentActivities(implClass) }.onSuccess { ok++ }
        runCatching { hookResolveActivity(implClass) }.onSuccess { ok++ }
        runCatching { hookGetPackageUid(implClass) }.onSuccess { ok++ }
        runCatching { hookGetApplicationEnabledSetting(implClass) }.onSuccess { ok++ }
        runCatching { hookGetInstalledAsUser(implClass) }.onSuccess { ok++ }
        runCatching { hookGetPackagesForUid(implClass) }.onSuccess { ok++ }
        runCatching { hookGetPackageGids(implClass) }.onSuccess { ok++ }
        runCatching { hookQueryContentProviders(implClass) }.onSuccess { ok++ }

        XLog.i("PackageHideHook 安装完成（$ok/$total 组，常开）")
    }

    private fun targets(impl: Class<*>?): List<Class<*>> =
        listOfNotNull(PackageManager::class.java, impl)

    // ---------- 1. getApplicationInfo：擦 xposed_* metaData ----------
    private fun hookGetApplicationInfo(impl: Class<*>?) {
        for (target in targets(impl)) {
            for (name in arrayOf("getApplicationInfo", "getApplicationInfoAsUser")) {
                try {
                    XposedBridge.hookAllMethods(target, name, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val pkg = param.args.firstOrNull() as? String ?: return
                            if (pkg != SELF_PKG) return
                            val ai = param.result as? ApplicationInfo ?: return
                            // 擦 LSPosed 模块标识 meta-data（"扫 Xposed 模块"标准手法）
                            // 不动其他 metaData 字段（App 自身业务 key 不在 xposed_* 命名空间）
                            ai.metaData?.let { md ->
                                for (k in XPOSED_META_KEYS) md.remove(k)
                            }
                        }
                    })
                } catch (t: Throwable) {
                    // AsUser 变体在某些 API 不存在：静默
                }
            }
        }
    }

    // ---------- 2. queryIntentActivities：过滤自身条目 ----------
    // MainActivity exported=true + MAIN/LAUNCHER：检测方 Intent.makeMainActivity 反查即穿透。
    // 返回的 ResolveInfo.activityInfo.sourceDir 含 base.apk 路径，过滤掉即不泄露
    private fun hookQueryIntentActivities(impl: Class<*>?) {
        for (target in targets(impl)) {
            for (name in arrayOf("queryIntentActivities", "queryIntentActivitiesAsUser")) {
                try {
                    XposedBridge.hookAllMethods(target, name, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val list = param.result as? List<*> ?: return
                            val filtered = list.filterNot { el ->
                                (el as? ResolveInfo)?.activityInfo?.packageName == SELF_PKG
                            }
                            if (filtered.size != list.size) {
                                param.result = filtered
                            }
                        }
                    })
                } catch (t: Throwable) {
                }
            }
        }
    }

    // ---------- 3. resolveActivity：命中自身返 null ----------
    private fun hookResolveActivity(impl: Class<*>?) {
        for (target in targets(impl)) {
            for (name in arrayOf("resolveActivity", "resolveActivityAsUser")) {
                try {
                    XposedBridge.hookAllMethods(target, name, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val ri = param.result as? ResolveInfo ?: return
                            if (ri.activityInfo?.packageName == SELF_PKG) {
                                param.result = null
                            }
                        }
                    })
                } catch (t: Throwable) {
                }
            }
        }
    }

    // ---------- 4. getPackageUid：命中自身抛 NameNotFoundException ----------
    // javap 实证（api-35 android.jar，1.1.17）：getPackageUid(String,int) 与
    // (String,PackageInfoFlags) 均声明 throws NameNotFoundException——包不存在时
    // 真机行为是"抛异常"，不是返 -1。此前返 -1 反而成了"包存在但被改过"的证据
    //（检测方 catch 不到异常即穿帮）。必须走 param.throwable（裸 throw 被框架吞，坑 #53）
    private fun hookGetPackageUid(impl: Class<*>?) {
        for (target in targets(impl)) {
            for (name in arrayOf("getPackageUid", "getPackageUidAsUser")) {
                try {
                    XposedBridge.hookAllMethods(target, name, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val pkg = param.args.firstOrNull() as? String ?: return
                            if (pkg == SELF_PKG) param.throwable = PackageManager.NameNotFoundException()
                        }
                    })
                } catch (t: Throwable) {
                }
            }
        }
    }

    // ---------- 5. getApplicationEnabledSetting：命中自身抛 NameNotFoundException ----------
    // AOSP 文档语义：非存在包应抛 NameNotFoundException。必须走 setThrowable 语义
    // （Kotlin 属性写法 param.throwable，before 阶段置 = returnEarly 跳过原方法 + 异常
    // 抛给调用方）——在回调里裸 throw 会被 Xposed 框架 catch 后只记日志，防御不生效
    private fun hookGetApplicationEnabledSetting(impl: Class<*>?) {
        for (target in targets(impl)) {
            try {
                XposedBridge.hookAllMethods(
                    target, "getApplicationEnabledSetting", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val pkg = param.args.firstOrNull() as? String ?: return
                            if (pkg == SELF_PKG) {
                                param.throwable = PackageManager.NameNotFoundException()
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
            }
        }
    }

    // ---------- 6. getInstalledPackagesAsUser / getInstalledApplicationsAsUser ----------
    // DetectionHook 只挂不带 AsUser 后缀的基类变体；这里补 AsUser 变体（系统 App / 多用户检测方旁路）
    private fun hookGetInstalledAsUser(impl: Class<*>?) {
        for (target in targets(impl)) {
            for (name in arrayOf("getInstalledPackagesAsUser", "getInstalledApplicationsAsUser")) {
                try {
                    XposedBridge.hookAllMethods(target, name, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val list = param.result as? List<*> ?: return
                            val filtered = list.filterNot { el ->
                                when (el) {
                                    is ApplicationInfo -> el.packageName == SELF_PKG
                                    is PackageInfo -> el.packageName == SELF_PKG
                                    else -> false
                                }
                            }
                            if (filtered.size != list.size) {
                                param.result = filtered
                            }
                        }
                    })
                } catch (t: Throwable) {
                }
            }
        }
    }

    // ---------- 7. getPackagesForUid：过滤返回数组中 SELF_PKG 条目 ----------
    // 检测方若已通过其他途径拿到 com.awei.spoofer 的 uid，再调 getPackagesForUid(uid)
    // 反查包名时会拿到 ["com.awei.spoofer"]——此处把自身从结果数组中过滤掉
    private fun hookGetPackagesForUid(impl: Class<*>?) {
        for (target in targets(impl)) {
            try {
                XposedBridge.hookAllMethods(target, "getPackagesForUid", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val arr = param.result as? Array<*> ?: return
                        val filtered = arr.filterIsInstance<String>().filterNot { it == SELF_PKG }
                        if (filtered.size != arr.size) {
                            @Suppress("UNCHECKED_CAST")
                            param.result = filtered.toTypedArray() as Array<String>
                        }
                    }
                })
            } catch (t: Throwable) {
            }
        }
    }

    // ---------- 8. getPackageGids：命中自身抛 NameNotFoundException ----------
    // javap 实证（api-35，与 getPackageUid 同轮 1.1.17）：getPackageGids 全重载同样声明
    // throws NameNotFoundException——真机对不存在包抛异常，返 null 同样是穿帮值
    private fun hookGetPackageGids(impl: Class<*>?) {
        for (target in targets(impl)) {
            try {
                XposedBridge.hookAllMethods(target, "getPackageGids", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pkg = param.args.firstOrNull() as? String ?: return
                        if (pkg == SELF_PKG) param.throwable = PackageManager.NameNotFoundException()
                    }
                })
            } catch (t: Throwable) {
            }
        }
    }

    // ---------- 9. queryContentProviders：过滤 ProviderInfo[] 自身 ----------
    // 挡 com.awei.spoofer.config / com.awei.spoofer.logs authority 枚举：检测方查全部
    // ContentProvider 列表时直接暴露我们的两个 authority 字符串
    private fun hookQueryContentProviders(impl: Class<*>?) {
        for (target in targets(impl)) {
            for (name in arrayOf("queryContentProviders", "queryContentProviderInfo")) {
                try {
                    XposedBridge.hookAllMethods(target, name, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val list = param.result as? List<*> ?: return
                            val filtered = list.filterNot {
                                (it as? ProviderInfo)?.packageName == SELF_PKG
                            }
                            if (filtered.size != list.size) {
                                param.result = filtered
                            }
                        }
                    })
                } catch (t: Throwable) {
                }
            }
        }
    }
}
