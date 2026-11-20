package com.azurlane.blyy.util.wiki

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 技能表解析单测（合成 HTML，结构取自线上「新泽西 / 绫波 / U-47」三张页面）。
 *
 * 守住四条线上真出过问题的规则：
 * 1. `style="display:none"` 的模板占位行不能当技能；
 * 2. `{{{技能1名改造}}}` 这类未渲染的模板原文不能当技能名；
 * 3. 空槽里的 1×1 透明占位 PNG 不能当图标；
 * 4. 图标优先取 srcset 里的原图（64px 缩略图放大后会糊）。
 */
class WikiSkillTableParserTest {

    /** 新泽西的技能表：3 个真实技能 + 6 行隐藏占位 + 1 行空槽占位 */
    private val skillTableHtml = """
        <html><body>
          <table class="wikitable sv-skill" style="width:100%;text-align:center;">
            <tbody>
              <tr>
                <td colspan="2" class="decisive"><span class="tjmode0"><b>技能</b></span></td>
              </tr>
              <tr>
                <td style="width:30%;font-weight:bold;background:pink">
                  <span class="noselect nopoint"><img alt="Skillicon 吐息之焰.png"
                    src="https://patchwiki.biligame.com/images/blhx/thumb/2/2d/rejflpb.png/64px-Skillicon_%E5%90%90%E6%81%AF.png"
                    srcset="https://patchwiki.biligame.com/images/blhx/thumb/2/2d/rejflpb.png/96px-Skillicon_%E5%90%90%E6%81%AF.png 1.5x, https://patchwiki.biligame.com/images/blhx/2/2d/rejflpb.png 2x"
                    data-file-width="128" data-file-height="128"></span><br>吐息之焰
                </td>
                <td style="text-align:left;">自身主炮开火时，有50.0%（100.0%）概率触发一轮特殊弹幕</td>
              </tr>
              <tr style="display:none">
                <td style="width:30%;font-weight:bold;background:">
                  <span class="noselect nopoint">[[文件:skillicon_{{{技能1名改造}}}.png|64px|link=]]</span><br>{{{技能1名改造}}}
                </td>
                <td style="text-align:left;">{{{技能1改造}}}</td>
              </tr>
              <tr>
                <td style="width:30%;font-weight:bold;background:gold">
                  <span class="noselect nopoint"><img alt="Skillicon Big J.png"
                    src="https://patchwiki.biligame.com/images/blhx/thumb/5/58/6650nn.png/64px-Skillicon_Big_J.png"
                    srcset="https://patchwiki.biligame.com/images/blhx/5/58/6650nn.png 2x"
                    data-file-width="128" data-file-height="128"></span><br>Big J
                </td>
                <td style="text-align:left;">战斗开始时提升自身炮击5.0%（15.0%）</td>
              </tr>
              <tr style="display:none">
                <td style="width:30%;font-weight:bold;background:">
                  <span class="noselect nopoint"><img alt="Skillicon .png"
                    src="https://patchwiki.biligame.com/images/blhx/f/f0/8mbyx.png"
                    data-file-width="1" data-file-height="1"></span><br>
                </td>
                <td style="text-align:left;"></td>
              </tr>
              <tr>
                <td style="width:30%;font-weight:bold;background:gold">
                  <span class="noselect nopoint"><img alt="Skillicon 领向自由的炮火.png"
                    src="https://patchwiki.biligame.com/images/blhx/thumb/8/88/f4noh9.png/64px-Skillicon_%E9%A2%86%E5%90%91.png"
                    data-file-width="128" data-file-height="128"></span><br>领向自由的炮火
                </td>
                <td style="text-align:left;">自身装备的主炮散布范围降低0.5（5）点</td>
              </tr>
            </tbody>
          </table>
        </body></html>
    """.trimIndent()

    private fun parse(html: String = skillTableHtml) =
        WikiSkillTableParser.parse(Jsoup.parse(html))

