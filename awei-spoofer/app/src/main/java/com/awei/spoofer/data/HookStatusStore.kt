package com.awei.spoofer.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Hook 侧运行状态存档（主 App 私有 SP，"hook_status"）：
 * - report(pkg)：Hook 进程（目标 App）经 SpooferProvider /status 上报的心跳
 * - selfRecord()：LSPosed 把模块注入主 App 自身进程时写的标记（XposedInit 同进程直写）
 * 状态仅用于状态页"体检"引导展示，不作安全边界（provider exported=true，写入方可被第三方伪造）。
 */
object HookStatusStore {

    data class Report(val pkg: String, val ver: String, val ts: Long, val self: Boolean)

    /**
     * 进程内静态标志：本 App 自身进程是否被 LSPosed 加载了模块（handleLoadPackage 跑过）。
     * 仅当前进程有效，App 重启后重置为 false。
     * 体检用它区分两类原因：selfHooked=false 时，本标志=true→"注入了但自标记没写"（异常），
     * 本标志=false→"完全没注入"（作用域没勾本 App / 没强停重开）。
     */
    @Volatile
    var selfLoadedInProcess: Boolean = false

    private const val KEY_PREFIX = "hb_"
    private const val SELF_KEY = "hb_#self"
    private const val MAX_ENTRIES = 10
    private const val TTL_MS = 7L * 24 * 3600 * 1000

    private fun sp(context: Context): SharedPreferences =
        context.getSharedPreferences("hook_status", Context.MODE_PRIVATE)

    /** 目标 App 进程心跳（来自 provider /status insert） */
    fun report(context: Context, pkg: String, ver: String) {
        record(context, KEY_PREFIX + pkg, Report(pkg, ver, System.currentTimeMillis(), self = false))
    }

    /** 模块被 LSPosed 注入本 App 自身进程（作用域勾选了本 App 的确凿证据） */
    fun selfRecord(context: Context) {
        record(context, SELF_KEY, Report("#self", verOf(context), System.currentTimeMillis(), self = true))
    }

    private fun verOf(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0)?.versionName ?: ""
    } catch (t: Throwable) {
        ""
    }

    private fun record(context: Context, key: String, r: Report) {
        try {
            val prefs = sp(context)
            val json = JSONObject().put("pkg", r.pkg).put("ver", r.ver).put("ts", r.ts)
            prefs.edit().putString(key, json.toString()).apply()
            // 1.1.16：淘汰/TTL 不许碰 hb_#self（≥10 目标包时误删自标记 → 体检"模块已启用"假阴性）
            val others = prefs.all.keys.filter { it.startsWith(KEY_PREFIX) && it != key && it != SELF_KEY }
            // 条目上限：超了删最旧的
            if (others.size >= MAX_ENTRIES) {
                oldest(prefs, others)?.let { prefs.edit().remove(it).apply() }
            }
            // 过期清理
            val cutoff = System.currentTimeMillis() - TTL_MS
            for (k in prefs.all.keys.filter { it.startsWith(KEY_PREFIX) && it != key && it != SELF_KEY }) {
                if (tsOf(prefs, k) < cutoff) prefs.edit().remove(k).apply()
            }
        } catch (t: Throwable) {
            // 状态存档失败不影响主流程
        }
    }

    private fun tsOf(prefs: SharedPreferences, key: String): Long = try {
        prefs.getString(key, null)?.let { JSONObject(it).optLong("ts", 0) } ?: 0L
    } catch (t: Throwable) {
        0L
    }

    private fun oldest(prefs: SharedPreferences, keys: List<String>): String? =
        keys.minByOrNull { tsOf(prefs, it) }

    /** 全部心跳（含 self 标记），时间倒序 */
    fun snapshot(context: Context): List<Report> {
        val out = ArrayList<Report>()
        try {
            val prefs = sp(context)
            for ((k, v) in prefs.all) {
                if (!k.startsWith(KEY_PREFIX) || v !is String) continue
                try {
                    val o = JSONObject(v)
                    out.add(Report(o.optString("pkg"), o.optString("ver"), o.optLong("ts", 0), k == SELF_KEY))
                } catch (t: Throwable) {
                }
            }
        } catch (t: Throwable) {
        }
        return out.sortedByDescending { it.ts }
    }

    /** 模块是否已被 LSPosed 注入本 App（强信号） */
    fun selfHooked(context: Context): Boolean = snapshot(context).any { it.self }
}
