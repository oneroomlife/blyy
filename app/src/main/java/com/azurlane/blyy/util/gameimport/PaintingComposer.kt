package com.azurlane.blyy.util.gameimport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import com.azurlane.blyy.util.unity.MeshRasterizer
import com.azurlane.blyy.util.unity.SerializedFile
import com.azurlane.blyy.util.unity.TextureStream
import com.azurlane.blyy.util.unity.UnityBundleSession
import com.azurlane.blyy.util.unity.UnityMesh
import com.azurlane.blyy.util.unity.UnityTexture
import com.azurlane.blyy.util.unity.UnityTextureExtractor
import com.azurlane.blyy.util.unity.UnityValues
import java.io.File
import java.io.IOException

/**
 * 静态立绘合成器：把「图集 + 网格 + 图层布局」还原成**游戏里看到的那张立绘**。
 *
 * 三种模式（从好到差依次尝试）：
 * 1. [Mode.LAYOUT] 有 prefab（`painting/<名字>.ys`）时：按 prefab 的
 *    `MeshImage` 图层 + RectTransform 布局逐层叠放（背景 / 人物 / 舰装 …），
 *    每层再用 [MeshRasterizer] 从图集还原。产物与游戏内一致。
 * 2. [Mode.MESH] 没有 prefab、但 `_tex` 包里有 `<名字>-mesh`：单层网格还原。
 *    碧蓝绝大多数立绘属于这一类——整张人物图被切成碎片打包进一张横图，
 *    网格就是拼回去的钥匙。
 * 3. [Mode.RAW] 既没有布局也没有网格：贴图本身就是成品（老资源），原样导出。
 *
 * 旧实现只有第 3 条，于是「碎片图集」被当成成品展示：人物碎成几十块、
 * 舰装与人错位——即用户看到的问题。这里把 1/2 补齐，3 只作兜底。
 */
internal class PaintingComposer(private val context: Context) {

    enum class Mode(val key: String) {
        /** 按 prefab 布局叠层 */
        LAYOUT("layout"),

        /** 单层网格还原 */
        MESH("mesh"),

        /** 原样贴图 */
        RAW("raw");

        companion object {
            fun fromKey(key: String?): Mode? = entries.firstOrNull { it.key == key }
        }
    }

    data class Outcome(
        val width: Int,
        val height: Int,
        val mode: Mode,
        /** 实际画上的图层数 */
        val layerCount: Int,
        /** 因缺包/缺图而跳过的图层数（prefab 里声明了但还原不出来） */
        val skippedLayers: Int,
        /** 主图集尺寸（排查「导入后是图集」用） */
        val atlasWidth: Int,
        val atlasHeight: Int,
        /** 主贴图的 Unity TextureFormat */
        val textureFormat: Int,
        /** 主包内的贴图数量（含未被使用的碎图） */
        val textureCount: Int,
        /** 完整性提示（null = 没发现问题） */
        val warning: String? = null
    )

    companion object {
        private const val TAG = "PaintingComposer"

        /** 网格还原结果小于该像素数时视为异常，退回整张贴图 */
        private const val MIN_USABLE_PIXELS = 64

        /** 布局模式下的画布上限（防异常 prefab 声明出几万像素的画布） */
        private const val MAX_CANVAS_SIDE = 8192

        /**
         * 画布未被图层覆盖的比例上限。
         *
         * prefab 的矩形通常比画面略大（留白/安全区），所以不能「有一像素空白就报警」；
         * 超过 15% 才判定为「可能缺层」——贝奇默认立绘实测差 23%（画布 979 高，
         * 网格只有 754），正是「少了头部」的那种比例。
         */
        private const val MIN_MESH_COVERAGE = 0.85f
    }

