package com.example.blyy.viewmodel

import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.example.blyy.data.local.PlayerSettingsDataStore
import com.example.blyy.data.model.VoiceLine
import com.example.blyy.domain.GetVoicesUseCase
import com.example.blyy.service.PlaybackServiceConnection
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VoiceViewState(
    val isLoading: Boolean = false,
    val shipName: String = "",
    val avatarUrl: String = "",
    val figureUrl: String = "", // Base figure
    val currentSkinFigureUrl: String = "", // Synced figure from settings
    val wikiUrl: String = "",
    val voices: List<VoiceLine> = emptyList(),
    val error: String? = null,
    val currentVoiceIndex: Int = -1,
    val isPlaying: Boolean = false
)

sealed class VoiceIntent {
    data class LoadVoices(val shipName: String, val avatarUrl: String) : VoiceIntent()
    data class PlayVoiceAtIndex(val index: Int, val playMode: PlayMode = PlayMode.PLAY_ONCE) : VoiceIntent()
    data class PlayRandomVoice(val excludeIndex: Int = -1) : VoiceIntent()
    object TogglePlayPause : VoiceIntent()
    object SkipNext : VoiceIntent()
    object SkipPrevious : VoiceIntent()
}

@HiltViewModel
class VoiceViewModel @OptIn(UnstableApi::class)
@Inject constructor(
    private val getVoicesUseCase: GetVoicesUseCase,
    private val playbackServiceConnection: PlaybackServiceConnection,
    private val settingsDataStore: PlayerSettingsDataStore
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    private val _shipName = MutableStateFlow("")
    private val _avatarUrl = MutableStateFlow("")
    private val _figureUrl = MutableStateFlow("")
    private val _wikiUrl = MutableStateFlow("")
    private val _voices = MutableStateFlow<List<VoiceLine>>(emptyList())
    private val _currentVoiceIndex = MutableStateFlow(-1)
    private val _isPlaying = MutableStateFlow(false)
    private val _syncedFigureUrl = MutableStateFlow<String?>(null)

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.mediaId?.let { mediaId ->
                val index = state.value.voices.indexOfFirst { it.audioUrl == mediaId }
                if (index != -1) {
                    _currentVoiceIndex.value = index
                }
            }
        }
    }

    init {
        playbackServiceConnection.mediaController.addListener({
            playbackServiceConnection.mediaController.get()?.addListener(playerListener)
        }, MoreExecutors.directExecutor())
    }

    val state: StateFlow<VoiceViewState> = combine(
        _isLoading, _voices, _error, _shipName, _avatarUrl, _figureUrl, _wikiUrl, _currentVoiceIndex, _isPlaying, settingsDataStore.favorites, _syncedFigureUrl
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
        
        // Favorite voices are pinned to top
        val sortedVoices = originalVoices.sortedByDescending { it.audioUrl in favorites }

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
            isPlaying = isPlaying
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = VoiceViewState(isLoading = true)
    )

    fun onIntent(intent: VoiceIntent) {
        when (intent) {
            is VoiceIntent.LoadVoices -> loadVoices(intent.shipName, intent.avatarUrl)
            is VoiceIntent.PlayVoiceAtIndex -> playVoiceAtIndex(intent.index, intent.playMode)
            is VoiceIntent.PlayRandomVoice -> playRandomVoice(intent.excludeIndex)
            VoiceIntent.TogglePlayPause -> togglePlayPause()
            VoiceIntent.SkipNext -> skipNext()
            VoiceIntent.SkipPrevious -> skipPrevious()
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
        onController { it.seekToNextMediaItem() }
    }

    private fun skipPrevious() {
        onController { it.seekToPreviousMediaItem() }
    }

    private fun playVoiceAtIndex(index: Int, playMode: PlayMode) {
        onController { player ->
            val currentVoices = state.value.voices
            if (index in currentVoices.indices) {
                _currentVoiceIndex.value = index
                
                // Always set full list to enable manual navigation (next/prev)
                val mediaItems = currentVoices.map { createMediaItem(it) }
                player.setMediaItems(mediaItems, index, 0)
                
                // PlayerViewModel will handle PLAY_ONCE auto-pause logic
                player.repeatMode = when(playMode) {
                    PlayMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE
                    PlayMode.REPEAT_ALL -> Player.REPEAT_MODE_ALL
                    else -> Player.REPEAT_MODE_OFF
                }
                player.shuffleModeEnabled = (playMode == PlayMode.SHUFFLE)
                
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
                    val mediaItem = createMediaItem(voices[randomIndex])
                    player.setMediaItem(mediaItem)
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
            
            // Sync figure from settings
            viewModelScope.launch {
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

    fun createMediaItem(voice: VoiceLine): MediaItem {
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
            .setMediaId(voice.audioUrl)
            .setUri(voice.audioUrl)
            .setMediaMetadata(metadata)
            .build()
    }

    override fun onCleared() {
        super.onCleared()
        playbackServiceConnection.mediaController.get()?.removeListener(playerListener)
    }
}
