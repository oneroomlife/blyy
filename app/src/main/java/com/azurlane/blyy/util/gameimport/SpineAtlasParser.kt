package com.azurlane.blyy.util.gameimport

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Spine `.atlas` 文本解析器（纯 Kotlin，无 Android 依赖，可直接单测）。
 *
 * 只做两件事，都是导入链路必须的：
 * 1. 找出图集里声明的**页（page）文件名与尺寸**——贴图落盘时必须用页名命名，
 *    否则 `TextureAtlas` 找不到图片，Spine 渲染直接黑屏；
 * 2. 在需要降采样贴图时，把页尺寸与区域坐标**同比缩放**，保证 UV 仍然正确
 *    （只缩图不改图集 = 区域错位、模型撕裂）。
 *
 * ## 关于 `rotate: 90/180/270`（重要，历史 bug 的根源）
 *
 * 早期版本认为「libgdx 只认 `true/false`，所以必须把 90/180/270 归一化」，
 * 于是改写 `rotate` 并在页像素上对相应矩形做 180° 翻转。这个前提是**错的**，
 * 而且会造成真实的错位：
 *
 * - `TextureAtlas` 把 `rotate: <数字>` 解析成 `degrees`（`Boolean.parseBoolean`
 *   那个坑只存在于更老的版本；当前依赖的 libgdx 1.13.5 走
 *   `region.degrees = Integer.parseInt(value)`，见 `TextureAtlasData`）。
 * - spine 3.8 的 `MeshAttachment.updateUVs()` 对 `degrees` 0/90/180/270 **四个值
 *   都有完整分支**，`RegionAttachment.updateOffset/setRegion` 支持 0/90。
 *   碧蓝动态皮肤里 `rotate:180/270` 的区域**全部**是 mesh attachment
 *   （实测 zhezhi_2 / zhuzi_2_DOA / zhaohe_4 三个包 100% 命中 mesh），
 *   原生运行时本来就能正确渲染。
 * - 归一化把 270 改成 `true`（=90）之后，`updateUVs` 的 case 90 用的是
 *   `origH - offY - packedW` 这种 90° 专用偏移；对 270 的正确采样矩形是
 *   `(origH, origW)`，两者相差 `2*offY`/`2*offX` 甚至整块错位
 *   （实测 `shadow_R1` 偏移 (+434, −613) px，`breast_R` (−48, +48) px），
 *   直接采到相邻部件的像素 → 「拼接错位 / 部件乱飞」。
 *
 * 因此现在**完全不改写 `rotate`，也不做任何像素翻转**：图集原样交给运行时。
 */
object SpineAtlasParser {

    /** 图集里的一页 */
    data class Page(
        /** 页文件名（如 `zhezhi_2.png`），与贴图文件同名 */
        val name: String,
        val width: Int,
        val height: Int,
        /** 该页上的区域（region）数量，仅用于展示 */
        val regionCount: Int
    )

    /**
     * 需要同比缩放的属性行前缀（页级 `size` 与区域级 `xy/size/orig/offset`）。
     *
     * 仅用于文档说明；实际改写按行号精确替换，见 [scale]。
     */
    private const val LF = "\n"
    private const val CR = "\r"
    private const val CRLF = "\r\n"
    private const val TAB_CHAR = '\t'

