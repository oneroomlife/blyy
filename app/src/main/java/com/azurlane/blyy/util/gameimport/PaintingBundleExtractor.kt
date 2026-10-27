package com.azurlane.blyy.util.gameimport

import android.content.Context
import android.util.Log
import com.azurlane.blyy.util.unity.TextureStream
import com.azurlane.blyy.util.unity.UnityBundleSession
import com.azurlane.blyy.util.unity.UnityTexture
import com.azurlane.blyy.util.unity.UnityTextureExtractor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 静态立绘资源包提取器（`painting` 目录下的 `_tex` 包）。
 *
 * 包内通常只有一张 2048 宽左右的大贴图（ASTC 4x4），提取为 `painting.png`，
 * 由 Compose 的图片组件直接展示，支持双指缩放查看细节。
 *
 * 注意：同名不带 `_tex` 的包是界面预制体（MeshImage / CanvasRenderer），
 * 里面没有像素数据，探测阶段就会判定为「空壳」并提示用户改选 `_tex` 包。
 */
@Singleton
class PaintingBundleExtractor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "PaintingExtractor"

        /** 立绘最长边上限：超过按比例缩图，避免超大图解码 OOM */
        const val DEFAULT_MAX_SIDE = 4096
    }

    data class Report(
        val fileName: String,
        val width: Int,
        val height: Int,
        val unityFormat: Int,
        val textureCount: Int,
        val elapsedMs: Long
    )

    /**
     * 提取 [bundleFile] 中最大的一张贴图为 [outputDir]/painting.png。
     *
     * @param id 资源 id：与贴图同名的贴图优先（同一包里偶尔会有 UI 碎图）
     * @param maxSide 最长边上限（0 = 原尺寸）
     */
    suspend fun extract(
        bundleFile: File,
        outputDir: File,
        id: String,
        maxSide: Int = DEFAULT_MAX_SIDE,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Report = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val session = UnityBundleSession.open(context, bundleFile) { fraction, message ->
            onProgress(fraction * 0.5f, message)
        }
        try {
            val textures = UnityTextureExtractor.list(session.serialized)
                .filter { it.width > 0 && it.height > 0 }
            if (textures.isEmpty()) throw IOException("资源包内没有可用的贴图")

            val chosen = pick(textures, id)
            onProgress(0.6f, "解码立绘 ${chosen.width}x${chosen.height}")
            outputDir.deleteRecursively()
            if (!outputDir.exists() && !outputDir.mkdirs()) throw IOException("无法创建目录：${outputDir.name}")

            val target = File(outputDir, AssetFiles.PAINTING_IMAGE)
            val (width, height) = UnityTextureExtractor.extract(
                stream = TextureStream { offset, size -> session.readResource(offset, size) },
                texture = chosen,
                outFile = target,
                maxSide = if (maxSide <= 0) 0 else maxSide
            )
            AssetMetaStore.writePainting(
                outputDir,
                PaintingAssetMeta(
                    file = AssetFiles.PAINTING_IMAGE,
                    width = width,
                    height = height,
                    unityFormat = chosen.format,
                    source = bundleFile.name,
                    createdAt = System.currentTimeMillis()
                )
            )
            onProgress(0.97f, "写入立绘信息")
            Log.i(TAG, "立绘提取完成：${bundleFile.name} → ${width}x$height")
            Report(
                fileName = AssetFiles.PAINTING_IMAGE,
                width = width,
                height = height,
                unityFormat = chosen.format,
                textureCount = textures.size,
                elapsedMs = System.currentTimeMillis() - started
            )
        } finally {
            runCatching { session.close() }
        }
    }

    /**
     * 选图策略：同 id 名优先 → 名字以 id 开头 → 面积最大。
     * 同一资源包里混着 UI 碎图时，按名字锁定能避免误取小图。
     */
    private fun pick(textures: List<UnityTexture>, id: String): UnityTexture {
        val lowerId = id.lowercase()
        val variantBase = GameAssetNaming.variantOf(id)?.first?.lowercase()
        val candidates = listOfNotNull(lowerId, variantBase)
        return textures.firstOrNull { it.name.lowercase() in candidates }
            ?: textures.firstOrNull { it.name.lowercase().startsWith(lowerId) }
            ?: textures.maxByOrNull { it.pixels }!!
    }
}
