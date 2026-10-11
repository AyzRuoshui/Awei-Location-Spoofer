package com.awei.spoofer.xposed.hooks

import android.media.MediaRecorder
import com.awei.spoofer.camera.StreamManager
import com.awei.spoofer.xposed.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * MediaRecorder 录像路径的检测与兜底。
 *
 * 虚拟摄像头覆盖了预览（Camera2 中转 + Camera1 置 null）和拍照（ImageHook 换 JPEG），
 * 但录像路径目前只被"被动"处理：recorder 的 MediaCodec 编码器输入面在 createCaptureSession
 * 里被 replaceSurface 换成 drain surface，编码器收不到帧，录制产物是黑屏——不会泄露真实
 * 画面，但也不伪造录制内容。
 *
 * 本 Hook 做两件事，都在伪装开启时才生效（未开启不打日志、不拦截，不干扰目标 App）：
 * 1. 日志可见性：setVideoSource / start 记录目标 App 的录像意图与走的是哪条 API 分支，
 *    排障时能看出录像路径走到了（此前 drawToApp 对非 Canvas 面是静默 return，无人察觉）
 * 2. Camera1 路径兜底：setVideoSource(CAMERA) 走 native Camera::setPreviewTexture(ISurface)，
 *    Java 层 hook 不可达（现有 Camera1Hook 对它无效）——伪装开启时录制必然拿到真实画面，
 *    这是 user 作用域不可逾越的边界。此处直接拒绝，把"真实画面落盘/上传"堵死：代价是目标
 *    App 录像功能报错，远好于泄露。
 *
 * 完整伪造录制内容（双编码链：自建编码器喂 recorder 编码器输入面，唯一非相机非 Canvas 的
 * 合法 producer）是下一版大功能，需真机验证参数匹配/PTS 同步/硬件编码器并发，本版不做。
 */
object RecordingHook {

    fun hook() {
        // 组级计数（仿 DetectionHideHook 模式）：子挂载各自 try/catch 吞异常会让
        // hook() 恒成功、XposedInit 的 ok/12 反映不出子挂载失败。这里子挂载返
        // Boolean 成功标志，hook() 聚合上浮"RecordingHook 安装完成（ok/total 组）"
        var ok = 0
        val total = 2
        if (hookSetVideoSource()) ok++
        if (hookStart()) ok++
        XLog.i("RecordingHook 安装完成（$ok/$total 组）")
    }

    private fun hookSetVideoSource(): Boolean {
        return try {
            XposedBridge.hookAllMethods(
                MediaRecorder::class.java, "setVideoSource",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val src = param.args.getOrNull(0) as? Int ?: return
                        if (!StreamManager.cameraActive) return
                        when (src) {
                            MediaRecorder.VideoSource.CAMERA -> {
                                XLog.w("MediaRecorder.setVideoSource(CAMERA)：Camera1 录像" +
                                    "走 native 路径、Java hook 不可达，伪装开启时录制会拿到" +
                                    "真实画面，已拒绝")
                                // 1.1.16：裸 throw 会被 Xposed 框架 catch 后只记日志、原方法照常
                                // 执行（拒绝不生效 → 真实画面照样落盘）；必须走 setThrowable
                                // 语义（Kotlin 属性写法 param.throwable）——returnEarly 跳过原
                                // 方法 + 异常抛给调用方，"真实画面绝不落盘"的唯一闸门
                                param.throwable = RuntimeException(
                                    "awei: Camera1 录像路径无法被 Hook 拦截（native 层），" +
                                    "伪装开启时录制会拿到真实画面，已拒绝"
                                )
                            }
                            MediaRecorder.VideoSource.SURFACE,
                            4 /* SURFACE_SECURE：@hide 常量不在公开 API，AOSP 稳定值 */ ->
                                XLog.i("MediaRecorder 录像走 Camera2 路径：编码器输入面已被" +
                                    "换成 drain，录制产物为黑屏（未伪造内容）")
                            else -> XLog.w("MediaRecorder.setVideoSource(未知源 $src)")
                        }
                    }
                }
            )
            true
        } catch (t: Throwable) {
            XLog.e("RecordingHook.setVideoSource 挂载失败: ${t.javaClass.simpleName}")
            false
        }
    }

    private fun hookStart(): Boolean {
        return try {
            XposedBridge.hookAllMethods(
                MediaRecorder::class.java, "start",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!StreamManager.cameraActive) return
                        XLog.w("目标 App 正在录像（伪装开启中）：当前版本录像产物为黑屏，" +
                            "未伪造录制内容")
                    }
                }
            )
            true
        } catch (t: Throwable) {
            XLog.e("RecordingHook.start 挂载失败: ${t.javaClass.simpleName}")
            false
        }
    }
}
