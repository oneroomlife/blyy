package com.azurlane.blyy.util.gameimport

import com.azurlane.blyy.util.unity.MeshRasterizer
import com.azurlane.blyy.util.unity.SerializedFile
import com.azurlane.blyy.util.unity.UnityMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * 真实资源包 + 真实图集的端到端验证（JVM，不需要设备）。
 *
 * GL 只负责把 ASTC 贴图解成像素，所以图集这里用 Python 侧导出的 PNG 代替；
 * 被测链路与线上完全一致：`SerializedFile → UnityMesh（顶点/UV/索引）
 * → MeshRasterizer（重心插值 + UV 夹紧 + 预乘合成）`。
 *
 * 期望的画布尺寸 / 覆盖率来自独立实现（UnityPy + 逐三角形光栅化）：
 * 两者一致说明「碎片图集」确实被拼回了整图，而不是又一次把图集当成品。
 */
class PaintingMeshRealDataTest {

    private data class Case(
        val bundle: String,
        val fixture: String,
        val expectedWidth: Int,
        val expectedHeight: Int,
        val expectedCoverage: Double
    )

    private val cases = listOf(
        Case("painting/beianpudun_tex", "atlas_beianpudun_tex.png", 800, 1021, 0.4642),
        Case("painting/changshehao_n_tex", "atlas_changshehao_n_tex.png", 2016, 1504, 0.5668),
        Case("painting/dashan_3_rw_tex", "atlas_dashan_3_rw_tex.png", 2047, 1931, 0.4402),
        Case("painting/2b_2_rw_tex", "atlas_2b_2_rw_tex.png", 1728, 2047, 0.5564)
    )

    private fun loadPng(file: File): Triple<IntArray, Int, Int> {
        val image: BufferedImage = ImageIO.read(file)
        val pixels = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        return Triple(pixels, image.width, image.height)
    }

    @Test
    fun `真实图集按网格还原成完整立绘`() {
        var executed = 0
        for (case in cases) {
            val bundleFile = PaintingTestSupport.bundle(case.bundle) ?: continue
            val fixture = PaintingTestSupport.fixture(case.fixture) ?: continue
            executed++

            val atlas = loadPng(fixture)
            val (pixels, atlasWidth, atlasHeight) = atlas

            PaintingTestSupport.open(bundleFile).use { opened ->
                val def = opened.serialized.objectsOfClass(SerializedFile.CLASS_MESH).firstOrNull()
                assertTrue("${case.bundle} 应带 Mesh", def != null)
                val mesh: UnityMesh = UnityMesh.parse(opened.serialized, def!!)!!
                assertTrue(mesh.usable)
                assertTrue("索引应全部有效", mesh.hasValidIndices())

                val output = MeshRasterizer.rasterize(mesh, pixels, atlasWidth, atlasHeight)
                assertTrue("${case.bundle} 光栅化失败", output != null)
                output!!

                assertEquals("${case.bundle} 画布宽", case.expectedWidth, output.width)
                assertEquals("${case.bundle} 画布高", case.expectedHeight, output.height)

                val coverage = output.pixels.count { (it ushr 24) and 0xFF > 0 } / output.pixels.size.toDouble()
                assertEquals(
                    "${case.bundle} 覆盖率",
                    case.expectedCoverage,
                    coverage,
                    0.03
                )

                // 与独立实现（Python）逐像素比对的宽松断言：确认几何朝向/拼接一致
                val reference = PaintingTestSupport.fixture("py_${case.fixture.removePrefix("atlas_")}")
                if (reference != null) {
                    val (refPixels, refWidth, refHeight) = loadPng(reference)
                    if (refWidth == output.width && refHeight == output.height) {
                        var diff = 0.0
                        var counted = 0
                        var maxDiff = 0
                        for (i in output.pixels.indices) {
                            val ref = refPixels[i]
                            if ((ref ushr 24) and 0xFF == 0) continue
                            counted++
                            for (shift in intArrayOf(16, 8, 0)) {
                                val a = (output.pixels[i] ushr shift) and 0xFF
                                val b = (ref ushr shift) and 0xFF
                                val d = abs(a - b)
                                diff += d
                                if (d > maxDiff) maxDiff = d
                            }
                        }
                        if (counted > 0) {
                            val mean = diff / (counted * 3)
                            assertTrue("${case.bundle} 与参考实现差异过大：mean=$mean max=$maxDiff", mean < 12.0)
                        }
                    }
                }

                savePng(output.pixels, output.width, output.height, case.bundle.substringAfterLast('/'))
            }
        }
        assumeTrue("缺少真实资源包/图集夹具，跳过", executed > 0)
    }

    @Test
    fun `图层引用的贴图与网格落在对应资源包里`() {
        // prefab 里的 m_Sprite / mMesh 是跨包指针：只有把这些包一起复制下来，
        // 合成时才能按 pathID 找到它们。这里核对 2b_2 的两个图层包。
        val background = PaintingTestSupport.bundle("painting/2b_2_tex") ?: run {
            assumeTrue(false)
            return
        }
        PaintingTestSupport.open(background).use { opened ->
            val textures = com.azurlane.blyy.util.unity.UnityTextureExtractor.list(opened.serialized)
            assertTrue("背景包应有贴图", textures.isNotEmpty())
            // 贴图 pathID：prefab 记录的是 Sprite 的 pathID，Sprite.m_RD.texture 才指向它
            assertEquals(323294843067895074L, textures.first().pathId)
            assertTrue(
                "背景包应含 prefab 引用的 Sprite",
                opened.serialized.objects.any { it.pathID == 7946471572695944482L }
            )
        }

        val character = PaintingTestSupport.bundle("painting/2b_2_rw_tex") ?: return
        PaintingTestSupport.open(character).use { opened ->
            val textures = com.azurlane.blyy.util.unity.UnityTextureExtractor.list(opened.serialized)
            assertEquals(-9187271953481094693L, textures.first().pathId)
            assertTrue(
                "人物层包应含 prefab 引用的 Sprite",
                opened.serialized.objects.any { it.pathID == 6991537980068571611L }
            )
            val mesh = opened.serialized.objectsOfClass(SerializedFile.CLASS_MESH).firstOrNull()
            assertTrue("人物层应带拼装网格", mesh != null)
            assertEquals(-7142384736335288403L, mesh!!.pathID)
        }
    }

    private fun savePng(pixels: IntArray, width: Int, height: Int, name: String) {
        runCatching {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            image.setRGB(0, 0, width, height, pixels, 0, width)
            ImageIO.write(image, "png", File(PaintingTestSupport.outputRoot, "kotlin_$name.png"))
        }
    }
}
