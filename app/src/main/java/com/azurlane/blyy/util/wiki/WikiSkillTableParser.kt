package com.azurlane.blyy.util.wiki

import com.azurlane.blyy.data.model.SkillRow
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * wiki 舰船页「技能表」→ [SkillRow] 的解析器（含技能图标）。
 *
 * 现实页面结构（以「新泽西」为例，技能表是页面里第 8 张 table）：
 *
 * ```html
 * <table class="wikitable sv-skill">
 *   <tr><td colspan="2" class="decisive"><span class="tjmode0"><b>技能</b></span>…</td></tr>
 *   <tr>
 *     <td style="width:30%;font-weight:bold;background:pink">
 *       <span><img alt="Skillicon 吐息之焰.png"
 *                  src="…/thumb/2/2d/xxx.png/64px-Skillicon_….png"
 *                  srcset="…/96px-… 1.5x, …/xxx.png 2x"
 *                  data-file-width="128"></span><br>吐息之焰
 *     </td>
 *     <td style="text-align:left;">自身主炮开火时…</td>
 *   </tr>
 *   <tr style="display:none">…[[文件:skillicon_{{{技能1名改造}}}.png]]…</tr>
 * </table>
 * ```
 *
 * 因此解析必须处理四类"假数据"，否则题面会抽到空图标/模板占位符：
 * 1. `style="display:none"` 的行 —— wiki 用它们承载「普通 / 改造」两套技能的模板占位；
 * 2. 名字里带 `{{{技能N名改造}}}` / `[[文件:…]]` 的模板原文；
 * 3. 空槽里那张 1×1 透明占位 PNG（`data-file-width="1"`，alt 是 `Skillicon .png`）；
 * 4. 同一技能在「改造前/后」两张行里重复出现（按技能名去重，图标取第一个非空的）。
 *
 * 图标优先取 `srcset` 里倍率最大的一档：wiki 的 `src` 是 64px 缩略图，
 * 而 2x 档才是 128×128 的原图 —— 界面上放大展示时清晰度差别很明显。
 *
 * 纯函数：只依赖 Jsoup [Document]，不碰网络/磁盘，便于用合成 HTML 单测。
 */
object WikiSkillTableParser {

    /** 单次解析的技能上限（正常舰娘 1–3 个，留足改造/强化变体余量） */
    const val MAX_SKILLS = 12

    private const val MAX_NAME_LENGTH = 30
    private const val MAX_DESCRIPTION_LENGTH = 400
    private const val ICON_ALT_PREFIX = "Skillicon"

    private val HIDDEN_STYLE_REGEX = Regex("""display\s*:\s*none""", RegexOption.IGNORE_CASE)

    /** 模板原文标记：命中即说明这一行是 wiki 未渲染的占位行 */
    private val TEMPLATE_MARKERS = listOf("{{{", "[[", "]]")

    /** 表头/说明行的伪技能名 */
    private val HEADER_NAMES = setOf("技能", "改造技能", "技能名称", "名称", "图标", "效果")

    private val WHITESPACE_REGEX = Regex("""\s+""")

    /** 被特殊兵装强化过的技能，描述里带这段前缀文案（唯一的"技能标记"依据） */
    private const val SPECIAL_GEAR_MARKER = "专属特殊兵装强化"

    /** srcset 的 "URL 2x" 片段 */
    private val SRCSET_SEPARATOR_REGEX = Regex("""\s+""")

    /**
     * 解析整页技能。
     *
     * 主路线：`table.sv-skill`（没有该类时退化为"含 Skillicon 图标的表"）。
     * 兜底路线：沿用历史启发式（含"技能"字样的表 → class 含 skill 的容器 → 标题后的列表），
     * 保证旧页面/合成测试 HTML 仍能解析出技能名（只是没有图标）。
     */
    fun parse(doc: Document): List<SkillRow> {
        val fromSkillTables = parseSkillTables(doc)
        if (fromSkillTables.isNotEmpty()) return fromSkillTables
        return parseLegacyTables(doc)
    }

    // ── 主路线：技能表 ──

