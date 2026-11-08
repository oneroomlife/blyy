package com.azurlane.blyy.ui.components

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.glutils.FileTextureData
import com.esotericsoftware.spine.Skeleton
import com.esotericsoftware.spine.attachments.MeshAttachment
import com.esotericsoftware.spine.attachments.RegionAttachment
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * ══════════════════════════════════════════════════════════════════════════════
 * SD 小人「可见内容」度量
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * 【要解决的问题】
 * Spine 官方的 `Skeleton.getBounds()` 返回的是「所有 attachment 顶点」的轴对齐包围盒：
 *  - 它不知道贴图里有多少透明像素（大量占位 region 整块是空的，但四边形很大）
 *  - 它不知道 slot 颜色 alpha=0（隐藏层）
 *  - 它把离体的武器/特效/装饰层也算进去
 * 于是「自适应缩放 = min(视口/包围盒)」会把真正可见的躯体压得很小——
 * 表现为「部分 SD 小人怎么放大都很小」。
 *
 * 【本文件的做法】
 * 1. [SpineAtlasAlpha]：扫描图集每张 page 的 PNG 不透明像素，得到每个 region 的
 *    可见占比与「不透明子矩形」（sprite 空间比例）。整块透明的占位 region 占比≈0，
 *    会被直接丢弃；半透明留白的 region 会被收紧到真实像素范围内。
 * 2. [SpineContentProbe]：逐 slot 取世界顶点，按 region 的不透明子矩形做四边形双线性
 *    裁剪，得到「这个部件真正画出来的范围」，权重 = 面积 × 可见占比。
 * 3. [ContentBoxTrimmer]：按可见权重剪掉离群碎屑（远离质心且总占比极小），
 *    避免个别特效挂点把包围盒撑大；同时不误伤爪子/武器等有实际像素的部件。
 * 4. [SpineHitTest]：同一套几何直接用于触摸命中（四边形 / 三角网），
 *    让交互区域贴合小人显示区域，而不是窗口矩形。
 *
 * 所有函数都只读骨架当前世界变换，不修改骨架状态。
 */

/** 骨骼坐标系下的轴对齐包围盒（y 向上，与 Spine 一致） */
internal data class ContentBox(
    val left: Float,
    val bottom: Float,
    val right: Float,
    val top: Float
) {
    val width: Float get() = right - left
    val height: Float get() = top - bottom
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (bottom + top) * 0.5f

    val isUsable: Boolean
        get() = width.isFinite() && height.isFinite() && width > 0.01f && height > 0.01f

    /** 宽高比（w/h）；退化时返回 0 */
    val aspect: Float get() = if (height > 0.01f) width / height else 0f

    fun union(other: ContentBox) = ContentBox(
        left = min(left, other.left),
        bottom = min(bottom, other.bottom),
        right = max(right, other.right),
        top = max(top, other.top)
    )

    companion object {
        fun of(boxes: Iterable<ContentBox>): ContentBox? {
            var l = Float.MAX_VALUE
            var b = Float.MAX_VALUE
            var r = -Float.MAX_VALUE
            var t = -Float.MAX_VALUE
            var any = false
            boxes.forEach { box ->
                if (!box.isUsable) return@forEach
                any = true
                if (box.left < l) l = box.left
                if (box.bottom < b) b = box.bottom
                if (box.right > r) r = box.right
                if (box.top > t) t = box.top
            }
            if (!any) return null
            return ContentBox(l, b, r, t)
        }
    }
}

/** 单个部件的包围盒 + 可见权重（面积 × 不透明占比） */
internal data class WeightedPart(
    val box: ContentBox,
    val weight: Float,
    /** 所属 slot 下标（用于多帧之间按部件合并运动包络） */
    val slot: Int = -1
)

/**
 * 按可见权重做离群修剪。
 *
 * 规则：先求加权质心，然后从「离质心最远」的部件开始，只要它的权重不超过
 * 允许丢弃的总量（`1 - keepRatio`）就直接丢掉，直到预算用完。
 *
 * - 大权重部件（武器、披风）永远保留——即使离得远，它的权重也超过预算
 * - 只有又远又小的碎屑（特效挂点、绑定位）会被剪掉
 */
internal object ContentBoxTrimmer {

