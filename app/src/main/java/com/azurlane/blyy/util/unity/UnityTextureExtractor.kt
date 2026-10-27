package com.azurlane.blyy.util.unity

import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Unity `Texture2D` 的统一描述。
 *
 * 碧蓝航线的贴图全部走 `m_StreamData`（数据在资源包的 `.resS` 节点里），
 * 只有少数老包把像素直接内联在对象里（[streamSize] = 0）。
 */
internal data class UnityTexture(
    val name: String,
    val width: Int,
    val height: Int,
    /** Unity `TextureFormat`，见 [CompressedTextureDecoder.glFormatOf] */
    val format: Int,
    val streamOffset: Long,
    val streamSize: Long
) {
    val pixels: Long get() = width.toLong() * height
    val sizeLabel: String get() = "${width}x$height"
    val isStreamed: Boolean get() = streamSize > 0
    val maxSide: Int get() = maxOf(width, height)
}

/**
 * Texture2D 列表读取 + 解码落盘。
 *
 * 与 [AlLive2dExtractor] 内部实现合并而来，解决三处历史问题：
 * 1. `TextureFormat=50`（ASTC 6x6，`char` SD 包在用）此前没有映射 → 解码必然失败；
 * 2. RGBA32 路径直接 `copyPixelsFromBuffer` 少了一次上下翻转 → 贴图镜像；
 * 3. 解码出的 Bitmap 未及时 recycle（大图连续解码时峰值内存翻倍）。
 */
/** 页图片上需要旋转 180° 的矩形（像素坐标，原点左上；用于图集旋转语义归一化） */
data class PixelRect(val x: Int, val y: Int, val width: Int, val height: Int)

/** 贴图压缩数据读取器：把 `.resS` 里的一段读成 direct buffer */
internal fun interface TextureStream {
    fun read(offset: Long, size: Long): java.nio.ByteBuffer
}

internal object UnityTextureExtractor {

    private const val TAG = "UnityTexture"

    fun list(sf: SerializedFile): List<UnityTexture> = sf.objectsOfClass(SerializedFile.CLASS_TEXTURE2D)
        .mapNotNull { def ->
            runCatching { read(sf, def) }
                .onFailure { Log.w(TAG, "读取贴图描述失败: def=${def.pathID}", it) }
                .getOrNull()
        }

    private fun read(sf: SerializedFile, def: SerializedFile.ObjectDef): UnityTexture? {
        val values = sf.readValues(def)
        val name = values["m_Name"] as? String ?: ""
        val width = UnityValues.int(values["m_Width"]) ?: 0
        val height = UnityValues.int(values["m_Height"]) ?: 0
        val format = UnityValues.int(values["m_TextureFormat"]) ?: -1
        val stream = values["m_StreamData"] as? Map<*, *>
        val offset = (stream?.get("offset") as? Number)?.toLong() ?: 0L
        val size = (stream?.get("size") as? Number)?.toLong() ?: 0L
        if (width <= 0 || height <= 0) return null
        return UnityTexture(name, width, height, format, offset, size)
    }

    /**
     * 解码一张贴图并写成 PNG。
     *
     * @param maxSide 最长边上限：0 = 保持原尺寸；超过时等比降采样。
     *   Spine 图集必须传 0（图集坐标是绝对像素，缩放会让 UV 错位），
     *   Live2D / 静态立绘可传 2048 控制内存。
     * @return 实际写出的像素尺寸
     */
    fun extract(
        stream: TextureStream,
        texture: UnityTexture,
        outFile: File,
        maxSide: Int,
        flips: List<PixelRect> = emptyList()
    ): Pair<Int, Int> {
        if (!texture.isStreamed) {
            throw IOException("贴图 ${texture.name} 未流式存放（内联像素），暂不支持")
        }
        val limit = if (maxSide <= 0) 0 else maxSide
        val bitmap = decode(stream, texture, limit, flips)
        try {
            outFile.parentFile?.mkdirs()
            outFile.outputStream().buffered().use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw IOException("贴图 ${texture.name} 编码 PNG 失败")
                }
            }
            return bitmap.width to bitmap.height
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    fun decode(
        stream: TextureStream,
        texture: UnityTexture,
        maxSide: Int,
        flips: List<PixelRect> = emptyList()
    ): Bitmap {
        val buffer = stream.read(texture.streamOffset, texture.streamSize)
        if (texture.format == 4) {
            return decodeRgba32(buffer, texture, maxSide, flips)
        }
        val glFormat = CompressedTextureDecoder.glFormatOf(texture.format)
            ?: throw IOException(
                "贴图 ${texture.name} 使用了不支持的格式（Unity TextureFormat=${texture.format}）"
            )
        return CompressedTextureDecoder.decode(
            data = buffer,
            width = texture.width,
            height = texture.height,
            internalFormat = glFormat,
            maxSize = maxSide,
            flipRects = flips
        )
    }

    /**
     * RGBA32 贴图解码：Unity 数据自下而上（OpenGL 约定），必须翻转行序，
     * 否则与压缩纹理路径表现相反（整张图上下镜像）。
     */
    private fun decodeRgba32(
        buffer: java.nio.ByteBuffer,
        texture: UnityTexture,
        maxSide: Int,
        flips: List<PixelRect>
    ): Bitmap {
        val expected = texture.width * texture.height * 4
        if (buffer.remaining() < expected) {
            throw IOException("贴图 ${texture.name} 数据不足（${buffer.remaining()} < $expected）")
        }
        val raw = ByteArray(expected)
        buffer.get(raw, 0, expected)
        val argb = IntArray(texture.width * texture.height)
        for (row in 0 until texture.height) {
            val src = row * texture.width * 4
            val dst = (texture.height - 1 - row) * texture.width
            for (col in 0 until texture.width) {
                val p = src + col * 4
                val r = raw[p].toInt() and 0xFF
                val g = raw[p + 1].toInt() and 0xFF
                val b = raw[p + 2].toInt() and 0xFF
                val a = raw[p + 3].toInt() and 0xFF
                argb[dst + col] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        if (flips.isNotEmpty()) {
            BitmapPixelOps.rotate180(argb, texture.width, texture.height, flips, 1f, 1f)
        }
        // 超过上限时按比例降采样（RGBA32 多为老资源，保持与压缩路径一致的语义）
        val limit = if (maxSide <= 0) 0 else maxSide
        var bitmap = Bitmap.createBitmap(argb, texture.width, texture.height, Bitmap.Config.ARGB_8888)
        val longest = maxOf(bitmap.width, bitmap.height)
        if (limit in 1 until longest) {
            val scale = limit.toFloat() / longest
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
        return bitmap
    }


}
