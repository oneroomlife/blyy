package com.azurlane.blyy.util.gameimport

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Spine 图集解析回归。
 *
 * 三件事一旦出错，导入流程就会「成功但黑屏/错位」：
 * 1. 页名解析错 → 贴图落盘名字对不上，`TextureAtlas` 找不到图片；
 * 2. 缩放时坐标没按**页上足迹**改 → 区域错位、模型撕裂；
 * 3. `rotate` 被错误改写 → spine 3.8 的 `updateUVs` 走错分支，部件乱飞。
 *
 * 第 3 点曾经是真实 bug：旧实现把 `270 → true(90)`、`180 → false` 并翻转页像素，
 * 导致采样矩形整体平移数百像素（实测 `shadow_R1` 偏移 (434, −613) px）。
 * 现在的约定是**图集原样交给运行时**，下面用 libgdx 真实的 `TextureAtlasData`
 * 把这条语义钉死。
 */
class SpineAtlasParserTest {

    private val atlasText: String by lazy { readResource("gameimport/zhezhi_2_trimmed.atlas") }

    /** 真实资源包里的完整图集（`zhezhi_2.atlas`，3 页 103 区域，含 8 个角度旋转） */
    private val fullAtlas: String by lazy { readResource("gameimport/zhezhi_2_full.atlas") }

    private fun readResource(path: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "缺少测试资源 $path"
        }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    // ------------------------------------------------------------------ 页解析

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
        assertTrue(SpineAtlasParser.pages(atlasText).none { it.name == "beachchair 1" })
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
    fun `空文本不抛异常`() {
        assertTrue(SpineAtlasParser.pages("").isEmpty())
        assertEquals("", SpineAtlasParser.scale("", 0.5f))
    }

    // ------------------------------------------------------------------ 缩放

    @Test
    fun `缩放同步改写页尺寸与区域坐标`() {
        val scaled = SpineAtlasParser.scale(atlasText, 0.5f)
        assertTrue(scaled.contains("size: 2048, 2048"))
        assertTrue(scaled.contains("xy: 1, 242"))
        assertTrue(scaled.contains("orig: 1434, 701"))
        assertTrue(scaled.contains("offset: -2, 3"))
        // 非尺寸属性保持原样（rotate 绝不改写）
        assertTrue(scaled.contains("rotate: true"))
        assertTrue(scaled.contains("index: -1"))
        assertTrue(scaled.contains("pma: true"))
    }

    @Test
    fun `factor 不小于 1 时原样返回`() {
        assertEquals(atlasText, SpineAtlasParser.scale(atlasText, 1f))
        assertEquals(atlasText, SpineAtlasParser.scale(atlasText, 2f))
    }

    @Test
    fun `缩放后每个区域仍落在页内且包围盒闭合`() {
        val scaled = SpineAtlasParser.scale(fullAtlas, 0.75f)
        val pages = parseAtlas(scaled)
        assertEquals(3, pages.size)
        pages.forEach { page ->
            assertEquals(3072, page.width)
            assertEquals(3072, page.height)
            page.regions.forEach { region ->
                assertTrue(
                    "${region.name} 越界：x=${region.left} w=${region.footprintW}",
                    region.right <= page.width
                )
                assertTrue(
                    "${region.name} 越界：y=${region.top} h=${region.footprintH}",
                    region.bottom <= page.height
                )
                assertTrue("${region.name} 尺寸非法", region.width >= 1 && region.height >= 1)
            }
        }
    }

    @Test
    fun `缩放不改变页数与区域数`() {
        val before = parseAtlas(fullAtlas)
        val after = parseAtlas(SpineAtlasParser.scale(fullAtlas, 0.5f))
        assertEquals(before.size, after.size)
        assertEquals(before.sumOf { it.regions.size }, after.sumOf { it.regions.size })
    }

    /**
     * `size` 是**未旋转**尺寸，页上足迹在 90/270 时交换宽高。
     *
     * 缩放必须在页上足迹上做，否则宽高会先互换再缩放，降采样后整块错位。
     */
    @Test
    fun `旋转区域按页上足迹缩放并保持轴向`() {
        val input = """
            page.png
            size: 1000,1000
            format: RGBA8888
            filter: Linear,Linear
            repeat: none
            spun270
              rotate: 270
              xy: 100, 200
              size: 120, 340
              orig: 120, 340
              offset: 0, 0
              index: -1
            spun90
              rotate: true
              xy: 400, 500
              size: 80, 60
              orig: 80, 60
              offset: 0, 0
              index: -1
        """.trimIndent()

        val scaled = SpineAtlasParser.scale(input, 0.5f)
        val pages = parseAtlas(scaled)
        val regions = pages.single().regions.associateBy { it.name }

        // 270：页上足迹 (340, 120) → 缩放后 (170, 60) → size 反推回 (60, 170)
        val spun270 = regions.getValue("spun270")
        assertEquals(50, spun270.left)
        assertEquals(100, spun270.top)
        assertEquals(60, spun270.width)
        assertEquals(170, spun270.height)
        assertEquals(170, spun270.footprintW)
        assertEquals(60, spun270.footprintH)

        // 90：页上足迹 (60, 80) → 缩放后 (30, 40) → size 反推回 (40, 30)
        val spun90 = regions.getValue("spun90")
        assertEquals(200, spun90.left)
        assertEquals(250, spun90.top)
        assertEquals(40, spun90.width)
        assertEquals(30, spun90.height)
        assertEquals(30, spun90.footprintW)
        assertEquals(40, spun90.footprintH)
    }

