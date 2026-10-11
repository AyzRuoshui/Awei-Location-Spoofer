package com.awei.remotecam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 远程摄像头 App 主界面（代码构建 UI，零第三方依赖）。
 * 手机无需 Root：打开服务 → 本机 App 连接时弹授权 → 同意即出画面。
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tvInfo: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvRelay: TextView
    private lateinit var swServer: Switch
    private lateinit var swRelay: Switch
    private lateinit var etRelayServer: android.widget.EditText
    private var suppressSwitch = false

    private val poll = object : Runnable {
        override fun run() {
            refreshInfo()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    private fun buildUi(): View {
        val ctx = this
        val pad = (resources.displayMetrics.density * 16).toInt()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(ctx).apply { addView(root) }

        fun title(s: String) = TextView(ctx).apply {
            text = s
            textSize = 20f
            paint.isFakeBoldText = true
        }

        fun hint(s: String) = TextView(ctx).apply {
            text = s
            textSize = 13f
            setPadding(0, dp(4), 0, dp(8))
        }

        // ---- 服务开关 ----
        swServer = Switch(ctx).apply {
            text = "摄像头共享服务"
            textSize = 16f
            isChecked = CamServerService.running
            setOnCheckedChangeListener { _, checked ->
                if (suppressSwitch) return@setOnCheckedChangeListener
                if (checked) {
                    if (!hasPerm(Manifest.permission.CAMERA)) {
                        isChecked = false
                        toast("请先授予相机权限")
                        requestPermissionsIfNeeded()
                        return@setOnCheckedChangeListener
                    }
                    startService(Intent(ctx, CamServerService::class.java).setAction(CamServerService.ACTION_START))
                } else {
                    startService(Intent(ctx, CamServerService::class.java).setAction(CamServerService.ACTION_STOP))
                }
            }
        }
        root.addView(swServer)

        tvInfo = TextView(ctx).apply {
            textSize = 18f
            setPadding(0, dp(12), 0, dp(12))
            gravity = Gravity.CENTER
        }
        root.addView(tvInfo)
        root.addView(hint("局域网直连：本机 App「虚拟摄像头」选择远程模式后，填入上面的地址和配对码。两台手机需在同一 WiFi（或可互通网络）。"))

        // ---- 服务器中转（跨网络远程调用） ----
        root.addView(title("服务器中转（跨网络）").apply { setPadding(0, dp(12), 0, dp(4)) })
        swRelay = Switch(ctx).apply {
            text = "连接中转服务器（显示远程 ID）"
            textSize = 16f
            isChecked = Settings.relayEnabled(ctx)
            setOnCheckedChangeListener { _, checked ->
                Settings.setRelayEnabled(ctx, checked)
                if (checked) {
                    // 必须发 ACTION_RELAY：ACTION_START 在服务已运行时被 ensureStarted 的
                    // running 短路，末尾的 ensureRelayLink 执行不到——"先开服务再开中转"
                    // 这个最自然的操作顺序会让链路永远建不起来。ACTION_RELAY 内部先
                    // ensureStarted()（覆盖服务未启动的情形），再强制按新配置重建链路
                    startService(Intent(ctx, CamServerService::class.java).setAction(CamServerService.ACTION_RELAY))
                    toast("中转已开启，连接服务器中…")
                } else if (CamServerService.running) {
                    startService(Intent(ctx, CamServerService::class.java).setAction(CamServerService.ACTION_RELAY))
                }
                refreshInfo()
            }
        }
        root.addView(swRelay)
        etRelayServer = android.widget.EditText(ctx).apply {
            hint = "中转服务器地址，默认已填阿伟服务器"
            setText(Settings.relayServer(ctx))
            maxLines = 1
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val old = Settings.relayServer(ctx)
                    val now = text.toString().trim()
                    if (now != old) {
                        Settings.setRelayServer(ctx, now)
                        if (Settings.relayEnabled(ctx) && CamServerService.running) {
                            startService(Intent(ctx, CamServerService::class.java).setAction(CamServerService.ACTION_RELAY))
                            toast("中转服务器已更新，重连中")
                        }
                    }
                }
            }
        }
        root.addView(etRelayServer)
        tvRelay = TextView(ctx).apply {
            textSize = 14f
            setPadding(0, dp(4), 0, 0)
        }
        root.addView(tvRelay)
        root.addView(hint("开启后拿到「远程 ID」，把它填进本机 App 即可跨网络调用（流量/不同 WiFi 都行），配对码照旧生效。中转服务器已默认填好，自建服务器可改地址。"))

        // ---- 设置 ----
        root.addView(title("设置").apply { setPadding(0, dp(16), 0, dp(4)) })

        root.addView(hint("画质固定 720p@24fps@1.5Mbps——适配 3Mbps 中转服务器（VBR 峰值留 25% 余量防卡顿）；LAN 直连无需调档。"))

        root.addView(Switch(ctx).apply {
            text = "输出竖屏画面（旋转 90°）"
            isChecked = Settings.portrait(ctx)
            setOnCheckedChangeListener { _, c -> Settings.setPortrait(ctx, c) }
        })
        root.addView(hint("默认开启，适合视频通话；远程手机横着架起来拍摄街景时关闭。"))

        root.addView(Switch(ctx).apply {
            text = "自动同意授权请求"
            isChecked = Settings.autoApprove(ctx)
            setOnCheckedChangeListener { _, c -> Settings.setAutoApprove(ctx, c) }
        })
        root.addView(hint("开启后本机请求不再弹窗直接同意；仅在自己可控的两台设备间使用。"))

        root.addView(Button(ctx).apply {
            text = "重新生成配对码"
            setOnClickListener {
                Settings.regenToken(ctx)
                toast("配对码已更新：${Settings.token(ctx)}")
                refreshInfo()
            }
        })
        root.addView(hint("重新生成后旧连接不会立即掉线，但新连接需用新码。"))

        // ---- 日志 ----
        root.addView(title("日志").apply { setPadding(0, dp(16), 0, dp(4)) })
        tvLog = TextView(ctx).apply {
            textSize = 12f
            setTextIsSelectable(true)
        }
        root.addView(tvLog, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        return scroll
    }

    private fun refreshInfo() {
        val ips = NetUtil.lanIps()
        val ip = ips.firstOrNull() ?: "未连接 WiFi"
        tvInfo.text = if (CamServerService.running) {
            "服务运行中\n地址  $ip:${Settings.port(this)}\n配对码  ${Settings.token(this)}\n状态：${CamServerService.statusLine}"
        } else {
            "服务未启动\n地址  $ip:${Settings.port(this)}\n配对码  ${Settings.token(this)}"
        }
        tvRelay.text = if (!Settings.relayEnabled(this)) {
            "中转未开启（本机 App 只能在同一 WiFi 直连）"
        } else {
            val id = RelayLink.currentId
            val srv = Settings.relayServer(this)
            buildString {
                append("远程 ID：")
                append(id ?: "——")
                append('\n')
                append("中转状态：")
                append(RelayLink.statusLine)
                if (id == null && srv.isBlank()) append("\n⚠ 请先填写中转服务器地址")
            }
        }
        tvLog.text = CamServerService.logText().ifEmpty { "（暂无日志）" }
        // 只在值变化时回写：服务启动的异步窗口内每 2s 强制 setChecked 会让开关视觉闪跳
        if (swServer.isChecked != CamServerService.running) {
            suppressSwitch = true
            swServer.isChecked = CamServerService.running
            suppressSwitch = false
        }
    }

    private fun hasPerm(p: String): Boolean =
        Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < 23) return
        val need = ArrayList<String>()
        if (!hasPerm(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33 && !hasPerm(Manifest.permission.POST_NOTIFICATIONS)) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            toast("权限已授予")
            return
        }
        toast("缺少权限将无法工作（相机/通知）")
        // 相机被永久拒绝（勾了"不再询问"后系统不再弹窗，重复 request 静默失败）：
        // 引导用户去应用设置页手动开启
        val camIdx = permissions.indexOf(Manifest.permission.CAMERA)
        if (camIdx >= 0 && grantResults[camIdx] != PackageManager.PERMISSION_GRANTED &&
            Build.VERSION.SDK_INT >= 23 && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        ) {
            android.app.AlertDialog.Builder(this)
                .setTitle("需要相机权限")
                .setMessage("远程摄像头必须使用相机采集画面。请在接下来的页面中把「相机」权限设为允许，然后回来重新打开服务。")
                .setPositiveButton("去设置") { _, _ ->
                    try {
                        startActivity(
                            Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", packageName, null)
                            )
                        )
                    } catch (t: Throwable) {
                    }
                }
                .setNegativeButton("暂不", null)
                .show()
        }
    }

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
