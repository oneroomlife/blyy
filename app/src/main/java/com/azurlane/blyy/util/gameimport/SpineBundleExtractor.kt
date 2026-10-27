package com.azurlane.blyy.util.gameimport

import android.content.Context
import android.util.Log
import com.azurlane.blyy.util.unity.SerializedFile
import com.azurlane.blyy.util.unity.PixelRect
import com.azurlane.blyy.util.unity.TextureStream
import com.azurlane.blyy.util.unity.UnityBundleSession
import com.azurlane.blyy.util.unity.UnityTextAssetReader
import com.azurlane.blyy.util.unity.UnityTexture
import com.azurlane.blyy.util.unity.UnityTextureExtractor
import com.azurlane.blyy.util.unity.UnityValues
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Spine 资源包提取器：`char`（SD 小人）与 `spinepainting`（动态皮肤）共用。
 *
 * 包内结构（碧蓝 2022.3 构建）：
 * ```
 * CAB-xxxx
 *  ├─ TextAsset "<name>.atlas"   # 图集描述（可能多页）
 *  ├─ TextAsset "<name>.skel"    # Spine 3.8 二进制骨骼
 *  └─ Texture2D "<pageName>"     # 每页一张 ASTC 贴图（数据在 .resS）
 * ```
 * 输出为 libgdx Spine 运行时可直接加载的目录：
 * ```
 * <out>/<name>.skel
 * <out>/<name>.atlas
 * <out>/<page>.png ...            # 文件名严格等于图集页名
 * <out>/spine.json                # 侧车元信息（版本/页/骨骼）
 * ```
 *
 * 两个坑点（都属于「提取成功但渲染黑屏」的根因）：
 * 1. 贴图必须按**图集页名**命名，否则 `TextureAtlas` 找不到图片；
 * 2. 贴图降采样时必须同步改写图集坐标，否则区域错位、模型撕裂。
 */
