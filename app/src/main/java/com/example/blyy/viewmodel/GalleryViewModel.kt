package com.example.blyy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.blyy.data.model.Ship
import com.example.blyy.data.repository.ShipRepository
import com.example.blyy.domain.GetShipsUseCase
import com.example.blyy.domain.ToggleFavoriteUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GalleryViewState(
    val isLoading: Boolean = false,
    val ships: List<Ship> = emptyList(),
    val searchQuery: String = "",
    val selectedFaction: String = "全部",
    val selectedType: String = "全部",
    val selectedRarity: String = "全部",
    val error: String? = null
)

sealed class GalleryIntent {
    object Refresh : GalleryIntent()
    data class Search(val query: String) : GalleryIntent()
    data class FilterFaction(val faction: String) : GalleryIntent()
    data class FilterType(val type: String) : GalleryIntent()
    data class FilterRarity(val rarity: String) : GalleryIntent()
    data class ToggleFavorite(val ship: Ship) : GalleryIntent()
}

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val getShipsUseCase: GetShipsUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val repository: ShipRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(GalleryViewState())
    val state: StateFlow<GalleryViewState> = _uiState.asStateFlow()

    init {
        // 监听数据库数据，并结合 UI 层的筛选状态进行本地过滤
        viewModelScope.launch {
            getShipsUseCase().collect { allShips ->
                _uiState.update { it.copy(ships = allShips) }
            }
        }
        onIntent(GalleryIntent.Refresh)
    }

    // 衍生状态：根据当前 ViewState 中的各种条件过滤后的列表
    val filteredShips = _uiState.map { s ->
        s.ships.filter { ship ->
            val matchSearch = ship.name.contains(s.searchQuery, ignoreCase = true)
            val matchFaction = s.selectedFaction == "全部" || ship.faction.contains(s.selectedFaction)
            val matchType = s.selectedType == "全部" || ship.type.contains(s.selectedType)
            val matchRarity = s.selectedRarity == "全部" || ship.rarity == s.selectedRarity
            matchSearch && matchFaction && matchType && matchRarity
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun onIntent(intent: GalleryIntent) {
        when (intent) {
            is GalleryIntent.Refresh -> refreshData()
            is GalleryIntent.Search -> _uiState.update { it.copy(searchQuery = intent.query) }
            is GalleryIntent.FilterFaction -> _uiState.update { it.copy(selectedFaction = intent.faction) }
            is GalleryIntent.FilterType -> _uiState.update { it.copy(selectedType = intent.type) }
            is GalleryIntent.FilterRarity -> _uiState.update { it.copy(selectedRarity = intent.rarity) }
            is GalleryIntent.ToggleFavorite -> viewModelScope.launch {
                toggleFavoriteUseCase(intent.ship.name, intent.ship.isFavorite)
            }
        }
    }

    private fun refreshData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                repository.refreshShipsFromWiki()
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "同步失败") }
            }
        }
    }
}