package com.awei.spoofer.xposed.hooks

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.media.ImageReader
import android.view.Surface
import com.awei.spoofer.camera.StreamManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.Collections
import java.util.WeakHashMap

/**
 * Camera2 Hook v2（中转方案）：
 *
 * BufferQueue 的 Surface 只允许一个 producer：真实相机在 createCaptureSession 时
 * 就会连接 app 传入的输出面，我们再直接往同一 Surface 解码渲染必然失败。
 *
 * 因此 createCaptureSession 时对输出面分类处理：
 * - ImageReader 创建的 surface（拍照 JPEG / 分析 YUV）：保持原样交给真实相机，
 *   帧内容由 ImageHook 在读取端替换（避免真实取景画面泄露）
 * - 其余 surface（SurfaceTexture 预览、编码输入等）：替换为自建 RGBA 中转面，
 *   真实相机渲染到中转面（被我们占用），视频帧经中转线程绘制到原目标面
 */
object Camera2Hook {

    // 目标 App 自建的 ImageReader 输出面（弱引用防泄漏）
    private val readerSurfaces: MutableMap<Surface, Boolean> =
        Collections.synchronizedMap(WeakHashMap())

    // 我们自建的 drain reader 输出面（真实相机渲入、帧丢弃）。
    // ImageHook 据此跳过抽帧（drain 每帧都 acquire，若触发 JPEG 抽帧会拖垮中转线程）
    private val drainSurfaces: MutableMap<Surface, Boolean> =
        Collections.synchronizedMap(WeakHashMap())

    // 内部自建的解码/中转 reader 输出面（视频解码渲入、我们自己的线程读帧）：
    // 与 drain 同理，ImageHook 的 ensureJpeg 对它们是纯浪费（每帧一次 10-30ms
    // JPEG 压缩白做，还占 frameLock 挤掉绘制帧）。建成后由持有方登记进来
    private val internalSurfaces: MutableMap<Surface, Boolean> =
        Collections.synchronizedMap(WeakHashMap())

    fun isDrainSurface(s: Surface): Boolean = try {
        drainSurfaces.containsKey(s)
    } catch (t: Throwable) {
        false
    }

    fun registerInternalSurface(s: Surface) {
        try {
            internalSurfaces[s] = true
        } catch (t: Throwable) {
        }
    }

    /** drain 面 + 内部解码/中转面：ImageHook 的 acquire 抽帧全部跳过 */
    fun isInternalSurface(s: Surface): Boolean = try {
        internalSurfaces.containsKey(s) || drainSurfaces.containsKey(s)
    } catch (t: Throwable) {
        false
    }

    // 建会话家族统一处理（抽象类声明与运行时 impl 类共用同一 hook 实例；
    // 重复触发无害——输出面替换是幂等的：drain/reader 面第二次进来原样保留）
    private val sessionNames = arrayOf(
        "createCaptureSession",
        "createCaptureSessionByOutputConfigurations",
        "createConstrainedHighSpeedCaptureSession",
        "createReprocessableCaptureSession",
        "createReprocessableCaptureSessionByConfigurations"
    )

