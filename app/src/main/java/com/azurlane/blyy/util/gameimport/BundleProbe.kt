package com.azurlane.blyy.util.gameimport

import android.util.Log
import com.azurlane.blyy.util.unity.CompressedTextureDecoder
import com.azurlane.blyy.util.unity.SerializedFile
import com.azurlane.blyy.util.unity.UnityBundleSession
import com.azurlane.blyy.util.unity.UnityTexture
import com.azurlane.blyy.util.unity.UnityTextAssetReader
import com.azurlane.blyy.util.unity.UnityTextureExtractor
import com.azurlane.blyy.util.unity.UnityValues
import java.io.File

/**
 * 资源包内容探测：判断包里到底是 Live2D / Spine / 静态贴图 / 空壳预制体。
 *
 * 判定**以包内容为准**（路径只作为兜底提示）：
 * 1. MonoBehaviour 内出现 `MOC3` 魔数 → Live2D 皮肤包；
 * 2. 有 `*.skel` TextAsset（配 `*.atlas`） → Spine 骨骼包；
 * 3. 有足够大的 Texture2D → 静态立绘；
 * 4. 其余（只有 GameObject/CanvasRenderer/材质） → 预制体配置包，明确告知用户
 *    应该去选同名的 `_tex` / `_res` 包。
 */
internal object BundleProbe {

    private const val TAG = "BundleProbe"
    private const val MOC3_PROBE = 512
    private val MOC3_MAGIC = byteArrayOf(
        'M'.code.toByte(), 'O'.code.toByte(), 'C'.code.toByte(), '3'.code.toByte()
    )

    /** 贴图足够大才当作立绘（排除 UI 小图 / 特效碎图） */
    private const val MIN_PAINTING_SIDE = 512
    private const val MIN_PAINTING_PIXELS = 512L * 512L

    internal fun probe(session: UnityBundleSession, hintKind: GameAssetKind? = null): BundleProbeResult =
        probe(session.serialized, hintKind, session.bundleFile.name)

    /**
     * 纯解析入口：只依赖已打开的序列化文件，便于 JVM 单测直接喂真实资源包。
     *
     * @param bundleFileName 仅用于日志/错误提示
     */
    internal fun probe(
        sf: SerializedFile,
        hintKind: GameAssetKind? = null,
        bundleFileName: String? = null
    ): BundleProbeResult {
        val bundleName = readBundleName(sf)
        val pathKind = GameAssetKind.fromBundleName(bundleName) ?: hintKind

        // ---- 1. Live2D（MOC3 内嵌在 MonoBehaviour 里）----
        if (hasMoc3(sf)) {
            val textures = runCatching { UnityTextureExtractor.list(sf) }.getOrDefault(emptyList())
            return BundleProbeResult(
                format = GameBundleFormat.LIVE2D,
                kind = GameAssetKind.LIVE2D,
                bundleName = bundleName,
                textureCount = textures.size,
                textureMaxSide = textures.maxOfOrNull { it.maxSide } ?: 0,
                textureFormatLabel = textures.firstOrNull()?.let { formatLabel(it.format) }
            )
        }

        // ---- 2. Spine（.skel + .atlas TextAsset）----
        val textAssets = runCatching { readTextAssets(sf) }.getOrDefault(emptyList())
        val skeletons = textAssets.filter { it.name.endsWith(".skel", ignoreCase = true) }
        val atlases = textAssets.filter { it.name.endsWith(".atlas", ignoreCase = true) }
        if (skeletons.isNotEmpty()) {
            val bases = skeletons.map { it.name.substringBeforeLast('.') }.distinct()
            val matchedAtlases = bases.mapNotNull { base ->
                atlases.firstOrNull { it.name.substringBeforeLast('.') == base }?.name
            }
            // 有骨骼没图集（或反过来）时，仍按 Spine 处理，让提取器给出具体缺哪个文件
            val version = skeletons.firstOrNull()?.let { SpineBinaryHeader.readVersion(it.bytes) }
            val hasAtlas = atlases.isNotEmpty()
            val kind = pathKind ?: if (hasAtlas) GameAssetKind.SD_CHAR else GameAssetKind.DYNAMIC_SKIN
            return BundleProbeResult(
                format = GameBundleFormat.SPINE,
                kind = kind,
                bundleName = bundleName,
                skeletonBases = bases,
                spineVersion = version,
                atlasNames = atlases.map { it.name },
                textureCount = 0,
                reason = when {
                    !hasAtlas -> "缺少 .atlas 图集文件，无法渲染"
                    matchedAtlases.isEmpty() -> "骨骼与图集文件名不匹配，无法渲染"
                    else -> null
                }
            )
        }

        // ---- 3. 静态立绘（单张大贴图）----
        val textures = runCatching { UnityTextureExtractor.list(sf) }.getOrDefault(emptyList())
        val bigTextures = textures.filter {
            it.maxSide >= MIN_PAINTING_SIDE && it.pixels >= MIN_PAINTING_PIXELS
        }
        if (bigTextures.isNotEmpty()) {
            val largest = bigTextures.maxBy { it.pixels }
            return BundleProbeResult(
                format = GameBundleFormat.TEXTURE,
                kind = pathKind?.takeIf { it != GameAssetKind.LIVE2D } ?: GameAssetKind.PAINTING,
                bundleName = bundleName,
                textureCount = textures.size,
                textureMaxSide = largest.maxSide,
                textureFormatLabel = formatLabel(largest.format)
            )
        }

        // ---- 4. 空壳（预制体/材质）----
        val suffixHint = when {
            pathKind == GameAssetKind.PAINTING -> "请改选同名的 *_tex 资源包"
            pathKind == GameAssetKind.DYNAMIC_SKIN -> "请改选同名的 *_res 资源包"
            else -> "该包可能只是界面预制体，骨骼/贴图在配套资源包里"
        }
        val detail = when (val kind = pathKind) {
            null -> "未找到 moc3 / skel / 可渲染贴图"
            GameAssetKind.LIVE2D -> "未找到 MOC3 模型数据"
            else -> "未找到可渲染的骨骼或贴图"
        }
        Log.i(TAG, "空壳资源包：${bundleFileName ?: "-"}（$detail）")
        return BundleProbeResult(
            format = GameBundleFormat.PREFAB_ONLY,
            kind = pathKind,
            bundleName = bundleName,
            textureCount = textures.size,
            textureMaxSide = textures.maxOfOrNull { it.maxSide } ?: 0,
            reason = "$detail；$suffixHint"
        )
    }

