package com.azurlane.blyy.util.gameimport

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 静态立绘资源包提取器（`painting` 目录下的 `_tex` 包 + 可选附属包）。
 *
 * 【游戏本体的加载方式】
 * `painting/<名字>_tex` 里并不是一张成品图：贴图是**碎片图集**，真正的画面由
 * 包内的 `<名字>-mesh`（顶点 ↔ UV）拼出来；而 `painting/<名字>`（无后缀）是
 * 一棵 UI 预制体树，用 `MeshImage.m_Sprite` / `mMesh` 把多张图集（`_tex` 背景层、
 * `_rw` 人物层、`_front` 前景层…）按 RectTransform 布局叠起来。
 *
 * 因此提取链路分三级（详见 [PaintingComposer]）：
 * - 有 prefab：按布局多层合成 → 与游戏内一致的完整立绘；
 * - 只有本包：按包内网格单层还原 → 人物/舰装完整，只是没有额外图层；
 * - 都没有：贴图本身就是成品（老资源），原样导出。
 *
 * 产出一律是 `painting.png` + `painting.json`（记录还原方式/图层数/图集尺寸），
 * 查看器只认这两个文件，因此历史产物无需改目录结构。
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

    private val composer: PaintingComposer by lazy { PaintingComposer(context) }

    data class Report(
        val fileName: String,
        val width: Int,
        val height: Int,
        val unityFormat: Int,
        val textureCount: Int,
        val elapsedMs: Long,
        /** 还原方式（`layout` / `mesh` / `raw`，见 [PaintingComposer.Mode]） */
        val mode: String,
        val layerCount: Int,
        val skippedLayers: Int,
        val atlasWidth: Int,
        val atlasHeight: Int
    ) {
        /** 是否经过网格/布局还原（false = 贴图本身就是成品） */
        val composed: Boolean get() = mode != PaintingComposer.Mode.RAW.key
    }

    /**
     * 提取 [bundleFile]（以及同一次导入复制下来的 [companions]）为 [outputDir]/painting.png。
     *
     * @param companions `painting/<名字>` prefab、`_rw` / `_front` 等图层包；
     *   为空时退化为「只用本包网格还原」
     * @param maxSide 最长边上限（0 = 原尺寸）
     */
    suspend fun extract(
        bundleFile: File,
        outputDir: File,
        id: String,
        companions: List<File> = emptyList(),
        maxSide: Int = DEFAULT_MAX_SIDE,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Report = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        outputDir.deleteRecursively()
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw IOException("无法创建目录：${outputDir.name}")
        }
        val target = File(outputDir, AssetFiles.PAINTING_IMAGE)
        val outcome = composer.compose(
            mainBundle = bundleFile,
            companions = companions.filter { it.isFile && it.length() > 0L },
            id = id,
            outputFile = target,
            maxSide = if (maxSide <= 0) 0 else maxSide,
            onProgress = onProgress
        )

        onProgress(0.97f, "写入立绘信息")
        AssetMetaStore.writePainting(
            outputDir,
            PaintingAssetMeta(
                file = AssetFiles.PAINTING_IMAGE,
                width = outcome.width,
                height = outcome.height,
                unityFormat = outcome.textureFormat,
                mode = outcome.mode.key,
                layers = outcome.layerCount,
                skippedLayers = outcome.skippedLayers,
                atlas = if (outcome.atlasWidth > 0 && outcome.atlasHeight > 0) {
                    "${outcome.atlasWidth}x${outcome.atlasHeight}"
                } else {
                    null
                },
                source = bundleFile.name,
                createdAt = System.currentTimeMillis()
            )
        )
        Log.i(
            TAG,
            "立绘提取完成：${bundleFile.name} → ${outcome.width}x${outcome.height} " +
                "mode=${outcome.mode.key} layers=${outcome.layerCount}/${outcome.layerCount + outcome.skippedLayers}"
        )
        Report(
            fileName = AssetFiles.PAINTING_IMAGE,
            width = outcome.width,
            height = outcome.height,
            unityFormat = outcome.textureFormat,
            textureCount = outcome.textureCount,
            elapsedMs = System.currentTimeMillis() - started,
            mode = outcome.mode.key,
            layerCount = outcome.layerCount,
            skippedLayers = outcome.skippedLayers,
            atlasWidth = outcome.atlasWidth,
            atlasHeight = outcome.atlasHeight
        )
    }
}
