package com.awei.spoofer.ui

import android.content.Context
import com.awei.spoofer.data.Prefs
import com.awei.spoofer.engine.mock.MockLocationEngine
import com.awei.spoofer.engine.mock.MockLocationService

/**
 * 体检：免Root版检查项（状态+文案+引导动作），状态页渲染。
 * 每个失败项（✗）必须给出明确的下一步；弱信号只提醒（⚠）不阻断。
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
    const val A_DEV_OPTIONS = 2     // 打开开发者选项

    data class Item(val state: Int, val text: String, val action: Int = A_NONE)

    fun build(ctx: Context): List<Item> {
        val items = ArrayList<Item>()

        // 1. 定位权限
        if (MockLocationEngine.hasLocationPermission(ctx)) {
            items.add(Item(ST_OK, "定位权限已授权"))
        } else {
            items.add(Item(ST_FAIL, "定位权限未授权（精确+粗略）", A_LOCATION_PERM))
        }

        // 2. 模拟位置应用=本 App：免Root版硬前置（test provider 注入的必要条件）
        if (MockLocationEngine.isMockLocationEnabled(ctx)) {
            items.add(Item(ST_OK, "模拟位置应用已选本 App"))
        } else {
            val sel = MockLocationEngine.mockLocationApp(ctx)
            val text = when (sel) {
                null -> "模拟位置应用还没选：开发者选项（没有先在 设置→关于手机 连点版本号 7 次开启）→ 模拟位置信息应用 → 选本 App"
                ctx.packageName -> "模拟位置应用已选本 App 但未生效，重启手机/模拟器后再试"
                else -> "模拟位置应用选成了别的 App，请改选本 App"
            }
            items.add(Item(ST_FAIL, text, A_DEV_OPTIONS))
        }

        // 3. 模拟服务运行
        if (Prefs.isRunning && MockLocationService.hasInstance) {
            items.add(Item(ST_OK, "模拟服务运行中（位置持续注入）"))
        } else {
            items.add(Item(ST_FAIL, "模拟服务未运行（点上方「启动模拟」按钮）"))
        }

        // 4. 端到端链路自检
        when (MockLocationService.selfCheckState) {
            MockLocationService.SELF_OK ->
                items.add(Item(ST_OK, "链路自检通过：${MockLocationService.selfCheckText}"))
            MockLocationService.SELF_RUNNING ->
                items.add(Item(ST_WARN, "链路自检进行中…"))
            MockLocationService.SELF_FAIL ->
                items.add(Item(ST_FAIL, "链路自检未通过：${MockLocationService.selfCheckText}"))
            else ->
                items.add(Item(ST_INFO, "链路自检：启动模拟后自动进行"))
        }

        // 能力边界：如实告知（此前只有一句模糊提示，用户不知道是「限制」还是「待修 bug」）
        items.add(Item(ST_INFO, "能力边界：系统会在模拟位置上打「模拟」标记，该标记由系统服务端设置，本 App 无法清除"))
        items.add(Item(ST_INFO, "→ 高德/百度系与一般 App 够用；腾讯系、运动类等会做模拟检测的 App 会识别"))
        items.add(Item(ST_INFO, "→ 需绕过模拟检测请换完整版「阿伟定位伪装·满血版」（LSPosed 模块，需 Root）"))
        return items
    }
}
