package com.azurlane.blyy.util.gameimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [AssetMetaStore] 侧车缓存单测。
 *
 * 缓存是「库内 6000+ 资产每次刷新都要过一次侧车解析」的性能前提，一旦失效策略写错
 * 就会变成「重新导入后看到的还是旧元信息」，所以这里把三条边界钉死：
 * 1. 命中缓存（连续读同一目录不再重新解析）
 * 2. 文件被改写（mtime / 长度变化）后必须重新解析
 * 3. 目录里侧车尚未写出时读到 null，写出后立刻能读到（写入主动失效）
 */
class AssetMetaCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun painting(mode: String, layers: Int = 1) = PaintingAssetMeta(
        file = AssetFiles.PAINTING_IMAGE,
        width = 1024,
        height = 2048,
        mode = mode,
        layers = layers
    )

    @Test
    fun `连续读取命中缓存`() {
        val dir = tmp.newFolder("painting_1")
        AssetMetaStore.writePainting(dir, painting("layout", layers = 3))

        val first = AssetMetaStore.readPainting(dir)
        val second = AssetMetaStore.readPainting(dir)

        assertNotNull(first)
        assertEquals("layout", first?.mode)
        assertEquals(3, first?.layers)
        // 同一个对象即证明走了缓存（而不是又解析了一遍）
        assertSame(first, second)
    }

    @Test
    fun `文件被外部改写后重新解析`() {
        val dir = tmp.newFolder("painting_2")
        AssetMetaStore.writePainting(dir, painting("mesh", layers = 1))
        assertEquals("mesh", AssetMetaStore.readPainting(dir)?.mode)

        // 绕过 writePainting 直接改文件：只靠指纹（mtime + 长度）失效
        val file = File(dir, AssetFiles.PAINTING_META)
        file.writeText(
            file.readText().replace("\"mesh\"", "\"layout\"").replace("\"layers\": 1", "\"layers\": 4")
        )

        val reread = AssetMetaStore.readPainting(dir)
        assertEquals("layout", reread?.mode)
        assertEquals(4, reread?.layers)
    }

    @Test
    fun `侧车缺失读到 null 且写出后立即可见`() {
        val dir = tmp.newFolder("spine_1")
        assertNull(AssetMetaStore.readSpine(dir))

        AssetMetaStore.writeSpine(
            dir,
            SpineAssetMeta(
                primary = "build",
                skeletons = listOf(SpineSkeletonMeta(name = "build", version = "3.8.99"))
            )
        )

        val meta = AssetMetaStore.readSpine(dir)
        assertEquals("build", meta?.primary)
        assertEquals("3.8.99", meta?.skeletons?.firstOrNull()?.version)
    }

    @Test
    fun `不同目录互不串味`() {
        val a = tmp.newFolder("painting_a")
        val b = tmp.newFolder("painting_b")
        AssetMetaStore.writePainting(a, painting("layout", layers = 2))
        AssetMetaStore.writePainting(b, painting("raw", layers = 1))

        assertEquals("layout", AssetMetaStore.readPainting(a)?.mode)
        assertEquals("raw", AssetMetaStore.readPainting(b)?.mode)
        assertEquals("layout", AssetMetaStore.readPainting(a)?.mode)
    }
}
