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

data class HomeViewState(
    val favoriteShips: List<Ship> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

sealed class HomeIntent {
    data class ToggleFavorite(val ship: Ship) : HomeIntent()
    object Refresh : HomeIntent()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    getShipsUseCase: GetShipsUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val repository: ShipRepository
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    val state: StateFlow<HomeViewState> = combine(
        getShipsUseCase(ArchiveType.DOCK),
        _isLoading,
        _error
    ) { dockShips, isLoading, error ->
        val favorites = dockShips.filter { it.isFavorite }
        HomeViewState(
            favoriteShips = favorites,
            isLoading = isLoading,
            error = error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeViewState(isLoading = true)
    )

    fun onIntent(intent: HomeIntent) {
        when (intent) {
            is HomeIntent.ToggleFavorite -> {
                viewModelScope.launch {
                    toggleFavoriteUseCase(intent.ship.name, intent.ship.isFavorite)
                }
            }
            is HomeIntent.Refresh -> refreshData()
        }
    }

    private fun refreshData() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                repository.refreshShipsFromWiki(ArchiveType.DOCK)
            } catch (e: Exception) {
                _error.value = e.message ?: "同步失败"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
