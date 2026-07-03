package com.azurlane.blyy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import com.azurlane.blyy.data.repository.AssistantRepository
import com.azurlane.blyy.ui.theme.UiStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsState(
    val uiStyle: UiStyle = UiStyle.COMMAND_CENTER,
    val forceDarkTheme: Boolean = false,
    val autoCheckUpdateEnabled: Boolean = true
)

/** 玩家信息验证状态：用于助手配置页判断是否需要显示自定义昵称输入框 */
sealed class PlayerVerifyState {
    /** 初始空闲 */
    object Idle : PlayerVerifyState()
    /** 验证中 */
    object Loading : PlayerVerifyState()
    /** 验证成功，[nickname] 为查询到的玩家昵称 */
    data class Success(val nickname: String) : PlayerVerifyState()
    /** 验证失败，[message] 为失败原因 */
    data class Failed(val message: String) : PlayerVerifyState()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: PlayerSettingsDataStore,
    private val assistantRepository: AssistantRepository
) : ViewModel() {

    val uiStyle: StateFlow<UiStyle> = settings.uiStyle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiStyle.COMMAND_CENTER)

    val forceDarkTheme: StateFlow<Boolean> = settings.forceDarkTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val dynamicColorEnabled: StateFlow<Boolean> = settings.dynamicColorEnabled
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
        )

    val autoCheckUpdateEnabled: StateFlow<Boolean> = settings.autoCheckUpdateEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // ── 小助手配置 ──

    /** 小助手默认 UID */
    val assistantDefaultUid: StateFlow<String> = settings.assistantDefaultUid
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    /** 小助手默认服务器 */
    val assistantDefaultServer: StateFlow<String> = settings.assistantDefaultServer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    /** 排行榜自定义昵称（无法通过 UID/服务器查询到玩家信息时使用） */
    val leaderboardNickname: StateFlow<String> = settings.leaderboardNickname
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    /** 玩家信息验证状态 */
    private val _playerVerifyState = MutableStateFlow<PlayerVerifyState>(PlayerVerifyState.Idle)
    val playerVerifyState: StateFlow<PlayerVerifyState> = _playerVerifyState.asStateFlow()

    fun setUiStyle(style: UiStyle) {
        viewModelScope.launch { settings.setUiStyle(style) }
    }

    fun setForceDarkTheme(force: Boolean) {
        viewModelScope.launch { settings.setForceDarkTheme(force) }
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setDynamicColorEnabled(enabled) }
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

    fun setLeaderboardNickname(nickname: String) {
        viewModelScope.launch { settings.setLeaderboardNickname(nickname) }
    }

    /** 重置验证状态（UID/服务器变更时调用） */
    fun resetVerifyState() {
        _playerVerifyState.value = PlayerVerifyState.Idle
    }

    /**
     * 验证当前 UID + 服务器能否查询到玩家信息。
     * - 成功：状态为 [PlayerVerifyState.Success]，配置页隐藏昵称输入框
     * - 失败：状态为 [PlayerVerifyState.Failed]，配置页显示昵称输入框
     */
    fun verifyPlayerInfo() {
        val uid = assistantDefaultUid.value.trim()
        val server = assistantDefaultServer.value.trim()
        if (uid.isBlank() || server.isBlank()) {
            _playerVerifyState.value = PlayerVerifyState.Failed("请先填写 UID 和服务器")
            return
        }
        viewModelScope.launch {
            _playerVerifyState.value = PlayerVerifyState.Loading
            val result = assistantRepository.fetchUserDetail(uid, server)
            result.fold(
                onSuccess = { data ->
                    val nick = data.user_info.nickname
                    if (nick.isNotBlank()) {
                        _playerVerifyState.value = PlayerVerifyState.Success(nick)
                    } else {
                        _playerVerifyState.value = PlayerVerifyState.Failed("查询到的玩家昵称为空")
                    }
                },
                onFailure = { e ->
                    _playerVerifyState.value = PlayerVerifyState.Failed(e.message ?: "无法查询到玩家信息")
                }
            )
        }
    }
}
