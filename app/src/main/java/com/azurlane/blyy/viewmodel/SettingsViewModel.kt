package com.azurlane.blyy.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import com.azurlane.blyy.data.repository.AssistantRepository
import com.azurlane.blyy.ui.theme.UiStyle
import com.azurlane.blyy.util.AppIconManager
import com.azurlane.blyy.util.AppIconType
import com.azurlane.blyy.util.PinShortcutResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val assistantRepository: AssistantRepository,
    private val appIconManager: AppIconManager
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

    /** 沉浸式：是否隐藏状态栏，默认开启 */
    val hideStatusBar: StateFlow<Boolean> = settings.hideStatusBar
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

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

    fun setHideStatusBar(hide: Boolean) {
        viewModelScope.launch { settings.setHideStatusBar(hide) }
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

    // ── 自定义 app 快捷方式 ──

    /** 当前保存的图标类型（DEFAULT=默认，CUSTOM=已创建自定义快捷方式） */
    val appIconType: StateFlow<String> = settings.appIconType
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppIconType.DEFAULT.id)

    /** 自定义图标的内部存储路径 */
    val appCustomIconPath: StateFlow<String> = settings.appCustomIconPath
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    /** 图标选择时间戳 */
    val appIconSelectedAt: StateFlow<Long> = settings.appIconSelectedAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    /** 图标操作状态：Idle=空闲，Switching=处理中，Error=失败，Success=成功，NeedSettings=需去系统设置授权 */
    sealed class IconSwitchState {
        object Idle : IconSwitchState()
        object Switching : IconSwitchState()
        data class Error(val message: String) : IconSwitchState()
        data class Success(val message: String) : IconSwitchState()
        object NeedSettings : IconSwitchState()
    }

    private val _iconSwitching = MutableStateFlow<IconSwitchState>(IconSwitchState.Idle)
    val iconSwitching: StateFlow<IconSwitchState> = _iconSwitching.asStateFlow()

    /** 检查当前设备/启动器是否支持自定义快捷方式 */
    private val _isPinShortcutSupported = MutableStateFlow(false)
    val isPinShortcutSupported: StateFlow<Boolean> = _isPinShortcutSupported.asStateFlow()

    init {
        viewModelScope.launch {
            _isPinShortcutSupported.value = appIconManager.isPinShortcutSupported()
        }
    }

    /**
     * 应用自定义快捷方式 — 从相册图片创建桌面快捷方式
     *
     * 流程：
     * 1. 从用户选择的图片 URI 生成裁剪后的自适应图标（432×432 PNG）
     * 2. 通过 [AppIconManager.pinCustomShortcut] 创建/更新桌面快捷方式
     * 3. 根据 [PinShortcutResult] 提供精确反馈：
     *    - [PinShortcutResult.Updated]：静默更新成功
     *    - [PinShortcutResult.WaitingConfirmation]：系统已弹确认框，等待用户操作
     *    - [PinShortcutResult.NotSupported]：引导用户去系统设置授权
     *    - 其它：错误提示
     * 4. 成功时持久化选择 + 自定义路径到 DataStore
     *
     * @param sourceUri 用户从相册选择的图片 URI
     */
    fun applyCustomIcon(sourceUri: Uri) {
        _iconSwitching.value = IconSwitchState.Switching
        viewModelScope.launch {
            val iconPath = withContext(Dispatchers.IO) {
                appIconManager.generateCustomIcon(sourceUri)
            }
            if (iconPath == null) {
                _iconSwitching.value = IconSwitchState.Error("图片处理失败，请选择其它图片")
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                appIconManager.pinCustomShortcut(iconPath)
            }

            when (result) {
                is PinShortcutResult.Updated -> {
                    settings.setAppIcon(AppIconType.CUSTOM.id, iconPath)
                    _iconSwitching.value = IconSwitchState.Success("图标已更新，桌面快捷方式即将刷新")
                }
                is PinShortcutResult.NotSupported -> {
                    _iconSwitching.value = IconSwitchState.NeedSettings
                }
                is PinShortcutResult.IconNotFound -> {
                    _iconSwitching.value = IconSwitchState.Error("图标文件丢失，请重新选择图片")
                }
                is PinShortcutResult.Failed -> {
                    _iconSwitching.value = IconSwitchState.Error(result.message)
                }
            }
        }
    }

    /**
     * 应用自定义图标 — 从裁剪参数生成图标并创建桌面快捷方式
     *
     * 用户在 ImageCropperScreen 调整裁剪后调用此方法。
     * 流程：
     * 1. 用裁剪参数从原图截取区域 → 缩放到 432×432 PNG
     * 2. 创建/更新桌面快捷方式
     * 3. 成功后回调 [onApplied]（通常用于返回上一页）
     *
     * @param imageUri 原图 URI
     * @param scale 用户缩放倍数
     * @param offsetX/offsetY 用户偏移（px）
     * @param cropBoxSizePx 裁剪框边长（px）
     * @param onApplied 成功回调
     */
    fun applyCustomIconFromBitmap(
        imageUri: Uri,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        cropBoxSizePx: Float,
        onApplied: () -> Unit
    ) {
        _iconSwitching.value = IconSwitchState.Switching
        viewModelScope.launch {
            val iconPath = withContext(Dispatchers.IO) {
                appIconManager.generateCustomIconWithCrop(
                    sourceUri = imageUri,
                    scale = scale,
                    offsetX = offsetX,
                    offsetY = offsetY,
                    cropBoxSizePx = cropBoxSizePx
                )
            }
            if (iconPath == null) {
                _iconSwitching.value = IconSwitchState.Error("图片处理失败，请重试")
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                appIconManager.pinCustomShortcut(iconPath)
            }

            when (result) {
                is PinShortcutResult.Updated -> {
                    settings.setAppIcon(AppIconType.CUSTOM.id, iconPath)
                    _iconSwitching.value = IconSwitchState.Success("图标已创建/更新")
                    onApplied()
                }
                is PinShortcutResult.NotSupported -> {
                    _iconSwitching.value = IconSwitchState.NeedSettings
                }
                is PinShortcutResult.IconNotFound -> {
                    _iconSwitching.value = IconSwitchState.Error("图标文件丢失，请重试")
                }
                is PinShortcutResult.Failed -> {
                    _iconSwitching.value = IconSwitchState.Error(result.message)
                }
            }
        }
    }

    /** 打开系统应用详情页，引导用户开启快捷方式权限 */
    fun openShortcutSettings() {
        appIconManager.openAppShortcutSettings()
    }

    /** 清除图标操作状态（UI 显示成功/错误后调用） */
    fun clearIconSwitchingState() {
        _iconSwitching.value = IconSwitchState.Idle
    }
}