    @Test
    fun `parses visible skill rows with names and descriptions`() {
        val skills = parse()
        assertEquals(listOf("吐息之焰", "Big J", "领向自由的炮火"), skills.map { it.name })
        assertTrue(skills.first().description.contains("特殊弹幕"))
        assertTrue(skills[1].description.contains("炮击"))
    }

    @Test
    fun `hidden template rows are skipped`() {
        val skills = parse()
        assertFalse(skills.any { it.name.contains("{{{") })
        assertFalse(skills.any { it.name.contains("[[") })
        assertFalse(skills.any { it.name == "技能" || it.name == "改造技能" })
    }

    @Test
    fun `icon prefers largest srcset candidate`() {
        val skills = parse()
        // 2x 档 = 原图（没有 64px 缩略图路径与 /thumb/ 段）
        assertEquals("https://patchwiki.biligame.com/images/blhx/2/2d/rejflpb.png", skills[0].iconUrl)
        // 没有 1.5x 档时同样取 2x
        assertEquals("https://patchwiki.biligame.com/images/blhx/5/58/6650nn.png", skills[1].iconUrl)
    }

    @Test
    fun `icon falls back to src when srcset missing`() {
        val skills = parse()
        assertEquals(
            "https://patchwiki.biligame.com/images/blhx/thumb/8/88/f4noh9.png/64px-Skillicon_%E9%A2%86%E5%90%91.png",
            skills[2].iconUrl
        )
    }

    @Test
    fun `empty slot placeholder image is rejected`() {
        val placeholderOnly = """
            <html><body><table class="wikitable sv-skill"><tbody>
              <tr><td colspan="2">技能</td></tr>
              <tr>
                <td style="width:30%"><img alt="Skillicon .png" src="https://x/1x1.png" data-file-width="1" data-file-height="1"><br>空技能</td>
                <td>没有图标的技能</td>
              </tr>
            </tbody></table></body></html>
        """.trimIndent()

        val skills = parse(placeholderOnly)
        assertEquals(1, skills.size)
        assertEquals("", skills.first().iconUrl)
        assertFalse(skills.first().isGuessable)
    }

    @Test
    fun `duplicate skill rows merge instead of duplicating`() {
        val duplicated = """
            <html><body><table class="wikitable sv-skill"><tbody>
              <tr><td colspan="2">技能</td></tr>
              <tr style="background:cyan">
                <td style="width:30%;background:cyan"><img alt="Skillicon 鬼神演舞.png" src="https://x/a.png" data-file-width="128"><br>鬼神演舞</td>
                <td>每20秒，有40.0%（70.0%）概率开启鬼神演舞</td>
              </tr>
              <tr style="background:cyan">
                <td style="width:30%;background:cyan"><img alt="Skillicon 鬼神演舞.png" src="https://x/a.png" data-file-width="128"><br>鬼神演舞</td>
                <td>每20秒，有40.0%（70.0%）概率开启鬼神演舞</td>
              </tr>
            </tbody></table></body></html>
        """.trimIndent()

        val skills = parse(duplicated)
        assertEquals(1, skills.size)
        // 底色不参与判定：没有"专属特殊兵装强化"文案就不是兵装强化技能
        assertEquals("", skills.first().badge)
    }

    /**
     * 技能标记只看描述里的明文，不按 wiki 单元格底色猜。
     *
     * 样本取自「光辉 / 装甲空母」：描述末尾追加了 `（专属特殊兵装强化）装甲空母+ ：…`。
     */
    @Test
    fun `badge marks gear strengthened skill by description marker`() {
        val gearStrengthened = """
            <html><body><table class="wikitable sv-skill"><tbody>
              <tr><td colspan="2">技能</td></tr>
              <tr>
                <td style="width:30%;background:gold"><img alt="Skillicon 装甲空母.png" src="https://x/g.png" data-file-width="128"><br>装甲空母</td>
                <td>每次执行空袭后为先锋部队增加一个护盾（专属特殊兵装强化）装甲空母+ ：每次执行空袭时额外触发一轮特殊弹幕</td>
              </tr>
            </tbody></table></body></html>
        """.trimIndent()

        val skills = parse(gearStrengthened)
        assertEquals(1, skills.size)
        assertEquals("兵装强化", skills.first().badge)
    }

