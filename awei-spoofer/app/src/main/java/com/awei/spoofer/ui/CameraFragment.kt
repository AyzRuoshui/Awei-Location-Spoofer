package com.awei.spoofer.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.awei.spoofer.R
import com.awei.spoofer.data.HookConfigStore
import com.google.android.material.switchmaterial.SwitchMaterial

class CameraFragment : Fragment() {

    private lateinit var swCam: SwitchMaterial
    private lateinit var swMirror: SwitchMaterial
    private lateinit var tvVideo: TextView
    private lateinit var btnPick: Button
    private lateinit var rgSource: RadioGroup
    private lateinit var rbFile: RadioButton
    private lateinit var rbRemote: RadioButton
    private lateinit var rbNetwork: RadioButton
    private lateinit var rbImage: RadioButton
    private lateinit var rgLink: RadioGroup
    private lateinit var rbLinkLan: RadioButton
    private lateinit var rbLinkRelay: RadioButton
    private lateinit var etRelayServer: EditText
    private lateinit var etAddr: EditText
    private lateinit var etToken: EditText

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val ctx = requireContext()
        val v = inflater.inflate(R.layout.fragment_camera, container, false)
        swCam = v.findViewById(R.id.sw_cam)
        swMirror = v.findViewById(R.id.sw_mirror)
        tvVideo = v.findViewById(R.id.tv_video)
        btnPick = v.findViewById(R.id.btn_pick_video)
        rgSource = v.findViewById(R.id.rg_source)
        rbFile = v.findViewById(R.id.rb_source_file)
        rbRemote = v.findViewById(R.id.rb_source_remote)
        rbNetwork = v.findViewById(R.id.rb_source_network)
        rbImage = v.findViewById(R.id.rb_source_image)
        rgLink = v.findViewById(R.id.rg_link_mode)
        rbLinkLan = v.findViewById(R.id.rb_link_lan)
        rbLinkRelay = v.findViewById(R.id.rb_link_relay)
        etRelayServer = v.findViewById(R.id.et_relay_server)
        etAddr = v.findViewById(R.id.et_remote_addr)
        etToken = v.findViewById(R.id.et_remote_token)

        val savedUri = HookConfigStore.cameraVideoUri(ctx)
        tvVideo.text = savedUri?.let { Uri.parse(it).lastPathSegment } ?: "未选择视频"
        swCam.isChecked = HookConfigStore.cameraEnabled(ctx)
        swMirror.isChecked = HookConfigStore.cameraMirror(ctx)
        val targetRadioId = when (HookConfigStore.cameraSource(ctx)) {
            HookConfigStore.CAM_SOURCE_REMOTE -> R.id.rb_source_remote
            HookConfigStore.CAM_SOURCE_NETWORK -> R.id.rb_source_network
            HookConfigStore.CAM_SOURCE_IMAGE -> R.id.rb_source_image
            else -> R.id.rb_source_file
        }
        rgSource.check(targetRadioId)
        etAddr.setText(HookConfigStore.cameraRemoteAddr(ctx) ?: "")
        etToken.setText(HookConfigStore.cameraRemoteToken(ctx) ?: "")
        if (HookConfigStore.cameraLinkMode(ctx) == HookConfigStore.CAM_LINK_RELAY) rbLinkRelay.isChecked = true
        else rbLinkLan.isChecked = true
        etRelayServer.setText(HookConfigStore.cameraRelayServer(ctx) ?: "")
        updateSourceUi(false)

        swMirror.setOnCheckedChangeListener { _, checked ->
            HookConfigStore.setCamera(ctx, HookConfigStore.cameraEnabled(ctx), HookConfigStore.cameraVideoUri(ctx), mirror = checked)
        }

        swCam.setOnCheckedChangeListener { _, checked ->
            val remote = rbRemote.isChecked
            val network = rbNetwork.isChecked
            if (checked) {
                val ok = when {
                    remote -> {
                        saveSource(ctx)
                        val relay = rbLinkRelay.isChecked
                        val base = etAddr.text.isNotBlank() && etToken.text.isNotBlank()
                        if (relay) base && etRelayServer.text.isNotBlank() else base
                    }
                    network -> {
                        saveSource(ctx)
                        etAddr.text.toString().trim().let {
                            it.startsWith("rtsp://") || it.startsWith("rtmp://") ||
                                it.startsWith("http://") || it.startsWith("https://")
                        }
                    }
                    else -> HookConfigStore.cameraVideoUri(ctx) != null
                }
                if (!ok) {
                    swCam.isChecked = false
                    toast(
                        when {
                            remote && rbLinkRelay.isChecked -> "请先填写中转服务器地址、远程 ID 和配对码"
                            remote -> "请先填写远程地址和配对码"
                            network -> "请先填写 rtsp:// 或 rtmp:// 或 http:// 开头的流地址"
                            rbImage.isChecked -> "请先选择照片文件"
                            else -> "请先选择视频文件"
                        }
                    )
                    return@setOnCheckedChangeListener
                }
            }
            HookConfigStore.setCamera(ctx, checked, HookConfigStore.cameraVideoUri(ctx), mirror = HookConfigStore.cameraMirror(ctx))
            if (checked) {
                toast(
                    when {
                        // 配置 15s 刷新：目标 App 下次打开相机即按新源工作，无需重启整个 App
                        remote -> "已启用远程源（目标 App 下次打开相机时生效）"
                        network -> "已启用网络源（目标 App 下次打开相机时生效）"
                        else -> "已启用: ${Uri.parse(HookConfigStore.cameraVideoUri(ctx) ?: "").lastPathSegment}"
                    }
                )
            }
        }

