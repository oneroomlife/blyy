package com.azurlane.blyy.viewmodel

import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import com.azurlane.blyy.data.model.VoiceLanguage
import com.azurlane.blyy.data.model.VoiceLine
import com.azurlane.blyy.data.repository.ShipRepository
import com.azurlane.blyy.domain.GetVoicesUseCase
import com.azurlane.blyy.service.PlaybackServiceConnection
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.Job

data class VoiceViewState(
    val isLoading: Boolean = false,
    val isRetrying: Boolean = false,
    val retryCount: Int = 0,
    val shipName: String = "",
    val avatarUrl: String = "",
    val figureUrl: String = "",
    val currentSkinFigureUrl: String = "",
    val wikiUrl: String = "",
    val voices: List<VoiceLine> = emptyList(),
    val error: String? = null,
    val currentVoiceIndex: Int = -1,
    val isPlaying: Boolean = false,
    val voiceLanguage: VoiceLanguage = VoiceLanguage.CN,
    val studentLink: String = "",  // 非空时表示当前为学生档案，用于重试逻辑
    val isCacheHit: Boolean = false  // 语音数据是否来自缓存
)

sealed class VoiceIntent {
    data class LoadVoices(val shipName: String, val avatarUrl: String) : VoiceIntent()
    data class LoadStudentVoices(val studentName: String, val avatarUrl: String, val studentLink: String) : VoiceIntent()
    data class PlayVoiceAtIndex(val index: Int, val playMode: PlayMode = PlayMode.PLAY_ONCE) : VoiceIntent()
    data class PlayRandomVoice(val excludeIndex: Int = -1) : VoiceIntent()
    object TogglePlayPause : VoiceIntent()
    object SkipNext : VoiceIntent()
    object SkipPrevious : VoiceIntent()
    data class SetVoiceLanguage(val language: VoiceLanguage) : VoiceIntent()
}

