package com.awei.spoofer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.awei.spoofer.R
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private val fragments = mutableMapOf<Int, Fragment>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }
        if (savedInstanceState == null) {
            showTab(R.id.nav_status)
        }
        requestNeededPermissions()
    }

    private fun showTab(tabId: Int) {
        val tx = supportFragmentManager.beginTransaction()
        for ((id, f) in fragments) {
            if (id != tabId) tx.hide(f)
        }
        // 日志页独立于底部 tab（add+返回栈），切 tab 时隐藏避免盖在新 tab 上
        supportFragmentManager.findFragmentByTag("log_view")?.let { tx.hide(it) }
        // 优先从缓存取；旋转/重建后从 FragmentManager 找回，避免重复 add
        val existing = fragments[tabId]
            ?: supportFragmentManager.findFragmentByTag("tab_$tabId")?.also { fragments[tabId] = it }
        if (existing != null) {
            tx.show(existing)
        } else {
            val f = createFragment(tabId)
            fragments[tabId] = f
            tx.add(R.id.container, f, "tab_$tabId")
        }
        tx.commitAllowingStateLoss()
    }

    private fun createFragment(tabId: Int): Fragment = when (tabId) {
        R.id.nav_map -> MapFragment()
        R.id.nav_track -> TrackFragment()
        R.id.nav_camera -> CameraFragment()
        R.id.nav_settings -> SettingsFragment()
        else -> StatusFragment()
    }

    private fun requestNeededPermissions() {
        val needed = ArrayList<String>()
        fun need(p: String) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) needed.add(p)
        }
        // 精略+精确定位一起请求（Android 12+ 规范），再加通知权限
        need(Manifest.permission.ACCESS_COARSE_LOCATION)
        need(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) need(Manifest.permission.POST_NOTIFICATIONS)
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERM)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return
        for (i in permissions.indices) {
            val granted = grantResults[i] == PackageManager.PERMISSION_GRANTED
            com.awei.spoofer.data.SpLogger.i("Perm", "${permissions[i]} = ${if (granted) "已授权" else "拒绝"}")
        }
        val locDenied = permissions.filterIndexed { i, p ->
            p.startsWith("android.permission.ACCESS_") && grantResults[i] != PackageManager.PERMISSION_GRANTED
        }
        // 拒绝过且系统不再弹窗（用户勾选"不再询问"）→ 引导去应用设置页开启
        val permanentlyDenied = locDenied.any {
            !ActivityCompat.shouldShowRequestPermissionRationale(this, it)
        }
        if (locDenied.isNotEmpty() && permanentlyDenied) {
            showGoSettingsDialog()
        }
    }

    private fun showGoSettingsDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("需要定位权限")
            .setMessage("地图「定位到我」需要定位权限（不影响模拟功能本身）。请在接下来打开的页面中，把\"位置信息\"权限设为允许。")
            .setPositiveButton("去设置") { _, _ ->
                try {
                    startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", packageName, null)
                        )
                    )
                } catch (_: Exception) {
                }
            }
            .setNegativeButton("暂不", null)
            .setCancelable(false)
            .show()
    }

    companion object {
        private const val REQ_PERM = 100
    }
}
