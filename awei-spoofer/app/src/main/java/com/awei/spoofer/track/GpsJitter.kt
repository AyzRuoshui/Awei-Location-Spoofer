package com.awei.spoofer.track

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * GPS 漂移模拟（1.1.14 纯函数化）。
 *
 * 旧实现是状态式 AR(1) + 随机种子：主 App（TrackEngine）与 Hook 进程（HookState）
 * 各持一个实例，种子与推进节奏都不同——同一时刻两边给出的"当前位置"天生错位几米
 *（loop 接缝处放大成明显分叉 = 用户实测反馈的"两个 App 定位不一致"）。
 *
 * 现全部为 (seed, 会话整秒 t) 的确定性纯函数：任意进程、任意调用节奏，同一秒内输出
 * 完全一致（与 TrackMath"任意进程任意时刻结果一致"同一硬约束）；相关性观感用低频
 * 正弦叠加 + 哈希微噪声近似 AR(1) 的相邻采样强相关。
 *
 * **seed 必须传会话时间锚 startAt**（两引擎共享）：种子不同 = 序列不同 = 错位复发。
 * offsetMeters 的 baseAccuracyM 两引擎必须传同一值（统一 6.0），否则幅度仍会错位。
 */
class GpsJitter(private val seed: Long = System.currentTimeMillis()) {

    /** 会话整秒（bucket）：同一秒内任意进程任意调用同值 */
    private fun tSec(): Long = (System.currentTimeMillis() - seed) / 1000L

    /** 确定性微噪声 [-1,1)：64 位 hash 置换（splitmix64 尾部），无状态无 Random。
     *  常量写 uL.toLong()：Kotlin 十六进制字面量 > Long.MAX 即使带 L 也编译不过（坑 #52 同族） */
    private fun noise(t: Long, salt: Long): Double {
        var x = seed xor (t * 0x9E3779B97F4A7C15uL.toLong()) xor (salt * 0xC2B2AE3D27D4EB4FuL.toLong())
        x = x xor (x ushr 30); x *= 0xBF58476D1CE4E5B9uL.toLong()
        x = x xor (x ushr 27); x *= 0x94D049BB133111EBuL.toLong()
        x = x xor (x ushr 31)
        return ((x ushr 11).toDouble() / (1L shl 53).toDouble()) * 2.0 - 1.0
    }

    /**
     * 水平漂移偏移（度）。幅度≈旧 AR(1) 稳态 σ（~1.5m @acc=6）。
     * baseAccuracyM 参与幅度缩放——**两引擎调用必须传同一值（统一 6.0）**。
     */
    fun offsetMeters(baseAccuracyM: Double, latDeg: Double): Pair<Double, Double> {
        val sigma = (baseAccuracyM / 2.0).coerceIn(1.0, 6.0)
        val t = tSec()
        val ph = (seed % 86_400).toDouble()
        val ox = sigma * (0.40 * sin(2.0 * PI * (t + ph) / 53.0) +
            0.25 * sin(2.0 * PI * (t + ph) / 17.0) + 0.25 * noise(t, 1))
        val oy = sigma * (0.40 * cos(2.0 * PI * (t + ph) / 47.0) +
            0.25 * sin(2.0 * PI * (t + ph) / 13.0) + 0.25 * noise(t, 2))
        val dLat = ox / 111_320.0
        // 经度换算用 cos(纬度)：极地除零防御（vc36 同款）
        val dLng = oy / (111_320.0 * cos(Math.toRadians(latDeg)).coerceAtLeast(0.2))
        return Pair(dLat, dLng)
    }

    /** 速度微抖 ±4%（确定性；两引擎同输入同输出） */
    fun jitterSpeed(speedMps: Double): Double =
        (speedMps * (1.0 + noise(tSec(), 3) * 0.04)).coerceAtLeast(0.0)

    /**
     * 精度圈 3-9m 正弦波动（免Root版引擎1 注入用；满血版 Hook 侧走 LocationHook.accuracyFor）。
     * tSec 由调用方按会话 elapsed 传入。
     */
    fun wanderAccuracy(base: Double = 5.0, tSec: Double): Double {
        val wave = 1.8 * sin(tSec / 17.0 * 2.0 * PI) + 1.0 * sin(tSec / 3.3 * 2.0 * PI)
        return (base + wave + noise(tSec.toLong(), 4) * 0.4).coerceIn(3.0, 9.0)
    }
}
