package com.azurlane.blyy.util.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * HTML 台词语音卡的渲染回归。
 *
 * 只测纯函数（[VoiceCardHtmlComposer.render] 等），不碰 Android 运行时：
 * 卡片是发给别人的产物，**转义漏一个字符就是一句台词被浏览器当标签吃掉**，
 * 而且语音改为「原站链接」后 URL 里的 `&` 必须转成 `&amp;`，
 * 所以这里盯死四件事——转义（文本 + 属性）、占位符替换彻底、播放器降级态、链接形态。
 */
class VoiceCardHtmlRenderTest {

    private val template = """
        <title>{{TITLE}}</title>
        <h1>{{SHIP}}</h1><span>{{SERIAL}}</span><div>{{TAGS}}</div>
        <div class="avatar">{{AVATAR}}</div>
        <p class="{{QUOTE_CLASS}}">{{DIALOGUE}}</p>
        <span>{{HINT}}</span>
        <div class="wave">{{BARS}}</div>
        <section class="player{{PLAYER_CLASS}}"><button{{PLAY_BTN_DISABLED}}></button></section>
        <audio{{AUDIO_ATTR}}></audio>
        <span>{{DATE}}</span>
    """.trimIndent()

    private fun content(
        dialogue: String = "指挥官，今天也要一起出航吗？",
        avatar: String? = "data:image/jpeg;base64,AAAA",
        audioUrl: String = "https://example.com/voice/a.mp3"
    ) = VoiceCardHtmlComposer.Content(
        shipName = "绫波",
        sceneLabel = "特型舰 · 改",
        dialogue = dialogue,
        serial = "NO. 07 / 128",
        dateLabel = "2026.11.20",
        avatarDataUri = avatar,
        audioUrl = audioUrl
    )

