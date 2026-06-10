package com.azurlane.blyy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import com.azurlane.blyy.data.model.BuildRecordData
import com.azurlane.blyy.data.model.UserDetailData
import com.azurlane.blyy.data.repository.AssistantRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── 状态定义 ──

/** 小助手页面状态 */
data class AssistantState(
    val isLoading: Boolean = false,
    val userDetail: UserDetailData? = null,
    val buildRecords: BuildRecordData? = null,
    val error: String? = null,
    /** 当前查询模式：玩家详情 / 建造记录 */
    val queryMode: QueryMode = QueryMode.USER_DETAIL
)

enum class QueryMode {
    USER_DETAIL, BUILD_RECORD
}

// ── Intent 定义 ──

sealed class AssistantIntent {
    /** 查询玩家详情，对应 main.py 的 az <UID> <服务器> */
    data class QueryUserDetail(val uid: String, val server: String) : AssistantIntent()
    /** 查询建造记录，对应 main.py 的 azb <UID> <服务器> [数量] */
    data class QueryBuildRecord(val uid: String, val server: String, val count: Int = 10) : AssistantIntent()
    /** 切换查询模式 */
    data class SwitchMode(val mode: QueryMode) : AssistantIntent()
    /** 清除错误信息 */
    object ClearError : AssistantIntent()
}

/**
 * 碧蓝航线小助手 ViewModel
 *
 * 采用 MVI Intent 模式，与项目现有 ViewModel 风格一致。
 * 自动从 DataStore 读取 UID/服务器配置，无需手动传入。
 */
@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val repository: AssistantRepository,
    private val settings: PlayerSettingsDataStore
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    private val _userDetail = MutableStateFlow<UserDetailData?>(null)
    private val _buildRecords = MutableStateFlow<BuildRecordData?>(null)
    private val _error = MutableStateFlow<String?>(null)
    private val _queryMode = MutableStateFlow(QueryMode.USER_DETAIL)

    /** 从 DataStore 读取的配置 */
    val defaultUid: StateFlow<String> = settings.assistantDefaultUid
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val defaultServer: StateFlow<String> = settings.assistantDefaultServer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val state: StateFlow<AssistantState> = combine(
        combine(_isLoading, _userDetail) { loading, detail -> loading to detail },
        combine(_buildRecords, _error, _queryMode) { records, error, mode ->
            Triple(records, error, mode)
        }
    ) { (loading, detail), (records, error, mode) ->
        AssistantState(
            isLoading = loading,
            userDetail = detail,
            buildRecords = records,
            error = error,
            queryMode = mode
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AssistantState()
    )

    fun onIntent(intent: AssistantIntent) {
        when (intent) {
            is AssistantIntent.QueryUserDetail -> queryUserDetail(intent.uid, intent.server)
            is AssistantIntent.QueryBuildRecord -> queryBuildRecord(intent.uid, intent.server, intent.count)
            is AssistantIntent.SwitchMode -> _queryMode.value = intent.mode
            AssistantIntent.ClearError -> _error.value = null
        }
    }

    private fun queryUserDetail(uid: String, server: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _userDetail.value = null

            val result = repository.fetchUserDetail(uid, server)
            result.onSuccess { _userDetail.value = it }
                .onFailure { _error.value = it.message }
            _isLoading.value = false
        }
    }

    private fun queryBuildRecord(uid: String, server: String, count: Int) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _buildRecords.value = null

            val result = repository.fetchBuildRecords(uid, server, count)
            result.onSuccess { _buildRecords.value = it }
                .onFailure { _error.value = it.message }
            _isLoading.value = false
        }
    }
}
