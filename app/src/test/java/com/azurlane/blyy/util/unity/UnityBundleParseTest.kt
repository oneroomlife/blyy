package com.azurlane.blyy.util.unity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 碧蓝航线资源包解析回归测试。
 *
 * 样本放在工程根的 `resource/test/`（不入包，仅本地存在时运行）。
 * 覆盖：UnityFS 头/blocksInfo 16 字节对齐/块解压、SerializedFile v22 头与类型树、
 * TypeTree 通用读取、MOC3 定位、Texture2D 流式信息解析。
 */
class UnityBundleParseTest {

    private fun sample(name: String): File {
        val candidates = listOf(
            File("../resource/test/$name"),
            File("../../resource/test/$name"),
            File("resource/test/$name")
        )
        return candidates.firstOrNull { it.isFile } ?: File("__missing__/$name")
    }

    private val samples = listOf(
        "chaijun_6", "feiteliedadi_4", "jinluhao_3",
        "sailimiye_2", "shi_3", "shuixinghao_2"
    )

    @Test
    fun `all samples expose a serialized node and a resource stream`() {
        var checked = 0
        for (name in samples) {
            val file = sample(name)
            if (!file.isFile) continue
            checked++
            assertTrue("$name 不是 UnityFS 包", UnityFsBundle.detect(file))
            val bundle = UnityFsBundle.open(file)
            val serialized = bundle.serializedNode()
            assertNotNull("$name 缺少序列化节点", serialized)
            assertTrue("$name 序列化节点过小", serialized!!.size > 1_000_000)
            val resource = bundle.resourceNode()
            assertNotNull("$name 缺少 .resS 流式节点", resource)
            // 块解压总量必须等于节点大小之和，否则说明对齐/块表解析有误
            assertEquals(
                "$name 块总量与节点大小不一致",
                bundle.nodes.sumOf { it.size },
                bundle.blocks.sumOf { it.uncompressedSize }
            )
        }
        assumeTrue("未找到 resource/test 样本，跳过", checked > 0)
        assertEquals("样本数量不符", 6, checked)
    }

    @Test
    fun `chaijun_6 yields moc3 v5 and astc texture info`() {
        val file = sample("chaijun_6")
        assumeTrue("未找到 resource/test/chaijun_6", file.isFile)

        val bundle = UnityFsBundle.open(file)
        val tmp = File.createTempFile("chaijun", ".img")
        try {
            bundle.decompressTo(tmp)
            BundleImage(tmp, bundle.nodes).use { image ->
                val sf = SerializedFile.open(image, bundle.serializedNode()!!)
                assertEquals("2022.3.62f3", sf.unityVersion)
                assertTrue("对象数量异常：${sf.objects.size}", sf.objects.size > 10_000)
                assertTrue("类型数量异常：${sf.types.size}", sf.types.size in 25..45)

                // ---- MOC3 ----
                var mocSize = -1L
                var mocVersion = -1
                for (def in sf.objectsOfClass(SerializedFile.CLASS_MONO_BEHAVIOUR)) {
                    if (def.byteSize < 1_000_000) continue
                    val head = sf.peek(def, 1024)
                    val index = indexOfMoc3(head)
                    if (index >= 0) {
                        mocSize = def.byteSize.toLong() - index.toLong()
                        mocVersion = head[index + 4].toInt()
                        break
                    }
                }
                assertTrue("未在 MonoBehaviour 中找到 MOC3", mocSize > 0)
                assertEquals("moc3 版本应为 5", 5, mocVersion)
                assertEquals("moc3 长度异常", 19_944_064L, mocSize)

                // ---- Texture2D ----
                val textures = sf.objectsOfClass(SerializedFile.CLASS_TEXTURE2D)
                assertEquals("贴图数量应为 2", 2, textures.size)
                val values = sf.readValues(textures.first())
                assertEquals(4096, (values["m_Width"] as Number).toInt())
                assertEquals(4096, (values["m_Height"] as Number).toInt())
                assertEquals("应为 ASTC_4x4", 48, (values["m_TextureFormat"] as Number).toInt())
                @Suppress("UNCHECKED_CAST")
                val stream = values["m_StreamData"] as Map<String, Any?>
                assertTrue((stream["size"] as Number).toLong() > 0)
                assertTrue((stream["path"] as String).endsWith(".resS"))

                // ---- 物理配置（TextAsset，字节数组走快路径） ----
                val textAssets = sf.objectsOfClass(SerializedFile.CLASS_TEXT_ASSET)
                assertEquals("TextAsset 数量应为 1", 1, textAssets.size)
                val ta = sf.readValues(textAssets.first())
                assertEquals("chaijun_6.physics3", ta["m_Name"])
                val script = ta["m_Script"]
                val bytes = when (script) {
                    is ByteArray -> script
                    is String -> script.toByteArray(Charsets.UTF_8)
                    else -> throw AssertionError("m_Script 类型异常：${script?.javaClass}")
                }
                assertTrue("物理文件过短：${bytes.size}", bytes.size > 10_000)
                assertTrue("物理文件不是 JSON", String(bytes, Charsets.UTF_8).contains("\"PhysicsSettings\""))
            }
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `animation clips are fully readable through the generic type tree reader`() {
        val file = sample("chaijun_6")
        assumeTrue("未找到 resource/test/chaijun_6", file.isFile)

        val bundle = UnityFsBundle.open(file)
        val tmp = File.createTempFile("chaijun-clips", ".img")
        try {
            bundle.decompressTo(tmp)
            BundleImage(tmp, bundle.nodes).use { image ->
                val sf = SerializedFile.open(image, bundle.serializedNode()!!)
                val clips = sf.objectsOfClass(SerializedFile.CLASS_ANIMATION_CLIP)
                assertEquals("动画剪辑数量异常", 113, clips.size)
                var failures = 0
                for (def in clips) {
                    val values = runCatching { sf.readValues(def) }.getOrElse { failures++; continue }
                    val name = values["m_Name"] as? String
                    assertTrue("动画剪辑缺少名称", !name.isNullOrBlank())
                    val sampleRate = (values["m_SampleRate"] as? Number)?.toFloat() ?: 0f
                    assertTrue("采样率异常：$name -> $sampleRate", sampleRate > 1f)
                }
                assertEquals("部分动画剪辑读取失败", 0, failures)
            }
        } finally {
            tmp.delete()
        }
    }

    private fun indexOfMoc3(bytes: ByteArray): Int {
        for (i in 0..(bytes.size - 5)) {
            if (bytes[i] == 'M'.code.toByte() &&
                bytes[i + 1] == 'O'.code.toByte() &&
                bytes[i + 2] == 'C'.code.toByte() &&
                bytes[i + 3] == '3'.code.toByte()
            ) {
                return i
            }
        }
        return -1
    }
}