    /** 默认保留 98.5% 的可见权重，最多剪掉 1.5% 的离群碎屑 */
    const val DEFAULT_KEEP_RATIO = 0.985f

    fun trim(parts: List<WeightedPart>, keepRatio: Float = DEFAULT_KEEP_RATIO): ContentBox? {
        if (parts.isEmpty()) return null
        val valid = parts.filter { it.weight > 0f && it.box.isUsable }
        if (valid.isEmpty()) return ContentBox.of(parts.map { it.box })

        var total = 0f
        var sumX = 0f
        var sumY = 0f
        valid.forEach { part ->
            total += part.weight
            sumX += part.box.centerX * part.weight
            sumY += part.box.centerY * part.weight
        }
        if (total <= 0f) return ContentBox.of(valid.map { it.box })

        val cx = sumX / total
        val cy = sumY / total
        val ordered = valid.sortedByDescending { part -> hypot(part.box.centerX - cx, part.box.centerY - cy) }

        var budget = total * (1f - keepRatio.coerceIn(0.5f, 1f))
        val kept = valid.toMutableList()
        ordered.forEach { part ->
            if (budget <= 0f) return@forEach
            if (part.weight > budget) return@forEach
            budget -= part.weight
            kept.remove(part)
        }
        if (kept.isEmpty()) return ContentBox.of(valid.map { it.box })
        return ContentBox.of(kept.map { it.box })
    }
}

/**
 * 图集 region 的可见信息。
 *
 * - [opacity]：不透明像素占比 0..1（1 = 整块画满）
 * - [u0]/[u1]：水平可见范围（sprite 空间比例，0=左、1=右）
 * - [v0]/[v1]：垂直可见范围（sprite 空间比例，0=底、1=顶，已按 y 向上翻转）
 */
internal data class RegionAlpha(
    val opacity: Float,
    val u0: Float = 0f,
    val u1: Float = 1f,
    val v0: Float = 0f,
    val v1: Float = 1f
) {
    /**
     * 子矩形是否可用（非退化）。
     *
     * 注意语义：全量 UV（u0=0,u1=1,v0=0,v1=1）同样返回 true —— 它表示
     * "可以按这个矩形取点"，而不是"矩形比全量小"。旋转打包的 region 由
     * [SpineAtlasAlpha] 直接给出全量 UV，因此这里不需要区分旋转。
     */
    val hasUsableSubRect: Boolean get() = (u1 - u0) > 0.01f && (v1 - v0) > 0.01f

    companion object {
        val FULL = RegionAlpha(1f)
    }
}

/** 部件分类：决定它是否参与取景框计算 */
internal enum class PartKind {
    /** 身体/服饰：取景的核心依据 */
    BODY,
    /** 手持武器：贴身时并入取景，飞出去时不并 */
    WEAPON,
    /** 特效层（fx_ / slash / warp / halo …）：**永不**参与取景 */
    EFFECT,
    /** 场景道具（canyin / table / 食物 …）：**永不**参与取景 */
    PROP,
    /** 无法识别：只在紧贴身体时并入 */
    OTHER
}

/** 一个部件所属的 slot / attachment 名（分类器两个都看，slot 名泛化时靠 attachment 名兜底） */
internal data class PartNames(val slot: String?, val attachment: String?)

/**
 * 按部件名分类。
 *
 * 碧蓝航线 SD 骨骼的 slot 命名很稳定：`head` / `face_x` / `hair_x` / `body` /
 * `leg_x` / `hand_x` / `skirt_x` 是身体，`wep_*` 是武器，`fx_*` 是特效，
 * `canyin*` 是食堂道具。判定按 EFFECT → PROP → WEAPON → BODY 的优先级，
 * 所以 `fx_halo` 归特效、`wep_ring_L` 归武器（前缀匹配，不做包含匹配）。
 */
internal object SpinePartNaming {

    private val EFFECT_PREFIXES = arrayOf(
        "fx", "ef_", "efx", "effect", "glow", "halo", "slash", "warp", "hit",
        "circle", "flip", "smoke", "spark", "light", "trail", "splash", "dust",
        "star", "wave", "beam", "laser", "shock", "magic", "flash", "bubble",
        "cloud", "ripple", "fog", "aura", "flare", "shield_fx"
    )

    private val PROP_PREFIXES = arrayOf(
        "canyin", "bg", "background", "prop", "table", "chair", "bed",
        "food", "dish", "tray", "balloon", "furniture"
    )