    /**
     * 合成一张立绘并写入 [outputFile]（PNG）。
     *
     * @param mainBundle 主资源包（`<名字>_tex`，扫描阶段就只收这个）
     * @param companions 同一次导入顺带复制的附属包：`painting/<名字>` prefab、`_rw` / `_front` 图层
     * @param maxSide    输出最长边上限（0 = 原尺寸）
     */
    fun compose(
        mainBundle: File,
        companions: List<File>,
        id: String,
        outputFile: File,
        maxSide: Int = 0,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Outcome {
        val opened = ArrayList<UnityBundleSession>()
        try {
            onProgress(0.02f, "打开立绘资源包")
            val mainSession = UnityBundleSession.open(context, mainBundle) { fraction, message ->
                onProgress(0.02f + fraction * 0.18f, message)
            }
            opened += mainSession
            val sources = ArrayList<Source>()
            sources += Source(mainBundle, mainSession)

            companions.forEach { file ->
                runCatching { UnityBundleSession.open(context, file) { _, _ -> } }
                    .onSuccess {
                        opened += it
                        sources += Source(file, it)
                    }
                    .onFailure { Log.w(TAG, "跳过附属资源包：${file.name}", it) }
            }

            onProgress(0.22f, "解析立绘结构")
            val mainTextures = sources.first().textures.size
            val prefab = sources.firstOrNull {
                it.textures.isEmpty() &&
                    it.serialized.objectsOfClass(SerializedFile.CLASS_GAME_OBJECT).isNotEmpty()
            }
            val plan = prefab?.let { PaintingLayout.parse(it.serialized) }
            if (plan != null) {
                val composed = runCatching {
                    composeLayout(plan, sources, outputFile, maxSide, mainTextures, onProgress)
                }.onFailure { Log.w(TAG, "布局合成失败，退回单层还原", it) }.getOrNull()
                if (composed != null) return composed
            }

            return composeSingle(sources.first(), sources, id, outputFile, maxSide, mainTextures, onProgress)
        } finally {
            opened.forEach { runCatching { it.close() } }
        }
    }

    // ------------------------------------------------------------------ 布局合成

    private fun composeLayout(
        plan: PaintingLayout.Plan,
        sources: List<Source>,
        outputFile: File,
        maxSide: Int,
        textureCount: Int,
        onProgress: (Float, String) -> Unit
    ): Outcome? {
        val frame = plan.frame()
        val longest = maxOf(frame.width, frame.height).coerceAtLeast(1)
        // 输出缩放：先受用户的最长边上限约束，再受画布硬上限约束
        var outScale = if (maxSide > 0) minOf(1f, maxSide.toFloat() / longest) else 1f
        if (longest * outScale > MAX_CANVAS_SIDE) {
            outScale = minOf(outScale, MAX_CANVAS_SIDE.toFloat() / longest)
        }
        val canvasWidth = maxOf(1, Math.round(frame.width * outScale))
        val canvasHeight = maxOf(1, Math.round(frame.height * outScale))
        val canvas = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        val drawer = Canvas(canvas)
        val paint = Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
            isDither = true
        }

        var drawn = 0
        var skipped = 0
        /** 所有图层里「网格覆盖率」的最小值（1 = 网格正好铺满声明的矩形） */
        var minMeshCoverage = 1f
        var atlasWidth = 0
        var atlasHeight = 0
        var format = 0

        plan.layers.forEachIndexed { index, layer ->
            onProgress(
                0.28f + 0.6f * (index.toFloat() / plan.layers.size.coerceAtLeast(1)),
                "合成图层 ${index + 1}/${plan.layers.size}"
            )
            val resolved = resolve(layer, sources)
            if (resolved == null) {
                skipped++
                return@forEachIndexed
            }
            // 覆盖率自检：MeshImage 声明的矩形（mRawSpriteSize / RectTransform）如果比
            // 实际能拿到的网格大，中间那段就没有几何体 —— 画面上表现为「少了上半身/头部」。
            // 贝奇默认立绘就是这种：画布 979 高，网格只有 754（77%）。
            if (layer.meshPathId != 0L && layer.height > 0f) {
                val mesh = findMesh(resolved.source, layer.meshPathId, sources)
                if (mesh != null && mesh.usable && mesh.height > 0f) {
                    minMeshCoverage = minOf(minMeshCoverage, mesh.height / layer.height)
                }
            }
            val bitmap = runCatching { buildLayerBitmap(resolved, layer, sources, maxSide) }
                .onFailure { Log.w(TAG, "图层 ${layer.name} 还原失败", it) }
                .getOrNull()
            if (bitmap == null) {
                skipped++
                return@forEachIndexed
            }
            atlasWidth = maxOf(atlasWidth, resolved.texture.width)
            atlasHeight = maxOf(atlasHeight, resolved.texture.height)
            if (format == 0) format = resolved.texture.format

            try {
                val padded = padToRaw(bitmap, layer, paint)
                try {
                    val place = PaintingLayout.place(layer, frame, outScale)
                    drawer.drawBitmap(
                        padded,
                        null,
                        RectF(
                            place.x.toFloat(),
                            place.y.toFloat(),
                            (place.x + place.width).toFloat(),
                            (place.y + place.height).toFloat()
                        ),
                        paint
                    )
                    drawn++
                } finally {
                    if (padded !== bitmap && !padded.isRecycled) padded.recycle()
                }
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }

        if (drawn == 0) {
            if (!canvas.isRecycled) canvas.recycle()
            return null
        }

        // 完整性自检：prefab 声明的画布（mRawSpriteSize）比「实际能画出来的图层包围盒」高出一截，
        // 说明顶部/底部有一段画布没有任何图层覆盖 —— 通常是该皮肤的某层资源包没下载全
        // （例如 char/painting 资源按需下载），此时立绘看起来就是「少了上半身/头部」。
        val warning = when {
            minMeshCoverage >= MIN_MESH_COVERAGE -> null
            else -> {
                val missing = Math.round((1f - minMeshCoverage) * 100)
                "顶部约 $missing% 没有几何体覆盖（该立绘可能还有未下载的图层包，" +
                    "建议先在游戏内打开一次再重新导入）"
            }
        }
        if (warning != null) Log.w(TAG, "立绘可能缺层：$warning（画布 ${canvasWidth}x$canvasHeight）")

        onProgress(0.93f, "写入立绘 PNG")
        writePng(canvas, outputFile)
        val result = Outcome(
            width = canvasWidth,
            height = canvasHeight,
            mode = Mode.LAYOUT,
            layerCount = drawn,
            skippedLayers = skipped,
            atlasWidth = atlasWidth,
            atlasHeight = atlasHeight,
            textureFormat = format,
            textureCount = textureCount,
            warning = warning
        )
        Log.i(TAG, "布局合成完成：${canvasWidth}x$canvasHeight，$drawn 层（跳过 $skipped）")
        return result
    }

    /** 单层：`_tex` 包内的网格还原；没有网格就整张贴图 */
    private fun composeSingle(
        main: Source,
        sources: List<Source>,
        id: String,
        outputFile: File,
        maxSide: Int,
        textureCount: Int,
        onProgress: (Float, String) -> Unit
    ): Outcome {
        val textures = main.textures.filter { it.width > 0 && it.height > 0 }
        if (textures.isEmpty()) throw IOException("资源包内没有可用的贴图")
        val chosen = pick(textures, id)

        val mesh = runCatching {
            main.serialized.objectsOfClass(SerializedFile.CLASS_MESH)
                .firstNotNullOfOrNull { UnityMesh.parse(main.serialized, it) }
        }.getOrNull()

        if (mesh != null && mesh.usable) {
            onProgress(0.45f, "按网格还原立绘 ${chosen.width}x${chosen.height}")
            val atlas = main.decode(chosen, maxSide)
            try {
                val pixels = IntArray(atlas.width * atlas.height)
                atlas.getPixels(pixels, 0, atlas.width, 0, 0, atlas.width, atlas.height)
                val raster = MeshRasterizer.rasterize(mesh, pixels, atlas.width, atlas.height)
                if (raster != null && raster.width * raster.height >= MIN_USABLE_PIXELS) {
                    var composed = Bitmap.createBitmap(
                        raster.pixels, raster.width, raster.height, Bitmap.Config.ARGB_8888
                    )
                    if (maxSide > 0) composed = limitSide(composed, maxSide)
                    val composedWidth = composed.width
                    val composedHeight = composed.height
                    onProgress(0.93f, "写入立绘 PNG")
                    writePng(composed, outputFile)
                    val outcome = Outcome(
                        width = composedWidth,
                        height = composedHeight,
                        mode = Mode.MESH,
                        layerCount = 1,
                        skippedLayers = 0,
                        atlasWidth = chosen.width,
                        atlasHeight = chosen.height,
                        textureFormat = chosen.format,
                        textureCount = textureCount
                    )
                    Log.i(TAG, "网格还原完成：${outcome.width}x${outcome.height}（图集 ${chosen.width}x${chosen.height}）")
                    return outcome
                }
            } finally {
                if (!atlas.isRecycled) atlas.recycle()
            }
        }

        onProgress(0.6f, "解码立绘 ${chosen.width}x${chosen.height}")
        val bitmap = main.decode(chosen, maxSide)
        val bitmapWidth = bitmap.width
        val bitmapHeight = bitmap.height
        onProgress(0.93f, "写入立绘 PNG")
        writePng(bitmap, outputFile)
        Log.i(TAG, "整图导出：${bitmapWidth}x$bitmapHeight（无网格/布局信息）")
        return Outcome(
            width = bitmapWidth,
            height = bitmapHeight,
            mode = Mode.RAW,
            layerCount = 1,
            skippedLayers = 0,
            atlasWidth = chosen.width,
            atlasHeight = chosen.height,
            textureFormat = chosen.format,
            textureCount = textureCount
        )
    }

    // ------------------------------------------------------------------ 辅助

    /** 一次打开的资源包：缓存对象索引 / 贴图列表，避免每个图层重复扫 */
    private class Source(val file: File, val session: UnityBundleSession) {
        val serialized: SerializedFile get() = session.serialized

        val byPath: Map<Long, SerializedFile.ObjectDef> by lazy {
            serialized.objects.associateBy { it.pathID }
        }

        val textures: List<UnityTexture> by lazy {
            UnityTextureExtractor.list(serialized).filter { it.width > 0 && it.height > 0 }
        }

        val textureByPath: Map<Long, UnityTexture> by lazy {
            textures.filter { it.pathId != 0L }.associateBy { it.pathId }
        }

        fun decode(texture: UnityTexture, maxSide: Int = 0): Bitmap =
            UnityTextureExtractor.decode(
                stream = TextureStream { offset, size -> session.readResource(offset, size) },
                texture = texture,
                maxSide = maxSide
            )
    }

    /** 一个图层解析出来的贴图 */
    private class Resolved(val source: Source, val texture: UnityTexture)

    /**
     * 图层 → 贴图：`m_Sprite` 是跨包指针，先按 pathID 在全部已打开包里找 Sprite，
     * 再用 `Sprite.m_RD.texture` 找到真正的 Texture2D。
     *
     * 找不到（例如装饰图层在没复制的 `ui/commonui_atlas` 里）就跳过——
     * 少一层装饰远好过整张图错位。
     */
    private fun resolve(layer: PaintingLayout.Layer, sources: List<Source>): Resolved? {
        for (source in sources) {
            val def = source.byPath[layer.spritePathId] ?: continue
            if (def.classId != SerializedFile.CLASS_SPRITE) continue
            val texture = textureOfSprite(source, def, sources) ?: continue
            return Resolved(source, texture)
        }
        Log.i(TAG, "图层 ${layer.name} 的 Sprite 不在已导入的包里，跳过")
        return null
    }

    private fun textureOfSprite(
        source: Source,
        spriteDef: SerializedFile.ObjectDef,
        sources: List<Source>
    ): UnityTexture? {
        val values = runCatching { source.serialized.readValues(spriteDef) }.getOrNull()
        val renderData = values?.get("m_RD") as? Map<*, *>
        val pointer = renderData?.get("texture") as? Map<*, *>
        val textureId = pointer?.let { UnityValues.number(it["m_PathID"]) } ?: 0L
        if (textureId != 0L) {
            source.textureByPath[textureId]?.let { return it }
            for (other in sources) {
                other.textureByPath[textureId]?.let { return it }
            }
        }
        // Sprite 没声明贴图（或声明在外部包）：包里只有一张大图时直接用它
        return source.textures.singleOrNull()
    }

    /**
     * 还原一个图层的像素。
     *
     * 图集解码上限用 [maxSide]（与输出上限一致）：两张 2048 宽的图集同时在内存里
     * 各占 16MB，低端机上很容易在「解码 → 取像素 → 光栅化」之间爆堆，
     * 因此取完像素立刻回收图集，只留下光栅化结果。
     */
    private fun buildLayerBitmap(
        resolved: Resolved,
        layer: PaintingLayout.Layer,
        sources: List<Source>,
        maxSide: Int
    ): Bitmap? {
        val atlas = resolved.source.decode(resolved.texture, maxSide)
        if (layer.meshPathId == 0L) return atlas
        val mesh = findMesh(resolved.source, layer.meshPathId, sources) ?: return atlas
        if (!mesh.usable) return atlas

        val pixels = IntArray(atlas.width * atlas.height)
        atlas.getPixels(pixels, 0, atlas.width, 0, 0, atlas.width, atlas.height)
        val atlasWidth = atlas.width
        val atlasHeight = atlas.height
        if (!atlas.isRecycled) atlas.recycle()

        val raster = MeshRasterizer.rasterize(mesh, pixels, atlasWidth, atlasHeight)
        if (raster == null || raster.width * raster.height < MIN_USABLE_PIXELS) return null
        return Bitmap.createBitmap(raster.pixels, raster.width, raster.height, Bitmap.Config.ARGB_8888)
    }

    private fun findMesh(preferred: Source, meshPathId: Long, sources: List<Source>): UnityMesh? {
        val order = ArrayList<Source>(sources.size)
        order += preferred
        sources.forEach { if (it !== preferred) order += it }
        for (source in order) {
            val def = source.byPath[meshPathId] ?: continue
            if (def.classId != SerializedFile.CLASS_MESH) continue
            return UnityMesh.parse(source.serialized, def)
        }
        return null
    }

    /**
     * 网格图垫到 Sprite 声明尺寸（`mRawSpriteSize`）。
     *
     * 网格包围盒往往比声明尺寸小几个像素（边缘留白），不垫就会在图层矩形里
     * 整体偏移；垫的方向是**底部对齐**——Unity 的 y 轴向上，留白在顶部。
     */
    private fun padToRaw(bitmap: Bitmap, layer: PaintingLayout.Layer, paint: Paint): Bitmap {
        val rawWidth = Math.round(layer.rawWidth)
        val rawHeight = Math.round(layer.rawHeight)
        if (rawWidth <= 0 || rawHeight <= 0) return bitmap
        if (bitmap.width >= rawWidth && bitmap.height >= rawHeight) return bitmap
        val padded = Bitmap.createBitmap(
            maxOf(rawWidth, bitmap.width),
            maxOf(rawHeight, bitmap.height),
            Bitmap.Config.ARGB_8888
        )
        Canvas(padded).drawBitmap(bitmap, 0f, (padded.height - bitmap.height).toFloat(), paint)
        return padded
    }

    private fun limitSide(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (maxSide <= 0 || longest <= maxSide) return bitmap
        val scale = maxSide.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            Math.round(bitmap.width * scale).coerceAtLeast(1),
            Math.round(bitmap.height * scale).coerceAtLeast(1),
            true
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun writePng(bitmap: Bitmap, outputFile: File) {
        try {
            outputFile.parentFile?.mkdirs()
            outputFile.outputStream().buffered().use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw IOException("PNG 编码失败：${outputFile.name}")
                }
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
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
        // 同名多尺寸（过场图包里 1920×1080 与 1024×576 都叫 bg_N）取面积最大的那份，
        // 「第一个匹配」会随机拿到低清预览图
        val named = textures.filter { it.name.lowercase() in candidates }
        val prefixed = textures.filter { it.name.lowercase().startsWith(lowerId) }
        return (named.ifEmpty { prefixed }).maxByOrNull { it.pixels }
            ?: textures.maxByOrNull { it.pixels }!!
    }
}
