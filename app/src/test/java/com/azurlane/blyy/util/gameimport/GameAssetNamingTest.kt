package com.azurlane.blyy.util.gameimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资源 id / 变体 / 类型判定规则回归。
 *
 * 这些规则直接决定「导入到哪个库、卡片上叫什么、是否会互相覆盖」，
 * 用真实的碧蓝资源包命名做样例，避免以后改规则时悄悄破坏已有资源。
 */
class GameAssetNamingTest {

    @Test
    fun `立绘包去掉 _tex 后缀`() {
        assertEquals("dashan_3", GameAssetNaming.assetIdOf("dashan_3_tex", GameAssetKind.PAINTING))
        assertEquals("dashan_3_n", GameAssetNaming.assetIdOf("dashan_3_n_tex", GameAssetKind.PAINTING))
        assertEquals("dashan_3_rw", GameAssetNaming.assetIdOf("dashan_3_rw_tex", GameAssetKind.PAINTING))
        assertEquals(
            "dashan_3_shophx",
            GameAssetNaming.assetIdOf("dashan_3_shophx_tex", GameAssetKind.PAINTING)
        )
    }

    /**
     * 立绘分层包判定 —— 「静态立绘拼接错位」的直接防线。
     *
     * 真实游戏里 `painting/` 一个皮肤会有多份包，`_rw`/`_bjN`/`_front`/`_shophx`
     * 都只是运行时拼装用的残图；它们一旦被当成独立立绘，界面上就是一堆碎片。
     * 正例（主立绘）与反例（分层包）都用真机导出的文件名做样例。
     */
    @Test
    fun `立绘分层包被排除、主立绘保留`() {
        // 主立绘：可以独立展示
        assertTrue(!GameAssetNaming.isPaintingLayer("dashan_3_tex"))
        assertTrue(!GameAssetNaming.isPaintingLayer("dashan_3_n_tex"))
        assertTrue(!GameAssetNaming.isPaintingLayer("2b_hx_tex"))
        assertTrue(!GameAssetNaming.isPaintingLayer("aerbien_3_tex"))

        // 分层残图：人物层 / 背景条 / 前景层 / 商店缩略图
        assertTrue(GameAssetNaming.isPaintingLayer("2b_2_rw_tex"))
        assertTrue(GameAssetNaming.isPaintingLayer("2b_n_rw_hx_tex"))
        assertTrue(GameAssetNaming.isPaintingLayer("2b_bj1_tex"))
        assertTrue(GameAssetNaming.isPaintingLayer("2b_bj2_hx_tex"))
        assertTrue(GameAssetNaming.isPaintingLayer("aerbien_front_tex"))
        assertTrue(GameAssetNaming.isPaintingLayer("dashan_3_shophx_tex"))
        assertTrue(GameAssetNaming.isPaintingLayer("aerbien_2_shophx_hx_tex"))

        // 目录名（资源库扫描入参，不带 _tex）同样识别
        assertTrue(GameAssetNaming.isPaintingLayer("2b_bj1"))
        assertTrue(!GameAssetNaming.isPaintingLayer("2b_2"))
    }

    @Test
    fun `动态皮肤包去掉 _res 后缀`() {        assertEquals("zhaohe_4", GameAssetNaming.assetIdOf("zhaohe_4_res", GameAssetKind.DYNAMIC_SKIN))
        assertEquals("zhuzi_2_doa", GameAssetNaming.assetIdOf("zhuzi_2_doa_res", GameAssetKind.DYNAMIC_SKIN))
    }

    @Test
    fun `SD 与 Live2D 保留原名`() {
        assertEquals("anninvwang_3", GameAssetNaming.assetIdOf("anninvwang_3", GameAssetKind.SD_CHAR))
        assertEquals("shi_3", GameAssetNaming.assetIdOf("shi_3.ys", GameAssetKind.LIVE2D))
    }

    @Test
    fun `后缀剥离不受大小写影响（保留原有大小写）`() {
        assertEquals("ZHAOHE_4", GameAssetNaming.assetIdOf("ZHAOHE_4_RES", GameAssetKind.DYNAMIC_SKIN))
        assertEquals("Dashan_3", GameAssetNaming.assetIdOf("Dashan_3_Tex", GameAssetKind.PAINTING))
    }

    @Test
    fun `变体识别长后缀优先`() {
        assertEquals("dashan_3" to "SHOPHX", GameAssetNaming.variantOf("dashan_3_shophx"))
        assertEquals("zhaohe_4" to "HX", GameAssetNaming.variantOf("zhaohe_4_hx"))
        assertEquals("changshehao" to "N", GameAssetNaming.variantOf("changshehao_n"))
        assertEquals("dashan_3" to "RW", GameAssetNaming.variantOf("dashan_3_rw"))
        assertEquals("zhuzi_2" to "DOA", GameAssetNaming.variantOf("zhuzi_2_doa"))
        assertNull(GameAssetNaming.variantOf("dashan_3"))
    }

    @Test
    fun `非法字符归一且长度受限`() {
        assertEquals("a_b_c", GameAssetNaming.sanitizeId("a b/c"))
        assertEquals("asset", GameAssetNaming.sanitizeId("  "))
        assertTrue(GameAssetNaming.sanitizeId("x".repeat(120)).length <= 48)
    }

    @Test
    fun `资源包路径推断类型`() {
        assertEquals(
            GameAssetKind.SD_CHAR,
            GameAssetKind.fromBundlePath("/sdcard/Android/data/com.bilibili.azurlane/files/AssetBundles/char/anninvwang_3")
        )
        assertEquals(
            GameAssetKind.DYNAMIC_SKIN,
            GameAssetKind.fromBundlePath("AssetBundles/spinepainting/zhaohe_4_res")
        )
        assertEquals(
            GameAssetKind.PAINTING,
            GameAssetKind.fromBundlePath("painting/dashan_3_tex")
        )
        assertEquals(
            GameAssetKind.LIVE2D,
            GameAssetKind.fromBundlePath("live2d/shi_3.ys")
        )
        // META 舰 Q 版小人目录结构与 char 一致
        assertEquals(GameAssetKind.SD_CHAR, GameAssetKind.fromBundlePath("metaship/xxx"))
        assertNull(GameAssetKind.fromBundlePath("AssetBundles/bg/cg_1"))
        assertNull(GameAssetKind.fromBundlePath(null))
    }

    @Test
    fun `包内 AssetBundle 名优先于目录名`() {
        assertEquals(GameAssetKind.SD_CHAR, GameAssetKind.fromBundleName("char/anninvwang_3"))
        assertEquals(GameAssetKind.PAINTING, GameAssetKind.fromBundleName("painting/dashan_3_tex"))
        assertNull(GameAssetKind.fromBundleName(""))
    }
}
