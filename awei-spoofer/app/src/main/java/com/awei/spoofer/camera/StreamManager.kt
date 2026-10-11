package com.awei.spoofer.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.view.Surface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 帧流管理器（Hook 进程内）：
 * - 直连模式（Camera1）：FrameStreamer 硬解视频直接渲染到目标 Surface
 * - 中转模式（Camera2）：真实相机占用目标 Surface 的 producer 槽位，
 *   因此视频先渲染到自建 RGBA ImageReader，再由中转线程绘制到目标 Surface
 * - 拍照路径：MediaMetadataRetriever 按当前播放位置抽帧 → JPEG/NV21
 *
 * retriever 非线程安全：所有帧抽取操作经同一把锁串行化。
 */
object StreamManager {

    @Volatile
    var cameraActive: Boolean = false
        private set

    @Volatile
    private var videoPfd: ParcelFileDescriptor? = null

    private val streams = ConcurrentHashMap<Surface, FrameStreamer>()
    private val relays = ConcurrentHashMap<Surface, RelayStreamer>()

    private var retriever: MediaMetadataRetriever? = null

    @Volatile
    var currentPtsUs: Long = 0L
        private set

    internal fun updatePts(pts: Long) {
        currentPtsUs = pts
    }

    @Volatile
    var videoWidth: Int = 0
        private set

    @Volatile
    var videoHeight: Int = 0
        private set

    @Volatile
    private var imageBitmapCw90: Bitmap? = null

    /** 前置画面镜像：开启后所有输出面走中转绘制路径并水平翻转 */
    @Volatile
    var mirror: Boolean = false

    /** 远程摄像头源模式（双机）：输出面由 RemoteCamSource 绘制远程帧 */
    @Volatile
    var useRemote: Boolean = false
        private set

    /** 照片源模式：输出面由 ImageStreamer 定时绘制静态 Bitmap */
    @Volatile
    var useImage: Boolean = false
        private set

    @Volatile
    private var imageBitmap: Bitmap? = null

    private val imageRelays = ConcurrentHashMap<Surface, ImageStreamer>()

    /** drain reader（真实相机消费面，帧丢弃防阻塞）——远程/照片源共用 */
    private val remoteDrains = ConcurrentHashMap<Surface, ImageReader>()

    private val drainHandler by lazy {
        val ht = HandlerThread("awei-drain")
        ht.start()
        Handler(ht.looper)
    }

    private val frameLock = Any()

