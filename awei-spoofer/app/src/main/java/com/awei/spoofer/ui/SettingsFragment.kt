package com.awei.spoofer.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.awei.spoofer.R
import com.awei.spoofer.data.Prefs
import com.awei.spoofer.engine.mock.MockLocationService

class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_settings, container, false)
        val tvState = v.findViewById<TextView>(R.id.tv_engine_state)
        v.findViewById<Button>(R.id.btn_check_again).setOnClickListener { refresh(tvState) }
        // 版本号动态取，避免"关于"里的版本漂移
        context?.let { c ->
            try {
                val ver = c.packageManager.getPackageInfo(c.packageName, 0)?.versionName
                val tvAbout = v.findViewById<TextView>(R.id.tv_about)
                tvAbout.text = "阿伟定位摄像头伪装 v$ver\n\n" + tvAbout.text
            } catch (t: Throwable) {
            }
        }
        setupMapSource(v)
        refresh(tvState)
        return v
    }

    private fun setupMapSource(v: View) {
        val ctx = context ?: return
        val rg = v.findViewById<android.widget.RadioGroup>(R.id.rg_map_source)
        val rbGaode = v.findViewById<android.widget.RadioButton>(R.id.rb_map_gaode)
        val rbArcgis = v.findViewById<android.widget.RadioButton>(R.id.rb_map_arcgis)
        val rbOsm = v.findViewById<android.widget.RadioButton>(R.id.rb_map_osm)
        when (Prefs.getMapSource(ctx)) {
            MapSources.ARCGIS -> rbArcgis.isChecked = true
            MapSources.OSM -> rbOsm.isChecked = true
            else -> rbGaode.isChecked = true
        }
        rg.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rb_map_arcgis -> MapSources.ARCGIS
                R.id.rb_map_osm -> MapSources.OSM
                else -> MapSources.GAODE
            }
            context?.let { Prefs.setMapSource(it, mode) }
        }
    }

    private fun refresh(tv: TextView) {
        val ctx = context ?: return
        val loc = ModeChecker.hasLocationPermission(ctx)
        val run = MockLocationService.hasInstance
        tv.text = buildString {
            appendLine("定位权限: ${if (loc) "已授权 ✓" else "未授权（仅影响地图「定位到我」）"}")
            appendLine("模拟服务（Hook 配置下发）: ${if (run) "运行中" else "已停止"}")
            appendLine("Xposed Hook 注入: ${if (com.awei.spoofer.data.HookStatusStore.selfHooked(ctx)) "模块已激活 ✓" else "未注入（需 LSPosed 启用模块）"}")
            appendLine()
            appendLine("逐项体检/引导：见「状态」页的「体检与引导」卡片")
        }
    }
}
