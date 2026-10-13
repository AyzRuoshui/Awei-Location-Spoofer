package com.awei.spoofer.xposed.hooks

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.awei.spoofer.xposed.HookState
import com.awei.spoofer.xposed.XLog
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
 * 真实设备静止在家而伪造位置在"跑步/骑行/开车"——加速计/陀螺仪/计步与位置叙事矛盾即穿帮。
 * 1.1.34 升级（优化运动传感器高精度步频拟合与 Gait 步态模型）：
 * - 双通道保障：硬件事件到达时进行原地数组覆写（零反射、零 GC 抖动、绕过 Android 9+ 私有构造限制）；
 * - 40Hz 自驱动时钟：手机真实静止在桌上（HAL 睡眠/事件停发）时，由后台 HandlerThread 主动向 listener
 *   以 40Hz 节拍投递仿真步态，确保各类步态分析引擎持续输出平滑步数与合理步态特征；
 * - 动态步幅与步频：对齐真实人体运动生物力学（11km/h 对应 ~170 SPM，步长 ~1.08m），步间距 ~350ms，
 *   防止步频异常触发应用层滤波拒收；
 * - 真实手持姿态与物理一致性：Y=8.5、Z=4.9 合成微倾手持重力矢量，严格保证 accel == gravity + linearAccel。
 */
object SensorHook {

    // 接管的数据源类型（与 AOSP Sensor.TYPE_* 一致）
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

    // 原 TriggerEventListener → 包装壳（SIGNIFICANT_MOTION 走该接口）
    private val triggerMap: MutableMap<TriggerEventListener, TriggerEventListener> =
        Collections.synchronizedMap(WeakHashMap())

