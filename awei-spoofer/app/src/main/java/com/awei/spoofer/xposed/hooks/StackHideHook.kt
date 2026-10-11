package com.awei.spoofer.xposed.hooks

import com.awei.spoofer.xposed.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/**
 * 堆栈帧隐藏：过滤 [Throwable.getStackTrace] / [Thread.getStackTrace] /
 * [Thread.getAllStackTraces] 中含 `de.robv.android.xposed` / `io.github.libxposed` /
 * `lsposed` / `com.android.xcel.Xposed` 的帧。
 *
 * 检测方常用手法：`new Throwable().getStackTrace()` 后扫描类名含 Xposed/LSPosed 的帧
 * 作为"被注入"铁证。本 hook 在 after 阶段过滤这些帧，让检测方看到干净堆栈（仅应用自身代码帧）。
 *
 * 局限：native 侧直接读 `Thread::GetStackTrace` 可绕过——本 hook 仅缓解 Java 层探测。
 * 低频调用（多数检测库只在检测点 new Throwable），性能开销可忽略。
 *
 * **1.1.16 激活语义改为常开（不看 deliverable）**："被注入"是独立于"是否在模拟"的存在性
 * 事实，用户尚未启动模拟的扫描窗口里透传真堆栈等于全暴露。主 App 自身进程不受影响
 *（SELF_PKG 分支不挂任何 hook，崩溃日志照常完整）。
 *
 * 挂载规范（vc37 铁律）：每个挂载独立 try/catch + 成功计数上浮日志。
 */
object StackHideHook {

    private var hooked = false

    /** 堆栈帧类名含这些子串的过滤掉 */
    private val FILTER_PATTERNS = arrayOf(
        "de.robv.android.xposed",
        "io.github.libxposed",
        "lsposed",
        "com.android.xcel.Xposed",
        // 1.1.17：本模块自身的 hook 回调帧（com.awei.spoofer.xposed.*）出现在目标 App
        // 堆栈里同样是"被注入"直接证据（检测方扫自有包名比扫框架名更直接）。
        // 目标 App 正常永远不会有我们的帧，过滤零误伤
        "com.awei.spoofer"
    )

    fun hook() {
        if (hooked) return
        hooked = true

        var ok = 0
        val total = 3

        runCatching { hookThrowableGetStackTrace() }.onSuccess { ok++ }
        runCatching { hookThreadGetStackTrace() }.onSuccess { ok++ }
        runCatching { hookThreadGetAllStackTraces() }.onSuccess { ok++ }

        XLog.i("StackHideHook 安装完成（$ok/$total 组，常开）")
    }

    private fun filterFrames(frames: Array<StackTraceElement>?): Array<StackTraceElement>? {
        if (frames == null) return null
        val filtered = frames.filterNot { e ->
            val cn = e.className ?: return@filterNot false
            FILTER_PATTERNS.any { cn.contains(it) }
        }
        // 帧数没变就不改 result（避免不必要的对象分配）
        return if (filtered.size == frames.size) null else filtered.toTypedArray()
    }

    private fun hookThrowableGetStackTrace() {
        XposedHelpers.findAndHookMethod(
            Throwable::class.java, "getStackTrace",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val filtered = filterFrames(param.result as? Array<StackTraceElement>)
                    if (filtered != null) param.result = filtered
                }
            }
        )
    }

    private fun hookThreadGetStackTrace() {
        XposedHelpers.findAndHookMethod(
            Thread::class.java, "getStackTrace",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val filtered = filterFrames(param.result as? Array<StackTraceElement>)
                    if (filtered != null) param.result = filtered
                }
            }
        )
    }

    private fun hookThreadGetAllStackTraces() {
        XposedHelpers.findAndHookMethod(
            Thread::class.java, "getAllStackTraces",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val map = param.result as? Map<Thread, Array<StackTraceElement>> ?: return
                    // 每个 Thread 的堆栈都过滤一遍
                    val filtered = map.mapValues { (_, frames) -> filterFrames(frames) ?: frames }
                    param.result = filtered
                }
            }
        )
    }
}
