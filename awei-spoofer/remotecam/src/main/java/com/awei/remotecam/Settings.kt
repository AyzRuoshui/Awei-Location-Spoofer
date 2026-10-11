package com.awei.remotecam

import android.content.Context
import com.awei.common.Proto
import java.security.SecureRandom

/**
 * 远程摄像头 App 设置（SharedPreferences）。
 * 配对码即访问令牌（v1 简化，LAN 场景可用；重新生成会使旧连接在下一次请求时失效）。
 */
object Settings {

    private fun sp(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun token(ctx: Context): String =
        sp(ctx).getString("token", null) ?: regenToken(ctx)

    fun regenToken(ctx: Context): String {
        // SecureRandom：配对码即访问令牌，可预测的随机源会让 LAN 内枚举爆破更容易
        val r = SecureRandom()
        val code = (0 until 6).joinToString("") { (r.nextInt(10)).toString() }
        sp(ctx).edit().putString("token", code).apply()
        return code
    }

    fun port(ctx: Context): Int = Proto.PORT_DEFAULT

    /** 输出竖屏画面（顺时针旋转 90°）。远程手机横放时可关闭 */
    fun portrait(ctx: Context): Boolean = sp(ctx).getBoolean("portrait", true)

    fun setPortrait(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean("portrait", v).apply()
    }

    /** 自动同意授权请求（可信环境使用） */
    fun autoApprove(ctx: Context): Boolean = sp(ctx).getBoolean("auto_approve", false)

    fun setAutoApprove(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean("auto_approve", v).apply()
    }

    // ---- 服务器中转（跨网络远程调用）----

    /** 中转服务器地址 host:port；未配置/空时回落到官方默认（Proto.RELAY_DEFAULT） */
    fun relayServer(ctx: Context): String =
        sp(ctx).getString("relay_server", null)?.takeIf { it.isNotBlank() } ?: Proto.RELAY_DEFAULT

    fun setRelayServer(ctx: Context, v: String) {
        sp(ctx).edit().putString("relay_server", v.trim()).apply()
    }

    /** 中转开关（开启后连接中转服务器，本机 App 可经服务器按远程 ID 拨入） */
    fun relayEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("relay_enabled", false)

    fun setRelayEnabled(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean("relay_enabled", v).apply()
    }

    /** 持久设备标识：中转服务器按它保持远程 ID 稳定（重装/重连 ID 不变） */
    fun deviceId(ctx: Context): String {
        val saved = sp(ctx).getString("devid", null)
        if (saved != null) return saved
        val id = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        sp(ctx).edit().putString("devid", id).apply()
        return id
    }
}
