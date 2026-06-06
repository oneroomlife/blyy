package com.azurlane.blyy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class Live2DViewModel @Inject constructor(
    private val settings: PlayerSettingsDataStore
) : ViewModel() {

    val sslTrusted: StateFlow<Boolean> = settings.live2dSslTrusted
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setSslTrusted(trusted: Boolean) {
        viewModelScope.launch { settings.setLive2dSslTrusted(trusted) }
    }
}