@HiltViewModel
class VoiceViewModel @OptIn(UnstableApi::class)
@Inject constructor(
    private val getVoicesUseCase: GetVoicesUseCase,
    private val playbackServiceConnection: PlaybackServiceConnection,
    private val settingsDataStore: PlayerSettingsDataStore,
    private val repository: ShipRepository
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    private val _isRetrying = MutableStateFlow(false)
    private val _retryCount = MutableStateFlow(0)
    private val _isCacheHit = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError
    fun clearPlaybackError() { _playbackError.value = null }
    private val _shipName = MutableStateFlow("")
    private val _avatarUrl = MutableStateFlow("")
    private val _figureUrl = MutableStateFlow("")
    private val _wikiUrl = MutableStateFlow("")
    private val _voices = MutableStateFlow<List<VoiceLine>>(emptyList())
    private val _currentVoiceIndex = MutableStateFlow(-1)
    private val _isPlaying = MutableStateFlow(false)
    private val _syncedFigureUrl = MutableStateFlow<String?>(null)
    private val _voiceLanguage = MutableStateFlow(VoiceLanguage.CN)
    private val _studentLink = MutableStateFlow("")
    private var figureSyncJob: Job? = null

    companion object {
        private const val TAG = "VoiceViewModel"
        /** 最大重试次数 */
        private const val MAX_RETRY_COUNT = 3
        /** 重试间隔（毫秒）— 指数退避 */
        private const val RETRY_BASE_DELAY_MS = 1000L
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.mediaId?.let { mediaId ->
                val voices = state.value.voices
                val index = voices.indexOfFirst {
                    it.audioUrlCn == mediaId || it.audioUrlJp == mediaId
                }
                if (index != -1) {
                    _currentVoiceIndex.value = index
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _playbackError.value = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                    "网络连接失败，请检查网络后重试"
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                    "网络连接超时，请重试"
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                    "资源访问被拒绝（防盗链），请重试"
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FAILED ->
                    "音频文件损坏或格式不支持"
                else -> "播放失败，请重试"
            }
        }
    }

    init {
        playbackServiceConnection.mediaController.addListener({
            playbackServiceConnection.mediaController.get()?.addListener(playerListener)
        }, MoreExecutors.directExecutor())
        
        viewModelScope.launch {
            settingsDataStore.voiceLanguage.collect { lang ->
                _voiceLanguage.value = lang
            }
        }
    }

    val state: StateFlow<VoiceViewState> = combine(
        _isLoading, _voices, _error, _shipName, _avatarUrl, _figureUrl, _wikiUrl, _currentVoiceIndex, _isPlaying, settingsDataStore.favorites, _syncedFigureUrl, _voiceLanguage, _studentLink, _isRetrying, _retryCount, _isCacheHit
    ) { args: Array<Any?> ->
        val isLoading = args[0] as Boolean
        @Suppress("UNCHECKED_CAST")
        val originalVoices = args[1] as List<VoiceLine>
        val error = args[2] as String?
        val shipName = args[3] as String
        val avatarUrl = args[4] as String
        val figureUrl = args[5] as String
        val wikiUrl = args[6] as String
        val currentVoiceIndex = args[7] as Int
        val isPlaying = args[8] as Boolean
        @Suppress("UNCHECKED_CAST")
        val favorites = args[9] as Set<String>
        val syncedFigure = args[10] as String?
        val voiceLanguage = args[11] as VoiceLanguage
        val studentLink = args[12] as String
        val isRetrying = args[13] as Boolean
        val retryCount = args[14] as Int
        val isCacheHit = args[15] as Boolean

        val sortedVoices = originalVoices.sortedByDescending { voice ->
            voice.audioUrlCn in favorites || voice.audioUrlJp in favorites
        }

        VoiceViewState(
            isLoading = isLoading,
            voices = sortedVoices,
            error = error,
            shipName = shipName,
            avatarUrl = avatarUrl,
            figureUrl = figureUrl,
            currentSkinFigureUrl = syncedFigure ?: figureUrl,
            wikiUrl = wikiUrl,
            currentVoiceIndex = currentVoiceIndex,
            isPlaying = isPlaying,
            voiceLanguage = voiceLanguage,
            studentLink = studentLink,
            isRetrying = isRetrying,
            retryCount = retryCount,
            isCacheHit = isCacheHit
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = VoiceViewState(isLoading = true)
    )

    fun onIntent(intent: VoiceIntent) {
        when (intent) {
            is VoiceIntent.LoadVoices -> loadVoices(intent.shipName, intent.avatarUrl)
            is VoiceIntent.LoadStudentVoices -> loadStudentVoices(intent.studentName, intent.avatarUrl, intent.studentLink)
            is VoiceIntent.PlayVoiceAtIndex -> playVoiceAtIndex(intent.index, intent.playMode)
            is VoiceIntent.PlayRandomVoice -> playRandomVoice(intent.excludeIndex)
            VoiceIntent.TogglePlayPause -> togglePlayPause()
            VoiceIntent.SkipNext -> skipNext()
            VoiceIntent.SkipPrevious -> skipPrevious()
            is VoiceIntent.SetVoiceLanguage -> setVoiceLanguage(intent.language)
        }
    }

    private fun onController(command: (Player) -> Unit) {
        val mediaControllerFuture = playbackServiceConnection.mediaController
        mediaControllerFuture.addListener({
            val controller = mediaControllerFuture.get()
            if (controller != null) {
                command(controller)
            }
        }, MoreExecutors.directExecutor())
    }

    private fun togglePlayPause() {
        onController { player ->
            if (player.isPlaying) player.pause() else player.play()
        }
    }

    private fun skipNext() {
        onController { player ->
            val voices = state.value.voices
            if (voices.isEmpty()) return@onController

            val currentIndex = _currentVoiceIndex.value
            val nextIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % voices.size

            val currentMode = getPlayModeFromPlayer(player)
            playVoiceAtIndex(nextIndex, currentMode)
        }
    }

    private fun skipPrevious() {
        onController { player ->
            val voices = state.value.voices
            if (voices.isEmpty()) return@onController

            val currentIndex = _currentVoiceIndex.value
            val prevIndex = if (currentIndex <= 0) voices.size - 1 else currentIndex - 1

            val currentMode = getPlayModeFromPlayer(player)
            playVoiceAtIndex(prevIndex, currentMode)
        }
    }

    private fun getPlayModeFromPlayer(player: Player): PlayMode {
        return when {
            player.shuffleModeEnabled -> PlayMode.SHUFFLE
            player.repeatMode == Player.REPEAT_MODE_ONE -> PlayMode.REPEAT_ONE
            player.repeatMode == Player.REPEAT_MODE_ALL -> PlayMode.REPEAT_ALL
            else -> PlayMode.PLAY_ONCE
        }
    }

    private fun playVoiceAtIndex(index: Int, playMode: PlayMode) {
        onController { player ->
            val currentVoices = state.value.voices
            if (index in currentVoices.indices) {
                _currentVoiceIndex.value = index

                when (playMode) {
                    PlayMode.PLAY_ONCE, PlayMode.REPEAT_ONE -> {
                        // 在单次播放或单曲循环模式下，只加载当前项，避免自动跳转到下一首
                        player.setMediaItem(createMediaItem(currentVoices[index]))
                        player.repeatMode = if (playMode == PlayMode.REPEAT_ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                        player.shuffleModeEnabled = false
                    }
                    PlayMode.REPEAT_ALL -> {
                        val mediaItems = currentVoices.map { createMediaItem(it) }
                        player.setMediaItems(mediaItems, index, 0)
                        player.repeatMode = Player.REPEAT_MODE_ALL
                        player.shuffleModeEnabled = false
                    }
                    PlayMode.SHUFFLE -> {
                        val mediaItems = currentVoices.map { createMediaItem(it) }
                        player.setMediaItems(mediaItems, index, 0)
                        player.repeatMode = Player.REPEAT_MODE_ALL
                        player.shuffleModeEnabled = true
                    }
                }

                player.prepare()
                player.play()
            }
        }
    }

    private fun playRandomVoice(excludeIndex: Int = -1) {
        onController { player ->
            val voices = state.value.voices
            if (voices.isNotEmpty()) {
                val availableIndices = voices.indices.filter { it != excludeIndex }
                if (availableIndices.isNotEmpty()) {
                    val randomIndex = availableIndices.random()
                    _currentVoiceIndex.value = randomIndex
                    
                    // 默认按单次播放处理，仅加载单曲。手动切歌由 skipNext/Previous 处理。
                    player.setMediaItem(createMediaItem(voices[randomIndex]))

                    player.repeatMode = Player.REPEAT_MODE_OFF
                    player.shuffleModeEnabled = false
                    player.prepare()
                    player.play()
                }
            }
        }
    }

    private fun loadVoices(shipName: String, avatarUrl: String) {
        viewModelScope.launch {
            _shipName.value = shipName
            _avatarUrl.value = avatarUrl
            _isLoading.value = true
            _error.value = null
            _studentLink.value = ""

            // Cancel previous figure sync collector to prevent leak
            figureSyncJob?.cancel()
            figureSyncJob = launch {
                settingsDataStore.getSavedFigure(shipName).collect {
                    _syncedFigureUrl.value = it
                }
            }

            try {
                val result = getVoicesUseCase(shipName)
                _voices.value = result.first
                _figureUrl.value = result.second
                _wikiUrl.value = "https://wiki.biligame.com/blhx/${java.net.URLEncoder.encode(shipName, "UTF-8")}"
            } catch (e: Exception) {
                _error.value = e.message ?: "加载语音失败"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun loadStudentVoices(studentName: String, avatarUrl: String, studentLink: String) {
        viewModelScope.launch {
            _shipName.value = studentName
            _avatarUrl.value = avatarUrl
            _isLoading.value = true
            _error.value = null
            _studentLink.value = studentLink
            _retryCount.value = 0
            _isRetrying.value = false
            _isCacheHit.value = false

            figureSyncJob?.cancel()
            _syncedFigureUrl.value = null

            // 带重试机制的加载
            loadStudentVoicesWithRetry(studentLink, avatarUrl, isRetry = false)
        }
    }

    /**
     * 带重试机制的学生语音加载
     *
     * 重试策略：
     * - 最多重试 3 次
     * - 指数退避：1s, 2s, 4s
     * - 首次尝试使用缓存，重试时强制刷新
     * - 重试时显示"正在重试"状态提示
     */
    private suspend fun loadStudentVoicesWithRetry(
        studentLink: String,
        avatarUrl: String,
        isRetry: Boolean
    ) {
        try {
            if (isRetry) {
                _isRetrying.value = true
                _retryCount.value = _retryCount.value + 1
                // 指数退避延迟
                val delayMs = RETRY_BASE_DELAY_MS * (1L shl (_retryCount.value - 1))
                android.util.Log.d(TAG, "Retrying student voices (attempt ${_retryCount.value}/$MAX_RETRY_COUNT) after ${delayMs}ms")
                kotlinx.coroutines.delay(delayMs)
            }

            // 首次尝试使用缓存，重试时强制刷新
            val result = repository.fetchStudentVoicesCached(
                studentLink,
                forceRefresh = isRetry
            )

            _voices.value = result.first
            _figureUrl.value = result.second.ifEmpty { avatarUrl }
            _wikiUrl.value = studentLink
            _isLoading.value = false
            _isRetrying.value = false
            _error.value = null
            _isCacheHit.value = !isRetry // 首次成功且来自缓存时标记
            android.util.Log.d(TAG, "Student voices loaded: ${result.first.size} voices, cacheHit=${!isRetry}")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "loadStudentVoices failed (attempt ${_retryCount.value + 1}/$MAX_RETRY_COUNT): ${e.message}")

            if (_retryCount.value < MAX_RETRY_COUNT) {
                // 重试
                loadStudentVoicesWithRetry(studentLink, avatarUrl, isRetry = true)
            } else {
                // 重试次数用尽，显示错误
                _isLoading.value = false
                _isRetrying.value = false
                _error.value = e.message ?: "加载学生语音失败（已重试 $MAX_RETRY_COUNT 次）"
                android.util.Log.e(TAG, "All retry attempts exhausted for student voices", e)
            }
        }
    }

    private fun setVoiceLanguage(language: VoiceLanguage) {
        viewModelScope.launch {
            settingsDataStore.setVoiceLanguage(language)
        }
    }

    fun createMediaItem(voice: VoiceLine): MediaItem {
        val activeUrl = voice.getActiveAudioUrl(_voiceLanguage.value)
        val cleanDialogue = voice.dialogue
            .replace("\n", " ")
            .replace("\r", "")
            .trim()

        val metadata = MediaMetadata.Builder()
            .setTitle(cleanDialogue)
            .setArtist("${_shipName.value} · ${voice.skinName}")
            .setAlbumTitle(voice.scene)
            .setArtworkUri(_avatarUrl.value.toUri())
            .build()

        return MediaItem.Builder()
            .setMediaId(activeUrl)
            .setUri(activeUrl)
            .setMediaMetadata(metadata)
            .build()
    }

    override fun onCleared() {
        super.onCleared()
        playbackServiceConnection.mediaController.get()?.removeListener(playerListener)
    }
}
