package com.awei.spoofer.track

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GCJ-02（火星坐标）↔ WGS-84（GPS 物理坐标）转换。
 *
 * 高德/腾讯地图及其 SDK 使用 GCJ-02：SDK 拿到 GPS(WGS-84) 位置后会自动加偏显示。
 * 本 App 在高德瓦片上选点存的坐标是"视觉值"（即 GCJ-02 编码），若不转换直接注入，
 * SDK 会把它当 WGS-84 再加一次偏 → 显示位置偏离选点 300-500 米
 *（"既不是实际位置也不是模拟位置"的根因）。
 *
 * 因此约定：内部存储/配置/绘制统一用"视觉坐标"（跟随地图源：高德=GCJ-02，ArcGIS/OSM=WGS-84），
 * 注入前统一反算回 WGS-84。
 */
object CoordUtil {

    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    /** WGS-84 → GCJ-02（正向加偏） */
    fun wgsToGcj(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return Pair(lat, lng)
        val dLat = transformLat(lng - 105.0, lat - 35.0)
        val dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        val offsetLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI)
        val offsetLng = (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * Math.PI)
        return Pair(lat + offsetLat, lng + offsetLng)
    }

    /** GCJ-02 → WGS-84（迭代反算至收敛，残差 <0.1m；上限 10 次防发散） */
    fun gcjToWgs(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return Pair(lat, lng)
        var wLat = lat
        var wLng = lng
        // 1.1.17：固定 3 次迭代会留 1-2m 系统性单向残差（与正向不对称），改为收敛
        // 判定——残差平方和 <1e-12（约 0.1m 量级）即停，通常 3-5 次收敛
        repeat(10) {
            val (gLat, gLng) = wgsToGcj(wLat, wLng)
            val dLat = lat - gLat
            val dLng = lng - gLng
            wLat += dLat
            wLng += dLng
            if (dLat * dLat + dLng * dLng < 1e-12) return Pair(wLat, wLng)
        }
        return Pair(wLat, wLng)
    }

    /**
     * GCJ-02 → BD-09（百度坐标，标准 z-π 摆正变换）。
     * 百度定位 SDK 的 setCoorType("bd09ll") 输出是 BD-09，
     * hook 边界喂值前需从 WGS-84 经 GCJ-02 再转到这里。
     */
    fun gcjToBd09(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return Pair(lat, lng)
        val xPi = Math.PI * 3000.0 / 180.0
        val x = lng - 0.0065
        val y = lat - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * xPi)
        val theta = atan2(y, x) - 0.000003 * cos(x * xPi)
        return Pair(z * sin(theta), z * cos(theta))
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320.0 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }
}