    private fun parseSkillTables(doc: Document): List<SkillRow> {
        val result = LinkedHashMap<String, SkillRow>()

        skillTables(doc).forEach tableLoop@{ table ->
            directRows(table).forEach rowLoop@{ row ->
                if (isHidden(row)) return@rowLoop

                val cells = row.children()
                    .filter { it.normalName() == "td" || it.normalName() == "th" }
                // 表头行是 colspan=2 的单格，天然被这里挡掉
                if (cells.size < 2) return@rowLoop

                val nameCell = cells[0]
                val name = cleanSkillName(nameCell.text())
                if (name.isEmpty()) return@rowLoop

                val iconUrl = iconUrlOf(nameCell)
                val description = cleanDescription(
                    cells.drop(1).joinToString(" ") { it.text() }
                )
                val badge = badgeOf(description)

                val existing = result[name]
                if (existing == null) {
                    result[name] = SkillRow(
                        name = name,
                        description = description,
                        iconUrl = iconUrl,
                        badge = badge
                    )
                } else {
                    // 同名技能的第二次出现（改造前后/重复行）只做字段补全，不覆盖已有内容
                    result[name] = existing.copy(
                        description = existing.description.ifBlank { description },
                        iconUrl = existing.iconUrl.ifBlank { iconUrl },
                        badge = existing.badge.ifBlank { badge }
                    )
                }
            }
        }

        return result.values.take(MAX_SKILLS).toList()
    }

    /**
     * 定位技能表。
     *
     * `table.sv-skill` 是 wiki 技能表的稳定类名；个别页面（或重写过的模板）没有这个类，
     * 但图标 alt 仍是 `Skillicon xxx.png`，用属性选择器兜底识别，比"表里含技能二字"更准。
     */
    private fun skillTables(doc: Document): List<Element> {
        val primary = doc.select("table.sv-skill")
        if (primary.isNotEmpty()) return primary
        return doc.select("table").filter { it.selectFirst("img[alt^=$ICON_ALT_PREFIX]") != null }
    }

    /** 只取当前表的直接 `<tr>`（`table.select("tr")` 会把嵌套表的行也捞进来） */
    private fun directRows(table: Element): List<Element> {
        val body = table.children().firstOrNull { it.normalName() == "tbody" } ?: table
        return body.children().filter { it.normalName() == "tr" }
    }

    /** 隐藏行：`style="display:none"` 或 `hidden` 属性/类（wiki 的技能模板占位行） */
    private fun isHidden(element: Element): Boolean {
        if (element.hasAttr("hidden")) return true
        val style = element.attr("style")
        if (style.isNotEmpty() && HIDDEN_STYLE_REGEX.containsMatchIn(style)) return true
        return element.classNames().any { it.equals("hidden", ignoreCase = true) }
    }

