package com.azurlane.blyy.util.gameimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spine 图集解析回归（样例取自真实的 `zhezhi_2.atlas`：多页 + pma + rotate）。
 *
 * 这两件事一旦出错，导入流程就会「成功但黑屏」：
 * 1. 页名解析错 → 贴图落盘名字对不上，`TextureAtlas` 找不到图片；
 * 2. 缩放时没改坐标 → 区域错位、模型撕裂。
 */
class SpineAtlasParserTest {

    private val atlasText: String by lazy {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("gameimport/zhezhi_2_trimmed.atlas")) {
            "缺少测试资源 gameimport/zhezhi_2_trimmed.atlas"
        }
        stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun `解析多页图集`() {
        val pages = SpineAtlasParser.pages(atlasText)
        assertEquals(2, pages.size)
        assertEquals("zhezhi_2.png", pages[0].name)
        assertEquals(4096, pages[0].width)
        assertEquals(4096, pages[0].height)
        assertEquals(2, pages[0].regionCount)
        assertEquals("zhezhi_22.png", pages[1].name)
        assertEquals(1, pages[1].regionCount)
    }

    @Test
    fun `区域名带空格不会误判为页`() {
        val pages = SpineAtlasParser.pages(atlasText)
        assertTrue(pages.none { it.name == "beachchair 1" })
    }

    @Test
    fun `缩放同步改写页尺寸与区域坐标`() {
        val scaled = SpineAtlasParser.scale(atlasText, 0.5f)
        assertTrue(scaled.contains("size: 2048, 2048"))
        assertTrue(scaled.contains("xy: 1, 242"))
        assertTrue(scaled.contains("size: 1434, 701"))
        assertTrue(scaled.contains("orig: 1434, 701"))
        // 负偏移按四舍五入减半
        assertTrue(scaled.contains("offset: -2, 3"))
        // 非尺寸属性保持原样
        assertTrue(scaled.contains("rotate: true"))
        assertTrue(scaled.contains("index: -1"))
        assertTrue(scaled.contains("pma: true"))
    }

    @Test
    fun `缩放后页信息保持一致`() {
        val pages = SpineAtlasParser.pages(SpineAtlasParser.scale(atlasText, 0.5f))
        assertEquals(2, pages.size)
        assertEquals(2048, pages[0].width)
        assertEquals(2048, pages[0].height)
        assertEquals(2, pages[0].regionCount)
    }

    @Test
    fun `factor 不小于 1 时原样返回`() {
        assertEquals(atlasText, SpineAtlasParser.scale(atlasText, 1f))
        assertEquals(atlasText, SpineAtlasParser.scale(atlasText, 2f))
    }

    @Test
    fun `Spine4 旋转角度归一化为 3_8 写法`() {
        val input = """
            page.png
            size: 1024,1024
            format: RGBA8888
            filter: Linear,Linear
            repeat: none
            rotate90
              rotate: 90
              xy: 10, 20
              size: 100, 200
              orig: 100, 200
              offset: 0, 0
              index: -1
            rotate180
              rotate: 180
              xy: 300, 40
              size: 60, 80
              orig: 60, 80
              offset: 0, 0
              index: -1
            rotate270
              rotate: 270
              xy: 500, 600
              size: 70, 90
              orig: 70, 90
              offset: 0, 0
              index: -1
            normal
              rotate: true
              xy: 0, 0
              size: 10, 10
              orig: 10, 10
              offset: 0, 0
              index: -1
        """.trimIndent()

        val result = SpineAtlasParser.normalizeRotations(input)
        assertEquals(2, result.flips.size)
        // 180：包围盒 (w,h) 不变
        assertEquals(
            SpineAtlasParser.RotationFlip("page.png", 300, 40, 60, 80),
            result.flips.first { it.page == "page.png" && it.x == 300 }
        )
        // 270：页上包围盒是 (h,w)
        assertEquals(
            SpineAtlasParser.RotationFlip("page.png", 500, 600, 90, 70),
            result.flips.first { it.page == "page.png" && it.x == 500 }
        )
        // 文本里不再出现角度值
        assertTrue(result.text.contains("rotate: true"))
        assertTrue(result.text.contains("rotate: false"))
        assertFalse(result.text.contains("rotate: 270"))
        assertFalse(result.text.contains("rotate: 180"))
        assertFalse(result.text.contains("rotate: 90"))
        // 归一化后仍可解析出同一页
        assertEquals(1, SpineAtlasParser.pages(result.text).size)
        assertEquals(4, SpineAtlasParser.pages(result.text).first().regionCount)
    }

    @Test
    fun `已经是 3_8 写法时不做改动`() {
        val result = SpineAtlasParser.normalizeRotations(atlasText)
        assertEquals(0, result.changedRegions)
        assertTrue(result.flips.isEmpty())
        assertEquals(atlasText, result.text)
    }

    @Test
    fun `空文本不抛异常`() {
        assertTrue(SpineAtlasParser.pages("").isEmpty())
        assertEquals("", SpineAtlasParser.scale("", 0.5f))
    }

    // ------------------------------------------------------------------ 真实图集

    /** 真实资源包里的完整图集（`zhezhi_2.atlas`，3 页 103 区域，含 8 个角度旋转） */
    private val fullAtlas: String by lazy {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("gameimport/zhezhi_2_full.atlas")) {
            "缺少测试资源 gameimport/zhezhi_2_full.atlas"
        }
        stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun `真实图集页信息与缩放后的页信息一致`() {
        val pages = SpineAtlasParser.pages(fullAtlas)
        assertEquals(3, pages.size)
        assertEquals(listOf("zhezhi_2.png", "zhezhi_22.png", "zhezhi_23.png"), pages.map { it.name })
        assertTrue(pages.all { it.width == 4096 && it.height == 4096 })
        assertEquals(103, pages.sumOf { it.regionCount })

        val scaled = SpineAtlasParser.pages(SpineAtlasParser.scale(fullAtlas, 0.5f))
        assertEquals(3, scaled.size)
        assertTrue(scaled.all { it.width == 2048 && it.height == 2048 })
        assertEquals(103, scaled.sumOf { it.regionCount })
    }

    @Test
    fun `真实图集的角度旋转全部归一化`() {
        val result = SpineAtlasParser.normalizeRotations(fullAtlas)
        assertEquals(8, result.changedRegions)
        assertEquals(8, result.flips.size)
        assertTrue(result.flips.all { it.page in setOf("zhezhi_2.png", "zhezhi_22.png", "zhezhi_23.png") })
        // 输出里不再有角度值，且仍是 3 页 103 区域
        listOf("rotate: 90", "rotate: 180", "rotate: 270").forEach {
            assertFalse(result.text.contains(it))
        }
        assertEquals(3, SpineAtlasParser.pages(result.text).size)
        assertEquals(103, SpineAtlasParser.pages(result.text).sumOf { it.regionCount })
    }
}
