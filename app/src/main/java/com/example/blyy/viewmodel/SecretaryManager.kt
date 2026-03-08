package com.example.blyy.viewmodel

import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.example.blyy.data.local.PlayerSettingsDataStore
import com.example.blyy.data.model.Ship
import com.example.blyy.data.model.VoiceLine
import com.example.blyy.domain.GetVoicesUseCase
import com.example.blyy.domain.SelectSecretaryUseCase
import com.example.blyy.service.PlaybackServiceConnection
import com.google.common.util.concurrent.MoreExecutors
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
    val autoPlayIntervalMinutes: Int = 5
)

@Singleton
class SecretaryManager @Inject constructor(
    private val settingsDataStore: PlayerSettingsDataStore,
    private val selectSecretaryUseCase: SelectSecretaryUseCase,
    private val getVoicesUseCase: GetVoicesUseCase,
    private val playbackServiceConnection: PlaybackServiceConnection
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _shipName = MutableStateFlow("")
    private val _figureUrl = MutableStateFlow("")
    private val _avatarUrl = MutableStateFlow("")
    private val _voices = MutableStateFlow<List<VoiceLine>>(emptyList())
    private val _isLoadingVoices = MutableStateFlow(false)
    private var autoPlayJob: Job? = null
    
    companion object {
        private const val TAG = "SecretaryManager"
    }

    val state: StateFlow<SecretaryManagerState> = combine(
        _shipName,
        _figureUrl,
        _avatarUrl,
        _voices,
        _isLoadingVoices,
        settingsDataStore.secretaryAutoPlayEnabled,
        settingsDataStore.secretaryAutoPlayIntervalMinutes
    ) { args ->
        SecretaryManagerState(
            shipName = args[0] as String,
            figureUrl = args[1] as String,
            avatarUrl = args[2] as String,
            voices = @Suppress("UNCHECKED_CAST") (args[3] as List<VoiceLine>),
            isLoadingVoices = args[4] as Boolean,
            autoPlayEnabled = args[5] as Boolean,
            autoPlayIntervalMinutes = args[6] as Int
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
            
            // 异步保存到持久化存储
            scope.launch {
                settingsDataStore.saveSecretaryShip(ship.name, finalFigureUrl, ship.avatarUrl)
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
                settingsDataStore.saveSecretaryShip(ship.name, ship.avatarUrl, ship.avatarUrl)
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
            
            // 异步保存
            scope.launch {
                settingsDataStore.saveSecretaryShip(ship.name, figureUrl, ship.avatarUrl)
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
                settingsDataStore.saveSecretaryShip(ship.name, ship.avatarUrl, ship.avatarUrl)
            }
        } finally {
            _isLoadingVoices.value = false
            if (state.value.autoPlayEnabled) startAutoPlay()
        }
    }

    fun clearSecretary() {
        Log.d(TAG, "clearSecretary: 清除秘书舰")
        scope.launch {
            settingsDataStore.clearSecretaryShip()
            _shipName.value = ""
            _figureUrl.value = ""
            _avatarUrl.value = ""
            _voices.value = emptyList()
            stopAutoPlay()
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    fun playRandomVoice() {
        val voices = _voices.value
        if (voices.isEmpty()) {
            if (_shipName.value.isNotEmpty()) loadVoicesInBackground(_shipName.value)
            return
        }
        onController { player ->
            val randomVoice = voices.random()
            val mediaItem = createMediaItem(randomVoice)
            player.setMediaItem(mediaItem)
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.prepare()
            player.play()
            Log.d(TAG, "playRandomVoice: 播放语音 ${randomVoice.scene}")
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun createMediaItem(voice: VoiceLine): MediaItem {
        val cleanDialogue = voice.dialogue.replace("\n", " ").replace("\r", "").trim()
        val metadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(cleanDialogue)
            .setArtist("${_shipName.value} · ${voice.skinName}")
            .setAlbumTitle(voice.scene)
            .setArtworkUri(_avatarUrl.value.let { if (it.isNotEmpty()) it.toUri() else null })
            .build()
        return MediaItem.Builder()
            .setMediaId(voice.audioUrl)
            .setUri(voice.audioUrl)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun onController(command: (Player) -> Unit) {
        playbackServiceConnection.mediaController.addListener({
            playbackServiceConnection.mediaController.get()?.let { command(it) }
        }, MoreExecutors.directExecutor())
    }

    fun ensureVoicesLoaded(shipName: String) {
        if (_shipName.value == shipName && _voices.value.isEmpty() && !_isLoadingVoices.value) {
            loadVoicesInBackground(shipName)
        }
    }

    private fun loadVoicesInBackground(shipName: String) {
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
            settingsDataStore.setSecretaryAutoPlay(enabled, intervalMinutes)
            if (enabled && _shipName.value.isNotEmpty()) startAutoPlay()
            else stopAutoPlay()
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
