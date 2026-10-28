package com.azurlane.blyy.util

import com.azurlane.blyy.util.gameimport.GameAssetNaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资源显示名解析回归（对应「船坞立绘 → 皮肤名称显示」这条链路）。
 *
 * 背景：资源库 / 查看器此前各自拼名字，导致
 * - `2b_2` / `a2_2` 这类纯字母数字舰名查不到（大小写与拼音表不匹配）；
 * - `zhaohe_4_hx` 只显示变体、丢了换装号；
 * - 舰船库还没同步完时，卡片直接显示拼音 id。
 *
 * 这里用 [Live2dNameResolver] 的纯解析逻辑（不依赖 Room / Android）锁定行为：
 * 通过注入式的映射表模拟 `pinyinMap`，复用同一套匹配分支。
 */
class AssetDisplayNameTest {

    /**
     * 复刻 [Live2dNameResolver] 的匹配顺序（与实现保持同步）。
     * 之所以不直接 new resolver：它依赖 ShipDao 与协程，JVM 单测不便构造。
     */
    private fun displayName(id: String, table: Map<String, String>): String {
        val variant = GameAssetNaming.variantOf(id)
        val baseId = variant?.first ?: id
        val base = baseName(baseId, table)
            ?: return if (variant != null) "$baseId · ${variant.second}" else id
        val skinIndex = skinIndexOf(baseId)
        val name = if (skinIndex != null && skinIndex >= 2) "$base · 换装$skinIndex" else base
        return variant?.let { "$name · ${it.second}" } ?: name
    }

    private fun baseName(id: String, table: Map<String, String>): String? {
        val stripped = stripSuffixes(id)
        val pinyin = stripped.lowercase()
        if (pinyin.isEmpty() || table.isEmpty()) return null
        table[pinyin]?.let { return it }
        table.entries.firstOrNull { it.key.equals(pinyin, ignoreCase = true) }?.let { return it.value }
        table.entries
            .filter { it.key.lowercase().startsWith(pinyin) && pinyin.length >= 4 }
            .minByOrNull { it.key.length }
            ?.let { return it.value }
        table.entries
            .filter { pinyin.startsWith(it.key.lowercase()) && it.key.length >= 6 }
            .maxByOrNull { it.key.length }
            ?.let { return it.value }
        return null
    }

    private fun stripSuffixes(id: String): String {
        var current = id
        GameAssetNaming.variantOf(current)?.let { current = it.first }
        if (skinIndexOf(current) != null) current = current.substringBeforeLast('_')
        return current
    }

    private fun skinIndexOf(id: String): Int? {
        val tail = id.substringAfterLast('_', "")
        if (tail.isEmpty() || tail.length > 2) return null
        return tail.toIntOrNull()
    }

    private val shipTable = mapOf(
        "aersasi" to "阿尔萨斯",
        "aerbien" to "阿尔比恩",
        "zhaohe" to "肇和",
        "2b" to "2B",
        "a2" to "A2",
        "adaerbote" to "阿达尔伯特亲王",
        "adaerboteqingwang" to "阿达尔伯特亲王"
    )

    @Test
    fun `换装号正确拼接`() {
        assertEquals("阿尔萨斯 · 换装2", displayName("aersasi_2", shipTable))
        assertEquals("阿尔比恩 · 换装4", displayName("aerbien_4", shipTable))
    }

    @Test
    fun `变体后缀与换装号可叠加`() {
        // 肇和 4 换装 + HX 变体：两段信息都要保留
        assertEquals("肇和 · 换装4 · HX", displayName("zhaohe_4_hx", shipTable))
    }

    @Test
    fun `纯字母数字舰名大小写不敏感`() {
        assertEquals("2B · 换装2", displayName("2b_2", shipTable))
        assertEquals("A2 · 换装2", displayName("a2_2", shipTable))
    }

    @Test
    fun `目录名比舰名拼音更长时反向命中`() {
        assertEquals("阿达尔伯特亲王", displayName("adaerboteqingwang", shipTable))
    }

    @Test
    fun `查不到时回退 id 但仍保留可读信息`() {
        // 换装号属于「可读信息」，即使舰名没解析出来也要保留；
        // 只剩形态后缀时拼成「基名 · 变体」
        assertEquals("unknownship_3 · HX", displayName("unknownship_3_hx", shipTable))
        assertEquals("unknownship · HX", displayName("unknownship_hx", shipTable))
    }

    @Test
    fun `默认皮肤不带换装号`() {
        assertEquals("阿尔萨斯", displayName("aersasi", shipTable))
        // _1 视为默认皮肤（游戏里 _1 与 default 等价）
        assertEquals("阿尔萨斯", displayName("aersasi_1", shipTable))
    }

    @Test
    fun `variantOf 只认形态后缀不吞换装号`() {
        assertNull(GameAssetNaming.variantOf("2b_2"))
        assertEquals("zhaohe_4" to "HX", GameAssetNaming.variantOf("zhaohe_4_hx"))
        assertEquals("zhaohe_4" to "DOA", GameAssetNaming.variantOf("zhaohe_4_doa"))
        assertTrue(GameAssetNaming.variantOf("aersasi") == null)
    }
}
