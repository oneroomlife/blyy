package com.azurlane.blyy.util.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 真实模板 + 极端数据的**端到端渲染**回归。
 *
 * [VoiceCardHtmlRenderTest] 用的是一个手写迷你模板，只证明替换逻辑正确；
 * 这里换成 `assets/share/voice_card.html` 本体，验证的是"这张卡在实际浏览器里长什么样"
 * 的那一层：脚本能否解析、无音频分支是否真的渲染成禁用态、以及最要命的
 * **渲染产物里不能残留任何 {{...}}**（残留会在卡面上直接显示成花括号）。
 */
class VoiceCardTemplateContractTest {

    private val templateFile: File? = listOf(
        File("src/main/assets/share/voice_card.html"),
        File("app/src/main/assets/share/voice_card.html")
    ).firstOrNull { it.isFile }

    private fun template(): String {
        assumeTrue("找不到 assets 模板，跳过（非本模块工作目录）", templateFile != null)
        return templateFile!!.readText()
    }

    private fun content(
        shipName: String = "绫波",
        sceneLabel: String = "主界面",
        dialogue: String = "指挥官，今天也要一起出航吗？",
        serial: String = "NO. 07 / 128",
        avatar: String? = "data:image/jpeg;base64,AAAA",
        audioUrl: String = "https://cdn.example.com/v.mp3"
    ) = VoiceCardHtmlComposer.Content(
        shipName = shipName,
        sceneLabel = sceneLabel,
        dialogue = dialogue,
        serial = serial,
        dateLabel = "2026.11.22",
        avatarDataUri = avatar,
        audioUrl = audioUrl
    )

    @Test
    fun `真实模板渲染后不残留任何占位符`() {
        val html = VoiceCardHtmlComposer.render(template(), content())
        assertFalse(
            "残留占位符: " + Regex("\\{\\{[A-Z_]+}}").findAll(html).map { it.value }.joinToString(),
            Regex("\\{\\{[A-Z_]+}}").containsMatchIn(html)
        )
    }

    @Test
    fun `模板注释里的占位符也会被替换`() {
        // 单趟替换不跳过注释：模板里若在注释写了 {{X}}，渲染后会变成实际值。
        // 这条测试的意义是——一旦有人往注释里塞占位符，这里会立刻发现"注释泄漏进产物"。
        val html = VoiceCardHtmlComposer.render(template(), content(audioUrl = ""))
        assertFalse("注释里不能残留占位符", html.contains("{{AUDIO_ATTR}}"))
    }

    @Test
    fun `有语音时 audio 标签带 src 与 preload`() {
        val html = VoiceCardHtmlComposer.render(template(), content())
        assertTrue(html.contains("""<audio id="audio" preload="metadata"""") )
        assertTrue(html.contains("""src="https://cdn.example.com/v.mp3""""))
        assertFalse("有语音时不能出现禁用态", html.contains("player is-dead"))
    }

    @Test
    fun `无语音时 audio 无 src 且播放器禁用`() {
        val html = VoiceCardHtmlComposer.render(template(), content(audioUrl = ""))
        assertTrue(html.contains("player is-dead"))
        assertTrue(Regex("""id="play"[^>]*disabled""").containsMatchIn(html))
        // audio 标签不允许有 src（否则浏览器会去请求空地址）
        val audioTag = Regex("""<audio[^>]*>""").find(html)!!.value
        assertFalse("无语音时 audio 不能有 src: $audioTag", audioTag.contains("src="))
    }

    @Test
    fun `脚本里的元素引用都做了空值守卫`() {
        // 卡片会被各家的内置浏览器打开，DOM 被改一个 id 就足以让整段脚本抛异常，
        // 表现是"播放按钮完全没反应"且没有任何提示。这里盯住几个关键引用点。
        val html = VoiceCardHtmlComposer.render(template(), content())
        val script = html.substringAfter("<script>").substringBefore("</script>")
        assertTrue("player 必须判空", script.contains("if (player)"))
        assertTrue("hint 元素必须判空", script.contains("if (hintEl"))
        assertTrue("进度条必须判空", script.contains("if (!seek) return"))
        // 不允许出现裸引用（这些在旧版里会直接 TypeError）
        assertFalse("curEl 裸引用", Regex("""^\s*curEl\.textContent""", RegexOption.MULTILINE).containsMatchIn(script))
        assertFalse("player 裸 classList", Regex("""^\s*player\.classList""", RegexOption.MULTILINE).containsMatchIn(script))
    }

    @Test
    fun `超长台词走最小档字号`() {
        val long = "指挥官".repeat(200)
        val html = VoiceCardHtmlComposer.render(template(), content(dialogue = long))
        assertTrue(html.contains("""<p class="q-s""""))
        assertTrue("长台词不能被截断丢弃", html.contains("指挥官"))
    }

    @Test
    fun `emoji 舰名按码点取首字不产生孤立代理`() {
        val html = VoiceCardHtmlComposer.render(template(), content(shipName = "🚢舰", avatar = null))
        assertTrue(html.contains("""<span class="mono">🚢</span>"""))
        // 产物整体必须是合法 UTF-16（没有孤立代理）
        for (i in html.indices) {
            if (Character.isHighSurrogate(html[i])) {
                assertTrue(i + 1 < html.length && Character.isLowSurrogate(html[i + 1]))
            }
        }
    }

    @Test
    fun `模板体积保持在几十 KB 以内`() {
        val html = VoiceCardHtmlComposer.render(template(), content())
        val bytes = html.toByteArray(Charsets.UTF_8).size
        // 卡片的卖点是"轻量单文件"：模板本身已经 30 KB 左右，
        // 除头像外不应再膨胀（头像 base64 在测试里是 4 字节的假数据）
        assertTrue("渲染产物过大: $bytes 字节", bytes < 80 * 1024)
    }

    @Test
    fun `波形柱数量与模板占位一致`() {
        val html = VoiceCardHtmlComposer.render(template(), content())
        assertEquals(40, Regex("""<span class="bar"""").findAll(html).count())
    }
}