    private val WEAPON_PREFIXES = arrayOf(
        "wep", "weapon", "sword", "gun", "spear", "shield", "bow", "blade",
        "axe", "hammer", "cannon", "torpedo", "plane", "missile"
    )

    private val BODY_PREFIXES = arrayOf(
        "head", "face", "hair", "brow", "mayuge", "eye", "ear", "hoho",
        "body", "leg", "arm", "hand", "skirt", "neck", "chest", "cloth",
        "dress", "sleeve", "foot", "thigh", "waist", "wing", "tail",
        "ribbon", "cape", "cloak", "hood", "hat", "horn", "band", "ahoge",
        "mouth", "nose", "cheek", "skin", "shoulder", "belly", "shoe",
        "boot", "sock", "glove", "scarf", "collar", "tie", "belt",
        "badge", "emblem", "flower", "sailor", "uniform"
    )

    fun kindOf(names: PartNames): PartKind {
        classify(names.slot)?.let { return it }
        classify(names.attachment)?.let { return it }
        return PartKind.OTHER
    }

    private fun classify(raw: String?): PartKind? {
        val name = raw?.lowercase()?.trim().orEmpty()
        if (name.isEmpty()) return null
        return when {
            EFFECT_PREFIXES.any { name.startsWith(it) } -> PartKind.EFFECT
            PROP_PREFIXES.any { name.startsWith(it) } -> PartKind.PROP
            WEAPON_PREFIXES.any { name.startsWith(it) } -> PartKind.WEAPON
            BODY_PREFIXES.any { name.startsWith(it) } -> PartKind.BODY
            else -> null
        }
    }
}

/**
 * 取景部件筛选：**框住角色本体**（身体 + 武器/舰装），不让离体特效和道具把框撑大。
 *
 * 【为什么需要它】
 * 实测 `char/2b_2`（Spine 3.8.99）的 `stand` 里，特效 slot 的 slot alpha 与骨骼缩放
 * 都是 1.0，几何却飞在身体之外：
 * ```
 *   fx_circle   box=(569,-158,1164,440)      595×598   ← 身体右侧 800 单位外
 *   fx_flip2    box=(-52,-407,110,1281)      162×1688  ← 上下各溢出 400+
 *   fx_slash1   box=(-77,-111,1057,1104)    1134×1215  ← 与身体重叠的整屏大罩子
 *   wep_manjuuhead2 box=(-35,-55,1475,966)  1509×1020  ← 武器特效甩出画面
 * ```
 * 这些件体积大、权重高，旧的 1.5% 权重修剪永远剪不掉，取景框于是变成 681×839：
 * 小人被压到 1/3 大小、重心还被拉到 (390,430) —— 即"偏小 + 位置偏移"。
 *
 * 现在的规则很简单：**EFFECT / PROP 一律不参与**，BODY/WEAPON/OTHER 全体并集。
 * 武器与舰装是角色的造型组成部分，必须算进来，否则大剑/炮塔会被视口裁掉；
 * 特效（斩击/光环/传送门）只是动画修饰，算进来只会把框撑大。
 * 名字完全不可用（件数太少）时返回 null，调用方回退到旧的权重修剪。
 */
internal object FrameBoxSelector {

    /** 少于这么多可用件就认为名字不可靠，交给旧逻辑 */
    private const val MIN_PARTS = 2

    /** 单件最大边超过可用件中位数的这个倍数就当离群大块丢掉（整屏背景/罩子） */
    private const val SIZE_OUTLIER_RATIO = 4f

    fun select(parts: List<WeightedPart>, namesOf: (Int) -> PartNames): ContentBox? {
        if (parts.size < MIN_PARTS) return null
        val kept = parts.filter { part ->
            when (SpinePartNaming.kindOf(namesOf(part.slot))) {
                PartKind.EFFECT, PartKind.PROP -> false
                else -> true
            }
        }
        if (kept.size < MIN_PARTS) return null
        val spans = kept.map { maxOf(it.box.width, it.box.height) }.sorted()
        val median = spans[spans.size / 2]
        val sized = if (median > 0f) {
            kept.filter { maxOf(it.box.width, it.box.height) <= median * SIZE_OUTLIER_RATIO }
        } else {
            kept
        }
        val usable = if (sized.size >= MIN_PARTS) sized else kept
        return ContentBox.of(usable.map { it.box })
    }
}

