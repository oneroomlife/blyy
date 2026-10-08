package com.azurlane.blyy.util

import com.azurlane.blyy.data.model.VoiceLanguage
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 台词块解析回归测试。
 *
 * 用例全部来自真实 wiki 页面的实测 DOM 结构（2026-10 抓取）：
 *  - 圣地亚哥（新结构）：一个 wrap 内 jp/zh 两行共用一条音频，日文行在前——
 *    旧实现取第一个 .ship_word_line 导致整页显示日文，本组用例防止回归
 *  - 逸仙（旧结构）：块内每个语言各占一个 wrap，各带音频
 *  - 情人节礼物表：无 data-lang、无音频的纯中文行
 */
class VoiceBlockParserTest {

    // 结构 A：块与 wrap 是同一个 div（圣地亚哥·自我介绍实测 DOM）
    private val newStructureBlock = """
        <div class="ship_word_block ship_word_media_wrap" data-key="profile" data-key-i="1">
          <p class="ship_word_line" data-lang="jp" data-key="profile" data-key-i="1">
            サンディエゴだよ！エンタープライズの姉貴の次に星をもらった艦だって！</p>
          <p class="ship_word_line" data-lang="zh" data-key="profile" data-key-i="1">
            我是圣地亚哥！听说我是战争中获得星星仅次于企业大姐头的船。</p>
          <div class="sm-bar" style="display: block;">&#160;
            <div class="sm-audio-src"><a rel="nofollow" class="external free"
              href="https://patchwiki.biligame.com/images/blhx/f/f2/jp-shared.mp3">audio</a></div>
          </div>
        </div>
    """.trimIndent()

    @Test
    fun `新结构圣地亚哥 - 日文行在前也取中文台词，共用音频`() {
        val block = Jsoup.parseBodyFragment(newStructureBlock).body().child(0)
        val lines = mutableListOf<com.azurlane.blyy.data.model.VoiceLine>()
        VoiceBlockParser.parse(block, "默认装扮", "自我介绍", lines)

        assertEquals(1, lines.size)
        val line = lines[0]
        assertEquals("我是圣地亚哥！听说我是战争中获得星星仅次于企业大姐头的船。", line.dialogue)
        assertEquals("https://patchwiki.biligame.com/images/blhx/f/f2/jp-shared.mp3", line.audioUrlCn)
        assertEquals("https://patchwiki.biligame.com/images/blhx/f/f2/jp-shared.mp3", line.audioUrlJp)
        assertEquals("默认装扮", line.skinName)
        assertEquals("自我介绍", line.scene)
    }

    @Test
    fun `新结构主界面 - td 内三个平级块各出一条中文台词`() {
        val td = Jsoup.parseBodyFragment(
            """
            <table><tr><td>
              <div class="ship_word_block ship_word_media_wrap" data-key-i="1">
                <p class="ship_word_line" data-lang="jp">jp1</p>
                <p class="ship_word_line" data-lang="zh">中文一</p>
                <div class="sm-bar"><div class="sm-audio-src"><a href="https://patchwiki.biligame.com/a.mp3">a</a></div></div>
              </div>
              <div class="ship_word_block ship_word_media_wrap" data-key-i="2">
                <p class="ship_word_line" data-lang="jp">jp2</p>
                <p class="ship_word_line" data-lang="zh">中文二</p>
                <div class="sm-bar"><div class="sm-audio-src"><a href="https://patchwiki.biligame.com/b.mp3">b</a></div></div>
              </div>
              <div class="ship_word_block ship_word_media_wrap" data-key-i="3">
                <p class="ship_word_line" data-lang="jp">jp3</p>
                <p class="ship_word_line" data-lang="zh">中文三</p>
                <div class="sm-bar"><div class="sm-audio-src"><a href="https://patchwiki.biligame.com/c.mp3">c</a></div></div>
              </div>
            </td></tr></table>
            """.trimIndent()
        ).select("td").first()!!

        val lines = mutableListOf<com.azurlane.blyy.data.model.VoiceLine>()
        VoiceBlockParser.parse(td, "默认装扮", "主界面", lines)

        assertEquals(listOf("中文一", "中文二", "中文三"), lines.map { it.dialogue })
        assertEquals(3, lines.count { it.scene == "主界面" })
        assertEquals("https://patchwiki.biligame.com/b.mp3", lines[1].audioUrlCn)
    }

    // 结构 B：块内每语言各一个 wrap（逸仙·自我介绍实测 DOM）
    private val oldStructureBlock = """
        <div class="ship_word_block" data-key="profile" data-key-i="1">
          <div class="ship_word_media_wrap">
            <p class="ship_word_line" data-lang="jp">東煌海軍第一艦隊所属、逸仙と申します。</p>
            <div class="sm-bar"><div class="sm-audio-src"><a href="https://patchwiki.biligame.com/images/yat-jp.mp3">a</a></div></div>
          </div>
          <div class="ship_word_media_wrap">
            <p class="ship_word_line" data-lang="zh">东煌海军第一舰队，逸仙号。</p>
            <div class="sm-bar"><div class="sm-audio-src"><a href="https://patchwiki.biligame.com/images/yat-cn.mp3">a</a></div></div>
          </div>
        </div>
    """.trimIndent()

