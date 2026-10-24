package com.azurlane.blyy.widget

// 小组件纯逻辑单测：问候语选取 / 台词捕获 / 每日推荐池与选舰 / 稀有度配色

import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.data.model.VoiceLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WidgetLogicTest {

    // ── WidgetGreetings.pick ──

    @Test
    fun `同一天同一时段问候语确定`() {
        val a = WidgetGreetings.pick(hour = 9, daySeed = 20261024L)
        val b = WidgetGreetings.pick(hour = 9, daySeed = 20261024L)
        assertEquals(a, b)
    }

    @Test
    fun `不同时段桶返回对应时段文案`() {
        assertTrue(WidgetGreetings.pick(8, 20261024L).contains("早"))
        assertTrue(WidgetGreetings.pick(12, 20261024L).contains("午"))
        assertTrue(WidgetGreetings.pick(20, 20261024L).contains("晚"))
        // 深夜桶（0 点与 23 点同桶，但种子同一天相同）
        assertEquals(
            WidgetGreetings.pick(0, 20261024L),
            WidgetGreetings.pick(23, 20261024L)
        )
    }

    // ── pickDialogueLine ──

    private fun voice(scene: String, dialogue: String) =
        VoiceLine(skinName = "默认装扮", scene = scene, dialogue = dialogue, audioUrlCn = "", audioUrlJp = "")

    @Test
    fun `优先选取触摸主界面旗舰场景台词`() {
        val voices = listOf(
            voice("胜利", "战斗胜利的台词"),
            voice("旗舰触摸", "指挥官，有什么事吗？"),
            voice("强化成功", "强化成功台词")
        )
        assertEquals("指挥官，有什么事吗？", pickDialogueLine(voices, Random(1)))
    }

    @Test
    fun `无偏好场景时回退任意非空台词`() {
        val voices = listOf(
            voice("胜利", "  "),  // 空白台词被过滤
            voice("强化成功", "强化成功台词")
        )
        assertEquals("强化成功台词", pickDialogueLine(voices, Random(1)))
    }

    @Test
    fun `清洗换行并截断超长台词`() {
        val long = "a".repeat(60)
        val result = pickDialogueLine(listOf(voice("旗舰触摸", long + "\n\r")), Random(1))
        assertNotNull(result)
        assertTrue(result!!.length == 41) // 40 字符 + 省略号
    }

    @Test
    fun `全空台词返回 null`() {
        assertNull(pickDialogueLine(listOf(voice("旗舰触摸", " ")), Random(1)))
        assertNull(pickDialogueLine(emptyList(), Random(1)))
    }

    // ── selectDailyPool ──

    private fun ship(name: String, favorite: Boolean = false) = Ship(
        name = name,
        avatarUrl = "https://example.com/$name.png",
        borderUrl = null,
        link = name,
        type = "驱逐舰",
        rarity = "超稀有",
        faction = "白鹰",
        extra = "",
        archiveType = "DOCK",
        isFavorite = favorite,
        filterData = ""
    )

    @Test
    fun `收藏达到阈值用收藏池`() {
        val favorites = listOf(ship("A", true), ship("B", true), ship("C", true))
        val all = favorites + listOf(ship("D"), ship("E"))
        val pool = selectDailyPool(favorites, all, minPool = 3)
        assertEquals(setOf("A", "B", "C"), pool.map { it.name }.toSet())
    }

    @Test
    fun `收藏不足回退全部舰船`() {
        val favorites = listOf(ship("A", true))
        val all = favorites + listOf(ship("D"), ship("E"))
        val pool = selectDailyPool(favorites, all, minPool = 3)
        assertEquals(setOf("A", "D", "E"), pool.map { it.name }.toSet())
    }

    @Test
    fun `收藏为空回退全部舰船`() {
        val all = listOf(ship("D"), ship("E"))
        assertEquals(all, selectDailyPool(emptyList(), all, minPool = 3))
    }

    // ── pickDailyShip ──

    @Test
    fun `同一天选舰结果确定且与列表顺序无关`() {
        val pool = listOf(ship("贝尔法斯特"), ship("企业"), ship("独角兽"), ship("圣地亚哥"))
        val shuffledPool = pool.shuffled(Random(99))
        assertEquals(
            pickDailyShip(pool, 20261024L)?.name,
            pickDailyShip(shuffledPool, 20261024L)?.name
        )
    }

    @Test
    fun `不同日期大概率换舰且空池返回 null`() {
        val pool = (1..20).map { ship("舰$it") }
        val today = pickDailyShip(pool, 20261024L)?.name
        val names = (20261020L..20261028L).mapNotNull { pickDailyShip(pool, it)?.name }
        assertTrue(today in names)
        assertTrue(names.distinct().size > 1) // 九天里应出现不止一位
        assertNull(pickDailyShip(emptyList(), 20261024L))
    }

    // ── rarityColorArgb ──

    @Test
    fun `稀有度映射颜色档位`() {
        assertEquals(0xFFE8A838, rarityColorArgb("海上传奇"))
        assertEquals(0xFFE8A838, rarityColorArgb("决战方案"))
        assertEquals(0xFF48CAE4, rarityColorArgb("超稀有"))
        assertEquals(0xFF48CAE4, rarityColorArgb("最高方案"))
        assertEquals(0xFFB08BEA, rarityColorArgb("精锐"))
        assertEquals(0xFF6FA8FF, rarityColorArgb("稀有"))
        assertEquals(0xFF9FB3C8, rarityColorArgb("普通"))
        assertEquals(0xFF9FB3C8, rarityColorArgb("未知档位"))
    }

    // ── 语音表持久缓存编解码 ──

    @Test
    fun `语音表缓存往返无损`() {
        val voices = listOf(
            VoiceLine("默认装扮", "旗舰触摸", "指挥官，有什么事吗？", "https://a/1.mp3", "https://a/1_jp.mp3"),
            VoiceLine("换装1", "自我介绍", "初次见面", "", "")
        )
        val json = encodeWidgetVoicesCache("贝尔法斯特", voices, now = 1000L)
        val decoded = decodeWidgetVoicesCache(json, "贝尔法斯特", now = 1000L + WIDGET_VOICES_CACHE_TTL_MS - 1)
        assertEquals(voices, decoded)
    }

    @Test
    fun `缓存过期或舰名不匹配返回 null`() {
        val json = encodeWidgetVoicesCache("企业", emptyList(), now = 1000L)
        assertNull(decodeWidgetVoicesCache(json, "企业", now = 1000L + WIDGET_VOICES_CACHE_TTL_MS + 1))
        assertNull(decodeWidgetVoicesCache(json, "圣地亚哥", now = 2000L))
        assertNull(decodeWidgetVoicesCache("损坏的{{{json", "企业", now = 1000L))
        assertNull(decodeWidgetVoicesCache("", "企业", now = 1000L))
    }
}