@Singleton
class SpineBundleExtractor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SpineBundleExtractor"

        /** 图集页最长边上限：0 = 按设备内存自适应（见 [recommendedMaxPageSide]） */
        const val DEFAULT_MAX_PAGE_SIDE = 0

        /**
         * 按 Java 堆大小挑选图集页上限。
         *
         * 4096² 的 ARGB 位图是 64MB，叠加 16MB 压缩数据与 PNG 编码缓冲，
         * 在 `memoryClass=192MB` 的设备上极易 OOM。因此：
         * - ≥512MB → 保持 4096（动态皮肤原分辨率）
         * - ≥256MB → 3072
         * - 其余 → 2048（图集坐标同比缩放，UV 仍然正确）
         */
        fun recommendedMaxPageSide(memoryClassMb: Int): Int = when {
            memoryClassMb >= 512 -> 4096
            memoryClassMb >= 256 -> 3072
            else -> 2048
        }
    }

    data class Report(
        /** 默认骨骼主名（查看器加载用） */
        val primary: String,
        val skeletons: List<SpineSkeletonMeta>,
        val spineVersion: String?,
        /** 写出的页文件（相对目录） */
        val pages: List<String>,
        /** 页尺寸汇总（如 4096x4096） */
        val textureSize: String?,
        val elapsedMs: Long
    ) {
        val skeletonNames: List<String> get() = skeletons.map { it.name }
    }

    /**
     * 提取 [bundleFile] 到 [outputDir]（目录会被清空重建）。
     *
     * @param id 目标资源 id（用于挑选默认骨骼：与 id 同名者优先）
     * @param maxPageSide 图集页最长边上限（0 = 不限制；超过时按比例缩图并改写图集）
     */
    suspend fun extract(
        bundleFile: File,
        outputDir: File,
        id: String,
        kind: GameAssetKind = GameAssetKind.SD_CHAR,
        maxPageSide: Int = DEFAULT_MAX_PAGE_SIDE,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Report = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val pageSideLimit = if (maxPageSide > 0) maxPageSide else memoryAwarePageSide()
        val session = UnityBundleSession.open(context, bundleFile) { fraction, message ->
            onProgress(fraction * 0.35f, message)
        }
        try {
            val sf = session.serialized
            onProgress(0.38f, "读取骨骼与图集")
            val texts = readTextAssets(sf)
            val skeletons = texts.filter { it.name.endsWith(".skel", ignoreCase = true) }
            if (skeletons.isEmpty()) {
                throw IOException("资源包内没有 .skel 骨骼文件（可能选到了预制体包，请改选同名 _res 包）")
            }
            val atlases = texts.filter { it.name.endsWith(".atlas", ignoreCase = true) }
            if (atlases.isEmpty()) throw IOException("资源包内没有 .atlas 图集文件")

            val textures = UnityTextureExtractor.list(sf)
            if (textures.isEmpty()) throw IOException("资源包内没有可用的贴图")

            outputDir.deleteRecursively()
            if (!outputDir.exists() && !outputDir.mkdirs()) throw IOException("无法创建目录：${outputDir.name}")

            val skeletonMetas = mutableListOf<SpineSkeletonMeta>()
            val allPages = mutableListOf<String>()
            var maxW = 0
            var maxH = 0
            var primaryVersion: String? = null

            skeletons.forEachIndexed { index, skelAsset ->
                val base = skelAsset.name.substringBeforeLast('.')
                val atlasAsset = atlases.firstOrNull { it.name.substringBeforeLast('.') == base }
                    ?: atlases.singleOrNull()
                    ?: throw IOException("骨骼 $base 找不到同名 .atlas 图集")
                val fractionBase = if (skeletons.size <= 1) 0.45f else 0.45f + 0.45f * index / skeletons.size
                onProgress(fractionBase, "导出骨骼 $base")

                val originalAtlas = String(atlasAsset.bytes, Charsets.UTF_8)
                // 先把 Spine4 风格的 rotate:90/180/270 归一化成 3.8 兼容写法，
                // 同时拿到需要做 180° 像素补偿的区域（坐标仍是原始贴图坐标）
                val rotation = SpineAtlasParser.normalizeRotations(originalAtlas)
                if (rotation.changedRegions > 0) {
                    Log.i(TAG, "图集 ${atlasAsset.name} 有 ${rotation.changedRegions} 个旋转区域需要归一化")
                }
                var pages = SpineAtlasParser.pages(rotation.text)
                if (pages.isEmpty()) throw IOException("图集 ${atlasAsset.name} 中没有页定义")

                // 页过大时按同一比例缩图 + 改写图集坐标
                val widest = pages.maxOf { max(it.width, it.height) }
                var atlasText = rotation.text
                if (pageSideLimit > 0 && widest > pageSideLimit) {
                    val factor = pageSideLimit.toFloat() / widest
                    atlasText = SpineAtlasParser.scale(atlasText, factor)
                    pages = SpineAtlasParser.pages(atlasText)
                    Log.i(
                        TAG,
                        "图集页过大（${widest}px），按 ${String.format(java.util.Locale.US, "%.2f", factor)} 缩放并改写坐标"
                    )
                }

                val version = SpineBinaryHeader.readVersion(skelAsset.bytes)
                if (version == null) {
                    throw IOException("骨骼 $base 头部解析失败（文本资源字节可能已损坏）")
                }
                if (primaryVersion == null) primaryVersion = version
                if (!SpineBinaryHeader.isSupportedByRuntime(version)) {
                    Log.w(TAG, "骨骼 $base 版本 $version 可能不被 libgdx Spine 3.8 运行时支持")
                }

                File(outputDir, "$base.skel").writeBytes(skelAsset.bytes)
                File(outputDir, "$base.atlas").writeText(atlasText, Charsets.UTF_8)

                val pageNames = mutableListOf<String>()
                val flipsByPage = rotation.flips.groupBy { it.page }
                pages.forEach { page ->
                    val pageFile = resolvePageFile(outputDir, page.name)
                    val texture = matchTexture(textures, page.name)
                        ?: throw IOException(
                            "图集页 ${page.name} 找不到对应贴图（包内贴图：${
                                textures.joinToString { it.name }.take(160)
                            }）"
                        )
                    val targetSide = if (pageSideLimit > 0 && widest > pageSideLimit) {
                        // 图集坐标已缩放，贴图按缩放后的页尺寸解码（保持 UV 一致）
                        max(page.width, page.height)
                    } else {
                        0
                    }
                    val pageFlips = flipsByPage[page.name].orEmpty().map {
                        PixelRect(it.x, it.y, it.width, it.height)
                    }
                    val (w, h) = UnityTextureExtractor.extract(
                        stream = TextureStream { offset, size -> session.readResource(offset, size) },
                        texture = texture,
                        outFile = pageFile,
                        maxSide = targetSide,
                        flips = pageFlips
                    )
                    maxW = maxOf(maxW, w)
                    maxH = maxOf(maxH, h)
                    pageNames += pageFile.name
                    allPages += pageFile.name
                }
                if (pageNames.isEmpty()) throw IOException("骨骼 $base 没有可导出的图集页")
                skeletonMetas += SpineSkeletonMeta(name = base, version = version, pages = pageNames)
            }

            val primary = pickPrimary(id, skeletonMetas.map { it.name })
            val textureSize = if (maxW > 0) "${maxW}x${maxH}" else null
            AssetMetaStore.writeSpine(
                outputDir,
                SpineAssetMeta(
                    kind = kind.key,
                    primary = primary,
                    skeletons = skeletonMetas,
                    textureSize = textureSize,
                    source = bundleFile.name,
                    createdAt = System.currentTimeMillis()
                )
            )
            onProgress(0.98f, "整理元信息")
            Report(
                primary = primary,
                skeletons = skeletonMetas,
                spineVersion = primaryVersion,
                pages = allPages.distinct(),
                textureSize = textureSize,
                elapsedMs = System.currentTimeMillis() - started
            )
        } finally {
            runCatching { session.close() }
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 设备可用堆（MB）→ 图集页上限 */
    private fun memoryAwarePageSide(): Int {
        val manager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as? android.app.ActivityManager
        val memoryClass = manager?.memoryClass ?: 192
        return recommendedMaxPageSide(memoryClass)
    }

    private data class TextAsset(val name: String, val bytes: ByteArray)

    private fun readTextAssets(sf: SerializedFile): List<TextAsset> =
        sf.objectsOfClass(SerializedFile.CLASS_TEXT_ASSET).mapNotNull { def ->
            if (def.byteSize <= 0) return@mapNotNull null
            // 必须读原始字节：.skel 是二进制，按 string 解码会整体损坏
            val asset = UnityTextAssetReader.read(sf, def) ?: return@mapNotNull null
            TextAsset(asset.name, asset.bytes)
        }

    /** 页名 → 输出文件（保持图集里的相对路径，禁止越界） */
    private fun resolvePageFile(outputDir: File, pageName: String): File {
        val relative = pageName.replace('\\', '/').trim().trimStart('/')
        if (relative.isEmpty()) throw IOException("图集页名为空")
        if (!relative.substringAfterLast('/').contains('.')) {
            throw IOException("图集页 $pageName 缺少扩展名，无法确定图片格式")
        }
        val out = File(outputDir, relative)
        val base = outputDir.canonicalPath + File.separator
        if (!out.canonicalPath.startsWith(base)) throw IOException("图集页路径越界：$pageName")
        out.parentFile?.mkdirs()
        return out
    }

    /** 贴图与页按名字匹配（忽略扩展名与大小写）；单页单图时允许兜底 */
    private fun matchTexture(textures: List<UnityTexture>, pageName: String): UnityTexture? {
        val pageBase = pageName.substringAfterLast('/').substringBeforeLast('.').lowercase()
        textures.firstOrNull { it.name.lowercase() == pageBase }?.let { return it }
        textures.firstOrNull { it.name.substringBeforeLast('.').lowercase() == pageBase }?.let { return it }
        return null
    }

    /** 默认骨骼：与资源 id 同名 > 最短名（去掉变体后缀） > 首个 */
    private fun pickPrimary(id: String, names: List<String>): String {
        if (names.isEmpty()) return id
        names.firstOrNull { it.equals(id, ignoreCase = true) }?.let { return it }
        val variantBase = GameAssetNaming.variantOf(id)?.first
        if (variantBase != null) {
            names.firstOrNull { it.equals(variantBase, ignoreCase = true) }?.let { return it }
        }
        return names.minByOrNull { it.length } ?: names.first()
    }
}
