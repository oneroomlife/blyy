package com.azurlane.blyy.util.gameimport

import kotlin.math.roundToInt

/**
 * Spine `.atlas` 文本解析器（纯 Kotlin，无 Android 依赖，可直接单测）。
 *
 * 只做三件事，都是导入链路必须的：
 * 1. 找出图集里声明的**页（page）文件名与尺寸**——贴图落盘时必须用页名命名，
 *    否则 `TextureAtlas` 找不到图片，Spine 渲染直接黑屏；
 * 2. 在需要降采样贴图时，把页尺寸与区域坐标**同比缩放**，保证 UV 仍然正确
 *    （只缩图不改图集 = 区域错位、模型撕裂）；
 * 3. 把 Spine 4 风格的 `rotate: 90/180/270` 归一化成 libgdx Spine 3.8 能识别的
 *    `true/false`，并给出需要做 180° 像素补偿的区域（碧蓝部分皮肤包实际在用）。
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

    /** 需要做 180° 像素翻转的区域（页内坐标，像素单位，原点左上） */
    data class RotationFlip(val page: String, val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * 旋转归一化结果。
     *
     * @param text 归一化后的图集文本（可直接交给 libgdx `TextureAtlas`）
     * @param flips 需要在页图片上做 180° 翻转的区域（调用方按页名分组处理）
     * @param changedRegions 被改写的区域数量（0 表示图集本来就是 3.8 兼容格式）
     */
    data class RotationNormalization(
        val text: String,
        val flips: List<RotationFlip>,
        val changedRegions: Int
    )

    /** 需要同比缩放的属性行（页级 `size` 与区域级 `xy/size/orig/offset`） */
    private val SCALED_KEYS = setOf("size", "orig", "xy", "offset")

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
     * factor >= 1 时原样返回，避免无意义改写。
     */
    fun scale(atlasText: String, factor: Float): String {
        if (factor >= 0.999f || factor <= 0f) return atlasText
        val usesCrlf = atlasText.contains(CRLF)
        val scaled = splitLines(atlasText).joinToString(LF) { raw ->
            val colon = raw.indexOf(':')
            if (colon <= 0) {
                raw
            } else {
                val key = raw.substring(0, colon).trim().lowercase()
                if (key !in SCALED_KEYS) {
                    raw
                } else {
                    val prefix = raw.substring(0, colon + 1)
                    val values = raw.substring(colon + 1).split(',')
                    if (values.size < 2) {
                        raw
                    } else {
                        // 尺寸类字段至少保留 1px（极端缩放因子下 round 可能变 0）
                        val minimum = if (key == "size" || key == "orig") 1 else Int.MIN_VALUE
                        val rebuilt = values.map { it.trim() }
                            .joinToString(", ") { token ->
                                val n = token.toFloatOrNull()
                                if (n == null) {
                                    token
                                } else {
                                    (n * factor).roundToInt().coerceAtLeast(minimum).toString()
                                }
                            }
                        "$prefix $rebuilt"
                    }
                }
            }
        }
        return if (usesCrlf) scaled.replace(LF, CRLF) else scaled
    }

    /**
     * 把 Spine 4 风格的 `rotate: 90/180/270` 归一化成 libgdx Spine 3.8 能识别的写法。
     *
     * libgdx 的 `TextureAtlas` 只认 `true/false`，`Boolean.parseBoolean("270")`
     * 得到 false → **区域会被当成未旋转**，渲染出来是一块转错方向、边缘撕裂的图。
     *
     * 归一化规则（几何等价）：
     * - 90  ≡ 顺时针 90  → `true`（无需改像素）
     * - 270 ≡ 顺时针 270 → 旋转 180° 后即为 90：`true` + 像素翻转 180°
     * - 180 → `false` + 像素翻转 180°（包围盒不交换宽高，位置不变）
     *
     * 由于 `rotate` 行出现在 `xy` / `size` 之前，这里先收集区域属性，再统一改写。
     */
    fun normalizeRotations(atlasText: String): RotationNormalization {
        if (atlasText.isBlank()) return RotationNormalization(atlasText, emptyList(), 0)
        val lines = splitLines(atlasText).toMutableList()

        class Region(var page: String) {
            var rotateIndex: Int = -1
            var xy: Pair<Int, Int>? = null
            var size: Pair<Int, Int>? = null
        }

        var page = ""
        var current: Region? = null
        val regions = mutableListOf<Region>()
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
                    page = line
                    current = null
                } else {
                    current = Region(page).also { regions += it }
                }
                index++
                continue
            }
            val region = current
            if (colon > 0 && region != null) {
                val key = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                when (key) {
                    "rotate" -> if (indented) region.rotateIndex = index
                    "xy" -> region.xy = parseInts(value).takeIf { it.size >= 2 }?.let { it[0] to it[1] }
                    "size" -> if (indented) {
                        region.size = parseInts(value).takeIf { it.size >= 2 }?.let { it[0] to it[1] }
                    }
                }
            }
            index++
        }

        val flips = mutableListOf<RotationFlip>()
        var changed = 0
        for (region in regions) {
            if (region.rotateIndex < 0) continue
            val rawLine = lines[region.rotateIndex]
            val colon = rawLine.indexOf(':')
            if (colon < 0) continue
            val value = rawLine.substring(colon + 1).trim().lowercase()
            if (value == "true" || value == "false") continue
            val degrees = value.toIntOrNull() ?: continue
            val normalized = when (((degrees % 360) + 360) % 360) {
                0, 180 -> "false"
                90, 270 -> "true"
                else -> continue
            }
            val flipNeeded = degrees % 360 == 180 || degrees % 360 == 270
            if (flipNeeded) {
                val size = region.size
                val xy = region.xy
                if (size == null || xy == null) continue
                // 270 的包围盒在页上是 (h, w)；180 与 0 相同 (w, h)
                val boxW = if (degrees % 360 == 270) size.second else size.first
                val boxH = if (degrees % 360 == 270) size.first else size.second
                flips += RotationFlip(region.page, xy.first, xy.second, boxW, boxH)
            }
            val prefix = rawLine.substring(0, colon)
            lines[region.rotateIndex] = "$prefix: $normalized"
            changed++
        }
        return RotationNormalization(lines.joinToString(LF), flips, changed)
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
