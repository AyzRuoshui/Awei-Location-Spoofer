package com.awei.spoofer.xposed.hooks

import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import com.awei.spoofer.camera.StreamManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.nio.ByteBuffer

/**
 * Camera2 拍照路径（ImageReader JPEG）帧替换（1.1.16 重写）。
 *
 * 旧实现（反射构造 Image.Plane 假对象替换 getPlanes 数组）是**死代码**：
 * AOSP 里 Image.Plane 是 abstract 类且只有包私有无参构造（api-35 android.jar
 * javap 实证）——getDeclaredConstructor(int,int,ByteBuffer) 必抛
 * NoSuchMethodException → hook() 直接 return → 整个模块从未装上；即使构造器
 * 存在，abstract 类 Constructor.newInstance 也必抛 InstantiationException，
 * afterHook 异常被框架吞掉 → fail-open 还真实照片给目标 App。
 *
 * 现方案（不构造任何 framework 对象，天然免疫构造器/抽象类差异）：
 * getPlanes 返回真实数组后，把"单 plane 的 JPEG 帧"的 plane 实例登记进换缓冲
 * 注册表，并对其**运行时类**动态挂 getBuffer/getRowStride/getPixelStride——
 * App 读 plane.getBuffer() 时返回本轮伪装 JPEG 字节（每次 wrap 新 ByteBuffer，
 * 同一 Image 多次读取内容一致）。YUV/RGBA 多 plane 分析流不登记不动。
 * 附带覆盖 CameraX takePicture(Executor, OnImageCapturedCallback) 的 ImageProxy
 * 直读像素路径（ImageProxy.getPlanes 底下就是 Image.getPlanes）。
 *
 * 激活语义：仅 StreamManager.cameraActive 时登记；未开启伪装零影响。
 */
object ImageHook {

    // plane 实例 → 伪装 JPEG 字节（getPlanes 时登记；弱键随 Image 一起回收）
    private val swapped: MutableMap<Image.Plane, ByteArray> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap())

    // 已挂 getBuffer 等方法的 plane 运行时类（防重复挂载；OEM 类名不定，
    // 从真实 plane 实例取类动态挂——永不命中抽象声明，无 vc37 那类挂载陷阱）
    private val hookedPlaneClasses: MutableSet<Class<*>> =
        java.util.Collections.synchronizedSet(HashSet())

    // 多 ImageReader 线程并发 acquire：不加 volatile 最坏重复压缩一帧（非数据损坏）
    @Volatile
    private var cachedJpeg: ByteArray? = null

    @Volatile
    private var cachedAt = 0L

    private fun ensureJpeg() {
        val now = System.currentTimeMillis()
        if (now - cachedAt < 400 && cachedJpeg != null) return
        cachedJpeg = StreamManager.captureJpeg(1280, 720, 0) ?: cachedJpeg
        cachedAt = now
    }

    /** Image.getPlanes after：JPEG 单 plane 时登记 plane 实例 + 补挂其运行时类 */
    private val planeSwap = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (!StreamManager.cameraActive) return
            val img = param.thisObject as? Image ?: return
            // 仅替换单 plane 的 JPEG 帧；YUV 三 plane / RGBA 分析流不动
            //（替换了会让 App 解析花屏）
            if (img.format != ImageFormat.JPEG) return
            val planes = param.result as? Array<*> ?: return
            val bytes = cachedJpeg ?: return
            if (planes.size != 1) return
            val plane = planes[0] as? Image.Plane ?: return
            // bytes 在登记时刻快照：同一 Image 多次读取内容一致（cachedJpeg 轮换不受影响）
            swapped[plane] = bytes
            ensurePlaneHooks(plane.javaClass)
        }
    }

    /** plane.getBuffer after：登记过的 plane 返回伪装 JPEG 字节（每次新 wrap 保证游标一致） */
    private val bufferSwap = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val self = param.thisObject as? Image.Plane ?: return
            val bytes = swapped[self] ?: return
            param.result = ByteBuffer.wrap(bytes)
        }
    }

    private val rowSwap = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val self = param.thisObject as? Image.Plane ?: return
            val bytes = swapped[self] ?: return
            param.result = bytes.size
        }
    }

    private val pixelSwap = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val self = param.thisObject as? Image.Plane ?: return
            if (swapped.containsKey(self)) param.result = 1
        }
    }

    private fun ensurePlaneHooks(clazz: Class<*>) {
        if (!hookedPlaneClasses.add(clazz)) return
        for ((name, cb) in listOf(
            "getBuffer" to bufferSwap,
            "getRowStride" to rowSwap,
            "getPixelStride" to pixelSwap,
        )) {
            try {
                XposedBridge.hookAllMethods(clazz, name, cb)
            } catch (t: Throwable) {
                // 单方法挂载失败不断链（其余方法/其余类照常）
            }
        }
    }

    fun hook() {
        // getPlanes 的运行时实现类挂载为主（Image.getPlanes 是抽象方法，主流 LSPosed
        // 对抽象方法直接抛 IllegalArgumentException）；基类挂载兜底（部分运行时容忍且无害）
        try {
            XposedBridge.hookAllMethods(Image::class.java, "getPlanes", planeSwap)
        } catch (t: Throwable) {
        }
        for (implName in arrayOf(
            "android.media.ImageReader\$SurfaceImage",
            "android.media.ImageWriter\$WriterSurfaceImage",
        )) {
            try {
                val clazz = XposedHelpers.findClass(implName, Image::class.java.classLoader)
                XposedBridge.hookAllMethods(clazz, "getPlanes", planeSwap)
            } catch (t: Throwable) {
                // 类不存在（低 API）/ 挂载失败：另一个 impl 或基类兜底
            }
        }

        // acquire 预热 JPEG 缓存；我们自建的 reader（drain/解码渲入/中转读帧）每帧都
        // acquire，抽帧会以每秒数十次频率拖垮中转线程，全部跳过
        val acquireHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val reader = param.thisObject as? ImageReader ?: return
                if (reader.surface != null && Camera2Hook.isInternalSurface(reader.surface)) return
                if (StreamManager.cameraActive) ensureJpeg()
            }
        }
        try {
            XposedBridge.hookAllMethods(ImageReader::class.java, "acquireLatestImage", acquireHook)
            XposedBridge.hookAllMethods(ImageReader::class.java, "acquireNextImage", acquireHook)
        } catch (t: Throwable) {
        }
        XposedBridge.log("awei: ImageHook installed (plane-buffer-swap)")
    }
}
