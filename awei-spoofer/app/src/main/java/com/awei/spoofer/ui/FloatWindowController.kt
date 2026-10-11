package com.awei.spoofer.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import com.awei.spoofer.data.Prefs
import com.awei.spoofer.engine.mock.MockLocationEngine
import com.awei.spoofer.engine.mock.MockLocationService

/**
 * 悬浮窗移动开关（1.1.14）：小圆钮悬浮在任意 App 上方，点击=起跑/暂停切换，拖动换位。
 * 待命/暂停=位置钉在原地（速度 0），定位仍是模拟后的位置——解决"目标 App 先开还是
 * 定位器先开"的时序死结（推荐：先开模拟待命 → 再开目标 App → 中途点出来后起跑）。
 *
 * 需要"显示在其他应用上层"权限（SYSTEM_ALERT_WINDOW）；未授权时不起窗，
 * 轨迹页的「起跑/暂停」按钮是等价备用入口。
 */
object FloatWindowController {

    private var panel: TextView? = null

    fun isShowing(): Boolean = panel != null

    fun hasPermission(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
    } catch (t: Throwable) {
        false
    }

    @Synchronized
    fun show(context: Context): Boolean {
        if (panel != null) return true
        if (!hasPermission(context)) return false
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val tv = TextView(context)
            tv.text = label()
            tv.setTextColor(Color.WHITE)
            tv.textSize = 14f
            tv.setPadding(28, 22, 28, 22)
            tv.background = GradientDrawable().apply {
                cornerRadius = 60f
                setColor(color())
            }
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                // FLAG_NOT_FOCUSABLE：不抢焦点，底下 App 照常操作
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            p.gravity = Gravity.TOP or Gravity.START
            p.x = 24
            p.y = 320
            var downX = 0f
            var downY = 0f
            var pX = 0
            var pY = 0
            var moved = false
            tv.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX
                        downY = ev.rawY
                        pX = p.x
                        pY = p.y
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - downX
                        val dy = ev.rawY - downY
                        if (Math.abs(dx) > 12 || Math.abs(dy) > 12) moved = true
                        p.x = pX + dx.toInt()
                        p.y = pY + dy.toInt()
                        try {
                            wm.updateViewLayout(v, p)
                        } catch (t: Throwable) {
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            MockLocationService.toggleHold(v.context)
                            // 服务经 startService 异步落状态，稍候再刷钮面
                            v.postDelayed({ refresh() }, 350)
                        }
                        true
                    }
                    else -> false
                }
            }
            wm.addView(tv, p)
            panel = tv
            true
        } catch (t: Throwable) {
            panel = null
            false
        }
    }

    @Synchronized
    fun hide(context: Context) {
        val tv = panel ?: return
        panel = null
        try {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(tv)
        } catch (t: Throwable) {
        }
    }

    /** 起跑/暂停切换后刷新钮面（状态已落定时调用）。任意线程可调：视图变更一律
     *  post 回主线程——attached 视图跨线程 requestLayout 会 CalledFromWrongThreadException，
     *  注册线程（Mock 启动后台线程）收尾的告警刷新也走这里 */
    @Synchronized
    fun refresh() {
        val tv = panel ?: return
        tv.post {
            try {
                tv.text = label()
                (tv.background as? GradientDrawable)?.setColor(color())
            } catch (t: Throwable) {
            }
        }
    }

    private fun isHeld(): Boolean = Prefs.holdAtMs > 0

    // 1.1.30：test provider 全失败（典型=Root 授权丢失）→ 红字压过起停态——此刻注入
    // 通道是死的，未被 Hook 覆盖的 App 必读真实位置，先去 Magisk 补授权再重启模拟才有意义
    private fun injectDead(): Boolean = MockLocationEngine.lastSetupAllFailed

    private fun label(): String = when {
        injectDead() -> "⚠ 定位未注入（Root?）"
        isHeld() -> "▶ 起跑"
        else -> "⏸ 暂停"
    }

    private fun color(): Int = when {
        injectDead() -> 0xFFC62828.toInt()
        isHeld() -> 0xFFE65100.toInt()
        else -> 0xFF2E7D32.toInt()
    }
}
