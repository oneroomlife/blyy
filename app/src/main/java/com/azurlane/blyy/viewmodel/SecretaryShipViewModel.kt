package com.azurlane.blyy.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.util.SdResourceLink
import com.azurlane.blyy.util.SdResourceLinkProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val secretaryManager: SecretaryManager,
    private val sdResourceLinkProvider: SdResourceLinkProvider
) : ViewModel() {

    companion object {
        private const val TAG = "SecretaryShipViewModel"
    }

    private val _isFlipping = MutableStateFlow(false)
    private val _flipRevealedShip = MutableStateFlow<Ship?>(null)

    // ── SD 资源下载链接状态（远程获取）──

    /** SD 资源下载链接（在线获取，来自仓库 network_drive_links.json 的 sd_* 字段） */
    private val _sdResourceLink = MutableStateFlow<SdResourceLink?>(null)
    val sdResourceLink: StateFlow<SdResourceLink?> = _sdResourceLink.asStateFlow()

    /** SD 资源链接加载中标记，供 UI 显示 loading 状态 */
    private val _isRefreshingSdLink = MutableStateFlow(false)
    val isRefreshingSdLink: StateFlow<Boolean> = _isRefreshingSdLink.asStateFlow()

    init {
        // ViewModel 创建时（App 主界面组合阶段）自动获取一次 SD 资源链接
        // （使用竞速策略，优先返回缓存/最快的源，不影响启动性能）
        loadSdResourceLink(forceRefresh = false)
    }

    /**
     * 加载 SD 资源下载链接。
     *
     * 无论初始加载还是强制刷新都设置 [_isRefreshingSdLink]，确保 UI 在加载期间
     * 显示 loading 指示器而非误显示"获取失败"。
     *
     * @param forceRefresh true 时强制刷新（跳过内存缓存，使用主源优先策略，确保拿到最新链接）；
     *                     false 时使用启动竞速策略（速度优先，可使用缓存）
     */
    private fun loadSdResourceLink(forceRefresh: Boolean) {
        viewModelScope.launch {
            _isRefreshingSdLink.value = true
            try {
                val link = sdResourceLinkProvider.getLink(forceRefresh = forceRefresh)
                if (link != null) {
                    _sdResourceLink.value = link
                    Log.i(TAG, "SD resource link loaded: ${link.label}, url=${link.url}")
                } else {
                    Log.w(TAG, "Failed to load SD resource link (all sources failed)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load SD resource link", e)
            } finally {
                _isRefreshingSdLink.value = false
            }
        }
    }

    /**
     * 确保 SD 资源下载链接已加载（供页面进入时调用）。
     *
     * 仅在链接尚未加载成功且当前不在加载中时触发（缓存友好的智能重试）：
     * - 启动阶段获取失败（网络异常/仓库文件缺失）时，进入页面会自动重试
     * - 已加载成功或正在加载中则跳过，避免重复请求
     */
    fun ensureSdResourceLinkLoaded() {
        if (_sdResourceLink.value != null || _isRefreshingSdLink.value) return
        Log.d(TAG, "ensureSdResourceLinkLoaded: link is null, retrying fetch on page entry")
        loadSdResourceLink(forceRefresh = false)
    }

    /**
     * 强制刷新 SD 资源下载链接（供 UI"刷新"按钮调用）。
     *
     * 跳过内存缓存，使用主源优先策略（Gitee → GitHub raw → jsDelivr），
     * 确保拿到仓库中最新的链接，而非启动时缓存的旧链接。
     */
    fun refreshSdResourceLink() {
        loadSdResourceLink(forceRefresh = true)
    }

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
