package com.azurlane.blyy.util.gameimport

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 排序纯函数的回归测试。
 *
 * 这里刻意只测「顺序」本身：资源包 / 资源库两条入口都收敛到
 * [GameAssetSorting.sort]，字段与方向的全部组合都在这一层出现一次，
 * UI 侧只负责把 `GameSortOrder` 传下来。
 */
class GameAssetSortingTest {

    // ---------------------------------------------------------------- 排序方案（GameSortOrder）

    @Test
    fun `默认方案是名称正序`() {
        val default = GameSortOrder.Default
        assertEquals(GameSortField.NAME, default.field)
        assertEquals(true, default.ascending)
        assertEquals("升序", default.directionLabel)
    }

    @Test
    fun `重复点同一个字段翻转方向`() {
        val nameAsc = GameSortOrder(GameSortField.NAME, ascending = true)
        val once = nameAsc.selecting(GameSortField.NAME)
        assertEquals(GameSortOrder(GameSortField.NAME, ascending = false), once)
        assertEquals("降序", once.directionLabel)
        // 再点一次回到正序：两个入口（芯片 / 方向按钮）行为一致
        assertEquals(nameAsc, once.selecting(GameSortField.NAME))
        assertEquals(nameAsc, once.toggled())
    }

    @Test
    fun `切到另一个字段用它的默认方向`() {
        // 名称降序 → 点「时间」应得到「时间倒序」（最新在前），而不是沿用降序
        val switched = GameSortOrder(GameSortField.NAME, ascending = false)
            .selecting(GameSortField.TIME)
        assertEquals(GameSortField.TIME, switched.field)
        assertEquals(false, switched.ascending)

        // 时间升序 → 点「名称」应得到「名称正序」
        val back = GameSortOrder(GameSortField.TIME, ascending = true)
            .selecting(GameSortField.NAME)
        assertEquals(GameSortOrder(GameSortField.NAME, ascending = true), back)
    }

    @Test
    fun `四个组合的持久化编码可往返`() {
        val all = GameSortField.entries.flatMap { field ->
            listOf(GameSortOrder(field, true), GameSortOrder(field, false))
        }
        all.forEach { order ->
            assertEquals(order, GameSortOrder.parse(order.storageKey))
        }
        assertEquals("name_asc", GameSortOrder(GameSortField.NAME, true).storageKey)
        assertEquals("time_desc", GameSortOrder(GameSortField.TIME, false).storageKey)
    }

    @Test
    fun `脏数据解析返回 null 由调用方保留当前方案`() {
        listOf(null, "", "   ", "size_asc", "名称_asc", "timex_desc").forEach { raw ->
            assertNull("raw=$raw", GameSortOrder.parse(raw))
        }
    }

    @Test
    fun `方向写坏或缺失时回落到该字段的默认方向`() {
        // 只写字段名（历史/手写配置）与写了未知方向，都按字段默认方向处理，不让排序失效
        assertEquals(GameSortOrder(GameSortField.TIME, false), GameSortOrder.parse("time"))
        assertEquals(GameSortOrder(GameSortField.TIME, false), GameSortOrder.parse("time_up"))
        assertEquals(GameSortOrder(GameSortField.NAME, true), GameSortOrder.parse("name_whatever"))
        // 大小写与空格容错
        assertEquals(GameSortOrder(GameSortField.NAME, false), GameSortOrder.parse(" NAME _ DESC "))
    }

    // ---------------------------------------------------------------- 资源包排序

    @Test
    fun `资源包按名称升序与降序`() {
        val entries = listOf(
            bundle("Z23", "p3", modified = 300L),
            bundle("埃塞克斯", "p1", modified = 100L),
            bundle("巴尔的摩", "p2", modified = 200L)
        )

        // 拉丁字母在 zh 排序规则里排在汉字之前，与系统文件管理器一致
        val asc = GameAssetSorting.sortBundles(entries, GameSortOrder(GameSortField.NAME, true)) { it.displayName }
        assertEquals(listOf("p3", "p1", "p2"), asc.map { it.path })

        val desc = GameAssetSorting.sortBundles(entries, GameSortOrder(GameSortField.NAME, false)) { it.displayName }
        assertEquals(listOf("p2", "p1", "p3"), desc.map { it.path })
    }

    @Test
    fun `中文名走拼音序而不是码位序`() {
        // 码位序是「埃(57C3) < 巴(5DF4) < 企(4F01)」以外的乱序，拼音序才是 a < b < q
        val entries = listOf(
            bundle("企业", "p3", modified = 0L),
            bundle("埃塞克斯", "p1", modified = 0L),
            bundle("巴尔的摩", "p2", modified = 0L)
        )
        val sorted = GameAssetSorting.sortBundles(entries, GameSortOrder(GameSortField.NAME, true)) { it.displayName }
        assertEquals(listOf("p1", "p2", "p3"), sorted.map { it.path })
    }

