package com.azurlane.blyy.util.gameimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 用真实 prefab（`painting/2b_2`）验证立绘布局解析。
 *
 * 期望值来自对游戏本体的逆向核对（ALPA 的 ExtendedTransform 语义 + 逐图层实测）：
 * - 画布 = 主图层 `mRawSpriteSize` = 2048×1206（2B_2 的背景贴图）；
 * - 根节点 RectTransform 4604×2708，因此 `scale = 2048 / 4604 ≈ 0.4448`；
 * - 人物层 `2B_2_rw` 声明尺寸 1730×2048，摆在画布 (1496.4, 93.6) 处；
 * - `shop_hx_ch25`（商店贴纸）必须被跳过，不能出现在图层列表里。
 */
class PaintingLayoutTest {

    private fun plan(): PaintingLayout.Plan? {
        val file = PaintingTestSupport.bundle("painting/2b_2") ?: return null
        PaintingTestSupport.open(file).use { opened ->
            return PaintingLayout.parse(opened.serialized)
        }
    }

    @Test
    fun `解析 prefab 得到背景与人物两层`() {
        val plan = plan() ?: run { assumeTrue(false); return }

        assertEquals("2B_2", plan.rootName)
        assertEquals(2048, plan.canvasWidth)
        assertEquals(1206, plan.canvasHeight)
        // 背景 + 人物 + 一个装饰层（`1`，装饰在 UI 图集里，解析阶段仍会被列出）
        assertTrue("图层数应 >= 2，实际 ${plan.layers.size}", plan.layers.size >= 2)
        assertTrue(plan.layers.none { it.name.startsWith("shop_hx") })
    }

    @Test
    fun `背景层的几何与引用正确`() {
        val plan = plan() ?: run { assumeTrue(false); return }
        val background = plan.layers.first { it.name == "2B_2" }

        assertEquals(7946471572695944482L, background.spritePathId)
        assertEquals(0L, background.meshPathId)
        assertEquals(2048f, background.rawWidth, 0.01f)
        assertEquals(1206f, background.rawHeight, 0.01f)
        assertEquals(0f, background.pasteX, 0.01f)
        assertEquals(0f, background.pasteY, 0.01f)
        // 根节点自身缩放不参与布局：sizeDelta 就是画面尺寸
        assertEquals(4604f, background.width, 0.01f)
        assertEquals(2708f, background.height, 0.01f)
    }

    @Test
    fun `人物层的引用与摆放位置正确`() {
        val plan = plan() ?: run { assumeTrue(false); return }
        val character = plan.layers.first { it.name == "2B_2_rw" }

        assertEquals(6991537980068571611L, character.spritePathId)
        assertEquals(-7142384736335288403L, character.meshPathId)
        assertEquals(1730f, character.rawWidth, 0.01f)
        assertEquals(2048f, character.rawHeight, 0.01f)
        assertEquals(1496.4f, character.pasteX, 0.5f)
        assertEquals(93.6f, character.pasteY, 0.5f)
        assertEquals(1730f, character.width, 0.01f)
        assertEquals(2048f, character.height, 0.01f)
    }

    @Test
    fun `图层落点与游戏内一致`() {
        val plan = plan() ?: run { assumeTrue(false); return }
        val frame = plan.frame()

        val background = PaintingLayout.place(plan.layers.first { it.name == "2B_2" }, frame)
        assertEquals(0, background.x)
        assertEquals(0, background.y)
        assertEquals(2048, background.width)
        assertEquals(1206, background.height)

        // 人物层：1730×2048 的贴图按布局缩到 770×911，摆在 (666, 253)
        val character = PaintingLayout.place(plan.layers.first { it.name == "2B_2_rw" }, frame)
        assertEquals(666, character.x)
        assertEquals(253, character.y)
        assertEquals(770, character.width)
        assertEquals(911, character.height)
    }

    @Test
    fun `输出帧按主图层贴图宽度吸附`() {
        val plan = plan() ?: run { assumeTrue(false); return }
        val frame = plan.frame()

        // scale = 画布宽 2048 / 根图层宽 4604
        assertEquals(0.44483f, frame.scale, 0.001f)
        assertEquals(2048, frame.width)
        assertEquals(1206, frame.height)
        assertEquals(0f, frame.left, 0.01f)
        assertEquals(0f, frame.bottom, 0.01f)
    }

    @Test
    fun `不是 prefab 的包返回 null`() {
        val file = PaintingTestSupport.bundle("painting/changshehao_n_tex")
        assumeTrue(file != null)
        PaintingTestSupport.open(file!!).use { opened ->
            assertEquals(null, PaintingLayout.parse(opened.serialized))
        }
    }

    @Test
    fun `场景里没有 prefab 时不抛异常`() {
        val file = PaintingTestSupport.bundle("painting/beianpudun_tex") ?: run {
            assumeTrue(false)
            return
        }
        PaintingTestSupport.open(file).use { opened ->
            assertNotNull(opened.serialized)
            assertEquals(null, PaintingLayout.parse(opened.serialized))
        }
    }
}
