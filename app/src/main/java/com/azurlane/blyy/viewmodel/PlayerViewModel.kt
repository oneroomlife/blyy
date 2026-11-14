package com.azurlane.blyy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.azurlane.blyy.data.local.PlayLaterItem
import com.azurlane.blyy.data.local.PlayerSessionStore
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import com.azurlane.blyy.data.model.PlayerSession
import com.azurlane.blyy.service.PlaybackServiceConnection
import com.azurlane.blyy.service.SleepTimerController
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
    val currentlyPlayingQueueItemUrl: String? = null,
    /** 播放倍速（0.5x–2.0x，持久化在 DataStore） */
    val playbackSpeed: Float = 1f,
    /** 睡眠定时剩余毫秒；0 = 未开启 */
    val sleepTimerRemainingMs: Long = 0L
) {
    val progress: Float
        get() = if (duration > 0) currentPosition.toFloat() / duration else 0f

    val sleepTimerActive: Boolean get() = sleepTimerRemainingMs > 0
}

@HiltViewModel
@UnstableApi
class PlayerViewModel @Inject constructor(
    private val settingsDataStore: PlayerSettingsDataStore,
    private val playbackServiceConnection: PlaybackServiceConnection,
    private val sleepTimer: SleepTimerController,
    private val sessionStore: PlayerSessionStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    /**
     * 共享的 MediaController（来自 [PlaybackServiceConnection] 单例）。
     *
     * 重构说明：原实现每个 PlayerViewModel 实例都通过 MediaBrowser.Builder
     * 创建独立 IPC 连接并在 onCleared 释放。由于 VoiceScreen / GuessXScreen
     * 每次进出都会新建该 ViewModel，造成频繁的 IPC 连接建立/销毁开销。
     * 现复用进程级共享连接（与 VoiceViewModel / SecretaryManager 架构一致），
     * onCleared 仅移除 listener，不释放连接（连接生命周期与进程一致）。
     */
    @Volatile
    private var controller: MediaController? = null

    private var progressJob: Job? = null

    /** 冷启动读到的上次会话；在 MediaController 就绪后按需恢复 */
    @Volatile
    private var restoredSession: PlayerSession? = null

    /** ViewModel 是否已销毁 — 防止销毁后 listener 才被挂到共享 MediaController 上造成泄漏 */
    private var cleared = false

    /** playerListener 是否已成功挂载到共享 MediaController 上 */
    private var listenerAttached = false

    /** 串行化 listener 的挂载/卸载决策，封死 attach 与 onCleared 之间的竞态 */
    private val listenerLock = Any()

    /**
     * 注意：此属性必须声明在 init 块之前。
     * Kotlin 属性按声明顺序初始化：init { initializeSession() } 若先于本属性执行，
     * 且共享 MediaController Future 已完成（如二次进入语音页），directExecutor 会
     * 同步回调 addListener(playerListener)，此时 playerListener 尚为 null，
     * 抛出 NPE（被 Future executor 吞掉）导致 listener 永远挂载失败，
     * 播放控制条因 currentMediaItem 恒为 null 而无法弹出。
     */
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            _uiState.update { it.copy(isBuffering = playbackState == Player.STATE_BUFFERING) }
            controller?.let { updateDuration(it) }

            if (playbackState == Player.STATE_ENDED && _uiState.value.isPlayingFromQueue) {
                playNextFromQueue()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            android.util.Log.d("PlayerViewModel", "onIsPlayingChanged: $isPlaying")
            _uiState.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) {
                startProgressUpdateLoop()
            } else {
                stopProgressUpdateLoop()
                // 暂停是"听到哪了"的关键节点，强制落盘一次
                saveSession(force = true)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            android.util.Log.d("PlayerViewModel", "onMediaItemTransition: mediaItem=$mediaItem, reason=$reason")
            _uiState.update { it.copy(currentMediaItem = mediaItem, currentPosition = 0L) }
            saveSession(force = true)
            controller?.let { player ->
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
            controller?.let { player ->
                _uiState.update { it.copy(playMode = getPlayModeFromPlayer(player)) }
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            controller?.let { player ->
                _uiState.update { it.copy(playMode = getPlayModeFromPlayer(player)) }
            }
        }
    }

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

        // 倍速：持久化偏好，进入页面即恢复（控制器就绪后也会再应用一次）
        viewModelScope.launch {
            settingsDataStore.playbackSpeed.collect { speed ->
                _uiState.update { it.copy(playbackSpeed = speed) }
                controller?.setPlaybackSpeed(speed)
            }
        }

        // 睡眠定时剩余时间（进程级控制器 → UI 状态）
        viewModelScope.launch {
            sleepTimer.remainingMs.collect { remaining ->
                _uiState.update { it.copy(sleepTimerRemainingMs = remaining) }
            }
        }

        // 会话快照：只读一次，等控制器就绪后决定是否恢复
        viewModelScope.launch {
            val raw = settingsDataStore.playerSession.first()
            if (raw.isNotBlank()) {
                restoredSession = runCatching {
                    kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        .decodeFromString<PlayerSession>(raw)
                }.getOrNull()?.takeIf { it.isRestorable() }
            }
        }
    }

    private fun initializeSession() {
        android.util.Log.d("PlayerViewModel", "initializeSession: registering future listener")
        playbackServiceConnection.mediaController.addListener({
            val c = playbackServiceConnection.mediaController.get()
            android.util.Log.d("PlayerViewModel", "future completed, controller=$c, cleared=$cleared")
            synchronized(listenerLock) {
                if (cleared || c == null) return@addListener
                controller = c
                c.addListener(playerListener)
                listenerAttached = true
                android.util.Log.d("PlayerViewModel", "playerListener attached: listener=${System.identityHashCode(playerListener)}")
            }
            // 注意：不将 Player 实例放入 UiState。
            // Player 持有 native 资源与 media3 Session，UiState 持有会导致：
            // 1. Compose 重组时持有重对象引用
            // 2. onCleared 后 UiState 仍残留已释放 Player，UI 调用会崩溃
            // UI 仅通过 isPlaying / currentPosition 等派生字段观察状态，
            // 操作播放器统一通过 ViewModel 方法（playOrPause/seekTo/skipToNext 等）。
            _uiState.update {
                it.copy(
                    isPlaying = c.isPlaying,
                    playMode = getPlayModeFromPlayer(c)
                )
            }
            updateMediaState(c)
            // 控制器就绪后：应用持久化倍速 + 按需恢复上次会话（只做一次）
            c.setPlaybackSpeed(_uiState.value.playbackSpeed)
            restoreSessionIfIdle(c)
        }, MoreExecutors.directExecutor())
    }

    private fun getPlayModeFromPlayer(player: Player): PlayMode {
        return when {
            player.shuffleModeEnabled -> PlayMode.SHUFFLE
            player.repeatMode == Player.REPEAT_MODE_ONE -> PlayMode.REPEAT_ONE
            player.repeatMode == Player.REPEAT_MODE_ALL -> PlayMode.REPEAT_ALL
            else -> PlayMode.PLAY_ONCE
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
            var lastPosition = -1L
            while (isActive) {
                controller?.let { player ->
                    val position = player.currentPosition
                    // Only emit when position actually changed to reduce recompositions
                    if (position != lastPosition) {
                        lastPosition = position
                        _uiState.update {
                            it.copy(
                                currentPosition = position,
                                savedPosition = position
                            )
                        }
                        // 播放中每 5 秒落一次会话（saveSession 内部节流），
                        // 保证"杀进程后回来接着听"的进度不会停留在很久以前
                        saveSession()
                    }
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
        controller?.let { player ->
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
        controller?.seekTo(positionMs)
        _uiState.update { it.copy(currentPosition = positionMs, savedPosition = positionMs) }
    }

    fun skipToNext() {
        controller?.let { player ->
            if (_uiState.value.isPlayingFromQueue) {
                val queue = _uiState.value.playLaterList
                if (queue.isNotEmpty()) {
                    val currentUrl = _uiState.value.currentlyPlayingQueueItemUrl
                    val currentIndex = queue.indexOfFirst { it.voiceUrl == currentUrl }
                    val nextIndex = (currentIndex + 1) % queue.size
                    playFromPlayLater(queue[nextIndex])
                }
                return@let
            }

            if (player.hasNextMediaItem()) {
                player.seekToNext()
            } else if (player.mediaItemCount > 0) {
                player.seekTo(0, 0)
            }
            player.play()
        }
    }

    fun skipToPrevious() {
        controller?.let { player ->
            if (_uiState.value.isPlayingFromQueue) {
                val queue = _uiState.value.playLaterList
                if (queue.isNotEmpty()) {
                    val currentUrl = _uiState.value.currentlyPlayingQueueItemUrl
                    val currentIndex = queue.indexOfFirst { it.voiceUrl == currentUrl }
                    val prevIndex = if (currentIndex <= 0) queue.size - 1 else currentIndex - 1
                    playFromPlayLater(queue[prevIndex])
                }
                return@let
            }

            if (player.hasPreviousMediaItem()) {
                player.seekToPrevious()
            } else if (player.mediaItemCount > 0) {
                player.seekTo(player.mediaItemCount - 1, 0)
            }
            player.play()
        }
    }

    fun clearQueueMode() {
        _uiState.update {
            it.copy(
                isPlayingFromQueue = false,
                currentlyPlayingQueueItemUrl = null
            )
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
        _uiState.update { it.copy(playMode = mode) }

        controller?.let { player ->
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
            controller?.seekTo(savedPos)
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
        controller?.let { player ->
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
        controller?.let { player ->
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

    // ─────────────────── 播放器产品化：倍速 / 睡眠定时 / 会话快照 ───────────────────

    /**
     * 冷启动恢复播放位置。
     *
     * 只在「控制器没有任何曲目」时执行（即用户是从桌面点进来听语音，而不是刚点了某句台词），
     * 且**不自动播放**——恢复的是"听到哪了"，播放由用户决定。
     */
    private fun restoreSessionIfIdle(player: MediaController) {
        val session = restoredSession ?: return
        restoredSession = null // 每个 ViewModel 实例只恢复一次
        if (player.currentMediaItem != null || player.isPlaying) return
        if (session.url.isBlank()) return
        runCatching {
            player.setMediaItem(
                MediaItem.Builder()
                    .setMediaId(session.url)
                    .setUri(session.url)
                    .build()
            )
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.shuffleModeEnabled = false
            player.prepare()
            if (session.positionMs > 0) player.seekTo(session.positionMs)
        }.onFailure { android.util.Log.w("PlayerViewModel", "restore session failed", it) }

        _uiState.update {
            it.copy(
                currentMediaItem = player.currentMediaItem,
                currentPosition = session.positionMs,
                savedPosition = session.positionMs,
                isPlayingFromQueue = session.fromQueue,
                currentlyPlayingQueueItemUrl = session.queueItemUrl.ifBlank { null }
            )
        }
    }

    /** 轮换倍速：0.75x → 1x → 1.25x → 1.5x → 0.75x（台词精听场景够用，避免长列表） */
    fun cycleSpeed() {
        val steps = listOf(0.75f, 1f, 1.25f, 1.5f)
        val current = _uiState.value.playbackSpeed
        val next = steps.firstOrNull { it > current + 0.001f } ?: steps.first()
        setSpeed(next)
    }

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(PlayerSettingsDataStore.SPEED_MIN, PlayerSettingsDataStore.SPEED_MAX)
        _uiState.update { it.copy(playbackSpeed = clamped) }
        controller?.setPlaybackSpeed(clamped)
        viewModelScope.launch { settingsDataStore.setPlaybackSpeed(clamped) }
    }

    /**
     * 睡眠定时：**委托给进程级的 [SleepTimerController]**。
     *
     * 这里曾经把倒计时放在 viewModelScope 里，导致退出语音页（VM 被销毁）后
     * 定时任务被取消——"30 分钟后暂停"静默失效。定时属于播放器约束，
     * 生命周期必须跟着播放服务，不跟页面走。
     */
    fun startSleepTimer(minutes: Int) {
        sleepTimer.start(minutes)
        saveSession(force = true)
    }

    fun cancelSleepTimer() {
        sleepTimer.cancel()
    }

    /**
     * 保存「听到哪了」。force=true 用于切歌/暂停/退出这些关键节点。
     *
     * 写盘交给进程级的 [PlayerSessionStore]：`onCleared()` 里 `viewModelScope` 已经
     * 被取消，原先在这里 launch 的写法实际从未执行（见 PlayerSessionStore 类注释）。
     */
    private fun saveSession(force: Boolean = false) {
        val player = controller ?: return
        val item = player.currentMediaItem ?: return

        // 已播完（单曲/队列结束）→ 清掉会话，避免下次启动把用户"恢复"到片尾
        if (player.playbackState == Player.STATE_ENDED) {
            sessionStore.clear()
            return
        }

        val url = item.mediaId.ifBlank { item.localConfiguration?.uri?.toString().orEmpty() }
        if (url.isBlank()) return
        sessionStore.save(
            PlayerSession(
                url = url,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                fromQueue = _uiState.value.isPlayingFromQueue,
                queueItemUrl = _uiState.value.currentlyPlayingQueueItemUrl.orEmpty(),
                savedAt = System.currentTimeMillis()
            ),
            force = force
        )
    }

    override fun onCleared() {
        android.util.Log.d("PlayerViewModel", "onCleared: removing listener, attached=$listenerAttached")
        // 与 initializeSession 中的挂载回调互斥：确保"挂载决策"与"卸载决策"串行化。
        // 若 listener 已挂载则安排移除；若尚未挂载，cleared 标记会让挂载回调直接跳过。
        // 共享 MediaController 连接不在此释放（生命周期与进程一致，由 PlaybackServiceConnection 管理）。
        synchronized(listenerLock) {
            cleared = true
            if (listenerAttached) {
                playbackServiceConnection.mediaController.addListener({
                    playbackServiceConnection.mediaController.get()?.removeListener(playerListener)
                    synchronized(listenerLock) { listenerAttached = false }
                }, MoreExecutors.directExecutor())
            }
        }
        saveSession(force = true)
        // 注意：不取消睡眠定时（它跟随进程，不跟随页面）
        controller = null
        stopProgressUpdateLoop()
        super.onCleared()
    }
}