    private fun cleanSkillName(raw: String): String {
        val name = raw.replace(WHITESPACE_REGEX, " ").trim()
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) return ""
        if (TEMPLATE_MARKERS.any { name.contains(it) }) return ""
        if (name in HEADER_NAMES) return ""
        return name
    }

    private fun cleanDescription(raw: String): String =
        raw.replace(WHITESPACE_REGEX, " ").trim().take(MAX_DESCRIPTION_LENGTH)

    /**
     * 技能图标 URL。
     *
     * 空串代表"这张技能没有可用图标"（占位图/空槽），这类技能不能作为「识技能」的题面。
     */
    private fun iconUrlOf(cell: Element): String {
        val img = cell.selectFirst("img") ?: return ""

        // wiki 空槽占位：1×1 透明 png（alt 就是 "Skillicon .png"）
        val declaredWidth = img.attr("data-file-width").toIntOrNull() ?: 0
        val declaredHeight = img.attr("data-file-height").toIntOrNull() ?: 0
        if (declaredWidth in 1..8 || declaredHeight in 1..8) return ""
        val alt = img.attr("alt").trim()
        if (alt.startsWith(ICON_ALT_PREFIX) &&
            alt.length <= ICON_ALT_PREFIX.length + ".png".length + 1
        ) {
            return ""
        }

        val fromSrcset = bestFromSrcset(img.attr("srcset"))
        val raw = fromSrcset.ifBlank {
            img.attr("src").ifBlank { img.attr("data-src") }
        }
        return normalizeImageUrl(raw)
    }

    /** 取 srcset 中倍率最大的一档（wiki 的 2x 档 = 原图 128px） */
    private fun bestFromSrcset(srcset: String): String {
        if (srcset.isBlank()) return ""
        return srcset.split(',')
            .mapNotNull { candidate ->
                val parts = candidate.trim().split(SRCSET_SEPARATOR_REGEX)
                val url = parts.firstOrNull().orEmpty()
                if (!url.startsWith("http") && !url.startsWith("//")) return@mapNotNull null
                val scale = parts.getOrNull(1)?.removeSuffix("x")?.toFloatOrNull() ?: 1f
                scale to url
            }
            .maxByOrNull { it.first }
            ?.second
            .orEmpty()
    }

    private fun normalizeImageUrl(raw: String): String {
        val url = raw.trim()
        if (url.isEmpty() || url.startsWith("data:")) return ""
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "https://wiki.biligame.com$url"
            else -> url
        }
    }

    /**
     * 技能标记：只从**描述文本**里取可确证的信息。
     *
     * wiki 的技能名单元格有 background:pink/gold/cyan 三种底色，但页面没有图例说明
     * 各自的语义（是技能类型？稀有度？改造？），按底色猜一个中文标签会给出错误信息。
     * 唯一有明文依据的是"专属特殊兵装强化"——被特殊兵装强化过的技能，描述里会带
     * `（专属特殊兵装强化）xxx+ ：…` 这段追加文案。
     */
    private fun badgeOf(description: String): String =
        if (description.contains(SPECIAL_GEAR_MARKER)) "兵装强化" else ""

    // ── 兜底路线：历史启发式 ──

    private fun parseLegacyTables(doc: Document): List<SkillRow> {
        val result = LinkedHashMap<String, SkillRow>()

        // 路线 1：含"技能"字样的表格（表头/标题命中）
        doc.select("table").forEach { table ->
            val heading = table.previousElementSibling()?.text().orEmpty() + table.text().take(80)
            if (!heading.contains("技能")) return@forEach
            collectLegacyRows(directRows(table), result)
        }

        // 路线 2：class 里带 skill 的容器
        if (result.isEmpty()) {
            doc.select("[class*=skill]").forEach { element ->
                val name = element.select("[class*=name]").text().trim()
                if (name.isNotEmpty() && name.length <= 20) {
                    val desc = element.select("[class*=desc], [class*=detail]").text().trim()
                    result.putIfAbsent(
                        name,
                        SkillRow(name = name, description = desc.take(MAX_DESCRIPTION_LENGTH))
                    )
                }
            }
        }

        // 路线 3：标题"技能"之后紧跟的列表
        if (result.isEmpty()) {
            doc.select("h1, h2, h3, h4, .mw-headline").forEach { heading ->
                if (!heading.text().contains("技能")) return@forEach
                var sibling: Element? = heading.nextElementSibling()
                repeat(3) {
                    sibling?.select("li")?.forEach { li ->
                        val name = li.select("b, strong").text().trim().ifBlank { li.text().take(12) }
                        if (name.isNotBlank()) {
                            result.putIfAbsent(
                                name,
                                SkillRow(
                                    name = name,
                                    description = li.text().trim().take(MAX_DESCRIPTION_LENGTH)
                                )
                            )
                        }
                    }
                    sibling = sibling?.nextElementSibling()
                }
            }
        }

        return result.values.take(MAX_SKILLS).toList()
    }

    private fun collectLegacyRows(rows: List<Element>, result: MutableMap<String, SkillRow>) {
        rows.forEach { row ->
            if (isHidden(row)) return@forEach
            val cells = row.children()
                .filter { it.normalName() == "td" || it.normalName() == "th" }
            if (cells.size < 2) return@forEach
            val name = cells[0].text().trim()
            if (name.isEmpty() || name.length > 24) return@forEach
            if (name.contains("技能") || name.contains("名称")) return@forEach
            val desc = cells.drop(1).joinToString(" ") { it.text().trim() }
            if (desc.isBlank() && name.length < 2) return@forEach
            result.putIfAbsent(
                name,
                SkillRow(
                    name = name,
                    description = desc.take(MAX_DESCRIPTION_LENGTH),
                    iconUrl = iconUrlOf(cells[0])
                )
            )
        }
    }
}
