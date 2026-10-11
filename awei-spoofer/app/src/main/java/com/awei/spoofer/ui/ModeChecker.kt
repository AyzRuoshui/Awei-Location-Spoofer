package com.awei.spoofer.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.awei.spoofer.data.HookStatusStore
import com.awei.spoofer.data.Prefs
import com.awei.spoofer.engine.mock.MockLocationService

/**
 * 体检：满血版（Xposed Hook 注入）检查项列表（状态+文案+引导动作），状态页渲染。
 * 每个失败项（✗）必须给出明确的下一步；弱信号只提醒（⚠）不阻断。
 * 无 Root 的系统级模拟已拆分到独立「免Root版」App，本清单只面向 Hook 链路。
 */
object ModeChecker {

    // 检查项状态
    const val ST_OK = 0     // ✓ 绿
    const val ST_FAIL = 1   // ✗ 红（有明确下一步）
    const val ST_WARN = 2   // ⚠ 橙（弱信号/提醒，不阻断）
    const val ST_INFO = 3   // · 灰（纯提示）

    // 项对应的引导动作（状态页据此显隐按钮）
    const val A_NONE = 0
    const val A_LOCATION_PERM = 1   // 去授权定位权限
    const val A_OPEN_LSPOSED = 3    // 打开 LSPosed

    data class Item(val state: Int, val text: String, val action: Int = A_NONE)

    /** 定位权限（地图「定位到我」用；Hook 注入本身不依赖它） */
    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // 弱信号 10 秒缓存（root/LSPosed 探测有磁盘/包管理 IO，不随 1s 轮询重跑）
    private var weakAt = 0L
    private var rootFound = false
    private var lspFound = false

    /** "重新体检"时强制弱信号重探 */
    fun invalidateWeak() {
        weakAt = 0
    }

    fun build(ctx: Context): List<Item> {
        val items = ArrayList<Item>()
        // 强信号：本 App 进程是否被 LSPosed 注入并写了自标记（SP 持久化，跨重启仍可见）
        val selfHooked = HookStatusStore.selfHooked(ctx)

        // 1. 定位权限：只影响地图「定位到我」，不影响 Hook 伪装——提醒不阻断
        if (hasLocationPermission(ctx)) {
            items.add(Item(ST_OK, "定位权限已授权"))
        } else {
            items.add(Item(ST_WARN, "定位权限未授权：不影响模拟，仅地图「定位到我」不可用", A_LOCATION_PERM))
        }

        // 2. 模拟服务运行（Hook 配置由它经 provider 下发给目标 App 内的 Hook 进程）
        if (Prefs.isRunning && MockLocationService.hasInstance) {
            items.add(Item(ST_OK, "模拟服务运行中（配置正常下发）"))
        } else {
            items.add(Item(ST_FAIL, "模拟服务未运行（点上方「启动模拟」按钮）"))
        }

        // 目标 App 心跳先取：后面 LSPosed/模块项的判定要用（心跳新鲜=注入链路其实已通）
        val latest = HookStatusStore.snapshot(ctx).firstOrNull { !it.self }
        val targetAlive = latest != null && System.currentTimeMillis() - latest.ts <= 10 * 60_000L

        weakProbe(ctx)
        // 3. Root 环境（弱信号：未检出≠没有，Magisk 可隐藏）
        items.add(if (rootFound) Item(ST_OK, "检测到 Root 环境")
        else Item(ST_WARN, "未检测到 Root（可能被隐藏了，不影响继续）"))
        // 4. LSPosed 管理器（弱信号）：新版 LSPosed 会隐藏/寄生管理器，
        //    探测不到≠没装；selfHooked/目标心跳才是权威——任一成立就说明 LSPosed 在正常工作
        val lspOk = lspFound || selfHooked || targetAlive
        items.add(if (lspOk) Item(ST_OK, "检测到 LSPosed")
        else Item(ST_WARN, "未检测到 LSPosed 管理器入口（新版会隐藏自身；以下方模块/心跳项为准）"))
        // 5. 模块自注入（强信号）：selfHooked=本 App 进程被注入且 attach 钩子写了自标记；
        //    selfLoadedInProcess=本进程内模块 handleLoadPackage 跑过（进程内静态标志）。
        //    心跳新鲜但本 App 未注入 → 降为 INFO：部分 LSPosed 版本作用域列表里找不到模块自身，
        //    本 App 自注入只是自检手段，功能不依赖它
        when {
            selfHooked -> items.add(Item(ST_OK, "模块已启用：LSPosed 已注入本 App"))
            HookStatusStore.selfLoadedInProcess -> items.add(Item(ST_WARN,
                "模块已加载进本 App 进程，但自标记未写入（异常）。点「运行日志」导出发给开发者"))
            targetAlive -> items.add(Item(ST_INFO,
                "本 App 未勾作用域（部分 LSPosed 版本列表里找不到模块自身，跳过即可）：不影响功能，目标 App 注入正常，以下方心跳为准"))
            else -> items.add(Item(ST_FAIL,
                "模块未注入本 App：LSPosed→模块→启用「阿伟定位伪装·满血版」→作用域勾选[本App+目标App]→强停本 App 重新打开→回这里「重新体检」",
                A_OPEN_LSPOSED))
        }
        // 6. 目标 App 注入心跳（强信号：Hook 进程每 15s 上报）
        when {
            latest == null -> items.add(Item(ST_FAIL,
                "目标 App 无注入记录：作用域勾选目标 App→强停目标 App→重新打开"))
            System.currentTimeMillis() - latest.ts <= 10 * 60_000L ->
                items.add(Item(ST_OK, "目标 App 注入正常：${label(ctx, latest.pkg)} · ${ago(latest.ts)}"))
            else -> items.add(Item(ST_WARN,
                "目标 App 注入记录较旧（${label(ctx, latest.pkg)} · ${ago(latest.ts)}）：强停目标 App 再打开"))
        }
        return items
    }

