package com.awei.remotecam

import java.net.Inet4Address
import java.net.NetworkInterface

/** 枚举本机 LAN IPv4（优先 wlan 接口），无需权限 */
object NetUtil {

    fun lanIps(): List<String> {
        val wlan = ArrayList<String>()
        val others = ArrayList<String>()
        return try {
            val all = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (ni in all) {
                val name = ni.name.lowercase()
                if (!ni.isUp || name.contains("dummy") || name.contains("rmnet")) continue
                for (ia in ni.inetAddresses) {
                    if (ia is Inet4Address && !ia.isLoopbackAddress) {
                        val s = ia.hostAddress ?: continue
                        if (s.startsWith("192.168.") || s.startsWith("10.") || s.startsWith("172.")) {
                            if (name.contains("wlan") || name.contains("wifi")) wlan.add(s) else others.add(s)
                        }
                    }
                }
            }
            wlan + others
        } catch (t: Throwable) {
            emptyList()
        }
    }
}
