package com.azurlane.blyy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.data.model.Ship
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
    val voices: List<com.azurlane.blyy.data.model.VoiceLine> = emptyList(),
    val isLoadingVoices: Boolean = false,
    val isFlipping: Boolean = false,
    val flipRevealedShip: Ship? = null,
    val autoPlayEnabled: Boolean = false,
    val autoPlayIntervalMinutes: Int = 5,
    /** 当前播放语音的台词文本（null 表示无台词显示） */
    val currentDialogue: String? = null,
    /** 台词弹窗开关（true=显示，false=隐藏） */
    val dialogueEnabled: Boolean = true,
    /** SD 小人皮肤名（"" / "default" 用默认皮肤，"gai" / "skin2" 等为其他皮肤） */
    val sdSkin: String = "",
    /** SD 小人显示缩放倍率（1.0 = 默认大小，0.5 = 半尺寸，1.5 = 放大 50%） */
    val sdScale: Float = 1.0f,
    /** 自定义 SD 资源 ID（空=跟随舰名匹配，非空=直接按 ID 加载该资源） */
    val sdResourceId: String = "",
    /** 悬浮窗触摸穿透（true=触摸事件穿透到下层应用，false=正常响应触摸交互） */
    val overlayTouchPassthrough: Boolean = false
)

sealed class SecretaryShipIntent {
    object SelectRandom : SecretaryShipIntent()
    data class SelectShip(val ship: Ship) : SecretaryShipIntent()
    object ClearSecretary : SecretaryShipIntent()
    object PlayRandomVoice : SecretaryShipIntent()
    data class SetAutoPlay(val enabled: Boolean, val intervalMinutes: Int) : SecretaryShipIntent()
    data class SetDialogueEnabled(val enabled: Boolean) : SecretaryShipIntent()
    data class SetSdSkin(val skin: String) : SecretaryShipIntent()
    data class SetSdScale(val scale: Float) : SecretaryShipIntent()
    data class SetSdResourceId(val resourceId: String) : SecretaryShipIntent()
    data class SetOverlayTouchPassthrough(val enabled: Boolean) : SecretaryShipIntent()
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
            autoPlayIntervalMinutes = managerState.autoPlayIntervalMinutes,
            currentDialogue = managerState.currentDialogue,
            dialogueEnabled = managerState.dialogueEnabled,
            sdSkin = managerState.sdSkin,
            sdScale = managerState.sdScale,
            sdResourceId = managerState.sdResourceId,
            overlayTouchPassthrough = managerState.overlayTouchPassthrough
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
            is SecretaryShipIntent.SetDialogueEnabled -> secretaryManager.setDialogueEnabled(intent.enabled)
            is SecretaryShipIntent.SetSdSkin -> secretaryManager.setSdSkin(intent.skin)
            is SecretaryShipIntent.SetSdScale -> secretaryManager.setSdScale(intent.scale)
            is SecretaryShipIntent.SetSdResourceId -> secretaryManager.setSdResourceId(intent.resourceId)
            is SecretaryShipIntent.SetOverlayTouchPassthrough -> secretaryManager.setOverlayTouchPassthrough(intent.enabled)
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