    /**
     * 解析图集页列表。
     *
     * Spine atlas 的结构是「页头（页名 + size/format/filter/repeat）+ 若干区域
     * （区域名 + 缩进属性）」，页名和区域名都在行首、都不含冒号，因此用
     * 「下一非空行是否为 `size:`」区分页头与区域。
     */
    fun pages(atlasText: String): List<Page> {
        val lines = splitLines(atlasText)
        val out = mutableListOf<Page>()
        var current: Page? = null
        var index = 0
        while (index < lines.size) {
            val raw = lines[index]
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("//")) {
                index++
                continue
            }
            val indented = isIndented(raw)
            val colon = line.indexOf(':')
            if (!indented && colon < 0) {
                val next = nextMeaningfulLine(lines, index + 1)
                if (next != null && next.trim().startsWith("size:", ignoreCase = true)) {
                    // 页头：先落上一页，再开新页
                    current?.let { out += it }
                    current = Page(name = line, width = 0, height = 0, regionCount = 0)
                } else {
                    // 区域名
                    current = current?.copy(regionCount = current.regionCount + 1)
                }
                index++
                continue
            }
            if (colon > 0) {
                val key = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                if (key == "size" && !indented && current != null) {
                    val dims = parseInts(value)
                    if (dims.size >= 2) {
                        current = current.copy(width = dims[0], height = dims[1])
                    }
                }
            }
            index++
        }
        current?.let { out += it }
        return out.filter { it.name.isNotBlank() }
    }

    /**
     * 按 [factor] 缩放图集里的像素尺寸（页尺寸 + 区域坐标）。
     *
     * 关键点 1：**区域的右/下边界由「缩放后的边界」反推**，而不是把 `xy` 与 `size`
     * 各自四舍五入——后者会让相邻区域之间出现 1~2px 的缝隙/重叠，在开启线性过滤的
     * 贴图上表现为「边缘发虚、部件错位」。这里按「原始矩形 → 缩放后矩形 → 反推宽高」
     * 计算，保证每个区域自身的包围盒严格闭合。
     *
     * 关键点 2：**页上足迹（footprint）与 `size` 的轴向关系随 rotations 变化**。
     * `.atlas` 的 `size` 是「未旋转」尺寸；页上实际占位是：
     * - 0 / 180 → (size.w, size.h)
     * - 90 / 270 → (size.h, size.w)
     * 缩放必须在**页上足迹**上做（乘同一个 factor），再把结果反算回 `size` 的轴向。
     * 只按 `size` 原样乘 factor 会在 90/270 区域上把宽高互换后再缩放，
     * 降采样时出现整块区域错位（本函数此前对 `rotate: 180/270` 也误判为「已旋转」，
     * 见 [RotationMode]）。
     *
     * factor >= 1 时原样返回，避免无意义改写。
     */
    fun scale(atlasText: String, factor: Float): String {
        if (factor >= 0.999f || factor <= 0f) return atlasText
        val usesCrlf = atlasText.contains(CRLF)
        val lines = splitLines(atlasText).toMutableList()

        class RegionScale {
            var xyIndex = -1
            var sizeIndex = -1
            var origIndex = -1
            var offsetIndex = -1
            /** 页上足迹是否交换宽高（仅在 90/270 时为 true） */
            var swapAxes = false
        }

        val regions = mutableListOf<RegionScale>()
        var current: RegionScale? = null
        val pageSizeIndices = mutableListOf<Int>()
        var index = 0
        while (index < lines.size) {
            val raw = lines[index]
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("//")) {
                index++
                continue
            }
            val indented = isIndented(raw)
            val colon = line.indexOf(':')
            if (!indented && colon < 0) {
                val next = nextMeaningfulLine(lines, index + 1)
                val isPageHeader = next != null && next.trim().startsWith("size:", ignoreCase = true)
                if (isPageHeader) {
                    current = null
                } else {
                    current = RegionScale().also { regions += it }
                }
                index++
                continue
            }
            if (colon > 0) {
                val key = line.substring(0, colon).trim().lowercase()
                if (key == "size" && !indented) {
                    pageSizeIndices += index
                    current = null
                } else {
                    val region = current
                    if (region != null && indented) {
                        when (key) {
                            "rotate" -> region.swapAxes =
                                RotationMode.parsesAxesSwapped(line.substring(colon + 1).trim())

                            "xy" -> region.xyIndex = index
                            "size" -> region.sizeIndex = index
                            "orig" -> region.origIndex = index
                            "offset" -> region.offsetIndex = index
                        }
                    }
                }
            }
            index++
        }

        // ---- 每页的页尺寸（多页图集逐页改写） ----
        for (pageSizeIndex in pageSizeIndices) {
            val dims = parseInts(valueOf(lines[pageSizeIndex]))
            if (dims.size >= 2) {
                writeValues(
                    lines,
                    pageSizeIndex,
                    max(1, (dims[0].toFloat() * factor).roundToInt()),
                    max(1, (dims[1].toFloat() * factor).roundToInt())
                )
            }
        }

        // ---- 区域矩形（按页上足迹缩放，再反推 size 轴向） ----
        for (region in regions) {
            val xy = if (region.xyIndex >= 0) parseInts(valueOf(lines[region.xyIndex])) else emptyList()
            val size = if (region.sizeIndex >= 0) parseInts(valueOf(lines[region.sizeIndex])) else emptyList()
            if (xy.size >= 2 && size.size >= 2) {
                // size 是未旋转尺寸；页上足迹在 90/270 时交换
                val footprintW = if (region.swapAxes) size[1] else size[0]
                val footprintH = if (region.swapAxes) size[0] else size[1]
                val left = (xy[0].toFloat() * factor).roundToInt()
                val top = (xy[1].toFloat() * factor).roundToInt()
                val right = max(left + 1, ((xy[0] + footprintW).toFloat() * factor).roundToInt())
                val bottom = max(top + 1, ((xy[1] + footprintH).toFloat() * factor).roundToInt())
                val scaledFootprintW = right - left
                val scaledFootprintH = bottom - top
                // 反推回 size 轴向（未旋转）
                val scaledW = if (region.swapAxes) scaledFootprintH else scaledFootprintW
                val scaledH = if (region.swapAxes) scaledFootprintW else scaledFootprintH
                writeValues(lines, region.xyIndex, left, top)
                writeValues(lines, region.sizeIndex, scaledW, scaledH)
            }
            if (region.origIndex >= 0) {
                val orig = parseInts(valueOf(lines[region.origIndex]))
                if (orig.size >= 2) {
                    writeValues(
                        lines,
                        region.origIndex,
                        max(1, (orig[0].toFloat() * factor).roundToInt()),
                        max(1, (orig[1].toFloat() * factor).roundToInt())
                    )
                }
            }
            if (region.offsetIndex >= 0) {
                val offset = parseInts(valueOf(lines[region.offsetIndex]))
                if (offset.size >= 2) {
                    writeValues(
                        lines,
                        region.offsetIndex,
                        (offset[0].toFloat() * factor).roundToInt(),
                        (offset[1].toFloat() * factor).roundToInt()
                    )
                }
            }
        }

        val scaled = lines.joinToString(LF)
        return if (usesCrlf) scaled.replace(LF, CRLF) else scaled
    }

    /** 取 `key: a, b` 里冒号后的内容 */
    private fun valueOf(line: String): String {
        val colon = line.indexOf(':')
        return if (colon < 0) "" else line.substring(colon + 1)
    }

    /**
     * 改写图集里的**页名**（只改页头那一行，不动区域名）。
     *
     * 使用场景：一个资源包里有多套骨骼，各自图集声明了同名页。导出时必须把
     * 后续骨骼的页改名，否则后写的 PNG 会覆盖前一套（导致整套骨骼采到别人的
     * 像素）。改名后必须同步改写图集里的页声明，`TextureAtlas` 才找得到图。
     *
     * @param renames 原页名 → 新页名（按出现顺序匹配；未命中的页保持不变）
     */
    fun rewritePageNames(atlasText: String, renames: List<Pair<String, String>>): String {
        if (atlasText.isBlank() || renames.isEmpty()) return atlasText
        val map = renames.toMap()
        if (map.none { (from, to) -> from != to }) return atlasText
        val usesCrlf = atlasText.contains(CRLF)
        val lines = splitLines(atlasText).toMutableList()
        var index = 0
        while (index < lines.size) {
            val raw = lines[index]
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("//")) {
                index++
                continue
            }
            if (!isIndented(raw) && line.indexOf(':') < 0) {
                val next = nextMeaningfulLine(lines, index + 1)
                val isPageHeader = next != null && next.trim().startsWith("size:", ignoreCase = true)
                if (isPageHeader) {
                    map[line]?.let { renamed ->
                        lines[index] = renamed
                    }
                }
            }
            index++
        }
        val out = lines.joinToString(LF)
        return if (usesCrlf) out.replace(LF, CRLF) else out
    }

    /** 保留原有缩进与键名，只替换 `a, b` 数值 */
    private fun writeValues(lines: MutableList<String>, index: Int, first: Int, second: Int) {
        val line = lines[index]
        val colon = line.indexOf(':')
        if (colon < 0) return
        lines[index] = line.substring(0, colon + 1) + " " + first + ", " + second
    }

    // ------------------------------------------------------------------ 内部

    /** 统一换行并去掉行尾 `\r` */
    private fun splitLines(text: String): List<String> =
        text.replace(CRLF, LF).replace(CR, LF).split(LF)

    private fun isIndented(line: String): Boolean =
        line.isNotEmpty() && (line[0] == ' ' || line[0] == TAB_CHAR)

    private fun nextMeaningfulLine(lines: List<String>, from: Int): String? {
        for (i in from until lines.size) {
            if (lines[i].isNotBlank()) return lines[i]
        }
        return null
    }

    private fun parseInts(value: String): List<Int> = value.split(',')
        .mapNotNull { it.trim().toIntOrNull() }
}

