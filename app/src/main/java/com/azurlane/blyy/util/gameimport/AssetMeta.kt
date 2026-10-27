package com.azurlane.blyy.util.gameimport

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** 资源目录内的约定文件名 */
object AssetFiles {
    /** Spine 侧车元信息（骨骼名 / 版本 / 页列表） */
    const val SPINE_META = "spine.json"

    /** 静态立绘侧车元信息 */
    const val PAINTING_META = "painting.json"

    /** 静态立绘图片文件名 */
    const val PAINTING_IMAGE = "painting.png"

    /** Live2D 侧车（由 AlLive2dExtractor 写出） */
    const val LIVE2D_SOURCE = "_blyy_source.json"
}

@Serializable
data class SpineSkeletonMeta(
    /** 骨骼主名（`<name>.skel` / `<name>.atlas`） */
    val name: String,
    /** Spine 二进制版本，如 `3.8.99`；null = 未识别 */
    val version: String? = null,
    /** 该骨骼引用的图集页文件名 */
    val pages: List<String> = emptyList()
)

@Serializable
data class SpineAssetMeta(
    /** [GameAssetKind.key] */
    val kind: String = GameAssetKind.SD_CHAR.key,
    /** 默认骨骼主名 */
    val primary: String = "",
    val skeletons: List<SpineSkeletonMeta> = emptyList(),
    /** 全部页的尺寸汇总（如 `2048x512`） */
    val textureSize: String? = null,
    /** 来源资源包名 */
    val source: String? = null,
    val createdAt: Long = 0L
)

@Serializable
data class PaintingAssetMeta(
    val file: String = AssetFiles.PAINTING_IMAGE,
    val width: Int = 0,
    val height: Int = 0,
    /** Unity TextureFormat（排查解码问题用） */
    val unityFormat: Int = 0,
    val source: String? = null,
    val createdAt: Long = 0L
)

/** 侧车元信息读写（容错优先：读失败当作「无侧车」，由调用方按目录内容推断） */
object AssetMetaStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun readSpine(dir: File): SpineAssetMeta? =
        runCatching {
            val file = File(dir, AssetFiles.SPINE_META)
            if (!file.isFile) return null
            json.decodeFromString<SpineAssetMeta>(file.readText())
        }.getOrNull()

    fun writeSpine(dir: File, meta: SpineAssetMeta) {
        runCatching {
            File(dir, AssetFiles.SPINE_META).writeText(json.encodeToString(SpineAssetMeta.serializer(), meta))
        }
    }

    fun readPainting(dir: File): PaintingAssetMeta? =
        runCatching {
            val file = File(dir, AssetFiles.PAINTING_META)
            if (!file.isFile) return null
            json.decodeFromString<PaintingAssetMeta>(file.readText())
        }.getOrNull()

    fun writePainting(dir: File, meta: PaintingAssetMeta) {
        runCatching {
            File(dir, AssetFiles.PAINTING_META)
                .writeText(json.encodeToString(PaintingAssetMeta.serializer(), meta))
        }
    }
}

/**
 * Spine 二进制文件头解析：`[varint len][hash][varint len][version]`。
 *
 * 只读版本号，不解析骨骼内容——导入时用于确认「这确实是 Spine 3.8 二进制」，
 * 版本不匹配（如 4.x）时给用户明确提示，而不是渲染黑屏后才排查。
 */
object SpineBinaryHeader {

    fun readVersion(bytes: ByteArray): String? {
        if (bytes.size < 4) return null
        var cursor = 0

        fun readVarint(): Int? {
            var shift = 0
            var value = 0
            while (true) {
                if (cursor >= bytes.size || shift > 28) return null
                val b = bytes[cursor++].toInt() and 0xFF
                value = value or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
        }

        /**
         * Spine 二进制的字符串编码是「长度 + 1」：
         * `0` = null、`1` = 空串、`n` = 长度 `n-1` 的 UTF-8 文本。
         * 少了这个 -1 就会把版本号读成乱码（hash 后面紧跟的 0x07 其实是 "3.8.99" 的 6+1）。
         */
        fun readString(): String? {
            val encoded = readVarint() ?: return null
            if (encoded <= 0) return null
            val length = encoded - 1
            if (length == 0) return ""
            if (cursor + length > bytes.size) return null
            val text = String(bytes, cursor, length, Charsets.UTF_8)
            cursor += length
            return text
        }

        // 第一段是 hash（base64 文本），第二段才是版本号
        readString() ?: return null
        val version = readString() ?: return null
        val sanitized = version.filter { it.isLetterOrDigit() || it == '.' || it == '-' }
        return sanitized.takeIf { it.isNotEmpty() && it.first().isDigit() }
    }

    /** 文件版本（读取失败返回 null） */
    fun readVersion(file: File): String? = runCatching {
        if (!file.isFile || file.length() < 16L) return null
        val head = file.inputStream().use { input ->
            val buffer = ByteArray(minOf(file.length(), 128L).toInt())
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n <= 0) break
                read += n
            }
            buffer
        }
        readVersion(head)
    }.getOrNull()

    /** 是否在 libgdx Spine 3.8 运行时可直接加载的版本区间 */
    fun isSupportedByRuntime(version: String?): Boolean {
        if (version.isNullOrBlank()) return true // 未知时不拦，交给运行时兜底
        return version.startsWith("3.8") || version.startsWith("3.7")
    }
}
