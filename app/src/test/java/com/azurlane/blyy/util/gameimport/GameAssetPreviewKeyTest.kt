package com.azurlane.blyy.util.gameimport

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 预览图缓存键（Coil 只按路径做键会回旧图，必须带版本）。
 */
class GameAssetPreviewKeyTest {

    private fun asset(previewFile: File?, modifiedAt: Long, sizeBytes: Long) = GameAssetInfo(
        kind = GameAssetKind.PAINTING,
        id = "22",
        dir = File("/tmp/game_assets/painting/22"),
        sizeBytes = sizeBytes,
        fileCount = 2,
        modifiedAt = modifiedAt,
        previewFile = previewFile
    )

    @Test
    fun `没有预览图时没有缓存键`() {
        assertNull(asset(null, 1_700_000_000_000L, 782_513L).previewCacheKey)
    }

    @Test
    fun `缓存键由路径与版本组成`() {
        val file = File("/tmp/game_assets/painting/22/painting.png")
        val key = asset(file, 1_700_000_000_000L, 782_513L).previewCacheKey
        assertTrue(key != null)
        assertTrue(key, key!!.startsWith(file.absolutePath + "#"))
        assertTrue(key, key.endsWith("782513"))
    }

    @Test
    fun `重新导入只改时间也会换键`() {
        val file = File("/tmp/game_assets/painting/22/painting.png")
        val before = asset(file, 1_700_000_000_000L, 782_513L).previewCacheKey
        val after = asset(file, 1_700_000_900_000L, 782_513L).previewCacheKey
        assertNotEquals(before, after)
    }

    @Test
    fun `重新导入只改体积也会换键`() {
        val file = File("/tmp/game_assets/painting/22/painting.png")
        val before = asset(file, 1_700_000_000_000L, 782_513L).previewCacheKey
        val after = asset(file, 1_700_000_000_000L, 6_413L).previewCacheKey
        assertNotEquals(before, after)
    }

    @Test
    fun `同一个资源重复扫描得到同一个键`() {
        val file = File("/tmp/game_assets/painting/22/painting.png")
        val first = asset(file, 1_700_000_000_000L, 782_513L).previewCacheKey
        val second = asset(File(file.path), 1_700_000_000_000L, 782_513L).previewCacheKey
        assertTrue(first == second)
    }
}