/**
 * 扫描图集贴图，统计每个 region 的不透明像素范围。
 *
 * 用 [Pixmap] 直接读盘上的 PNG（GL 纹理上传后 `textureData` 往往已释放），
 * 只在资源加载时执行一次；用 `pixels` 一次取回整块像素缓冲，避免逐像素 JNI 调用。
 */
internal object SpineAtlasAlpha {

    /** alpha 小于该值视为透明（PNG 边缘抗锯齿留下 1~8 的噪声） */
    private const val ALPHA_THRESHOLD = 12

    /** 大 region 抽稀采样的目标边长（像素），兼顾精度与耗时 */
    private const val SAMPLE_TARGET = 96

    fun read(atlas: TextureAtlas): Map<String, RegionAlpha> {
        val result = HashMap<String, RegionAlpha>(atlas.regions.size)
        // 同一张 page 上的 region 只解码一次像素
        val pageFiles = LinkedHashMap<String, FileHandle?>()
        val pageRegions = LinkedHashMap<String, MutableList<TextureAtlas.AtlasRegion>>()
        val regions = atlas.regions
        for (i in 0 until regions.size) {
            val region = regions[i]
            val file = pageFileOf(region)
            val key = file?.path() ?: "<none>"
            if (!pageFiles.containsKey(key)) pageFiles[key] = file
            pageRegions.getOrPut(key) { mutableListOf() }.add(region)
        }
        for ((key, group) in pageRegions) {
            val file = pageFiles[key]
            val pixmap = if (file == null) null else runCatching { Pixmap(file) }.getOrNull()
            if (pixmap == null) {
                group.forEach { result[it.name] = RegionAlpha.FULL }
                continue
            }
            try {
                val pageWidth = pixmap.width
                val pageHeight = pixmap.height
                if (pageWidth <= 0 || pageHeight <= 0) {
                    group.forEach { result[it.name] = RegionAlpha.FULL }
                    continue
                }
                val pixels = runCatching { pixmap.pixels.duplicate() }.getOrNull()
                group.forEach { region ->
                    result[region.name] = if (pixels == null) {
                        RegionAlpha.FULL
                    } else {
                        scanRegion(pixels, pageWidth, pageHeight, region)
                    }
                }
            } finally {
                pixmap.dispose()
            }
        }
        return result
    }

    private fun pageFileOf(region: TextureAtlas.AtlasRegion): FileHandle? {
        val data = runCatching { region.texture.textureData }.getOrNull() ?: return null
        return (data as? FileTextureData)?.fileHandle
    }

