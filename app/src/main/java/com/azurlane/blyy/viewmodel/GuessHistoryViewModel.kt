package com.azurlane.blyy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.data.local.GuessHistoryDao
import com.azurlane.blyy.data.model.GuessHistory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 四元组辅助类 */
private data class Quad<A, B, C, D>(
    val first: A, val second: B, val third: C, val fourth: D
)

/** 五元组辅助类 */
private data class Quint<A, B, C, D, E>(
    val first: A, val second: B, val third: C, val fourth: D, val fifth: E
)

/**
 * 历史记录筛选模式
 */
enum class HistoryFilter {
    ALL,    // 全部
    IMAGE,  // 看图识舰娘
    VOICE   // 听音识舰娘
}

/**
 * 历史记录统计摘要（数据库端聚合，避免客户端遍历计算）
 */
data class HistoryStats(
    val totalCount: Int = 0,
    val imageCount: Int = 0,
    val voiceCount: Int = 0,
    val totalScoreSum: Int = 0,
    val totalQuestionsSum: Int = 0,
    val totalCorrectSum: Int = 0,
    val highestScore: Int = 0,
    val overallAccuracy: Float = 0f
)

/**
 * 历史记录 UI 状态
 */
data class GuessHistoryUiState(
    val isLoading: Boolean = true,
    val records: List<GuessHistory> = emptyList(),
    val filter: HistoryFilter = HistoryFilter.ALL,
    val selectedIds: Set<Long> = emptySet(),
    val isMultiSelectMode: Boolean = false,
    val detailRecord: GuessHistory? = null,
    val isEmpty: Boolean = false,
    val stats: HistoryStats = HistoryStats()
)

