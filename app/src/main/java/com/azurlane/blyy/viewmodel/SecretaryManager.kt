package com.azurlane.blyy.viewmodel

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import com.azurlane.blyy.data.local.ShipDao
import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.data.model.VoiceLanguage
import com.azurlane.blyy.data.model.VoiceLine
import com.azurlane.blyy.domain.GetVoicesUseCase
import com.azurlane.blyy.domain.SelectSecretaryUseCase
import com.azurlane.blyy.service.PlaybackServiceConnection
import com.azurlane.blyy.util.PinyinHelper
import com.azurlane.blyy.util.SDResourceManager
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

data class SecretaryManagerState(
    val shipName: String = "",
    val figureUrl: String = "",
    val avatarUrl: String = "",
    val voices: List<VoiceLine> = emptyList(),
    val isLoadingVoices: Boolean = false,
    val autoPlayEnabled: Boolean = false,
    val autoPlayIntervalMinutes: Int = 5,
    val voiceLanguage: VoiceLanguage = VoiceLanguage.CN,
    /** 当前播放语音的台词文本（null 表示无台词显示） */
    val currentDialogue: String? = null,
    /** 台词弹窗开关（true=显示，false=隐藏） */
    val dialogueEnabled: Boolean = true,
    /** SD 小人皮肤名（"" / "default" 用默认皮肤，"gai" / "skin2" 等为其他皮肤） */
    val sdSkin: String = "",
    /** SD 小人显示缩放倍率（1.0 = 默认大小，0.5 = 半尺寸，1.5 = 放大 50%） */
    val sdScale: Float = 1.0f,
    /** 自定义 SD 资源 ID（空=跟随舰名匹配，非空=直接按 ID 加载该资源，解除舰名限制） */
    val sdResourceId: String = "",
    /** 悬浮窗触摸穿透（true=触摸事件穿透到下层应用，false=正常响应触摸交互） */
    val overlayTouchPassthrough: Boolean = false
)