    @Test
    fun `资源包按时间排序默认最新在前`() {
        val entries = listOf(
            bundle("A", "p1", modified = 100L),
            bundle("B", "p2", modified = 300L),
            bundle("C", "p3", modified = 200L)
        )

        val newest = GameAssetSorting.sortBundles(entries, GameSortOrder(GameSortField.TIME, false)) { it.displayName }
        assertEquals(listOf(300L, 200L, 100L), newest.map { it.modified })

        val oldest = GameAssetSorting.sortBundles(entries, GameSortOrder(GameSortField.TIME, true)) { it.displayName }
        assertEquals(listOf(100L, 200L, 300L), oldest.map { it.modified })
    }

    @Test
    fun `时间相同按名称排出稳定顺序且翻转方向不乱跳`() {
        val entries = listOf(
            bundle("Zeta", "p3", modified = 100L),
            bundle("Alpha", "p1", modified = 100L),
            bundle("Mike", "p2", modified = 100L)
        )
        val asc = GameAssetSorting.sortBundles(entries, GameSortOrder(GameSortField.TIME, true)) { it.displayName }
        val desc = GameAssetSorting.sortBundles(entries, GameSortOrder(GameSortField.TIME, false)) { it.displayName }
        // 次关键字固定升序：主轴换向不影响同名/同时间条目的相对顺序
        assertEquals(listOf("p1", "p2", "p3"), asc.map { it.path })
        assertEquals(listOf("p1", "p2", "p3"), desc.map { it.path })
    }

    // ---------------------------------------------------------------- 资源库排序

    @Test
    fun `已导入资源按名称与时间排序`() {
        val assets = listOf(
            asset("blhx_ying", modifiedAt = 300L),
            asset("blhx_ai", modifiedAt = 100L),
            asset("blhx_ba", modifiedAt = 200L)
        )

        val byName = GameAssetSorting.sortAssets(assets, GameSortOrder(GameSortField.NAME, true)) { it.id }
        assertEquals(listOf("blhx_ai", "blhx_ba", "blhx_ying"), byName.map { it.id })

        val byNewest = GameAssetSorting.sortAssets(assets, GameSortOrder(GameSortField.TIME, false)) { it.id }
        assertEquals(listOf("blhx_ying", "blhx_ba", "blhx_ai"), byNewest.map { it.id })
    }

    @Test
    fun `同一 id 跨类型时用 kind 兜底不会丢条目`() {
        val assets = listOf(
            asset("same", modifiedAt = 100L, kind = GameAssetKind.LIVE2D),
            asset("same", modifiedAt = 100L, kind = GameAssetKind.PAINTING),
            asset("same", modifiedAt = 100L, kind = GameAssetKind.DYNAMIC_SKIN)
        )
        val sorted = GameAssetSorting.sortAssets(assets, GameSortOrder(GameSortField.TIME, false)) { it.id }
        assertEquals(3, sorted.size)
        assertEquals(assets.map { it.kind }.toSet(), sorted.map { it.kind }.toSet())
    }

    // ---------------------------------------------------------------- 边界

    @Test
    fun `零条与一条时直接返回原实例`() {
        val empty = emptyList<GameBundleEntry>()
        assertSame(empty, GameAssetSorting.sortBundles(empty, GameSortOrder.Default) { it.displayName })

        val single = listOf(bundle("A", "p1", modified = 1L))
        assertSame(single, GameAssetSorting.sortBundles(single, GameSortOrder(GameSortField.TIME, false)) { it.displayName })
    }

    @Test
    fun `名字首尾空格不参与比较`() {
        val entries = listOf(
            bundle("  Bravo ", "p2", modified = 0L),
            bundle("Alpha", "p1", modified = 0L)
        )
        val sorted = GameAssetSorting.sortBundles(entries, GameSortOrder(GameSortField.NAME, true)) { it.displayName }
        assertEquals(listOf("p1", "p2"), sorted.map { it.path })
    }

    // ---------------------------------------------------------------- 夹具

    private fun bundle(name: String, path: String, modified: Long) = GameBundleEntry(
        path = path,
        fileName = "$name.ab",
        size = 1024L,
        modified = modified,
        kind = GameAssetKind.PAINTING,
        assetId = name,
        state = GameBundleEntry.State.NEW
    )

    private fun asset(
        id: String,
        modifiedAt: Long,
        kind: GameAssetKind = GameAssetKind.PAINTING
    ) = GameAssetInfo(
        kind = kind,
        id = id,
        dir = File(id),
        sizeBytes = 1024L,
        fileCount = 1,
        modifiedAt = modifiedAt
    )
}
