package com.awei.spoofer.xposed.hooks

import com.awei.spoofer.xposed.HookState
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 活动识别（ActivityRecognition）数据层 Hook——运动反作弊核心缺口补齐（1.1.3 新增第 12 模块）。
 *
 * Keep / 咕咚 / 悦动圈 / 华为运动健康 / Strava 等运动 App 靠 GMS ActivityRecognitionClient
 * 判"步行/跑步/开车/静止"与 GPS 速度交叉校验。设备静止而伪造位置在"开车 60km/h"时，
 * ActivityRecognition 真值报 STILL → 硬冲突穿帮。SensorHook 对它无效（走 GMS 服务端
 * 算法，不读 SensorManager），故在数据层 getter 兜底：不管 GMS 怎么投递，App 读
 * DetectedActivity.getType() / getConfidence() 时即被改写为与伪造速度一致的活动类型。
 *
 * 策略参考 CnSdkHook：字符串加载 GMS 公开 API 类（类名稳定不混淆），找不到静默跳过
 * （无 GMS / 未引用活动识别 API 的 App 该模块空跑）。与 LocationHook 基类 getter 同理——
 * 不替换 list 本身（构造 DetectedActivity 需反射构造器，不稳），靠遍历读 type 时的
 * getter 覆盖生效；getConfidence 返 100 确保伪装类型为主导，否则 App 会取次高真值。
 */
object ActivityRecognitionHook {

    // GMS DetectedActivity 活动类型常量（公开稳定，与 com.google.android.gms.location.DetectedActivity 一致）
    private const val A_IN_VEHICLE = 0
    private const val A_STILL = 3
    private const val A_WALKING = 6
    private const val A_RUNNING = 7

    fun hook(classLoader: ClassLoader) {
        val daClass = try {
            classLoader.loadClass("com.google.android.gms.location.DetectedActivity")
        } catch (t: Throwable) {
            return // 无 GMS / 该 App 未引用活动识别 API
        }

        // getType() → 按伪造速度映射活动类型（int 返回类型，setResult 直接写 int）
        try {
            XposedBridge.hookAllMethods(daClass, "getType", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!HookState.deliverable()) return
                    val fp = HookState.current() ?: return
                    param.result = activityForSpeed(fp.speedMps)
                }
            })
        } catch (t: Throwable) {
        }

        // getConfidence() → 75-95 高置信区间（1.1.13）：伪装类型须占主导（否则 App 取次高
        // 真值），但真机置信度几乎不会满分——恒 100 本身是人造特征；种子跟 fakerSeed
        // （同秒一致、跨秒微变）
        try {
            XposedBridge.hookAllMethods(daClass, "getConfidence", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!HookState.deliverable()) return
                    val r = java.util.Random(HookState.fakerSeed() xor 0xC0F1DEL)
                    param.result = 75 + r.nextInt(21)
                }
            })
        } catch (t: Throwable) {
        }

        // ActivityTransition：部分运动 App 用过渡事件（进入/离开某活动状态）而非快照。
        // getActivityType() 复用上面的速度映射；getTransitionType() 返 TRANSITION_ENTER(0)
        // ——伪装的活动"正在持续"比"刚离开"更自洽
        val atClass = try {
            classLoader.loadClass("com.google.android.gms.location.ActivityTransition")
        } catch (t: Throwable) {
            null
        }
        if (atClass != null) {
            try {
                XposedBridge.hookAllMethods(atClass, "getActivityType", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!HookState.deliverable()) return
                        val fp = HookState.current() ?: return
                        param.result = activityForSpeed(fp.speedMps)
                    }
                })
            } catch (t: Throwable) {
            }
            try {
                XposedBridge.hookAllMethods(atClass, "getTransitionType", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!HookState.deliverable()) return
                        param.result = 0 // ActivityTransition.TRANSITION_ENTER
                    }
                })
            } catch (t: Throwable) {
            }
        }

        XposedBridge.log("awei: ActivityRecognitionHook installed")
    }

    /**
     * 速度 → 活动类型映射（与 GPS 速度叙事自洽）：
     *  >7 m/s(25km/h) 开车；2.5–7 跑步/骑车；0.7–2.5 步行；<0.7 静止。
     *  不细分 ON_BICYCLE(1)/ON_FOOT(2)：混淆阈值不稳，高速段统一 RUNNING、开车段 IN_VEHICLE，
     *  足够骗过"速度 vs 活动状态"交叉校验；真要区分需结合传感器步频，留作后续增强。
     */
    private fun activityForSpeed(speedMps: Double): Int {
        return when {
            speedMps > 7.0 -> A_IN_VEHICLE
            speedMps > 2.5 -> A_RUNNING
            speedMps > 0.7 -> A_WALKING
            else -> A_STILL
        }
    }
}