    @Synchronized
    fun attachVideo(pfd: ParcelFileDescriptor): Boolean {
        detachVideo()
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(pfd.fileDescriptor)
            videoWidth = try {
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            } catch (t: Throwable) {
                0
            }
            videoHeight = try {
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            } catch (t: Throwable) {
                0
            }
            retriever = r
            videoPfd = pfd
            cameraActive = true
            com.awei.spoofer.xposed.XLog.i("视频源 attach 成功 ${videoWidth}x${videoHeight}")
            true
        } catch (t: Throwable) {
            com.awei.spoofer.xposed.XLog.e("视频源 attach 失败: $t")
            // 失败路径必须关闭调用方传入的 pfd，否则 15s 配置刷新重试会每分钟泄漏一个 fd
            try {
                pfd.close()
            } catch (t2: Throwable) {
            }
            false
        }
    }

    /**
     * 切换到照片源模式：解码 JPEG/PNG → Bitmap，ImageStreamer 定时绘制到全部输出面。
     * 不编码 H.264（FrameStreamer 的 EOS 循环依赖设备规范外行为；仓库无编码能力）。
     */
    @Synchronized
    fun attachImage(pfd: ParcelFileDescriptor): Boolean {
        detachVideo()
        return try {
            // PFD 不支持 seek，两次 decode（量尺寸 + 真解码）需独立数据源：
            // 读全部字节到内存，再用 decodeByteArray 两次
            val raw = java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
            if (raw.isEmpty()) throw RuntimeException("读取 0 字节")
            val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, probe)
            if (probe.outWidth <= 0 || probe.outHeight <= 0)
                throw RuntimeException("无法解析图片尺寸")
            // 降采样防 OOM：目标长边 ≤1920
            val maxEdge = 1920
            val maxSrc = maxOf(probe.outWidth, probe.outHeight)
            val sample = if (maxSrc > maxEdge)
                Integer.highestOneBit((maxSrc.toFloat() / maxEdge).toInt().coerceAtLeast(1))
            else 1
            val decOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, decOpts)
                ?: throw RuntimeException("decodeByteArray 返回 null")
            // 1.1.28：BitmapFactory 不应用 Exif 方向（坑 #71）——androidx ExifInterface 全 8 种
            // 方向补齐（含镜像/转置 2/4/5/7）+ WebP/PNG/HEIF 支持（微信保存图常为 WebP，框架
            // ExifInterface 不认会抛错）。1.1.27 只映射了 90/180/270 且异常静默吞掉，真机日志
            // attach 尺寸未变、无旋转标记——该图落在未覆盖分支或解析失败，此版补齐并显式记日志
            var exifOri = 1
            var exifErr: String? = null
            try {
                exifOri = androidx.exifinterface.media.ExifInterface(java.io.ByteArrayInputStream(raw))
                    .getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)
            } catch (t: Throwable) {
                exifErr = t.toString()
            }
            val m = Matrix()
            when (exifOri) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE -> {
                    m.postRotate(90f); m.postScale(1f, -1f)
                }
                androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE -> {
                    m.postRotate(90f); m.postScale(-1f, 1f)
                }
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                else -> {}
            }
            val upright = if (!m.isIdentity) {
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true) ?: bmp
            } else bmp
            if (upright !== bmp) {
                try { bmp.recycle() } catch (t: Throwable) {}
            }
            // Bitmap 已解码到内存，pfd 不再需要
            try { pfd.close() } catch (t: Throwable) {}
            imageBitmap = upright
            // 1.1.29：同时备一份顺时针 90° 预旋转副本——裸 SurfaceTexture（微信拍摄页等
            // 自定义相机页）的着色器按真实相机的传感器朝向硬编码显示旋转，送"显示朝向"的
            // 正立图会被再转 90° 横躺；该路径需送"传感器朝向"内容（顺时针预旋转）补偿
            imageBitmapCw90 = try {
                Bitmap.createBitmap(
                    upright, 0, 0, upright.width, upright.height,
                    Matrix().apply { postRotate(90f) }, true
                )
            } catch (t: Throwable) {
                com.awei.spoofer.xposed.XLog.w("照片源 90° 预旋转副本创建失败（该路径回退原图）: $t")
                null
            }
            videoWidth = upright.width
            videoHeight = upright.height
            useImage = true
            cameraActive = true
            com.awei.spoofer.xposed.XLog.i(
                "照片源 attach 成功 ${upright.width}x${upright.height} " +
                "mime=${probe.outMimeType ?: "?"} Exif[ori=$exifOri${exifErr?.let { " err=$it" } ?: ""}]"
            )
            true
        } catch (t: Throwable) {
            com.awei.spoofer.xposed.XLog.e("照片源 attach 失败: $t")
            try { pfd.close() } catch (t2: Throwable) {}
            false
        }
    }

    @Synchronized
    fun startStream(surface: Surface, contentRotateCw90: Boolean = false): Boolean {
        if (!cameraActive) return false
        if (!surface.isValid) return false
        // 远程源模式：输出面直接交给 RemoteCamSource（无本地视频解码）
        if (useRemote) return RemoteCamSource.attachOutput(surface)
        // 照片源模式：走中转（ImageStreamer 定时绘制 Bitmap 到目标面）
        if (useImage) return startImageRelay(surface, contentRotateCw90) != null
        val pfd = videoPfd ?: return false
        // 直连/中转两种路径互斥，切换前两者都清（防旧 relay 泄漏线程与 reader）
        stopStreamLocked(surface)
        stopRelayLocked(surface)
        // 镜像模式走中转绘制路径（MediaCodec 直渲无法翻转）
        if (mirror) {
            val relay = startRelay(surface)
            return relay != null
        }
        return try {
            val s = FrameStreamer(pfd, surface) { pts -> currentPtsUs = pts }
            s.start()
            streams[surface] = s
            com.awei.spoofer.xposed.XLog.i("直连流启动（Camera1${if (mirror) "+镜像" else ""}）")
            true
        } catch (t: Throwable) {
            com.awei.spoofer.xposed.XLog.e("直连流启动失败: $t")
            false
        }
    }

    @Synchronized
    fun stopStream(surface: Surface) {
        stopStreamLocked(surface)
        stopRelayLocked(surface)
        stopImageRelayLocked(surface)
        remoteDrains.remove(surface)?.close()
        if (useRemote) RemoteCamSource.detachOutput(surface)
    }

    private fun stopStreamLocked(surface: Surface) {
        streams.remove(surface)?.stop()
    }

    private fun stopRelayLocked(surface: Surface) {
        relays.remove(surface)?.stop()
    }

    private fun stopImageRelayLocked(surface: Surface) {
        imageRelays.remove(surface)?.stop()
    }

    /**
     * Camera2 中转：返回替代 Surface（交给 createCaptureSession 替换 app 的输出面），
     * 视频帧经替代面中转绘制到 appSurface。
     */
    @Synchronized
    fun startRelay(appSurface: Surface): Surface? {
        if (!cameraActive) return null
        if (!appSurface.isValid) return null
        // 照片源模式：走照片中转（ImageStreamer 定时绘制到目标面，真实相机渲 drain 丢弃）
        if (useImage) {
            return startImageRelay(appSurface)
        }
        if (useRemote) {
            // 远程源：真实相机仍需消费面（drain 丢帧防阻塞），远程帧绘制到原目标面
            stopRelayLocked(appSurface)
            remoteDrains.remove(appSurface)?.close()
            val w = if (videoWidth > 0) videoWidth.coerceAtMost(1280) else 1280
            val h = if (videoHeight > 0) videoHeight.coerceAtMost(720) else 720
            return try {
                val drain = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
                drain.setOnImageAvailableListener({ rd ->
                    try {
                        rd.acquireLatestImage()?.close()
                    } catch (t: Throwable) {
                    }
                }, drainHandler)
                remoteDrains[appSurface] = drain
                RemoteCamSource.attachOutput(appSurface)
                com.awei.spoofer.xposed.XLog.i("远程源中转启动（相机渲 drain 丢弃，远程帧绘制到目标面）")
                drain.surface
            } catch (t: Throwable) {
                com.awei.spoofer.xposed.XLog.e("远程 drain 启动失败: $t")
                null
            }
        }
        val pfd = videoPfd ?: return null
        if (videoWidth <= 0 || videoHeight <= 0) return null
        stopRelayLocked(appSurface)
        return try {
            val relay = RelayStreamer(pfd, videoWidth, videoHeight, appSurface)
            val relaySurface = relay.start() ?: run {
                com.awei.spoofer.xposed.XLog.e("中转器启动失败")
                return null
            }
            relays[appSurface] = relay
            com.awei.spoofer.xposed.XLog.i("中转流启动（相机渲 drain reader，视频经中转绘制）")
            relaySurface
        } catch (t: Throwable) {
            com.awei.spoofer.xposed.XLog.e("中转器异常: $t")
            null
        }
    }

    /**
     * 照片源中转：返回 drain Surface（真实相机渲入丢弃），ImageStreamer 定时把
     * 静态 Bitmap 绘制到 appSurface。结构与 startRelay 的远程分支一致。
     */
    @Synchronized
    fun startImageRelay(appSurface: Surface, contentRotateCw90: Boolean = false): Surface? {
        if (!cameraActive) return null
        if (!appSurface.isValid) return null
        val raw = imageBitmap ?: return null
        // 1.1.29：裸 SurfaceTexture（自定义相机页）路径送顺时针预旋转副本（传感器朝向补偿）
        val bmp = (if (contentRotateCw90) imageBitmapCw90 else null) ?: raw
        stopRelayLocked(appSurface)
        stopImageRelayLocked(appSurface)
        remoteDrains.remove(appSurface)?.close()
        val w = if (videoWidth > 0) videoWidth.coerceAtMost(1280) else 1280
        val h = if (videoHeight > 0) videoHeight.coerceAtMost(720) else 720
        return try {
            val drain = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
            drain.setOnImageAvailableListener({ rd ->
                try { rd.acquireLatestImage()?.close() } catch (t: Throwable) {}
            }, drainHandler)
            remoteDrains[appSurface] = drain
            val streamer = ImageStreamer(bmp, appSurface, mirror)
            streamer.start()
            imageRelays[appSurface] = streamer
            com.awei.spoofer.xposed.XLog.i(
                "照片源中转启动（相机渲 drain 丢弃，照片定时绘制到目标面${if (bmp !== raw) "，内容预旋转90°CW" else ""}）"
            )
            drain.surface
        } catch (t: Throwable) {
            com.awei.spoofer.xposed.XLog.e("照片源中转启动失败: $t")
            null
        }
    }

    /**
     * 自建丢弃帧的 drain reader（startRelay 返 null 时防真实画面泄露用）。
     * 伪装开启但源未就绪/尺寸异常时，真实相机输出面替换为此 drain，帧被丢弃（黑屏）。
     */
    @Synchronized
    fun createBlackDrain(appSurface: Surface): Surface? {
        if (!cameraActive) return null
        return try {
            val drain = ImageReader.newInstance(1280, 720, PixelFormat.RGBA_8888, 3)
            drain.setOnImageAvailableListener({ rd ->
                try { rd.acquireLatestImage()?.close() } catch (t: Throwable) {}
            }, drainHandler)
            remoteDrains[appSurface] = drain
            com.awei.spoofer.xposed.XLog.w("startRelay 返回 null，自建黑屏 drain 防真实画面泄露")
            drain.surface
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 停止所有输出流，但保留视频源（retriever/pfd）与 cameraActive 状态。
     * App 生命周期（stopPreview/关闭 session）会频繁触发——若连视频源一起卸载，
     * 需等主 App 15s 配置刷新才能重建，期间 App 重新预览会黑屏。
     */
    @Synchronized
    fun stopAll() {
        streams.values.forEach { it.stop() }
        streams.clear()
        relays.values.forEach { it.stop() }
        relays.clear()
        imageRelays.values.forEach { it.stop() }
        imageRelays.clear()
        remoteDrains.values.forEach { try { it.close() } catch (t: Throwable) {} }
        remoteDrains.clear()
        if (useRemote) RemoteCamSource.detachAllOutputs()
    }

    /** 完全卸载：停流 + 释放视频源（attachVideo 前 / 伪装彻底关闭时调用）；远程模式下断开远程连接 */
    @Synchronized
    fun detachVideo() {
        streams.values.forEach { it.stop() }
        streams.clear()
        relays.values.forEach { it.stop() }
        relays.clear()
        remoteDrains.values.forEach { try { it.close() } catch (t: Throwable) {} }
        remoteDrains.clear()
        if (useRemote) {
            RemoteCamSource.release()
            useRemote = false
        }
        if (useImage) {
            imageRelays.values.forEach { it.stop() }
            imageRelays.clear()
            try { imageBitmap?.recycle() } catch (t: Throwable) {}
            imageBitmap = null
            try { imageBitmapCw90?.recycle() } catch (t: Throwable) {}
            imageBitmapCw90 = null
            useImage = false
        }
        // retriever 释放必须与帧抽取（frameLock）互斥：vendor 实现的 release/getFrameAtTime
        // 并发会 native 崩溃带崩目标 App
        synchronized(frameLock) {
            try {
                retriever?.release()
            } catch (t: Throwable) {
            }
        }
        retriever = null
        try {
            videoPfd?.close()
        } catch (t: Throwable) {
        }
        videoPfd = null
        cameraActive = false
        com.awei.spoofer.xposed.XLog.i("视频/照片源完全卸载")
    }

    /** 切换到远程摄像头源模式（授权/连接/解码由 RemoteCamSource 管理） */
    fun attachRemote(): Boolean {
        detachVideo()
        useRemote = true
        cameraActive = true
        com.awei.spoofer.xposed.XLog.i("虚拟摄像头切换为远程源")
        return true
    }

    /**
     * 拍照：按当前播放位置抽帧转 JPEG。
     * 低频调用（拍照事件），性能可接受。
     */
    fun captureJpeg(width: Int, height: Int, rotation: Int, preRotateCw90: Boolean = false): ByteArray? {
        synchronized(frameLock) {
            return try {
                var bmp = currentFrameBitmap() ?: return null
                // 1.1.29：裸 SurfaceTexture 自定义相机页按传感器朝向取产物（顺时针预旋转，
                // 目标 App 显示侧会转回来；正立产物会被它转成横躺）
                if (preRotateCw90) {
                    bmp = Bitmap.createBitmap(
                        bmp, 0, 0, bmp.width, bmp.height,
                        Matrix().apply { postRotate(90f) }, true
                    ) ?: bmp
                }
                val fitted = YuvUtil.fitBitmap(bmp, width.coerceAtLeast(1), height.coerceAtLeast(1))
                val mirrored = if (mirror) YuvUtil.mirrorBitmap(fitted) else fitted
                val rotated = if (rotation != 0) YuvUtil.rotate(mirrored, rotation.toFloat()) else mirrored
                val jpeg = YuvUtil.bitmapToJpeg(rotated)
                // 中间产物统一回收（LinkedHashSet 去重同一实例）
                val chain = LinkedHashSet<Bitmap>()
                chain.add(bmp); chain.add(fitted); chain.add(mirrored); chain.add(rotated)
                for (b in chain) b.recycle()
                jpeg
            } catch (t: Throwable) {
                null
            }
        }
    }

    fun currentFrameNv21(width: Int, height: Int, preRotateCw90: Boolean = false): ByteArray? {
        synchronized(frameLock) {
            return try {
                var bmp = currentFrameBitmap() ?: return null
                // 1.1.29：同 captureJpeg——自定义相机页按传感器朝向取预览帧
                if (preRotateCw90) {
                    bmp = Bitmap.createBitmap(
                        bmp, 0, 0, bmp.width, bmp.height,
                        Matrix().apply { postRotate(90f) }, true
                    ) ?: bmp
                }
                val fitted = YuvUtil.fitBitmap(bmp, width.coerceAtLeast(1), height.coerceAtLeast(1))
                val mirrored = if (mirror) YuvUtil.mirrorBitmap(fitted) else fitted
                val nv21 = YuvUtil.bitmapToNv21(mirrored, width, height)
                val chain = LinkedHashSet<Bitmap>()
                chain.add(bmp); chain.add(fitted); chain.add(mirrored)
                for (b in chain) b.recycle()
                nv21
            } catch (t: Throwable) {
                null
            }
        }
    }

    /** 当前帧 Bitmap：远程模式取 RemoteCamSource 最新帧，照片模式取静态 Bitmap，本地模式按播放位置抽帧 */
    private fun currentFrameBitmap(): Bitmap? {
        return if (useRemote) {
            val f = RemoteCamSource.lastFrame ?: return null
            try {
                Bitmap.createBitmap(f)
            } catch (t: Throwable) {
                null
            }
        } else if (useImage) {
            try {
                imageBitmap?.let { Bitmap.createBitmap(it) }
            } catch (t: Throwable) {
                null
            }
        } else {
            try {
                retriever?.getFrameAtTime(currentPtsUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (t: Throwable) {
                null
            }
        }
    }
}

/**
 * Camera2 中转器：
 * - 视频 reader（RGBA）：FrameStreamer（decoder）渲入，中转线程读帧 → lockCanvas 绘制到 App 目标 Surface
 * - drain reader（RGBA）：真实相机渲入（createCaptureSession 替换的 session 输出面），
 *   其回调线程立即 acquire+close 丢弃——防止真实取景画面进入中转线程（泄露），
 *   同时保证相机有消费端不阻塞
 * 双 producer 分离：decoder 与真实相机各占一个独立 reader，互不争 BufferQueue。
 */
class RelayStreamer(
    private val pfd: ParcelFileDescriptor,
    videoWidth: Int,
    videoHeight: Int,
    private val appSurface: Surface,
) {

    // 中转尺寸压到 720p 内，控制 copy 与绘制开销
    private val rw: Int = run {
        val max = 1280
        if (videoWidth <= max) videoWidth else max
    }
    private val rh: Int = run {
        val max = 720
        if (videoHeight <= max) videoHeight else max
    }

    private var reader: ImageReader? = null
    private var drainReader: ImageReader? = null
    private var streamer: FrameStreamer? = null
    private var worker: HandlerThread? = null
    private val running = AtomicBoolean(false)
    private var eglRenderer: EglSurfaceRenderer? = null
    // EGL 初始化失败后本实例永久降级 Canvas（防每帧重试刷日志）；新中转实例会重新尝试 EGL
    private var eglDisabled = false
    private var eglFailStreak = 0
    private var pathLogged = false
    // 目标面非 Canvas（如 MediaRecorder 编码器输入面）只报一次，避免每帧刷屏
    private var nonCanvasReported = false

    private fun logPathOnce(msg: String, warn: Boolean) {
        if (pathLogged) return
        pathLogged = true
        if (warn) com.awei.spoofer.xposed.XLog.w(msg)
        else com.awei.spoofer.xposed.XLog.i(msg)
    }

    /** @return 给 createCaptureSession 使用的"相机输出面"（drain reader surface） */
    fun start(): Surface? {
        if (!running.compareAndSet(false, true)) return null
        if (rw <= 0 || rh <= 0) return null
        val ht = HandlerThread("awei-relay").apply { start() }
        worker = ht

        // 视频 reader：decoder 渲入，中转线程读帧画到 appSurface
        val r = ImageReader.newInstance(rw, rh, PixelFormat.RGBA_8888, 3)
        reader = r
        // 内部面登记：ImageHook 的 acquire 抽帧对这些 reader 是纯浪费，全部跳过
        com.awei.spoofer.xposed.hooks.Camera2Hook.registerInternalSurface(r.surface)
        r.setOnImageAvailableListener({ rd ->
            var img: Image? = null
            try {
                img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
                drawToApp(img)
            } catch (t: Throwable) {
            } finally {
                try {
                    img?.close()
                } catch (t: Throwable) {
                }
            }
        }, Handler(ht.looper))

        // drain reader：真实相机渲入（session 输出面），帧立即丢弃
        val drain = ImageReader.newInstance(rw, rh, PixelFormat.RGBA_8888, 3)
        drainReader = drain
        com.awei.spoofer.xposed.hooks.Camera2Hook.registerInternalSurface(drain.surface)
        drain.setOnImageAvailableListener({ rd ->
            try {
                rd.acquireLatestImage()?.close()
            } catch (t: Throwable) {
            }
        }, Handler(ht.looper))

        return try {
            val s = FrameStreamer(pfd, r.surface) { pts -> StreamManager.updatePts(pts) }
            streamer = s
            s.start()
            drain.surface
        } catch (t: Throwable) {
            stop()
            null
        }
    }

    private fun drawToApp(img: Image) {
        if (!running.get() || !appSurface.isValid) return
        val src = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888)
        try {
            // rowStride 感知拷贝（1.1.17）：个别机型 RGBA 行跨度≠宽×4，整读画斜纹
            YuvUtil.copyRgbaPlane(img.planes[0], img.width, img.height, src)

            // EGL 首选（1.1.25）：GLConsumer 类目标面上 Canvas 会"投帧不上屏"
            // （MI 6/Adreno 实测预览砖红）；EGL swapBuffers 与解码器同一条供帧路径，
            // 且对 MediaRecorder 编码器输入面（Canvas 拒收）天然可用
            if (!eglDisabled) {
                if (eglRenderer == null) {
                    val r = EglSurfaceRenderer(appSurface, src, StreamManager.mirror)
                    if (r.init()) {
                        eglRenderer = r
                        logPathOnce(
                            "中转：绘制通道=EGL14/GLES20 硬件渲染 " +
                            "目标面=${r.surfaceWidth}x${r.surfaceHeight}", false
                        )
                    } else {
                        r.release()
                        eglDisabled = true
                        logPathOnce("中转：EGL 初始化失败，本实例降级 Canvas 兜底", true)
                    }
                }
                val r = eglRenderer
                if (r != null) {
                    if (r.updateFrame(src, StreamManager.mirror) && r.drawFrame()) {
                        eglFailStreak = 0
                        return
                    }
                    // 供帧失败（面失效/驱动异常）：释放后下一帧重建；连续失败降级 Canvas 防空转
                    r.release()
                    eglRenderer = null
                    if (++eglFailStreak >= 5) {
                        eglDisabled = true
                        logPathOnce("中转：EGL 连续供帧失败，降级 Canvas 兜底", true)
                    }
                    return
                }
            }

            // Canvas 兜底（仅 EGL 不可用时走）
            val canvas = try {
                appSurface.lockCanvas(null)
            } catch (t: Throwable) {
                null
            }
            if (canvas == null) {
                // 目标面不接受 Canvas 绘制——典型是 MediaRecorder 的 MediaCodec 编码器输入面
                // （只吃 YUV compressed-input，不收 RGBA 像素）。此处帧被丢弃，录制产物为黑屏；
                // 不再静默 return，打一条日志让排障者知道录像路径走到了（每目标面只报一次）
                if (!nonCanvasReported) {
                    nonCanvasReported = true
                    com.awei.spoofer.xposed.XLog.i(
                        "目标输出面非 Canvas（疑 MediaRecorder 编码器输入面）：" +
                        "录像产物为黑屏，伪造源未覆盖此路径"
                    )
                }
                return
            }
            try {
                val cw = canvas.width
                val ch = canvas.height
                if (cw > 0 && ch > 0) {
                    // 每帧都把当前 src 画上去：fit(center-crop) + 镜像合成一个 Matrix 直绘。
                    // 此前实现缓存命中时直接复用上一帧位图、新帧从未参与绘制，画面从第二帧起永久冻结
                    val m = Matrix()
                    val s = maxOf(cw.toFloat() / src.width, ch.toFloat() / src.height)
                    m.setScale(s, s)
                    m.postTranslate((cw - src.width * s) / 2f, (ch - src.height * s) / 2f)
                    if (StreamManager.mirror) m.postScale(-1f, 1f, cw / 2f, 0f)
                    canvas.drawBitmap(src, m, null)
                }
            } finally {
                try {
                    appSurface.unlockCanvasAndPost(canvas)
                } catch (t: Throwable) {
                }
            }
        } finally {
            src.recycle()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            streamer?.stop()
        } catch (t: Throwable) {
        }
        streamer = null
        try {
            reader?.close()
        } catch (t: Throwable) {
        }
        reader = null
        try {
            drainReader?.close()
        } catch (t: Throwable) {
        }
        drainReader = null
        // EGL 渲染器须在与 drawToApp 相同的线程释放（worker looper 即 ImageReader 回调线程）
        val w = worker
        if (w != null) {
            Handler(w.looper).post {
                eglRenderer?.release()
                eglRenderer = null
            }
        }
        try {
            w?.quitSafely()
        } catch (t: Throwable) {
        }
        worker = null
    }
}

/**
 * 视频 → Surface 硬件解码渲染循环（直连模式）。
 */
class FrameStreamer(
    private val pfd: ParcelFileDescriptor,
    private val surface: Surface,
    private val onPts: (Long) -> Unit,
) {

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = thread(name = "awei-frame-streamer") {
            var extractor: MediaExtractor? = null
            var decoder: MediaCodec? = null
            try {
                extractor = MediaExtractor()
                extractor.setDataSource(pfd.fileDescriptor)
                var trackIndex = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/")) {
                        trackIndex = i
                        format = f
                        break
                    }
                }
                if (trackIndex < 0 || format == null) return@thread
                extractor.selectTrack(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: return@thread

                decoder = MediaCodec.createDecoderByType(mime)
                decoder.configure(format, surface, null, 0)
                decoder.start()

                val bufferInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var lastPtsUs = 0L

                while (running.get() && !outputDone) {
                    if (!inputDone) {
                        val inIdx = decoder.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = decoder.getInputBuffer(inIdx)
                            if (buf == null) {
                                // 不可能发生（索引合法时），防御：归还 buffer 防泄漏
                                try {
                                    decoder.queueInputBuffer(inIdx, 0, 0, 0, 0)
                                } catch (t: Throwable) {
                                }
                            } else {
                                val sampleSize = extractor.readSampleData(buf, 0)
                                if (sampleSize < 0) {
                                    decoder.queueInputBuffer(
                                        inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                } else {
                                    val pts = extractor.sampleTime
                                    if (pts != lastPtsUs) {
                                        lastPtsUs = pts
                                        onPts(pts)
                                    }
                                    decoder.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    when (val outIdx = decoder.dequeueOutputBuffer(bufferInfo, 10_000)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                        MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {}
                        else -> {
                            if (outIdx >= 0) {
                                val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                decoder.releaseOutputBuffer(outIdx, true) // 渲染到 surface
                                if (eos) {
                                    // 循环播放：seek + flush 后从头续喂。flush 必须有——
                                    // 输出 EOS 后不 flush 直接喂新样本属规范外行为，部分机型
                                    // 解码器拒绝输入（画面冻结在尾帧，1.1.6 记录的隐患）
                                    outputDone = !running.get()
                                    if (running.get()) {
                                        try {
                                            decoder.flush()
                                        } catch (t: Throwable) {
                                        }
                                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                                        inputDone = false
                                        outputDone = false
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                // surface 失效（app 退出预览）等情况静默退出
            } finally {
                try {
                    decoder?.stop()
                } catch (t: Throwable) {
                }
                try {
                    decoder?.release()
                } catch (t: Throwable) {
                }
                try {
                    extractor?.release()
                } catch (t: Throwable) {
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        worker = null
    }
}

/**
 * 照片源中转器：定时把静态 Bitmap 绘制到 appSurface（~30fps）。
 * 真实相机帧由 drain reader 丢弃（startImageRelay 建），此处只负责绘制。
 */
class ImageStreamer(
    private val bitmap: Bitmap,
    private val appSurface: Surface,
    private val mirror: Boolean,
) {
    private val running = AtomicBoolean(false)
    private var worker: HandlerThread? = null
    private var eglRenderer: EglSurfaceRenderer? = null
    // EGL 初始化失败后本实例永久降级 Canvas（防每帧重试刷日志）；新流实例会重新尝试 EGL
    private var eglDisabled = false
    private var eglFailStreak = 0
    private var pathLogged = false
    private var nonCanvasReported = false

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val ht = HandlerThread("awei-image").apply { start() }
        worker = ht
        val h = Handler(ht.looper)
        val drawTask = object : Runnable {
            override fun run() {
                if (!running.get()) return
                drawToApp()
                if (running.get()) h.postDelayed(this, 33L) // ~30fps
            }
        }
        h.post(drawTask)
    }

    private fun logPathOnce(msg: String, warn: Boolean) {
        if (pathLogged) return
        pathLogged = true
        if (warn) com.awei.spoofer.xposed.XLog.w(msg)
        else com.awei.spoofer.xposed.XLog.i(msg)
    }

    private fun drawToApp() {
        if (!running.get() || !appSurface.isValid) return

        // EGL 首选（1.1.25）：GLConsumer 类目标面（SurfaceTexture 派生）上 Canvas 会
        // "投帧不上屏"——MI 6/Adreno 实测 lockCanvas 返非 null、绘制投递全程无错、
        // 预览仍显示未初始化脏纹理（砖红色）。EGL swapBuffers 与解码器同一条供帧路径
        if (!eglDisabled) {
            if (eglRenderer == null) {
                val r = EglSurfaceRenderer(appSurface, bitmap, mirror)
                if (r.init()) {
                    eglRenderer = r
                    logPathOnce(
                        "照片源：绘制通道=EGL14/GLES20 硬件渲染 " +
                        "目标面=${r.surfaceWidth}x${r.surfaceHeight}", false
                    )
                } else {
                    r.release()
                    eglDisabled = true
                    logPathOnce("照片源：EGL 初始化失败，本实例降级 Canvas 兜底", true)
                }
            }
            val r = eglRenderer
            if (r != null) {
                if (r.drawFrame()) {
                    eglFailStreak = 0
                    return
                }
                // 供帧失败（面失效/驱动异常）：释放后下一 tick 重建；连续失败降级 Canvas 防空转
                r.release()
                eglRenderer = null
                if (++eglFailStreak >= 5) {
                    eglDisabled = true
                    logPathOnce("照片源：EGL 连续供帧失败，降级 Canvas 兜底", true)
                }
                return
            }
        }

        // Canvas 兜底（仅 EGL 不可用时走）
        val canvas = try {
            appSurface.lockCanvas(null)
        } catch (t: Throwable) {
            null
        }

        if (canvas == null) {
            if (!nonCanvasReported) {
                nonCanvasReported = true
                com.awei.spoofer.xposed.XLog.w(
                    "照片源：Canvas lockCanvas 返 null 且 EGL 不可用，目标面无法供帧"
                )
            }
            return
        }

        try {
            val cw = canvas.width
            val ch = canvas.height
            if (cw > 0 && ch > 0) {
                val m = Matrix()
                val s = maxOf(cw.toFloat() / bitmap.width, ch.toFloat() / bitmap.height)
                m.setScale(s, s)
                m.postTranslate((cw - bitmap.width * s) / 2f, (ch - bitmap.height * s) / 2f)
                if (mirror) m.postScale(-1f, 1f, cw / 2f, 0f)
                canvas.drawBitmap(bitmap, m, null)
            }
        } finally {
            try {
                appSurface.unlockCanvasAndPost(canvas)
            } catch (t: Throwable) {
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        val w = worker
        worker = null
        if (w != null) {
            val h = Handler(w.looper)
            h.post {
                eglRenderer?.release()
                eglRenderer = null
                try {
                    w.quitSafely()
                } catch (t: Throwable) {
                }
            }
        }
    }
}
