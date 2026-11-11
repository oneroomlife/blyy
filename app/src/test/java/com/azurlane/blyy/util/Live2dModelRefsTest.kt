package com.azurlane.blyy.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * model3.json 引用修正单测（纯 JVM，真实临时目录）。
 *
 * 手动导入的模型绝大多数是 Windows 上二次打包的：`Textures/texture_00.png`
 * 与磁盘上的 `textures/texture_00.png` 大小写不同、路径里带反斜杠、
 * 贴图被移到别的层级。Android 大小写敏感 → 旧实现判"model3.json 损坏"
 * → 模型在库里完全不显示。这里覆盖修正与幂等性。
 */
class Live2dModelRefsTest {

    private lateinit var tempRoot: File

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("live2d-refs").toFile()
    }

    @After
    fun tearDown() {
        runCatching { tempRoot.deleteRecursively() }
    }

    private fun file(rel: String, content: String? = null): File {
        val f = File(tempRoot, rel)
        f.parentFile?.mkdirs()
        if (content != null) f.writeText(content) else f.writeBytes(ByteArray(4))
        return f
    }

    private val model3Json = """
        {
          "Version": 3,
          "FileReferences": {
            "Moc": "model.moc3",
            "Textures": ["textures/texture_00.png"],
            "Physics": "physics3.json",
            "Motions": {
              "Idle": [{ "File": "motions\\idle.motion3.json", "Sound": "motions\\idle.wav" }]
            }
          }
        }
    """.trimIndent()

    private fun buildCubismModel(): File {
        // 磁盘上是 Textures/（大写）与 motions/；json 里写的是小写 textures/ 与反斜杠
        file("model/model.moc3")
        file("model/Textures/texture_00.png")
        file("model/motions/idle.motion3.json")
        file("model/motions/idle.wav")
        file("model/physics3.json")
        file("model/haitun_2.model3.json", model3Json)
        return File(tempRoot, "model")
    }

    // ---------- 引用解析 ----------

    @Test
    fun `大小写与层级漂移的引用能解析到真实文件`() {
        val dir = buildCubismModel()
        val index = Live2dFileIndex(dir)
        assertEquals("Textures/texture_00.png", index.resolve("textures/texture_00.png"))
        assertEquals("motions/idle.motion3.json", index.resolve("""motions\idle.motion3.json"""))
        assertEquals("model.moc3", index.resolve("MODEL.MOC3"))
    }

    @Test
    fun `按文件名兜底时优先末级目录同名者`() {
        val dir = buildCubismModel()
        file("model/backup/texture_00.png")
        val index = Live2dFileIndex(dir)
        // 引用里带 textures 段 → 应命中 textures/ 下的那份，而不是 backup/
        assertEquals("Textures/texture_00.png", index.resolve("textures/texture_00.png"))
    }

    @Test
    fun `穿越引用一律拒绝`() {
        val dir = buildCubismModel()
        val index = Live2dFileIndex(dir)
        assertNull(index.resolve("../etc/passwd"))
        assertNull(index.resolve(".."))
        assertNull(index.resolve(""))
        assertNull(index.resolve(null))
    }

    // ---------- repair ----------

    @Test
    fun `repair 修正贴图大小写与动作反斜杠并报告关键资源可用`() {
        val dir = buildCubismModel()
        val index = Live2dFileIndex(dir)
        val result = Live2dModelRefs.repair(model3Json, index)
        assertNotNull(result)
        result!!
        assertTrue("moc 应解析成功", result.mocResolved == "model.moc3")
        assertEquals(1, result.texturesResolved)
        assertTrue(result.criticalOk)
        assertTrue("应改写至少 2 处引用", result.changedRefs >= 2)
        assertTrue(result.json.contains("Textures/texture_00.png"))
        assertTrue(result.json.contains("motions/idle.motion3.json"))
        assertTrue(result.json.contains("motions/idle.wav"))
    }

    @Test
    fun `非法 JSON 返回 null 由扫描侧按原语义判损坏`() {
        val dir = buildCubismModel()
        assertNull(Live2dModelRefs.repair("{ not json", Live2dFileIndex(dir)))
    }

    // ---------- localize（导入落盘后 / 扫描兜底） ----------

    @Test
    fun `localize 回写引用且第二次调用幂等`() {
        val dir = buildCubismModel()
        val first = Live2dModelLocalizer.localize(dir)
        assertTrue("首次应判定可渲染: ${first.reason}", first.ok)
        assertTrue("首次应回写引用", first.rewrittenRefs > 0)

        val written = File(dir, "haitun_2.model3.json").readText()
        assertTrue(written.contains("Textures/texture_00.png"))
        assertTrue(written.contains("motions/idle.motion3.json"))

        val second = Live2dModelLocalizer.localize(dir)
        assertTrue(second.ok)
        assertEquals("再次调用不应重复改写", 0, second.rewrittenRefs)
    }

    @Test
    fun `缺 moc3 时明确报失败而不是静默入库`() {
        file("model/Textures/texture_00.png")
        file("model/haitun_2.model3.json", model3Json)
        val outcome = Live2dModelLocalizer.localize(File(tempRoot, "model"))
        assertTrue(!outcome.ok)
        assertTrue("原因应指出缺 moc3: ${outcome.reason}", outcome.reason!!.contains("moc3"))
    }

    @Test
    fun `缺贴图时明确报失败`() {
        file("model/model.moc3")
        file("model/haitun_2.model3.json", model3Json)
        val outcome = Live2dModelLocalizer.localize(File(tempRoot, "model"))
        assertTrue(!outcome.ok)
        assertTrue("原因应指出缺贴图: ${outcome.reason}", outcome.reason!!.contains("贴图"))
    }

    @Test
    fun `没有 model3 时返回未找到`() {
        file("model/readme.txt", "hi")
        val outcome = Live2dModelLocalizer.localize(File(tempRoot, "model"))
        assertTrue(!outcome.ok)
        assertTrue(outcome.reason!!.contains("model3.json"))
    }

    @Test
    fun `模型文件在子目录时也能定位并合格化`() {
        // 手动导入包常见形态：<id>/<id>/<id>.model3.json
        file("model/haitun_2/haitun_2.model3.json", model3Json)
        file("model/haitun_2/model.moc3")
        file("model/haitun_2/Textures/texture_00.png")
        val outcome = Live2dModelLocalizer.localize(File(tempRoot, "model"))
        assertTrue("应能定位子目录模型: ${outcome.reason}", outcome.ok)
    }

    @Test
    fun `同名 model3 优先于更浅的其他 model3`() {
        val dir = buildCubismModel()
        file("model/zzz_other.model3.json", model3Json)
        val picked = Live2dModelLocalizer.findPrimaryModel3(dir)
        // 择优不得依赖 listFiles 顺序（Linux ext4 是哈希序，CI 上翻过车）：
        // 无同名候选时按 BFS 深度 + 名字字典序确定回退
        assertEquals("haitun_2.model3.json", picked?.name)
    }

    @Test
    fun `与自身目录同名的深层 model3 优先于更浅的无关 model3`() {
        // 官方 rip 习惯 <id>/<id>/<id>.model3.json：即便根层先出现别的 model3，
        // 也优先选与自身父目录同名的那份
        val dir = buildCubismModel()
        file("model/sub/sub.model3.json", model3Json)
        val picked = Live2dModelLocalizer.findPrimaryModel3(dir)
        assertEquals("sub.model3.json", picked?.name)
    }
}
