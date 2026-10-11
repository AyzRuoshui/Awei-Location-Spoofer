package com.awei.remotecam

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 相机采集 + H.264 编码会话（远程 App 进程内，单会话多客户端扇出）。
 *
 * 采集：Camera1 + NV21 预览回调（无需预览显示面）。
 * 方向：portrait=true 时 NV21 顺时针旋转 90° 输出竖屏画面（视频通话主流形态），
 *       同时 NV21(VU交错) → NV12(UV交错)；false 时原样输出（远程手机横放用）。
 * 编码：MediaCodec ByteBuffer 输入（getInputImage 兼容各厂 YUV 布局），baseline。
 */
class CamSession(
    private val portrait: Boolean,
    private val onError: (String) -> Unit,
) {

    companion object {
        // 固定档位 720p@24fps@1.5Mbps——适配 3Mbps 中转服务器：
        // 均值 1.5M 占 50%、VBR 场景切换峰值 ~2.25M 仍留 25% 余量，
        // 避免 720p@2M 的 VBR 峰值（~3M）顶满服务器下行导致重传卡顿。
        // 480p/1080p 档已移除：4Mbps 以下带宽下 1080p 必然卡顿，480p 画质损失过大不划算。
        private const val W = 1280
        private const val H = 720
        private const val BITRATE = 1_500_000
        private const val FPS = 24
    }

    private interface Sender {
        fun send(type: Int, payload: ByteArray)
        val alive: Boolean
    }

    private val running = AtomicBoolean(false)
    private var camera: Camera? = null
    // @Volatile：isAlive/requestKeyframe 跨线程读；latch 超时路径无 happens-before 保护
    @Volatile
    private var encoder: MediaCodec? = null
    private var outThread: Thread? = null
    private val clients = CopyOnWriteArrayList<Sender>()

    // 采集专用线程：Camera.open 的回调绑定"调用线程的 Looper（无则主 Looper）"——
    // 之前在授权/连接线程里 open，onPreviewFrame 全部回落主线程，720p@24 的逐帧
    // 拷贝+UV 旋转（~34MB/s）把 remotecam 自己的 UI 拖卡。专线程 open 后回调在本
    // 线程处理，UI 零负担
    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null

    private var camW = 0
    private var camH = 0
    private var outW = 0
    private var outH = 0
    private var fps = 24

    @Volatile
    private var csd: ByteArray? = null
    @Volatile
    private var ptsUs = 0L

    val hasClients: Boolean get() = clients.isNotEmpty()

    /** 会话健康存活（start 成功且未停止）：授权端据此拒绝给死会话发幽灵 cam_grant */
    val isAlive: Boolean get() = running.get() && encoder != null

    /** 编码输出分辨率（授权应答上报用） */
    val outWidth: Int get() = outW
    val outHeight: Int get() = outH

    /** 输出端口号回调型发送器注册；返回解绑句柄 */
    fun attach(send: (Int, ByteArray) -> Unit, isAlive: () -> Boolean): () -> Unit {
        val s = object : Sender {
            override fun send(type: Int, payload: ByteArray) = send.invoke(type, payload)
            override val alive: Boolean get() = isAlive.invoke()
        }
        clients.add(s)
        csd?.let { s.send(com.awei.common.Proto.TYPE_CSD, it) }
        requestKeyframe()
        return { clients.remove(s) }
    }

    fun requestKeyframe() {
        try {
            encoder?.setParameters(Bundle().apply { putBoolean(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, true) })
        } catch (t: Throwable) {
        }
    }

    @SuppressLint("NewApi")
    fun start() {
        if (!running.compareAndSet(false, true)) return
        val ht = HandlerThread("awei-cam").apply { start() }
        camThread = ht
        val h = Handler(ht.looper)
        camHandler = h
        // open + 参数配置在采集线程执行（回调随之绑定该线程的 Looper）；
        // 调用方同步等待结果（open 通常数百 ms，授权线程等得起），失败经 onError 清场
        val latch = CountDownLatch(1)
        h.post {
            try {
                openAndConfigure()
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(8, TimeUnit.SECONDS)
        } catch (t: Throwable) {
        }
    }

    private fun openAndConfigure() {
        try {
            // 各阶段检查 running：latch 8s 超时后 stop() 可能已跑完，若不检查，
            // 迟到的 open 结果会创建出无人管理的相机/编码器（泄漏，只能靠 finalizer）
            if (!running.get()) return
            val p = Triple(W, H, BITRATE)
            fps = FPS

            camera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK).also { cam ->
                val params = cam.parameters
                val size = pickSize(params, p.first, p.second)
                camW = size.first
                camH = size.second
                params.setPreviewSize(camW, camH)
                params.previewFormat = android.graphics.ImageFormat.NV21
                try {
                    // 帧率档必须从设备支持列表里选：固定档位不支持的 HAL 会在
                    // parameters 赋值时才抛（不是 setPreviewFpsRange 时），
                    // 直接硬塞默认档（720p@24）会让整场会话起不来
                    val want = fps * 1000
                    val chosen = params.supportedPreviewFpsRange
                        .firstOrNull { it[0] == want && it[1] == want }
                        ?: params.supportedPreviewFpsRange.minByOrNull { Math.abs(it[0] - want) }
                    if (chosen != null) params.setPreviewFpsRange(chosen[0], chosen[1])
                } catch (t: Throwable) {
                }
                cam.parameters = params
                cam.setDisplayOrientation(0)
                // 无显示面回调采集的标准姿势：dummy SurfaceTexture
                cam.setPreviewTexture(SurfaceTexture(0))
            }

            // latch 8s 超时后 stop() 可能已先行跑完（其 releaseCamera 扑空）：
            // 自释刚打开的相机，防无人管理的泄漏
            if (!running.get()) {
                try {
                    camera?.release()
                } catch (t: Throwable) {
                }
                camera = null
                return
            }

            // 输出尺寸：portrait 时旋转 90°（宽高互换）
            outW = if (portrait) camH else camW
            outH = if (portrait) camW else camH

            encoder = MediaCodec.createEncoderByType("video/avc").also { enc ->
                val fmt = MediaFormat.createVideoFormat("video/avc", outW, outH)
                fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                fmt.setInteger(MediaFormat.KEY_BIT_RATE, p.third)
                fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                enc.start()
                // 同理：stop 先行时自释放编码器
                if (!running.get()) {
                    try {
                        enc.stop()
                    } catch (t: Throwable) {
                    }
                    try {
                        enc.release()
                    } catch (t: Throwable) {
                    }
                }
            }

            outThread = Thread({ encodeLoop() }, "awei-enc-out").apply { start() }

            val bufSize = camW * camH * 3 / 2
            camera?.setPreviewCallbackWithBuffer(Camera.PreviewCallback { data, cam ->
                // 关键：回调缓冲必须归还，否则 HAL 耗尽启动时投入的 3 块初始缓冲后
                // onPreviewFrame 永久停发，远程画面冻结在头几帧
                try {
                    if (running.get()) {
                        feedEncoder(data)
                    }
                } catch (t: Throwable) {
                } finally {
                    try {
                        cam?.addCallbackBuffer(data)
                    } catch (t: Throwable) {
                    }
                }
            })
            repeat(3) { camera?.addCallbackBuffer(ByteArray(bufSize)) }
            camera?.startPreview()
            CamServerService.logLine("采集启动 ${camW}x${camH} → 输出 ${outW}x${outH}@$fps portrait=$portrait")
        } catch (t: Throwable) {
            onError("会话启动失败: $t")
            stop()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        clients.clear()
        // 相机释放同样回采集线程排队执行（Camera 释放与回调同线程最稳）；
        // 若 stop 本就发生在采集线程（如 open 失败路径），直接就地释放防自等死锁
        val ht = camThread
        val onCamThread = Thread.currentThread() === ht
        val h = camHandler
        if (h != null && !onCamThread) {
            val done = CountDownLatch(1)
            h.post {
                try {
                    releaseCamera()
                } finally {
                    done.countDown()
                }
            }
            try {
                done.await(3, TimeUnit.SECONDS)
            } catch (t: Throwable) {
            }
        } else {
            releaseCamera()
        }
        try {
            outThread?.interrupt()
        } catch (t: Throwable) {
        }
        outThread = null
        try {
            encoder?.stop()
        } catch (t: Throwable) {
        }
        try {
            encoder?.release()
        } catch (t: Throwable) {
        }
        encoder = null
        try {
            ht?.quitSafely()
        } catch (t: Throwable) {
        }
        camThread = null
        camHandler = null
        CamServerService.logLine("采集会话已停止")
    }

    private fun releaseCamera() {
        try {
            camera?.setPreviewCallbackWithBuffer(null)
        } catch (t: Throwable) {
        }
        try {
            camera?.stopPreview()
        } catch (t: Throwable) {
        }
        try {
            camera?.release()
        } catch (t: Throwable) {
        }
        camera = null
    }

    // ---------- 编码输入 ----------

    private var rotBuf: ByteArray? = null

    private fun feedEncoder(nv21: ByteArray) {
        val enc = encoder ?: return
        val idx = enc.dequeueInputBuffer(10_000)
        if (idx < 0) return
        val img = try {
            enc.getInputImage(idx)
        } catch (t: Throwable) {
            null
        }
        if (img == null) {
            try {
                enc.queueInputBuffer(idx, 0, 0, ptsUs, 0)
            } catch (t: Throwable) {
            }
            return
        }
        val buf = rotBuf
        try {
            if (portrait) {
                val out = buf ?: ByteArray(camW * camH * 3 / 2).also { rotBuf = it }
                rotateNv21(nv21, out)
                fillImage(out, img)
            } else {
                // Android Image 约定 plane[1]=U、plane[2]=V，而 NV21 是 (V,U) 交错：
                // 不旋转也必须做 NV21→NV12 的 UV 换序，否则横屏模式整画面偏色
                val out = buf ?: ByteArray(camW * camH * 3 / 2).also { rotBuf = it }
                swapNv21ToNv12(nv21, out)
                fillImage(out, img)
            }
        } catch (t: Throwable) {
            // 填帧异常（个别设备 plane stride 异常 BufferOverflow）：吞掉本帧，
            // 但 input buffer 必须归还——已 dequeue 不还的话每帧漏一块，
            // 系统性发生会让编码器饥饿、画面永久冻结
            CamServerService.logLine("填帧异常(丢弃该帧): $t")
        }
        enc.queueInputBuffer(idx, 0, 0, ptsUs, 0)
        ptsUs += 1_000_000L / fps
    }

    /** NV21 顺时针旋转 90° → NV12（UV 交错换序）。
     *  目标尺寸 W'=源高 h、H'=源宽 w：dst[x*h + (h-1-y)] = src[y*w + x] */
    private fun rotateNv21(src: ByteArray, dst: ByteArray) {
        val w = camW
        val h = camH
        // Y 平面
        for (yy in 0 until h) {
            val srcRow = yy * w
            for (xx in 0 until w) {
                dst[xx * h + (h - 1 - yy)] = src[srcRow + xx]
            }
        }
        // UV 宏像素：目标宏(mx'=halfH-1-my, my'=mx)，行距 h 字节，NV21 对(V,U)→NV12 对(U,V)
        val uvSrc = w * h
        val halfH = h / 2
        val halfW = w / 2
        for (my in 0 until halfH) {
            val srcRow = my * halfW
            for (mx in 0 until halfW) {
                val si = uvSrc + (srcRow + mx) * 2
                val di = mx * h + (halfH - 1 - my) * 2
                dst[di] = src[si + 1]      // U
                dst[di + 1] = src[si]      // V
            }
        }
    }

    /** NV21 → NV12（不旋转，仅 Y 平面直拷 + UV 对 (V,U)→(U,V) 换序） */
    private fun swapNv21ToNv12(src: ByteArray, dst: ByteArray) {
        val ySize = camW * camH
        System.arraycopy(src, 0, dst, 0, ySize)
        var i = 0
        val n = src.size - ySize
        while (i + 1 < n) {
            dst[ySize + i] = src[ySize + i + 1]     // U
            dst[ySize + i + 1] = src[ySize + i]     // V
            i += 2
        }
    }

    /** NV12 → encoder InputImage（按 plane stride 写，兼容半平面/分离布局） */
    private fun fillImage(nv12: ByteArray, img: Image) {
        val yP = img.planes[0]
        val uP = img.planes[1]
        val vP = img.planes[2]
        val yBuf = yP.buffer
        val uvOff = outW * outH
        // Y
        for (row in 0 until outH) {
            yBuf.position(yP.rowStride * row)
            yBuf.put(nv12, row * outW, outW)
        }
        // UV：半平面共享 buffer（绝大多数设备）行级 bulk
        if (uP.buffer === vP.buffer && uP.pixelStride == 2) {
            val uv = uP.buffer
            for (row in 0 until outH / 2) {
                uv.position(uP.rowStride * row)
                uv.put(nv12, uvOff + row * outW, outW)
            }
        } else {
            for (row in 0 until outH / 2) {
                for (col in 0 until outW / 2) {
                    val si = uvOff + (row * outW / 2 + col) * 2
                    uP.buffer.position(uP.rowStride * row + uP.pixelStride * col)
                    uP.buffer.put(nv12, si, 1)
                    vP.buffer.position(vP.rowStride * row + vP.pixelStride * col)
                    vP.buffer.put(nv12, si + 1, 1)
                }
            }
        }
    }

    // ---------- 编码输出 ----------

    private fun encodeLoop() {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            val enc = encoder ?: break
            try {
                val idx = enc.dequeueOutputBuffer(info, 10_000)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val fmt = enc.outputFormat
                    val csd0 = fmt.getByteBuffer("csd-0")
                    val csd1 = fmt.getByteBuffer("csd-1")
                    if (csd0 != null) {
                        val arr = ByteArray(csd0.remaining() + (csd1?.remaining() ?: 0))
                        csd0.get(arr, 0, csd0.remaining())
                        if (csd1 != null) csd1.get(arr, arr.size - csd1.remaining(), csd1.remaining())
                        csd = arr
                        fanout(com.awei.common.Proto.TYPE_CSD, arr)
                    }
                } else if (idx >= 0) {
                    val buf = enc.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        val arr = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(arr, 0, info.size)
                        fanout(com.awei.common.Proto.TYPE_H264, arr)
                    }
                    enc.releaseOutputBuffer(idx, false)
                }
            } catch (t: Throwable) {
                if (running.get()) {
                    CamServerService.logLine("编码输出异常: $t")
                    // 编码线程死亡必须上抛重建：静默退出会留下"授权中但无帧"的死会话，
                    // 主端重连 attach 的还是这个死编码器 → 无限"重连成功但黑屏"
                    onError("编码器异常: $t")
                }
                break
            }
        }
    }

    private fun fanout(type: Int, payload: ByteArray) {
        if (clients.isEmpty()) return
        for (c in clients) {
            if (!c.alive) continue
            try {
                c.send(type, payload)
            } catch (t: Throwable) {
            }
        }
    }

    private fun pickSize(params: Camera.Parameters, wantW: Int, wantH: Int): Pair<Int, Int> {
        var best: Pair<Int, Int>? = null
        var bestScore = Long.MAX_VALUE
        for (s in params.supportedPreviewSizes) {
            val score = (s.width - wantW).toLong() * (s.width - wantW) +
                (s.height - wantH).toLong() * (s.height - wantH)
            if (score < bestScore) {
                bestScore = score
                best = Pair(s.width, s.height)
            }
        }
        return best ?: Pair(wantW, wantH)
    }
}