    private fun scanRegion(
        pixels: java.nio.ByteBuffer,
        pageWidth: Int,
        pageHeight: Int,
        region: TextureAtlas.AtlasRegion
    ): RegionAlpha {
        // rotate=true 时打包尺寸与显示尺寸是转置关系，直接取打包矩形
        val rectW = if (region.rotate) region.packedHeight else region.regionWidth
        val rectH = if (region.rotate) region.packedWidth else region.regionHeight
        if (rectW <= 0 || rectH <= 0) return RegionAlpha.FULL
        val left = region.regionX
        val top = region.regionY
        val limit = pixels.limit()

        val step = max(1, min(rectW, rectH) / SAMPLE_TARGET)
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var opaque = 0
        var total = 0

        var y = top
        while (y < top + rectH) {
            val rowBase = y * pageWidth
            var x = left
            while (x < left + rectW) {
                if (x >= 0 && y >= 0 && x < pageWidth && y < pageHeight) {
                    val index = (rowBase + x) * 4 + 3
                    if (index in 0 until limit) {
                        val alpha = pixels.get(index).toInt() and 0xFF
                        total++
                        if (alpha > ALPHA_THRESHOLD) {
                            opaque++
                            if (x < minX) minX = x
                            if (x > maxX) maxX = x
                            if (y < minY) minY = y
                            if (y > maxY) maxY = y
                        }
                    }
                }
                x += step
            }
            y += step
        }
        if (total <= 0) return RegionAlpha.FULL
        val opacity = opaque.toFloat() / total.toFloat()
        if (opaque <= 0 || minX > maxX || minY > maxY) {
            // 整块透明：保留 opacity=0，交由调用方丢弃该部件
            return RegionAlpha(opacity = 0f)
        }
        if (region.rotate) {
            // 旋转 90° 打包：sprite 空间是打包空间的转置，子矩形换算需要额外判定朝向，
            // 这里只用占比做权重，不做子矩形收紧（面积信息已经足够判断"是否可见"）。
            return RegionAlpha(opacity = opacity)
        }
        // 打包像素 → sprite 空间比例。外扩 0.5% 避免裁掉边缘。
        //
        // 【u 轴】方向唯一（左→右），可以直接收紧。
        //
        // 【v 轴】贴图行号与 UV 的 v 轴朝向取决于加载器约定（有的 v=0 在贴图顶部，
        // 有的在底部），这里无法在不读贴图元数据的前提下判定。若猜错，收紧后的
        // 包围盒会整体上/下偏移（比不收紧更糟）。因此 v 方向不做"取哪一半"的判定，
        // 而是把上下透明部分「对称」减掉：中心恒为 0.5，任何朝向约定下包围盒
        // 都不会偏移，同时仍然去掉了垂直方向的黑边。
        val padU = 0.005f
        val padV = 0.005f
        val u0 = ((minX - left) / rectW.toFloat() - padU).coerceIn(0f, 1f)
        val u1 = ((maxX + 1 - left) / rectW.toFloat() + padU).coerceIn(0f, 1f)
        val opaqueHeight = ((maxY + 1 - minY) / rectH.toFloat() + padV * 2f).coerceIn(0f, 1f)
        val vHalf = opaqueHeight / 2f
        return RegionAlpha(
            opacity = opacity,
            u0 = u0,
            u1 = u1,
            v0 = 0.5f - vHalf,
            v1 = 0.5f + vHalf
        )
    }
}

/**
 * 逐 slot 提取「真正画出来的范围」。
 *
 * RegionAttachment 用四边形四角做双线性插值，把不透明子矩形映射到骨骼空间；
 * MeshAttachment 直接取顶点包围盒（网格顶点本身已经是贴合形体的）。
 */
internal object SpineContentProbe {

    /** slot / attachment 颜色 alpha 小于该值视为不可见 */
    private const val ALPHA_EPS = 0.02f

    /** region 不透明占比小于该值视为纯占位贴图，直接丢弃 */
    private const val MIN_OPAQUE_RATIO = 0.012f

    fun frameParts(
        skeleton: Skeleton,
        alphaOf: (name: String?, path: String?) -> RegionAlpha
    ): List<WeightedPart> {
        val parts = ArrayList<WeightedPart>(skeleton.slots.size)
        val slots = skeleton.slots
        for (slotIndex in 0 until slots.size) {
            val slot = slots[slotIndex]
            val attachment = slot.attachment ?: continue
            val slotAlpha = slot.color?.a ?: 1f
            if (slotAlpha <= ALPHA_EPS) continue
            when (attachment) {
                is RegionAttachment -> {
                    val attAlpha = attachment.color?.a ?: 1f
                    if (attAlpha <= ALPHA_EPS) continue
                    val info = alphaOf(regionNameOf(attachment.region), attachment.path)
                    if (info.opacity < MIN_OPAQUE_RATIO) continue
                    val vertices = FloatArray(8)
                    val ok = runCatching {
                        attachment.computeWorldVertices(slot.bone, vertices, 0, 2)
                    }.isSuccess
                    if (!ok) continue
                    val box = regionContentBox(vertices, info)
                    if (!box.isUsable) continue
                    val weight = box.width * box.height * max(info.opacity, 0.02f) * slotAlpha * attAlpha
                    parts.add(WeightedPart(box, weight, slotIndex))
                }
                is MeshAttachment -> {
                    val attAlpha = attachment.color?.a ?: 1f
                    if (attAlpha <= ALPHA_EPS) continue
                    val info = alphaOf(regionNameOf(attachment.region), attachment.path)
                    if (info.opacity < MIN_OPAQUE_RATIO) continue
                    val len = attachment.worldVerticesLength
                    if (len < 4) continue
                    val vertices = FloatArray(len)
                    runCatching { attachment.computeWorldVertices(slot, 0, len, vertices, 0, 2) }
                    val box = boxOfVertices(vertices, len)
                    if (!box.isUsable) continue
                    val weight = box.width * box.height * max(info.opacity, 0.02f) * slotAlpha * attAlpha
                    parts.add(WeightedPart(box, weight, slotIndex))
                }
                else -> Unit
            }
        }
        return parts
    }

