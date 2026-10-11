package com.awei.spoofer.track

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class GpsJitter(private val seed: Long = System.currentTimeMillis()) {

    private val rnd = Random(seed)

    // 主 AR(1) 中频分量状态（失相关 ~1.45s）：模拟真实 GPS 漂移的相关性
    private var smoothLat = 0.0
    private var smoothLng = 0.0
    private var smoothInit = false

    // 慢速偏置随机游走状态（失相关 ~10s）：真机 GPS 残差自相关是"快+慢"双峰，
    // 单层 AR(1) 只有中频分量，缺 30s-5min 的 receiver bias walk
    private var slowLat = 0.0
    private var slowLng = 0.0
    private var slowInit = false

    // 速度抖动 AR(1) 状态（替代白噪声，相邻采样速度强相关）
    private var smoothSpeed = 0.0
    private var speedInit = false

    // 航向摆动 AR(1) 状态（真人步行航向围绕均值 ±5-15° 随机游走）
    private var smoothBearing = 0.0
    private var bearingInit = false

    // offsetMeters() 会被注入线程（服务 HandlerThread）与 UI 轮询线程并发调用：
    // AR(1) 状态无同步推进会得到不确定的漂移序列（同一秒不同线程不同坐标）
    @Synchronized
    fun nextGaussian(mean: Double = 0.0, sigma: Double = 1.0): Double {
        var u = 0.0
        var v = 0.0
        while (u < 1e-9) u = rnd.nextDouble()
        while (v < 1e-9) v = rnd.nextDouble()
        return mean + sigma * sqrt(-2.0 * kotlin.math.ln(u)) * cos(2.0 * PI * v)
    }

    /**
     * 位置抖动（米）：主 AR(1) 中频 + 慢速偏置游走双分量叠加。
     * σ 速度关联：静止 0.6m（信号佳）、步行 1.3m、跑步 2-3m、快跑 3-4m
     * （真机 GPS 抖动幅度跟移动速度强相关——多路径反射 + 天线方向变化）；
     * accuracy 小幅调制（±20%）：信号好抖动小、信号差抖动大。
     */
    @Synchronized
    fun offsetMeters(baseAccuracyM: Double, latDeg: Double, speedMps: Double = 0.0): Pair<Double, Double> {
        val speedSigma = (0.6 + 0.5 * speedMps).coerceIn(0.5, 4.5)
        val accMod = (baseAccuracyM / 10.0).coerceIn(0.8, 1.2)
        val sigma = speedSigma * accMod
        // 主 AR(1) 中频分量
        val rawLat = nextGaussian(0.0, sigma)
        val rawLng = nextGaussian(0.0, sigma)
        if (!smoothInit) {
            smoothInit = true
            smoothLat = rawLat
            smoothLng = rawLng
        } else {
            // 一阶惯性：70% 沿用上一时刻误差 + 30% 新噪声
            smoothLat = 0.7 * smoothLat + 0.3 * rawLat
            smoothLng = 0.7 * smoothLng + 0.3 * rawLng
        }
        // 慢速偏置游走（α=0.95、σ=0.3m，独立状态，失相关 ~10s）
        val slowRawLat = nextGaussian(0.0, 0.3)
        val slowRawLng = nextGaussian(0.0, 0.3)
        if (!slowInit) {
            slowInit = true
            slowLat = slowRawLat
            slowLng = slowRawLng
        } else {
            slowLat = 0.95 * slowLat + 0.05 * slowRawLat
            slowLng = 0.95 * slowLng + 0.05 * slowRawLng
        }
        val totalLat = smoothLat + slowLat
        val totalLng = smoothLng + slowLng
        val dLat = totalLat / 111_320.0
        // 经度换算用 cos(纬度)：旧固定 0.7 在中国主力纬度区把经向漂移系统性放大
        // 15-30%（抖动成经向偏宽的椭圆，非真实 GPS 的各向近似同性）；极地除零防御
        val dLng = totalLng / (111_320.0 * cos(Math.toRadians(latDeg)).coerceAtLeast(0.2))
        return Pair(dLat, dLng)
    }

    /** 速度抖动：AR(1)（α=0.5、σ=4%），相邻采样速度强相关，替代白噪声 */
    @Synchronized
    fun jitterSpeed(speedMps: Double): Double {
        val raw = nextGaussian(0.0, 0.04)
        if (!speedInit) {
            speedInit = true
            smoothSpeed = raw
        } else {
            smoothSpeed = 0.5 * smoothSpeed + 0.5 * raw
        }
        return (speedMps * (1.0 + smoothSpeed)).coerceAtLeast(0.0)
    }

    /**
     * 航向自然摆动（度）：AR(1) α=0.85，σ = (3 + 2·speed)°（静止收敛到 0、
     * 步行 ±2-3°、跑步 ±4-7°）。真人步行航向围绕路径均值 ±5-15° 随机游走，
     * 零方差 + 段间阶梯跳变是纯几何合成的强特征。静止时返回原 bearing 不加摆动。
     */
    @Synchronized
    fun bearingJitter(bearing: Float, speedMps: Double): Float {
        if (speedMps < 0.5) {
            // 静止时航向稳定，重置状态（下次运动时重新初始化）
            bearingInit = false
            smoothBearing = 0.0
            return bearing
        }
        val sigmaDeg = (3.0 + 2.0 * speedMps).coerceAtMost(25.0)
        val raw = nextGaussian(0.0, sigmaDeg)
        if (!bearingInit) {
            bearingInit = true
            smoothBearing = raw
        } else {
            smoothBearing = 0.85 * smoothBearing + 0.15 * raw
        }
        var result = (bearing.toDouble() + smoothBearing) % 360.0
        if (result < 0) result += 360.0
        return result.toFloat()
    }

    @Synchronized
    fun wanderAccuracy(base: Double = 5.0, tSec: Double): Double {
        // 精度圈收紧（3-9m）：免 Root 下网络定位（真实 WiFi 反查，精度 10-30m）
        // 会与 mock GPS 打架——GPS 精度持续优于网络定位才能在融合中稳定胜出，
        // 之前的 2-18m 大波动会让 SDK 在两源间摇摆导致蓝点跳
        val wave = 1.8 * sin(tSec / 17.0 * 2.0 * PI) + 1.0 * sin(tSec / 3.3 * 2.0 * PI)
        return (base + wave + nextGaussian(0.0, 0.4)).coerceIn(3.0, 9.0)
    }
}
