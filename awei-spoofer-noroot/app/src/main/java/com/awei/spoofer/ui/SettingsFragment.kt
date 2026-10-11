package com.awei.spoofer.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.awei.spoofer.R
import com.awei.spoofer.data.Prefs
import com.awei.spoofer.engine.mock.MockLocationEngine
import com.awei.spoofer.engine.mock.MockLocationService

class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_settings, container, false)
        val tvState = v.findViewById<TextView>(R.id.tv_engine_state)
        v.findViewById<Button>(R.id.btn_open_dev).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        v.findViewById<Button>(R.id.btn_check_again).setOnClickListener { refresh(tvState) }
        // 版本号动态取，避免"关于"里的版本漂移（此前硬编码停在 v0.5.4）
        context?.let { c ->
            try {
                val ver = c.packageManager.getPackageInfo(c.packageName, 0)?.versionName
                val tvAbout = v.findViewById<TextView>(R.id.tv_about)
                tvAbout.text = "阿伟定位伪装·免Root版 v$ver\n\n" + tvAbout.text
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
        val mock = MockLocationEngine.isMockLocationEnabled(ctx)
        val loc = MockLocationEngine.hasLocationPermission(ctx)
        val run = MockLocationService.hasInstance
        tv.text = buildString {
            appendLine("模拟位置权限: ${if (mock) "已授权 ✓" else "未授权 ✗"}")
            appendLine("定位权限: ${if (loc) "已授权 ✓" else "未授权 ✗"}")
            appendLine("引擎1 (Mock) 服务: ${if (run) "运行中" else "已停止"}")
            appendLine()
            appendLine("逐项体检/引导：见「状态」页的「运行与体检」卡片")
        }
    }
}