    private fun regionNameOf(region: com.badlogic.gdx.graphics.g2d.TextureRegion?): String? =
        (region as? TextureAtlas.AtlasRegion)?.name

    /**
     * 四边形（BL/UL/UR/BR）+ 不透明子矩形 → 骨骼空间包围盒。
     *
     * 双线性插值：P(u,v) = BL·(1-u)(1-v) + BR·u(1-v) + UL·(1-u)v + UR·uv
     * 取子矩形四角的 AABB。旋转四边形时 AABB 会略大于真实范围（保守，不会裁剪）。
     */
    private fun regionContentBox(vertices: FloatArray, info: RegionAlpha): ContentBox {
        if (!info.hasUsableSubRect) return boxOfVertices(vertices, 8)
        val u0 = info.u0
        val u1 = info.u1
        val v0 = info.v0
        val v1 = info.v1
        var l = Float.MAX_VALUE
        var b = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var t = -Float.MAX_VALUE
        for (corner in 0 until 4) {
            val u = if (corner == 0 || corner == 2) u0 else u1
            val v = if (corner < 2) v0 else v1
            val f00 = (1f - u) * (1f - v)
            val f10 = u * (1f - v)
            val f01 = (1f - u) * v
            val f11 = u * v
            val x = vertices[0] * f00 + vertices[6] * f10 + vertices[2] * f01 + vertices[4] * f11
            val y = vertices[1] * f00 + vertices[7] * f10 + vertices[3] * f01 + vertices[5] * f11
            if (x < l) l = x
            if (x > r) r = x
            if (y < b) b = y
            if (y > t) t = y
        }
        return ContentBox(l, b, r, t)
    }

    private fun boxOfVertices(vertices: FloatArray, length: Int): ContentBox {
        var l = Float.MAX_VALUE
        var b = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var t = -Float.MAX_VALUE
        var i = 0
        while (i + 1 < length) {
            val x = vertices[i]
            val y = vertices[i + 1]
            if (x.isFinite() && y.isFinite()) {
                if (x < l) l = x
                if (x > r) r = x
                if (y < b) b = y
                if (y > t) t = y
            }
            i += 2
        }
        if (l > r || b > t) return ContentBox(0f, 0f, 0f, 0f)
        return ContentBox(l, b, r, t)
    }
}

/**
 * 触摸命中测试：判断骨骼空间中的点是否落在「可见部件」内部。
 *
 * 与 [SpineContentProbe] 共用同一套几何：
 * - RegionAttachment → 四边形（4 顶点）内部判定
 * - MeshAttachment → 三角网内部判定
 *
 * 这样悬浮窗内的透明空隙（两腿之间、举手留下的空档）不再吞掉触摸事件。
 */
internal object SpineHitTest {

    private const val ALPHA_EPS = 0.02f

    /** 与 [SpineContentProbe] 保持一致：整块透明的占位贴图不参与命中 */
    private const val MIN_OPAQUE_RATIO = 0.012f

