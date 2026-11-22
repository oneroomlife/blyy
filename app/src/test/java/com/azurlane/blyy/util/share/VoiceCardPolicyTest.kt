package com.azurlane.blyy.util.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 台词语音卡的**策略层**回归。
 *
 * 这些规则被四个文件共用（两个合成器 + 分享出口 + 素材层），
 * 一旦漂移就会出现「同一句台词两个文件名 / 卡片里点了没反应 / 落盘文件名是乱码」，
 * 而这三类问题在设备上都极难复现，所以在这里用纯函数钉死。
 */
class VoiceCardPolicyTest {

    // ── URL 可用性 ──

    @Test
    fun `只接受 http 与 https 绝对地址`() {
        assertTrue(VoiceCardPolicy.isHttpUrl("https://cdn.example.com/v.mp3"))
        assertTrue(VoiceCardPolicy.isHttpUrl("http://cdn.example.com/v.mp3"))
        assertTrue(VoiceCardPolicy.isHttpUrl("  https://cdn.example.com/v.mp3  "))
        assertTrue("大小写不敏感", VoiceCardPolicy.isHttpUrl("HTTPS://cdn.example.com/v.mp3"))
    }

    @Test
    fun `相对路径与危险 scheme 一律判为无语音`() {
        // wiki 侧解析出的字段偶尔是相对路径，嵌进卡片就是个死按钮
        assertFalse(VoiceCardPolicy.isHttpUrl("/audio/voice.mp3"))
        assertFalse(VoiceCardPolicy.isHttpUrl("audio/voice.mp3"))
        assertFalse(VoiceCardPolicy.isHttpUrl(""))
        assertFalse(VoiceCardPolicy.isHttpUrl("   "))
        assertFalse(VoiceCardPolicy.isHttpUrl("javascript:alert(1)"))
        assertFalse(VoiceCardPolicy.isHttpUrl("file:///etc/passwd"))
        assertFalse(VoiceCardPolicy.isHttpUrl("content://media/external/audio/1"))
        assertFalse("缺主机名的 http 也不能放行", VoiceCardPolicy.isHttpUrl("http://"))
        assertFalse(VoiceCardPolicy.isHttpUrl("https:/cdn.example.com/v.mp3"))
    }

    // ── 音频文件名 ──

    @Test
    fun `同一句台词的中配与日配不会互相覆盖`() {
        val cn = VoiceCardPolicy.audioFileName(
            "绫波", "主界面", "https://cdn.example.com/cn/main_01.mp3"
        )
        val jp = VoiceCardPolicy.audioFileName(
            "绫波", "主界面", "https://cdn.example.com/jp/main_01.mp3"
        )
        assertNotEquals("中配/日配必须是两个文件", cn, jp)
        assertTrue(cn.endsWith(".mp3"))
        assertTrue(jp.endsWith(".mp3"))
    }

    @Test
    fun `同一 URL 生成的文件名稳定且可复现`() {
        val url = "https://cdn.example.com/v.mp3?x=1&y=2"
        assertEquals(
            VoiceCardPolicy.audioFileName("绫波", "主界面", url),
            VoiceCardPolicy.audioFileName("绫波", "主界面", url)
        )
    }

    @Test
    fun `audioFileName 里的非法字符被净化`() {
        val name = VoiceCardPolicy.audioFileName(
            "绫波/改", "主界面:触摸", "https://cdn.example.com/v.mp3"
        )
        assertFalse("文件名里不能出现路径分隔符", name.contains('/'))
        assertFalse(name.contains(':'))
        assertTrue(name.endsWith(".mp3"))
    }

    // ── 码点安全 ──

    @Test
    fun `截断不会切出孤立代理项`() {
        // "👩‍✈️" 是 ZWJ 序列，这里只验证不产生孤立 high surrogate
        val emoji = "😀😀😀"
        val cut = VoiceCardPolicy.takeCodePoints(emoji, 2)
        assertEquals(2, cut.codePointCount(0, cut.length))
        assertEquals("😀😀", cut)
        // 每一个 char 都不能是孤立代理
        for (i in cut.indices) {
            if (Character.isHighSurrogate(cut[i])) {
                assertTrue("high surrogate 后必须跟 low", i + 1 < cut.length && Character.isLowSurrogate(cut[i + 1]))
            }
            if (Character.isLowSurrogate(cut[i])) {
                assertTrue("low surrogate 前必须有 high", i > 0 && Character.isHighSurrogate(cut[i - 1]))
            }
        }
    }

    @Test
    fun `码点截断在纯中文与空串上边界正确`() {
        assertEquals("", VoiceCardPolicy.takeCodePoints("", 5))
        assertEquals("", VoiceCardPolicy.takeCodePoints("绫波", 0))
        assertEquals("绫", VoiceCardPolicy.takeCodePoints("绫波", 1))
        assertEquals("绫波", VoiceCardPolicy.takeCodePoints("绫波", 99))
    }

    @Test
    fun `文件名净化保留中文并压掉控制字符`() {
        val name = VoiceCardPolicy.safeName("绫波\u0000\t 改")
        assertTrue(name.startsWith("绫波"))
        assertFalse(name.contains('\u0000'))
        assertFalse(name.contains('\t'))
    }

    @Test
    fun `文件名全非法时回退到默认值`() {
        assertEquals("voice", VoiceCardPolicy.safeName("///:::", fallback = "voice"))
        assertEquals("voice", VoiceCardPolicy.safeName("   ", fallback = "voice"))
        assertEquals("voice", VoiceCardPolicy.safeName("", fallback = "voice"))
    }

    @Test
    fun `连续非法字符折叠成一个下划线`() {
        // 「///:::」不折叠会变成「______」，既难看又容易和别的舰娘撞名
        assertEquals("A_B", VoiceCardPolicy.safeName("A //// ::: B"))
    }

    @Test
    fun `文件名按码点截断且不超过上限`() {
        val long = "😀".repeat(100)
        val name = VoiceCardPolicy.safeName(long, max = 40)
        assertEquals(40, name.codePointCount(0, name.length))
        // 结尾不能是孤立高代理（说明被切断了）
        assertFalse(Character.isHighSurrogate(name.last()))
    }

    // ── 分享文案 ──

    @Test
    fun `分享文案摘录去掉首尾空白并按码点截断`() {
        assertEquals("指挥官", VoiceCardPolicy.excerpt("  指挥官  "))
        val long = "字".repeat(500)
        assertEquals(200, VoiceCardPolicy.excerpt(long).length)
        assertEquals("", VoiceCardPolicy.excerpt("   "))
    }
}
