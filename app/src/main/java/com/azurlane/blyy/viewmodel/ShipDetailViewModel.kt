package com.azurlane.blyy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.data.local.ShipDao
import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.data.model.ShipCharacterInfo
import com.azurlane.blyy.data.model.ShipDetail
import com.azurlane.blyy.data.repository.ShipDetailRepository
import com.azurlane.blyy.data.repository.ShipRepository
import com.azurlane.blyy.util.diagnostics.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 舰娘详情页状态。
 *
 * 三块数据分别来自不同层，加载策略也不同：
 * - [ship]：本地 Room（瞬时，缺失说明种子/名录还没建）
 * - [detail]：wiki（网络优先 + 本地缓存覆盖，可手动刷新）
 * - [profile]：角色档案（网络 + 缓存，失败静默为空）
 */
data class ShipDetailUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val shipName: String = "",
    val archiveType: String = "DOCK",
    val ship: Ship? = null,
    val detail: ShipDetail = ShipDetail(),
    val profile: ShipCharacterInfo = ShipCharacterInfo(shipName = ""),
    val error: String? = null
) {
    /** 详情字段为空（网络没抓到且无缓存）→ 页面显示"数据暂缺 → 去 Wiki" */
    val detailMissing: Boolean get() = detail.isEmpty
}

@HiltViewModel
class ShipDetailViewModel @Inject constructor(
    private val detailRepository: ShipDetailRepository,
    private val shipRepository: ShipRepository,
    private val shipDao: ShipDao
) : ViewModel() {

    private companion object {
        private const val TAG = "ShipDetailViewModel"
    }

    private val _uiState = MutableStateFlow(ShipDetailUiState())
    val uiState: StateFlow<ShipDetailUiState> = _uiState.asStateFlow()

    private var shipName: String = ""
    private var archiveType: String = "DOCK"

    /** 首次进入：先出本地资料（秒开），再补网络详情 */
    fun load(name: String, archive: String) {
        if (name.isBlank()) return
        if (shipName == name && archiveType == archive && !_uiState.value.isLoading) return
        shipName = name
        archiveType = archive
        _uiState.update { it.copy(isLoading = true, shipName = name, archiveType = archive, error = null) }

        viewModelScope.launch {
            val ship = runCatching { shipDao.getShipByName(name, archive) }.getOrNull()
            _uiState.update { it.copy(ship = ship) }
        }
        viewModelScope.launch {
            loadDetail(forceRefresh = false)
        }
        // 角色档案（性格/萌点…）与详情是两条独立链路：任一失败不影响另一个
        viewModelScope.launch {
            val profile = runCatching {
                shipRepository.fetchCharacterInfo(name, archive)
            }.getOrNull() ?: ShipCharacterInfo(shipName = name)
            _uiState.update { it.copy(profile = profile) }
        }
    }

    fun refresh() {
        if (shipName.isBlank() || _uiState.value.isRefreshing) return
        viewModelScope.launch { loadDetail(forceRefresh = true) }
    }

    private suspend fun loadDetail(forceRefresh: Boolean) {
        _uiState.update { it.copy(isRefreshing = forceRefresh) }
        val detail = runCatching {
            detailRepository.load(shipName, archiveType, forceRefresh)
        }.onFailure { AppLog.w(TAG, "loadDetail failed: ${it.message}") }
            .getOrDefault(ShipDetail())

        _uiState.update {
            it.copy(
                isLoading = false,
                isRefreshing = false,
                detail = detail,
                error = if (detail.isEmpty) "详情数据暂缺（wiki 解析失败或页面结构变更）" else null
            )
        }
    }
}
