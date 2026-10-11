package com.awei.spoofer.xposed

import com.awei.spoofer.data.HookConfigStore
import com.awei.spoofer.track.GpsJitter
import com.awei.spoofer.track.TrackMath

/**
 * Hook 进程内状态：配置加载 + 当前伪造位置计算。
 * 线程安全：apply 在后台线程调用，current 在任意线程调用。
 */
object HookState {

    @Volatile
    var active: Boolean = false
        private set

    /**
     * 伪装是否真正可交付（enabled 且当前能算出伪造位置）——全部叙事类 hook 的统一门禁。
     * 判"是否伪装"一律用本函数：裸 enabled 标志会在路线缺失等场景形成
     * "真实 GPS + 自洽假环境"的半伪装矛盾态（交叉验证即穿帮）。
     * current() 有秒级缓存，高频 hook 调用开销可忽略。
     */
    fun deliverable(): Boolean = try {
        current() != null
    } catch (t: Throwable) {
        false
    }

    @Volatile
    private var cfg: HookConfigStore.HookCfg? = null

    @Volatile
    private var route: List<TrackMath.Pt> = emptyList()

    // Hook 端漂移模拟：seed 随机——此前固定 42L，每个会话/每个进程的漂移序列完全相同，
    // 风控做跨会话统计能看出"噪声轨迹一模一样"；同秒位置一致性由秒级缓存保证，不依赖 seed
    private var jitter = GpsJitter(0L)

    fun apply(json: String?) {
        val c = HookConfigStore.parse(json)
        synchronized(this) {
            // 周期刷新读到完全相同的配置时跳过：否则 AR(1) 漂移随每次刷新重置，
            // 伪造位置在精度圈内跳一下（真机 GPS 不会这样跳），缓存也随之失效
            if (c == cfg) return
            cfg = c
            route = if (c?.mode == 1 && c.route.size >= 2) TrackMath.densify(c.route) else emptyList()
            active = c?.enabled == true
            // 1.1.14 纯函数漂移：seed=startAt（与主 App TrackEngine 同源同序列——
            // 旧随机种子两进程各一条 AR(1) 序列 = 两 App 显示点错位的根因）
            jitter = GpsJitter(c?.startAt ?: System.currentTimeMillis())
            // 配置变更后位置缓存失效
            cacheElapsed = Double.NaN
            cachePos = null
        }
    }

    data class FakePos(
        val lat: Double,
        val lng: Double,
        val bearing: Double,
        val speedMps: Double,
        val altitude: Double,
    )

    // 位置计算缓存：getLatitude 等被目标 App 高频调用（每帧多次），
    // dist 在同一秒内不变，缓存避免每帧 O(n) 插值
    @Volatile
    private var cacheElapsed: Double = Double.NaN

    @Volatile
    private var cachePos: FakePos? = null