    private val sessionHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (!StreamManager.cameraActive) return
            for (i in param.args.indices) {
                val a = param.args[i] ?: continue
                when (a) {
                    is List<*> -> param.args[i] = replaceOutputs(a)
                    is android.hardware.camera2.params.SessionConfiguration -> replaceSessionConfig(a)
                }
            }
        }
    }

    private val deviceCloseHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            StreamManager.stopAll()
        }
    }

    // 已补挂运行时 hook 的设备类（防重复 hook 导致回调翻倍）
    private val hookedDeviceClasses: MutableSet<Class<*>> = Collections.synchronizedSet(HashSet())

    fun hook() {
        hookNewReader()
        hookCreateCaptureSession()
        hookClose()
        hookDeviceImplByName()
        hookSessionImplByName()
        hookOpenCamera()
        XposedBridge.log("awei: Camera2Hook v2 installed")
    }

    private fun hookCreateCaptureSession() {
        // 全部建会话入口：普通/List、旧版 ByOutputConfigurations、慢动作 HighSpeed、
        // 重处理 Reprocessable（含旧签名）——漏掉任何一条都会让该场景走真相机（穿帮）。
        // CameraDevice 的这些方法是 abstract 声明：主流 LSPosed hookAllMethods 会直接抛
        // IllegalArgumentException，必须逐条 try/catch（运行时实际类由 hookDeviceImplByName
        // 与 openCamera 动态补挂兜住，此处挂上了是锦上添花，挂不上不影响功能）
        for (name in sessionNames) {
            try {
                XposedBridge.hookAllMethods(CameraDevice::class.java, name, sessionHook)
            } catch (t: Throwable) {
            }
        }
    }

    /**
     * 加固（参考 android_virtual_cam）：部分 ROM 上只挂抽象类声明不触发，
     * 直接挂 framework 的 CameraDeviceImpl；OEM 自定义子类由 openCamera 后动态补挂
     */
    private fun hookDeviceImplByName() {
        val impl = try {
            de.robv.android.xposed.XposedHelpers.findClass(
                "android.hardware.camera2.impl.CameraDeviceImpl", CameraDevice::class.java.classLoader
            )
        } catch (t: Throwable) {
            null
        } ?: return
        hookDeviceClass(impl)
    }

    /** 会话关闭走的是 CameraCaptureSessionImpl（抽象类 CameraCaptureSession.close 挂不上）：
     *  直接挂 framework impl 类，设备关闭已有 hookDeviceClass 的 close 兜底 */
    private fun hookSessionImplByName() {
        val impl = try {
            de.robv.android.xposed.XposedHelpers.findClass(
                "android.hardware.camera2.impl.CameraCaptureSessionImpl", CameraCaptureSession::class.java.classLoader
            )
        } catch (t: Throwable) {
            null
        } ?: return
        try {
            XposedBridge.hookAllMethods(impl, "close", deviceCloseHook)
        } catch (t: Throwable) {
        }
    }

    private fun hookDeviceClass(clazz: Class<*>) {
        if (!hookedDeviceClasses.add(clazz)) return
        for (name in sessionNames) {
            try {
                XposedBridge.hookAllMethods(clazz, name, sessionHook)
            } catch (t: Throwable) {
            }
        }
        try {
            XposedBridge.hookAllMethods(clazz, "close", deviceCloseHook)
        } catch (t: Throwable) {
        }
    }

    private fun hookOpenCamera() {
        val cmClass = android.hardware.camera2.CameraManager::class.java
        // 回调式 openCamera：包一层 StateCallback，在 onOpened 里对运行时实际类动态补挂
        //（OEM 常改 impl 类/子类化 CameraDeviceImpl，只挂已知类名会整类 ROM 失效）
        XposedBridge.hookAllMethods(cmClass, "openCamera", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                for (i in param.args.indices) {
                    val a = param.args[i] ?: continue
                    if (a is android.hardware.camera2.CameraDevice.StateCallback) {
                        param.args[i] = wrapStateCallback(a)
                    }
                }
            }
        })
    }

    private fun wrapStateCallback(original: android.hardware.camera2.CameraDevice.StateCallback)
        : android.hardware.camera2.CameraDevice.StateCallback {
        return object : android.hardware.camera2.CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                try {
                    hookDeviceClass(camera.javaClass)
                } catch (t: Throwable) {
                }
                try {
                    original.javaClass.methods.firstOrNull { it.name == "onOpened" }?.let { m ->
                        m.isAccessible = true
                        m.invoke(original, camera)
                    }
                } catch (t: Throwable) {
                }
            }

            override fun onDisconnected(camera: CameraDevice) {
                forward(original, "onDisconnected", camera, 0)
            }

            override fun onError(camera: CameraDevice, error: Int) {
                forward(original, "onError", camera, error)
            }

            override fun onClosed(camera: CameraDevice) {
                forward(original, "onClosed", camera, 0)
            }
        }
    }

    private fun forward(original: android.hardware.camera2.CameraDevice.StateCallback,
                        name: String, camera: CameraDevice, error: Int) {
        try {
            original.javaClass.methods.firstOrNull { it.name == name }?.let { m ->
                // App 的回调多是匿名类（类本身包私有）：不 setAccessible 会 IllegalAccessException
                m.isAccessible = true
                if (m.parameterTypes.size >= 2) m.invoke(original, camera, error)
                else m.invoke(original, camera)
            }
        } catch (t: Throwable) {
        }
    }

    private fun hookNewReader() {
        XposedBridge.hookAllMethods(ImageReader::class.java, "newInstance", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val reader = param.result as? ImageReader ?: return
                try {
                    readerSurfaces[reader.surface] = true
                } catch (t: Throwable) {
                }
            }
        })
    }

    private fun replaceOutputs(original: List<*>): List<*> {
        return original.map { item ->
            when (item) {
                is Surface -> replaceSurface(item)
                is android.hardware.camera2.params.OutputConfiguration -> replaceOutputConfig(item)
                else -> item
            }
        }
    }

    private fun replaceSessionConfig(config: android.hardware.camera2.params.SessionConfiguration) {
        try {
            val outputs = config.outputConfigurations
            for (idx in outputs.indices) {
                val replaced = replaceOutputConfig(outputs[idx])
                if (replaced !== outputs[idx]) {
                    outputs[idx] = replaced
                }
            }
        } catch (t: Throwable) {
        }
    }

    private fun replaceOutputConfig(oc: android.hardware.camera2.params.OutputConfiguration): android.hardware.camera2.params.OutputConfiguration {
        return try {
            val s = oc.surface
            if (s == null || readerSurfaces.containsKey(s) || drainSurfaces.containsKey(s)) {
                // reader 面由 ImageHook 接管；drain 面是我们自己换上去的（重复处理防御）——原样保留
                oc
            } else {
                val relay = StreamManager.startRelay(s)
                if (relay != null && relay !== s) {
                    drainSurfaces[relay] = true
                    android.hardware.camera2.params.OutputConfiguration(relay)
                } else if (StreamManager.cameraActive) {
                    // startRelay 失败但伪装已开启：自建黑屏 drain 防真实画面泄露
                    val black = StreamManager.createBlackDrain(s)
                    if (black != null) {
                        drainSurfaces[black] = true
                        android.hardware.camera2.params.OutputConfiguration(black)
                    } else {
                        oc
                    }
                } else {
                    oc
                }
            }
        } catch (t: Throwable) {
            oc
        }
    }

    private fun replaceSurface(s: Surface): Surface {
        if (readerSurfaces.containsKey(s) || drainSurfaces.containsKey(s)) {
            return s
        }
        val relay = StreamManager.startRelay(s)
        if (relay != null) {
            drainSurfaces[relay] = true
            return relay
        }
        // startRelay 失败但伪装已开启：自建黑屏 drain 防真实画面泄露（视频/照片源未就绪或尺寸异常时）
        if (StreamManager.cameraActive) {
            val black = StreamManager.createBlackDrain(s)
            if (black != null) {
                drainSurfaces[black] = true
                return black
            }
        }
        return s
    }

    private fun hookClose() {
        // 两个 close 都是 abstract 声明：逐个 try/catch（真实关闭路径由 impl 类挂载兜住）
        try {
            XposedBridge.hookAllMethods(CameraCaptureSession::class.java, "close", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // v1 简化：app 关闭任一 session 时停止全部流（多 session 场景少见）
                    StreamManager.stopAll()
                }
            })
        } catch (t: Throwable) {
        }
        try {
            XposedBridge.hookAllMethods(CameraDevice::class.java, "close", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    StreamManager.stopAll()
                }
            })
        } catch (t: Throwable) {
        }
    }
}