    /** 贴图格式可读名（错误提示用） */
    fun formatLabel(unityFormat: Int): String {
        val gl = CompressedTextureDecoder.glFormatOf(unityFormat)
        return when {
            unityFormat == 4 -> "RGBA32"
            gl == null -> "Format=$unityFormat（不支持）"
            else -> "压缩格式 $unityFormat"
        }
    }

    private data class TextAsset(val name: String, val bytes: ByteArray)

    private fun readTextAssets(sf: SerializedFile): List<TextAsset> =
        sf.objectsOfClass(SerializedFile.CLASS_TEXT_ASSET).mapNotNull { def ->
            if (def.byteSize <= 0) return@mapNotNull null
            // 直接按布局取原始字节：TypeTree 把 m_Script 声明成 string，
            // 走 UTF-8 解码会把二进制骨骼毁掉（U+FFFD 替换）
            val asset = UnityTextAssetReader.read(sf, def) ?: return@mapNotNull null
            TextAsset(asset.name, asset.bytes)
        }

    private fun readBundleName(sf: SerializedFile): String? =
        sf.objectsOfClass(SerializedFile.CLASS_ASSET_BUNDLE).firstOrNull()?.let { def ->
            runCatching { sf.readValues(def)["m_Name"] as? String }.getOrNull()
        }

    /** MonoBehaviour 内是否存在 `MOC3` 魔数（与 AlLive2dExtractor 的判定一致） */
    private fun hasMoc3(sf: SerializedFile): Boolean {
        for (def in sf.objectsOfClass(SerializedFile.CLASS_MONO_BEHAVIOUR)) {
            if (def.byteSize < 64) continue
            val probe = runCatching { sf.peek(def, minOf(def.byteSize, MOC3_PROBE)) }.getOrNull() ?: continue
            if (indexOfMagic(probe) != null) return true
        }
        return false
    }

    private fun indexOfMagic(bytes: ByteArray): Int? {
        val limit = bytes.size - MOC3_MAGIC.size
        for (i in 0..limit) {
            if (bytes[i] == MOC3_MAGIC[0] && bytes[i + 1] == MOC3_MAGIC[1] &&
                bytes[i + 2] == MOC3_MAGIC[2] && bytes[i + 3] == MOC3_MAGIC[3]
            ) {
                return i
            }
        }
        return null
    }

    /** 是否为 UnityFS 资源包（供 SAF 选择器预筛选） */
    fun looksLikeBundle(file: File): Boolean =
        file.isFile && file.length() >= 128L && com.azurlane.blyy.util.unity.UnityFsBundle.detect(file)
}