/**
 * `.atlas` 里 `rotate:` 字段的语义（单一定义来源）。
 *
 * 三种写法并存于碧蓝的资源：
 * - `false` → 0°
 * - `true`  → 90°（libgdx 的历史写法）
 * - `90` / `180` / `270` → 数字角度（Spine 4 / 新导出器写法）
 *
 * libgdx 1.13.5 的 `TextureAtlasData` 对三种写法都会算出正确的 `degrees`
 * （`true` → 90，数字直接 `Integer.parseInt`），spine 3.8 的
 * `MeshAttachment.updateUVs()` 则对 degrees 0/90/180/270 全部有分支，
 * 所以这里**只做解析与判定，不改写图集**。
 */
object RotationMode {

    /** 把 `rotate:` 的值归一化成 0/90/180/270；无法识别返回 0 */
    fun degreesOf(rawValue: String): Int {
        val value = rawValue.trim().trimEnd(',').lowercase()
        if (value == "true") return 90
        if (value == "false" || value.isEmpty()) return 0
        val degrees = value.toIntOrNull() ?: return 0
        return ((degrees % 360) + 360) % 360
    }

    /** 该角度下「页上足迹」的宽高相对 `size` 是否交换（90 / 270 交换） */
    fun swapsAxes(degrees: Int): Boolean = degrees == 90 || degrees == 270

    /** 便捷：直接从 `rotate:` 的原始值判断是否需要交换轴向 */
    fun parsesAxesSwapped(rawValue: String): Boolean = swapsAxes(degreesOf(rawValue))
}
