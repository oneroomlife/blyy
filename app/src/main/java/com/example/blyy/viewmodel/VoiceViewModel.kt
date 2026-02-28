package com.example.blyy.viewmodel

import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
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
    val figureUrl: String = "",
    val voices: List<VoiceLine> = emptyList(),
    val error: String? = null,
    val currentVoiceIndex: Int = -1,
    val isPlaying: Boolean = false
)

sealed class VoiceIntent {
    data class LoadVoices(val shipName: String, val avatarUrl: String) : VoiceIntent()
    data class PlayVoiceAtIndex(val index: Int) : VoiceIntent()
    data class PlayRandomVoice(val excludeIndex: Int = -1) : VoiceIntent()
    object TogglePlayPause : VoiceIntent()
    object SkipNext : VoiceIntent()
    object SkipPrevious : VoiceIntent()
}

@HiltViewModel
class VoiceViewModel @OptIn(UnstableApi::class)
@Inject constructor(
    private val getVoicesUseCase: GetVoicesUseCase,
    private val playbackServiceConnection: PlaybackServiceConnection
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    private val _shipName = MutableStateFlow("")
    private val _avatarUrl = MutableStateFlow("")
    private val _figureUrl = MutableStateFlow("")
    private val _voices = MutableStateFlow<List<VoiceLine>>(emptyList())
    private val _currentVoiceIndex = MutableStateFlow(-1)
    private val _isPlaying = MutableStateFlow(false)

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.mediaId?.let { mediaId ->
                val index = _voices.value.indexOfFirst { it.audioUrl == mediaId }
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
        _isLoading, _voices, _error, _shipName, _avatarUrl, _figureUrl, _currentVoiceIndex, _isPlaying
    ) { args: Array<Any?> ->
        @Suppress("UNCHECKED_CAST")
        VoiceViewState(
            isLoading = args[0] as Boolean,
            voices = args[1] as List<VoiceLine>,
            error = args[2] as String?,
            shipName = args[3] as String,
            avatarUrl = args[4] as String,
            figureUrl = args[5] as String,
            currentVoiceIndex = args[6] as Int,
            isPlaying = args[7] as Boolean
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = VoiceViewState(isLoading = true)
    )

    fun onIntent(intent: VoiceIntent) {
        when (intent) {
            is VoiceIntent.LoadVoices -> loadVoices(intent.shipName, intent.avatarUrl)
            is VoiceIntent.PlayVoiceAtIndex -> playVoiceAtIndex(intent.index)
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

    private fun playVoiceAtIndex(index: Int) {
        onController { player ->
            val voices = _voices.value
            if (index in voices.indices) {
                _currentVoiceIndex.value = index
                val mediaItems = voices.map { createMediaItem(it) }
                player.setMediaItems(mediaItems, index, 0)
                player.prepare()
                player.play()
            }
        }
    }

    private fun playRandomVoice(excludeIndex: Int = -1) {
        onController { player ->
            val voices = _voices.value
            if (voices.isNotEmpty()) {
                val availableIndices = voices.indices.filter { it != excludeIndex }
                if (availableIndices.isNotEmpty()) {
                    val randomIndex = availableIndices.random()
                    _currentVoiceIndex.value = randomIndex
                    val mediaItems = voices.map { createMediaItem(it) }
                    player.setMediaItems(mediaItems, randomIndex, 0)
                    player.prepare()
                    player.play()
                }
            }
        }
    }

    private fun loadVoices(shipName: String, avatarUrl: String) {
        if (_shipName.value == shipName && _voices.value.isNotEmpty()) return

        viewModelScope.launch {
            _shipName.value = shipName
            _avatarUrl.value = avatarUrl
            _isLoading.value = true
            _error.value = null
            try {
                val result = getVoicesUseCase(shipName)
                _voices.value = result.first
                _figureUrl.value = result.second
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