    @Test
    fun `台词中的 HTML 元字符必须被转义`() {
        val html = VoiceCardHtmlComposer.render(
            template,
            content(dialogue = "<script>alert(1)</script> & \"引号\" 'x'")
        )
        assertFalse("台词里的标签不能被原样输出", html.contains("<script>alert(1)</script>"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("&amp;"))
        assertTrue(html.contains("&quot;"))
        assertTrue(html.contains("&#39;"))
    }

    @Test
    fun `换行转成 br 且不留下未替换的占位符`() {
        val html = VoiceCardHtmlComposer.render(template, content(dialogue = "第一句\n第二句"))
        assertTrue(html.contains("第一句<br />第二句"))
        assertFalse("渲染后不允许残留 {{...}} 占位符", html.contains("{{"))
    }

    @Test
    fun `CRLF 与裸 CR 都归一成单个 br`() {
        val html = VoiceCardHtmlComposer.render(
            template,
            content(dialogue = "第一句\r\n第二句\r第三句")
        )
        assertTrue(html.contains("第一句<br />第二句<br />第三句"))
        assertFalse("不能残留 \\r", html.contains("\r"))
    }

    @Test
    fun `台词里的占位符不会被二次替换`() {
        // 链式 String.replace 的经典坑：先填入的台词会被后续 replace 再扫一遍。
        // 一句写着 {{BARS}} 的台词若被展开成 40 根波形柱，卡面就被撑爆了。
        val html = VoiceCardHtmlComposer.render(
            template,
            content(dialogue = "我把 {{BARS}} 和 {{DATE}} 写进了台词")
        )
        assertTrue("占位符文本要原样保留在正文里", html.contains("我把 {{BARS}} 和 {{DATE}} 写进了台词"))
        // 台词里的 {{BARS}} 若被二次替换，波形柱会变成 80 根
        assertEquals(40, Regex("<span class=\"bar\"").findAll(html).count())
    }

    @Test
    fun `舰名里的占位符同样不会被二次替换`() {
        val html = VoiceCardHtmlComposer.render(
            template,
            content().copy(shipName = "{{AVATAR}}")
        )
        // 若被替换，头像块会被插进 <h1> 里
        assertTrue(html.contains("{{AVATAR}}"))
        assertTrue(html.contains("{{AVATAR}} · 台词语音卡"))
    }

    @Test
    fun `非 http 语音地址按无语音降级`() {
        listOf("/audio/x.mp3", "file:///sdcard/x.mp3", "javascript:alert(1)").forEach { url ->
            val html = VoiceCardHtmlComposer.render(template, content(audioUrl = url))
            assertTrue("$url 应降级为死播放器", html.contains("player is-dead"))
            assertTrue(html.contains("disabled"))
            assertFalse("$url 不能进 src", html.contains("src=\"$url"))
        }
    }

    @Test
    fun `有语音时用原站链接而不是内嵌数据`() {
        val html = VoiceCardHtmlComposer.render(template, content())
        assertTrue("必须是 src=链接", html.contains("""src="https://example.com/voice/a.mp3""""))
        assertFalse("不允许再出现 base64 音频", html.contains("data:audio"))
        assertFalse(html.contains("is-dead"))
        assertFalse(html.contains("disabled"))
        assertTrue(html.contains("取自原站"))
    }

    @Test
    fun `链接里的查询参数必须转义`() {
        val html = VoiceCardHtmlComposer.render(
            template,
            content(audioUrl = "https://cdn.example.com/v.mp3?ref=a&t=1")
        )
        assertTrue("& 必须写成 &amp;，否则属性被截断", html.contains("ref=a&amp;t=1"))
        assertFalse(html.contains("ref=a&t=1"))
    }

    @Test
    fun `无语音地址时降级为禁用播放器但仍出卡片`() {
        val html = VoiceCardHtmlComposer.render(template, content(audioUrl = ""))
        assertTrue(html.contains("player is-dead"))
        assertTrue("按钮必须 disabled，否则点了没反应", html.contains("disabled"))
        assertTrue(html.contains("暂无语音"))
        assertTrue("audio 标签不应带 src", html.contains("<audio></audio>"))
        assertFalse(html.contains("src=\"http"))
        // 台词与舰名照常输出
        assertTrue(html.contains("绫波"))
    }

    @Test
    fun `卡片不再带语言标签`() {
        val html = VoiceCardHtmlComposer.render(
            template,
            content().copy(sceneLabel = "特型舰 · 改")
        )
        assertTrue(html.contains("特型舰 · 改"))
        assertFalse("中配/日配不属于卡面信息", html.contains("tag lang"))
        assertFalse(html.contains("中配"))
        assertFalse(html.contains("日配"))
    }

    @Test
    fun `无头像时用首字纹章占位`() {
        val html = VoiceCardHtmlComposer.render(template, content(avatar = null))
        assertTrue(html.contains("""<span class="mono">绫</span>"""))
        assertFalse(html.contains("<img"))
    }

    @Test
    fun `台词长度决定字号档位`() {
        val q = VoiceCardHtmlComposer::quoteClassFor
        assertEquals("q-xl", q("短句"))
        assertEquals("q-xl", q("字".repeat(34)))
        assertEquals("q-l", q("字".repeat(35)))
        assertEquals("q-l", q("字".repeat(70)))
        assertEquals("q-m", q("字".repeat(71)))
        assertEquals("q-m", q("字".repeat(120)))
        assertEquals("q-s", q("字".repeat(121)))
    }

    @Test
    fun `波形条数量与确定性`() {
        val a = VoiceCardHtmlComposer.waveBars(40, 7L)
        val b = VoiceCardHtmlComposer.waveBars(40, 7L)
        assertEquals("同一句台词要生成同一波形（分享可复现）", a, b)
        assertEquals(40, Regex("<span class=\"bar\"").findAll(a).count())
        assertTrue(a.contains("--h:"))
        // 高度落在 CSS 可用的 0.12~1.00 之间，不能出现 0 或负值把柱子抽没
        Regex("--h:([0-9.]+);").findAll(a).forEach {
            val h = it.groupValues[1].toFloat()
            assertTrue("柱高越界: $h", h in 0.12f..1f)
        }
    }

    @Test
    fun `真实模板可以被完整渲染`() {
        val file = listOf(
            File("src/main/assets/share/voice_card.html"),
            File("app/src/main/assets/share/voice_card.html")
        ).firstOrNull { it.isFile }
        assumeTrue("找不到 assets 模板，跳过（非本模块工作目录）", file != null)

        val raw = file!!.readText()
        // 模板本身该有的占位符一个都不能少，否则 render 里某行替换会静默失效
        listOf(
            "{{TITLE}}", "{{SHIP}}", "{{SERIAL}}", "{{TAGS}}", "{{AVATAR}}",
            "{{QUOTE_CLASS}}", "{{DIALOGUE}}", "{{BARS}}", "{{HINT}}",
            "{{PLAYER_CLASS}}", "{{PLAY_BTN_DISABLED}}", "{{AUDIO_ATTR}}", "{{DATE}}"
        ).forEach { assertTrue("模板缺占位符 $it", raw.contains(it)) }

        val html = VoiceCardHtmlComposer.render(raw, content(dialogue = "指挥官，今天也要一起出航吗？"))
        assertFalse("真实模板渲染后仍有残留占位符", html.contains("{{"))
        assertTrue(html.contains("语音档案"))
        assertTrue(html.contains("绫波"))
        assertTrue(html.contains("https://example.com/voice/a.mp3"))
    }
}
