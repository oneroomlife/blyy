package com.azurlane.blyy.util.unity

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 压缩纹理解码器（离屏 EGL + GLES3 + FBO）。
 *
 * 碧蓝航线把贴图压成 ASTC（Unity `TextureFormat` 48/50/…，即 ASTC 4x4 / 6x6 …），
 * 数据流式存放在资源包的 `.resS` 节点里。Android 没有「系统 API 直接解 ASTC」，
 * 但 GLES 3.x 设备可通过 `GL_KHR_texture_compression_astc_ldr` 上传压缩纹理，
 * 因此这里用「离屏上下文 + 全屏 quad + glReadPixels」把压缩纹理转成 Bitmap（可降采样）。
 *
 * 实测覆盖：
 * - Live2D 立绘包：ASTC 4x4（`48`）
 * - `char` SD 小人包：ASTC 6x6（`50`）——此前只映射 4x4，导致 SD 资源整包解码失败
 * - `painting` / `spinepainting` 包：ASTC 4x4（`48`）
 *
 * 顺带支持 ETC2 / ETC1（GLES3 核心格式），兼容部分老资源。
 */
object CompressedTextureDecoder {

    private const val TAG = "TexDecoder"

    /** GL_COMPRESSED_RGBA_ASTC_4x4_KHR … 12x12（RGBA 与 RGB 变体共用同一 GL 枚举） */
    private const val GL_ASTC_4X4 = 0x93B0
    private const val GL_ASTC_5X5 = 0x93B1
    private const val GL_ASTC_6X6 = 0x93B2
    private const val GL_ASTC_8X8 = 0x93B3
    private const val GL_ASTC_10X10 = 0x93B4
    private const val GL_ASTC_12X12 = 0x93B5

    /** GL_COMPRESSED_RGBA8_ETC2_EAC */
    private const val GL_ETC2_RGBA8 = 0x9278

    /** GL_COMPRESSED_RGB8_ETC2 */
    private const val GL_ETC2_RGB8 = 0x9273

    /** GL_ETC1_RGB8_OES（Unity 老包 TextureFormat=34） */
    private const val GL_ETC1_RGB8 = 0x8D64

    private const val EGL_OPENGL_ES3_BIT_KHR = 0x40

    private const val VERTEX_SHADER = """
        attribute vec2 aPos;
        uniform vec2 uUvScale;
        varying vec2 vUv;
        void main() {
            vUv = (aPos * 0.5 + 0.5) * uUvScale;
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
    """

    private const val FRAGMENT_SHADER = """
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uTex;
        void main() {
            gl_FragColor = texture2D(uTex, vUv);
        }
    """

    @Volatile
    private var astcSupported: Boolean? = null

    /**
     * Unity `TextureFormat` → GL 内部格式。
     *
     * Unity 里 ASTC 的 RGB / RGBA 变体（48..53 / 54..59）只是压缩端有没有保留 alpha，
     * GL 侧的压缩枚举完全相同，因此两组值映射到同一批常量。
     *
     * @return null 表示当前实现不支持该格式
     */
    fun glFormatOf(unityFormat: Int): Int? = when (unityFormat) {
        48, 54 -> GL_ASTC_4X4
        49, 55 -> GL_ASTC_5X5
        50, 56 -> GL_ASTC_6X6
        51, 57 -> GL_ASTC_8X8
        52, 58 -> GL_ASTC_10X10
        53, 59 -> GL_ASTC_12X12
        // ETC2_RGBA1（46）是 punch-through alpha，Android 只有 EAC RGBA8 可用，走同一枚举
        46, 47 -> GL_ETC2_RGBA8
        45 -> GL_ETC2_RGB8
        34 -> GL_ETC1_RGB8
        else -> null
    }

    /** 压缩格式的块尺寸（宽, 高）；未知返回 null */
    private fun blockSizeOf(internalFormat: Int): Pair<Int, Int>? = when (internalFormat) {
        GL_ASTC_4X4 -> 4 to 4
        GL_ASTC_5X5 -> 5 to 5
        GL_ASTC_6X6 -> 6 to 6
        GL_ASTC_8X8 -> 8 to 8
        GL_ASTC_10X10 -> 10 to 10
        GL_ASTC_12X12 -> 12 to 12
        GL_ETC2_RGBA8, GL_ETC2_RGB8, GL_ETC1_RGB8 -> 4 to 4
        else -> null
    }

    private fun roundUp(value: Int, block: Int): Int = ((value + block - 1) / block) * block

