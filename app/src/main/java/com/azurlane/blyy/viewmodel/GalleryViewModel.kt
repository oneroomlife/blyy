package com.azurlane.blyy.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.data.model.StudentFilterData
import com.azurlane.blyy.data.repository.ShipRepository
import com.azurlane.blyy.domain.GetShipsUseCase
import com.azurlane.blyy.domain.ToggleFavoriteUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 档案画廊视图状态
 *
 * 缓存加载机制说明：
 * - [isLoading]：首次加载（数据库无数据）时的加载状态，显示全屏 Loading
 * - [isRefreshing]：后台刷新（缓存过期或手动刷新）时的状态，显示顶部进度条
 */
data class GalleryViewState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val ships: List<Ship> = emptyList(),
    val searchQuery: String = "",
    val selectedFaction: String = "全部",
    val selectedType: String = "全部",
    val selectedRarity: String = "全部",
    val error: String? = null,
    val archiveType: ArchiveType = ArchiveType.DOCK,
    /** 缓存是否命中（用于 UI 显示"已使用缓存"提示） */
    val isCacheHit: Boolean = false,
    /** 上次缓存时间戳（毫秒），0 表示无缓存 */
    val cacheTimestamp: Long = 0L,
    /** 学生档案 14 维筛选选中项：key=FilterCategory.key, value=选中值（"全部"表示未筛选） */
    val studentFilters: Map<String, String> = emptyMap()
)

