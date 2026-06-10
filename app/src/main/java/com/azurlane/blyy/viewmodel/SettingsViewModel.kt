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
    val forceDarkTheme: Boolean = false,
    val autoCheckUpdateEnabled: Boolean = true
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: PlayerSettingsDataStore
) : ViewModel() {

    val uiStyle: StateFlow<UiStyle> = settings.uiStyle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiStyle.COMMAND_CENTER)

    val forceDarkTheme: StateFlow<Boolean> = settings.forceDarkTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val autoCheckUpdateEnabled: StateFlow<Boolean> = settings.autoCheckUpdateEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // ── 小助手配置 ──

    /** 小助手默认 UID */
    val assistantDefaultUid: StateFlow<String> = settings.assistantDefaultUid
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    /** 小助手默认服务器 */
    val assistantDefaultServer: StateFlow<String> = settings.assistantDefaultServer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun setUiStyle(style: UiStyle) {
        viewModelScope.launch { settings.setUiStyle(style) }
    }

    fun setForceDarkTheme(force: Boolean) {
        viewModelScope.launch { settings.setForceDarkTheme(force) }
    }

    fun setAutoCheckUpdateEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoCheckUpdateEnabled(enabled) }
    }

    fun setAssistantDefaultUid(uid: String) {
        viewModelScope.launch { settings.setAssistantDefaultUid(uid) }
    }

    fun setAssistantDefaultServer(server: String) {
        viewModelScope.launch { settings.setAssistantDefaultServer(server) }
    }
}