    /** 该 GL 压缩格式期望的数据字节数（含块对齐）；null 表示未知格式 */
    private fun expectedDataSize(internalFormat: Int, width: Int, height: Int): Int? {
        fun blocks(size: Int, block: Int): Int = (size + block - 1) / block
        return when (internalFormat) {
            GL_ASTC_4X4 -> blocks(width, 4) * blocks(height, 4) * 16
            GL_ASTC_5X5 -> blocks(width, 5) * blocks(height, 5) * 16
            GL_ASTC_6X6 -> blocks(width, 6) * blocks(height, 6) * 16
            GL_ASTC_8X8 -> blocks(width, 8) * blocks(height, 8) * 16
            GL_ASTC_10X10 -> blocks(width, 10) * blocks(height, 10) * 16
            GL_ASTC_12X12 -> blocks(width, 12) * blocks(height, 12) * 16
            GL_ETC2_RGBA8 -> blocks(width, 4) * blocks(height, 4) * 16
            GL_ETC2_RGB8, GL_ETC1_RGB8 -> blocks(width, 4) * blocks(height, 4) * 8
            else -> null
        }
    }

    /**
     * 设备是否支持 ASTC 4x4（结果缓存）。
     *
     * 通过一次极小的 EGL 上下文查询 `GL_EXTENSIONS`，避免依赖厂商的
     * `ConfigurationInfo` 字符串；任何异常都按「支持」处理，真正的失败
     * 仍由 [decode] 的 glGetError 兜底并抛出可读错误。
     */
    fun isSupported(): Boolean {
        astcSupported?.let { return it }
        val supported = runCatching {
            val extensions = probeExtensions().lowercase()
            extensions.contains("astc_ldr") || extensions.contains("texture_compression_astc")
        }.getOrDefault(true)
        astcSupported = supported
        return supported
    }

