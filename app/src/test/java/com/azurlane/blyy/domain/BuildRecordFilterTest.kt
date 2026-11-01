package com.azurlane.blyy.domain

import com.azurlane.blyy.data.model.BuildRecordItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 建造记录属性筛选单测
 *
 * 覆盖图2两组筛选项的匹配语义、图鉴稀有度反查、以及未知稀有度的降级行为。
 */
class BuildRecordFilterTest {

    private val rarityIndex = mapOf(
        "企业" to "超稀有",
        "贝尔法斯特" to "精锐",
        "Z23" to "稀有",
        "长岛" to "普通",
        "新泽西" to "海上传奇"
    )

    private fun record(role: String, task: String) = BuildRecordItem(roleName = role, taskName = task)

    @Test
    fun `默认全部筛选不丢任何记录`() {
        val records = listOf(
            record("企业", "限时建造"),
            record("Z23", "轻型舰建造"),
            record("长岛", "重型建造")
        )

        val result = filterBuildRecords(records, BuildFilterSelection(), rarityIndex)

        assertEquals(3, result.rows.size)
        assertEquals(3, result.total)
        assertEquals(0, result.unknownRarity)
        assertFalse(result.isNarrowed)
    }

    @Test
    fun `空记录返回空结果`() {
        val result = filterBuildRecords(emptyList(), BuildFilterSelection(), rarityIndex)

        assertTrue(result.rows.isEmpty())
        assertEquals(0, result.total)
    }

    @Test
    fun `建造类型按关键词匹配建造池名称`() {
        val records = listOf(
            record("企业", "限时建造"),
            record("Z23", "轻型舰建造"),
            record("长岛", "重型建造"),
            record("新泽西", "特型建造"),
            record("贝尔法斯特", "祈愿建造"),
            record("Z23", "新手限定建造")
        )

        assertEquals(listOf("企业"), names(records, BuildPoolFilter.LIMITED))
        assertEquals(listOf("Z23"), names(records, BuildPoolFilter.LIGHT))
        assertEquals(listOf("长岛"), names(records, BuildPoolFilter.HEAVY))
        assertEquals(listOf("新泽西"), names(records, BuildPoolFilter.SPECIAL))
        assertEquals(listOf("贝尔法斯特"), names(records, BuildPoolFilter.WISH))
        assertEquals(listOf("Z23"), names(records, BuildPoolFilter.ROOKIE))
        assertEquals(6, names(records, BuildPoolFilter.ALL).size)
    }

    @Test
    fun `建造池名称为空时不命中任何具体类型`() {
        val records = listOf(record("企业", "  "))

        BuildPoolFilter.entries
            .filter { it != BuildPoolFilter.ALL }
            .forEach { filter ->
                assertTrue(
                    "空建造池名称不应命中 ${filter.label}",
                    filterBuildRecords(records, BuildFilterSelection(pool = filter), rarityIndex).rows.isEmpty()
                )
            }
        assertEquals(1, filterBuildRecords(records, BuildFilterSelection(), rarityIndex).rows.size)
    }

    @Test
    fun `稀有度依据本地图鉴反查`() {
        val records = listOf(
            record("企业", "限时建造"),
            record("贝尔法斯特", "限时建造"),
            record("Z23", "轻型舰建造"),
            record("长岛", "重型建造"),
            record("新泽西", "特型建造")
        )

        assertEquals(
            listOf("新泽西"),
            names(records, rarity = BuildRarityFilter.ULTRA_RARE)
        )
        assertEquals(
            listOf("企业"),
            names(records, rarity = BuildRarityFilter.SUPER_RARE)
        )
        assertEquals(
            listOf("贝尔法斯特"),
            names(records, rarity = BuildRarityFilter.ELITE)
        )
        assertEquals(
            listOf("Z23"),
            names(records, rarity = BuildRarityFilter.RARE)
        )
        assertEquals(
            listOf("长岛"),
            names(records, rarity = BuildRarityFilter.COMMON)
        )
    }

    @Test
    fun `图鉴未收录的舰船在稀有度筛选下被排除并计数`() {
        val records = listOf(
            record("企业", "限时建造"),
            record("某未收录舰", "限时建造")
        )

        val result = filterBuildRecords(
            records,
            BuildFilterSelection(rarity = BuildRarityFilter.SUPER_RARE),
            rarityIndex
        )

        assertEquals(listOf("企业"), result.rows.map { it.roleName })
        assertEquals(2, result.total)
        // 只在命中建造类型之后才统计未知稀有度
        assertEquals(1, result.unknownRarity)
        assertTrue(result.isNarrowed)
    }

    @Test
    fun `图鉴为空时稀有度筛选不命中任何记录`() {
        val records = listOf(record("企业", "限时建造"))

        val result = filterBuildRecords(
            records,
            BuildFilterSelection(rarity = BuildRarityFilter.SUPER_RARE),
            emptyMap()
        )

        assertTrue(result.rows.isEmpty())
        assertEquals(1, result.unknownRarity)
    }

    @Test
    fun `建造类型与稀有度组合筛选`() {
        val records = listOf(
            record("企业", "限时建造"),
            record("企业", "轻型舰建造"),
            record("Z23", "轻型舰建造")
        )

        val result = filterBuildRecords(
            records,
            BuildFilterSelection(pool = BuildPoolFilter.LIGHT, rarity = BuildRarityFilter.SUPER_RARE),
            rarityIndex
        )

        assertEquals(1, result.rows.size)
        assertEquals("企业", result.rows.first().roleName)
        assertEquals("轻型舰建造", result.rows.first().taskName)
    }

    @Test
    fun `筛选结果保留原始下标用于列表稳定 key`() {
        val records = listOf(
            record("企业", "轻型舰建造"),
            record("Z23", "轻型舰建造"),
            record("长岛", "重型建造")
        )

        val result = filterBuildRecords(
            records,
            BuildFilterSelection(pool = BuildPoolFilter.LIGHT),
            rarityIndex
        )

        assertEquals(listOf(0, 1), result.rows.map { it.sourceIndex })
    }

    @Test
    fun `筛选摘要与激活状态`() {
        val none = BuildFilterSelection()
        assertEquals(0, none.activeCount)
        assertFalse(none.isActive)
        assertEquals("全部", none.summary())

        val single = BuildFilterSelection(pool = BuildPoolFilter.LIGHT)
        assertEquals(1, single.activeCount)
        assertEquals("轻型建造", single.summary())

        val singleRarity = BuildFilterSelection(rarity = BuildRarityFilter.ELITE)
        assertEquals("精锐", singleRarity.summary())

        val both = BuildFilterSelection(BuildPoolFilter.LIMITED, BuildRarityFilter.ULTRA_RARE)
        assertEquals(2, both.activeCount)
        assertTrue(both.isActive)
        assertEquals("限时建造 · 海上传奇", both.summary())
    }

    @Test
    fun `舰名归一去除普通与全角空白`() {
        assertEquals("企业", normalizeShipName("  企业 "))
        assertEquals("Z23", normalizeShipName("Z23\u3000"))
        assertEquals("Z 23", normalizeShipName(" Z\u300023 "))
    }

    private fun names(
        records: List<BuildRecordItem>,
        pool: BuildPoolFilter = BuildPoolFilter.ALL,
        rarity: BuildRarityFilter = BuildRarityFilter.ALL
    ): List<String> = filterBuildRecords(
        records = records,
        selection = BuildFilterSelection(pool = pool, rarity = rarity),
        rarityByName = rarityIndex
    ).rows.map { it.roleName }
}
