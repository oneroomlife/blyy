package com.azurlane.blyy.util.wiki

import com.azurlane.blyy.data.model.ShipDetail
import com.azurlane.blyy.data.model.SkillRow
import com.azurlane.blyy.data.model.StatRow
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * wiki 舰船页 → [ShipDetail] 的容错解析器。
 *
 * 现实约束：wiki 是**人工维护的 MediaWiki**，没有稳定 schema，页面改版是常态
 * （这个项目已经历过 gamekee 两次改版）。因此这里的原则是：
 *
 * 1. **永不抛异常**：任何一步解析失败都只丢失该字段，不影响其他字段；
 * 2. **多路兜底**：属性先找"属性表"（按已知属性名匹配单元格），再退回全文正则；
 *    技能先找含"技能"的表，再退回 `已经 class 含 skill 的元素`；
 * 3. **产出可验证**：`ShipDetail.isEmpty` 为 true 时 UI 明确显示"数据暂缺 → 去 Wiki"，
 *    而不是渲染一张空表格；
 * 4. **纯函数**：只依赖 Jsoup Document，不碰网络/磁盘，便于用合成 HTML 做单测。
 */
object WikiShipDetailParser {

    /** 属性名白名单（wiki 表格左列常见写法；命中即收，防止把"获得方式"等行混进来） */
    private val STAT_LABELS = listOf(
        "耐久", "装甲", "装填", "炮击", "雷击", "航空", "防空", "命中", "机动", "航速",
        "消耗", "反潜", "幸运", "氧气", "火力", "雷装", "对空", "索敌", "运", "航程"
    )

    private val CV_REGEX = Regex("""(?:CV|声优|CV\.)\s*[:：]\s*([^\s/|,，、\[\]]{1,24})""")
    private val ILLUST_REGEX = Regex("""(?:画师|立绘|原画)\s*[:：]\s*([^\s/|,，、\[\]]{1,24})""")
    private val ACQUIRE_REGEX = Regex("""(?:获得方式|获取方式|入手方式)\s*[:：]?\s*([^\n]{2,60})""")

    fun parse(doc: Document): ShipDetail {
        // 表格键值对优先：wiki 的 infobox 是 <th>CV</th><td>xxx</td>，
        // 直接对 doc.text() 做正则时 "CV" 与值会被粘在一起（没有冒号），因此先走表格。
        val metaPairs = parseMetaPairs(doc)
        val text = doc.text()

        val cv = metaPairs["CV"].orEmpty()
            .ifBlank { metaPairs["声优"].orEmpty() }
            .ifBlank { firstMatch(CV_REGEX, text) }
        val illustrator = metaPairs["画师"].orEmpty()
            .ifBlank { metaPairs["原画"].orEmpty() }
            .ifBlank { firstMatch(ILLUST_REGEX, text) }

        return ShipDetail(
            cv = cv,
            illustrator = illustrator,
            stats = parseStats(doc),
            skills = parseSkills(doc),
            equipment = parseEquipment(doc),
            acquire = metaPairs["获得方式"].orEmpty()
                .ifBlank { metaPairs["获取方式"].orEmpty() }
                .ifBlank { firstMatch(ACQUIRE_REGEX, text) },
            fetchedAt = System.currentTimeMillis()
        )
    }

    /** 收集「表头 → 值」形式的键值对（CV/声优/画师/获得方式…） */
    private fun parseMetaPairs(doc: Document): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        doc.select("table tr").forEach { row ->
            val cells = row.select("th, td")
            if (cells.size < 2) return@forEach
            val key = cells[0].text().trim().trimEnd('：', ':')
            if (key.length > 8) return@forEach
            val value = cells.drop(1).joinToString(" / ") { it.text().trim() }
            if (value.isNotBlank() && !result.containsKey(key)) {
                result[key] = value.take(60)
            }
        }
        return result
    }

    private fun firstMatch(regex: Regex, text: String): String =
        regex.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()

    // ── 属性 ──

    private fun parseStats(doc: Document): List<StatRow> {
        val result = LinkedHashMap<String, String>()

        doc.select("table tr").forEach { row ->
            val cells = row.select("th, td")
            if (cells.size < 2) return@forEach
            val label = cells[0].text().trim().trimEnd('：', ':')
            val matchedLabel = STAT_LABELS.firstOrNull { label == it || label.startsWith(it) } ?: return@forEach
            if (result.containsKey(matchedLabel)) return@forEach
            val value = cells.drop(1)
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .joinToString(" / ")
            if (value.isNotBlank()) result[matchedLabel] = value.take(40)
        }

        // 兜底：页面把属性写成"耐久 1234"的纯文本块
        if (result.isEmpty()) {
            STAT_LABELS.forEach { label ->
                val regex = Regex("""$label\s*[:：]?\s*(\d{1,5})""")
                regex.find(doc.text())?.groupValues?.getOrNull(1)?.let { result[label] = it }
            }
        }
        return result.entries.take(24).map { StatRow(label = it.key, value = it.value) }
    }

    // ── 技能 ──

    private fun parseSkills(doc: Document): List<SkillRow> {
        val result = LinkedHashMap<String, SkillRow>()

        // 路线 1：含"技能"字样的表格（表头/标题命中）
        doc.select("table").forEach { table ->
            val heading = table.previousElementSibling()?.text().orEmpty() + table.text().take(80)
            if (!heading.contains("技能")) return@forEach
            collectSkillRows(table.select("tr"), result)
        }

        // 路线 2：class 里带 skill 的容器
        if (result.isEmpty()) {
            doc.select("[class*=skill]").forEach { element ->
                val name = element.select("[class*=name]").text().trim()
                if (name.isNotEmpty() && name.length <= 20) {
                    val desc = element.select("[class*=desc], [class*=detail]").text().trim()
                    result.putIfAbsent(name, SkillRow(name = name, description = desc.take(400)))
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
                                SkillRow(name = name, description = li.text().trim().take(400))
                            )
                        }
                    }
                    sibling = sibling?.nextElementSibling()
                }
            }
        }

        return result.values.take(12).toList()
    }

    private fun collectSkillRows(rows: List<Element>, result: MutableMap<String, SkillRow>) {
        rows.forEach { row ->
            val cells = row.select("th, td")
            if (cells.size < 2) return@forEach
            val name = cells[0].text().trim()
            if (name.isEmpty() || name.length > 24) return@forEach
            if (name.contains("技能") || name.contains("名称")) return@forEach
            val desc = cells.drop(1).joinToString(" ") { it.text().trim() }
            if (desc.isBlank() && name.length < 2) return@forEach
            result.putIfAbsent(name, SkillRow(name = name, description = desc.take(400)))
        }
    }

    // ── 装备 ──

    private fun parseEquipment(doc: Document): List<String> {
        val result = LinkedHashSet<String>()

        doc.select("table tr").forEach { row ->
            val cells = row.select("th, td")
            if (cells.isEmpty()) return@forEach
            val label = cells[0].text()
            if (!label.contains("装备")) return@forEach
            cells.drop(1)
                .flatMap { it.select("a").map { a -> a.text().trim() } }
                .filter { it.isNotBlank() && it.length <= 24 }
                .forEach { result += it }
        }

        if (result.isEmpty()) {
            val regex = Regex("""初始装备\s*[:：]?\s*([^\n]{2,80})""")
            regex.find(doc.text())?.groupValues?.getOrNull(1)
                ?.split('、', '，', ',', ' ')
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() && it.length <= 24 }
                ?.forEach { result += it }
        }

        return result.take(8).toList()
    }
}