    @Test
    fun `table without sv-skill class is found by Skillicon alt`() {
        val plain = """
            <html><body><table class="wikitable">
              <tr><td colspan="2"><b>技能</b></td></tr>
              <tr>
                <td><img alt="Skillicon LuckyE.png" src="//patchwiki.biligame.com/images/blhx/2/27/m7vi.png" data-file-width="128"><br>LuckyE</td>
                <td>空中支援时，有40.0%（70.0%）概率造成2倍伤害</td>
              </tr>
            </table></body></html>
        """.trimIndent()

        val skills = parse(plain)
        assertEquals(1, skills.size)
        // 协议相对地址必须补成 https
        assertEquals("https://patchwiki.biligame.com/images/blhx/2/27/m7vi.png", skills.first().iconUrl)
    }

    @Test
    fun `legacy table without icons still yields skill names`() {
        val legacy = """
            <html><body>
              <h2>技能</h2>
              <table>
                <tr><th>技能名称</th><th>描述</th></tr>
                <tr><td>歼星炮</td><td>主炮开火时对全体敌人造成伤害</td></tr>
                <tr><td>装甲强化</td><td>受到伤害降低</td></tr>
              </table>
            </body></html>
        """.trimIndent()

        val skills = parse(legacy)
        assertEquals(listOf("歼星炮", "装甲强化"), skills.map { it.name })
        assertTrue(skills.all { it.iconUrl.isEmpty() })
        assertTrue(skills.none { it.isGuessable })
    }

    @Test
    fun `page without skills yields empty list instead of throwing`() {
        val skills = parse("<html><body><p>什么都没有</p></body></html>")
        assertTrue(skills.isEmpty())
    }

    @Test
    fun `skill list is capped`() {
        val rows = (1..30).joinToString("") { index ->
            """<tr><td style="width:30%"><img alt="Skillicon S$index.png" src="https://x/s$index.png" data-file-width="128"><br>技能$index</td><td>描述$index</td></tr>"""
        }
        val html = """<html><body><table class="wikitable sv-skill"><tbody><tr><td colspan="2">技能</td></tr>$rows</tbody></table></body></html>"""

        assertEquals(WikiSkillTableParser.MAX_SKILLS, parse(html).size)
    }

    /**
     * 线上真实样本（`https://wiki.biligame.com/blhx/新泽西` 的技能表，2026-11 抓取）。
     *
     * 合成 HTML 只能验证"我以为的页面结构"，这条用真实页面锁定一次端到端结果：
     * 3 个技能、128px 原图直链（不带 /thumb/ 缩略段）、描述完整。
     */
    @Test
    fun `real wiki skill table of New Jersey parses three skills with original icons`() {
        val html = checkNotNull(javaClass.classLoader?.getResourceAsStream("wiki/skill_table_newjersey.html")) {
            "缺少测试资源 wiki/skill_table_newjersey.html"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }

        val skills = WikiSkillTableParser.parse(Jsoup.parse(html))

        assertEquals(listOf("吐息之焰", "领向自由的炮火", "Big J"), skills.map { it.name })
        assertTrue(skills.all { it.iconUrl.startsWith("https://patchwiki.biligame.com/images/blhx/") })
        // srcset 的 2x 档才是原图；/thumb/ 路径是 64px 缩略图，放大展示会糊
        assertTrue(skills.all { !it.iconUrl.contains("/thumb/") })
        assertTrue(skills.all { it.description.isNotBlank() })
        assertTrue(skills.all { it.isGuessable })
        // 新泽西这三个技能都没有被特殊兵装强化，标记必须为空（不能按底色瞎标）
        assertTrue(skills.all { it.badge.isEmpty() })
    }
}
