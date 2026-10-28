package com.azurlane.blyy.util.gameimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 附属包判定（真实命名来自设备的 `AssetBundles/painting`）。
 *
 * 这里锁死的是**换装变体夹在中间**的命名：`2b_rw_hx_tex` 属于 `2b_hx`，
 * 只按 `<主包>_` 前缀匹配会漏掉它（实测让 HX 立绘只剩背景）。
 */
class PaintingCompanionRulesTest {

    private val deviceNames = listOf(
        "2b", "2b_tex",
        "2b_2", "2b_2_tex", "2b_2_rw_tex", "2b_2_n", "2b_2_n_rw_tex",
        "2b_hx", "2b_hx_tex", "2b_rw_hx_tex", "2b_bj1_hx_tex", "2b_bj2_hx_tex",
        "2b_n", "2b_n_tex", "2b_n_rw_tex", "2b_n_rw_hx_tex", "2b_rw_tex", "2b_bj1_tex", "2b_bj2_tex",
        "dashan_3", "dashan_3_tex", "dashan_3_rw_tex",
        "dashan_3_n", "dashan_3_n_tex", "dashan_3_n_rw_tex",
        "commonui_atlas.ys"
    )

    private fun index(): Map<String, String> =
        deviceNames.associate { it.lowercase() to "/game/painting/$it" }

    private fun picked(fileName: String, uiAtlas: String? = null): List<String> =
        PaintingCompanionRules.companions(index(), fileName, uiAtlas)
            .map { it.substringAfterLast('/') }

    @Test
    fun `HX 换装能拿到夹在中间的图层包`() {
        val picked = picked("2b_hx_tex.ys")
        assertTrue("prefab 要选同名的 2b_hx", picked.contains("2b_hx"))
        assertTrue("人物层 2b_rw_hx_tex 必须复制", picked.contains("2b_rw_hx_tex"))
        assertTrue(picked.contains("2b_bj1_hx_tex"))
        assertTrue(picked.contains("2b_bj2_hx_tex"))
        assertFalse("不能把主包自己再复制一遍", picked.contains("2b_hx_tex"))
        assertFalse("不能选基础立绘的 prefab", picked.contains("2b"))
        assertFalse("别的换装图层不该混进来", picked.contains("2b_2_rw_tex"))
        assertFalse(picked.contains("2b_n_rw_tex"))
    }

    @Test
    fun `普通立绘复制基础图层不复制换装图层`() {
        val picked = picked("2b_tex.ys")
        assertTrue(picked.contains("2b"))
        assertTrue(picked.contains("2b_rw_tex"))
        assertTrue(picked.contains("2b_bj1_tex"))
        assertFalse(picked.contains("2b_rw_hx_tex"))
        assertFalse(picked.contains("2b_2_rw_tex"))
    }

    @Test
    fun `皮肤序号与变体后缀区分开`() {
        val picked = picked("2b_2_tex.ys")
        assertTrue(picked.contains("2b_2"))
        assertTrue(picked.contains("2b_2_rw_tex"))
        assertFalse(picked.contains("2b_rw_tex"))
        assertFalse("_n 是另一套皮肤", picked.contains("2b_2_n_rw_tex"))
    }

    @Test
    fun `改造后缀同样能拿到图层`() {
        val picked = picked("dashan_3_n_tex.ys")
        assertTrue(picked.contains("dashan_3_n"))
        assertTrue(picked.contains("dashan_3_n_rw_tex"))
        // 共用基础图层也一并带上（_n 的变体集合包含空集）
        assertTrue(picked.contains("dashan_3_rw_tex"))
        assertFalse(picked.contains("dashan_3_tex"))
    }

    @Test
    fun `UI 图集作为装饰层追加且有数量上限`() {
        val picked = picked("2b_hx_tex.ys", uiAtlas = "/game/ui/commonui_atlas.ys")
        assertTrue(picked.contains("commonui_atlas.ys"))

        val limited = PaintingCompanionRules.companions(index(), "2b_hx_tex.ys", null, limit = 2)
        assertEquals(2, limited.size)
    }

    @Test
    fun `拆名规则`() {
        val hx = PaintingCompanionRules.splitName("2b_hx_tex")
        assertEquals("2b", hx.base)
        assertEquals(setOf("hx"), hx.variants)
        assertEquals("2b", hx.lastToken)

        val layer = PaintingCompanionRules.splitName("2b_rw_hx_tex.ys")
        assertEquals("2b_rw", layer.base)
        assertEquals(setOf("hx"), layer.variants)
        assertEquals("rw", layer.lastToken)

        val skin = PaintingCompanionRules.splitName("2b_2_tex")
        assertEquals("2b_2", skin.base)
        assertEquals(emptySet<String>(), skin.variants)
        assertEquals("2", skin.lastToken)

        assertEquals("2b", PaintingCompanionRules.splitName("2b_tex.ys").base)
        assertEquals("z23", PaintingCompanionRules.splitName("Z23_tex").base)
        // 背景条：最后一段是 bj1，属于图层关键字
        val bg = PaintingCompanionRules.splitName("2b_bj1_hx_tex")
        assertEquals("2b_bj1", bg.base)
        assertEquals("bj1", bg.lastToken)
    }
}
