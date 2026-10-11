package com.awei.spoofer.xposed.hooks

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Handler
import android.os.Looper
import com.awei.spoofer.xposed.HookState
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 传感器一致性伪装（手法参考 zhufucdev MotionEmulatorSdk SensorHooker）：
 *
 * 真实设备静止在家而伪造位置在"开车"——加速计/陀螺仪/计步与位置叙事矛盾即穿帮。
 * 做法：registerListener 全重载把 listener 包成动态代理，onSensorChanged 同步替换数据源
 * （跟随真实事件节奏投递伪造值，不改变事件频率，比自建时钟投递更简单可靠）；
 * 伪造值由 HookState.current() 的速度/航向推导：
 * - 加速度计 = 速度微分 + 重力分量 + 步态振荡 + 噪声（1.1.13：匀速步行的步频摆动——
 *   此前纯微分+噪声=静止读数，与计步器/GPS 组合出"摇步器/打车刷步"标准指纹）
 * - 陀螺仪 = 航向角变化率（rad/s）+ 步态摆动 + 噪声
 * - 旋转矢量/方向 = 航向角对应四元数 + 步态小幅滚转
 * - 气压计 = 标准气压公式按伪造海拔推导（1.1.13，堵"气压推海拔 vs Location.altitude"交叉）
 * - 计步器 = 累计距离 / 0.7m 步幅，自驱动 1Hz 投递（1.1.13：不搭真实事件的车——
 *   手机真静止时 HAL 无事件可搭，假步数送不出去）
 * 未激活时全量透传真实数据；unregisterListener 换回代理对象（内部登记的是代理），防注销失效泄漏。
 */
object SensorHook {

    // 我们接管的数据源类型（其余传感器全量透传）。
    // 值须与 AOSP android.hardware.Sensor.TYPE_* 一致——此前此处整体错位
    // （GRAVITY 起较规范值 +1、STEP 系列较规范值 +3~4），致真 STEP_COUNTER(15)
    // 永不命中 T_STEP_COUNTER(旧19)→计步器透传真值（死代码），线性加速度(9)
    // 命中 GRAVITY 分支返回含重力值、旋转矢量(10)返回非单位四元数。1.1.3 改回规范值。
    private const val T_ACCELEROMETER = 1
    private const val T_MAGNETIC_FIELD = 2
    private const val T_ORIENTATION = 3
    private const val T_GYROSCOPE = 4
    private const val T_PRESSURE = 6
    private const val T_GRAVITY = 8
    private const val T_LINEAR_ACCELERATION = 9
    private const val T_ROTATION_VECTOR = 10
    private const val T_SIGNIFICANT_MOTION = 16
    private const val T_STEP_DETECTOR = 14
    private const val T_STEP_COUNTER = 15

    // 原 listener → 代理（弱键；unregister 时用）
    private val proxyMap: MutableMap<SensorEventListener, SensorEventListener> =
        Collections.synchronizedMap(WeakHashMap())

    // 原 TriggerEventListener → 包装壳（SIGNIFICANT_MOTION 走该接口，registerListener 路径接收不到）
    private val triggerMap: MutableMap<TriggerEventListener, TriggerEventListener> =
        Collections.synchronizedMap(WeakHashMap())

