package com.azurlane.blyy.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live2D 模型目录布局规则单测。
 *
 * 这些规则是「手动导入的模型在库里看不到」的根因所在：
 * - 模型文件就在导入根顶层时，旧实现把模型根算成空串 → 名字空 → 整模型丢弃
 * - 空模型根只匹配"不含 / 的路径" → 贴图/动作子目录全部被丢弃 → 落地即坏模型
 */
class Live2dModelLayoutTest {

    // ---------- 模型 id 推导 ----------

    @Test
    fun `模型文件在导入根顶层时用 model3 文件名当模型名`() {
        // 用户直接选中模型文件夹本身（SAF「使用此文件夹」），relPath 没有目录前缀
        assertEquals(
            "haitun_2",
            Live2dModelLayout.modelIdFor(rootRel = "", model3Rel = "haitun_2.model3.json")
        )
    }

    @Test
    fun `嵌套结构优先用模型根目录名`() {
        assertEquals(
            "haitun_2",
            Live2dModelLayout.modelIdFor(
                rootRel = "pack/haitun_2",
                model3Rel = "pack/haitun_2/haitun_2.model3.json"
            )
        )
    }

    @Test
    fun `顶层无 model3 时用导入源名称兜底`() {
        assertEquals(
            "我的模型",
            Live2dModelLayout.modelIdFor(rootRel = "", model3Rel = "", fallback = "我的模型")
        )
    }

    @Test
    fun `model3 文件名兜底优先于导入源名称`() {
        assertEquals(
            "x",
            Live2dModelLayout.modelIdFor(rootRel = "", model3Rel = "x.model3.json", fallback = "zip包名")
        )
    }

    @Test
    fun `扩展名剥离大小写不敏感`() {
        assertEquals("A", Live2dModelLayout.stripModel3Ext("A.MODEL3.JSON"))
        assertEquals("b", Live2dModelLayout.stripModel3Ext("b.model3.json"))
        assertEquals("c.model3.foo", Live2dModelLayout.stripModel3Ext("c.model3.foo"))
    }

    @Test
    fun `所有名字都为空时返回空串由调用方报错`() {
        assertEquals("", Live2dModelLayout.modelIdFor("", "", null))
        assertEquals("", Live2dModelLayout.modelIdFor("", "", "   "))
    }

    // ---------- 目录名净化 ----------

    @Test
    fun `非法字符替换为下划线且保留中文`() {
        assertEquals("a_b_c", Live2dModelLayout.sanitizeFolderName("a/b\\c"))
        assertEquals("碧蓝_皮肤_1", Live2dModelLayout.sanitizeFolderName("碧蓝:皮肤?1"))
    }

    @Test
    fun `点与点点被视为无效名`() {
        assertEquals("", Live2dModelLayout.sanitizeFolderName("."))
        assertEquals("", Live2dModelLayout.sanitizeFolderName(".."))
    }

    @Test
    fun `控制字符被剔除且长度截断到 64`() {
        assertEquals("ab", Live2dModelLayout.sanitizeFolderName("a\u0000b"))
        assertEquals(64, Live2dModelLayout.sanitizeFolderName("x".repeat(200)).length)
    }

    // ---------- 文件归属 ----------

    @Test
    fun `空模型根拥有导入根下的一切包括子目录`() {
        // 这是"选中模型文件夹本身"场景：textures/ 与 motions/ 必须属于该模型
        val roots = listOf("")
        assertEquals("", Live2dModelLayout.ownerRoot("haitun_2.model3.json", roots))
        assertEquals("", Live2dModelLayout.ownerRoot("textures/texture_00.png", roots))
        assertEquals("", Live2dModelLayout.ownerRoot("motions/idle.motion3.json", roots))
    }

    @Test
    fun `嵌套根取最长匹配`() {
        val roots = listOf("", "pack/haitun_2")
        assertEquals(
            "pack/haitun_2",
            Live2dModelLayout.ownerRoot("pack/haitun_2/textures/t.png", roots)
        )
        assertEquals("", Live2dModelLayout.ownerRoot("other/a.png", roots))
    }

    @Test
    fun `前缀相同但不是同一层目录不算归属`() {
        val roots = listOf("haitun_2")
        assertNull(Live2dModelLayout.ownerRoot("haitun_2_x/a.png", roots))
        assertEquals("haitun_2", Live2dModelLayout.ownerRoot("haitun_2", roots))
    }

    // ---------- zip 条目名归一 ----------

    @Test
    fun `反斜杠与点前缀被归一`() {
        assertEquals("dir/x.model3.json", Live2dModelLayout.normalizeEntryPath("""dir\x.model3.json"""))
        assertEquals("a/b.txt", Live2dModelLayout.normalizeEntryPath("./a/b.txt"))
        assertEquals("a/b.txt", Live2dModelLayout.normalizeEntryPath("/a/b.txt"))
    }

    @Test
    fun `穿越路径与空名被拒绝`() {
        assertNull(Live2dModelLayout.normalizeEntryPath("../etc/passwd"))
        assertNull(Live2dModelLayout.normalizeEntryPath("a/../../b"))
        assertNull(Live2dModelLayout.normalizeEntryPath("   "))
    }

    @Test
    fun `顶层往返导入的路径集合完整`() {
        // 模拟「选中模型文件夹本身」的 SAF 遍历结果：所有文件都应归给同一个模型
        val rels = listOf(
            "haitun_2.model3.json",
            "model.moc3",
            "textures/texture_00.png",
            "motions/idle.motion3.json",
            "physics3.json"
        )
        val roots = rels
            .filter { it.endsWith(".model3.json", ignoreCase = true) }
            .map { it.substringBeforeLast('/', "") }
            .distinct()
        assertEquals(listOf(""), roots)
        assertTrue(rels.all { Live2dModelLayout.ownerRoot(it, roots) == "" })
        assertEquals("haitun_2", Live2dModelLayout.modelIdFor("", "haitun_2.model3.json"))
    }
}
