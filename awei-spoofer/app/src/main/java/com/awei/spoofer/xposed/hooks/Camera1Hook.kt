package com.awei.spoofer.xposed.hooks

import android.hardware.Camera
import android.view.Surface
import android.view.SurfaceHolder
import com.awei.spoofer.camera.StreamManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Proxy

/**
 * Camera1 Hook（android.hardware.Camera）：
 * - setPreviewTexture / setPreviewDisplay → Surface 注入视频流（置 null 断开真实相机 producer）
 * - PreviewCallback.onPreviewFrame → byte[] 替换为 NV21
 * - takePicture → PictureCallback.onPictureTaken byte[] 替换为 JPEG
 */
object Camera1Hook {

    // 尺寸按 Camera 实例弱键记录：固定 key 0 在前后摄切换（尺寸不同）时会串台，
    // 按 thisObject/回调参数里的 Camera 实例区分（弱键防 Camera 泄漏）
    private val previewSizes: MutableMap<Camera, IntArray> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<Camera, IntArray>())
    private val pictureSizes: MutableMap<Camera, IntArray> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<Camera, IntArray>())
    private val surfaceTextureMap = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<android.graphics.SurfaceTexture, Surface>()
    )

    // 1.1.27：App/框架对 SurfaceTexture 自设的缓冲尺寸（TextureView 建面即按视图设；
    // 规范相机 App 在 setPreviewTexture 前也会设）——接管预览面时优先沿用，
    // 无记录（裸 SurfaceTexture，出厂 1x1，微信拍摄页即此类）才回退 9:16 猜测
    private val stBufferSizes = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<android.graphics.SurfaceTexture, IntArray>()
    )

    // 1.1.29：使用裸 SurfaceTexture 的相机实例（= 自定义相机页，着色器按传感器朝向
    // 硬编码显示旋转）——其预览帧/拍照产物按真实相机契约送"传感器朝向"内容
    private val bareStCameras = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<Camera, Boolean>()
    )

    private fun getOrCreateSurface(st: android.graphics.SurfaceTexture): Surface {
        synchronized(surfaceTextureMap) {
            val existing = surfaceTextureMap[st]
            if (existing != null && existing.isValid) {
                return existing
            }
            val created = Surface(st)
            surfaceTextureMap[st] = created
            return created
        }
    }

    fun hook() {
        hookStBufferSizeRecorder()
        hookPreviewSetup()
        hookPreviewCallback()
        hookTakePicture()
        hookRelease()
        XposedBridge.log("awei: Camera1Hook installed")
    }

    /**
     * 1.1.27：监听记录 App/框架对 SurfaceTexture 的 setDefaultBufferSize 调用。
     * 真实相机 HAL 就是靠这一手决定缓冲尺寸的；Hook 切断真实相机后，接管方
     * （StreamManager 供帧）必须给出同尺寸的缓冲，否则比例失配被目标 App 拉伸变形。
     */
    private fun hookStBufferSizeRecorder() {
        XposedBridge.hookAllMethods(
            android.graphics.SurfaceTexture::class.java, "setDefaultBufferSize",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val st = param.thisObject as? android.graphics.SurfaceTexture ?: return
                    val w = param.args.getOrNull(0) as? Int ?: return
                    val h = param.args.getOrNull(1) as? Int ?: return
                    if (w > 0 && h > 0) stBufferSizes[st] = intArrayOf(w, h)
                }
            })
    }

    private fun hookPreviewSetup() {
        // 关键：把真实相机对预览面的 producer 连接断开（传入 null），
        // 让视频解码器独占 Surface 的 producer 槽位（BufferQueue 单 producer 限制）
        XposedBridge.hookAllMethods(Camera::class.java, "setPreviewTexture", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!StreamManager.cameraActive) return
                // App 可能不调 setPreviewSize（用默认尺寸）：从相机参数补记真实预览尺寸，
                // 避免 NV21 回调按 640x480 错误尺寸换帧导致尺寸不匹配而泄露真实帧
                try {
                    val cam = param.thisObject as? Camera
                    val ps = cam?.parameters?.previewSize
                    if (cam != null && ps != null && ps.width > 0 && ps.height > 0) {
                        previewSizes[cam] = intArrayOf(ps.width, ps.height)
                    }
                } catch (t: Throwable) {
                }
                val st = param.args.firstOrNull() as? android.graphics.SurfaceTexture ?: return
                param.args[0] = null // 真实相机不渲染，避免 producer 冲突
                // 1.1.26：真实相机被切断后没人代行 HAL 的 setDefaultBufferSize，
                // SurfaceTexture 停在出厂默认 1x1——EGL 供帧面与目标 App 的 OES 采样
                // 纹理都被钉死 1x1（MI 6 实测拍摄页"目标面=1x1"仍花屏）。
                // 1.1.27：优先沿用 App/框架自设的缓冲尺寸（TextureView 类框架已按视图设尺）。
                // 1.1.29：裸 SurfaceTexture（微信拍摄页等自定义相机页）按真实相机契约处理——
                // 缓冲用 App 请求的预览尺寸（传感器朝向横屏），内容预旋转 90°CW 补偿其
                // 硬编码的显示旋转（实测正立内容被转 90° 横躺）；有记录路径维持直显
                val camObj = param.thisObject as? Camera
                val recorded = stBufferSizes[st]
                val bare = recorded == null
                if (camObj != null) {
                    if (bare) bareStCameras[camObj] = true else bareStCameras.remove(camObj)
                }
                try {
                    val sz = recorded
                        ?: camObj?.let { previewSizes[it] }?.takeIf { it[0] > it[1] }
                        ?: intArrayOf(1920, 1080)
                    st.setDefaultBufferSize(sz[0], sz[1])
                    if (bare) {
                        com.awei.spoofer.xposed.XLog.i(
                            "Camera1: 裸 SurfaceTexture（自定义相机页）→ 缓冲=${sz[0]}x${sz[1]} + 内容预旋转90°CW"
                        )
                    }
                } catch (t: Throwable) {
                    com.awei.spoofer.xposed.XLog.w("Camera1: 预览面默认缓冲设置失败: ${t.message}")
                }
                StreamManager.startStream(getOrCreateSurface(st), bare)
            }
        })
        XposedBridge.hookAllMethods(Camera::class.java, "setPreviewDisplay", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!StreamManager.cameraActive) return
                val holder = param.args.firstOrNull() as? SurfaceHolder ?: return
                val surface = holder.surface ?: return
                param.args[0] = null
                StreamManager.startStream(surface)
            }
        })
        // 记录 app 设置的预览/拍照尺寸（按 Camera 实例区分）
        XposedBridge.hookAllMethods(Camera::class.java, "setPreviewSize", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                (param.thisObject as? Camera)?.let {
                    previewSizes[it] = intArrayOf(param.args[0] as Int, param.args[1] as Int)
                }
            }
        })
        XposedBridge.hookAllMethods(Camera::class.java, "setPictureSize", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                (param.thisObject as? Camera)?.let {
                    pictureSizes[it] = intArrayOf(param.args[0] as Int, param.args[1] as Int)
                }
            }
        })
    }

    private fun hookPreviewCallback() {
        // addCallbackBuffer：把 App 登记的回调 buffer 换成新零数组——
        // 真实帧永远不会被写入 App 的 buffer 池（比事后 arraycopy 覆盖更干净，真帧零残留）
        XposedBridge.hookAllMethods(Camera::class.java, "addCallbackBuffer", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!StreamManager.cameraActive) return
                val buf = param.args.getOrNull(0) as? ByteArray ?: return
                param.args[0] = ByteArray(buf.size)
            }
        })
        // setPreviewCallback / setPreviewCallbackWithBuffer / setOneShotPreviewCallback
        for (name in arrayOf("setPreviewCallback", "setPreviewCallbackWithBuffer", "setOneShotPreviewCallback")) {
            XposedBridge.hookAllMethods(Camera::class.java, name, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val cb = param.args.firstOrNull() as? Camera.PreviewCallback ?: return
                    param.args[0] = wrapPreviewCallback(cb)
                }
            })
        }
    }

    private fun wrapPreviewCallback(original: Camera.PreviewCallback): Camera.PreviewCallback {
        return Camera.PreviewCallback { data, camera ->
            var replaced = false
            try {
                if (StreamManager.cameraActive) {
                    val size = previewSizes[camera] ?: intArrayOf(640, 480)
                    val frame = StreamManager.currentFrameNv21(size[0], size[1], bareStCameras[camera] == true)
                    if (frame != null && frame.size == data.size) {
                        System.arraycopy(frame, 0, data, 0, frame.size)
                        replaced = true
                    }
                }
            } catch (t: Throwable) {
            }
            if (StreamManager.cameraActive && !replaced) {
                // 抽帧失败（远程未连上/尺寸不匹配等）时 data 里是真实相机帧：
                // 原样回调会泄露真实画面，必须清零兜底
                java.util.Arrays.fill(data, 0)
                // NV21 全 0 时 U/V=0 画面发绿：色度面填 128（0x80，有符号 -128）才是纯黑
                val ps = previewSizes[camera]
                val ySize = if (ps != null) ps[0] * ps[1] else 0
                if (ySize in 1 until data.size) {
                    java.util.Arrays.fill(data, ySize, data.size, 128.toByte())
                }
            }
            original.onPreviewFrame(data, camera)
        }
    }

    private fun hookTakePicture() {
        XposedBridge.hookAllMethods(Camera::class.java, "takePicture", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!StreamManager.cameraActive) return
                for (i in param.args.indices) {
                    val cb = param.args[i]
                    if (cb is Camera.PictureCallback) {
                        param.args[i] = proxyPictureCallback(cb)
                    }
                }
            }
        })
    }

    private fun proxyPictureCallback(original: Camera.PictureCallback): Camera.PictureCallback {
        val loader = original.javaClass.classLoader ?: Thread.currentThread().contextClassLoader
        return Proxy.newProxyInstance(
            loader, arrayOf(Camera.PictureCallback::class.java)
        ) { proxy, method, args ->
            if (method.name == "onPictureTaken") {
                val jpeg = try {
                    val cam = args?.getOrNull(1) as? Camera
                    val size = pictureSizes[cam] ?: intArrayOf(1280, 720)
                    val preRot = cam != null && bareStCameras[cam] == true
                    StreamManager.captureJpeg(size[0], size[1], 0, preRot)
                } catch (t: Throwable) {
                    null
                }
                // 回调必须必达：ShutterCallback 已响，若 here 丢弃回调，目标 App 拍照流程会永久挂起。
                // 抽帧失败给一张纯黑 JPEG 兜底（既不泄露真实画面，也不让 App 卡死）
                original.onPictureTaken(jpeg ?: blankJpeg(), args?.getOrNull(1) as? Camera)
            } else {
                method.invoke(original, *(args ?: emptyArray()))
            }
        } as Camera.PictureCallback
    }

    private fun blankJpeg(): ByteArray {
        return try {
            val bmp = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.eraseColor(0xFF000000.toInt())
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
            bmp.recycle()
            out.toByteArray()
        } catch (t: Throwable) {
            byteArrayOf()
        }
    }

    private fun hookRelease() {
        for (name in arrayOf("release", "stopPreview")) {
            XposedBridge.hookAllMethods(Camera::class.java, name, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    StreamManager.stopAll()
                }
            })
        }
    }
}
