package com.azurlane.blyy.util.wiki

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * wiki 舰名归一化单测。
 *
 * 只测 [WikiUrls.normalize]（纯字符串逻辑）。
 * 不测 `page()`：它依赖 `android.net.Uri.encode`，在 JVM 单测里是 stub，
 * 真正的 URL 拼接由集成/手工验证覆盖。
 */
class WikiUrlsTest {

    @Test
    fun `strips retrofit suffix`() {
        assertEquals("企业", WikiUrls.normalize("企业.改"))
        assertEquals("加古", WikiUrls.normalize("加古.改"))
    }

    @Test
    fun `strips kai suffix`() {
        assertEquals("Enterprise", WikiUrls.normalize("EnterpriseKai"))
    }

    @Test
    fun `applies special name mappings`() {
        assertEquals("DEAD_MASTER", WikiUrls.normalize("DEAD"))
        assertEquals("BLACK★ROCK_SHOOTER", WikiUrls.normalize("BLACK★ROCK"))
    }

    @Test
    fun `keeps plain names and trims`() {
        assertEquals("企业", WikiUrls.normalize(" 企业 "))
    }
}
