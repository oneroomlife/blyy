package com.example.blyy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.blyy.data.local.PlayerSettingsDataStore
import com.example.blyy.data.model.ShipGallery
import com.example.blyy.data.repository.ShipRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ShipGalleryState(
    val isLoading: Boolean = false,
    val gallery: ShipGallery? = null,
    val error: String? = null,
    val selectedFigureUrl: String? = null,
    val selectedFigureSkinName: String? = null
)

@HiltViewModel
class ShipGalleryViewModel @Inject constructor(
    private val shipRepository: ShipRepository,
    private val settingsDataStore: PlayerSettingsDataStore
) : ViewModel() {
    
    private val _state = MutableStateFlow(ShipGalleryState())
    val state: StateFlow<ShipGalleryState> = _state.asStateFlow()
    
    fun loadGallery(shipName: String) {
        viewModelScope.launch {
            _state.value = ShipGalleryState(isLoading = true)
            try {
                val gallery = shipRepository.fetchShipGallery(shipName)
                val savedFigureUrl = settingsDataStore.getSavedFigure(shipName).first()
                val savedFigureSkinName = gallery.figures.find { it.second == savedFigureUrl }?.first
                
                _state.value = ShipGalleryState(
                    isLoading = false,
                    gallery = gallery,
                    selectedFigureUrl = savedFigureUrl ?: gallery.figures.firstOrNull()?.second,
                    selectedFigureSkinName = savedFigureSkinName ?: gallery.figures.firstOrNull()?.first
                )
            } catch (e: Exception) {
                _state.value = ShipGalleryState(
                    isLoading = false,
                    error = e.message ?: "加载立绘失败"
                )
            }
        }
    }
    
    fun selectFigure(shipName: String, skinName: String, figureUrl: String) {
        viewModelScope.launch {
            settingsDataStore.saveFigure(shipName, figureUrl)
            _state.value = _state.value.copy(
                selectedFigureUrl = figureUrl,
                selectedFigureSkinName = skinName
            )
        }
    }
}