    private fun weakProbe(ctx: Context) {
        if (System.currentTimeMillis() - weakAt < 10_000) return
        weakAt = System.currentTimeMillis()
        rootFound = detectRoot(ctx)
        lspFound = detectLsposed(ctx)
    }

    private fun detectRoot(ctx: Context): Boolean {
        for (p in arrayOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/vendor/bin/su"
        )) {
            try {
                if (java.io.File(p).exists()) return true
            } catch (t: Throwable) {
            }
        }
        for (pkg in arrayOf("com.topjohnwu.magisk", "io.github.huskydg.magisk", "me.bmax.apatch")) {
            try {
                ctx.packageManager.getPackageInfo(pkg, 0)
                return true
            } catch (t: Throwable) {
            }
        }
        return false
    }

    private fun detectLsposed(ctx: Context): Boolean {
        // 新版 LSPosed（2.x / 隐藏管理器后）会把管理器寄生到 com.android.shell 等包名下，
        // 老包名探测不到；这里多探一个，探测不到也不阻断（selfHooked 才是权威）
        for (pkg in arrayOf("org.lsposed.manager", "io.github.lsposed.manager", "com.android.shell")) {
            try {
                if (ctx.packageManager.getLaunchIntentForPackage(pkg) != null) return true
            } catch (t: Throwable) {
            }
        }
        return false
    }

    /** LSPosed 管理器启动入口（隐藏/寄生后老包名探测不到，补 com.android.shell） */
    fun lsposedLaunchIntent(ctx: Context) = try {
        ctx.packageManager.getLaunchIntentForPackage("org.lsposed.manager")
            ?: ctx.packageManager.getLaunchIntentForPackage("io.github.lsposed.manager")
            ?: ctx.packageManager.getLaunchIntentForPackage("com.android.shell")
    } catch (t: Throwable) {
        null
    }

    private fun label(ctx: Context, pkg: String): String = try {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (t: Throwable) {
        pkg
    }

    private fun ago(ts: Long): String {
        val m = (System.currentTimeMillis() - ts) / 60_000
        return when {
            m < 1 -> "刚刚"
            m < 60 -> "${m}分钟前"
            m < 24 * 60 -> "${m / 60}小时前"
            else -> "${m / 1440}天前"
        }
    }
}
