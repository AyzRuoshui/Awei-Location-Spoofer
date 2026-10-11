package com.awei.spoofer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * 摇杆控件：拖动拇指控制模拟移动方向与速度。
 *
 * 角度约定（与 RoutePlanner.bearingDeg 一致）：
 * - 0°=北（上），90°=东（右），180°=南（下），270°=西（左）
 * - 换算：屏幕 y 轴朝下，north=-dy；bearing=toDegrees(atan2(east, north))
 *
 * 幅度：0~1，0=不动（松手/未拖），1=拖到边缘=满速。
 * 回调在 ACTION_UP/CANCEL 时发 (0,0)，消费方据此停步。
 */
class JoystickView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onVector: ((angleDeg: Float, magnitude: Float) -> Unit)? = null

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80424242.toInt()
        style = Paint.Style.FILL
    }
    private val stickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1976D2.toInt()
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    // 拇指当前偏移（相对 View 中心），(0,0)=未拖动
    private var stickX = 0f
    private var stickY = 0f

    // 拖动指针锁（1.0.14，同步手绘 1.1.15 的笔尖锁教训）：只响应起笔那根手指——
    // 拇指拖着摇杆时手掌/第二指落屏，先抬拇指后 event.x 指向剩余指针（手掌），
    // 摇杆瞬间跳到手掌位置 = 模拟位置突变/满速误判（手持跑步场景高发）
    private var dragPointerId = MotionEvent.INVALID_POINTER_ID

    private val density by lazy { resources.displayMetrics.density }
    private val baseRadius by lazy { min(width, height) / 2f - 4f * density }
    private val stickRadius by lazy { baseRadius * 0.35f }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, baseRadius, basePaint)
        canvas.drawCircle(cx, cy, baseRadius, strokePaint)
        canvas.drawCircle(cx + stickX, cy + stickY, stickRadius, stickPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragPointerId = event.getPointerId(0)
                updateStick(event.x - cx, event.y - cy)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // 只跟锁定手指；其他手指（手掌/误触）的移动完全忽略
                val idx = event.findPointerIndex(dragPointerId)
                if (idx >= 0) updateStick(event.getX(idx) - cx, event.getY(idx) - cy)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> return true // 第二指落下：不抢锁，保持手势流
            MotionEvent.ACTION_POINTER_UP -> {
                // 抬的是非锁定手指：忽略；抬的是锁定手指：结束拖动
                if (event.getPointerId(event.actionIndex) == dragPointerId) resetStick()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                resetStick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun resetStick() {
        dragPointerId = MotionEvent.INVALID_POINTER_ID
        stickX = 0f
        stickY = 0f
        invalidate()
        onVector?.invoke(0f, 0f)
    }

    private fun updateStick(dx: Float, dy: Float) {
        val r = (baseRadius - stickRadius).coerceAtLeast(1f)
        val dist = hypot(dx, dy)
        if (dist > r && dist > 0) {
            stickX = dx / dist * r
            stickY = dy / dist * r
        } else {
            stickX = dx
            stickY = dy
        }
        invalidate()
        // smoothstep 非线性曲线：小幅拖动精细控慢速、大幅才到满速（原线性 dist/r 满把即满速）
        // t*t*(3-2*t)：t=0→0、t=0.5→0.5、t=1→1，单调递增且值域[0,1]
        val t = (dist / r).coerceIn(0f, 1f)
        val mag = t * t * (3f - 2f * t)
        if (mag > 0.01f) {
            // 屏幕坐标 → 方位角（0=北顺时针）：east=dx, north=-dy
            val angle = Math.toDegrees(atan2(dx.toDouble(), (-dy).toDouble())).toFloat()
            val normalized = if (angle < 0) angle + 360f else angle
            onVector?.invoke(normalized, mag)
        } else {
            onVector?.invoke(0f, 0f)
        }
    }
}
