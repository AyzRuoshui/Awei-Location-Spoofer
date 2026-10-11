package com.awei.spoofer.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.awei.spoofer.R
import com.awei.spoofer.data.SpLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志页：实时滚动展示本 App 全部动作日志，
 * 支持导出 txt 经系统分享（微信等）发给开发者排查。
 */
class LogFragment : Fragment() {

    private var tvLog: TextView? = null
    private var scroll: ScrollView? = null
    private val listener: (SpLogger.Entry) -> Unit = { e ->
        activity?.runOnUiThread { appendLine(e) }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_log, container, false)
        tvLog = v.findViewById(R.id.tv_log)
        scroll = v.findViewById(R.id.scroll)
        v.findViewById<Button>(R.id.btn_export).setOnClickListener { export() }
        v.findViewById<Button>(R.id.btn_clear).setOnClickListener {
            SpLogger.clear()
            renderAll()
        }
        renderAll()
        return v
    }

    override fun onResume() {
        super.onResume()
        renderAll()
        SpLogger.addListener(listener)
    }

    override fun onPause() {
        SpLogger.removeListener(listener)
        super.onPause()
    }

    private fun renderAll() {
        val sb = StringBuilder()
        for (e in SpLogger.snapshot()) sb.appendLine(SpLogger.format(e))
        tvLog?.text = sb.toString()
        scrollBottom()
    }

    private fun appendLine(e: SpLogger.Entry) {
        val tv = tvLog ?: return
        tv.append(SpLogger.format(e) + "\n")
        scrollBottom()
    }

    private fun scrollBottom() {
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun export() {
        val ctx = context ?: return
        val file = SpLogger.export(ctx)
        if (file == null) {
            Toast.makeText(ctx, "导出失败", Toast.LENGTH_SHORT).show()
            return
        }
        SpLogger.i("Log", "导出日志 ${file.name} (${file.length() / 1024}KB)")
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.logs", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(
                    Intent.EXTRA_SUBJECT,
                    "阿伟伪装日志 ${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "发送日志文件（选微信即可）"))
        } catch (t: Throwable) {
            Toast.makeText(ctx, "分享失败: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }
}