    fun hit(
        skeleton: Skeleton,
        x: Float,
        y: Float,
        alphaOf: (name: String?, path: String?) -> RegionAlpha
    ): Boolean {
        val slots = skeleton.slots
        for (slotIndex in 0 until slots.size) {
            val slot = slots[slotIndex]
            val attachment = slot.attachment ?: continue
            val slotAlpha = slot.color?.a ?: 1f
            if (slotAlpha <= ALPHA_EPS) continue
            when (attachment) {
                is RegionAttachment -> {
                    val attAlpha = attachment.color?.a ?: 1f
                    if (attAlpha <= ALPHA_EPS) continue
                    val info = alphaOf(regionNameOf(attachment.region), attachment.path)
                    if (info.opacity < MIN_OPAQUE_RATIO) continue
                    val vertices = FloatArray(8)
                    val ok = runCatching {
                        attachment.computeWorldVertices(slot.bone, vertices, 0, 2)
                    }.isSuccess
                    if (!ok) continue
                    if (info.hasUsableSubRect) {
                        if (pointInSubQuad(vertices, info, x, y)) return true
                    } else if (pointInConvexQuad(vertices, x, y)) {
                        return true
                    }
                }
                is MeshAttachment -> {
                    val attAlpha = attachment.color?.a ?: 1f
                    if (attAlpha <= ALPHA_EPS) continue
                    val len = attachment.worldVerticesLength
                    if (len < 6) continue
                    val vertices = FloatArray(len)
                    val ok = runCatching {
                        attachment.computeWorldVertices(slot, 0, len, vertices, 0, 2)
                    }.isSuccess
                    if (!ok) continue
                    val triangles = attachment.triangles
                    if (triangles == null || triangles.isEmpty()) {
                        if (pointInBox(vertices, len, x, y)) return true
                    } else {
                        var i = 0
                        while (i + 2 < triangles.size) {
                            val a = triangles[i].toInt() * 2
                            val b = triangles[i + 1].toInt() * 2
                            val c = triangles[i + 2].toInt() * 2
                            if (a + 1 < len && b + 1 < len && c + 1 < len) {
                                if (pointInTriangle(
                                        x, y,
                                        vertices[a], vertices[a + 1],
                                        vertices[b], vertices[b + 1],
                                        vertices[c], vertices[c + 1]
                                    )
                                ) {
                                    return true
                                }
                            }
                            i += 3
                        }
                    }
                }
                else -> Unit
            }
        }
        return false
    }

    private fun regionNameOf(region: com.badlogic.gdx.graphics.g2d.TextureRegion?): String? =
        (region as? TextureAtlas.AtlasRegion)?.name

    /** 四边形整体命中（BL/UL/UR/BR 顺序） */
    fun pointInConvexQuad(v: FloatArray, x: Float, y: Float): Boolean {
        val order = intArrayOf(0, 2, 4, 6)
        var sign = 0
        for (i in 0 until 4) {
            val i0 = order[i]
            val i1 = order[(i + 1) % 4]
            val cross = cross(v[i0], v[i0 + 1], v[i1], v[i1 + 1], x, y)
            if (abs(cross) < 1e-4f) continue
            val s = if (cross > 0f) 1 else -1
            if (sign == 0) sign = s else if (sign != s) return false
        }
        return true
    }

    /** 不透明子矩形命中：把点反解到 (u,v) 空间，与 [info] 的范围比较 */
    private fun pointInSubQuad(v: FloatArray, info: RegionAlpha, x: Float, y: Float): Boolean {
        // 四边形近似矩形：用两条边做基向量反解参数，再用双线性修正
        val ox = v[0]
        val oy = v[1]
        val ax = v[6] - v[0]
        val ay = v[7] - v[1]
        val bx = v[2] - v[0]
        val by = v[3] - v[1]
        val det = ax * by - ay * bx
        if (abs(det) < 1e-6f) return pointInConvexQuad(v, x, y)
        val px = x - ox
        val py = y - oy
        val u = (px * by - py * bx) / det
        val w = (ax * py - ay * px) / det
        // 四边形轻微非矩形时误差可忽略；越界直接判否
        if (u < -0.05f || u > 1.05f || w < -0.05f || w > 1.05f) return false
        return u in info.u0..info.u1 && w in info.v0..info.v1
    }

    private fun pointInBox(v: FloatArray, len: Int, x: Float, y: Float): Boolean {
        var l = Float.MAX_VALUE
        var b = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var t = -Float.MAX_VALUE
        var i = 0
        while (i + 1 < len) {
            val px = v[i]
            val py = v[i + 1]
            if (px.isFinite() && py.isFinite()) {
                if (px < l) l = px
                if (px > r) r = px
                if (py < b) b = py
                if (py > t) t = py
            }
            i += 2
        }
        return x in l..r && y in b..t
    }

    fun pointInTriangle(
        px: Float, py: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        x3: Float, y3: Float
    ): Boolean {
        val d1 = cross(x1, y1, x2, y2, px, py)
        val d2 = cross(x2, y2, x3, y3, px, py)
        val d3 = cross(x3, y3, x1, y1, px, py)
        val hasNeg = d1 < 0f || d2 < 0f || d3 < 0f
        val hasPos = d1 > 0f || d2 > 0f || d3 > 0f
        return !(hasNeg && hasPos)
    }

    private fun cross(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Float =
        (bx - ax) * (py - ay) - (by - ay) * (px - ax)
}
