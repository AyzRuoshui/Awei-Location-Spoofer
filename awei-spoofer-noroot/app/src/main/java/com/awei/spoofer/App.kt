package com.awei.spoofer

import android.app.Application
import android.util.Log
import org.osmdroid.config.Configuration
import java.io.File

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val cfg = Configuration.getInstance()
        cfg.userAgentValue = packageName
        cfg.osmdroidBasePath = File(getExternalFilesDir(null) ?: filesDir, "osmdroid")
        cfg.osmdroidTileCache = File(cfg.osmdroidBasePath, "tiles")
        cfg.load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
        installCrashLogger()
        com.awei.spoofer.data.SpLogger.init(this)
    }

    /** 崩溃堆栈写入 crash.log，状态页展示，便于用户截图反馈 */
    private fun installCrashLogger() {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val stack = Log.getStackTraceString(e)
                com.awei.spoofer.data.SpLogger.e("Crash", stack.take(800))
                val f = File(getExternalFilesDir(null) ?: filesDir, "crash.log")
                val ver = try {
                    val pi = packageManager.getPackageInfo(packageName, 0)
                    @Suppress("DEPRECATION")
                    "ver=${pi.versionName}(${pi.versionCode})\n"
                } catch (_: Throwable) {
                    ""
                }
                f.writeText(
                    "time=${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}\n" +
                        "sdk=${android.os.Build.VERSION.SDK_INT} model=${android.os.Build.MODEL}\n" +
                        ver +
                        stack
                )
            } catch (_: Throwable) {
            }
            default?.uncaughtException(t, e)
        }
    }

    companion object {
        fun crashLog(context: android.content.Context): File? {
            val f = File(context.getExternalFilesDir(null) ?: context.filesDir, "crash.log")
            return if (f.exists()) f else null
        }

        fun clearCrashLog(context: android.content.Context) {
            try {
                crashLog(context)?.delete()
            } catch (_: Throwable) {
            }
        }
    }
}
