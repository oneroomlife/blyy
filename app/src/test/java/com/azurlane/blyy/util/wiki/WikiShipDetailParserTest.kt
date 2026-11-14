package com.azurlane.blyy.util.wiki

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * wiki 详情解析单测（合成 HTML）。
 *
 * 说明：合成页面模仿 wiki 的真实结构（infobox 表 + 属性表 + 技能表 + 纯文本获取方式），
 * 用来锁定"表格键值对优先、属性白名单、技能按表头命中"这几条策略。
 * 真实页面改版时，这里测不出——那是线上兜底（`ShipDetail.isEmpty` → 去 Wiki）负责的事。
 */
class WikiShipDetailParserTest {

    private val html = """
        <html><body><div class="mw-parser-output">
          <table class="infobox">
            <tr><th>CV</th><td>石川由依</td></tr>
            <tr><th>画师</th><td>某某子</td></tr>
            <tr><th>获得方式</th><td>建造 / 活动</td></tr>
          </table>

          <h2>属性</h2>
          <table>
            <tr><th>耐久</th><td>5000 → 6000</td></tr>
            <tr><th>装甲</th><td>中型</td></tr>
            <tr><th>炮击</th><td>320</td></tr>
            <tr><th>备注</th><td>这一行不该被当成属性</td></tr>
          </table>

          <h2>技能</h2>
          <table>
            <tr><th>技能名称</th><th>描述</th></tr>
            <tr><td>歼星炮</td><td>主炮开火时对全体敌人造成伤害</td></tr>
            <tr><td>装甲强化</td><td>受到伤害降低</td></tr>
          </table>

          <p>初始装备：三联装主炮、高性能防空炮</p>
        </div></body></html>
    """.trimIndent()

    private fun parse() = WikiShipDetailParser.parse(Jsoup.parse(html))

    @Test
    fun `parses cv and illustrator from infobox table`() {
        val detail = parse()
        assertEquals("石川由依", detail.cv)
        assertEquals("某某子", detail.illustrator)
        assertFalse(detail.isEmpty)
    }

    @Test
    fun `parses stats using whitelist`() {
        val detail = parse()
        val labels = detail.stats.map { it.label }
        assertTrue(labels.contains("耐久"))
        assertTrue(labels.contains("装甲"))
        assertTrue(labels.contains("炮击"))
        // 非白名单行不应进入属性表
        assertFalse(labels.any { it.contains("备注") })
        assertEquals("5000 → 6000", detail.stats.first { it.label == "耐久" }.value)
    }

    @Test
    fun `parses skills from table under 技能 heading`() {
        val detail = parse()
        assertTrue(detail.skills.isNotEmpty())
        val first = detail.skills.first()
        assertEquals("歼星炮", first.name)
        assertTrue(first.description.contains("主炮"))
    }

    @Test
    fun `parses equipment from text fallback`() {
        val detail = parse()
        assertTrue(detail.equipment.contains("三联装主炮"))
        assertTrue(detail.equipment.contains("高性能防空炮"))
    }

    @Test
    fun `parses acquire from meta table`() {
        assertEquals("建造 / 活动", parse().acquire)
    }

    @Test
    fun `empty page yields empty detail instead of throwing`() {
        val detail = WikiShipDetailParser.parse(Jsoup.parse("<html><body>什么都没有</body></html>"))
        assertTrue(detail.isEmpty)
        assertEquals("", detail.cv)
        assertTrue(detail.stats.isEmpty())
        assertTrue(detail.skills.isEmpty())
    }

    @Test
    fun `subtitle shows cv and illustrator`() {
        val subtitle = parse().subtitle
        assertTrue(subtitle.contains("CV"))
        assertTrue(subtitle.contains("画师"))
    }
}
