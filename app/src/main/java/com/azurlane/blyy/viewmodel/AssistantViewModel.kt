package com.azurlane.blyy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import com.azurlane.blyy.data.local.ShipDao
import com.azurlane.blyy.data.model.BuildRecordData
import com.azurlane.blyy.data.model.UserDetailData
import com.azurlane.blyy.data.repository.AssistantRepository
import com.azurlane.blyy.domain.BuildFilterResult
import com.azurlane.blyy.domain.BuildFilterSelection
import com.azurlane.blyy.domain.BuildPoolFilter
import com.azurlane.blyy.domain.BuildRarityFilter
import com.azurlane.blyy.domain.filterBuildRecords
import com.azurlane.blyy.domain.normalizeShipName
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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
    val queryMode: QueryMode = QueryMode.USER_DETAIL,
    /** 建造查询——建造类型筛选 */
    val poolFilter: BuildPoolFilter = BuildPoolFilter.ALL,
    /** 建造查询——稀有度筛选 */
    val rarityFilter: BuildRarityFilter = BuildRarityFilter.ALL,
    /** 建造记录按当前筛选组合过滤后的结果 */
    val buildFilter: BuildFilterResult = BuildFilterResult(),
    /** 本地图鉴是否已收录舰船（空图鉴时稀有度筛选无法命中，UI 需给出提示） */
    val hasRarityIndex: Boolean = false
) {
    val buildSelection: BuildFilterSelection
        get() = BuildFilterSelection(poolFilter, rarityFilter)
}

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
    /** 应用建造记录「属性筛选」（建造类型 + 稀有度） */
    data class ApplyBuildFilter(
        val pool: BuildPoolFilter,
        val rarity: BuildRarityFilter
    ) : AssistantIntent()
    /** 重置建造记录筛选为「全部」 */
    object ResetBuildFilter : AssistantIntent()
    /** 清除错误信息 */
    object ClearError : AssistantIntent()
}

/**
 * 碧蓝航线小助手 ViewModel
 *
 * 采用 MVI Intent 模式，与项目现有 ViewModel 风格一致。
 * 自动从 DataStore 读取 UID/服务器配置，无需手动传入。
 *
 * 建造记录的稀有度不在接口返回中，这里订阅 ships 表（图鉴）建立
 * 舰名 → 稀有度 索引，筛选逻辑交给 [filterBuildRecords] 纯函数，
 * 保证 UI 只消费结果、可单测。
 */
@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val repository: AssistantRepository,
    private val settings: PlayerSettingsDataStore,
    private val shipDao: ShipDao
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    private val _userDetail = MutableStateFlow<UserDetailData?>(null)
    private val _buildRecords = MutableStateFlow<BuildRecordData?>(null)
    private val _error = MutableStateFlow<String?>(null)
    private val _queryMode = MutableStateFlow(QueryMode.USER_DETAIL)
    private val _poolFilter = MutableStateFlow(BuildPoolFilter.ALL)
    private val _rarityFilter = MutableStateFlow(BuildRarityFilter.ALL)

    /** 从 DataStore 读取的配置 */
    val defaultUid: StateFlow<String> = settings.assistantDefaultUid
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val defaultServer: StateFlow<String> = settings.assistantDefaultServer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    /** 图鉴稀有度索引：舰名 → 稀有度（图鉴未刷新时为空表） */
    private val rarityIndex: StateFlow<Map<String, String>> = shipDao
        .getShipsByArchiveType(ArchiveType.DOCK.name)
        .map { ships -> ships.associate { normalizeShipName(it.name) to it.rarity } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val state: StateFlow<AssistantState> = combine(
        combine(_isLoading, _userDetail) { loading, detail -> loading to detail },
        combine(_buildRecords, _error, _queryMode) { records, error, mode ->
            Triple(records, error, mode)
        },
        combine(_poolFilter, _rarityFilter, rarityIndex) { pool, rarity, index ->
            Triple(pool, rarity, index)
        }
    ) { (loading, detail), (records, error, mode), (pool, rarity, index) ->
        val selection = BuildFilterSelection(pool, rarity)
        AssistantState(
            isLoading = loading,
            userDetail = detail,
            buildRecords = records,
            error = error,
            queryMode = mode,
            poolFilter = pool,
            rarityFilter = rarity,
            buildFilter = filterBuildRecords(
                records = records?.buildRecords?.data.orEmpty(),
                selection = selection,
                rarityByName = index
            ),
            hasRarityIndex = index.isNotEmpty()
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
            is AssistantIntent.ApplyBuildFilter -> {
                _poolFilter.value = intent.pool
                _rarityFilter.value = intent.rarity
            }
            AssistantIntent.ResetBuildFilter -> {
                _poolFilter.value = BuildPoolFilter.ALL
                _rarityFilter.value = BuildRarityFilter.ALL
            }
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
