package com.azurlane.blyy.util.unity

import com.azurlane.blyy.util.gameimport.SpineAtlasParser
import com.azurlane.blyy.util.gameimport.SpineBinaryHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * TextAsset 字节保真回归（真实资源包）。
 *
 * 回归的 bug：TypeTree 把 `m_Script` 声明为 `string`，按 UTF-8 解码再编码会把
 * 二进制 `.skel` 里所有非 UTF-8 字节替换成 `U+FFFD`（实测 200KB 里 3.7 万个），
 * 运行时报 `EOFException`，模型直接加载失败。这里直接断言：
 * 1. 读出的字节里**没有** `EF BF BD` 替换序列；
 * 2. `.skel` 头能解析出 `3.8.99`；
 * 3. `.atlas` 仍是可解析文本（页数 > 0）。
 */
class UnityTextAssetReaderTest {

    private val testRoot: File? = sequenceOf(
        File("../resource/test"),
        File("resource/test"),
        File("../../resource/test")
    ).firstOrNull { it.isDirectory }

    private fun spineBundles(): List<File> {
        val root = testRoot ?: return emptyList()
        val out = mutableListOf<File>()
        File(root, "char").listFiles { f -> f.isFile }?.let { out += it }
        File(root, "spinepainting").listFiles { f ->
            f.isFile && f.name.lowercase().substringBeforeLast('.').endsWith("_res")
        }?.let { out += it }
        return out.sortedBy { it.name }
    }

    private fun openSerialized(file: File): Pair<BundleImage, SerializedFile> {
        val bundle = UnityFsBundle.open(file)
        val imageFile = File.createTempFile("blyy_text", ".img").apply { deleteOnExit() }
        bundle.decompressTo(imageFile)
        val image = BundleImage(imageFile, bundle.nodes)
        return image to SerializedFile.open(image, bundle.serializedNode()!!)
    }

    private fun containsReplacementChar(bytes: ByteArray): Boolean {
        if (bytes.size < 3) return false
        for (i in 0..bytes.size - 3) {
            if (bytes[i] == 0xEF.toByte() && bytes[i + 1] == 0xBF.toByte() &&
                bytes[i + 2] == 0xBD.toByte()
            ) {
                return true
            }
        }
        return false
    }

    @Test
    fun `Spine 包的文本资源字节保真`() {
        assumeTrue("resource/test 不存在，跳过", testRoot != null)
        val bundles = spineBundles()
        assertTrue("未找到 Spine 测试包", bundles.size >= 8)

        val problems = mutableListOf<String>()
        bundles.forEach { file ->
            val (image, sf) = openSerialized(file)
            try {
                val assets = sf.objectsOfClass(SerializedFile.CLASS_TEXT_ASSET).mapNotNull { def ->
                    UnityTextAssetReader.read(sf, def)
                }
                val skels = assets.filter { it.name.endsWith(".skel", true) }
                val atlases = assets.filter { it.name.endsWith(".atlas", true) }
                if (skels.isEmpty()) problems += "${file.name}: 没有 skel"
                if (atlases.isEmpty()) problems += "${file.name}: 没有 atlas"

                skels.forEach { skel ->
                    if (containsReplacementChar(skel.bytes)) {
                        problems += "${file.name}/${skel.name}: 含 U+FFFD 替换字符（被按文本解码）"
                    }
                    val version = SpineBinaryHeader.readVersion(skel.bytes)
                    if (version == null || !version.startsWith("3.")) {
                        problems += "${file.name}/${skel.name}: 版本解析失败（$version）"
                    }
                    if (skel.bytes.size < 512) {
                        problems += "${file.name}/${skel.name}: 字节数异常 ${skel.bytes.size}"
                    }
                }
                atlases.forEach { atlas ->
                    if (containsReplacementChar(atlas.bytes)) {
                        problems += "${file.name}/${atlas.name}: 含 U+FFFD 替换字符"
                    }
                    val text = String(atlas.bytes, Charsets.UTF_8)
                    if (SpineAtlasParser.pages(text).isEmpty()) {
                        problems += "${file.name}/${atlas.name}: 图集解析不到页"
                    }
                }
            } finally {
                image.close()
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `char 包骨骼大小与快照一致`() {
        assumeTrue("resource/test 不存在，跳过", testRoot != null)
        val file = File(testRoot, "char/anninvwang_3").takeIf { it.isFile } ?: run {
            assumeTrue(false)
            return
        }
        val (image, sf) = openSerialized(file)
        try {
            val skel = sf.objectsOfClass(SerializedFile.CLASS_TEXT_ASSET).mapNotNull { def ->
                UnityTextAssetReader.read(sf, def)
            }.first { it.name.endsWith(".skel") }
            // 参考：Python 侧解析出的脚本字节约 531.6KB（含 4 字节长度前缀前）
            assertTrue("骨骼字节数异常：${skel.bytes.size}", skel.bytes.size in 531_000..532_000)
            assertEquals("3.8.99", SpineBinaryHeader.readVersion(skel.bytes))
            assertFalse(containsReplacementChar(skel.bytes))
        } finally {
            image.close()
        }
    }
}