    // 整体同步：多个线程同时进缓存未命中分支会各自推进 AR(1) 漂移状态，
    // 同一秒内不同线程拿到不同坐标（自相矛盾）。锁开销远小于插值计算
    @Synchronized
    fun current(): FakePos? {
        val c = cfg ?: return null
        if (!active) return null
        // 1.1.16 连续 elapsed（毫秒精度，缓存键=毫秒值）：此前 toLong() 整秒截断——
        // hook 侧位置/速度 1s 阶梯、与 TrackEngine 蓝点最多差 1 秒行程（慢跑 ~3m），
        // 违背"任意进程任意时刻结果一致"。现与 TrackEngine.emit 同为"当前毫秒的纯函数"：
        // 同一毫秒任意进程读数一致，缓存只省同一毫秒内的重复插值
        // 有效行进时长（1.1.14 hold 语义）：暂停/待命时冻结——与 TrackEngine.emit 同一公式
        val elapsedMs = com.awei.spoofer.track.TrackMath.elapsedMs(
            System.currentTimeMillis(), c.startAt, c.holdAtMs, c.holdAccumMs
        ).coerceAtLeast(0.0)
        // 波形相位源 = startAt/1000（与引擎1 共享的时间锚）：不同会话速度/海拔曲线不重样
        val phase = c.startAt / 1000.0
        val tSec = elapsedMs / 1000.0
        val altitude = TrackMath.altitudeAt(tSec, phase)
        if (c.mode == 1) {
            // 路线模式但路线缺失（解析失败/点数不足）：不伪装，
            // 避免 fallback 到 lat/lng=0 的 (0,0) 海上坐标
            if (route.isEmpty()) return null
            if (elapsedMs == cacheElapsed && cachePos != null) return cachePos
            val baseMps = c.speedKmh / 3.6
            val dist = TrackMath.distanceTraveled(baseMps, c.variable, tSec, phase)
            val pos: FakePos
            val total = route.last().cumDistM
            // 1.1.16 线形单向（单向打卡场景）：首尾差距极大的"一条线"不回卷，到终点
            // 原地保持；判定压过 loop 开关（TrackMath.isLinearRoute 纯函数，与
            // TrackEngine.emit 同源同判定——两 App 看到的都是"到头停住"）
            val effectiveLoop = c.loop && !TrackMath.isLinearRoute(route)
            if (!effectiveLoop && dist >= total) {
                // 轨迹完成：保持终点（静态），不停止 hook
                val p = TrackMath.positionAt(route, total, loop = false) ?: return null
                val (dLat, dLng) = jitter.offsetMeters(6.0, p.first)
                pos = toWgs(c, p.first + dLat, p.second + dLng, p.third, 0.0, altitude)
            } else {
                // 1.1.15：dist 原样交给 positionAt，调用方禁止预回卷——positionAt
                // 内部按 totalClosed（含"末点→首点"闭合段）回卷；此前 `dist % total`
                // 预卷绕过闭合段，每圈在路线尾点瞬移回首点、闭合那节直接空掉
                //（用户实测"跑动中出现断点/一节空掉/瞬移到下个点"的根因；
                // 本 App 状态页蓝点走 TrackEngine 不预卷路径，两 App 一个走缝
                // 一个瞬移还互相打架）。1.1.14 修 positionAt 时漏掉的唯一调用点。
                val p = TrackMath.positionAt(route, dist, loop = effectiveLoop) ?: return null
                val (dLat, dLng) = jitter.offsetMeters(6.0, p.first)
                pos = toWgs(
                    c, p.first + dLat, p.second + dLng, p.third,
                    jitter.jitterSpeed(TrackMath.speedAt(baseMps, c.variable, tSec, phase)),
                    altitude
                )
            }
            cacheElapsed = elapsedMs
            cachePos = pos
            return pos
        } else {
            if (cachePos != null && cacheElapsed == elapsedMs) return cachePos
            val (dLat, dLng) = jitter.offsetMeters(6.0, c.lat)
            val pos = toWgs(c, c.lat + dLat, c.lng + dLng, 0.0, 0.0, altitude)
            cacheElapsed = elapsedMs
            cachePos = pos
            return pos
        }
    }

    /**
     * 伪随机种子时间锚：随会话不同（startAt 不同）、随整秒变化（elapsedSec 不同）、
     * 同秒内一致——供 GnssStatus 等需要"确定性但去指纹"的伪造共享同一时间基准，
     * 避免各 getter 各自 Math.random() 导致同一次读数内卫星字段不自洽。未激活或
     * 无配置时返 0（调用方应已被 deliverable() 门禁挡住）。
     */
    fun fakerSeed(): Long {
        val c = cfg ?: return 0L
        if (!active) return 0L
        val elapsedSec = (com.awei.spoofer.track.TrackMath.elapsedMs(
            System.currentTimeMillis(), c.startAt, c.holdAtMs, c.holdAccumMs
        ) / 1000.0).toLong().coerceAtLeast(0L)
        return c.startAt.toLong() xor (elapsedSec * 0x9E3779B1L)
    }

    /**
     * 会话时间锚（startAt）：供"会话内必须恒定、跨会话/跨用户必须不同"的派生值使用
     * （四源偏移、假 AP/BSSID/MAC、基站 LAC/CID 等结构性身份）。与 fakerSeed() 的区别：
     * fakerSeed 每秒变（卫星等数据要逐秒抖动去指纹），sessionSeed 整场会话恒定
     * （结构身份逐秒变反而是指纹）。未配置时返 0。
     */
    fun sessionSeed(): Long = cfg?.startAt ?: 0L

    /** 配置里的坐标是"视觉坐标"（高德源=GCJ-02），输出给 App 前统一反算 WGS-84 */
    private fun toWgs(c: HookConfigStore.HookCfg, lat: Double, lng: Double,
                      bearing: Double, speedMps: Double, altitude: Double): FakePos {
        if (!c.gcj) return FakePos(lat, lng, bearing, speedMps, altitude)
        val (wLat, wLng) = try {
            com.awei.spoofer.track.CoordUtil.gcjToWgs(lat, lng)
        } catch (t: Throwable) {
            Pair(lat, lng)
        }
        return FakePos(wLat, wLng, bearing, speedMps, altitude)
    }
}