        rgSource.setOnCheckedChangeListener { _, _ ->
            saveSource(ctx)
            updateSourceUi(true)
        }
        rgLink.setOnCheckedChangeListener { _, _ ->
            saveSource(ctx)
            updateSourceUi(true)
        }
        etAddr.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveSource(ctx) }
        etToken.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveSource(ctx) }
        etRelayServer.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveSource(ctx) }

        btnPick.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (rbImage.isChecked) "image/*" else "video/*"
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivityForResult(i, REQ_VIDEO)
        }
        return v
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VIDEO && resultCode == android.app.Activity.RESULT_OK) {
            val uri = data?.data ?: return
            val ctx = requireContext()
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (t: Throwable) {
            }
            // 智能识别选中文件类型（图片 vs 视频）并同步单选框与配置
            val mimeType = try { ctx.contentResolver.getType(uri) } catch (t: Throwable) { null }
            val pathStr = uri.toString().lowercase()
            val isImage = mimeType?.startsWith("image/") == true ||
                pathStr.endsWith(".jpg") || pathStr.endsWith(".jpeg") ||
                pathStr.endsWith(".png") || pathStr.endsWith(".webp") ||
                pathStr.contains("image")

            val targetRadioId = if (isImage) R.id.rb_source_image else R.id.rb_source_file
            rgSource.check(targetRadioId)

            HookConfigStore.setCamera(ctx, swCam.isChecked, uri.toString(), mirror = HookConfigStore.cameraMirror(ctx))
            saveSource(ctx)
            tvVideo.text = "已选择: ${uri.lastPathSegment}"
            updateSourceUi(false)
        }
    }

    private fun currentSource(ctx: Context): String = when {
        rbRemote.isChecked -> HookConfigStore.CAM_SOURCE_REMOTE
        rbNetwork.isChecked -> HookConfigStore.CAM_SOURCE_NETWORK
        rbImage.isChecked -> HookConfigStore.CAM_SOURCE_IMAGE
        else -> HookConfigStore.CAM_SOURCE_FILE
    }

    private fun saveSource(ctx: Context) {
        HookConfigStore.setCameraSource(
            ctx,
            currentSource(ctx),
            etAddr.text.toString(),
            etToken.text.toString(),
            if (rbLinkRelay.isChecked) HookConfigStore.CAM_LINK_RELAY else HookConfigStore.CAM_LINK_LAN,
            etRelayServer.text.toString()
        )
    }

    /** 视图可用性随源切换；checkEnable=true 时校验启用开关（切源后调用） */
    private fun updateSourceUi(checkEnable: Boolean) {
        val source = currentSource(requireContext())
        val remote = source == HookConfigStore.CAM_SOURCE_REMOTE
        val network = source == HookConfigStore.CAM_SOURCE_NETWORK
        val relay = remote && rbLinkRelay.isChecked
        val local = source == HookConfigStore.CAM_SOURCE_FILE || source == HookConfigStore.CAM_SOURCE_IMAGE
        btnPick.isEnabled = local
        btnPick.alpha = if (local) 1f else 0.4f
        tvVideo.visibility = if (local) View.VISIBLE else View.GONE
        btnPick.text = if (source == HookConfigStore.CAM_SOURCE_IMAGE) "选择照片文件" else "选择视频文件"
        rgLink.visibility = if (remote) View.VISIBLE else View.GONE
        etRelayServer.visibility = if (relay) View.VISIBLE else View.GONE
        etAddr.visibility = if (remote || network) View.VISIBLE else View.GONE
        etToken.visibility = if (remote) View.VISIBLE else View.GONE
        etAddr.hint = when {
            network -> "流地址，如 rtsp://192.168.1.10:554/stream1"
            relay -> "远程 ID（远程手机「服务器中转」里显示的数字）"
            else -> "远程手机地址，如 192.168.1.23:17521"
        }
        if (checkEnable && swCam.isChecked) {
            val incomplete = when {
                remote && relay -> etAddr.text.isBlank() || etToken.text.isBlank() || etRelayServer.text.isBlank()
                remote -> etAddr.text.isBlank() || etToken.text.isBlank()
                network -> etAddr.text.isBlank()
                else -> false
            }
            if (incomplete) {
                swCam.isChecked = false
                toast(
                    when {
                        remote && relay -> "中转源未配置完整（服务器地址/远程 ID/配对码），已停用"
                        remote -> "远程源未配置完整，已停用"
                        else -> "网络源未配置地址，已停用"
                    }
                )
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_VIDEO = 2001
    }
}
