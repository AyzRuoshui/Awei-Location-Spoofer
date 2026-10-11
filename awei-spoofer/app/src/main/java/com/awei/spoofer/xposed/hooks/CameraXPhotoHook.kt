package com.awei.spoofer.xposed.hooks

import com.awei.spoofer.camera.StreamManager
import com.awei.spoofer.xposed.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * CameraX ImageCapture 拍照代理 Hook（1.1.16 重写）。
 *
 * 旧实现在 before 阶段预写伪造文件，再反射调 `onCaptureComplete`——该方法在
 * `OnImageSavedCallback` 上根本不存在（正确名是 `onImageSaved(OutputFileResults)`），
 * 通知必失败（每次拍照刷一条 warn）；原 void takePicture 照常执行 → **真实照片
 * 落盘覆盖伪造文件并正常回调** = CameraX 拍照伪造整条失效且 fail-open。
 *
 * 现方案：把 takePicture 的 OnImageSavedCallback 参数换成动态代理——原方法正常
 * 拍照保存，回调触发时先把保存目标的字节覆写为本轮伪装帧，再转发给 App 的原回调：
 * App 只会经回调读到/使用伪造图，全程只回调一次（无双回调副作用）。
 *
 * 保存目标覆写面（1.1.17 补齐 MediaStore）：
 * - File 目标：直接 FileOutputStream 覆写；
 * - MediaStore 目标：最终 Uri 只出现在回调的 OutputFileResults.getSavedUri()，
 *   回调时 contentResolver.openOutputStream(uri, "wt") 覆写（contentResolver 从
 *   OutputFileOptions 反射取）；
 * - OutputStream 目标：App 自持流引用、CameraX 写完即关，事后无可覆写入口，
 *   为已知缺口（该场景透传真实照片，记入 §11）。
 *
 * OutputFileOptions 字段按**类型**反射（File / ContentResolver 各只有一个字段），
 * 不按名——CameraX 源码字段是 mFile/mContentResolver，且 App 侧 R8 可能改名字段，
 * 按名取（旧实现取 "file"）会静默落空整路失效。
 *
 * 另一重载 takePicture(Executor, OnImageCapturedCallback)（App 拿 ImageProxy 直读
 * 像素）由 ImageHook 的 plane 缓冲换覆盖——ImageProxy.getPlanes() 底下就是
 * Image.getPlanes，两路共用同一伪造源。
 *
 * 激活语义：仅 StreamManager.cameraActive 时包装；未开启伪装透传原回调，零干扰。
 */
object CameraXPhotoHook {

    @Volatile
    private var hooked = false

    fun hook(classLoader: ClassLoader) {
        if (hooked) return
        hooked = true
        try {
            val imageCaptureClass = classLoader.loadClass("androidx.camera.core.ImageCapture")
            val outputFileOptionsClass = classLoader.loadClass(
                "androidx.camera.core.ImageCapture\$OutputFileOptions"
            )
            val savedCallbackClass = classLoader.loadClass(
                "androidx.camera.core.ImageCapture\$OnImageSavedCallback"
            )

            // 保存到文件重载：包回调代理（转发前换文件字节）
            try {
                XposedHelpers.findAndHookMethod(
                    imageCaptureClass, "takePicture",
                    outputFileOptionsClass, Executor::class.java, savedCallbackClass,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!StreamManager.cameraActive) return
                            val cb = param.args.getOrNull(2) ?: return
                            // 已包过（重复注册/内部递归）防套娃
                            if (Proxy.isProxyClass(cb.javaClass)) return
                            val opts = param.args.getOrNull(0)
                            val file = getFileFromOptions(opts)
                            val resolver = if (file == null) getResolverFromOptions(opts) else null
                            if (file == null && resolver == null) {
                                XLog.w("CameraXPhotoHook：保存目标是 OutputStream（无 File/Uri 入口），无法覆写字节（已知缺口）")
                                return
                            }
                            param.args[2] = wrapSavedCallback(cb, savedCallbackClass, classLoader, file, resolver)
                        }
                    }
                )
            } catch (t: Throwable) {
                XLog.w("CameraXPhotoHook 保存重载挂载失败: ${t.javaClass.simpleName}")
            }

            XLog.i("CameraXPhotoHook 已安装（回调代理换帧）")
        } catch (t: Throwable) {
            // 目标 App 未使用 CameraX（类不存在）/ 其他反射失败：静默跳过
            XLog.w("CameraXPhotoHook 未激活（目标 App 可能未使用 CameraX）: ${t.javaClass.simpleName}")
        }
    }

    /**
     * OnImageSavedCallback 动态代理：onImageSaved 先把保存目标的字节换成本轮伪装帧
     * （File 目标直接覆写文件；MediaStore 目标从回调的 OutputFileResults 取 savedUri
     * 经 contentResolver 覆写），再转发原回调；onError 等其余方法原样透传。
     */
    private fun wrapSavedCallback(
        original: Any,
        iface: Class<*>,
        loader: ClassLoader,
        file: File?,
        contentResolver: android.content.ContentResolver?,
    ): Any {
        return Proxy.newProxyInstance(loader, arrayOf(iface)) { _, method, args ->
            when (method.name) {
                "onImageSaved" -> {
                    try {
                        val jpeg = StreamManager.captureJpeg(1280, 720, 0)
                        if (jpeg != null) {
                            if (file != null) {
                                file.parentFile?.mkdirs()
                                FileOutputStream(file).use { it.write(jpeg) }
                            } else if (contentResolver != null) {
                                // MediaStore：最终 Uri 只在此时由 OutputFileResults 给出
                                val savedUri = args?.getOrNull(0)?.let { res ->
                                    try {
                                        res.javaClass.getMethod("getSavedUri").invoke(res) as? android.net.Uri
                                    } catch (t: Throwable) {
                                        null
                                    }
                                }
                                if (savedUri != null) {
                                    contentResolver.openOutputStream(savedUri, "wt")?.use { it.write(jpeg) }
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        XLog.w("CameraXPhotoHook 覆写失败: ${t.javaClass.simpleName}")
                    }
                    try {
                        method.invoke(original, *(args ?: emptyArray()))
                    } catch (t: Throwable) {
                        XLog.w("CameraXPhotoHook 回调转发失败: ${t.javaClass.simpleName}")
                    }
                    null
                }
                "hashCode" -> original.hashCode()
                "equals" -> (args?.getOrNull(0) as Any?) === original
                "toString" -> original.toString()
                else -> try {
                    method.invoke(original, *(args ?: emptyArray()))
                } catch (t: Throwable) {
                    null
                }
            }
        }
    }

    /** OutputFileOptions 里取 File 保存目标（按字段类型反射，防 R8/m 前缀改名失效） */
    private fun getFileFromOptions(opts: Any?): File? = try {
        if (opts == null) null
        else opts.javaClass.declaredFields.firstOrNull { it.type == File::class.java }
            ?.let { f -> f.isAccessible = true; f.get(opts) as? File }
    } catch (t: Throwable) {
        null
    }

    /** OutputFileOptions 里取 MediaStore 目标的 ContentResolver（按字段类型反射） */
    private fun getResolverFromOptions(opts: Any?): android.content.ContentResolver? = try {
        if (opts == null) null
        else opts.javaClass.declaredFields
            .firstOrNull { it.type == android.content.ContentResolver::class.java }
            ?.let { f -> f.isAccessible = true; f.get(opts) as? android.content.ContentResolver }
    } catch (t: Throwable) {
        null
    }
}
