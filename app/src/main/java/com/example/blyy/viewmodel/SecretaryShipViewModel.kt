package com.example.blyy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.blyy.data.model.Ship
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SecretaryShipState(
    val shipName: String = "",
    val figureUrl: String = "",
    val avatarUrl: String = "",
    val voices: List<com.example.blyy.data.model.VoiceLine> = emptyList(),
    val isLoadingVoices: Boolean = false,
    val isFlipping: Boolean = false,
    val flipRevealedShip: Ship? = null,
    val autoPlayEnabled: Boolean = false,
    val autoPlayIntervalMinutes: Int = 5
)

sealed class SecretaryShipIntent {
    object SelectRandom : SecretaryShipIntent()
    data class SelectShip(val ship: Ship) : SecretaryShipIntent()
    object ClearSecretary : SecretaryShipIntent()
    object PlayRandomVoice : SecretaryShipIntent()
    data class SetAutoPlay(val enabled: Boolean, val intervalMinutes: Int) : SecretaryShipIntent()
    object StartFlipAnimation : SecretaryShipIntent()
    object EndFlipAnimation : SecretaryShipIntent()
}

@HiltViewModel
class SecretaryShipViewModel @Inject constructor(
    private val secretaryManager: SecretaryManager
) : ViewModel() {

    private val _isFlipping = MutableStateFlow(false)
    private val _flipRevealedShip = MutableStateFlow<Ship?>(null)

    val state: StateFlow<SecretaryShipState> = combine(
        secretaryManager.state,
        _isFlipping,
        _flipRevealedShip
    ) { managerState, isFlipping, flipRevealedShip ->
        SecretaryShipState(
            shipName = managerState.shipName,
            figureUrl = managerState.figureUrl,
            avatarUrl = managerState.avatarUrl,
            voices = managerState.voices,
            isLoadingVoices = managerState.isLoadingVoices,
            isFlipping = isFlipping,
            flipRevealedShip = flipRevealedShip,
            autoPlayEnabled = managerState.autoPlayEnabled,
            autoPlayIntervalMinutes = managerState.autoPlayIntervalMinutes
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SecretaryShipState()
    )

    fun onIntent(intent: SecretaryShipIntent) {
        when (intent) {
            SecretaryShipIntent.SelectRandom -> selectRandom()
            is SecretaryShipIntent.SelectShip -> {
                viewModelScope.launch {
                    secretaryManager.selectShip(intent.ship, intent.ship.avatarUrl)
                }
            }
            SecretaryShipIntent.ClearSecretary -> secretaryManager.clearSecretary()
            SecretaryShipIntent.PlayRandomVoice -> secretaryManager.playRandomVoice()
            is SecretaryShipIntent.SetAutoPlay -> secretaryManager.setAutoPlay(intent.enabled, intent.intervalMinutes)
            SecretaryShipIntent.StartFlipAnimation -> _isFlipping.value = true
            SecretaryShipIntent.EndFlipAnimation -> _isFlipping.value = false
        }
    }

    private fun selectRandom() {
        viewModelScope.launch {
            val ship = secretaryManager.selectRandom()
            if (ship == null) {
                _isFlipping.value = false
                return@launch
            }
            _flipRevealedShip.value = ship
        }
    }

    fun confirmFlipAndClose() {
        _isFlipping.value = false
        _flipRevealedShip.value = null
    }

    fun ensureVoicesLoaded(shipName: String) {
        secretaryManager.ensureVoicesLoaded(shipName)
    }

    fun playRandomVoice() {
        secretaryManager.playRandomVoice()
    }
}
