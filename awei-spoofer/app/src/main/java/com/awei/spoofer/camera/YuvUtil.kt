package com.awei.spoofer.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * 位图帧与各格式（NV21/NV12/JPEG）之间的转换，供预览绘制与拍照注入共用。
 */
object YuvUtil {

    fun bitmapToJpeg(bmp: Bitmap, quality: Int = 90): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    fun jpegToBitmap(bytes: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    /**
     * ARGB Bitmap → NV21 byte[]（Camera1 onPreviewFrame 标准格式）
     */
    fun bitmapToNv21(bmp: Bitmap, width: Int, height: Int): ByteArray {
        val argb = IntArray(width * height)
        bmp.getPixels(argb, 0, width, 0, 0, width, height)
        return argbToNv21(argb, width, height)
    }

    fun argbToNv21(argb: IntArray, width: Int, height: Int): ByteArray {
        val frameSize = width * height
        // NV21 UV 组数 = ceil(w/2) * ceil(h/2)，防御奇数尺寸越界
        val uvSize = 2 * ((width + 1) / 2) * ((height + 1) / 2)
        val yuv = ByteArray(frameSize + uvSize)
        var yIndex = 0
        var uvIndex = frameSize
        var index = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                // 注意 Kotlin 优先级：shr 高于 and，必须加括号提取分量
                val r = (argb[index] and 0xFF0000) shr 16
                val g = (argb[index] and 0xFF00) shr 8
                val b = argb[index] and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                yuv[yIndex++] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                    yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                }
                index++
            }
        }
        return yuv
    }

    /**
     * NV21 → JPEG（复用系统编码）
     */
    fun nv21ToJpeg(nv21: ByteArray, width: Int, height: Int, quality: Int = 90): ByteArray {
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, width, height), quality, out)
        return out.toByteArray()
    }

    fun imageToBitmap(image: Image): Bitmap? {
        return try {
            if (image.format == android.graphics.ImageFormat.JPEG) {
                val buf = image.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                jpegToBitmap(bytes)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 缩放+裁剪位图到目标尺寸（保持内容居中 cover 策略）
     */
    fun fitBitmap(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val sw = (src.width * scale).toInt().coerceAtLeast(w)
        val sh = (src.height * scale).toInt().coerceAtLeast(h)
        val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
        val x = (sw - w) / 2
        val y = (sh - h) / 2
        val cropped = Bitmap.createBitmap(scaled, x.coerceAtLeast(0), y.coerceAtLeast(0), w, h)
        if (cropped != scaled && scaled != src) scaled.recycle()
        return cropped
    }

    fun rotate(bmp: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bmp
        val m = Matrix()
        m.postRotate(degrees)
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /** 水平镜像（前置画面翻转，与预览路径保持一致） */
    fun mirrorBitmap(bmp: Bitmap): Bitmap {
        val m = Matrix()
        m.setScale(-1f, 1f, bmp.width / 2f, 0f)
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    fun toDirectByteBuffer(bytes: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { flip() }

    /**
     * RGBA Image.Plane → Bitmap（rowStride 感知，1.1.17）：个别机型 ImageReader 的
     * RGBA 行跨度 ≠ 宽×4（行对齐 padding），直接 copyPixelsFromBuffer 整读会把
     * padding 当像素画出斜纹；紧密排布（绝大多数机型）走快路径零开销。
     */
    fun copyRgbaPlane(plane: Image.Plane, width: Int, height: Int, out: Bitmap) {
        val buf = plane.buffer
        buf.rewind()
        val packed = width * 4
        if (plane.rowStride <= packed) {
            out.copyPixelsFromBuffer(buf)
            return
        }
        val tight = ByteArray(packed * height)
        for (y in 0 until height) {
            buf.position(y * plane.rowStride)
            buf.get(tight, y * packed, packed)
        }
        out.copyPixelsFromBuffer(ByteBuffer.wrap(tight))
    }
}
