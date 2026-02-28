package com.example.blyy.viewmodel

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import com.example.blyy.service.PlaybackService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject


@UnstableApi
data class PlayerUiState(
    val player: Player? = null,
    val currentMediaItem: MediaItem? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val errorMessage: String? = null,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffleModeEnabled: Boolean = false
) {
    val progress: Float
        get() = if (duration > 0) currentPosition.toFloat() / duration else 0f
}

@HiltViewModel
@UnstableApi
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var browser: MediaBrowser? = null
    private var progressJob: Job? = null

    init {
        initializeSession()
    }

    private fun initializeSession() {
        viewModelScope.launch {
            try {
                val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
                val browserFuture = MediaBrowser.Builder(context, sessionToken).buildAsync()
                browser = browserFuture.await()

                browser?.let { player ->
                    player.addListener(playerListener)
                    _uiState.update {
                        it.copy(
                            player = player,
                            isPlaying = player.isPlaying,
                            repeatMode = player.repeatMode,
                            shuffleModeEnabled = player.shuffleModeEnabled
                        )
                    }
                    updateMediaState(player)
                }
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Connection failed", e)
                _uiState.update { it.copy(errorMessage = "服务连接失败: ${e.message}") }
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            _uiState.update { it.copy(isBuffering = playbackState == Player.STATE_BUFFERING) }
            browser?.let { updateDuration(it) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) startProgressUpdateLoop() else stopProgressUpdateLoop()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _uiState.update { it.copy(currentMediaItem = mediaItem, currentPosition = 0L) }
            browser?.let { updateDuration(it) }
        }

        override fun onPlayerError(error: PlaybackException) {
            _uiState.update { it.copy(errorMessage = error.localizedMessage ?: "播放出错") }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _uiState.update { it.copy(repeatMode = repeatMode) }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _uiState.update { it.copy(shuffleModeEnabled = shuffleModeEnabled) }
        }
    }

    private fun updateMediaState(player: Player) {
        _uiState.update {
            it.copy(
                currentMediaItem = player.currentMediaItem,
                duration = if (player.duration != C.TIME_UNSET) player.duration else 0L
            )
        }
    }

    private fun updateDuration(player: Player) {
        val duration = if (player.duration != C.TIME_UNSET) player.duration else 0L
        _uiState.update { it.copy(duration = duration) }
    }

    private fun startProgressUpdateLoop() {
        stopProgressUpdateLoop()
        progressJob = viewModelScope.launch {
            while (isActive) {
                browser?.let { player ->
                    _uiState.update { it.copy(currentPosition = player.currentPosition) }
                }
                delay(500)
            }
        }
    }

    private fun stopProgressUpdateLoop() {
        progressJob?.cancel()
        progressJob = null
    }

    fun playOrPause() {
        browser?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun seekTo(positionMs: Long) {
        browser?.seekTo(positionMs)
        _uiState.update { it.copy(currentPosition = positionMs) }
    }

    fun skipToNext() {
        if (browser?.hasNextMediaItem() == true) browser?.seekToNext()
    }

    fun skipToPrevious() {
        if (browser?.hasPreviousMediaItem() == true) browser?.seekToPrevious()
    }

    fun toggleRepeatMode() {
        browser?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun toggleShuffleMode() {
        browser?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    override fun onCleared() {
        super.onCleared()
        browser?.removeListener(playerListener)
        browser?.release()
        browser = null
        stopProgressUpdateLoop()
    }
}