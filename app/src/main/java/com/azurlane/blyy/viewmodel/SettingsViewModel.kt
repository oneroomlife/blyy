package com.azurlane.blyy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import com.azurlane.blyy.ui.theme.UiStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsState(
    val uiStyle: UiStyle = UiStyle.COMMAND_CENTER,
    val forceDarkTheme: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: PlayerSettingsDataStore
) : ViewModel() {

    val uiStyle: StateFlow<UiStyle> = settings.uiStyle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiStyle.COMMAND_CENTER)

    val forceDarkTheme: StateFlow<Boolean> = settings.forceDarkTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setUiStyle(style: UiStyle) {
        viewModelScope.launch { settings.setUiStyle(style) }
    }

    fun setForceDarkTheme(force: Boolean) {
        viewModelScope.launch { settings.setForceDarkTheme(force) }
    }
}
