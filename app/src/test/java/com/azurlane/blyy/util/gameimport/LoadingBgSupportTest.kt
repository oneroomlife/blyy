package com.azurlane.blyy.util.gameimport

import com.azurlane.blyy.util.gameimport.GameBundleFormat.TEXTURE
import com.azurlane.blyy.util.unity.UnityTextureExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 过场图（`loadingbg`）支持的回归测试。
 *
 * 覆盖三件容易出错的事：
 * 1. 目录/包名 → 资源类型（`loadingbg` 与 `loadingbg_hx` 都要落到 LOADING_BG）；
 * 2. 资源 id 与显示（`bg_1` 不能带 `_tex` 之类的后缀处理）；
 * 3. 真实包探测：内容只有贴图 → TEXTURE + LOADING_BG，且能读到 1920×1080 主图。
 */
class LoadingBgSupportTest {

    private val sample: File? = sequenceOf(
        File("../resource/work/loadingbg/bg_1.ys"),
        File("resource/work/loadingbg/bg_1.ys"),
        File("../../resource/work/loadingbg/bg_1.ys")
    ).firstOrNull { it.isFile }

    @Test
    fun `目录与包名都能识别为过场图`() {
        assertEquals(GameAssetKind.LOADING_BG, GameAssetKind.fromKey("loadingbg"))
        assertEquals(GameAssetKind.LOADING_BG, GameAssetKind.fromBundleName("loadingbg/bg_1"))
        // 和谐版目录独立成包，但仍然属于过场图
        assertEquals(GameAssetKind.LOADING_BG, GameAssetKind.fromBundleName("loadingbg_hx/bg_1"))
        assertEquals(
            GameAssetKind.LOADING_BG,
            GameAssetKind.fromBundlePath("/sdcard/Android/data/com.bilibili.azurlane/files/AssetBundles/loadingbg/bg_1.ys")
        )
        // 兄弟目录声明：扫描时会把 hx 目录一起列举
        assertEquals(listOf("loadingbg_hx"), GameAssetKind.LOADING_BG.siblingDirs)
        assertTrue(GameAssetKind.ordered.contains(GameAssetKind.LOADING_BG))
    }

    @Test
    fun `过场图资源 id 保留原文件名`() {
        assertEquals("bg_1", GameAssetNaming.assetIdOf("bg_1.ys", GameAssetKind.LOADING_BG))
        assertEquals("bg_12", GameAssetNaming.assetIdOf("bg_12", GameAssetKind.LOADING_BG))
    }

    @Test
    fun `真实过场图包被识别为图片资源`() {
        val file = sample ?: run { assumeTrue(false); return }
        PaintingTestSupport.open(file).use { opened ->
            val probe = BundleProbe.probe(opened.serialized, GameAssetKind.LOADING_BG, file.name)
            assertEquals(TEXTURE, probe.format)
            assertEquals(GameAssetKind.LOADING_BG, probe.kind)
            assertTrue(probe.importable)

            val textures = UnityTextureExtractor.list(opened.serialized)
            assertTrue("过场图包应带贴图", textures.isNotEmpty())
            // 包里 1920×1080 与 1024×576 同名，取图策略必须拿到大的那份
            assertEquals(1920, textures.maxByOrNull { it.pixels }!!.width)
        }
    }
}