@HiltViewModel
class GuessHistoryViewModel @Inject constructor(
    private val historyDao: GuessHistoryDao
) : ViewModel() {

    companion object {
        private const val TAG = "GuessHistoryViewModel"
        /** nickname 默认值：无法从"碧蓝航线助手"解析到玩家 ID 时使用 */
        private const val DEFAULT_NICKNAME = "指挥官"
    }

    private val _filter = MutableStateFlow(HistoryFilter.ALL)
    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _isMultiSelectMode = MutableStateFlow(false)
    private val _detailRecord = MutableStateFlow<GuessHistory?>(null)

    // 历史记录只做浏览/删除：成绩榜已是纯本地实现（见 domain/PersonalScoreBoard），
    // 本类不再持有任何上传态、凭证或远端依赖。

    /**
     * 根据筛选条件查询历史记录
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val _records = _filter.flatMapLatest { filter ->
        when (filter) {
            HistoryFilter.ALL -> historyDao.getAllHistory()
            HistoryFilter.IMAGE -> historyDao.getHistoryByMode("IMAGE")
            HistoryFilter.VOICE -> historyDao.getHistoryByMode("VOICE")
        }
    }

    // ── 统计数据：使用数据库端聚合查询，避免客户端遍历 ──
    // 使用嵌套 combine 绕过 5 参数限制，保持类型安全
    private val _stats = combine(
        historyDao.getCount(),
        historyDao.getCountByMode(),
        historyDao.getTotalScoreSum()
    ) { count, modeCounts, scoreSum ->
        Triple(count, modeCounts, scoreSum)
    }.combine(
        historyDao.getTotalQuestionsSum()
    ) { (count, modeCounts, scoreSum), questionsSum ->
        Quad(count, modeCounts, scoreSum, questionsSum)
    }.combine(
        historyDao.getTotalCorrectSum()
    ) { (count, modeCounts, scoreSum, questionsSum), correctSum ->
        Quint(count, modeCounts, scoreSum, questionsSum, correctSum)
    }.combine(
        historyDao.getHighestScore()
    ) { (count, modeCounts, scoreSum, questionsSum, correctSum), highest ->
        val imageCount = modeCounts.find { mc -> mc.mode == "IMAGE" }?.count ?: 0
        val voiceCount = modeCounts.find { mc -> mc.mode == "VOICE" }?.count ?: 0
        val accuracy = if (questionsSum > 0) correctSum.toFloat() / questionsSum else 0f
        HistoryStats(
            totalCount = count,
            imageCount = imageCount,
            voiceCount = voiceCount,
            totalScoreSum = scoreSum,
            totalQuestionsSum = questionsSum,
            totalCorrectSum = correctSum,
            highestScore = highest ?: 0,
            overallAccuracy = accuracy
        )
    }

    // uiState 必须把 filter/selectedIds/isMultiSelectMode/detailRecord 全部纳入 combine 上游：
    // 若在 transform 内用 .value 快照读取，这些流的变化不会触发 uiState 重发射，
    // 多选勾选/详情弹窗的刷新将依赖 Room 恰好发射的巧合（B1 修复）。
    // uiState 必须把 filter/selectedIds/isMultiSelectMode/detailRecord 全部纳入 combine 上游：
    // 若在 transform 内用 .value 快照读取，这些流的变化不会触发 uiState 重发射，
    // 多选勾选/详情弹窗的刷新将依赖 Room 恰好发射的巧合（B1 修复）。
    // 本项目 kotlinx.coroutines 的扩展 combine 仅支持 2 流，3 流以上用顶层 combine，
    // 故采用与 _stats 链相同的级联写法（3-flow 顶层 + 2-flow 扩展）。
    private val baseInputs = combine(_stats, _records, _filter) { stats, records, filter ->
        Triple(stats, records, filter)
    }

    private val inputs2 = baseInputs.combine(_selectedIds) { base, selectedIds ->
        Quad(base.first, base.second, base.third, selectedIds)
    }

    private val inputs3 = inputs2.combine(_isMultiSelectMode) { inputs, isMultiSelect ->
        Quint(inputs.first, inputs.second, inputs.third, inputs.fourth, isMultiSelect)
    }

    val uiState: StateFlow<GuessHistoryUiState> =
        inputs3.combine(_detailRecord) { inputs, detail ->
            GuessHistoryUiState(
                isLoading = false,
                records = inputs.second,
                filter = inputs.third,
                selectedIds = inputs.fourth,
                isMultiSelectMode = inputs.fifth,
                detailRecord = detail,
                isEmpty = inputs.second.isEmpty(),
                stats = inputs.first
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = GuessHistoryUiState()
        )

    /** 切换筛选模式，同时清除详情对话框状态（防止切换分类后弹窗残留） */
    fun setFilter(filter: HistoryFilter) {
        _filter.value = filter
        _detailRecord.value = null
    }

    /** 进入多选模式 */
    fun enterMultiSelectMode() {
        _isMultiSelectMode.value = true
    }

    /** 退出多选模式并清空选择 */
    fun exitMultiSelectMode() {
        _isMultiSelectMode.value = false
        _selectedIds.value = emptySet()
    }

    /** 切换某条记录的选中状态 */
    fun toggleSelection(id: Long) {
        _selectedIds.update { current ->
            if (id in current) current - id else current + id
        }
    }

    /** 全选当前列表 */
    fun selectAll() {
        _selectedIds.value = uiState.value.records.map { it.id }.toSet()
    }

    /** 删除单条记录 */
    fun deleteRecord(id: Long) {
        viewModelScope.launch {
            historyDao.deleteById(id)
        }
    }

    /** 批量删除选中的记录 */
    fun deleteSelected() {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            historyDao.deleteByIds(ids)
            exitMultiSelectMode()
        }
    }

    /** 删除所有记录 */
    fun deleteAll() {
        viewModelScope.launch {
            historyDao.deleteAll()
            exitMultiSelectMode()
        }
    }

    /** 查看详情 */
    fun showDetail(record: GuessHistory) {
        _detailRecord.value = record
    }

    /** 关闭详情 */
    fun closeDetail() {
        _detailRecord.value = null
    }

}
