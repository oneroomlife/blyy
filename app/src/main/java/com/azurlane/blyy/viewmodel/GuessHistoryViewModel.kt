package com.azurlane.blyy.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.BuildConfig
import com.azurlane.blyy.data.local.GuessHistoryDao
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import com.azurlane.blyy.data.model.GuessHistory
import com.azurlane.blyy.data.model.LEADERBOARD_ENTRY_FORMAT_VERSION
import com.azurlane.blyy.data.model.LeaderboardCategory
import com.azurlane.blyy.data.model.LeaderboardEntry
import com.azurlane.blyy.data.repository.AssistantRepository
import com.azurlane.blyy.data.repository.LeaderboardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
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

/** 单条记录的上传状态 */
sealed class RecordUploadStatus {
    /** 上传中 */
    object Uploading : RecordUploadStatus()
    /** 上传成功 */
    object Success : RecordUploadStatus()
    /** 上传失败，[message] 为失败原因 */
    data class Failed(val message: String) : RecordUploadStatus()
}

@HiltViewModel
class GuessHistoryViewModel @Inject constructor(
    private val historyDao: GuessHistoryDao,
    private val leaderboardRepository: LeaderboardRepository,
    private val assistantRepository: AssistantRepository,
    private val settings: PlayerSettingsDataStore
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

    /** 每条记录的上传状态，key = GuessHistory.id */
    private val _uploadStatuses = MutableStateFlow<Map<Long, RecordUploadStatus>>(emptyMap())
    val uploadStatuses: StateFlow<Map<Long, RecordUploadStatus>> = _uploadStatuses.asStateFlow()

    /** 需要提示配置 UID/服务器的记录（未配置时上传触发） */
    private val _configPromptRecord = MutableStateFlow<GuessHistory?>(null)
    val configPromptRecord: StateFlow<GuessHistory?> = _configPromptRecord.asStateFlow()

    /** 需要输入自定义昵称的记录（无法通过 UID/服务器查询到玩家信息时触发） */
    private val _nicknamePromptRecord = MutableStateFlow<GuessHistory?>(null)
    val nicknamePromptRecord: StateFlow<GuessHistory?> = _nicknamePromptRecord.asStateFlow()

    /** 预填的昵称建议（已保存的自定义昵称或本地用户名） */
    private val _suggestedNickname = MutableStateFlow("")
    val suggestedNickname: StateFlow<String> = _suggestedNickname.asStateFlow()

    // 用户 UID / 服务器（复用小助手配置，用于上传成绩）
    val userUid = settings.assistantDefaultUid
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val userServer = settings.assistantDefaultServer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

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

    val uiState: StateFlow<GuessHistoryUiState> =
        combine(_stats, _records) { stats, records ->
            GuessHistoryUiState(
                isLoading = false,
                records = records,
                filter = _filter.value,
                selectedIds = _selectedIds.value,
                isMultiSelectMode = _isMultiSelectMode.value,
                detailRecord = _detailRecord.value,
                isEmpty = records.isEmpty(),
                stats = stats
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

    /** 检查用户是否已配置 UID 和服务器 */
    fun isUserInfoConfigured(): Boolean {
        return userUid.value.isNotBlank() && userServer.value.isNotBlank()
    }

    /**
     * 上传单条历史记录到排行榜。
     *
     * 上传流程：
     * 1. 未配置 UID/服务器 → 弹出配置提示（[configPromptRecord]）
     * 2. 通过"碧蓝航线助手"查询玩家昵称 → 成功则用该昵称上传
     * 3. 查询失败但已有保存的自定义昵称 → 用自定义昵称上传
     * 4. 查询失败且无自定义昵称 → 弹出昵称输入弹窗（[nicknamePromptRecord]）
     *
     * 上传状态通过 [uploadStatuses] 反馈，支持失败后调用 [retryUpload] 重试。
     */
    fun uploadRecord(record: GuessHistory) {
        viewModelScope.launch {
            val uid = settings.assistantDefaultUid.first()
            val server = settings.assistantDefaultServer.first()
            if (uid.isBlank() || server.isBlank()) {
                Log.w(TAG, "上传中止：未配置 UID/服务器")
                _configPromptRecord.value = record
                return@launch
            }

            _uploadStatuses.update { it + (record.id to RecordUploadStatus.Uploading) }

            // 1. 尝试通过助手查询玩家昵称
            val queriedNick = tryQueryNickname(uid, server)
            val nickname = if (queriedNick != null) {
                queriedNick
            } else {
                // 2. 查询失败 → 使用已保存的自定义昵称
                val savedNick = settings.leaderboardNickname.first()
                if (savedNick.isNotBlank()) {
                    Log.d(TAG, "使用已保存的自定义昵称上传: $savedNick")
                    savedNick
                } else {
                    // 3. 无自定义昵称 → 弹出昵称输入弹窗
                    Log.w(TAG, "无法查询玩家信息且无自定义昵称，弹出昵称输入弹窗")
                    _uploadStatuses.update { it - record.id }
                    _suggestedNickname.value = settings.userName.first().ifBlank { DEFAULT_NICKNAME }
                    _nicknamePromptRecord.value = record
                    return@launch
                }
            }

            performUpload(record, uid, server, nickname)
        }
    }

    /** 执行实际上传逻辑 */
    private suspend fun performUpload(record: GuessHistory, uid: String, server: String, nickname: String) {
        Log.i(TAG, "上传记录: id=${record.id}, nickname=$nickname, uid=$uid, server=$server")
        val category = LeaderboardCategory.fromModeAndDifficulty(record.mode, record.difficulty)
        val entry = LeaderboardEntry(
            uid = uid,
            server = server,
            nickname = nickname,
            score = record.totalScore,
            accuracy = record.accuracy,
            totalQuestions = record.totalQuestions,
            correctAnswers = record.correctAnswers,
            timestamp = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            formatVersion = LEADERBOARD_ENTRY_FORMAT_VERSION
        )

        val result = leaderboardRepository.uploadScore(entry, category)
        _uploadStatuses.update {
            it + (record.id to result.fold(
                onSuccess = {
                    Log.i(TAG, "上传成功: id=${record.id}")
                    RecordUploadStatus.Success
                },
                onFailure = { e ->
                    Log.e(TAG, "上传失败: id=${record.id}, reason=${e.message}")
                    RecordUploadStatus.Failed(e.message ?: "上传失败")
                }
            ))
        }
    }

    /**
     * 通过"碧蓝航线助手"查询玩家昵称。
     * @return 查询成功且昵称非空时返回昵称，查询失败或昵称为空时返回 null
     */
    private suspend fun tryQueryNickname(uid: String, server: String): String? {
        return try {
            val result = assistantRepository.fetchUserDetail(uid, server)
            result.fold(
                onSuccess = { data ->
                    val nick = data.user_info.nickname
                    if (nick.isNotBlank()) {
                        Log.d(TAG, "解析到玩家 ID: $nick")
                        nick
                    } else {
                        Log.w(TAG, "玩家 ID 为空")
                        null
                    }
                },
                onFailure = { e ->
                    Log.w(TAG, "查询玩家 ID 失败: ${e.message}")
                    null
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "查询玩家 ID 异常: ${e.message}")
            null
        }
    }

    /**
     * 使用用户输入的自定义昵称上传记录。
     * 保存昵称后立即上传，后续上传将自动复用该昵称。
     */
    fun uploadWithNickname(record: GuessHistory, nickname: String) {
        val trimmed = nickname.trim()
        if (trimmed.isBlank()) {
            Log.w(TAG, "昵称为空，取消上传")
            return
        }
        viewModelScope.launch {
            _nicknamePromptRecord.value = null
            settings.setLeaderboardNickname(trimmed)
            val uid = settings.assistantDefaultUid.first()
            val server = settings.assistantDefaultServer.first()
            _uploadStatuses.update { it + (record.id to RecordUploadStatus.Uploading) }
            performUpload(record, uid, server, trimmed)
        }
    }

    /** 关闭配置提示弹窗 */
    fun dismissConfigPrompt() {
        _configPromptRecord.value = null
    }

    /** 关闭昵称输入弹窗 */
    fun dismissNicknamePrompt() {
        _nicknamePromptRecord.value = null
    }

    /** 重试上传失败的记录 */
    fun retryUpload(record: GuessHistory) {
        uploadRecord(record)
    }

    /** 清除指定记录的上传状态 */
    fun clearUploadStatus(recordId: Long) {
        _uploadStatuses.update { it - recordId }
    }

    /**
     * 保存一局游戏记录（供 GuessShipViewModel 调用）
     * 包含 sessionId 用于防重复
     */
    fun saveGameRecord(
        mode: String,
        difficulty: String,
        score: GameScore,
        sessionId: String = UUID.randomUUID().toString()
    ) {
        viewModelScope.launch {
            // 预检查：防止重复录入
            val exists = historyDao.existsBySession(sessionId, mode)
            if (exists) return@launch

            val record = GuessHistory(
                mode = mode,
                difficulty = difficulty,
                totalQuestions = score.totalQuestions,
                correctAnswers = score.correctAnswers,
                totalScore = score.totalScore,
                hintsUsedTotal = score.hintsUsedTotal,
                skippedQuestions = score.skippedQuestions,
                totalPossibleScore = score.totalPossibleScore,
                timestamp = System.currentTimeMillis(),
                accuracy = score.accuracy,
                averageScore = score.averageScore,
                sessionId = sessionId
            )
            historyDao.insert(record)
        }
    }
}
