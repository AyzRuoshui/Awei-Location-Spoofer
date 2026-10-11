package com.awei.spoofer.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import com.awei.spoofer.App
import com.awei.spoofer.R
import com.awei.spoofer.data.Prefs
import com.awei.spoofer.engine.mock.MockLocationEngine
import com.awei.spoofer.engine.mock.MockLocationService

class StatusFragment : Fragment() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tvState: TextView
    private lateinit var tvPosition: TextView
    private lateinit var btnToggle: Button
    private lateinit var crashBox: View
    private lateinit var tvCrash: TextView
    private lateinit var tvChecklist: TextView
    private lateinit var btnChkPerm: Button
    private lateinit var btnChkDev: Button
    private lateinit var btnChkRecheck: Button
    private lateinit var btnCopyCrash: Button

    private val refresh = object : Runnable {
        override fun run() {
            // 本页被 hide()（切到别的 tab）时 Fragment 不走 onPause，轮询若照跑每秒做
            // AppOps binder + 磁盘 IO：跳过重绘但仍保持节拍
            if (!isHidden) updateUi()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_status, container, false)
        tvState = v.findViewById(R.id.tv_state)
        tvPosition = v.findViewById(R.id.tv_position)
        btnToggle = v.findViewById(R.id.btn_toggle)
        crashBox = v.findViewById(R.id.crash_box)
        tvCrash = v.findViewById(R.id.tv_crash)
        tvChecklist = v.findViewById(R.id.tv_checklist)
        btnChkPerm = v.findViewById(R.id.btn_chk_perm)
        btnChkDev = v.findViewById(R.id.btn_chk_dev)
        btnChkRecheck = v.findViewById(R.id.btn_chk_recheck)
        btnCopyCrash = v.findViewById(R.id.btn_copy_crash)

        tvCrash.setOnClickListener {
            val ctx = activity ?: return@setOnClickListener
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", tvCrash.text))
            android.widget.Toast.makeText(ctx, "日志已复制，请粘贴发给开发者", android.widget.Toast.LENGTH_LONG).show()
        }
        tvCrash.setOnLongClickListener {
            App.clearCrashLog(requireContext())
            crashBox.visibility = View.GONE
            android.widget.Toast.makeText(requireContext(), "崩溃记录已清除", android.widget.Toast.LENGTH_SHORT).show()
            true
        }
        // 一键复制完整崩溃日志（显示框只截了尾部，完整内容从这里复制）
        btnCopyCrash.setOnClickListener {
            val act = activity ?: return@setOnClickListener
            val text = try { App.crashLog(act)?.readText() } catch (_: Throwable) { null }
            if (text.isNullOrBlank()) {
                android.widget.Toast.makeText(act, "没有崩溃日志", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val cm = act.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
            android.widget.Toast.makeText(act, "完整崩溃日志已复制，可粘贴发给开发者", android.widget.Toast.LENGTH_LONG).show()
        }
        v.findViewById<Button>(R.id.btn_log).setOnClickListener {
            // 已有日志页则复用（重复 add 会累积 N 个实例：各持整份日志文本 + 各挂一个
            // SpLogger 监听器，隐藏的也一直在收），没有才新建
            val fm = parentFragmentManager
            val existing = fm.findFragmentByTag("log_view")
            if (existing != null) {
                fm.beginTransaction().show(existing).commitAllowingStateLoss()
            } else {
                fm.beginTransaction()
                    .add(R.id.container, LogFragment(), "log_view")
                    .addToBackStack("log_view")
                    .commitAllowingStateLoss()
            }
        }

        btnToggle.setOnClickListener {
            if (Prefs.isRunning) {
                MockLocationService.stop(requireContext())
            } else {
                val mockOk = MockLocationEngine.isMockLocationEnabled(requireContext())
                if (mockOk && MockLocationEngine.hasLocationPermission(requireContext())) {
                    // 未选点就启动会在服务里静默 stopSelf（用户视角=按钮没反应）——提前拦截
                    if (Prefs.buildConfig(requireContext()) == null) {
                        Toast.makeText(
                            requireContext(), "请先到「地图」页长按选择目标位置", Toast.LENGTH_LONG
                        ).show()
                        return@setOnClickListener
                    }
                    try {
                        MockLocationService.start(requireContext())
                    } catch (t: Throwable) {
                        Toast.makeText(
                            requireContext(), "启动失败: ${t.javaClass.simpleName}", Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    // 原先是静默不响应，用户会以为按钮坏了
                    Toast.makeText(requireContext(), "先完成下方「运行与体检」里的红色 ✗ 项", Toast.LENGTH_LONG).show()
                }
            }
        }

        // 引导按钮：每个 ✗ 项一个明确动作
        btnChkPerm.setOnClickListener {
            // requestCode 100 = MainActivity.REQ_PERM，复用其回调（授权日志 + 永久拒绝跳设置引导）
            val act = activity ?: return@setOnClickListener
            ActivityCompat.requestPermissions(
                act,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                100
            )
        }
        btnChkDev.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        btnChkRecheck.setOnClickListener {
            MockLocationService.rerunSelfCheck()
            updateUi()
        }
        return v
    }

    override fun onResume() {
        super.onResume()
        updateUi()
        handler.postDelayed(refresh, 1000)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun updateUi() {
        val ctx = activity ?: return
        // 只在尚未发现崩溃时读 crash.log（每秒 readText 是主线程磁盘 IO；
        // 发现后由长按清除归位 GONE，才会重新开始检查）
        if (crashBox.visibility == View.GONE) {
            App.crashLog(ctx)?.let { f ->
                try {
                    val text = f.readText()
                    if (text.isNotBlank()) {
                        crashBox.visibility = View.VISIBLE
                        tvCrash.text = text.takeLast(1500)
                    }
                } catch (_: Throwable) {
                }
            }
        }

        if (Prefs.isRunning) {
            tvState.text = getString(R.string.mock_running)
            tvState.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.state_ok))
            btnToggle.text = getString(R.string.stop_mock)
        } else {
            tvState.text = getString(R.string.mock_stopped)
            tvState.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.state_warn))
            btnToggle.text = getString(R.string.start_mock)
        }
        tvPosition.text = if (Prefs.isRunning && Prefs.currentLat != 0.0) {
            "当前模拟: %.5f, %.5f\n速度: %.0f km/h".format(
                Prefs.currentLat, Prefs.currentLng, Prefs.currentSpeedKmh
            )
        } else {
            "未注入"
        }

        val items = ModeChecker.build(ctx)
        renderChecklist(items)
        btnChkPerm.visibility = if (items.any { it.action == ModeChecker.A_LOCATION_PERM }) View.VISIBLE else View.GONE
        btnChkDev.visibility = if (items.any { it.action == ModeChecker.A_DEV_OPTIONS }) View.VISIBLE else View.GONE
    }

    private fun renderChecklist(items: List<ModeChecker.Item>) {
        val ctx = context ?: return
        // 状态符号颜色统一走语义色资源（与暗色面板对比度校准过，
        // 旧的硬编码 #2E7D32/#C62828/#888888 暗色下对比度不足）
        fun colorOf(res: Int) = androidx.core.content.ContextCompat.getColor(ctx, res)
        val sb = SpannableStringBuilder()
        for (item in items) {
            val (sym, color) = when (item.state) {
                ModeChecker.ST_OK -> "✓" to colorOf(R.color.state_ok)
                ModeChecker.ST_FAIL -> "✗" to colorOf(R.color.state_fail)
                ModeChecker.ST_WARN -> "⚠" to colorOf(R.color.state_warn)
                else -> "·" to colorOf(R.color.text_secondary)
            }
            val start = sb.length
            sb.append(sym).append("  ").append(item.text).append('\n')
            sb.setSpan(ForegroundColorSpan(color), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tvChecklist.text = sb
    }
}