    // ------------------------------------------------------------------ rotate 语义

    /** 缩放不会改写 `rotate`，图集角度原样保留（180/270 由 spine 3.8 原生支持） */
    @Test
    fun `缩放不改写 rotate 字段`() {
        val input = """
            page.png
            size: 1000,1000
            format: RGBA8888
            filter: Linear,Linear
            repeat: none
            a
              rotate: 180
              xy: 10, 20
              size: 60, 80
              orig: 60, 80
              offset: 0, 0
              index: -1
            b
              rotate: 270
              xy: 300, 40
              size: 70, 90
              orig: 70, 90
              offset: 0, 0
              index: -1
        """.trimIndent()

        val scaled = SpineAtlasParser.scale(input, 0.5f)
        assertTrue(scaled.contains("rotate: 180"))
        assertTrue(scaled.contains("rotate: 270"))
        assertFalse(scaled.contains("rotate: false"))
        assertFalse(scaled.contains("rotate: true"))
    }

    @Test
    fun `RotationMode 解析三种写法`() {
        assertEquals(0, RotationMode.degreesOf("false"))
        assertEquals(90, RotationMode.degreesOf("true"))
        assertEquals(90, RotationMode.degreesOf("90"))
        assertEquals(180, RotationMode.degreesOf("180"))
        assertEquals(270, RotationMode.degreesOf("270"))
        assertEquals(0, RotationMode.degreesOf("0"))
        assertEquals(0, RotationMode.degreesOf(""))
        // 非法值按 0 处理，不抛异常
        assertEquals(0, RotationMode.degreesOf("xyz"))
        // 只有 90/270 交换页上足迹的轴向
        assertTrue(RotationMode.swapsAxes(90))
        assertTrue(RotationMode.swapsAxes(270))
        assertFalse(RotationMode.swapsAxes(0))
        assertFalse(RotationMode.swapsAxes(180))
    }

