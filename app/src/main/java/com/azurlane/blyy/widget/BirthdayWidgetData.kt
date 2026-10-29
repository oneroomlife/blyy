package com.azurlane.blyy.widget

// 生日小组件数据层：纯逻辑（可 JVM 单测，零 Android 依赖）。
// provideGlance 负责取数（Room + 头像解析），本文件只做状态组装与文案，
// 与 BirthdayCalendar 域核心（日期钳制/倒计时）配合使用。

import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.domain.BirthdayCalendar
import com.azurlane.blyy.domain.ShipOrdering
import java.time.LocalDate
import java.time.MonthDay

/** 生日小组件展示快照（provideGlance 组装，Glance 组合层渲染） */
data class BirthdayWidgetState(
    val dateLabel: String,
    /** 今日寿星（稀有度+名称排序，最多 [BirthdayWidgetData.MAX_TODAY_SHIPS] 位） */
    val todayShips: List<BirthdayShipUi>,
    /** 今日寿星总数（大于展示数时渲染 "+N"） */
    val todayCount: Int,
    /** 近期生日（未来 [BirthdayWidgetData.UPCOMING_WINDOW_DAYS] 天内、不含今天，最近在前） */
    val upcomingShips: List<BirthdayShipUi>
) {
    /** 完全无数据（档案未刷新/名单未回填）→ 空态提示布局 */
    val hasAnyData: Boolean get() = todayCount > 0 || upcomingShips.isNotEmpty()
}

/** 组件内单个寿星条目 */
data class BirthdayShipUi(
    val name: String,
    val avatarUrl: String,
    val rarityColorArgb: Long,
    /** 距生日天数（0 = 今天） */
    val daysUntil: Int
)

object BirthdayWidgetData {

    /** 近期生日预览窗口（天，与生日日历页口径一致） */
    const val UPCOMING_WINDOW_DAYS = 60

    /** 今日寿星最多展示条数（超出渲染 "+N"，点击进日历页看全部） */
    const val MAX_TODAY_SHIPS = 3

    /** 近期生日最多展示条数（4x2 组件单行宽度预算） */
    const val MAX_UPCOMING = 2

    private const val WEEKDAY_LABELS = "一二三四五六日"

    /**
     * 组装展示快照。
     * @param avatarUrlOf 头像解析回调（本地 assets 优先 → 网络兜底），
     *   由提供层注入 Context 依赖，保持本函数纯函数可测
     */
    fun build(
        ships: List<Ship>,
        today: LocalDate,
        avatarUrlOf: (Ship) -> String
    ): BirthdayWidgetState {
        val ranked = ships.mapNotNull { ship ->
            val birthday = BirthdayCalendar.parse(ship.birthday) ?: return@mapNotNull null
            Triple(ship, BirthdayCalendar.daysUntil(birthday, today), birthday)
        }.sortedWith(
            compareBy(
                { it.second },
                { ShipOrdering.rarityRank(it.first.rarity) },
                { it.first.name }
            )
        )
        val todayShips = ranked.filter { it.second == 0 }
        val upcoming = ranked.filter { it.second in 1..UPCOMING_WINDOW_DAYS }.take(MAX_UPCOMING)
        return BirthdayWidgetState(
            dateLabel = dateLabel(today),
            todayShips = todayShips.take(MAX_TODAY_SHIPS).map { it.toUi(avatarUrlOf) },
            todayCount = todayShips.size,
            upcomingShips = upcoming.map { it.toUi(avatarUrlOf) }
        )
    }

    /** 空态（DB 读取失败或库内无任何生日数据） */
    fun empty(today: LocalDate = LocalDate.now()): BirthdayWidgetState =
        BirthdayWidgetState(dateLabel(today), emptyList(), 0, emptyList())

    /** 头部日期文案："10月26日 · 周一" */
    fun dateLabel(today: LocalDate): String =
        "${today.monthValue}月${today.dayOfMonth}日 · 周${WEEKDAY_LABELS[today.dayOfWeek.value - 1]}"

    /** 倒计时徽标文案（与日历页一致：今天/明天/N天，组件场景带"后"更口语） */
    fun countdownLabel(days: Int): String = when (days) {
        0 -> "今天"
        1 -> "明天"
        else -> "${days}天后"
    }

    private fun Triple<Ship, Int, MonthDay>.toUi(avatarUrlOf: (Ship) -> String) =
        BirthdayShipUi(
            name = first.name,
            avatarUrl = avatarUrlOf(first),
            rarityColorArgb = rarityColorArgb(first.rarity),
            daysUntil = second
        )
}
