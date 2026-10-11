package com.awei.spoofer.track

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 共享插值核心：免Root版 App（引擎1）与 Xposed Hook 进程（引擎3）使用同一公式，
 * 以 startAt 时间锚保证两端推进位置一致。
 *
 * 人类移动真实性（v0.9.0 打磨）：
 * - 起步斜坡：真人从静止到目标配速需要数秒，瞬时满速是 mock 特征。RAMP_S 秒线性加速；
 * - 多谐波变速：真实配速是多个节奏（体能周期/步频微波动/分钟级漂移）的叠加而非
 *   单一正弦，variable=true 时按 (幅度, 周期s) 谐波组叠加，总幅度 ≤±30%；
 * - 跨会话相位：波形时间 = elapsed + phaseSec（调用方传 startAt/1000）——不同会话
 *   的速度曲线不重样（防跨会话统计指纹），且 phase 来自两引擎共享的 startAt 锚，
 *   零新增配置字段仍满足"任意进程任意时刻结果一致"硬约束；
 * - 海拔慢波动：恒定海拔对有海拔库的 App 立刻穿帮，altitudeAt 以分钟级正弦 ±5.5m 波动；
 * - 全部为 elapsedSec 的解析纯函数（无进程内状态、无随机数），解析积分式已数值验证。
 */
object TrackMath {

    data class Pt(val lat: Double, val lng: Double, val cumDistM: Double, val bearing: Double)

    /** 起步加速时长（秒） */
    private const val RAMP_S = 8.0

    /** 变速谐波（幅度, 周期秒）：主节奏 45s + 步频微波动 13s/5.3s + 分钟级体能漂移 240s */
    private val HARMONICS = arrayOf(
        Pair(0.14, 45.0),
        Pair(0.07, 13.0),
        Pair(0.04, 5.3),
        Pair(0.06, 240.0),
    )

    /** 海拔基准（米）与波动幅度 */
    private const val ALT_BASE = 30.0

    fun densify(raw: List<Pair<Double, Double>>): List<Pt> {
        val out = ArrayList<Pt>(raw.size)
        var cum = 0.0
        var prev: Pair<Double, Double>? = null
        for (p in raw) {
            val pp = prev
            if (pp != null) cum += RoutePlanner.haversineM(pp.first, pp.second, p.first, p.second)
            val brg = pp?.let { RoutePlanner.bearingDeg(it.first, it.second, p.first, p.second).toDouble() } ?: 0.0
            out.add(Pt(p.first, p.second, cum, brg))
            prev = p
        }
        return out
    }

    /** t 时刻瞬时速度系数（不含 base）：ramp(t)·(1+Σa·sin(w(t+φ)))；variable=false 无谐波 */
    private fun speedFactor(elapsedSec: Double, phaseSec: Double, variable: Boolean): Double {
        val ramp = (elapsedSec / RAMP_S).coerceAtMost(1.0)
        if (ramp <= 0.0) return 0.0
        var f = 1.0
        if (variable) {
            for ((a, period) in HARMONICS) {
                val w = 2.0 * PI / period
                f += a * sin(w * (elapsedSec + phaseSec))
            }
        }
        return ramp * f
    }

    /**
     * elapsedSec 时刻的累计里程（米）。解析积分，不依赖逐步累加，
     * 保证任意进程任意时刻计算结果一致。
     * speed(t) = base · min(1, t/T) · (1 + Σ a_i·sin(w_i·(t+φ)))（variable=false 时 Σ 项为 0）
     * phaseSec = startAt/1000：波形相位随会话变化（防跨会话指纹），两引擎同源。
     */
    fun distanceTraveled(
        baseMps: Double, variable: Boolean, elapsedSec: Double, phaseSec: Double = 0.0,
    ): Double {
        if (elapsedSec <= 0.0) return 0.0
        val t = elapsedSec
        // 斜坡段 ∫₀ᵗ (τ/T)·(1+Σa·sin(w(τ+φ))) dτ
        var acc = t * t / (2.0 * RAMP_S)
        if (variable) {
            for ((a, period) in HARMONICS) {
                val w = 2.0 * PI / period
                // ∫₀ᵗ τ·sin(w(τ+φ))dτ = −t·cos(w(t+φ))/w + (sin(w(t+φ))−sin(wφ))/w²
                acc += a / RAMP_S * (-t * cos(w * (t + phaseSec)) / w +
                    (sin(w * (t + phaseSec)) - sin(w * phaseSec)) / (w * w))
            }
        }
        var d = baseMps * acc
        if (t > RAMP_S) {
            // 满速段：dist(T) + base·(t−T) + base·Σ a·(cos(w(T+φ))−cos(w(t+φ)))/w
            var accT = RAMP_S / 2.0
            if (variable) {
                for ((a, period) in HARMONICS) {
                    val w = 2.0 * PI / period
                    accT += a / RAMP_S * (-RAMP_S * cos(w * (RAMP_S + phaseSec)) / w +
                        (sin(w * (RAMP_S + phaseSec)) - sin(w * phaseSec)) / (w * w))
                }
            }
            var tail = t - RAMP_S
            if (variable) {
                for ((a, period) in HARMONICS) {
                    val w = 2.0 * PI / period
                    tail += a * (cos(w * (RAMP_S + phaseSec)) - cos(w * (t + phaseSec))) / w
                }
            }
            d = baseMps * accT + baseMps * tail
        }
        return d
    }