    @Test
    fun `旧结构逸仙 - 双 wrap 按语言合并，中日语频各归其位`() {
        val block = Jsoup.parseBodyFragment(oldStructureBlock).body().child(0)
        val lines = mutableListOf<com.azurlane.blyy.data.model.VoiceLine>()
        VoiceBlockParser.parse(block, "默认装扮", "自我介绍", lines)

        assertEquals(1, lines.size)
        val line = lines[0]
        assertEquals("东煌海军第一舰队，逸仙号。", line.dialogue)
        assertEquals("https://patchwiki.biligame.com/images/yat-cn.mp3", line.audioUrlCn)
        assertEquals("https://patchwiki.biligame.com/images/yat-jp.mp3", line.audioUrlJp)
        assertTrue(line.hasDualAudio)
        assertEquals("https://patchwiki.biligame.com/images/yat-jp.mp3", line.getActiveAudioUrl(VoiceLanguage.JP))
    }

    @Test
    fun `旧结构 - 日文侧无音频时中文侧音频兜底`() {
        val block = Jsoup.parseBodyFragment(
            """
            <div class="ship_word_block">
              <div class="ship_word_media_wrap">
                <p class="ship_word_line" data-lang="jp">jpテキスト</p>
              </div>
              <div class="ship_word_media_wrap">
                <p class="ship_word_line" data-lang="zh">中文台词</p>
                <div class="sm-bar"><div class="sm-audio-src"><a href="https://patchwiki.biligame.com/cn-only.mp3">a</a></div></div>
              </div>
            </div>
            """.trimIndent()
        ).body().child(0)

        val lines = mutableListOf<com.azurlane.blyy.data.model.VoiceLine>()
        VoiceBlockParser.parse(block, "默认装扮", "查看详情", lines)

        assertEquals(1, lines.size)
        assertEquals("中文台词", lines[0].dialogue)
        assertEquals("https://patchwiki.biligame.com/cn-only.mp3", lines[0].audioUrlCn)
        assertEquals("https://patchwiki.biligame.com/cn-only.mp3", lines[0].audioUrlJp)
    }

    @Test
    fun `旧结构仅中文有音频 - 单语言 wrap 不误并，独立成条`() {
        val block = Jsoup.parseBodyFragment(
            """
            <div class="ship_word_block">
              <div class="ship_word_media_wrap">
                <p class="ship_word_line" data-lang="zh">中配专属台词</p>
                <div class="sm-bar"><div class="sm-audio-src"><a href="https://patchwiki.biligame.com/zh-solo.mp3">a</a></div></div>
              </div>
            </div>
            """.trimIndent()
        ).body().child(0)

        val lines = mutableListOf<com.azurlane.blyy.data.model.VoiceLine>()
        VoiceBlockParser.parse(block, "默认装扮", "主界面", lines)

        assertEquals(1, lines.size)
        assertEquals("中配专属台词", lines[0].dialogue)
        assertEquals("https://patchwiki.biligame.com/zh-solo.mp3", lines[0].audioUrlCn)
    }

    @Test
    fun `情人节礼物 - 无语言标记无音频的纯中文行被跳过`() {
        val block = Jsoup.parseBodyFragment(
            """
            <div class="ship_word_block ship_word_media_wrap">
              <p class="ship_word_line">情人节？虽然不是太明白，不过把这个给指挥官就好了吧！</p>
            </div>
            """.trimIndent()
        ).body().child(0)

        val lines = mutableListOf<com.azurlane.blyy.data.model.VoiceLine>()
        VoiceBlockParser.parse(block, "默认装扮", "2018年", lines)

        assertTrue(lines.isEmpty())
    }

    @Test
    fun `无语言标记有音频 - 假名启发式归类日文`() {
        val block = Jsoup.parseBodyFragment(
            """
            <div class="ship_word_block ship_word_media_wrap">
              <p class="ship_word_line">ハロー、私はサンディエゴ！</p>
              <div class="sm-bar"><div class="sm-audio-src"><a href="https://patchwiki.biligame.com/legacy.mp3">a</a></div></div>
            </div>
            """.trimIndent()
        ).body().child(0)

        val lines = mutableListOf<com.azurlane.blyy.data.model.VoiceLine>()
        VoiceBlockParser.parse(block, "默认装扮", "获取台词", lines)

        assertEquals(1, lines.size)
        assertEquals("ハロー、私はサンディエゴ！", lines[0].dialogue)
        assertEquals("https://patchwiki.biligame.com/legacy.mp3", lines[0].audioUrlJp)
        assertEquals("https://patchwiki.biligame.com/legacy.mp3", lines[0].audioUrlCn)
    }

    @Test
    fun `誓约块 - 场景名追加誓约后缀`() {
        val block = Jsoup.parseBodyFragment(
            """
            <div class="ship_word_block ship_word_media_wrap">
              <span title="誓约">【誓约】</span>
              <p class="ship_word_line" data-lang="jp">誓約jp</p>
              <p class="ship_word_line" data-lang="zh">誓约中文</p>
              <div class="sm-bar"><div class="sm-audio-src"><a href="https://patchwiki.biligame.com/oath.mp3">a</a></div></div>
            </div>
            """.trimIndent()
        ).body().child(0)

        val lines = mutableListOf<com.azurlane.blyy.data.model.VoiceLine>()
        VoiceBlockParser.parse(block, "默认装扮", "誓约台词", lines)

        assertEquals(1, lines.size)
        assertEquals("誓约台词(誓约)", lines[0].scene)
        assertEquals("誓约中文", lines[0].dialogue)
    }
}
