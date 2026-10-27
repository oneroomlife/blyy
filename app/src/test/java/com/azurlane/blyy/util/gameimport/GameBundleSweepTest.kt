package com.azurlane.blyy.util.gameimport

import com.azurlane.blyy.util.unity.BundleImage
import com.azurlane.blyy.util.unity.SerializedFile
import com.azurlane.blyy.util.unity.UnityFsBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * `resource/test` 全量扫描：把四类真实资源包逐个探测一遍，校验分类与产物前置条件。
 *
 * 覆盖：
 * - `live2d` 目录下的包 → LIVE2D（可导入）
 * - `char` 目录下的包 → SPINE + SD_CHAR（有 skel/atlas，版本 3.8.99）
 * - `painting` 下的 `_tex` 包 → TEXTURE + PAINTING（最大边 ≥512）
 * - `painting/<无 _tex>` → PREFAB_ONLY（给出改选 `_tex` 的提示）
 * - `spinepainting` 下的 `_res` 包 → SPINE + DYNAMIC_SKIN（有 skel/atlas）
 * - `spinepainting/<无 _res>` → PREFAB_ONLY（给出改选 `_res` 的提示）
 *
 * `resource/` 未入库，目录缺失时整体跳过。
 */
class GameBundleSweepTest {

    private val testRoot: File? = sequenceOf(
        File("../resource/test"),
        File("resource/test"),
        File("../../resource/test")
    ).firstOrNull { it.isDirectory }

    private fun bundles(dir: String): List<File> {
        val root = testRoot ?: return emptyList()
        val folder = File(root, dir)
        if (!folder.isDirectory) return emptyList()
        return folder.listFiles { f -> f.isFile }?.sortedBy { it.name }.orEmpty()
    }

    private fun probe(file: File, hint: GameAssetKind?): BundleProbeResult {
        val bundle = UnityFsBundle.open(file)
        val imageFile = File.createTempFile("blyy_sweep", ".img").apply { deleteOnExit() }
        bundle.decompressTo(imageFile)
        val image = BundleImage(imageFile, bundle.nodes)
        try {
            val sf = SerializedFile.open(image, bundle.serializedNode()!!)
            return BundleProbe.probe(sf, hint, file.name)
        } finally {
            image.close()
            imageFile.delete()
        }
    }

    @Test
    fun `全量资源包分类符合预期`() {
        assumeTrue("resource/test 不存在，跳过全量扫描", testRoot != null)
        val failures = mutableListOf<String>()
        val counted = bundles("live2d").size + bundles("char").size +
            bundles("painting").size + bundles("spinepainting").size
        // 防止「路径没对上 → 一个包都没扫到」也静默通过
        assertTrue("只找到 $counted 个资源包，测试资源路径不可用", counted >= 20)
        assertEquals(
            "资源包数量与 resource/test 快照不一致（live2d=${bundles("live2d").size}, " +
                "char=${bundles("char").size}, painting=${bundles("painting").size}, " +
                "spinepainting=${bundles("spinepainting").size}）",
            // resource/test 快照：live2d 6 / char 4 / painting 10 / spinepainting 9
            29,
            counted
        )

        fun check(file: File, hint: GameAssetKind?, expectedFormat: GameBundleFormat, expectedKind: GameAssetKind?) {
            val probe = runCatching { probe(file, hint) }.getOrElse { error ->
                failures += "${file.name}: 探测异常 ${error.message}"
                return
            }
            if (probe.format != expectedFormat) {
                failures += "${file.name}: format=${probe.format}，期望 $expectedFormat"
            }
            if (expectedKind != null && probe.kind != expectedKind) {
                failures += "${file.name}: kind=${probe.kind}，期望 $expectedKind"
            }
            if (expectedFormat == GameBundleFormat.PREFAB_ONLY && probe.reason.isNullOrBlank()) {
                failures += "${file.name}: 空壳包没有给出原因"
            }
            if (expectedFormat != GameBundleFormat.PREFAB_ONLY && !probe.importable) {
                failures += "${file.name}: 可渲染资源却判为不可导入（${probe.reason}）"
            }
        }

        // ---- Live2D ----
        bundles("live2d").forEach { file ->
            val probe = runCatching { probe(file, null) }.getOrNull()
            if (probe == null || probe.format != GameBundleFormat.LIVE2D) {
                failures += "${file.name}: 未识别为 Live2D"
            }
        }

        // ---- SD 小人 ----
        bundles("char").forEach { file ->
            val probe = runCatching { probe(file, null) }.getOrNull()
            if (probe == null || probe.format != GameBundleFormat.SPINE) {
                failures += "${file.name}: 未识别为 Spine"
            } else {
                if (probe.kind != GameAssetKind.SD_CHAR) {
                    failures += "${file.name}: kind=${probe.kind}，期望 SD_CHAR"
                }
                if (probe.skeletonBases.isEmpty()) failures += "${file.name}: 没有骨骼"
                if (probe.atlasNames.isEmpty()) failures += "${file.name}: 没有图集"
                if (probe.spineVersion?.startsWith("3.8") != true) {
                    failures += "${file.name}: 版本 ${probe.spineVersion} 不在 3.8"
                }
            }
        }

        // ---- 静态立绘 ----
        bundles("painting").forEach { file ->
            val isTexture = file.name.lowercase().substringBeforeLast('.').endsWith("_tex")
            if (isTexture) {
                check(file, null, GameBundleFormat.TEXTURE, GameAssetKind.PAINTING)
            } else {
                check(file, GameAssetKind.PAINTING, GameBundleFormat.PREFAB_ONLY, null)
            }
        }

        // ---- 动态皮肤 ----
        bundles("spinepainting").forEach { file ->
            val isAssets = file.name.lowercase().substringBeforeLast('.').endsWith("_res")
            if (isAssets) {
                check(file, GameAssetKind.DYNAMIC_SKIN, GameBundleFormat.SPINE, GameAssetKind.DYNAMIC_SKIN)
            } else {
                check(file, GameAssetKind.DYNAMIC_SKIN, GameBundleFormat.PREFAB_ONLY, null)
            }
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertEquals(0, failures.size)
    }
}