    /**
     * SIGNIFICANT_MOTION 触发器抑制壳：onTrigger 吞掉真实触发。
     * 伪造"开车"时手机在架上不动，本就不该有 significant motion；转发真实触发会让
     * App 拿到"设备被移动"信号与静止叙事矛盾。TriggerEventListener 是抽象类（非接口），
     * 动态代理替代不了，必须真实子类。
     */
    private class TriggerSuppressListener : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            // 吞掉：不转发给原 listener
        }
    }

    // ---- 运动状态（推导用；多传感器线程并发，统一锁）----
    private val stateLock = Any()
    private var lastSpeedMps = 0.0
    private var lastBearingDeg = 0.0
    private var lastTsMs = 0L
    private var traveledM = 0.0
    private var lastStepCount = -1
    private var stepsEmitted = 0 // 上次 STEP_DETECTOR 发射到的整步位置（stateLock 内读写）
    private var lastEmittedSteps = 0 // 1.1.16 单调护栏：上次报出的计步值（stateLock 内读写）
    @Volatile private var stepLogged = false

    // 计步自驱动登记（1.1.13）：(原 listener, 目标传感器)；CopyOnWrite 防定时器线程遍历竞态
    private class StepReg(val orig: SensorEventListener, val sensor: Sensor)
    private val stepRegs = java.util.concurrent.CopyOnWriteArrayList<StepReg>()

    // lazy：模块类装载期不得触碰 Looper（坑 #49 同源）
    private val stepHandler by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var stepTimerOn = false

    fun hook() {
        val smClass = SensorManager::class.java
        XposedBridge.hookAllMethods(smClass, "registerListener", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val orig = param.args.firstOrNull() as? SensorEventListener ?: return
                if (orig.javaClass.name.startsWith("com.awei.spoofer")) return
                val sensor = param.args.filterIsInstance<Sensor>().firstOrNull()
                if (sensor != null &&
                    (sensor.type == T_STEP_COUNTER || sensor.type == T_STEP_DETECTOR)
                ) {
                    regStep(orig, sensor) // 计步类登记给自驱动定时器（1.1.13）
                }
                val existing = proxyMap[orig]
                if (existing != null) {
                    param.args[0] = existing
                    return
                }
                val proxy = newProxy(orig)
                proxyMap[orig] = proxy
                param.args[0] = proxy
            }
        })
        XposedBridge.hookAllMethods(smClass, "unregisterListener", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val orig = param.args.firstOrNull() as? SensorEventListener ?: return
                val sensor = param.args.filterIsInstance<Sensor>().firstOrNull()
                if (sensor != null) {
                    stepRegs.removeAll { it.orig === orig && it.sensor.type == sensor.type }
                } else {
                    stepRegs.removeAll { it.orig === orig }
                }
                proxyMap[orig]?.let { param.args[0] = it }
            }
        })
        // SIGNIFICANT_MOTION 走 TriggerEventListener（非 SensorEventListener），
        // 上面的 registerListener 路径接收不到；此处包成 TriggerSuppressListener 吞掉真实触发
        XposedBridge.hookAllMethods(smClass, "requestTriggerSensor", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                for (i in param.args.indices) {
                    val a = param.args[i] ?: continue
                    if (a !is TriggerEventListener) continue
                    if (a is TriggerSuppressListener) continue  // 防套娃
                    val existing = triggerMap[a]
                    if (existing != null) {
                        param.args[i] = existing
                    } else {
                        val wrapper = TriggerSuppressListener()
                        triggerMap[a] = wrapper
                        param.args[i] = wrapper
                    }
                }
            }
        })
        XposedBridge.hookAllMethods(smClass, "cancelTriggerSensor", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // App 注销的是原对象，系统登记的是包装壳：换回去才能真正注销
                for (i in param.args.indices) {
                    val a = param.args[i] ?: continue
                    if (a !is TriggerEventListener) continue
                    triggerMap.remove(a)?.let { param.args[i] = it }
                }
            }
        })
        XposedBridge.log("awei: SensorHook installed")
    }

    private fun newProxy(original: SensorEventListener): SensorEventListener {
        val handler = java.lang.reflect.InvocationHandler { _, method, args ->
            when (method.name) {
                "onSensorChanged" -> {
                    try {
                        onSensorChanged(original, args?.getOrNull(0) as? SensorEvent)
                    } catch (t: Throwable) {
                    }
                    null
                }
                "onAccuracyChanged" -> {
                    try {
                        val s = args?.getOrNull(0) as? Sensor
                        val a = args?.getOrNull(1) as? Int
                        if (s != null && a != null) original.onAccuracyChanged(s, a)
                    } catch (t: Throwable) {
                    }
                    null
                }
                else -> try {
                    method.invoke(original, *(args ?: emptyArray()))
                } catch (t: Throwable) {
                    null
                }
            }
        }
        return java.lang.reflect.Proxy.newProxyInstance(
            original.javaClass.classLoader ?: SensorEventListener::class.java.classLoader,
            arrayOf(SensorEventListener::class.java),
            handler
        ) as SensorEventListener
    }

    private fun onSensorChanged(original: SensorEventListener, event: SensorEvent?) {
        if (event == null) return
        // 统一 deliverable 门禁（与其他叙事 hook 同口径）：路线缺失等"定位侧已放行
        // 真实"的场景下，传感器同步放行——否则出现"真实 GPS + 伪造步频/航向"的半伪装态
        if (!HookState.deliverable()) {
            original.onSensorChanged(event)
            return
        }
        // 计步类（1.1.13）：由 stepTick 自驱动合成投递——搭真实事件的车在"手机真静止"时
        // HAL 可能根本无事件可发，假步数送不出去（微信运动等直接读计步器的 App 步数冻结）。
        // 真实事件只用来采集硬件累计基数后吞掉，防双份投递
        if (event.sensor.type == T_STEP_COUNTER || event.sensor.type == T_STEP_DETECTOR) {
            if (event.sensor.type == T_STEP_COUNTER) {
                synchronized(stateLock) {
                    if (lastStepCount < 0) {
                        lastStepCount = (event.values?.firstOrNull() ?: 0f).toInt()
                    }
                }
            }
            return
        }
        val spoofed = when (event.sensor.type) {
            T_ACCELEROMETER, T_MAGNETIC_FIELD, T_GRAVITY, T_LINEAR_ACCELERATION, T_GYROSCOPE,
            T_ROTATION_VECTOR, T_ORIENTATION, T_SIGNIFICANT_MOTION, T_PRESSURE
            -> true
            else -> false
        }
        if (!spoofed) {
            original.onSensorChanged(event)
            return
        }
        val values = motionValues(event.sensor.type, event) ?: run {
            original.onSensorChanged(event)
            return
        }
        val fake = buildEvent(event.sensor, values) ?: run {
            original.onSensorChanged(event)
            return
        }
        original.onSensorChanged(fake)
    }

    /** 运动状态快照：advance() 的输出，供各传感器类型合成读数 */
    private class Motion(
        val accel: Double,     // 前进加速度 m/s²
        val yawRate: Double,   // 航向角速度 rad/s
        val traveledM: Double, // 会话累计距离 m
        val stepPhase: Double, // 步态相位 = traveledM/0.7（以步为单位，非弧度）
    )

    /**
     * 推进运动状态（dt 积分）：onSensorChanged 与计步自驱动定时器共用——两条路径按
     * "距上次推进的墙钟时长"分摊积分，不会重复累计。会话隔离：首次进入或距上次 >5s
     * （停→重启、跨进程复活）视为新会话，重置累计距离/计步种子/速度航向微分基线——
     * 否则旧会话脏值会让本会话首帧速度微分突变、步数累加跨会话漂移（STEP_COUNTER
     * 是硬件累计计数器，需从当前真值重新起步）。
     */
    private fun advance(fp: HookState.FakePos): Motion {
        val now = System.currentTimeMillis()
        synchronized(stateLock) {
            if (lastTsMs == 0L || now - lastTsMs > 5_000L) {
                traveledM = 0.0
                lastStepCount = -1
                stepsEmitted = 0
                lastSpeedMps = fp.speedMps
                lastBearingDeg = fp.bearing
            }
            val dt = if (lastTsMs == 0L) 1.0 else ((now - lastTsMs).coerceAtLeast(20)) / 1000.0
            val speed = fp.speedMps
            val bearing = fp.bearing
            val accel = (speed - lastSpeedMps) / dt // m/s²，正=加速
            val dYaw = normalizeDeg(bearing - lastBearingDeg)
            val yawRate = (dYaw / dt) / (180.0 / PI) // rad/s
            traveledM += speed * dt
            lastSpeedMps = speed
            lastBearingDeg = bearing
            lastTsMs = now
            return Motion(accel, yawRate, traveledM, traveledM / 0.7)
        }
    }

    /** 由伪装运动叙事推导传感器读数（输出长度与真实 event 保持一致） */
    private fun motionValues(type: Int, event: SensorEvent): FloatArray? {
        val fp = HookState.current() ?: return null // 无伪装位置叙事 → 透传真实
        // event.values 是平台类型，触发型事件理论上可空——取本地引用避免重复解引用
        val realValues = event.values ?: return null
        val m = advance(fp)
        // 步态振荡（1.1.13）：真人步行/跑步的加速度计有 ~步频 的竖直正弦摆动（0.4-1.5 m/s²），
        // 横向为半频（身体左右摇）；此前只有"速度微分+白噪声"，匀速行走输出≈纹丝不动，
        // 与计步器/GPS 在动组合出"摇步器/打车刷步"标准指纹（运动反作弊核心交叉项）。
        // 相位 = traveledM/0.7（空间推进式步相位，与计步器步数天然同步）；速度<0.7 视为静止无摆动
        val amp = if (fp.speedMps < 0.7) 0.0 else (0.35 * fp.speedMps).coerceAtMost(1.5)
        val gaitV = amp * sin(2.0 * PI * m.stepPhase)  // 竖直，步频
        val gaitH = 0.4 * amp * sin(PI * m.stepPhase)  // 横向，半频
        fun n(sigma: Double): Float = ((Math.random() * 2 - 1) * sigma).toFloat()
        val full: FloatArray = when (type) {
            T_ACCELEROMETER -> floatArrayOf(
                (0.2 * m.accel + gaitH).toFloat() + n(0.15),
                (-9.81 + gaitV).toFloat() + n(0.25),
                (0.3 * gaitV).toFloat() + n(0.15)
            )
            T_MAGNETIC_FIELD -> {
                // 地磁三分量按航向推导（中国中纬度：水平北向 H≈30µT、东向 E≈5µT、垂直 -Z≈-45µT）
                // 设备朝向=bearing：世界北在设备系投影 (x=-sin,y=cos)、东=(x=cos,y=sin)
                // 静止设备磁场不变化是物理证据，开车时磁场随航向旋转更自洽
                val rad = Math.toRadians(fp.bearing)
                val c = cos(rad); val s = sin(rad)
                val H = 30.0; val E = 5.0; val Z = 45.0
                floatArrayOf(
                    ((E * c - H * s) + n(1.0)).toFloat(),
                    ((E * s + H * c) + n(1.0)).toFloat(),
                    ((-Z) + n(1.0)).toFloat()
                )
            }
            T_LINEAR_ACCELERATION -> floatArrayOf(
                (0.2 * m.accel + gaitH).toFloat() + n(0.1),
                gaitV.toFloat() + n(0.2),
                (0.3 * gaitV).toFloat() + n(0.1)
            )
            T_GRAVITY -> floatArrayOf(0f, -9.81f, 0f)
            T_GYROSCOPE -> floatArrayOf(
                (0.05 * gaitV).toFloat() + n(0.01), n(0.01),
                (m.yawRate + (Math.random() * 2 - 1) * 0.01).toFloat()
            )
            T_ROTATION_VECTOR -> {
                val half = Math.toRadians(fp.bearing) / 2.0
                floatArrayOf(
                    (0.015 * amp * sin(PI * m.stepPhase)).toFloat(), // 步态小幅滚转
                    sin(half).toFloat(), 0f, cos(half).toFloat()
                )
            }
            T_ORIENTATION -> floatArrayOf(fp.bearing.toFloat(), 0f, 0f)
            // 气压计（1.1.13）：有气压计的机型可拿气压推海拔与 Location.altitude 交叉——
            // 标准气压公式按伪造海拔推导 + 微噪声
            T_PRESSURE -> floatArrayOf(
                (1013.25 * Math.pow(1.0 - 2.25577e-5 * fp.altitude, 5.25588)).toFloat() + n(0.02)
            )
            // 计步类已改自驱动投递（stepTick），真实事件在 onSensorChanged 前置分支被吞；
            // SIGNIFICANT_MOTION 实际走 TriggerEventListener（TriggerSuppressListener 吞），
            // 本路径接收不到，分支保留作无害兜底
            T_STEP_COUNTER, T_STEP_DETECTOR, T_SIGNIFICANT_MOTION -> floatArrayOf(1f)
            else -> realValues.copyOf()
        }
        // 输出长度与真实 event 一致（不足补 0，超出截断）
        return FloatArray(realValues.size).also { out ->
            for (i in out.indices) out[i] = if (i < full.size) full[i] else 0f
        }
    }

    /** 计步自驱动登记（1.1.13）：去重登记 + 幂等启动 1Hz 定时器 */
    private fun regStep(orig: SensorEventListener, sensor: Sensor) {
        if (stepRegs.none { it.orig === orig && it.sensor === sensor }) {
            stepRegs.add(StepReg(orig, sensor))
        }
        if (!stepTimerOn) {
            stepTimerOn = true
            stepHandler.postDelayed(stepTick, 1000)
        }
    }

    /**
     * 计步自驱动定时器（1.1.13）：1Hz 合成 STEP_COUNTER/STEP_DETECTOR 事件直投原 listener
     * （不经 proxy——proxy 对计步类是吞真值，走它会被再吞一次）。步数 = 会话基值 +
     * traveledM/0.7（与步态相位同源）；STEP_DETECTOR 按跨整步边界才发一次。
     * 无登记自停；真实事件到达时也会推进 advance()（dt 分摊积分，不重复累计）。
     */
    private val stepTick = object : Runnable {
        override fun run() {
            try {
                if (stepRegs.isEmpty()) {
                    stepTimerOn = false // 无登记自停
                    return
                }
                if (HookState.deliverable()) {
                    val fp = HookState.current()
                    if (fp != null) {
                        val m = advance(fp)
                        val totalSteps = m.stepPhase.toInt()
                        val counterValue: Int
                        val emitDetector: Boolean
                        synchronized(stateLock) {
                            if (lastStepCount < 0) {
                                // 全程静止收不到真实计步事件：会话派生的
                                // "当日已走部分步数"外观基值（跨会话不同）
                                lastStepCount = ((HookState.sessionSeed() % 3000) + 200).toInt()
                            }
                            // 1.1.16 单调护栏：STEP_COUNTER 是硬件累计计数器只增不减，
                            // 新会话基值（200-3200 会话派生）可能低于上一会话已报出值——
                            // 报出回退 = "步数倒走"即穿帮。把基值抬到不低于上次报出值
                            val raw = lastStepCount + totalSteps
                            if (raw < lastEmittedSteps) lastStepCount += (lastEmittedSteps - raw)
                            counterValue = lastStepCount + totalSteps
                            lastEmittedSteps = counterValue
                            emitDetector = totalSteps > stepsEmitted
                            if (emitDetector) stepsEmitted = totalSteps
                        }
                        if (!stepLogged) {
                            stepLogged = true
                            // 自检一次性日志（参考"方法名写错整段静默失效"前科）：
                            // 真机验证时确认计步自驱动已接管
                            XposedBridge.log("awei: SensorHook step-timer delivering (self-driven)")
                        }
                        for (reg in stepRegs) {
                            try {
                                when (reg.sensor.type) {
                                    T_STEP_COUNTER ->
                                        buildEvent(
                                            reg.sensor,
                                            floatArrayOf(counterValue.toFloat())
                                        )?.let { reg.orig.onSensorChanged(it) }
                                    T_STEP_DETECTOR ->
                                        if (emitDetector) {
                                            buildEvent(reg.sensor, floatArrayOf(1f))
                                                ?.let { reg.orig.onSensorChanged(it) }
                                        }
                                }
                            } catch (t: Throwable) {
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
            }
            stepHandler.postDelayed(this, 1000)
        }
    }

    private fun normalizeDeg(d: Double): Double {
        var x = d % 360.0
        if (x > 180.0) x -= 360.0
        if (x < -180.0) x += 360.0
        return x
    }

    /** SensorEvent 构造：优先隐藏 4 参构造器，兜底 1 参构造器 + 手填字段 */
    private fun buildEvent(sensor: Sensor, values: FloatArray): SensorEvent? {
        return try {
            val c = SensorEvent::class.java.getDeclaredConstructor(
                Sensor::class.java, Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType, FloatArray::class.java
            )
            c.isAccessible = true
            c.newInstance(sensor, 3, android.os.SystemClock.elapsedRealtimeNanos(), values) as SensorEvent
        } catch (t: Throwable) {
            try {
                val c = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
                c.isAccessible = true
                val ev = c.newInstance(values.size) as SensorEvent
                ev.sensor = sensor
                ev.accuracy = 3
                ev.timestamp = android.os.SystemClock.elapsedRealtimeNanos()
                val n = minOf(values.size, ev.values.size)
                System.arraycopy(values, 0, ev.values, 0, n)
                ev
            } catch (t2: Throwable) {
                null
            }
        }
    }
}
