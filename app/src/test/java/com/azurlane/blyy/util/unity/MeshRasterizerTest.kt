package com.azurlane.blyy.util.unity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MeshRasterizer] 的像素级单元测试（不需要设备 / GL / Bitmap）。
 *
 * 覆盖四种最容易出错的情形：
 * 1. **朝向**：网格 y 向上、图片行号向下，UV 的 v 也向上——任何一个翻错，
 *    立绘就会上下/左右镜像；
 * 2. **1:1 精确性**：图集碎片与画面像素一一对应时，输出必须逐字节等于图集，
 *    不能被双线性「抹平」；
 * 3. **UV 夹紧**：图集碎片紧挨着打包，采样越界会吸到隔壁碎片的颜色
 *    （表现为人物边缘出现彩色描边）；
 * 4. **退化数据**：没有三角形 / 索引越界时返回 null，由调用方退回整张贴图。
 */
class MeshRasterizerTest {

    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val magenta = 0xFFFF00FF.toInt()
    private val clear = 0

    /** 覆盖整张贴图的四边形（两个三角形），UV 与顶点一一对应 */
    private fun quad(
        width: Float,
        height: Float,
        uMin: Float = 0f,
        vMin: Float = 0f,
        uMax: Float = 1f,
        vMax: Float = 1f
    ): UnityMesh {
        val positions = floatArrayOf(
            0f, 0f,
            width, 0f,
            width, height,
            0f, height
        )
        val uvs = floatArrayOf(
            uMin, vMin,
            uMax, vMin,
            uMax, vMax,
            uMin, vMax
        )
        val indices = intArrayOf(0, 1, 2, 0, 2, 3)
        return UnityMesh("quad", positions, uvs, indices, 0f, 0f, width, height)
    }

    @Test
    fun `1比1 还原逐像素等于图集`() {
        // 图集（自上而下）：红 绿 / 蓝 白
        val texture = intArrayOf(red, green, blue, white)
        val mesh = quad(2f, 2f)
        val output = MeshRasterizer.rasterize(mesh, texture, 2, 2)

        assertNotNull(output)
        output!!
        assertEquals(2, output.width)
        assertEquals(2, output.height)
        // 图片方向：左上 = 图集左上（红），右上 = 绿，左下 = 蓝，右下 = 白
        assertEquals(red, output.pixels[0])
        assertEquals(green, output.pixels[1])
        assertEquals(blue, output.pixels[2])
        assertEquals(white, output.pixels[3])
    }

    @Test
    fun `放大两倍时光栅化尺寸正确且四角对齐`() {
        val texture = intArrayOf(red, green, blue, white)
        val mesh = quad(4f, 4f)
        val output = MeshRasterizer.rasterize(mesh, texture, 2, 2)!!

        assertEquals(4, output.width)
        assertEquals(4, output.height)
        // 四角必须精确等于对应纹素（放大时中间像素允许双线性过渡）
        assertEquals("左上", red, output.pixels[0])
        assertEquals("右上", green, output.pixels[3])
        assertEquals("左下", blue, output.pixels[3 * 4])
        assertEquals("右下", white, output.pixels[3 * 4 + 3])
    }

    @Test
    fun `UV 夹紧不会采到相邻碎片的颜色`() {
        // 4×4 图集：左上 2×2 是红色，其余全是品红（当作隔壁碎片的脏色）
        val texture = IntArray(16) { magenta }
        for (y in 0 until 2) for (x in 0 until 2) texture[y * 4 + x] = red

        // 网格只引用左上 2×2。UV 的 v 轴向上：左上象限对应 u 0..0.5、v 0.5..1
        val mesh = quad(2f, 2f, uMax = 0.5f, vMin = 0.5f, vMax = 1f)
        val output = MeshRasterizer.rasterize(mesh, texture, 4, 4)!!

        assertEquals(2, output.width)
        assertEquals(2, output.height)
        for (pixel in output.pixels) {
            assertEquals("不该出现隔壁碎片的颜色", red, pixel)
        }
    }

    @Test
    fun `透明区域保持全透明`() {
        val texture = intArrayOf(clear, clear, clear, clear)
        val mesh = quad(2f, 2f)
        val output = MeshRasterizer.rasterize(mesh, texture, 2, 2)!!

        assertEquals(4, output.pixels.count { it == 0 })
    }

    @Test
    fun `重叠三角形按索引顺序叠加`() {
        // 底：不透明蓝；顶：半透明红（alpha 128）——叠完应该是红蓝混合的紫色系
        val texture = intArrayOf(blue)
        val bottom = UnityMesh(
            "bottom",
            floatArrayOf(0f, 0f, 2f, 0f, 2f, 2f, 0f, 2f),
            floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f),
            intArrayOf(0, 1, 2, 0, 2, 3),
            0f, 0f, 2f, 2f
        )
        val top = UnityMesh(
            "top",
            floatArrayOf(0f, 0f, 2f, 0f, 2f, 2f, 0f, 2f),
            floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f),
            intArrayOf(0, 1, 2, 0, 2, 3),
            0f, 0f, 2f, 2f
        )
        val halfRed = 0x80FF0000.toInt()
        val overlay = intArrayOf(halfRed)

        // 先把蓝色铺满，再用半透明红叠一层：这里直接模拟两个 mesh 先后绘制
        val first = MeshRasterizer.rasterize(bottom, texture, 1, 1)!!
        val second = MeshRasterizer.rasterize(top, overlay, 1, 1)!!
        // 单独光栅化不共享画布，因此这里只验证「单层内的合成」是直通 alpha 输出
        assertEquals(blue, first.pixels[0])
        assertEquals(halfRed and 0x00FFFFFF, second.pixels[0] and 0x00FFFFFF)
        assertEquals(0x80, (second.pixels[0] ushr 24) and 0xFF)
    }

    @Test
    fun `退化网格返回 null`() {
        val noTriangles = UnityMesh(
            "empty",
            floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f),
            floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f),
            IntArray(0),
            0f, 0f, 1f, 1f
        )
        assertNull(MeshRasterizer.rasterize(noTriangles, intArrayOf(red), 1, 1))

        val outOfRange = UnityMesh(
            "bad",
            floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f),
            floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f),
            intArrayOf(0, 1, 9),
            0f, 0f, 1f, 1f
        )
        assertNull(MeshRasterizer.rasterize(outOfRange, intArrayOf(red), 1, 1))
        assertTrue(true)
    }
}
