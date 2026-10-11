package com.awei.spoofer.xposed.hooks

import com.awei.spoofer.xposed.HookState
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 国产定位 SDK Hook。
 * 高德 AMapLocation 继承 android.location.Location，已被 LocationHook getter 覆盖；
 * 百度 BDLocation 是独立类，需要单独 hook。
 * 腾讯 SDK 由 TencentLocationHook 覆盖（1.1.9 起），本模块只管百度 BDLocation。
 */
object CnSdkHook {

    // BDLocation 坐标类型由 LocationClientOption.setCoorType 决定（百度默认 gcj02）。
    // 实例上读不到 getCoorType() 时按最近一次 setCoorType 请求值处理
    @Volatile
    private var requestedCoorType: String = "gcj02"

    fun hook(classLoader: ClassLoader) {
        hookBaidu(classLoader)
    }

    private fun hookBaidu(cl: ClassLoader) {
        val bdClass = try {
            cl.loadClass("com.baidu.location.BDLocation")
        } catch (t: Throwable) {
            return // 无百度 SDK
        }
        // 捕获 App 请求的坐标类型（bd09ll/gcj02/wgs84）：多客户端不同设置时取最近一次，
        // 现实中一 App 一 LocationClient，够用
        try {
            val optClass = cl.loadClass("com.baidu.location.LocationClientOption")
            XposedBridge.hookAllMethods(optClass, "setCoorType", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    (param.args?.firstOrNull() as? String)?.let {
                        requestedCoorType = it.trim().lowercase(java.util.Locale.ROOT)
                    }
                }
            })
        } catch (t: Throwable) {
        }
        val hookGet = { name: String, transform: (Any?, HookState.FakePos) -> Any? ->
            try {
                XposedHelpers.findAndHookMethod(
                    bdClass, name, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            HookState.current()?.let {
                                param.result = transform(param.thisObject, it)
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
            }
        }
        // lat/lng 必须落在 BDLocation 声明的坐标系里（见 bdCoords）——
        // 一律喂 WGS 会让百度系 App 把 WGS 值当 GCJ/BD09 显示，偏一个火星偏移量
        hookGet("getLatitude") { self, fp -> bdCoords(self, fp).first }
        hookGet("getLongitude") { self, fp -> bdCoords(self, fp).second }
        hookGet("getSpeed") { _, fp -> fp.speedMps.toFloat() }
        hookGet("getDirection") { _, fp -> fp.bearing.toFloat() }
        // 1.1.16：恒 6.0f 是指纹——按会话确定性微抖（同一次读数多次调用结果一致）
        hookGet("getRadius") { _, _ ->
            val sec = ((System.currentTimeMillis() - HookState.sessionSeed()) / 1000L).coerceAtLeast(0L)
            val wave = 1.8 * Math.sin(sec / 17.0 * 2.0 * Math.PI) +
                1.0 * Math.sin(sec / 3.3 * 2.0 * Math.PI)
            (6.0 + wave).coerceIn(3.0, 9.0).toFloat()
        }
        hookGet("getAltitude") { _, fp -> fp.altitude }
        hookGet("getLocType") { _, _ -> 61 } // GPS 类型
        XposedBridge.log("awei: BaiduHook installed (coorType default=$requestedCoorType)")
    }

    /** BDLocation 坐标系分流：fp 是 WGS-84，按实例 getCoorType()/setCoorType 请求值转换 */
    private fun bdCoords(self: Any?, fp: HookState.FakePos): Pair<Double, Double> {
        val ct = try {
            self?.javaClass?.getMethod("getCoorType")?.invoke(self) as? String
        } catch (t: Throwable) {
            null
        }?.trim()?.lowercase(java.util.Locale.ROOT) ?: requestedCoorType
        return when (ct) {
            "wgs84" -> Pair(fp.lat, fp.lng)
            // bd09mc 是 BD-09 的墨卡托投影，lat/lng 仍按 BD-09 经纬度给（罕见请求，尽力而为）
            "bd09ll", "bd09", "bd09mc" -> {
                val (gLat, gLng) = try {
                    com.awei.spoofer.track.CoordUtil.wgsToGcj(fp.lat, fp.lng)
                } catch (t: Throwable) {
                    Pair(fp.lat, fp.lng)
                }
                try {
                    com.awei.spoofer.track.CoordUtil.gcjToBd09(gLat, gLng)
                } catch (t: Throwable) {
                    Pair(gLat, gLng)
                }
            }
            else -> try {
                // gcj02（百度默认）
                com.awei.spoofer.track.CoordUtil.wgsToGcj(fp.lat, fp.lng)
            } catch (t: Throwable) {
                Pair(fp.lat, fp.lng)
            }
        }
    }
}
