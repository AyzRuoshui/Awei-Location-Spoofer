package com.awei.spoofer.camera

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import com.awei.spoofer.xposed.XLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 专用于向各类 Surface（特别是由 SurfaceTexture/TextureView 生成的硬件加速 Surface）
 * 进行 GLES 纹理贴图渲染的渲染器。
 *
 * 解决背景（1.1.25 实测定性）：由 SurfaceTexture 派生的 Surface（GLConsumer 消费端），
 * Canvas 路径（lockCanvas/unlockCanvasAndPost）在此类目标面上会"投帧不上屏"——
 * MI 6 (Adreno 540) 实测 lockCanvas 返非 null、绘制与投递全成功、预览仍是砖红色脏纹理。
 * 本渲染器走 EGL14 + GLES 2.0（与 MediaCodec/MediaPlayer 同一条标准供帧路径），
 * eglSwapBuffers 直接向 BufferQueue 提交 GPU 缓冲，上屏行为与解码器完全一致。
 *
 * 1.1.25 起所有中转绘制路径（照片/视频/远程源）均以本渲染器为首选通道，
 * Canvas 降级为 EGL 初始化失败时的兜底。
 */
class EglSurfaceRenderer(
    private val surface: Surface,
    private val bitmap: Bitmap,
    private val mirror: Boolean,
) {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var programId = 0
    private var textureId = 0
    private var aPositionLoc = -1
    private var aTexCoordLoc = -1
    private var uTextureLoc = -1

    private var vertexBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null

    var surfaceWidth = 0
        private set
    var surfaceHeight = 0
        private set

    fun init(): Boolean {
        if (!surface.isValid) return false
        return try {
            if (initEgl() && initProgram()) {
                // 首帧随 init 上传（构造参数即首帧）
                updateFrame(bitmap, mirror)
            } else false
        } catch (t: Throwable) {
            XLog.e("EglSurfaceRenderer 初始化失败: $t")
            release()
            false
        }
    }

    private fun initEgl(): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay === EGL14.EGL_NO_DISPLAY) {
            XLog.e("EGL 获取默认 Display 失败")
            return false
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            XLog.e("EGL 初始化失败")
            return false
        }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] <= 0) {
            XLog.e("EGL 配置查找失败")
            return false
        }
        val config = configs[0] ?: return false

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext === EGL14.EGL_NO_CONTEXT) {
            XLog.e("EGL 创建 Context 失败")
            return false
        }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, surfaceAttribs, 0)
        if (eglSurface === EGL14.EGL_NO_SURFACE) {
            XLog.e("EGL 创建 WindowSurface 失败: ${EGL14.eglGetError()}")
            return false
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            XLog.e("EGL eglMakeCurrent 失败")
            return false
        }
        return true
    }

    private fun initProgram(): Boolean {
        val vertexShaderCode = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        val fragmentShaderCode = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        if (vertexShader == 0 || fragmentShader == 0) return false

        programId = GLES20.glCreateProgram()
        if (programId == 0) return false
        GLES20.glAttachShader(programId, vertexShader)
        GLES20.glAttachShader(programId, fragmentShader)
        GLES20.glLinkProgram(programId)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            XLog.e("GL Program 链接失败: ${GLES20.glGetProgramInfoLog(programId)}")
            GLES20.glDeleteProgram(programId)
            programId = 0
            return false
        }

        aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
        uTextureLoc = GLES20.glGetUniformLocation(programId, "uTexture")

        val vertexCoords = floatArrayOf(
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f,
        )
        vertexBuffer = ByteBuffer.allocateDirect(vertexCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertexCoords)
                position(0)
            }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        return true
    }

    /**
     * 上传新帧并按当前面尺寸重算 center-crop/镜像纹理坐标。
     * 视频/远程源每帧调用（位图内容变化）；照片源 init 后不再调用。
     * 必须在与 drawFrame 同线程调用（EGL 上下文切换按线程生效）。
     */
    fun updateFrame(bmp: Bitmap, mirror: Boolean): Boolean {
        if (textureId == 0) return false
        return try {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false
            querySurfaceSize()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            // texImage2D 全量上传：帧尺寸变化时自动重建存储，无需关心旧尺寸
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

            val imageAspect = bmp.width.toFloat() / bmp.height.toFloat()
            // 面尺寸未知（query 返 0）时按图自身比例铺满（不裁切），待首帧 swap 后尺寸即就绪
            val surfaceAspect = if (surfaceWidth > 0 && surfaceHeight > 0)
                surfaceWidth.toFloat() / surfaceHeight.toFloat() else imageAspect
            var uMin = 0.0f
            var uMax = 1.0f
            var vMin = 0.0f
            var vMax = 1.0f

            if (imageAspect > surfaceAspect) {
                val scale = surfaceAspect / imageAspect
                val offset = (1.0f - scale) / 2.0f
                uMin = offset
                uMax = 1.0f - offset
            } else {
                val scale = imageAspect / surfaceAspect
                val offset = (1.0f - scale) / 2.0f
                vMin = offset
                vMax = 1.0f - offset
            }

            val leftU = if (mirror) uMax else uMin
            val rightU = if (mirror) uMin else uMax

            val texCoords = floatArrayOf(
                leftU, vMax,
                rightU, vMax,
                leftU, vMin,
                rightU, vMin,
            )
            texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(texCoords)
                    position(0)
                }
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun querySurfaceSize() {
        val w = IntArray(1)
        val h = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, w, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, h, 0)
        if (w[0] > 0 && h[0] > 0) {
            surfaceWidth = w[0]
            surfaceHeight = h[0]
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            XLog.e("Shader 编译失败: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    fun drawFrame(): Boolean {
        if (eglDisplay === EGL14.EGL_NO_DISPLAY || eglSurface === EGL14.EGL_NO_SURFACE) return false
        return try {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false
            querySurfaceSize()
            val vw = if (surfaceWidth > 0) surfaceWidth else 1
            val vh = if (surfaceHeight > 0) surfaceHeight else 1
            GLES20.glViewport(0, 0, vw, vh)
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(programId)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glUniform1i(uTextureLoc, 0)

            val vb = vertexBuffer ?: return false
            val tb = texCoordBuffer ?: return false

            GLES20.glEnableVertexAttribArray(aPositionLoc)
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vb)

            GLES20.glEnableVertexAttribArray(aTexCoordLoc)
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, tb)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPositionLoc)
            GLES20.glDisableVertexAttribArray(aTexCoordLoc)

            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } catch (t: Throwable) {
            false
        }
    }

    fun release() {
        try {
            if (eglDisplay === EGL14.EGL_NO_DISPLAY) return
            // GL 对象删除要求上下文 current；先恢复 current 再删
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            if (textureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                textureId = 0
            }
            if (programId != 0) {
                GLES20.glDeleteProgram(programId)
                programId = 0
            }
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface !== EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext !== EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            // 绝不能 eglTerminate：EGLDisplay 进程内全局（Hook 进程与目标 App 共享），
            // terminate 会连带销毁目标 App 自身的 EGL 资源
            eglDisplay = EGL14.EGL_NO_DISPLAY
        } catch (t: Throwable) {
        }
    }
}
