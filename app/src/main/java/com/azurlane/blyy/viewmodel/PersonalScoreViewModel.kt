package com.azurlane.blyy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import com.azurlane.blyy.data.model.RankedScore
import com.azurlane.blyy.data.model.ScoreDifficultyFilter
import com.azurlane.blyy.data.model.ScoreModeFilter
import com.azurlane.blyy.data.model.ScoreRecord
import com.azurlane.blyy.data.model.ScoreSummary
import com.azurlane.blyy.data.repository.PersonalScoreRepository
import com.azurlane.blyy.domain.PersonalScoreBoard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 「我的成绩榜」UI 状态。
 *
 * 与旧「全服排行榜」状态类的差别：没有 isLoading/error 的网络态（数据来自 Room 的 Flow，
 * 冷启动即可用），取而代之的是 totalRuns vs filteredRuns 两个计数——它们在 UI 上承担
 * "筛选后为空"与"完全没有成绩"两种空态的区分。
 */
data class PersonalScoreUiState(
    val loaded: Boolean = false,
    val nickname: String = "",
    val summary: ScoreSummary = ScoreSummary(),
    val ranked: List<RankedScore> = emptyList(),
    val trend: List<Int> = emptyList(),
    val records: List<ScoreRecord> = emptyList(),
    val bestHistoryIds: Set<Long> = emptySet(),
    val totalRuns: Int = 0,
    val filteredRuns: Int = 0,
    val modeFilter: ScoreModeFilter = ScoreModeFilter.ALL,
    val difficultyFilter: ScoreDifficultyFilter = ScoreDifficultyFilter.ALL
) {
    /** 完全没有成绩（用于整页空态） */
    val isEmpty: Boolean get() = totalRuns == 0

    /** 有成绩但被筛选清空（用于"换个筛选条件"提示） */
    val isFilteredEmpty: Boolean get() = totalRuns > 0 && filteredRuns == 0

    val hasActiveFilter: Boolean
        get() = modeFilter != ScoreModeFilter.ALL || difficultyFilter != ScoreDifficultyFilter.ALL
}

@HiltViewModel
class PersonalScoreViewModel @Inject constructor(
    private val repository: PersonalScoreRepository,
    settings: PlayerSettingsDataStore
) : ViewModel() {

    private val modeFilter = MutableStateFlow(ScoreModeFilter.ALL)
    private val difficultyFilter = MutableStateFlow(ScoreDifficultyFilter.ALL)

    val uiState: StateFlow<PersonalScoreUiState> = combine(
        repository.runs,
        modeFilter,
        difficultyFilter,
        settings.commanderNickname
    ) { runs, mode, difficulty, nickname ->
        val filtered = PersonalScoreBoard.filter(runs, mode, difficulty)
        PersonalScoreUiState(
            loaded = true,
            nickname = nickname.trim(),
            // 汇总与纪录始终基于全量历史：切换筛选不该让"总局数/个人纪录"跟着变
            summary = PersonalScoreBoard.summarize(runs),
            ranked = PersonalScoreBoard.rank(filtered),
            trend = PersonalScoreBoard.trend(filtered),
            records = PersonalScoreBoard.records(runs),
            bestHistoryIds = PersonalScoreBoard.bestHistoryIds(runs),
            totalRuns = runs.size,
            filteredRuns = filtered.size,
            modeFilter = mode,
            difficultyFilter = difficulty
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PersonalScoreUiState()
    )

    fun setModeFilter(filter: ScoreModeFilter) {
        modeFilter.value = filter
    }

    fun setDifficultyFilter(filter: ScoreDifficultyFilter) {
        difficultyFilter.value = filter
    }

    /** 一键回到「全部 × 全部」 */
    fun resetFilters() {
        modeFilter.value = ScoreModeFilter.ALL
        difficultyFilter.value = ScoreDifficultyFilter.ALL
    }

    /** 删除单条成绩（个人榜允许用户自己清理误记/测试记录） */
    fun deleteRun(historyId: Long) {
        viewModelScope.launch { repository.deleteRun(historyId) }
    }

    /** 清空全部历史成绩 */
    fun clearAll() {
        viewModelScope.launch { repository.clearRuns() }
    }
}
