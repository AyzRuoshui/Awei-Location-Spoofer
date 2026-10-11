package com.awei.spoofer.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.awei.spoofer.R
import com.awei.spoofer.data.MockConfig
import com.awei.spoofer.data.Prefs
import com.awei.spoofer.engine.mock.MockLocationEngine
import com.awei.spoofer.engine.mock.MockLocationService
import com.awei.spoofer.track.CoordUtil
import com.awei.spoofer.track.RoutePlanner
import com.awei.spoofer.track.TrackMath
import com.google.android.material.switchmaterial.SwitchMaterial

class TrackFragment : Fragment() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var rgMode: RadioGroup
    private lateinit var tvHint: TextView
    private lateinit var tvLoopHint: TextView
    private lateinit var seekSpeed: SeekBar
    private lateinit var tvSpeed: TextView
    private lateinit var swVariable: SwitchMaterial
    private lateinit var swLoop: SwitchMaterial
    private lateinit var swJoystick: SwitchMaterial
    private lateinit var tvState: TextView
    private lateinit var btnGenLoop: Button

    @Volatile
    private var generatingLoop = false
    // 状态文本驱动标志：用 startsWith("运行中") 判态会在"启动中..."卡住时永远不刷新为已停止
    private var shownRunning = false
    private var loopDialog: androidx.appcompat.app.AlertDialog? = null

    private val refresh = object : Runnable {
        override fun run() {
            updateState()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val ctx = requireContext()
        val v = inflater.inflate(R.layout.fragment_track, container, false)
        rgMode = v.findViewById(R.id.rg_mode)
        tvHint = v.findViewById(R.id.tv_point_hint)
        tvLoopHint = v.findViewById(R.id.tv_loop_hint)
        seekSpeed = v.findViewById(R.id.seek_speed)
        tvSpeed = v.findViewById(R.id.tv_speed)
        swVariable = v.findViewById(R.id.sw_variable)
        swLoop = v.findViewById(R.id.sw_loop)
        tvState = v.findViewById(R.id.tv_track_state)
        btnGenLoop = v.findViewById(R.id.btn_gen_loop)

        rgMode.check(if (Prefs.getUiMode(ctx) == MockConfig.MODE_ROUTE) R.id.rb_route else R.id.rb_fixed)
        seekSpeed.progress = (Prefs.getSpeed(ctx).toInt() - 5).coerceIn(0, 115)
        tvSpeed.text = getString(R.string.speed_label, Prefs.getSpeed(ctx).toInt())
        swVariable.isChecked = Prefs.getVariableSpeed(ctx)
        swLoop.isChecked = Prefs.getLoop(ctx)
        swJoystick = v.findViewById(R.id.sw_joystick)
        swJoystick.isChecked = Prefs.getJoystick(ctx)
        swJoystick.setOnCheckedChangeListener { _, checked ->
            Prefs.setJoystick(ctx, checked)
            Prefs.saveUiMode(ctx, if (checked) MockConfig.MODE_JOYSTICK else MockConfig.MODE_FIXED)
            // 服务在跑时重启切换模式（摇杆↔固定点）
            if (Prefs.isRunning) MockLocationService.start(ctx)
            updateJoystickHint(checked)
        }

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.rb_route) MockConfig.MODE_ROUTE else MockConfig.MODE_FIXED
            // 切 fixed/route 时关掉摇杆（与摇杆模式互斥）
            if (Prefs.getJoystick(ctx)) {
                Prefs.setJoystick(ctx, false)
                swJoystick.isChecked = false
            }
            Prefs.saveUiMode(ctx, mode)
            tvHint.text = getString(
                if (mode == MockConfig.MODE_ROUTE) R.string.pick_route_hint else R.string.pick_point_hint
            )
        }
        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val kmh = progress + 5
                Prefs.setSpeed(ctx, kmh.toFloat())
                tvSpeed.text = getString(R.string.speed_label, kmh)
                if (Prefs.isRunning && fromUser) {
                    // 该行不被每秒状态刷新覆盖
                    tvLoopHint.text = "⚠ 速度修改需停止后重新启动才生效"
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        swVariable.setOnCheckedChangeListener { _, checked -> Prefs.setVariableSpeed(ctx, checked) }
        swLoop.setOnCheckedChangeListener { _, checked -> Prefs.setLoop(ctx, checked) }

        // 场景预设：一键到常见配速（散步/快走/慢跑/快跑），滑杆留给自定义
        fun preset(kmh: Int) {
            Prefs.setSpeed(ctx, kmh.toFloat())
            seekSpeed.progress = (kmh - 5).coerceIn(0, 115)
            tvSpeed.text = getString(R.string.speed_label, kmh)
        }
        v.findViewById<Button>(R.id.btn_preset_walk).setOnClickListener { preset(5) }
        v.findViewById<Button>(R.id.btn_preset_brisk).setOnClickListener { preset(7) }
        v.findViewById<Button>(R.id.btn_preset_jog).setOnClickListener { preset(10) }
        v.findViewById<Button>(R.id.btn_preset_run).setOnClickListener { preset(15) }

        btnGenLoop.setOnClickListener { showLoopDistanceDialog(ctx) }

        v.findViewById<Button>(R.id.btn_start).setOnClickListener {
            if (!MockLocationEngine.isMockLocationEnabled(ctx)) {
                tvState.text = "未获得模拟位置权限，请到状态页按引导设置"
                return@setOnClickListener
            }
            if (!MockLocationEngine.hasLocationPermission(ctx)) {
                tvState.text = getString(R.string.need_location_permission)
                return@setOnClickListener
            }
            val cfg = Prefs.buildConfig(ctx)
            if (cfg == null) {
                tvState.text = if (Prefs.getUiMode(ctx) == MockConfig.MODE_ROUTE)
                    "请先在地图页长按设置起终点，或用手绘/环线生成一条路线" else "请先在地图页长按设置目标位置"
                return@setOnClickListener
            }
            if (cfg.mode == MockConfig.MODE_ROUTE && !Prefs.hasRoute(ctx) && Prefs.getDrawnRoute(ctx) == null) {
                tvState.text = "请先在地图页长按设置起点和终点"
                return@setOnClickListener
            }
            tvState.text = when {
                cfg.mode == MockConfig.MODE_JOYSTICK -> "启动中...（摇杆模式，到地图页拖动摇杆）"
                cfg.mode == MockConfig.MODE_ROUTE && Prefs.hasRoute(ctx) -> "启动中...（路线模式需先规划路径）"
                else -> "启动中...（手绘路线将直接使用）"
            }
            try {
                MockLocationService.start(ctx)
            } catch (t: Throwable) {
                tvState.text = "启动失败: ${t.javaClass.simpleName}"
            }
        }
        v.findViewById<Button>(R.id.btn_stop).setOnClickListener {
            MockLocationService.stop(ctx)
            shownRunning = false
            tvState.text = "已停止"
        }
        return v
    }

    /** 环线中心：轨迹模式长按设的「起点」，没设过则回退固定点；都没有提示用户 */
    private fun loopCenter(): Pair<Double, Double>? {
        val ctx = context ?: return null
        return Prefs.getRouteStart(ctx) ?: Prefs.getFixedPoint(ctx)
    }

    private fun showLoopDistanceDialog(ctx: android.content.Context) {
        // 快速双击防重：generatingLoop 在选定距离后才置位，弹窗本身会弹两个
        if (generatingLoop || loopDialog?.isShowing == true) return
        val center = loopCenter()
        if (center == null) {
            tvLoopHint.text = "⚠ 请先到地图页长按一个位置作为环线中心（轨迹模式下的「起点」），再回来生成"
            return
        }
        val labels = arrayOf("2 公里", "3 公里", "5 公里", "10 公里")
        val kms = doubleArrayOf(2.0, 3.0, 5.0, 10.0)
        loopDialog = AlertDialog.Builder(requireContext())
            .setTitle("选择环线距离")
            .setItems(labels) { _, which -> generateLoop(ctx, center, kms[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 生成跑步环线：中心为视觉坐标（高德源=GCJ-02）→ 转 WGS-84 请求路网拟合
     * → 结果转回视觉坐标存入手绘路线（清掉起终点，保证启动时走手绘优先级）。
     */
    // 生成线程引用：页面销毁时 interrupt——RoutePlanner.loop 档间检查 isInterrupted
    // 提前终止剩余联网档位（最多 5 档 ×8s）。已完成的 Prefs 写入保留：用户点了
    // 「生成」，路线该存下来；守卫的只是视图访问（isAdded）与剩余联网
    @Volatile
    private var loopGenThread: Thread? = null

    private fun generateLoop(ctx: android.content.Context, centerVisual: Pair<Double, Double>, km: Double) {
        generatingLoop = true
        tvLoopHint.text = "⏳ 正在沿真实道路规划 ${km.toInt()} 公里环线…"
        val gcj = Prefs.getMapSource(ctx) == 0
        val (wLat, wLng) = if (gcj) CoordUtil.gcjToWgs(centerVisual.first, centerVisual.second)
        else centerVisual
        val t = Thread {
            var msg: String
            try {
                val loop = RoutePlanner.loop(wLat, wLng, km)
                val visual = loop.points.map { p ->
                    if (gcj) CoordUtil.wgsToGcj(p.first, p.second) else p
                }
                Prefs.setDrawnRoute(ctx, visual)
                Prefs.clearRoute(ctx) // 清起终点：buildConfig/MockLocationService 都会回退到手绘（环线）
                Prefs.saveUiMode(ctx, MockConfig.MODE_ROUTE)
                msg = "✅ 环线已生成（沿真实道路约 %.1f 公里，%d 个点），点「启动模拟」即可开跑".format(
                    loop.totalM / 1000.0, visual.size
                )
            } catch (e: InterruptedException) {
                return@Thread // 页面已离开：静默取消，不写状态不碰视图
            } catch (t: Throwable) {
                msg = "❌ 环线生成失败: ${t.javaClass.simpleName}（可重试，或直接手绘路线）"
            }
            handler.post {
                generatingLoop = false
                if (!isAdded) return@post // 页面已离开：路线已存好，不再碰视图
                tvLoopHint.text = msg
                if (msg.startsWith("✅")) tvHint.text = msg
            }
        }
        loopGenThread = t
        t.start()
    }

    override fun onDestroyView() {
        loopGenThread?.interrupt()
        loopGenThread = null
        super.onDestroyView()
    }

    private fun updateJoystickHint(enabled: Boolean) {
        if (enabled) {
            tvLoopHint.text = "🎮 摇杆模式已启用：到地图页拖动左下角摇杆控制方向，速度用上方滑杆调节"
        }
    }

    private fun updateState() {
        val ctx = context ?: return
        if (Prefs.planningRoute) {
            tvState.text = "路线规划中..."
        } else if (Prefs.isRunning && Prefs.currentLat != 0.0) {
            shownRunning = true
            tvState.text = (if (Prefs.terminalHold) "已到达终点，保持原地模拟\n" else "") +
                "运行中: %.5f, %.5f\n速度: %.0f km/h".format(
                    Prefs.currentLat, Prefs.currentLng, Prefs.currentSpeedKmh
                )
        } else if (shownRunning || tvState.text.startsWith("启动中")) {
            // 服务停止/被拒后回到已停止（旧实现按 startsWith("运行中") 判态，
            // 文本停在"启动中..."时永远不会刷新）
            shownRunning = false
            tvState.text = "已停止"
        }
    }

    override fun onResume() {
        super.onResume()
        // 地图页（手绘保存/长按）可能改了模式，回来时同步单选与提示
        context?.let { ctx ->
            val route = Prefs.getUiMode(ctx) == MockConfig.MODE_ROUTE
            rgMode.check(if (route) R.id.rb_route else R.id.rb_fixed)
            tvHint.text = getString(
                if (route) R.string.pick_route_hint else R.string.pick_point_hint
            )
            val drawn = Prefs.getDrawnRoute(ctx)
            if (route && drawn != null) {
                // 1.0.12 线形单向提示（同步自满血版 1.1.16）：画线（首尾差距极大）=单向到终点保持（打卡场景）；画圈=循环
                val linear = TrackMath.isLinearRoute(TrackMath.densify(drawn))
                tvHint.text = if (linear)
                    "✏️ 已有路线（${drawn.size} 点）：📍 单向路线（首尾不闭合）——一头到另一头，到终点原地保持（跑完打卡场景）"
                else
                    "✏️ 已有路线（${drawn.size} 点，手绘或环线生成）：循环路线，直接点启动即可；长按起终点可替换"
            } else if (route && Prefs.hasRoute(ctx)) {
                tvHint.text = "📍 起终点路线为单向：到达终点后原地保持（打卡场景适用）"
            }
            // 摇杆开关可能被地图页 saveDrawnRoute 关掉，回来时同步
            swJoystick.isChecked = Prefs.getJoystick(ctx)
            updateJoystickHint(Prefs.getJoystick(ctx))
        }
        updateState()
        handler.postDelayed(refresh, 1000)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }
}
