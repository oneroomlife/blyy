package com.azurlane.blyy.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StudentFilterData.richnessOf] 单测——ShipDao.upsertShips 的 filterData
 * 保留合并按此比较（更完整的一方胜出），语义回归靠这里守住。
 */
class StudentFilterDataTest {

    private fun full() = StudentFilterData(
        starRating = "3星",
        limitedType = "限定",
        attackType = "爆炸",
        defenseType = "轻装甲",
        formationPosition = "前排",
        combatRole = "输出",
        frontBackColor = "前",
        weaponType = "HG",
        streetAdapt = "S",
        outdoorAdapt = "A",
        indoorAdapt = "B",
        coverInteract = "高",
        school = "阿拜多斯",
        club = "对策委员会"
    )

    @Test
    fun `全量 14 维 优于 列表子集 优于 空数据`() {
        val subset = StudentFilterData(starRating = "3星", school = "阿拜多斯")
        assertTrue(StudentFilterData.richnessOf(full().toJson()) > StudentFilterData.richnessOf(subset.toJson()))
        assertTrue(StudentFilterData.richnessOf(subset.toJson()) > StudentFilterData.richnessOf(""))
        assertEquals(14, StudentFilterData.richnessOf(full().toJson()))
        assertEquals(2, StudentFilterData.richnessOf(subset.toJson()))
    }

    @Test
    fun `损坏 JSON 与空串都返回 -1 排在所有有效数据之后`() {
        // 空串无法解析出任何维度，与损坏 JSON 同为最弱（不参与保留，让位给任何有效值）
        assertEquals(-1, StudentFilterData.richnessOf("{ not json"))
        assertEquals(-1, StudentFilterData.richnessOf(""))
        assertEquals(-1, StudentFilterData.richnessOf("   "))
    }

    @Test
    fun `空字段不计入丰富度`() {
        // encodeDefaults = false：空字段不会出现在 JSON 里，fromJson 回填默认空串
        val halfFilled = StudentFilterData(
            starRating = "3星", attackType = "爆炸", school = "阿拜多斯", club = "对策委员会"
        )
        assertEquals(4, StudentFilterData.richnessOf(halfFilled.toJson()))
    }
}
