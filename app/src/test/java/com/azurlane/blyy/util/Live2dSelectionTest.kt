package com.azurlane.blyy.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live2D 皮肤库多选逻辑回归测试。
 *
 * 守护点：选中集与"实际会删除的模型"必须始终一致——非法 id 不能进入选中集
 *（防路径穿越进删除流程）、全选只作用于传入列表、删库/重扫后选中项要收敛。
 */
class Live2dSelectionTest {

    @Test
    fun `toggle adds and removes`() {
        var sel = Live2dSelection.toggle(emptySet(), "adaerbote_2")
        assertEquals(setOf("adaerbote_2"), sel)
        sel = Live2dSelection.toggle(sel, "chaijun_6")
        assertEquals(setOf("adaerbote_2", "chaijun_6"), sel)
        sel = Live2dSelection.toggle(sel, "adaerbote_2")
        assertEquals(setOf("chaijun_6"), sel)
    }

    @Test
    fun `toggle rejects path traversal and blank ids`() {
        // 反斜杠用字符拼接，避免源码转义歧义（"a\\b" 与 "a\b" 是两回事）
        val backslash = 0x5C.toChar().toString()
        val dangerous = listOf(
            "", "  ", "../etc", "a/b", "a" + backslash + "b", "..", ".thumbs", "foo..bar"
        )
        var sel: Set<String> = emptySet()
        for (id in dangerous) {
            sel = Live2dSelection.toggle(sel, id)
        }
        assertTrue("危险 id 不应进入选中集：$sel", sel.isEmpty())
        assertFalse(Live2dSelection.isSelectable(".thumbs"))
        assertTrue(Live2dSelection.isSelectable("z46_3"))
    }

    @Test
    fun `select all only affects provided ids`() {
        val selected = setOf("keep_1")
        val ids = listOf("a_1", "a_2", "b_3")
        val all = Live2dSelection.selectAll(selected, ids, select = true)
        assertEquals(setOf("keep_1", "a_1", "a_2", "b_3"), all)
        // 取消全选只移除本次列表内的项，列表外的选中项保留
        val cleared = Live2dSelection.selectAll(all, ids, select = false)
        assertEquals(setOf("keep_1"), cleared)
    }

    @Test
    fun `select all skips invalid ids in the list`() {
        val all = Live2dSelection.selectAll(emptySet(), listOf("ok_1", "../bad", ".thumbs"), true)
        assertEquals(setOf("ok_1"), all)
    }

    @Test
    fun `retainExisting drops deleted models`() {
        val selected = setOf("a_1", "a_2", "a_3")
        val retained = Live2dSelection.retainExisting(selected, listOf("a_1", "a_3", "b_9"))
        assertEquals(setOf("a_1", "a_3"), retained)
        // 模型库清空 → 选中集也清空（调用方据此退出多选模式）
        assertTrue(Live2dSelection.retainExisting(selected, emptyList()).isEmpty())
    }

    @Test
    fun `isAllSelected handles empty and partial lists`() {
        assertFalse("空列表不算全选", Live2dSelection.isAllSelected(setOf("a"), emptyList()))
        assertFalse(Live2dSelection.isAllSelected(setOf("a"), listOf("a", "b")))
        assertTrue(Live2dSelection.isAllSelected(setOf("a", "b"), listOf("a", "b")))
        // 列表里的非法 id 不参与判断，否则"全选"按钮永远点不亮
        assertTrue(Live2dSelection.isAllSelected(setOf("a"), listOf("a", ".thumbs")))
    }
}