sealed class GalleryIntent {
    object Refresh : GalleryIntent()
    /** 强制刷新（忽略缓存过期检查） */
    object ForceRefresh : GalleryIntent()
    data class Search(val query: String) : GalleryIntent()
    data class FilterFaction(val faction: String) : GalleryIntent()
    data class FilterType(val type: String) : GalleryIntent()
    data class FilterRarity(val rarity: String) : GalleryIntent()
    data class ToggleFavorite(val ship: Ship) : GalleryIntent()
    data class SwitchArchive(val archiveType: ArchiveType) : GalleryIntent()
    /** 学生档案 14 维筛选：更新某个维度的选中值 */
    data class FilterStudent(val key: String, val value: String) : GalleryIntent()
    /** 重置所有学生筛选 */
    object ResetStudentFilters : GalleryIntent()
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class GalleryViewModel @Inject constructor(
    private val getShipsUseCase: GetShipsUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val repository: ShipRepository,
    private val settingsDataStore: PlayerSettingsDataStore
) : ViewModel() {

    companion object {
        private const val TAG = "GalleryViewModel"
    }

    private val _uiState = MutableStateFlow(GalleryViewState())
    val state: StateFlow<GalleryViewState> = _uiState.asStateFlow()

    /** 已预加载过的档案类型集合，避免重复预加载 */
    private val preloadedArchives = mutableSetOf<ArchiveType>()

    init {
        // 监听数据库数据，并结合 UI 层的筛选状态进行本地过滤
        // 这是多级缓存中的"持久化存储"层 — Room 数据库
        viewModelScope.launch {
            _uiState.flatMapLatest { state ->
                getShipsUseCase(state.archiveType)
            }.collect { allShips ->
                _uiState.update {
                    it.copy(
                        ships = allShips,
                        // 如果数据库已有数据，关闭首次加载状态
                        isLoading = it.isLoading && allShips.isEmpty()
                    )
                }
            }
        }

        // 初始化：先读取缓存时间戳，再决定是否需要网络刷新
        viewModelScope.launch {
            val archiveType = _uiState.value.archiveType
            val timestamp = settingsDataStore.getArchiveCacheTimestamp(archiveType.name)
            _uiState.update { it.copy(cacheTimestamp = timestamp) }

            val isExpired = settingsDataStore.isArchiveCacheExpired(archiveType.name)
            // 串行化：先完成当前档案刷新，再预加载另一档案，避免并发写入相互干扰
            val refreshJob = if (!isExpired) {
                // 缓存未过期：标记为缓存命中，仅后台静默刷新
                _uiState.update { it.copy(isCacheHit = true) }
                Log.d(TAG, "Cache HIT for $archiveType (age=${System.currentTimeMillis() - timestamp}ms), silent refresh in background")
                refreshData(force = false, silent = true)
            } else {
                // 缓存过期或无缓存：有缓存数据时静默刷新（立即显示缓存），无缓存时显示 Loading
                val hasCachedData = _uiState.value.ships.isNotEmpty()
                Log.d(TAG, "Cache MISS for $archiveType, hasCachedData=$hasCachedData, refreshing...")
                refreshData(force = false, silent = hasCachedData)
            }

            // 等待主档案刷新完成后再预加载另一档案，确保数据加载的原子性与独立性
            refreshJob.join()
            preloadOtherArchive(archiveType)
        }
    }

    // 衍生状态：根据当前 ViewState 中的各种条件过滤后的列表
    // 使用 debounce 避免每次按键都触发完整过滤，提升输入流畅度
    private val searchQueryFlow = _uiState
        .map { it.searchQuery }
        .distinctUntilChanged()
        .debounce(200) // 200ms 防抖，减少快速输入时的重复过滤

    val filteredShips = combine(_uiState, searchQueryFlow) { s, debouncedQuery ->
        // 使用防抖后的搜索词，其余筛选条件实时响应
        s.ships.filter { ship ->
            val matchSearch = ship.name.contains(debouncedQuery, ignoreCase = true)
            if (s.archiveType == ArchiveType.STUDENT) {
                // 学生档案：使用 14 维筛选
                matchSearch && matchStudentFilters(ship, s.studentFilters)
            } else {
                // 舰娘档案：使用传统 3 维筛选
                val matchFaction = matchFaction(ship, s.selectedFaction, s.archiveType)
                val matchType = matchShipType(ship.type, s.selectedType, s.archiveType)
                val matchRarity = s.selectedRarity == "全部" || ship.rarity == s.selectedRarity
                matchSearch && matchFaction && matchType && matchRarity
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 学生档案 14 维筛选匹配
     *
     * 遍历所有筛选维度，任一维度不匹配则返回 false。
     * "全部" 或未选中表示该维度不筛选。
     */
    private fun matchStudentFilters(ship: Ship, filters: Map<String, String>): Boolean {
        if (filters.isEmpty()) return true
        val filterData = StudentFilterData.fromJson(ship.filterData) ?: return true

        for ((key, selectedValue) in filters) {
            if (selectedValue == "全部") continue
            val fieldValue = filterData.getFieldValue(key)
            // 字段为空时跳过该维度（数据未解析到，不作为排除条件，避免全空导致结果为空）
            if (fieldValue.isEmpty()) continue
            if (!fieldValue.contains(selectedValue, ignoreCase = true)) return false
        }
        return true
    }

    /**
     * 阵营匹配：兼容历史数据。
     * - "余烬"：匹配 faction 含"余烬"，或 extra 含 "META" 标签的舰船（META-??? 归类到余烬）
     * - 其余阵营：faction 包含匹配
     */
    private fun matchFaction(ship: Ship, selectedFaction: String, archiveType: ArchiveType): Boolean {
        if (selectedFaction == "全部") return true
        if (archiveType == ArchiveType.STUDENT) {
            // 学生档案：faction 为学园名
            return ship.faction.contains(selectedFaction)
        }
        if (ship.faction.contains(selectedFaction)) return true
        // 余烬阵营兜底：META 舰船（extra 含 META 标签）归入余烬
        if (selectedFaction == "META" && ship.extra.contains("META", ignoreCase = true)) return true
        return false
    }

    private fun matchShipType(shipType: String, selectedType: String, archiveType: ArchiveType): Boolean {
        if (selectedType == "全部") return true

        if (archiveType == ArchiveType.STUDENT) {
            // 学生档案：type 为角色定位（攻击类型等）
            return shipType.contains(selectedType)
        }

        return when (selectedType) {
            // type 字段为组合字符串（如"驱逐·前排先锋"），用 contains 匹配位置标签
            "前排先锋" -> shipType.contains("前排先锋")
            "后排主力" -> shipType.contains("后排主力")
            else -> shipType.contains(selectedType)
        }
    }

    fun onIntent(intent: GalleryIntent) {
        when (intent) {
            is GalleryIntent.Refresh -> refreshData(force = false, silent = true)
            is GalleryIntent.ForceRefresh -> refreshData(force = true, silent = false)
            is GalleryIntent.Search -> _uiState.update { it.copy(searchQuery = intent.query) }
            is GalleryIntent.FilterFaction -> _uiState.update { it.copy(selectedFaction = intent.faction) }
            is GalleryIntent.FilterType -> _uiState.update { it.copy(selectedType = intent.type) }
            is GalleryIntent.FilterRarity -> _uiState.update { it.copy(selectedRarity = intent.rarity) }
            is GalleryIntent.ToggleFavorite -> viewModelScope.launch {
                toggleFavoriteUseCase(intent.ship.name, intent.ship.archiveType, intent.ship.isFavorite)
            }
            is GalleryIntent.FilterStudent -> _uiState.update {
                it.copy(studentFilters = it.studentFilters.toMutableMap().apply {
                    if (intent.value == "全部") {
                        remove(intent.key)
                    } else {
                        put(intent.key, intent.value)
                    }
                })
            }
            is GalleryIntent.ResetStudentFilters -> _uiState.update {
                it.copy(studentFilters = emptyMap())
            }
            is GalleryIntent.SwitchArchive -> {
                val newType = intent.archiveType
                _uiState.update {
                    it.copy(
                        archiveType = newType,
                        selectedFaction = "全部",
                        selectedType = "全部",
                        selectedRarity = "全部",
                        searchQuery = "",
                        error = null,
                        studentFilters = emptyMap()
                    )
                }
                // 切换档案后检查缓存
                viewModelScope.launch {
                    val timestamp = settingsDataStore.getArchiveCacheTimestamp(newType.name)
                    val isExpired = settingsDataStore.isArchiveCacheExpired(newType.name)
                    _uiState.update { it.copy(cacheTimestamp = timestamp, isCacheHit = !isExpired) }

                    if (isExpired) {
                        // 缓存过期：正常刷新
                        refreshData(force = false, silent = false)
                    } else {
                        // 缓存有效：后台静默刷新
                        refreshData(force = false, silent = true)
                    }
                }
            }
        }
    }

    /**
     * 刷新档案数据
     *
     * 多级缓存策略：
     * 1. 内存缓存：StateFlow 中的 ships 列表（由 Room Flow 自动维护）
     * 2. 持久化缓存：Room 数据库 + DataStore 时间戳
     * 3. 网络刷新：缓存过期时从网络拉取最新数据
     *
     * @param force 是否强制刷新（忽略缓存过期检查）
     * @param silent 是否静默刷新（不显示全屏 Loading，仅显示顶部进度条）
     */
    private fun refreshData(force: Boolean = false, silent: Boolean = false): Job {
        return viewModelScope.launch {
            val currentArchive = _uiState.value.archiveType

            // 非强制刷新时，先检查缓存是否过期
            if (!force) {
                val isExpired = settingsDataStore.isArchiveCacheExpired(currentArchive.name)
                if (!isExpired) {
                    // 缓存未过期：跳过网络刷新，仅更新时间戳显示
                    val timestamp = settingsDataStore.getArchiveCacheTimestamp(currentArchive.name)
                    _uiState.update {
                        it.copy(
                            isCacheHit = true,
                            cacheTimestamp = timestamp,
                            isLoading = false,
                            isRefreshing = false
                        )
                    }
                    Log.d(TAG, "Skip refresh for $currentArchive, cache valid")
                    return@launch
                }
            }

            // 设置加载状态
            _uiState.update {
                it.copy(
                    isLoading = if (silent) it.isLoading else true,
                    isRefreshing = true,
                    error = null
                )
            }

            try {
                when (currentArchive) {
                    ArchiveType.DOCK -> repository.refreshShipsFromWiki()
                    ArchiveType.STUDENT -> {
                        val students = repository.refreshStudentsFromGamekee()
                        // 后台启动筛选数据补充，不阻塞 UI，viewModelScope 自动管理生命周期
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                repository.enrichStudentFilterData(students)
                            } catch (e: Exception) {
                                Log.w(TAG, "Enrichment failed (non-critical): ${e.message}")
                            }
                        }
                    }
                }
                // 刷新成功：更新缓存时间戳
                settingsDataStore.setArchiveCacheTimestamp(currentArchive.name)
                val newTimestamp = settingsDataStore.getArchiveCacheTimestamp(currentArchive.name)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isCacheHit = false,
                        cacheTimestamp = newTimestamp,
                        error = null
                    )
                }
                Log.d(TAG, "Refresh success for $currentArchive, cache updated")
            } catch (e: Exception) {
                val hasCachedData = _uiState.value.ships.isNotEmpty()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        // 始终保存错误信息；UI 根据 ships 是否为空决定显示全屏错误还是非侵入式提示
                        error = e.message ?: "同步失败，请检查网络后重试"
                    )
                }
                Log.e(TAG, "Refresh failed for $currentArchive, hasCachedData=$hasCachedData, error=${e.message}", e)
            }
        }
    }

    /**
     * 预加载另一档案类型的数据
     *
     * 在当前档案加载完成后，后台预加载另一档案类型的数据到数据库，
     * 这样用户切换档案时可以立即显示数据，无需等待网络加载。
     */
    private suspend fun preloadOtherArchive(currentType: ArchiveType) {
        val otherType = when (currentType) {
            ArchiveType.DOCK -> ArchiveType.STUDENT
            ArchiveType.STUDENT -> ArchiveType.DOCK
        }

        // 避免重复预加载
        if (otherType in preloadedArchives) return
        preloadedArchives.add(otherType)

        try {
            val isExpired = settingsDataStore.isArchiveCacheExpired(otherType.name)
            if (isExpired) {
                Log.d(TAG, "Preloading $otherType in background")
                when (otherType) {
                    ArchiveType.DOCK -> repository.refreshShipsFromWiki()
                    ArchiveType.STUDENT -> repository.refreshStudentsFromGamekee()
                }
                settingsDataStore.setArchiveCacheTimestamp(otherType.name)
                Log.d(TAG, "Preload success for $otherType")
            } else {
                Log.d(TAG, "Skip preload for $otherType, cache valid")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Preload failed for $otherType: ${e.message}")
            // 预加载失败不影响主流程，下次切换时会正常加载
            preloadedArchives.remove(otherType)
        }
    }
}
