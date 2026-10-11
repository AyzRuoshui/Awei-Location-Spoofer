package com.awei.spoofer.data

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 运行日志：内存环形缓冲 + 文件持久化 + UI 实时监听 + 导出 txt 分享。
 * 全部关键动作（启动/注入/规划）埋点于此，用户可直接导出发微信排查。
 */
object SpLogger {

    data class Entry(val ts: Long, val level: Char, val tag: String, val msg: String)

    private const val MAX_MEM = 800
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    private val mem = ArrayDeque<Entry>()
    private val lock = Any()
    private val listeners = ArrayList<(Entry) -> Unit>()
    private var logFile: File? = null
    private var versionName = "?"
    private var versionCode = 0L
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    // 文件写入专用单线程：调用方（主线程/轨迹线程/binder）不再持全局锁做磁盘 IO，
    // 否则任意线程打日志都被 stat+append 阻塞。单线程天然保序，磁盘内容与内存顺序一致
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "awei-log-io").apply { isDaemon = true }
    }

    fun init(context: Context) {
        ensureInit(context)
        i("Sys", "应用启动 v$versionName(vc$versionCode) ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}(SDK ${Build.VERSION.SDK_INT})")
    }

    /** 幂等初始化（Application onCreate 入口） */
    @Synchronized
    fun ensureInit(context: Context) {
        if (logFile != null) return
        try {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
            dir.mkdirs()
            logFile = File(dir, "app.log")
        } catch (t: Throwable) {
            logFile = null
        }
        try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            versionName = pi.versionName ?: "?"
            versionCode = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        } catch (t: Throwable) {
        }
    }

    fun i(tag: String, msg: String) = log('I', tag, msg)
    fun w(tag: String, msg: String) = log('W', tag, msg)
    fun e(tag: String, msg: String) = log('E', tag, msg)

    fun log(level: Char, tag: String, msg: String) {
        val e = Entry(System.currentTimeMillis(), level, tag, msg)
        // 格式化放进锁内：SimpleDateFormat 非线程安全，主线程/轨迹线程/binder 线程并发调用会错乱
        val line: String
        synchronized(lock) {
            line = format(e)
            mem.addLast(e)
            while (mem.size > MAX_MEM) mem.removeFirst()
        }
        val f = logFile
        if (f != null) {
            io.execute {
                try {
                    if (f.length() > MAX_FILE_BYTES) f.writeText("")
                    f.appendText(line + "\n")
                } catch (t: Throwable) {
                }
            }
        }
        val ls = synchronized(lock) { listeners.toList() }
        for (l in ls) try { l(e) } catch (t: Throwable) {
        }
    }

    fun snapshot(): List<Entry> = synchronized(lock) { mem.toList() }

    fun addListener(l: (Entry) -> Unit) = synchronized(lock) { listeners.add(l) }

    fun removeListener(l: (Entry) -> Unit) = synchronized(lock) { listeners.remove(l) }

    fun clear() {
        synchronized(lock) {
            mem.clear()
        }
        logFile?.let { f ->
            io.execute {
                try {
                    f.writeText("")
                } catch (t: Throwable) {
                }
            }
        }
        i("Sys", "日志已清空")
    }

    // 全部 format 调用同锁：共享 SimpleDateFormat 非线程安全——log() 在锁内格式化，
    // LogFragment 实时渲染/Snapshot 消费方在锁外调 format，不加锁会与并发 log() 打架
    //（Calendar 内部状态损坏 → 时间戳错乱甚至 UI 线程数组越界崩溃）
    fun format(e: Entry): String = synchronized(lock) {
        "[${fmt.format(Date(e.ts))}] [${e.level}] [${e.tag}] ${e.msg}"
    }

    /**
     * 导出 txt（含设备/版本头 + 全量日志），返回文件供 FileProvider 分享。
     */
    fun export(context: Context): File? {
        return try {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val out = File(dir, "awei-log-$stamp.txt")
            val sb = StringBuilder()
            sb.appendLine("==== 阿伟定位伪装·免Root版 日志导出 ====")
            sb.appendLine("版本: $versionName (vc$versionCode)")
            sb.appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
            sb.appendLine("系统: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            sb.appendLine("导出时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            sb.appendLine("====")
            // 格式化必须在锁内：与并发 log() 共享 SimpleDateFormat，锁外 format 会错乱
            val body: String = synchronized(lock) {
                buildString {
                    for (e in mem) appendLine(format(e))
                }
            }
            sb.append(body)
            out.writeText(sb.toString())
            out
        } catch (t: Throwable) {
            null
        }
    }
}