    /** 用最小 EGL 上下文读取 `GL_EXTENSIONS` */
    private fun probeExtensions(): String {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) throw IOException("EGL 不可用")
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) throw IOException("EGL 初始化失败")
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_NONE
            )
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) {
                throw IOException("没有 GLES3 配置")
            }
            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
            )
            if (context == EGL14.EGL_NO_CONTEXT) throw IOException("上下文创建失败")
            surface = EGL14.eglCreatePbufferSurface(
                display, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                throw IOException("makeCurrent 失败")
            }
            return GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        } finally {
            runCatching {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                    )
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            }
        }
    }

    /**
     * 解码压缩纹理为 Bitmap。
     *
     * @param data           压缩数据（长度取 `remaining()`）
     * @param width          原始宽度
     * @param height         原始高度
     * @param internalFormat GL 内部格式（见 [glFormatOf]）
     * @param maxSize        输出位图最长边上限（超过则等比降采样；0 表示保持原尺寸）
     */
    fun decode(
        data: ByteBuffer,
        width: Int,
        height: Int,
        internalFormat: Int,
        maxSize: Int = 2048,
        flipRects: List<PixelRect> = emptyList()
    ): Bitmap {
        require(width > 0 && height > 0) { "贴图尺寸非法：${width}x$height" }
        val dataSize = data.remaining()
        if (dataSize <= 0) throw IOException("压缩纹理数据为空")

        // 块压缩格式要求「块对齐后的精确字节数」：少 → 说明资源包截断；
        // 多 → GL 会报 INVALID_VALUE，这里按期望长度上传（多余补齐字节丢弃）
        val expected = expectedDataSize(internalFormat, width, height)
        if (expected != null && dataSize < expected) {
            throw IOException("压缩纹理数据不足：需要 $expected 字节，实际 $dataSize（${width}x$height）")
        }
        val uploadSize = if (expected != null) minOf(expected, dataSize) else dataSize

        var outW = width
        var outH = height
        if (maxSize in 1 until maxOf(width, height)) {
            val scale = maxSize.toFloat() / maxOf(width, height)
            outW = (width * scale).toInt().coerceAtLeast(1)
            outH = (height * scale).toInt().coerceAtLeast(1)
        }

        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        var fbo = 0
        var colorTex = 0
        var srcTex = 0
        var program = 0
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) throw IOException("无法获取 EGL 显示")
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) throw IOException("EGL 初始化失败")

            val cfgAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfig = IntArray(1)
            if (!EGL14.eglChooseConfig(display, cfgAttribs, 0, configs, 0, 1, numConfig, 0) || numConfig[0] == 0) {
                throw IOException("设备不支持 GLES3 配置")
            }
            val config = configs[0] ?: throw IOException("无法获取 EGL 配置")

            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
            )
            if (context == EGL14.EGL_NO_CONTEXT) throw IOException("EGL 上下文创建失败")

            surface = EGL14.eglCreatePbufferSurface(
                display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            if (surface == EGL14.EGL_NO_SURFACE) throw IOException("EGL Pbuffer 创建失败")
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                throw IOException("EGL makeCurrent 失败")
            }

            // ---- 离屏目标 FBO ----
            val ids = IntArray(1)
            GLES20.glGenFramebuffers(1, ids, 0)
            fbo = ids[0]
            GLES20.glGenTextures(1, ids, 0)
            colorTex = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTex)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, outW, outH, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, colorTex, 0
            )
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                throw IOException("FBO 不完整：0x${status.toString(16)}")
            }

            // ---- 上传压缩纹理 ----
            GLES20.glGenTextures(1, ids, 0)
            srcTex = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            // 部分驱动（实测 Adreno 740 + ASTC 6x6）对「宽高不是块整数倍」的
            // glCompressedTexImage2D 直接报 INVALID_VALUE。块网格本身是按 ceil 组织的，
            // 把尺寸向上补齐到块整数倍后数据量完全一致，只是采样需要按原尺寸收缩 UV。
            val block = blockSizeOf(internalFormat)
            val attempts = ArrayList<Pair<Int, Int>>(2).apply {
                add(width to height)
                if (block != null) {
                    val paddedW = roundUp(width, block.first)
                    val paddedH = roundUp(height, block.second)
                    if (paddedW != width || paddedH != height) add(paddedW to paddedH)
                }
            }
            var uploadedW = width
            var uploadedH = height
            var lastError = 0
            var uploaded = false
            for ((index, size) in attempts.withIndex()) {
                while (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
                    // 清空历史错误码
                }
                data.position(0)
                GLES30.glCompressedTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, internalFormat,
                    size.first, size.second, 0, uploadSize, data
                )
                val err = GLES20.glGetError()
                if (err == GLES20.GL_NO_ERROR) {
                    uploaded = true
                    uploadedW = size.first
                    uploadedH = size.second
                    break
                }
                lastError = err
                Log.w(
                    TAG,
                    "压缩纹理上传失败：format=$internalFormat ${size.first}x${size.second} " +
                        "size=$uploadSize（尝试 ${index + 1}/${attempts.size}）0x${err.toString(16)}"
                )
            }
            if (!uploaded) {
                throw IOException(
                    "当前设备 GPU 不支持该压缩纹理格式（Format=$internalFormat，${width}x$height，" +
                        "${uploadSize}B，GL 错误 0x${lastError.toString(16)}）；" +
                        "模拟器通常只实现了 ASTC 4x4，真机（Adreno/Mali）可正常解码"
                )
            }

            // ---- 着色器 + 全屏 quad ----
            program = buildProgram()
            GLES20.glUseProgram(program)
            val posLoc = GLES20.glGetAttribLocation(program, "aPos")
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
            // 块补齐时只采样原尺寸对应的部分，避免整图被拉伸
            GLES20.glUniform2f(
                GLES20.glGetUniformLocation(program, "uUvScale"),
                width.toFloat() / uploadedW,
                height.toFloat() / uploadedH
            )
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)

            val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            val buffer: FloatBuffer = ByteBuffer.allocateDirect(quad.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                    put(quad)
                    position(0)
                }
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 8, buffer)

            GLES20.glViewport(0, 0, outW, outH)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glFinish()

            // ---- 读回像素 ----
            val pixels = ByteBuffer.allocateDirect(outW * outH * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, outW, outH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            val readErr = GLES20.glGetError()
            if (readErr != GLES20.GL_NO_ERROR) throw IOException("glReadPixels 失败：0x${readErr.toString(16)}")

            val raw = ByteArray(outW * outH * 4)
            pixels.get(raw)

            // ⚠️ 行序：Unity 的压缩纹理数据是**自下而上**（OpenGL 约定）存放的。
            // 上传后「数据第 0 行」落在 v=0（帧缓冲底部），而 glReadPixels 也从底部
            // 开始返回，于是读回的第 0 行其实是图像的最后一行 —— 必须按行倒序写入
            // Bitmap（自上而下），否则整张贴图上下镜像：模型所有 UV 都取到错误的
            // 区域，最终渲染成一堆错位的色块（此前"导入后渲染异常"的根因）。
            val rowStride = outW * 4
            val argb = IntArray(outW * outH)
            for (row in 0 until outH) {
                val dstBase = (outH - 1 - row) * outW
                var si = row * rowStride
                var idx = dstBase
                val end = dstBase + outW
                while (idx < end) {
                    val r = raw[si].toInt() and 0xFF
                    val g = raw[si + 1].toInt() and 0xFF
                    val b = raw[si + 2].toInt() and 0xFF
                    val a = raw[si + 3].toInt() and 0xFF
                    argb[idx] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    si += 4
                    idx++
                }
            }
            // 图集 rotate:180/270 的像素补偿（在数组阶段做，避免不可变 Bitmap 无法 setPixels）
            if (flipRects.isNotEmpty()) {
                BitmapPixelOps.rotate180(
                    pixels = argb,
                    canvasWidth = outW,
                    canvasHeight = outH,
                    rects = flipRects,
                    scaleX = outW.toFloat() / width,
                    scaleY = outH.toFloat() / height
                )
            }
            return Bitmap.createBitmap(argb, outW, outH, Bitmap.Config.ARGB_8888)
        } finally {
            runCatching {
                if (program != 0) GLES20.glDeleteProgram(program)
                if (srcTex != 0) GLES20.glDeleteTextures(1, intArrayOf(srcTex), 0)
                if (colorTex != 0) GLES20.glDeleteTextures(1, intArrayOf(colorTex), 0)
                if (fbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                    )
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            }
        }
    }

    private fun buildProgram(): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw IOException("着色器链接失败：$log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IOException("着色器编译失败：$log")
        }
        return shader
    }
}
