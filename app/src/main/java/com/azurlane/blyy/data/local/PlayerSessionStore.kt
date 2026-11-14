package com.azurlane.blyy.data.local

import android.util.Log
import com.azurlane.blyy.data.model.PlayerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播放会话的**进程级**写入器。
 *
 * 为什么需要它（本轮审查发现的真实 bug）：
 * 会话快照原先在 `PlayerViewModel.onCleared()` 里用 `viewModelScope.launch { ... }` 写入，
 * 但 `ViewModel.clear()` 会**先取消 viewModelScope、再调用 onCleared()** ——
 * 也就是说"退出页面时保存进度"这段代码从来没有执行过。
 * 换成独立作用域后，页面销毁不影响落盘。
 *
 * 附带收益：写入节流从 ViewModel 挪到这里，多个入口（暂停/切歌/退出/定时结束）
 * 共享同一份时间戳，不会互相顶掉。
 */
@Singleton
class PlayerSessionStore @Inject constructor(
    private val settings: PlayerSettingsDataStore
) {

    private companion object {
        private const val TAG = "PlayerSessionStore"
        /** 播放中每 5 秒最多落一次盘 */
        private const val THROTTLE_MS = 5_000L
    }

    /** 与进程同生命周期（后台/退出页面后仍能完成最后一次写入） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var lastSavedAt = 0L

    /**
     * 保存会话；[force] = false 时按 [THROTTLE_MS] 节流。
     * 非 suspend：调用方（含 onCleared）不需要协程上下文。
     */
    fun save(session: PlayerSession, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSavedAt < THROTTLE_MS) return
        lastSavedAt = now
        scope.launch {
            runCatching {
                settings.setPlayerSession(json.encodeToString(PlayerSession.serializer(), session))
            }.onFailure { Log.w(TAG, "save session failed: ${it.message}") }
        }
    }

    /** 播完/清空时移除会话，避免下次"恢复到片尾" */
    fun clear() {
        lastSavedAt = 0L
        scope.launch {
            runCatching { settings.clearPlayerSession() }
                .onFailure { Log.w(TAG, "clear session failed: ${it.message}") }
        }
    }
}
