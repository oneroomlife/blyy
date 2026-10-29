package com.azurlane.blyy.widget

import com.azurlane.blyy.data.model.Ship
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * [BirthdayWidgetData] 纯逻辑单测：今日寿星选取与排序、近期生日窗口、
 * 文案生成、空态降级、2/29 平年钳制下的跨年表现。
 */
class BirthdayWidgetDataTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 26)

    private fun ship(
        name: String,
        birthday: String,
        rarity: String = "精锐",
        avatarUrl: String = "https://wiki.example/$name.jpg"
    ): Ship = Ship(
        name = name,
        avatarUrl = avatarUrl,
        borderUrl = null,
        link = "/blhx/$name",
        type = "驱逐",
        rarity = rarity,
        faction = "白鹰",
        extra = "",
        archiveType = "DOCK",
        birthday = birthday
    )

    /** 固定解析回调：本地匹配与网络一致，测试中直接透传舰名便于断言 */
    private val identityAvatar: (Ship) -> String = { it.avatarUrl }

    // ── build：今日寿星 ──

    @Test
    fun `today ships are selected and ordered by rarity then name`() {
        val state = BirthdayWidgetData.build(
            ships = listOf(
                ship("普通舰", "10-26", rarity = "普通"),
                ship("精锐舰", "10-26"),
                ship("传奇舰", "10-26", rarity = "海上传奇"),
                ship("别天舰", "12-07")
            ),
            today = today,
            avatarUrlOf = identityAvatar
        )
        assertEquals(listOf("传奇舰", "精锐舰", "普通舰"), state.todayShips.map { it.name })
        assertEquals(3, state.todayCount)
        assertTrue(state.todayShips.all { it.daysUntil == 0 })
    }

    @Test
    fun `today shows at most 3 with total count preserved for plus-N`() {
        val state = BirthdayWidgetData.build(
            ships = (1..5).map { ship("舰$it", "10-26") },
            today = today,
            avatarUrlOf = identityAvatar
        )
        assertEquals(3, state.todayShips.size)
        assertEquals(5, state.todayCount)
    }

    // ── build：近期生日 ──

    @Test
    fun `upcoming excludes today and sorts by days then rarity`() {
        val state = BirthdayWidgetData.build(
            ships = listOf(
                ship("远期舰", "12-07"),                       // 42 天
                ship("明天舰", "10-27"),
                ship("今天的舰", "10-26"),                     // 不进 upcoming
                ship("远期精锐", "12-07", rarity = "海上传奇") // 42 天，更稀有排前
            ),
            today = today,
            avatarUrlOf = identityAvatar
        )
        // MAX_UPCOMING=2 截断：同分(42天)下更稀有的「远期精锐」留下，「远期舰」被挤出
        assertEquals(listOf("明天舰", "远期精锐"), state.upcomingShips.map { it.name })
        assertEquals(1, state.upcomingShips[0].daysUntil)
        assertEquals(42, state.upcomingShips[1].daysUntil)
        assertTrue(state.upcomingShips.none { it.daysUntil == 0 })
    }

    @Test
    fun `upcoming caps at 2 entries`() {
        val state = BirthdayWidgetData.build(
            ships = listOf(
                ship("舰1", "10-27"),
                ship("舰2", "10-28"),
                ship("舰3", "10-29"),
                ship("舰4", "10-30"),
                ship("舰5", "10-31")
            ),
            today = today,
            avatarUrlOf = identityAvatar
        )
        assertEquals(2, state.upcomingShips.size)
        // 最近优先：明天(10-27)、后天(10-28)
        assertEquals(1, state.upcomingShips[0].daysUntil)
        assertEquals(2, state.upcomingShips[1].daysUntil)
    }

    @Test
    fun `upcoming beyond 60 day window is dropped`() {
        val state = BirthdayWidgetData.build(
            ships = listOf(ship("远舰", "03-01")),  // 距 10-26 约 126 天
            today = today,
            avatarUrlOf = identityAvatar
        )
        assertTrue(state.upcomingShips.isEmpty())
    }

    // ── 跨年与 2/29 ──

    @Test
    fun `february 29 birthday counts against feb28 in common year`() {
        // 今天 2027-01-15（平年）：2/29 钳制到 2/28，共 44 天，在 60 天窗口内
        val state = BirthdayWidgetData.build(
            ships = listOf(ship("闰日舰", "02-29")),
            today = LocalDate.of(2027, 1, 15),
            avatarUrlOf = identityAvatar
        )
        assertEquals(44, state.upcomingShips.single().daysUntil)
        // 对照：超窗口（2026-10-26 视角 125 天）时正常落出预览列表
        val far = BirthdayWidgetData.build(
            ships = listOf(ship("闰日舰", "02-29")),
            today = today,
            avatarUrlOf = identityAvatar
        )
        assertTrue(far.upcomingShips.isEmpty())
    }

    @Test
    fun `december birthday wraps across year boundary`() {
        val state = BirthdayWidgetData.build(
            ships = listOf(ship("年末舰", "12-29")),
            today = LocalDate.of(2026, 12, 25),
            avatarUrlOf = identityAvatar
        )
        assertEquals(4, state.upcomingShips.single().daysUntil)
    }

    // ── 降级与解析 ──

    @Test
    fun `invalid birthday entries are ignored`() {
        val state = BirthdayWidgetData.build(
            ships = listOf(ship("脏数据", "13-99"), ship("空数据", "")),
            today = today,
            avatarUrlOf = identityAvatar
        )
        assertFalse(state.hasAnyData)
        assertEquals(0, state.todayCount)
    }

    @Test
    fun `empty ships produce empty state with date label`() {
        val state = BirthdayWidgetData.build(emptyList(), today, identityAvatar)
        assertFalse(state.hasAnyData)
        assertEquals("10月26日 · 周一", state.dateLabel)
    }

    @Test
    fun `empty fallback uses today and no data`() {
        val state = BirthdayWidgetData.empty(LocalDate.of(2026, 10, 26))
        assertFalse(state.hasAnyData)
        assertEquals("10月26日 · 周一", state.dateLabel)
    }

    @Test
    fun `avatar resolver is applied per ship`() {
        val state = BirthdayWidgetData.build(
            ships = listOf(ship("拉菲", "10-30")),
            today = today,
            avatarUrlOf = { "asset:///${it.name}" }
        )
        assertEquals("asset:///拉菲", state.upcomingShips.single().avatarUrl)
    }

    // ── 文案 ──

    @Test
    fun `date label formats chinese weekday`() {
        assertEquals("10月26日 · 周一", BirthdayWidgetData.dateLabel(LocalDate.of(2026, 10, 26)))
        assertEquals("1月1日 · 周四", BirthdayWidgetData.dateLabel(LocalDate.of(2026, 1, 1)))
        assertEquals("3月17日 · 周二", BirthdayWidgetData.dateLabel(LocalDate.of(2026, 3, 17)))
    }

    @Test
    fun `countdown labels match calendar page wording`() {
        assertEquals("今天", BirthdayWidgetData.countdownLabel(0))
        assertEquals("明天", BirthdayWidgetData.countdownLabel(1))
        assertEquals("3天后", BirthdayWidgetData.countdownLabel(3))
    }
}
