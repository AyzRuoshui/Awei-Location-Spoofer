package com.awei.spoofer.xposed

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Hook 进程轻量日志：
 * - XposedBridge.log（LSPosed 日志可见）
 * - 经 SpooferProvider insert 回传主 App，汇入用户可导出的统一日志
 * 仅记事件（hook 安装/配置加载/摄像头状态），无高频路径埋点。
 */
object XLog {

    /** 当前 Hook 进程名（XposedInit.handleLoadPackage 首行设置）。Hook 日志 tag 带
     *  完整进程名——旧版只记包名，主进程与 appbrandN（小程序运行时）进程的日志在
     *  导出里无法分辨，多进程链路排查时不知道哪条日志来自哪个进程 */
    @Volatile
    var processName: String = ""

    @Volatile
    private var appContext: Context? = null

    // 有界队列 + DiscardOldestPolicy：主 App 冻结时日志积压上限 512 条，满了丢最旧
    // 保最新 Hook 心跳（原 newSingleThreadExecutor 是无界 LinkedBlockingQueue，OOM 风险）
    private val pool = ThreadPoolExecutor(
        1, 1, 60L, TimeUnit.SECONDS,
        LinkedBlockingQueue(512),
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    // attach 前日志黑洞缓冲：模块安装循环（XposedInit L78-86）早于 Application.attach，
    // 此时 appContext==null，"模块 X 安装失败"/"hook 安装完成 ok/12"主 App 收不到。
    // 缓冲到 attach 时 flush 补发（仅 XposedBridge.log 可见不够，用户排查靠主 App 日志页）
    private val pendingBeforeAttach =
        java.util.Collections.synchronizedList(mutableListOf<Pair<Char, String>>())
    private const val PENDING_MAX = 64

    fun attach(ctx: Context) {
        appContext = ctx.applicationContext
        // flush 安装期积压日志（主 App 现在能收到了）
        synchronized(pendingBeforeAttach) {
            if (pendingBeforeAttach.isEmpty()) return
            val snapshot = ArrayList(pendingBeforeAttach)
            pendingBeforeAttach.clear()
            snapshot.forEach { (level, msg) -> enqueue(level, msg, ctx.applicationContext) }
        }
    }

    fun d(msg: String) = log('D', msg)
    fun i(msg: String) = log('I', msg)
    fun w(msg: String) = log('W', msg)
    fun e(msg: String) = log('E', msg)

    private fun log(level: Char, msg: String) {
        try {
            // Hook 进程内才可用（compileOnly 依赖）；主 App 进程若误加载此处静默
            XposedBridge.log("awei: [$level] $msg")
        } catch (t: Throwable) {
        }
        val ctx = appContext
        if (ctx == null) {
            // attach 前：缓冲，attach 时补发（不丢安装期关键日志如模块挂载失败）
            synchronized(pendingBeforeAttach) {
                if (pendingBeforeAttach.size < PENDING_MAX) {
                    pendingBeforeAttach.add(level to msg)
                }
            }
            return
        }
        enqueue(level, msg, ctx)
    }

    private fun enqueue(level: Char, msg: String, ctx: Context) {
        val pkg = if (processName.isNotBlank()) processName else try {
            ctx.packageName
        } catch (t: Throwable) {
            "?"
        }
        pool.execute {
            try {
                val cv = ContentValues().apply {
                    put("tag", "Hook:$pkg")
                    put("msg", "[$level] $msg")
                }
                ctx.contentResolver.insert(
                    Uri.parse("content://com.awei.spoofer.config/log"), cv
                )
            } catch (t: Throwable) {
                // 主 App 未安装/provider 异常：静默丢弃
            }
        }
    }
}