    /**
     * 用 libgdx 真实解析器确认：`rotate: 180/270` 会被算成 `degrees`，
     * 而不是被 `Boolean.parseBoolean` 静默当成未旋转。
     *
     * 这是「不需要归一化」的核心依据——一旦这条失败，说明依赖升级改变了语义，
     * 才需要重新评估归一化策略。
     */
    @Test
    fun `libgdx 把角度写法解析成 degrees`() {
        val dir = File.createTempFile("rotate_semantics", "").apply {
            delete()
            mkdirs()
        }
        try {
            val input = """
                page.png
                size: 200, 200
                format: RGBA8888
                filter: Linear,Linear
                repeat: none
                spun90
                  rotate: 90
                  xy: 10, 20
                  size: 30, 40
                  orig: 30, 40
                  offset: 0, 0
                  index: -1
                spun180
                  rotate: 180
                  xy: 50, 60
                  size: 25, 35
                  orig: 25, 35
                  offset: 0, 0
                  index: -1
                spun270
                  rotate: 270
                  xy: 90, 100
                  size: 45, 15
                  orig: 45, 15
                  offset: 0, 0
                  index: -1
                spunTrue
                  rotate: true
                  xy: 120, 130
                  size: 20, 10
                  orig: 20, 10
                  offset: 0, 0
                  index: -1
            """.trimIndent()
            val file = File(dir, "p.atlas").apply { writeText(input) }
            val data = TextureAtlas.TextureAtlasData(FileHandle(file), FileHandle(dir), false)
            val byName = data.regions.associateBy { it.name }

            assertEquals(90, byName.getValue("spun90").degrees)
            assertEquals(180, byName.getValue("spun180").degrees)
            assertEquals(270, byName.getValue("spun270").degrees)
            assertEquals(90, byName.getValue("spunTrue").degrees)
            // degrees==90 时 libgdx 交换宽高（页上足迹），180/270 保持 size 轴向
            assertTrue(byName.getValue("spun90").rotate)
            assertFalse(byName.getValue("spun180").rotate)
            assertFalse(byName.getValue("spun270").rotate)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 真实图集的非 0° 区域全部保留原角度，且数量可审计 */
    @Test
    fun `真实图集保持原角度并统计旋转区域`() {
        val report = SpineRotationAudit.inspect(fullAtlas)
        // zhezhi_2：15 个 true(=90)、2 个 180、6 个 270
        assertEquals(23, report.changedRegions)
        assertEquals(15, report.counts[90])
        assertEquals(2, report.counts[180])
        assertEquals(6, report.counts[270])
        // 审计不改写文本
        assertTrue(fullAtlas.contains("rotate: 270"))
        assertTrue(fullAtlas.contains("rotate: 180"))
    }

    // ------------------------------------------------------------------ 页名改写

    /**
     * 多骨骼包（同一资源包里有 `xxx.skel` 与 `xxx_hx.skel`）常声明同名页。
     * 导出时必须给后续骨骼的页改名，否则后写的 PNG 覆盖前一套，
     * 前一套就会整块采到别人的像素。
     */
    @Test
    fun `页名改写只动页头不动区域名`() {
        val input = """
            zhaohe_4.png
            size: 2048,2048
            format: RGBA8888
            filter: Linear,Linear
            repeat: none
            body
              rotate: false
              xy: 10, 20
              size: 30, 40
              orig: 30, 40
              offset: 0, 0
              index: -1
            zhaohe_4.png
              rotate: 90
              xy: 50, 60
              size: 15, 25
              orig: 15, 25
              offset: 0, 0
              index: -1
        """.trimIndent()

        val rewritten = SpineAtlasParser.rewritePageNames(
            input,
            listOf("zhaohe_4.png" to "zhaohe_4_hx-zhaohe_4.png")
        )
        // 页头被改写
        assertTrue(rewritten.startsWith("zhaohe_4_hx-zhaohe_4.png"))
        val pages = SpineAtlasParser.pages(rewritten)
        assertEquals(1, pages.size)
        assertEquals("zhaohe_4_hx-zhaohe_4.png", pages.first().name)
        // 区域名（哪怕与旧页名同形）保持不变，否则骨骼会找不到 attachment
        assertTrue(rewritten.contains("\nzhaohe_4.png\n  rotate: 90"))
        assertEquals(2, pages.first().regionCount)
    }

    @Test
    fun `没有改名时原样返回`() {
        assertEquals(atlasText, SpineAtlasParser.rewritePageNames(atlasText, emptyList()))
        assertEquals(
            atlasText,
            SpineAtlasParser.rewritePageNames(atlasText, listOf("zhezhi_2.png" to "zhezhi_2.png"))
        )
    }

    // ------------------------------------------------------------------ 测试用最小解析

    private data class RawRegion(
        val name: String,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val degrees: Int
    ) {
        /** 页上足迹宽（90/270 时与 `size` 交换） */
        val footprintW: Int get() = if (degrees == 90 || degrees == 270) height else width

        /** 页上足迹高（90/270 时与 `size` 交换） */
        val footprintH: Int get() = if (degrees == 90 || degrees == 270) width else height

        val right: Int get() = left + footprintW
        val bottom: Int get() = top + footprintH
    }

    private data class RawPage(
        val name: String,
        val width: Int,
        val height: Int,
        val regions: List<RawRegion>
    )

    /** 只解析页尺寸与区域矩形，用于校验缩放后的几何关系 */
    private fun parseAtlas(atlas: String): List<RawPage> {
        val lines = atlas.replace("\r\n", "\n").split("\n")
        val pages = mutableListOf<RawPage>()
        var pageName = ""
        var pageW = 0
        var pageH = 0
        val regions = mutableListOf<RawRegion>()
        var name = ""
        var left = 0
        var top = 0
        var width = 0
        var height = 0
        var degrees = 0

        fun flushRegion() {
            if (name.isNotEmpty()) {
                regions += RawRegion(name, left, top, width, height, degrees)
            }
            name = ""
            left = 0
            top = 0
            width = 0
            height = 0
            degrees = 0
        }

        fun flushPage() {
            if (pageName.isNotEmpty()) {
                pages += RawPage(pageName, pageW, pageH, regions.toList())
                regions.clear()
            }
        }

        var index = 0
        while (index < lines.size) {
            val raw = lines[index]
            val line = raw.trim()
            if (line.isEmpty()) {
                index++
                continue
            }
            val indented = raw.startsWith(" ") || raw.startsWith("\t")
            val colon = line.indexOf(':')
            if (!indented && colon < 0) {
                var next = ""
                for (j in index + 1 until lines.size) {
                    if (lines[j].isNotBlank()) {
                        next = lines[j].trim()
                        break
                    }
                }
                if (next.startsWith("size:", ignoreCase = true)) {
                    flushRegion()
                    flushPage()
                    pageName = line
                } else {
                    flushRegion()
                    name = line
                }
                index++
                continue
            }
            if (colon > 0) {
                val key = line.substring(0, colon).trim().lowercase()
                val numbers = line.substring(colon + 1)
                    .split(',', ' ')
                    .mapNotNull { it.trim().toIntOrNull() }
                when (key) {
                    "size" -> if (!indented) {
                        if (numbers.size >= 2) {
                            pageW = numbers[0]
                            pageH = numbers[1]
                        }
                    } else if (numbers.size >= 2) {
                        width = numbers[0]
                        height = numbers[1]
                    }

                    "xy" -> if (numbers.size >= 2) {
                        left = numbers[0]
                        top = numbers[1]
                    }

                    "rotate" -> degrees = RotationMode.degreesOf(line.substringAfter(':'))
                }
            }
            index++
        }
        flushRegion()
        flushPage()
        return pages
    }
}
