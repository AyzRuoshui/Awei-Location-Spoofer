package com.awei.spoofer.xposed.hooks

import com.awei.spoofer.xposed.HookState
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * NMEA 0183 报文生成（GGA/RMC），配合 LocationManager.addNmeaListener hook 使用。
 */
object NmeaGenerator {

    fun checksum(msg: String): String {
        var cs = 0
        for (c in msg) cs = cs xor c.code
        return String.format(Locale.US, "%02X", cs and 0xFF)
    }

    private fun latToNmea(lat: Double): String {
        val h = if (lat >= 0) "N" else "S"
        val a = abs(lat)
        val deg = a.toInt()
        val min = (a - deg) * 60.0
        return String.format(Locale.US, "%02d%07.4f,%s", deg, min, h)
    }

    private fun lngToNmea(lng: Double): String {
        val h = if (lng >= 0) "E" else "W"
        val a = abs(lng)
        val deg = a.toInt()
        val min = (a - deg) * 60.0
        return String.format(Locale.US, "%03d%07.4f,%s", deg, min, h)
    }

    private fun utcTime(cal: Calendar): String =
        String.format(
            Locale.US, "%02d%02d%02d.00",
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND)
        )

    private fun utcDate(cal: Calendar): String =
        String.format(
            Locale.US, "%02d%02d%02d",
            cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.YEAR) % 100
        )

    fun gga(fp: HookState.FakePos, cal: Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))): String {
        // 海拔跟随当前伪造值：Location.getAltitude 已是波动值，GGA 报文若恒 30.0
        // 可被检测方交叉验证出矛盾（vc33 波动改造的遗漏点）
        // 卫星数/HDOP 与 GnssFaker 同源（1.1.13）：此前硬编码 "10,0.9" 与 GnssStatus
        // 的 9-11 星不同自洽，报文与状态交叉验证即矛盾
        val sats = DetectionHook.GnssFaker.count()
        val hdop = DetectionHook.GnssFaker.hdop()
        val body = "GPGGA,${utcTime(cal)},${latToNmea(fp.lat)},${lngToNmea(fp.lng)}," +
            "1,${String.format(Locale.US, "%02d", sats)}," +
            "${String.format(Locale.US, "%.1f", hdop)}," +
            "${String.format(Locale.US, "%.1f", fp.altitude)},M,-33.9,M,,"
        return "\$$body*${checksum(body)}"
    }

    fun rmc(fp: HookState.FakePos, cal: Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))): String {
        val speedKnots = (fp.speedMps * 1.94384)
        val cog = ((fp.bearing % 360) + 360) % 360
        val body = "GPRMC,${utcTime(cal)},A,${latToNmea(fp.lat)},${lngToNmea(fp.lng)}," +
            "${String.format(Locale.US, "%.2f", speedKnots)}," +
            "${String.format(Locale.US, "%.1f", cog)},${utcDate(cal)},,,A"
        return "\$$body*${checksum(body)}"
    }
}
