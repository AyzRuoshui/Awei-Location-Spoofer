package com.awei.spoofer.util

import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

/**
 * Root 权限执行基础工具
 */
object RootShell {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 执行 su 命令，返回 (exitCode, output)
     */
    fun exec(command: String): Pair<Int, String> {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()

            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            while (errorReader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }

            val exitCode = process.waitFor()
            Pair(exitCode, output.toString().trim())
        } catch (e: Exception) {
            Pair(-1, e.message ?: "执行失败")
        }
    }

    /** 验证 Root 可用（su id 返回 uid=0） */
    fun ensureRooted(): Boolean {
        val (rootCode, rootMsg) = exec("id")
        return rootCode == 0 && rootMsg.contains("uid=0")
    }

    internal fun postCallback(callback: (Boolean, String) -> Unit, success: Boolean, msg: String) {
        mainHandler.post { callback(success, msg) }
    }

    internal fun postToMain(action: () -> Unit) {
        mainHandler.post(action)
    }
}