    private class TriggerSuppressListener : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {}
    }

    // 计步自驱动登记：(原 listener, 目标传感器)
    private class StepReg(val orig: SensorEventListener, val sensor: Sensor)
    private val stepRegs = java.util.concurrent.CopyOnWriteArrayList<StepReg>()

    // 运动传感器自驱动登记（加速度计/陀螺仪/姿态角等）
    private class MotionReg(val orig: SensorEventListener, val sensor: Sensor)
    private val motionRegs = java.util.concurrent.CopyOnWriteArrayList<MotionReg>()

    // 独立后台传感器泵时钟线程（免切主线程 Looper，高频 40Hz 零卡顿零抖动）
    private val sensorThread by lazy {
        HandlerThread("awei-sensor-pump").apply { start() }
    }
    private val sensorHandler by lazy { Handler(sensorThread.looper) }
    @Volatile private var stepTimerOn = false
    @Volatile private var motionTimerOn = false
    @Volatile private var lastHardwareEventTs = 0L
    @Volatile private var lastMotionLogMs = 0L

    // ---- 运动状态（推导用；多传感器线程并发，统一锁）----
    private val stateLock = Any()
    private var lastSpeedMps = 0.0
    private var lastBearingDeg = 0.0
    private var lastTsMs = 0L
    private var traveledM = 0.0
    private var lastStepCount = -1
    private var stepsEmitted = 0 // 上次 STEP_DETECTOR 发射到的整步位置
    private var lastEmittedSteps = 0 // 单调护栏：上次报出的计步值
    @Volatile private var stepLogged = false

    private fun isMotionSensor(type: Int): Boolean = when (type) {
        T_ACCELEROMETER, T_GYROSCOPE, T_ROTATION_VECTOR, T_ORIENTATION,
        T_LINEAR_ACCELERATION, T_GRAVITY -> true
        else -> false
    }

    private fun sensorName(type: Int): String = when (type) {
        T_ACCELEROMETER -> "加速度计"
        T_MAGNETIC_FIELD -> "地磁"
        T_ORIENTATION -> "方向"
        T_GYROSCOPE -> "陀螺仪"
        T_PRESSURE -> "气压计"
        T_GRAVITY -> "重力"
        T_LINEAR_ACCELERATION -> "线性加速度"
        T_ROTATION_VECTOR -> "旋转矢量"
        T_STEP_DETECTOR -> "计步探测"
        T_STEP_COUNTER -> "计步器"
        T_SIGNIFICANT_MOTION -> "显著运动"
        else -> "Sensor#$type"
    }

    private fun regStep(orig: SensorEventListener, sensor: Sensor) {
        if (stepRegs.none { it.orig === orig && it.sensor.type == sensor.type }) {
            stepRegs.add(StepReg(orig, sensor))
            XLog.i("SensorHook: 注册计步传感器 [${sensorName(sensor.type)}]")
        }
        if (!stepTimerOn) {
            stepTimerOn = true
            sensorHandler.postDelayed(stepTick, 1000)
        }
    }

    private fun regMotion(orig: SensorEventListener, sensor: Sensor) {
        if (motionRegs.none { it.orig === orig && it.sensor.type == sensor.type }) {
            motionRegs.add(MotionReg(orig, sensor))
            XLog.i("SensorHook: 注册运动传感器 [${sensorName(sensor.type)}] (${sensor.name})")
        }
        if (!motionTimerOn) {
            motionTimerOn = true
            sensorHandler.post(motionTick)
        }
    }

    fun hook() {
        val smClass = SensorManager::class.java
        XposedBridge.hookAllMethods(smClass, "registerListener", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val orig = param.args.firstOrNull() as? SensorEventListener ?: return
                if (orig.javaClass.name.startsWith("com.awei.spoofer")) return
                val sensor = param.args.filterIsInstance<Sensor>().firstOrNull()
                if (sensor != null) {
                    if (sensor.type == T_STEP_COUNTER || sensor.type == T_STEP_DETECTOR) {
                        regStep(orig, sensor)
                    } else if (isMotionSensor(sensor.type)) {
                        regMotion(orig, sensor)
                    }
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
                    motionRegs.removeAll { it.orig === orig && it.sensor.type == sensor.type }
                } else {
                    stepRegs.removeAll { it.orig === orig }
                    motionRegs.removeAll { it.orig === orig }
                }
                if (stepRegs.isEmpty()) stepTimerOn = false
                if (motionRegs.isEmpty()) motionTimerOn = false
                proxyMap[orig]?.let { param.args[0] = it }
            }
        })
        XposedBridge.hookAllMethods(smClass, "requestTriggerSensor", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                for (i in param.args.indices) {
                    val a = param.args[i] ?: continue
                    if (a !is TriggerEventListener) continue
                    if (a is TriggerSuppressListener) continue
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
                    } catch (_: Throwable) {
                    }
                    null
                }
                "onAccuracyChanged" -> {
                    try {
                        val s = args?.getOrNull(0) as? Sensor
                        val a = args?.getOrNull(1) as? Int
                        if (s != null && a != null) original.onAccuracyChanged(s, a)
                    } catch (_: Throwable) {
                    }
                    null
                }
                else -> try {
                    method.invoke(original, *(args ?: emptyArray()))
                } catch (_: Throwable) {
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
        if (!HookState.deliverable()) {
            original.onSensorChanged(event)
            return
        }
        // 计步类：由 stepTick 独立自驱动合成投递，硬件真实事件只用来采集硬件累计基数后吞掉，防双份投递
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
        val values = motionValues(event.sensor.type, event)
        if (values != null && event.values != null) {
            // 原地覆写真实硬件事件数组：零反射、零对象开销、彻底杜绝 Android 9+ 私有构造器反射异常
            System.arraycopy(values, 0, event.values, 0, minOf(values.size, event.values.size))
        }
        lastHardwareEventTs = SystemClock.elapsedRealtime()
        original.onSensorChanged(event)
    }

    /** 运动状态快照：advance() 的输出，供各传感器类型合成读数 */
    private class Motion(
        val accel: Double,     // 前进加速度 m/s²
        val yawRate: Double,   // 航向角速度 rad/s
        val traveledM: Double, // 会话累计距离 m
        val stepPhase: Double, // 步态相位（以步为单位，非弧度）
        val cadence: Int,      // 实时步频 (SPM)
        val stride: Double     // 实时步长 (m)
    )

    /**
     * 推进运动状态（dt 积分）：
     * dt 按真实毫秒计算，放宽下限至 1ms（杜绝旧实现强制 >=20ms 导致多传感器并发时步频 300% 虚高暴涨）；
     * 跑步模式下根据速度自适应拟合人体生物步频 (165~176 SPM) 与步幅 (0.90~1.30m)。
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
            val dt = if (lastTsMs == 0L) 0.02 else ((now - lastTsMs).coerceIn(1, 1000)) / 1000.0
            val speed = fp.speedMps
            val bearing = fp.bearing
            val accel = (speed - lastSpeedMps) / dt
            val dYaw = normalizeDeg(bearing - lastBearingDeg)
            val yawRate = (dYaw / dt) / (180.0 / PI)
            traveledM += speed * dt
            lastSpeedMps = speed
            lastBearingDeg = bearing
            lastTsMs = now

            // 动态步幅与步频拟合（对齐真实跑步生物力学）：
            // 跑步 (speed >= 2.0 m/s ~ 7.2 km/h)：步频维持在 165 ~ 176 SPM
            // stride = speed / (cadence / 60.0)。
            // 慢速/步行：保持 0.70m 步长
            val (stride, cadence) = if (speed >= 2.0) {
                val targetCadence = (162.0 + (speed * 3.6 - 7.0) * 2.5).coerceIn(165.0, 176.0)
                val targetStride = (speed / (targetCadence / 60.0)).coerceIn(0.90, 1.30)
                targetStride to targetCadence.toInt()
            } else if (speed > 0.5) {
                val targetCadence = (110.0 + (speed * 3.6) * 10.0).coerceIn(110.0, 150.0)
                0.70 to targetCadence.toInt()
            } else {
                0.70 to 0
            }
            val stepPhase = traveledM / stride
            return Motion(accel, yawRate, traveledM, stepPhase, cadence, stride)
        }
    }

    /** 由伪装运动叙事推导传感器读数（输出长度与真实 event 保持一致） */
    private fun motionValues(type: Int, event: SensorEvent?): FloatArray? {
        val fp = HookState.current() ?: return null
        val realValues = event?.values
        val m = advance(fp)
        // 振幅：静止=0；跑步时 (speed >= 2.0) amp 提升到 1.15 ~ 1.35 m/s²，
        // 确保加速度 RMS 稳定在 0.80 ~ 0.95 m/s²（显著高于常见计步器 runMinRms = 0.40 门限）
        val amp = when {
            fp.speedMps < 0.5 -> 0.0
            fp.speedMps < 2.0 -> (0.35 * fp.speedMps).coerceAtMost(0.7)
            else -> (1.0 + 0.08 * (fp.speedMps * 3.6 - 7.0)).coerceIn(1.15, 1.35)
        }
        val gaitV = amp * sin(2.0 * PI * m.stepPhase)  // 竖直，步频
        val gaitH = 0.35 * amp * sin(PI * m.stepPhase) // 横向，半频
        fun n(sigma: Double): Float = ((Math.random() * 2 - 1) * sigma).toFloat()
        val full: FloatArray = when (type) {
            T_ACCELEROMETER -> {
                // 真实手持跑步姿态：手机微倾，主要重力分布在 +Y 轴（约 8.5）与 +Z 轴（约 4.9），合力 ≈ 9.81 m/s²
                floatArrayOf(
                    (0.2 * m.accel + gaitH).toFloat() + n(0.15),
                    (8.5 + gaitV).toFloat() + n(0.25),
                    (4.9 + 0.3 * gaitV).toFloat() + n(0.15)
                )
            }
            T_MAGNETIC_FIELD -> {
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
                (0.2 * m.accel + gaitH).toFloat() + n(0.10),
                gaitV.toFloat() + n(0.20),
                (0.3 * gaitV).toFloat() + n(0.10)
            )
            T_GRAVITY -> floatArrayOf(0f, 8.5f, 4.9f)
            T_GYROSCOPE -> floatArrayOf(
                (0.05 * gaitV).toFloat() + n(0.01), n(0.01),
                (m.yawRate + (Math.random() * 2 - 1) * 0.01).toFloat()
            )
            T_ROTATION_VECTOR -> {
                val half = Math.toRadians(fp.bearing) / 2.0
                floatArrayOf(
                    (0.015 * amp * sin(PI * m.stepPhase)).toFloat(),
                    sin(half).toFloat(), 0f, cos(half).toFloat()
                )
            }
            T_ORIENTATION -> floatArrayOf(fp.bearing.toFloat(), 0f, 0f)
            T_PRESSURE -> floatArrayOf(
                (1013.25 * Math.pow(1.0 - 2.25577e-5 * fp.altitude, 5.25588)).toFloat() + n(0.02)
            )
            T_STEP_COUNTER, T_STEP_DETECTOR, T_SIGNIFICANT_MOTION -> floatArrayOf(1f)
            else -> realValues?.copyOf() ?: floatArrayOf(0f, 0f, 0f)
        }
        val outSize = realValues?.size ?: when (type) {
            T_ROTATION_VECTOR -> 4
            T_PRESSURE, T_STEP_COUNTER, T_STEP_DETECTOR, T_SIGNIFICANT_MOTION -> 1
            else -> 3
        }
        return FloatArray(outSize).also { out ->
            for (i in out.indices) out[i] = if (i < full.size) full[i] else 0f
        }
    }

    /** 40Hz (25ms) 运动传感器自驱动泵：手机平放/HAL 休眠停发时，主动向 listener 泵入仿真步态 */
    private val motionTick = object : Runnable {
        override fun run() {
            try {
                if (motionRegs.isEmpty()) {
                    motionTimerOn = false
                    return
                }
                val nowRt = SystemClock.elapsedRealtime()
                // 若硬件最近 35ms 内刚触发过真实事件，说明硬件主动投递中，自驱动让道防双份
                val hwActive = nowRt - lastHardwareEventTs < 35L
                if (!hwActive && HookState.deliverable()) {
                    val fp = HookState.current()
                    if (fp != null) {
                        for (reg in motionRegs) {
                            try {
                                val vals = motionValues(reg.sensor.type, null) ?: continue
                                val ev = buildEvent(reg.sensor, vals) ?: continue
                                reg.orig.onSensorChanged(ev)
                            } catch (_: Throwable) {}
                        }
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastMotionLogMs > 60_000L) {
                            lastMotionLogMs = nowMs
                            val m = advance(fp)
                            XLog.i("SensorHook: 40Hz 步态自驱动活跃，速度=%.1fkm/h 步频=%dSPM 步长=%.2fm 累计步数=%d".format(
                                fp.speedMps * 3.6, m.cadence, m.stride, m.stepPhase.toInt()
                            ))
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            if (motionTimerOn) {
                sensorHandler.postDelayed(this, 25) // 40Hz (25ms)
            }
        }
    }

    /** 1Hz 计步自驱动定时器：合成 STEP_COUNTER / STEP_DETECTOR 事件直投原 listener */
    private val stepTick = object : Runnable {
        override fun run() {
            try {
                if (stepRegs.isEmpty()) {
                    stepTimerOn = false
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
                                lastStepCount = (HookState.sessionSeed() and 0x7FFF).toInt() % 3000 + 200
                            }
                            val raw = lastStepCount + totalSteps
                            if (raw < lastEmittedSteps) lastStepCount += (lastEmittedSteps - raw)
                            counterValue = lastStepCount + totalSteps
                            lastEmittedSteps = counterValue
                            emitDetector = totalSteps > stepsEmitted
                            if (emitDetector) stepsEmitted = totalSteps
                        }
                        if (!stepLogged) {
                            stepLogged = true
                            XLog.i("SensorHook: 1Hz 计步自驱动接管完成，初始基数=$counterValue")
                        }
                        for (reg in stepRegs) {
                            try {
                                when (reg.sensor.type) {
                                    T_STEP_COUNTER ->
                                        buildEvent(reg.sensor, floatArrayOf(counterValue.toFloat()))?.let {
                                            reg.orig.onSensorChanged(it)
                                        }
                                    T_STEP_DETECTOR ->
                                        if (emitDetector) {
                                            buildEvent(reg.sensor, floatArrayOf(1f))?.let {
                                                reg.orig.onSensorChanged(it)
                                            }
                                        }
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            if (stepTimerOn) {
                sensorHandler.postDelayed(this, 1000)
            }
        }
    }

    private fun normalizeDeg(d: Double): Double {
        var x = d % 360.0
        if (x > 180.0) x -= 360.0
        if (x < -180.0) x += 360.0
        return x
    }

    /** SensorEvent 构造：优先 HiddenApiBypass 绕过 Android 9+ 私有构造限制，兜底反射 */
    private fun buildEvent(sensor: Sensor, values: FloatArray): SensorEvent? {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val evBypass = try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.newInstance(
                SensorEvent::class.java, values.size
            ) as? SensorEvent
        } catch (_: Throwable) { null }

        val ev = evBypass ?: try {
            val c = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            c.isAccessible = true
            c.newInstance(values.size) as SensorEvent
        } catch (_: Throwable) { null } ?: return null

        return try {
            ev.sensor = sensor
            ev.accuracy = 3
            ev.timestamp = nowNanos
            val n = minOf(values.size, ev.values.size)
            System.arraycopy(values, 0, ev.values, 0, n)
            ev
        } catch (_: Throwable) {
            null
        }
    }
}
