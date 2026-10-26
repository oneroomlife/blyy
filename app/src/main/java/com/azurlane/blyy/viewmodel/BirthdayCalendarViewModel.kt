package com.azurlane.blyy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.domain.BirthdayCalendar
import com.azurlane.blyy.domain.GetShipsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

/**
 * 舰娘生日日历状态。
 *
 * 数据链：Room ships（DOCK 档案，birthday 列）→ 按"展示月"分桶 → 日历网格；
 * 打开页面时先触发静态名单回填（幂等），首次安装/档案未刷新时列表自然为空。
 */
@HiltViewModel
class BirthdayCalendarViewModel @Inject constructor(
    getShipsUseCase: GetShipsUseCase,
    private val shipRepository: com.azurlane.blyy.data.repository.ShipRepository
) : ViewModel() {

    /** 单个寿星条目（选中日列表用） */
    data class DayBirthday(
        val ship: Ship,
        /** 展示日（2/29 平年钳制到 2/28 后的日号） */
        val day: Int
    )

    /** 近期生日条目（顶部横向预览用） */
    data class UpcomingBirthday(
        val ship: Ship,
        val date: LocalDate,
        /** 距今天数（0 = 今天） */
        val daysUntil: Int
    )

    data class UiState(
        val loading: Boolean = true,
        /** 当前展示月份 */
        val month: YearMonth = YearMonth.now(),
        /** 当前选中的日期（默认今天） */
        val selectedDate: LocalDate = LocalDate.now(),
        /** 展示月内 日号 → 寿星列表（已按稀有度/名称排序） */
        val birthdaysByDay: Map<Int, List<DayBirthday>> = emptyMap(),
        /** 选中日的寿星 */
        val selectedShips: List<DayBirthday> = emptyList(),
        /** 近期生日（未来 [UPCOMING_WINDOW_DAYS] 天内，含今天） */
        val upcoming: List<UpcomingBirthday> = emptyList(),
        /** 全库有生日的舰娘总数 */
        val totalCount: Int = 0,
        /** 今天是否有寿星（顶栏角标用） */
        val todayCount: Int = 0
    )

    private val today: LocalDate = LocalDate.now()

    private val monthFlow = MutableStateFlow(YearMonth.now())
    private val selectedFlow = MutableStateFlow(today)

    val state: StateFlow<UiState> = combine(
        getShipsUseCase(ArchiveType.DOCK),
        monthFlow,
        selectedFlow
    ) { ships, month, selected ->
        val withBirthday = ships.mapNotNull { ship ->
            BirthdayCalendar.parse(ship.birthday)?.let { ship to it }
        }
        val byDay = buildMap {
            for ((ship, birthday) in withBirthday) {
                val day = BirthdayCalendar.dayInMonth(birthday, month) ?: continue
                getOrPut(day) { mutableListOf() }.add(DayBirthday(ship, day))
            }
        }.mapValues { (_, list) -> list.sortedWith(compareBy({ it.ship.rarityRank() }, { it.ship.name })) }

        val selectedShips = byDay[selected.dayOfMonth].orEmpty()

        val upcoming = withBirthday.mapNotNull { (ship, birthday) ->
            val date = BirthdayCalendar.nextOccurrence(birthday, today)
            val days = BirthdayCalendar.daysUntil(birthday, today)
            if (days > UPCOMING_WINDOW_DAYS) null
            else UpcomingBirthday(ship, date, days)
        }.sortedWith(
            compareBy({ it.daysUntil }, { it.ship.rarityRank() }, { it.ship.name })
        ).take(UPCOMING_LIMIT)

        UiState(
            loading = false,
            month = month,
            selectedDate = selected,
            birthdaysByDay = byDay,
            selectedShips = selectedShips,
            upcoming = upcoming,
            totalCount = withBirthday.size,
            todayCount = byDay[today.dayOfMonth].orEmpty().size
                .takeIf { month == YearMonth.from(today) } ?: 0
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        // 打开页面即触发静态名单回填（幂等、仅写差异行）；失败静默（无生日数据降级）
        viewModelScope.launch {
            runCatching { shipRepository.seedDockBirthdaysIfNeeded() }
        }
    }

    /** 切换展示月份；选中日跟随：回到当前真实月份时落回今天，否则同日号钳制到月末 */
    fun selectMonth(month: YearMonth) {
        monthFlow.value = month
        val current = selectedFlow.value
        selectedFlow.value = if (month == YearMonth.from(today)) {
            today
        } else {
            month.atDay(current.dayOfMonth.coerceAtMost(month.lengthOfMonth()))
        }
    }

    fun previousMonth() = selectMonth(monthFlow.value.minusMonths(1))

    fun nextMonth() = selectMonth(monthFlow.value.plusMonths(1))

    /** 回到今天所在月份并选中今天 */
    fun jumpToToday() = selectMonth(YearMonth.from(today))

    /** 选中某天（仅允许展示月内的日期） */
    fun selectDate(date: LocalDate) {
        if (date.year == monthFlow.value.year && date.month == monthFlow.value.month) {
            selectedFlow.value = date
        }
    }

    /** 稀有度排序键（数字越小越稀有），沿用 ShipDao 查询里的 CASE 顺序 */
    private fun Ship.rarityRank(): Int = when (rarity) {
        "海上传奇", "决战方案", "三星" -> 1
        "超稀有", "最高方案", "二星" -> 2
        "精锐", "一星" -> 3
        "稀有" -> 4
        "普通" -> 5
        else -> 6
    }

    companion object {
        private const val UPCOMING_WINDOW_DAYS = 60
        private const val UPCOMING_LIMIT = 40
    }
}
