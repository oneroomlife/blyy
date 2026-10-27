package com.azurlane.blyy.util.gameimport

import com.azurlane.blyy.util.unity.BundleImage
import com.azurlane.blyy.util.unity.SerializedFile
import com.azurlane.blyy.util.unity.UnityFsBundle
import com.azurlane.blyy.util.unity.UnityTextureExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 用 `resource/test` 里的**真实资源包**验证「探测 + 贴图元信息」链路。
 *
 * 这一层不涉及 GL（贴图只在需要渲染时才解码），因此可以在 JVM 上直接跑：
 * 覆盖「四类资源包能否被正确分类」以及「贴图格式/尺寸是否读对」——
 * 这两件事一旦出错，导入会分流到错误的提取器或整包解码失败。
 *
 * `resource/` 未入库（.gitignore），目录不存在时测试自动跳过。
 */
class GameBundleProbeTest {

    private val testRoot: File? = sequenceOf(
        File("../resource/test"),
        File("resource/test"),
        File("../../resource/test")
    ).firstOrNull { it.isDirectory }

    private fun bundle(relative: String): File? {
        val root = testRoot ?: return null
        val file = File(root, relative)
        return file.takeIf { it.isFile }
    }

    /** 解包到临时镜像并打开序列化文件；调用方负责关闭 */
    private fun open(file: File): Pair<BundleImage, SerializedFile> {
        val bundle = UnityFsBundle.open(file)
        val image = File.createTempFile("blyy_probe", ".img").apply { deleteOnExit() }
        bundle.decompressTo(image)
        val imageReader = BundleImage(image, bundle.nodes)
        val serialized = SerializedFile.open(imageReader, bundle.serializedNode()!!)
        return imageReader to serialized
    }

    @Test
    fun `char 包识别为 SD 小人`() {
        val file = bundle("char/anninvwang_3") ?: run { assumeTrue(false); return }
        val (image, sf) = open(file)
        try {
            val probe = BundleProbe.probe(sf, null, file.name)
            assertEquals(GameBundleFormat.SPINE, probe.format)
            assertEquals(GameAssetKind.SD_CHAR, probe.kind)
            assertEquals(listOf("anninvwang_3"), probe.skeletonBases)
            assertEquals(listOf("anninvwang_3.atlas"), probe.atlasNames)
            assertEquals("3.8.99", probe.spineVersion)
            assertTrue(probe.importable)
            assertEquals(null, probe.reason)

            val textures = UnityTextureExtractor.list(sf)
            assertEquals(1, textures.size)
            // 实测 char 包使用 ASTC 6x6（Unity TextureFormat=50），此前未映射 → 必然失败
            assertEquals(50, textures.first().format)
            assertEquals(2048, textures.first().width)
            assertEquals(512, textures.first().height)
        } finally {
            image.close()
        }
    }

    @Test
    fun `painting 的 _tex 包识别为静态立绘`() {
        val file = bundle("painting/dashan_3_tex") ?: run { assumeTrue(false); return }
        val (image, sf) = open(file)
        try {
            val probe = BundleProbe.probe(sf, null, file.name)
            assertEquals(GameBundleFormat.TEXTURE, probe.format)
            assertEquals(GameAssetKind.PAINTING, probe.kind)
            assertEquals(2048, probe.textureMaxSide)
            assertEquals(48, UnityTextureExtractor.list(sf).first().format)
            assertTrue(probe.importable)
        } finally {
            image.close()
        }
    }

    @Test
    fun `painting 的同名预制体包给出可读原因`() {
        val file = bundle("painting/dashan_3") ?: run { assumeTrue(false); return }
        val (image, sf) = open(file)
        try {
            val probe = BundleProbe.probe(sf, GameAssetKind.PAINTING, file.name)
            assertEquals(GameBundleFormat.PREFAB_ONLY, probe.format)
            assertFalse(probe.importable)
            assertTrue(probe.reason.orEmpty().contains("_tex"))
        } finally {
            image.close()
        }
    }

    @Test
    fun `spinepainting 的 _res 包识别为动态皮肤（多骨骼多页）`() {
        val file = bundle("spinepainting/zhaohe_4_res") ?: run { assumeTrue(false); return }
        val (image, sf) = open(file)
        try {
            val probe = BundleProbe.probe(sf, GameAssetKind.DYNAMIC_SKIN, file.name)
            assertEquals(GameBundleFormat.SPINE, probe.format)
            assertEquals(GameAssetKind.DYNAMIC_SKIN, probe.kind)
            assertEquals(setOf("zhaohe_4", "zhaohe_4_hx"), probe.skeletonBases.toSet())
            assertEquals("3.8.99", probe.spineVersion)
            assertTrue(probe.atlasNames.containsAll(listOf("zhaohe_4.atlas", "zhaohe_4_hx.atlas")))
            assertEquals(2, UnityTextureExtractor.list(sf).size)
        } finally {
            image.close()
        }
    }

    @Test
    fun `spinepainting 的预制体包不带骨骼`() {
        val file = bundle("spinepainting/zhaohe_4") ?: run { assumeTrue(false); return }
        val (image, sf) = open(file)
        try {
            val probe = BundleProbe.probe(sf, GameAssetKind.DYNAMIC_SKIN, file.name)
            assertEquals(GameBundleFormat.PREFAB_ONLY, probe.format)
            assertFalse(probe.importable)
            assertTrue(probe.reason.orEmpty().contains("_res"))
        } finally {
            image.close()
        }
    }

    @Test
    fun `live2d 包识别为 Cubism 模型`() {
        val file = bundle("live2d/shi_3") ?: run { assumeTrue(false); return }
        val (image, sf) = open(file)
        try {
            val probe = BundleProbe.probe(sf, null, file.name)
            assertEquals(GameBundleFormat.LIVE2D, probe.format)
            assertEquals(GameAssetKind.LIVE2D, probe.kind)
            assertTrue(probe.importable)
            assertTrue(probe.textureCount >= 1)
        } finally {
            image.close()
        }
    }
}