@Singleton
class SecretaryManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settingsDataStore: PlayerSettingsDataStore,
    private val selectSecretaryUseCase: SelectSecretaryUseCase,
    private val getVoicesUseCase: GetVoicesUseCase,
    private val playbackServiceConnection: PlaybackServiceConnection,
    private val shipDao: ShipDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _shipName = MutableStateFlow("")
    private val _figureUrl = MutableStateFlow("")
    private val _avatarUrl = MutableStateFlow("")
    private val _voices = MutableStateFlow<List<VoiceLine>>(emptyList())
    private val _isLoadingVoices = MutableStateFlow(false)
    private val _voiceLanguage = MutableStateFlow(VoiceLanguage.CN)
    private val _currentDialogue = MutableStateFlow<String?>(null)
    private val _sdSkin = MutableStateFlow("")
    private val _sdScale = MutableStateFlow(1.0f)
    private val _sdResourceId = MutableStateFlow("")
    private var autoPlayJob: Job? = null
    private var dialogueClearJob: Job? = null
    
    companion object {
        private const val TAG = "SecretaryManager"
    }

    val state: StateFlow<SecretaryManagerState> = combine(
        _shipName,
        _figureUrl,
        _avatarUrl,
        _voices,
        _isLoadingVoices,
        _voiceLanguage,
        settingsDataStore.secretaryAutoPlayEnabled,
        settingsDataStore.secretaryAutoPlayIntervalMinutes,
        _currentDialogue,
        settingsDataStore.secretaryDialogueEnabled,
        _sdSkin,
        _sdScale,
        _sdResourceId,
        settingsDataStore.secretaryOverlayTouchPassthrough
    ) { args ->
        SecretaryManagerState(
            shipName = args[0] as String,
            figureUrl = args[1] as String,
            avatarUrl = args[2] as String,
            voices = @Suppress("UNCHECKED_CAST") (args[3] as List<VoiceLine>),
            isLoadingVoices = args[4] as Boolean,
            voiceLanguage = args[5] as VoiceLanguage,
            autoPlayEnabled = args[6] as Boolean,
            autoPlayIntervalMinutes = args[7] as Int,
            currentDialogue = args[8] as String?,
            dialogueEnabled = args[9] as Boolean,
            sdSkin = args[10] as String,
            sdScale = args[11] as Float,
            sdResourceId = args[12] as String,
            overlayTouchPassthrough = args[13] as Boolean
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = SecretaryManagerState()
    )

    init {
        scope.launch {
            settingsDataStore.secretaryShipName.collect { _shipName.value = it }
        }
        scope.launch {
            settingsDataStore.secretaryFigureUrl.collect { _figureUrl.value = it }
        }
        scope.launch {
            settingsDataStore.secretaryAvatarUrl.collect { _avatarUrl.value = it }
        }
        scope.launch {
            settingsDataStore.secretaryAutoPlayEnabled.collect { enabled ->
                if (enabled && _shipName.value.isNotEmpty()) {
                    startAutoPlay()
                } else {
                    stopAutoPlay()
                }
            }
        }
        scope.launch {
            settingsDataStore.voiceLanguage.collect { lang ->
                _voiceLanguage.value = lang
            }
        }
        scope.launch {
            settingsDataStore.secretaryDialogueEnabled.collect { enabled ->
                // 开关关闭时立即清除残留台词
                if (!enabled) {
                    dialogueClearJob?.cancel()
                    _currentDialogue.value = null
                }
            }
        }
        scope.launch {
            settingsDataStore.secretarySdSkin.collect { _sdSkin.value = it }
        }
        scope.launch {
            settingsDataStore.secretarySdScale.collect { _sdScale.value = it }
        }
        scope.launch {
            settingsDataStore.secretarySdResourceId.collect { _sdResourceId.value = it }
        }
    }

    suspend fun selectRandom(): Ship? {
        val startTime = System.currentTimeMillis()
        val ship = selectSecretaryUseCase.selectRandomSecretary() ?: return null
        loadFigureAndSave(ship)
        Log.d(TAG, "selectRandom: 耗时 ${System.currentTimeMillis() - startTime}ms")
        return ship
    }

    suspend fun selectShip(ship: Ship, figureUrl: String) {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "selectShip: 开始选择 ${ship.name}")
        
        try {
            // 立即更新舰娘名称，让用户知道正在加载
            _shipName.value = ship.name
            _avatarUrl.value = ship.avatarUrl
            
            // 从 Wiki 获取立绘 URL
            _isLoadingVoices.value = true
            val (voices, defaultFigure, skinFigureMap) = getVoicesUseCase(ship.name)
            
            // 优先使用皮肤立绘，其次使用默认立绘，最后使用头像作为降级
            val finalFigureUrl = skinFigureMap.values.firstOrNull()
                ?: defaultFigure
                ?: ship.avatarUrl
            
            _figureUrl.value = finalFigureUrl
            _voices.value = voices
            _isLoadingVoices.value = false
            
            // 异步保存到持久化存储（NonCancellable：即使 scope 被取消也需完成写入，避免状态丢失）
            scope.launch {
                withContext(NonCancellable) {
                    settingsDataStore.saveSecretaryShip(ship.name, finalFigureUrl, ship.avatarUrl)
                }
            }

            if (state.value.autoPlayEnabled) startAutoPlay()

            Log.d(TAG, "selectShip: 完成，立绘URL=$finalFigureUrl，耗时 ${System.currentTimeMillis() - startTime}ms")
        } catch (e: Exception) {
            Log.e(TAG, "selectShip: 选择失败，使用头像降级", e)
            // 优雅降级：使用头像作为立绘
            _figureUrl.value = ship.avatarUrl
            _voices.value = emptyList()
            _isLoadingVoices.value = false

            scope.launch {
                withContext(NonCancellable) {
                    settingsDataStore.saveSecretaryShip(ship.name, ship.avatarUrl, ship.avatarUrl)
                }
            }
        }
    }

    private suspend fun loadFigureAndSave(ship: Ship) {
        val startTime = System.currentTimeMillis()
        _isLoadingVoices.value = true
        
        try {
            val (voices, defaultFigure, skinFigureMap) = getVoicesUseCase(ship.name)
            val savedFigure = settingsDataStore.getSavedFigure(ship.name).first()
            val figureUrl = savedFigure
                ?: skinFigureMap.values.firstOrNull()
                ?: defaultFigure
            
            // 立即更新UI
            _shipName.value = ship.name
            _figureUrl.value = figureUrl
            _avatarUrl.value = ship.avatarUrl
            _voices.value = voices

            // 异步保存（NonCancellable：确保写入完成）
            scope.launch {
                withContext(NonCancellable) {
                    settingsDataStore.saveSecretaryShip(ship.name, figureUrl, ship.avatarUrl)
                }
            }

            Log.d(TAG, "loadFigureAndSave: 成功，耗时 ${System.currentTimeMillis() - startTime}ms")
        } catch (e: Exception) {
            Log.e(TAG, "loadFigureAndSave: 加载失败，使用头像降级", e)
            // 优雅降级
            _shipName.value = ship.name
            _figureUrl.value = ship.avatarUrl
            _avatarUrl.value = ship.avatarUrl
            _voices.value = emptyList()

            scope.launch {
                withContext(NonCancellable) {
                    settingsDataStore.saveSecretaryShip(ship.name, ship.avatarUrl, ship.avatarUrl)
                }
            }
        } finally {
            _isLoadingVoices.value = false
            if (state.value.autoPlayEnabled) startAutoPlay()
        }
    }

    fun clearSecretary() {
        Log.d(TAG, "clearSecretary: 清除秘书舰")
        // 立即更新内存状态（UI 即时响应，不依赖协程）
        _shipName.value = ""
        _figureUrl.value = ""
        _avatarUrl.value = ""
        _voices.value = emptyList()
        stopAutoPlay()
        // 持久化清理（NonCancellable：即使 scope 被取消也需完成，避免重启后残留旧数据）
        scope.launch {
            withContext(NonCancellable) {
                settingsDataStore.clearSecretaryShip()
            }
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    fun playRandomVoice() {
        val voices = _voices.value
        if (voices.isEmpty()) {
            if (_shipName.value.isNotEmpty()) loadVoicesInBackground(_shipName.value)
            return
        }
        val randomVoice = voices.random()
        // 更新台词弹窗内容，8 秒后自动清除（仅在开关开启时显示）
        val cleanDialogue = randomVoice.dialogue.replace("\n", " ").replace("\r", "").trim()
        if (state.value.dialogueEnabled) {
            _currentDialogue.value = cleanDialogue.ifEmpty { null }
            dialogueClearJob?.cancel()
            dialogueClearJob = scope.launch {
                delay(8000)
                _currentDialogue.value = null
            }
        }
        onController { player ->
            val mediaItem = createMediaItem(randomVoice)
            player.setMediaItem(mediaItem)
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.prepare()
            player.play()
            Log.d(TAG, "playRandomVoice: 播放语音 ${randomVoice.scene}, dialogue=$cleanDialogue")
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun createMediaItem(voice: VoiceLine): MediaItem {
        val activeAudioUrl = voice.getActiveAudioUrl(_voiceLanguage.value)
        val cleanDialogue = voice.dialogue.replace("\n", " ").replace("\r", "").trim()
        val metadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(cleanDialogue)
            .setArtist("${_shipName.value} · ${voice.skinName}")
            .setAlbumTitle(voice.scene)
            .setArtworkUri(_avatarUrl.value.let { if (it.isNotEmpty()) it.toUri() else null })
            .build()
        return MediaItem.Builder()
            .setMediaId(activeAudioUrl)
            .setUri(activeAudioUrl)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun onController(command: (Player) -> Unit) {
        playbackServiceConnection.mediaController.addListener({
            playbackServiceConnection.mediaController.get()?.let { command(it) }
        }, MoreExecutors.directExecutor())
    }

    fun ensureVoicesLoaded(shipName: String) {
        // 舰名为空时不加载语音（自定义非舰娘资源无语音，避免错误触发网络请求）
        if (shipName.isBlank()) return
        if (_shipName.value == shipName && _voices.value.isEmpty() && !_isLoadingVoices.value) {
            loadVoicesInBackground(shipName)
        }
    }

    private fun loadVoicesInBackground(shipName: String) {
        // 舰名为空时不加载语音（自定义非舰娘资源无对应舰娘，不应触发语音加载）
        if (shipName.isBlank()) {
            _voices.value = emptyList()
            return
        }
        scope.launch {
            _isLoadingVoices.value = true
            try {
                val (voices, _, _) = getVoicesUseCase(shipName)
                _voices.value = voices
                Log.d(TAG, "loadVoicesInBackground: 加载了 ${voices.size} 条语音")
            } catch (e: Exception) {
                Log.e(TAG, "loadVoicesInBackground: 加载失败", e)
                _voices.value = emptyList()
            } finally {
                _isLoadingVoices.value = false
            }
        }
    }

    fun setAutoPlay(enabled: Boolean, intervalMinutes: Int) {
        scope.launch {
            withContext(NonCancellable) {
                settingsDataStore.setSecretaryAutoPlay(enabled, intervalMinutes)
            }
            if (enabled && _shipName.value.isNotEmpty()) startAutoPlay()
            else stopAutoPlay()
        }
    }

    fun setVoiceLanguage(language: VoiceLanguage) {
        scope.launch {
            withContext(NonCancellable) {
                settingsDataStore.setVoiceLanguage(language)
            }
        }
    }

    fun setDialogueEnabled(enabled: Boolean) {
        scope.launch {
            withContext(NonCancellable) {
                settingsDataStore.setSecretaryDialogueEnabled(enabled)
            }
        }
    }

    /**
     * 设置悬浮窗触摸穿透开关。
     *
     * 触摸穿透开启后，悬浮窗主窗口添加 [android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE]，
     * 所有触摸事件直接穿透到下层 App；关闭后恢复正常点击/拖动/缩放交互。
     *
     * 仅写入 DataStore，[SecretaryOverlayService] 通过 collect 状态实时更新窗口 flags，
     * 无需重启 App，多角色切换保持统一设置。
     */
    fun setOverlayTouchPassthrough(enabled: Boolean) {
        scope.launch {
            withContext(NonCancellable) {
                settingsDataStore.setSecretaryOverlayTouchPassthrough(enabled)
            }
        }
    }

    fun setSdSkin(skin: String) {
        // 立即更新内存状态，确保 UI 实时响应（皮肤切换无需等待 DataStore 异步写入）
        _sdSkin.value = skin
        scope.launch {
            withContext(NonCancellable) {
                settingsDataStore.setSecretarySdSkin(skin)
            }
        }
    }

    fun setSdScale(scale: Float) {
        // 立即更新内存状态，确保 UI 实时响应滑块拖动（避免等待 DataStore 异步写入 + collect 回调的延迟）
        _sdScale.value = scale
        scope.launch {
            withContext(NonCancellable) {
                settingsDataStore.setSecretarySdScale(scale)
            }
        }
    }

    fun setSdResourceId(resourceId: String) {
        // 立即更新内存状态，确保 UI 实时响应
        _sdResourceId.value = resourceId
        // 切换资源时重置皮肤为默认，避免旧皮肤名不适用于新资源
        _sdSkin.value = ""
        scope.launch {
            withContext(NonCancellable) {
                settingsDataStore.setSecretarySdResourceId(resourceId)
                settingsDataStore.setSecretarySdSkin("")
            }
            // 联动更新语音和立绘：如果选择的 SD 资源对应一个舰娘，
            // 同时更新秘书舰名和语音列表，确保皮肤切换和语音与 SD 小人保持一致
            if (resourceId.isNotBlank()) {
                updateShipFromResourceId(resourceId)
            }
        }
    }

    /**
     * 从 SD 资源 ID 反查中文舰名，联动更新秘书舰名和语音。
     *
     * 反查策略：
     * 1. 先通过 [SDResourceManager.resolveById] 获取资源，检查是否有 shipName 字段
     * 2. 如果没有 shipName，但资源来源是舰娘，遍历舰娘数据库用拼音匹配
     * 3. 如果找到对应舰娘且与当前舰名不同，更新舰名、语音和立绘
     * 4. 如果资源无对应舰娘（自定义非舰娘资源），清空舰名和语音，
     *    避免错误使用原舰娘语音
     */
    private suspend fun updateShipFromResourceId(resourceId: String) {
        // 策略1：通过 SDResourceManager 获取资源的 shipName（resolveByShipName 构建的资源有此字段）
        val resource = withContext(Dispatchers.IO) {
            SDResourceManager.resolveById(appContext, resourceId)
        }
        val shipName = when {
            // 资源自带 shipName 字段（resolveByShipName 构建的资源）
            !resource?.shipName.isNullOrBlank() -> resource!!.shipName
            // 资源来源是舰娘但没有 shipName，遍历数据库拼音匹配
            resource?.source == com.azurlane.blyy.util.SDResourceSource.SHIP ->
                resolveShipNameByPinyin(resourceId)
            // 自定义资源，无对应舰娘
            else -> null
        }

        if (shipName.isNullOrBlank()) {
            // 无对应舰娘的自定义资源：清空舰名和语音，避免错误使用原语音
            // 即使原秘书舰有语音，切换到无语音资源后也不应继续播放原语音
            if (_shipName.value.isNotEmpty() || _voices.value.isNotEmpty()) {
                Log.d(TAG, "updateShipFromResourceId: 资源 $resourceId 无对应舰娘，清空舰名和语音（原 ${_shipName.value}）")
                _shipName.value = ""
                _voices.value = emptyList()
                _avatarUrl.value = ""
                _figureUrl.value = ""
                _currentDialogue.value = null
                stopAutoPlay()
                // 停止当前正在播放的语音
                onController { it.pause() }
                // 持久化清空，避免重启后残留原舰名导致错误加载语音
                withContext(NonCancellable) {
                    settingsDataStore.saveSecretaryShip("", "", "")
                }
            }
            return
        }

        // 找到对应舰名，且与当前舰名不同，则更新秘书舰和语音
        if (shipName != _shipName.value) {
            Log.d(TAG, "updateShipFromResourceId: 资源 $resourceId → 舰名 $shipName（原 ${_shipName.value}）")
            _shipName.value = shipName
            _voices.value = emptyList()
            _avatarUrl.value = ""
            _figureUrl.value = ""
            _currentDialogue.value = null
            stopAutoPlay()
            // 停止当前正在播放的旧语音，避免新语音加载期间继续播放旧语音
            onController { it.pause() }
            // 持久化新舰名，立绘和头像清空让后续加载流程重新获取
            withContext(NonCancellable) {
                settingsDataStore.saveSecretaryShip(shipName, "", "")
            }
            // 后台加载新舰娘的语音
            loadVoicesInBackground(shipName)
        }
    }

    /**
     * 通过拼音匹配从舰娘数据库反查中文舰名。
     *
     * 遍历所有 DOCK 类型舰娘，用 [PinyinHelper.toPinyin] 将舰名转拼音后与资源 ID 匹配。
     * 同时支持首字母缩写匹配和前缀匹配，覆盖 LocalSdResolver 的多种匹配策略。
     */
    private suspend fun resolveShipNameByPinyin(resourceId: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val ships = shipDao.getShipsByArchiveType(ArchiveType.DOCK.name).first()
                // 1. 拼音全拼精确匹配
                ships.firstOrNull { PinyinHelper.toPinyin(it.name) == resourceId }?.name
                    // 2. 拼音首字母缩写匹配
                    ?: ships.firstOrNull { PinyinHelper.toPinyinInitials(it.name) == resourceId }?.name
                    // 3. 资源ID是舰名全拼的前缀（资源用简写命名）
                    ?: ships.firstOrNull {
                        val pinyin = PinyinHelper.toPinyin(it.name)
                        pinyin.length >= 3 && pinyin.startsWith(resourceId)
                    }?.name
                    // 4. 舰名全拼是资源ID的前缀（资源用完整命名）
                    ?: ships.firstOrNull {
                        val pinyin = PinyinHelper.toPinyin(it.name)
                        resourceId.length >= pinyin.length && resourceId.startsWith(pinyin)
                    }?.name
            } catch (e: Exception) {
                Log.e(TAG, "resolveShipNameByPinyin: 反查舰名失败 $resourceId", e)
                null
            }
        }
    }

    private fun startAutoPlay() {
        stopAutoPlay()
        val intervalMs = state.value.autoPlayIntervalMinutes * 60 * 1000L
        autoPlayJob = scope.launch {
            while (isActive && _shipName.value.isNotEmpty()) {
                delay(intervalMs)
                if (isActive && state.value.autoPlayEnabled) playRandomVoice()
            }
        }
    }

    private fun stopAutoPlay() {
        autoPlayJob?.cancel()
        autoPlayJob = null
    }
    
    fun cleanup() {
        stopAutoPlay()
        scope.cancel()
    }
}
