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
import com.example.blyy.data.local.PlayLaterItem
import com.example.blyy.data.local.PlayerSettingsDataStore
import com.example.blyy.service.PlaybackService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class PlayMode {
    PLAY_ONCE,
    REPEAT_ONE,
    REPEAT_ALL,
    SHUFFLE
}

@UnstableApi
data class PlayerUiState(
    val player: Player? = null,
    val currentMediaItem: MediaItem? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val errorMessage: String? = null,
    val playMode: PlayMode = PlayMode.PLAY_ONCE,
    val savedPosition: Long = 0L,
    val favorites: Set<String> = emptySet(),
    val playLaterList: List<PlayLaterItem> = emptyList(),
    val isPlayingFromQueue: Boolean = false,
    val currentlyPlayingQueueItemUrl: String? = null
) {
    val progress: Float
        get() = if (duration > 0) currentPosition.toFloat() / duration else 0f
}

@HiltViewModel
@UnstableApi
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: PlayerSettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var browser: MediaBrowser? = null
    private var progressJob: Job? = null

    init {
        initializeSession()
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            settingsDataStore.playMode.collect { savedMode ->
                setPlayMode(savedMode)
            }
        }
        
        viewModelScope.launch {
            settingsDataStore.favorites.collect { favorites ->
                _uiState.update { it.copy(favorites = favorites) }
            }
        }
        
        viewModelScope.launch {
            settingsDataStore.playLaterList.collectLatest { playLater ->
                _uiState.update { it.copy(playLaterList = playLater) }
            }
        }
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
                            playMode = getPlayModeFromPlayer(player)
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

    private fun getPlayModeFromPlayer(player: Player): PlayMode {
        return when {
            player.shuffleModeEnabled -> PlayMode.SHUFFLE
            player.repeatMode == Player.REPEAT_MODE_ONE -> PlayMode.REPEAT_ONE
            player.repeatMode == Player.REPEAT_MODE_ALL -> PlayMode.REPEAT_ALL
            else -> PlayMode.PLAY_ONCE
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            _uiState.update { it.copy(isBuffering = playbackState == Player.STATE_BUFFERING) }
            browser?.let { updateDuration(it) }
            
            if (playbackState == Player.STATE_ENDED && _uiState.value.isPlayingFromQueue) {
                playNextFromQueue()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) startProgressUpdateLoop() else stopProgressUpdateLoop()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _uiState.update { it.copy(currentMediaItem = mediaItem, currentPosition = 0L) }
            browser?.let { player ->
                updateDuration(player)
                
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && _uiState.value.playMode == PlayMode.PLAY_ONCE) {
                    player.pause()
                    player.seekTo(0)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _uiState.update { it.copy(errorMessage = error.localizedMessage ?: "播放出错") }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            browser?.let { player ->
                _uiState.update { it.copy(playMode = getPlayModeFromPlayer(player)) }
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            browser?.let { player ->
                _uiState.update { it.copy(playMode = getPlayModeFromPlayer(player)) }
            }
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
                    val position = player.currentPosition
                    _uiState.update { 
                        it.copy(
                            currentPosition = position,
                            savedPosition = position
                        ) 
                    }
                }
                delay(250)
            }
        }
    }

    private fun stopProgressUpdateLoop() {
        progressJob?.cancel()
        progressJob = null
    }

    fun playOrPause() {
        browser?.let { player ->
            if (player.isPlaying) {
                player.pause()
            } else {
                val savedPos = _uiState.value.savedPosition
                if (savedPos > 0 && player.currentPosition == 0L) {
                    player.seekTo(savedPos)
                }
                player.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        browser?.seekTo(positionMs)
        _uiState.update { it.copy(currentPosition = positionMs, savedPosition = positionMs) }
    }

    fun skipToNext() {
        browser?.let { player ->
            if (player.hasNextMediaItem()) {
                player.seekToNext()
            } else if (player.mediaItemCount > 0) {
                player.seekTo(0, 0)
            }
            player.play()
        }
    }

    fun skipToPrevious() {
        browser?.let { player ->
            if (player.hasPreviousMediaItem()) {
                player.seekToPrevious()
            } else if (player.mediaItemCount > 0) {
                player.seekTo(player.mediaItemCount - 1, 0)
            }
            player.play()
        }
    }

    fun cyclePlayMode() {
        val nextMode = when (_uiState.value.playMode) {
            PlayMode.PLAY_ONCE -> PlayMode.REPEAT_ONE
            PlayMode.REPEAT_ONE -> PlayMode.REPEAT_ALL
            PlayMode.REPEAT_ALL -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE -> PlayMode.PLAY_ONCE
        }
        setPlayMode(nextMode)
        viewModelScope.launch {
            settingsDataStore.savePlayMode(nextMode)
        }
    }

    fun setPlayMode(mode: PlayMode) {
        browser?.let { player ->
            _uiState.update { it.copy(playMode = mode) }
            
            when (mode) {
                PlayMode.PLAY_ONCE -> {
                    player.repeatMode = Player.REPEAT_MODE_OFF
                    player.shuffleModeEnabled = false
                }
                PlayMode.REPEAT_ONE -> {
                    player.repeatMode = Player.REPEAT_MODE_ONE
                    player.shuffleModeEnabled = false
                }
                PlayMode.REPEAT_ALL -> {
                    player.repeatMode = Player.REPEAT_MODE_ALL
                    player.shuffleModeEnabled = false
                }
                PlayMode.SHUFFLE -> {
                    player.repeatMode = Player.REPEAT_MODE_ALL
                    player.shuffleModeEnabled = true
                }
            }
        }
    }

    fun restoreProgress() {
        val savedPos = _uiState.value.savedPosition
        if (savedPos > 0) {
            browser?.seekTo(savedPos)
            _uiState.update { it.copy(currentPosition = savedPos) }
        }
    }

    fun toggleFavorite(voiceUrl: String) {
        viewModelScope.launch {
            settingsDataStore.toggleFavorite(voiceUrl)
        }
    }

    fun isFavorite(voiceUrl: String): Boolean {
        return voiceUrl in _uiState.value.favorites
    }

    fun addToPlayLater(item: PlayLaterItem) {
        viewModelScope.launch {
            settingsDataStore.addToPlayLater(item)
        }
    }

    fun removeFromPlayLater(voiceUrl: String) {
        viewModelScope.launch {
            settingsDataStore.removeFromPlayLater(voiceUrl)
        }
    }

    fun clearPlayLaterList() {
        viewModelScope.launch {
            settingsDataStore.clearPlayLaterList()
        }
    }

    fun markItemAsPlayed(voiceUrl: String) {
        viewModelScope.launch {
            settingsDataStore.markAsPlayed(voiceUrl)
        }
    }

    fun playFromPlayLater(item: PlayLaterItem) {
        browser?.let { player ->
            val mediaItem = MediaItem.Builder()
                .setMediaId(item.voiceUrl)
                .setUri(item.voiceUrl)
                .build()
            
            player.setMediaItem(mediaItem)
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.shuffleModeEnabled = false
            player.prepare()
            player.play()
            
            _uiState.update { 
                it.copy(
                    playMode = PlayMode.PLAY_ONCE,
                    isPlayingFromQueue = true,
                    currentlyPlayingQueueItemUrl = item.voiceUrl
                ) 
            }
        }
    }

    fun playSingleVoice(url: String) {
        browser?.let { player ->
            val mediaItem = MediaItem.Builder()
                .setMediaId(url)
                .setUri(url)
                .build()

            player.setMediaItem(mediaItem)
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.shuffleModeEnabled = false
            player.prepare()
            player.play()

            _uiState.update {
                it.copy(
                    playMode = PlayMode.PLAY_ONCE,
                    isPlayingFromQueue = false,
                    currentlyPlayingQueueItemUrl = null
                )
            }
        }
    }
    
    fun startPlayQueue() {
        val queue = _uiState.value.playLaterList
        if (queue.isNotEmpty()) {
            playFromPlayLater(queue.first())
        }
    }
    
    private fun playNextFromQueue() {
        val currentQueue = _uiState.value.playLaterList
        if (currentQueue.isNotEmpty()) {
            val currentItemUrl = _uiState.value.currentlyPlayingQueueItemUrl
            val currentIndex = currentQueue.indexOfFirst { it.voiceUrl == currentItemUrl }
            
            if (currentIndex >= 0) {
                viewModelScope.launch {
                    settingsDataStore.markAsPlayed(currentQueue[currentIndex].voiceUrl)
                }
            }
            
            val nextIndex = currentIndex + 1
            if (nextIndex < currentQueue.size) {
                val nextItem = currentQueue[nextIndex]
                playFromPlayLater(nextItem)
            } else {
                _uiState.update { 
                    it.copy(
                        isPlayingFromQueue = false,
                        currentlyPlayingQueueItemUrl = null
                    ) 
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        browser?.removeListener(playerListener)
        browser?.release()
        browser = null
        stopProgressUpdateLoop()
    }
}
