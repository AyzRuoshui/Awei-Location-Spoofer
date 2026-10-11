package com.awei.spoofer.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.awei.spoofer.R
import com.awei.spoofer.data.Prefs
import com.awei.spoofer.engine.mock.MockLocationEngine
import com.awei.spoofer.engine.mock.MockLocationService
import com.awei.spoofer.track.PlaceSearcher
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class MapFragment : Fragment() {

    private lateinit var map: MapView
    private var startMarker: Marker? = null
    private var endMarker: Marker? = null
    private var fixedMarker: Marker? = null
    private var drawnPolyline: Polyline? = null

    // 当前模拟位置点（蓝色）：1s 轮询 Prefs.currentLat/Lng，实时显示模拟走到哪了
    private var posMarker: Marker? = null
    private val posRefreshHandler = Handler(Looper.getMainLooper())
    private val posRefresh = object : Runnable {
        override fun run() {
            if (!isHidden) updatePosMarker()
            posRefreshHandler.postDelayed(this, 1000)
        }
    }

    // 摇杆控件（免Root版独有）
    private var joyHost: View? = null
    private var joystick: JoystickView? = null

    // 手绘状态
    private var drawing = false
    private var drawnPoints = mutableListOf<GeoPoint>()

    // requestSingleUpdate(API<30) 的监听器：GPS 关闭时永不回调，必须主动注销
    //（否则每次进地图页/点定位都累积一个持 Fragment 引用的监听器）
    private var singleUpdateListener: android.location.LocationListener? = null

    private fun removeSingleUpdateListener() {
        val lm = context?.getSystemService(android.content.Context.LOCATION_SERVICE)
            as? LocationManager ?: return
        singleUpdateListener?.let { l ->
            try {
                lm.removeUpdates(l)
            } catch (t: Throwable) {
            }
        }
        singleUpdateListener = null
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_map, container, false)
        map = root.findViewById(R.id.map)
        map.setTileSource(MapSources.forMode(Prefs.getMapSource(requireContext())))
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0)
        map.controller.setCenter(GeoPoint(39.9042, 116.4074))

        val detector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                val geo = map.projection.fromPixels(e.x.toInt(), e.y.toInt()) as? GeoPoint ?: return
                onMapLongPress(geo.latitude, geo.longitude)
            }
        })
        map.setOnTouchListener { _, event ->
            if (drawing) {
                handleDrawTouch(event)
            } else {
                detector.onTouchEvent(event)
                false
            }
        }

        root.findViewById<View>(R.id.btn_locate).setOnClickListener { locateToMe(true) }
        root.findViewById<View>(R.id.btn_draw).setOnClickListener { toggleDraw() }
        root.findViewById<View>(R.id.btn_clear).setOnClickListener { confirmClear() }

        // 地名搜索：回车或点 🔍 触发；结果点一下直接飞过去并落点
        val etSearch = root.findViewById<android.widget.EditText>(R.id.et_search)
        root.findViewById<View>(R.id.btn_search).setOnClickListener { doSearch(etSearch.text.toString()) }
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                doSearch(etSearch.text.toString())
                true
            } else {
                false
            }
        }

        // 摇杆绑定：拖动 → 写 Prefs.joystickVec，TrackEngine tick 读取
        joyHost = root.findViewById(R.id.joy_host)
        joystick = root.findViewById<JoystickView>(R.id.joystick)?.also {
            it.onVector = { angle, mag -> Prefs.setJoystickVec(angle, mag) }
        }

        restoreMarkers()
        restoreDrawnLine()
        // 首帧先按默认中心渲染，定位回调后再移动（避免白屏等待）
        locateToMe(false)
        maybeShowFirstTip()
        return root
    }

    /** 首次进入地图页给一句操作引导 */
    private fun maybeShowFirstTip() {
        val ctx = context ?: return
        val sp = ctx.getSharedPreferences("awei_spoofer", android.content.Context.MODE_PRIVATE)
        if (!sp.getBoolean("map_tip_shown", false)) {
            sp.edit().putBoolean("map_tip_shown", true).apply()
            android.widget.Toast.makeText(
                ctx, "长按地图选位置 · ⌖ 回到我 · ✏️ 画路线 · 🗑 清除", android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    /** 定位到当前位置：只用真实位置（过滤模拟注入），避免画线/选点时跳到模拟点 */
    private fun locateToMe(animate: Boolean) {
        val ctx = context ?: return
        if (!MockLocationEngine.hasLocationPermission(ctx)) return
        val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
        var best: android.location.Location? = null
        try {
            for (p in arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                val loc = try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null } ?: continue
                if (!isRealFix(loc)) continue
                if (best == null || loc.time > best!!.time) best = loc
            }
        } catch (e: Exception) {
        }
        val gotReal = best != null
        best?.let { moveTo(it.latitude, it.longitude, animate) }
        // 补一次单次定位（缓存可能过期）；GPS 不可用时回退网络定位
        try {
            removeSingleUpdateListener()
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val exec = ContextCompat.getMainExecutor(ctx)
                val consume: (android.location.Location?) -> Unit = { loc ->
                    if (loc != null && isRealFix(loc)) moveTo(loc.latitude, loc.longitude, true)
                }
                lm.getCurrentLocation(LocationManager.GPS_PROVIDER, null, exec, consume)
                lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, exec, consume)
            } else {
                @Suppress("DEPRECATION")
                val l = object : android.location.LocationListener {
                    override fun onLocationChanged(loc: android.location.Location) {
                        if (isRealFix(loc)) moveTo(loc.latitude, loc.longitude, true)
                    }
                    override fun onProviderDisabled(provider: String) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                }
                singleUpdateListener = l
                lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, l, null)
            }
        } catch (e: Exception) {
        }
        if (!gotReal) {
            android.widget.Toast.makeText(
                ctx, "暂无真实定位（GPS 未就绪或模拟正在运行）；可双指缩放后拖到目标区域",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    /** 位置是否来自真实定位（非模拟注入） */
    private fun isRealFix(loc: android.location.Location): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) !loc.isMock
        else @Suppress("DEPRECATION") !loc.isFromMockProvider

    private fun moveTo(lat: Double, lng: Double, animate: Boolean) {
        val p = GeoPoint(lat, lng)
        if (animate) map.controller.animateTo(p) else map.controller.setCenter(p)
    }

    // ---- 地名搜索 ----

    // 防抖：连续搜索时旧请求的结果作废
    private var searchSeq = 0

    private fun doSearch(raw: String) {
        val ctx = context ?: return
        val q = raw.trim()
        if (q.isEmpty()) {
            android.widget.Toast.makeText(ctx, "先输入要搜的地名或地址", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        hideKeyboard()

        // 直接输坐标（如 39.9042,116.4074）：不联网，按 WGS-84 解释（高德源下自动转视觉坐标）。
        // 两段都必须带小数点，避免"3栋,5号"这类门牌被误判成坐标
        val m = Regex("^(-?\\d{1,2}\\.\\d+)\\s*[,，]\\s*(-?\\d{1,3}\\.\\d+)$").find(q)
        if (m != null) {
            val la = m.groupValues[1].toDouble()
            val ln = m.groupValues[2].toDouble()
            if (Math.abs(la) <= 90.0 && Math.abs(ln) <= 180.0) {
                pickVisual(la, ln, "坐标 $la,$ln")
                return
            }
        }

        val seq = ++searchSeq
        setSearchBusy(true)
        renderResultRows(listOf(null), q) // 占位"搜索中"行
        Thread {
            val hits = try {
                PlaceSearcher.search(q)
            } catch (t: Throwable) {
                emptyList()
            }
            activity?.runOnUiThread {
                if (!isAdded || seq != searchSeq) return@runOnUiThread
                setSearchBusy(false)
                if (hits.isEmpty()) {
                    renderResultRows(emptyList(), q)
                } else {
                    renderResultRows(hits, q)
                }
            }
        }.start()
    }

    private fun setSearchBusy(busy: Boolean) {
        view?.findViewById<View>(R.id.btn_search)?.isEnabled = !busy
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** hits 为空列表时显示"无结果"，含单个 null 时显示"搜索中"占位 */
    private fun renderResultRows(hits: List<com.awei.spoofer.track.PlaceSearcher.Hit?>, query: String) {
        val ctx = context ?: return
        val panel = view?.findViewById<View>(R.id.sv_results) ?: return
        val list = view?.findViewById<android.widget.LinearLayout>(R.id.ll_results) ?: return
        list.removeAllViews()
        if (hits.isEmpty()) {
            val tv = TextView(ctx)
            tv.text = "没找到「$query」，试试前面加上城市名（如：深圳市 xx 路）"
            tv.textSize = 14f
            tv.setPadding(dp(14), dp(14), dp(14), dp(14))
            list.addView(tv)
        }
        for ((idx, hit) in hits.withIndex()) {
            if (idx > 0) {
                val line = View(ctx)
                line.layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
                )
                // 暗色搜索面板上的分隔线：白色低透明（黑色叠加在暗底上不可见）
                line.setBackgroundColor(0x24FFFFFF)
                list.addView(line)
            }
            if (hit == null) {
                val tv = TextView(ctx)
                tv.text = "搜索中…"
                tv.textSize = 14f
                tv.setPadding(dp(14), dp(14), dp(14), dp(14))
                list.addView(tv)
                continue
            }
            val h = hit
            val tv = TextView(ctx)
            val secColor = androidx.core.content.ContextCompat.getColor(ctx, com.awei.spoofer.R.color.text_secondary)
            // 第二行详情置灰，一眼区分名称与地址
            val full = "${h.name}\n${h.detail}"
            val ss = android.text.SpannableString(full)
            ss.setSpan(
                android.text.style.ForegroundColorSpan(secColor),
                h.name.length + 1, full.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            tv.text = ss
            tv.textSize = 15f
            tv.setPadding(dp(14), dp(14), dp(14), dp(14))
            tv.setBackgroundResource(R.drawable.bg_selectable)
            tv.setOnClickListener {
                hideResults()
                pickVisual(h.lat, h.lng, h.name)
            }
            list.addView(tv)
            if (idx >= 5) break // 最多展示 6 条，避免小屏溢出
        }
        if (hits.isNotEmpty() && hits[0] != null) {
            val foot = TextView(ctx)
            foot.text = "收起"
            foot.textSize = 13f
            foot.gravity = android.view.Gravity.CENTER
            foot.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, com.awei.spoofer.R.color.text_secondary))
            foot.setPadding(0, dp(12), 0, dp(12))
            foot.setBackgroundResource(R.drawable.bg_selectable)
            foot.setOnClickListener { hideResults() }
            list.addView(foot)
        }
        panel.visibility = View.VISIBLE
    }

    private fun hideResults() {
        view?.findViewById<View>(R.id.sv_results)?.visibility = View.GONE
    }

    private fun hideKeyboard() {
        val v = view ?: return
        val imm = context?.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager ?: return
        imm.hideSoftInputFromWindow(v.windowToken, 0)
    }

    /**
     * 选中搜索结果：WGS-84 → 当前地图源视觉坐标（高德=GCJ-02）→ 飞过去 + 按当前
     * 模式落点（与长按选点同一套逻辑：定位模式设目标位置，路线模式设起点/终点）。
     */
    private fun pickVisual(lat: Double, lng: Double, title: String) {
        val ctx = context ?: return
        val visual = if (Prefs.getMapSource(ctx) == MapSources.GAODE) {
            com.awei.spoofer.track.CoordUtil.wgsToGcj(lat, lng)
        } else {
            Pair(lat, lng)
        }
        if (map.zoomLevelDouble < 14.0) map.controller.setZoom(15.0)
        moveTo(visual.first, visual.second, true)
        onMapLongPress(visual.first, visual.second)
        android.widget.Toast.makeText(ctx, "已定位：$title（长按可微调）", android.widget.Toast.LENGTH_SHORT).show()
    }

    // ---- 手绘轨迹 ----

    private fun toggleDraw() {
        drawing = !drawing
        view?.findViewById<View>(R.id.tv_draw_hint)?.visibility =
            if (drawing) View.VISIBLE else View.GONE
        // 手绘模式地图全屏触摸，搜索条会挡住顶部 → 隐藏，退出恢复
        view?.findViewById<View>(R.id.ll_search)?.visibility =
            if (drawing) View.GONE else View.VISIBLE
        if (drawing) {
            drawNewSession = true // 新会话：首笔开新线（覆盖旧路线），此后松手续画
            hideResults()
        }
        // 手绘模式隐藏摇杆（避免触摸冲突），退出时按开关+运行态恢复
        joyHost?.visibility = if (drawing) View.GONE
            else if (Prefs.getJoystick(requireContext()) && Prefs.isRunning) View.VISIBLE else View.GONE
        val btn = view?.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btn_draw)
        btn?.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (drawing) 0xFFFFC107.toInt() else 0xFFFFFFFF.toInt()
        )
        if (!drawing && drawnPoints.size >= 2) saveDrawnRoute()
    }

    // ---- 手绘笔迹捕获（1.0.11 重写，同步自满血版 1.1.15）----
    // 旧实现是"画圈变尖角 + 跑动跨场跳"两个 bug 的共同根源：
    // ① 不锁笔尖指针——笔尖还在屏时另一指/手掌落屏，若先抬笔尖再抬手掌，
    //    手掌的屏幕位置会被当画点记进路线（event.x 指向剩余指针），尾部凭空
    //    戳出"尖角"（两段线出去又回来）；
    // ② 双指期间 MOVE 整段丢弃 → 弧线中间跨出一段直线弦；
    // ③ 每次按下清空——教程"松手可接着画"从未成立：松手重画只剩后半条线，
    //    首尾分居操场两侧，每圈跑到缺口就跨场跳。
    // 现在：笔尖指针锁（只记起笔那根手指，其余手指按下/移动/抬起全忽略，
    // 手掌压屏既不丢点也不戳点）+ 会话内多笔续画（首笔开新线，此后松手在
    // 30m 内接着画、落笔离上一笔终点更远视为"重新画"自动覆盖）+ 单点笔
    //（点击/轻触）当误触回滚。
    private var drawPointerId = MotionEvent.INVALID_POINTER_ID
    private var drawNewSession = true // 进入手绘模式后还没画首笔（首笔开新线）
    private var strokeBaseSize = -1 // 本笔起笔时的点数（-1=本笔已结束/未开始）
    private var strokeRestart = false // 本笔=重新画（结束时把线截成只剩本笔）

    private fun handleDrawTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawPointerId = event.getPointerId(0)
                strokeBaseSize = drawnPoints.size
                strokeRestart = false
                val geo = geoAt(event.x, event.y) ?: return true
                if (drawNewSession) {
                    drawNewSession = false
                    drawnPoints.clear()
                    drawnPolyline?.setPoints(emptyList())
                    strokeBaseSize = 0
                } else {
                    // 落笔离上一笔终点 >30m：视为重新画，结束时把线截成只剩本笔
                    val last = drawnPoints.lastOrNull()
                    if (last != null && last.distanceToAsDouble(geo) > 30.0) strokeRestart = true
                }
                addDrawPoint(geo)
            }
            MotionEvent.ACTION_MOVE -> {
                // 只记笔尖那根手指；其他手指（手掌/误触）完全忽略，
                // 笔尖被压屏也不丢点（双指期间继续记录本指）
                val idx = event.findPointerIndex(drawPointerId)
                if (idx >= 0) addDrawPoint(geoAt(event.getX(idx), event.getY(idx)) ?: return true)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 抬的是笔尖=本笔结束；抬的是别的手指=忽略，笔尖继续画
                if (event.getPointerId(event.actionIndex) == drawPointerId) endStroke()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endStroke()
        }
        return true // 手绘模式独占触摸（地图不平移不缩放）
    }

    private fun endStroke() {
        if (strokeBaseSize < 0) return // 本笔已结束（笔尖抬起后其他手指的 UP 不重复处理）
        drawPointerId = MotionEvent.INVALID_POINTER_ID
        if (drawnPoints.size - strokeBaseSize < 2) {
            // 误触回滚：一笔只留下不到 2 个采样点（点击/轻触）不算画线
            while (drawnPoints.size > strokeBaseSize) drawnPoints.removeAt(drawnPoints.size - 1)
        } else if (strokeRestart) {
            // 重新画：线截成只剩本笔（覆盖旧路线）
            val stroke = drawnPoints.subList(strokeBaseSize, drawnPoints.size).toList()
            drawnPoints.clear()
            drawnPoints.addAll(stroke)
        }
        strokeBaseSize = -1
        strokeRestart = false
        updateDrawnLine()
    }

    private fun geoAt(x: Float, y: Float): GeoPoint? =
        map.projection.fromPixels(x.toInt(), y.toInt()) as? GeoPoint

    private fun addDrawPoint(geo: GeoPoint) {
        val last = drawnPoints.lastOrNull()
        // 相邻采样 >5m 才记录，防止原地抖动堆积
        if (last != null && last.distanceToAsDouble(geo) < 5.0) return
        drawnPoints.add(geo)
        updateDrawnLine()
    }

    private fun updateDrawnLine() {
        if (drawnPoints.size < 2) return
        val line = drawnPolyline ?: Polyline(map).also {
            it.outlinePaint.color = 0xFF1565C0.toInt()
            it.outlinePaint.strokeWidth = 8f
            map.overlays.add(it)
            drawnPolyline = it
        }
        line.setPoints(drawnPoints)
        map.invalidate()
    }

    private fun saveDrawnRoute() {
        val ctx = context ?: return
        var pts = drawnPoints.map { Pair(it.latitude, it.longitude) }
        // 实时画线续跑 + 路线修正：模拟运行中保存手绘时，
        // 把第一个点强制对齐到当前模拟位置（手指落点偏离当前点几米会造成"突然跳到画线起点"穿帮）
        if (Prefs.isRunning && Prefs.currentLat != 0.0) {
            pts = listOf(Pair(Prefs.currentLat, Prefs.currentLng)) + pts.drop(1)
        }
        Prefs.setDrawnRoute(ctx, pts)
        Prefs.saveUiMode(ctx, MODE_ROUTE)
        // 摇杆切到路线模式时关掉摇杆开关（防切回轨迹页时 joy_host 闪现）
        if (Prefs.getJoystick(ctx)) Prefs.setJoystick(ctx, false)
        // 手绘路线替代起终点规划
        Prefs.clearRoute(ctx)
        Prefs.terminalHold = false
        startMarker?.let { map.overlays.remove(it) }
        endMarker?.let { map.overlays.remove(it) }
        startMarker = null
        endMarker = null
        if (Prefs.isRunning) {
            // 模拟在跑 → 重启引擎带着新线，startAtMs 重置为 now（从当前位置续跑新路线）
            MockLocationService.start(ctx)
            android.widget.Toast.makeText(
                ctx, "手绘路线已保存（${pts.size} 点），从当前位置续跑新路线", android.widget.Toast.LENGTH_LONG
            ).show()
        } else {
            android.widget.Toast.makeText(
                ctx, "手绘路线已保存（${pts.size} 点），已切换为路线模式，去轨迹页点启动", android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun restoreDrawnLine() {
        val ctx = context ?: return
        val pts = Prefs.getDrawnRoute(ctx) ?: return
        drawnPoints.clear()
        pts.forEach { drawnPoints.add(GeoPoint(it.first, it.second)) }
        updateDrawnLine()
    }

    /** 清除前确认，防误触 */
    private var clearDialog: androidx.appcompat.app.AlertDialog? = null

    private fun confirmClear() {
        // 快速连点防重复弹窗
        if (clearDialog?.isShowing == true) return
        clearDialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("清除标记")
            .setMessage("将清除目标位置、起终点和手绘路线，确定吗？")
            .setPositiveButton("清除") { _, _ -> clearAll() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearAll() {
        val ctx = context ?: return
        Prefs.clearRoute(ctx)
        Prefs.clearDrawnRoute(ctx)
        spClearFixed()
        startMarker?.let { map.overlays.remove(it) }
        endMarker?.let { map.overlays.remove(it) }
        fixedMarker?.let { map.overlays.remove(it) }
        drawnPolyline?.let { map.overlays.remove(it) }
        startMarker = null
        endMarker = null
        fixedMarker = null
        drawnPolyline = null
        drawnPoints.clear()
        map.invalidate()
        android.widget.Toast.makeText(ctx, "已清除全部标记和路线", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun spClearFixed() {
        val ctx = context ?: return
        ctx.getSharedPreferences("awei_spoofer", android.content.Context.MODE_PRIVATE)
            .edit().remove("fp").apply()
    }

    // ---- 选点（长按） ----

    private fun onMapLongPress(lat: Double, lng: Double) {
        val ctx = context ?: return
        val mode = if (Prefs.getUiMode(ctx) == 1) MODE_ROUTE else MODE_FIXED
        if (mode == MODE_FIXED) {
            Prefs.setFixedPoint(ctx, lat, lng)
            placeFixedMarker(lat, lng)
        } else {
            // 长按起终点 = 想用规划路线，清除手绘（后设置的优先）
            Prefs.clearDrawnRoute(ctx)
            drawnPolyline?.let { map.overlays.remove(it) }
            drawnPolyline = null
            drawnPoints.clear()
            val start = Prefs.getRouteStart(ctx)
            if (start == null || (Prefs.getRouteEnd(ctx) != null)) {
                Prefs.clearRoute(ctx)
                Prefs.setRoutePoints(ctx, Pair(lat, lng), Pair(0.0, 0.0))
                placeStartMarker(lat, lng)
                clearEndMarker()
            } else {
                Prefs.setRoutePoints(ctx, start, Pair(lat, lng))
                placeEndMarker(lat, lng)
            }
        }
    }

    private fun restoreMarkers() {
        val ctx = context ?: return
        Prefs.getFixedPoint(ctx)?.takeIf { it.first != 0.0 }?.let { placeFixedMarker(it.first, it.second) }
        Prefs.getRouteStart(ctx)?.let { placeStartMarker(it.first, it.second) }
        Prefs.getRouteEnd(ctx)?.let { if (it.first != 0.0) placeEndMarker(it.first, it.second) }
    }

    private fun addMarker(lat: Double, lng: Double, title: String, color: Int): Marker {
        val m = Marker(map)
        m.position = GeoPoint(lat, lng)
        m.title = title
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        m.icon = dotDrawable(color)
        map.overlays.add(m)
        map.invalidate()
        return m
    }

    private fun dotDrawable(color: Int): android.graphics.drawable.Drawable {
        return object : android.graphics.drawable.Drawable() {
            override fun draw(canvas: Canvas) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                val b = bounds
                p.color = color
                canvas.drawCircle(b.centerX().toFloat(), b.centerY().toFloat(), b.width() / 2f, p)
                p.color = Color.WHITE
                canvas.drawCircle(
                    b.centerX().toFloat(), b.centerY().toFloat(), b.width() / 4f, p
                )
            }

            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

            override fun getIntrinsicWidth(): Int = 48
            override fun getIntrinsicHeight(): Int = 48
        }
    }

    private fun placeFixedMarker(lat: Double, lng: Double) {
        fixedMarker?.let { map.overlays.remove(it) }
        fixedMarker = addMarker(lat, lng, "目标位置", 0xFFFF6D00.toInt())
    }

    private fun placeStartMarker(lat: Double, lng: Double) {
        startMarker?.let { map.overlays.remove(it) }
        startMarker = addMarker(lat, lng, "起点", 0xFF2E7D32.toInt())
    }

    private fun placeEndMarker(lat: Double, lng: Double) {
        endMarker?.let { map.overlays.remove(it) }
        endMarker = addMarker(lat, lng, "终点", 0xFFC62828.toInt())
    }

    private fun clearEndMarker() {
        endMarker?.let { map.overlays.remove(it) }
        endMarker = null
    }

    /** 更新当前模拟位置点：模拟运行中显示蓝色点，停止时隐藏 */
    private fun updatePosMarker() {
        if (Prefs.isRunning && Prefs.currentLat != 0.0) {
            if (posMarker == null) {
                posMarker = addMarker(Prefs.currentLat, Prefs.currentLng, "当前模拟位置", 0xFF1976D2.toInt())
            } else {
                posMarker?.position = GeoPoint(Prefs.currentLat, Prefs.currentLng)
                map.invalidate()
            }
        } else {
            posMarker?.let { map.overlays.remove(it) }
            posMarker = null
        }
    }

    override fun onResume() {
        super.onResume()
        // 设置页可能切换了地图源；同源时跳过，避免每次切页都重载瓦片
        context?.let {
            val src = MapSources.forMode(Prefs.getMapSource(it))
            val currentName = map.tileProvider.tileSource?.name()
            if (currentName == null || currentName != src.name()) {
                map.setTileSource(src)
            }
        }
        map.onResume()
        updatePosMarker()
        posRefreshHandler.postDelayed(posRefresh, 1000)
        joyHost?.visibility = if (Prefs.getJoystick(requireContext()) && Prefs.isRunning) View.VISIBLE else View.GONE
    }

    override fun onPause() {
        posRefreshHandler.removeCallbacks(posRefresh)
        // 绘制中途离开（切页/旋转）自动保存，避免丢失
        if (drawing) {
            drawing = false
            view?.findViewById<View>(R.id.tv_draw_hint)?.visibility = View.GONE
            view?.findViewById<View>(R.id.ll_search)?.visibility = View.VISIBLE
            view?.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btn_draw)
                ?.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            if (drawnPoints.size >= 2) saveDrawnRoute()
        }
        removeSingleUpdateListener()
        map.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        map.onDetach()
        super.onDestroyView()
    }

    companion object {
        const val MODE_FIXED = 0
        const val MODE_ROUTE = 1
    }
}