    /** 指定里程处的位置（lat, lng, bearing）。loop=true 时自动回卷（含闭合段，1.1.14）。 */
    fun positionAt(route: List<Pt>, distM: Double, loop: Boolean): Triple<Double, Double, Double>? {
        if (route.isEmpty()) return null
        if (route.size == 1) return Triple(route[0].lat, route[0].lng, route[0].bearing)
        val total = route.last().cumDistM
        if (total <= 0.0) return Triple(route[0].lat, route[0].lng, route[0].bearing)
        var d = distM
        if (loop) {
            // 回卷含"末点→首点"闭合段（1.1.14）：手绘线首尾不闭合时，旧实现 d%=total
            // 是瞬移传送——500ms 内跳十几米而 getSpeed() 还报配速，位移/速度矛盾是
            // 假定位的经典检测向量（运动类反作弊判瞬移、进"正在确认定位"拒收）。
            // 现把闭合段计入总里程、按真实配速走过去，接缝零跳变
            val gap = RoutePlanner.haversineM(
                route.last().lat, route.last().lng, route[0].lat, route[0].lng
            )
            val totalClosed = total + gap
            d %= totalClosed
            if (d > total) {
                val t = ((d - total) / gap.coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
                val lat = route.last().lat + (route[0].lat - route.last().lat) * t
                val lng = route.last().lng + (route[0].lng - route.last().lng) * t
                val brg = if (gap < 1e-6) route.last().bearing
                else RoutePlanner.bearingDeg(
                    route.last().lat, route.last().lng, route[0].lat, route[0].lng
                ).toDouble()
                return Triple(lat, lng, brg)
            }
        } else d = d.coerceAtMost(total)
        var i = 0
        while (i < route.size - 1 && route[i + 1].cumDistM < d) i++
        // a=线段下端顶点，b=上端（i+1）：循环退出时 i 必 < size-1（d≤total 保证
        // route[size-1].cumDistM ≥ d 挡住推进），coerce 只是浮点防御。
        // 此前误写 route[i.coerceAtMost(...)] → b 恒等于 a，插值整段死代码，
        // 轨迹退化成"按路网顶点阶梯跳变"（速度平滑但位移跳变 = 检测向量）
        val a = route[i]
        val b = route[(i + 1).coerceAtMost(route.size - 1)]
        val seg = (b.cumDistM - a.cumDistM).coerceAtLeast(1e-6)
        val t = ((d - a.cumDistM) / seg).coerceIn(0.0, 1.0)
        val lat = a.lat + (b.lat - a.lat) * t
        val lng = a.lng + (b.lng - a.lng) * t
        // 航向取"当前行进段"的方位角（旧实现取进入 a 的上一段航向，拐弯处滞后一段，
        // 且 loop 回卷进首段时会恒报 0° 正北）；零长段（回卷接缝）沿用存储航向
        val brg = if (b.cumDistM - a.cumDistM < 1e-6) a.bearing
        else RoutePlanner.bearingDeg(a.lat, a.lng, b.lat, b.lng).toDouble()
        return Triple(lat, lng, brg)
    }

    /**
     * 线形路线判定（1.1.16 单向打卡场景）：首尾不闭合且差距极大 = "一条线"——
     * 单向跑完到终点原地保持，**不回卷**（回卷要么瞬移回首点、要么沿闭合段穿场
     * 走回去，跑完打卡时位置早已离开终点区）。gap=末点→首点距离（即 positionAt
     * 的闭合段）；判线条件 gap > 200m 且 gap > 总里程 20%（防画圈的小闭合误差误判）。
     * 画圈/一键环线 gap≈0 判非线形照常循环；起终点规划（OSRM）路线天然判线形。
     * 纯函数：两引擎同路线必同判定（"任意进程任意时刻结果一致"），判定压过 loop 开关。
     */
    fun isLinearRoute(route: List<Pt>): Boolean {
        if (route.size < 2) return false
        val total = route.last().cumDistM
        if (total <= 0.0) return false
        val gap = RoutePlanner.haversineM(
            route.last().lat, route.last().lng, route[0].lat, route[0].lng
        )
        return gap > 200.0 && gap > total * 0.2
    }

    /** 指定时刻速度（m/s），用于展示/注入。起步斜坡同样生效（t=0 时为 0）。 */
    fun speedAt(baseMps: Double, variable: Boolean, elapsedSec: Double, phaseSec: Double = 0.0): Double =
        if (elapsedSec <= 0.0) 0.0 else baseMps * speedFactor(elapsedSec, phaseSec, variable)

    /**
     * 有效行进时长（ms，1.1.14 hold 语义）：holdAtMs>0 表示暂停/待命中——时长冻结在
     * (holdAtMs-startAt-accum)；否则 now-startAt-accum。**两引擎必须用同一公式**
     *（TrackEngine.emit 与 HookState.current），否则暂停时一个在走一个站着。
     */
    fun elapsedMs(nowMs: Long, startAtMs: Long, holdAtMs: Long, holdAccumMs: Long): Double {
        val base = if (holdAtMs > 0) holdAtMs else nowMs
        return (base - startAtMs - holdAccumMs).toDouble().coerceAtLeast(0.0)
    }

    /**
     * 海拔慢波动（米）：分钟级正弦叠加，±5.5m。纯函数（相位同 speed 走 startAt/1000），
     * 引擎1（免Root版）注入与引擎3 getter 用同一函数，替代此前"恒 30m/恒 25m"的不一致。
     */
    fun altitudeAt(elapsedSec: Double, phaseSec: Double = 0.0): Double {
        val t = elapsedSec + phaseSec
        return ALT_BASE + 4.0 * sin(2.0 * PI * t / 600.0) + 1.5 * sin(2.0 * PI * t / 47.0)
    }
}
