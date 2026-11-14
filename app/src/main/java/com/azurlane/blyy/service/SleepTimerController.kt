package com.azurlane.blyy.service

import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 睡眠定时的**进程级**持有者。
 *
 * 为什么不能放在 ViewModel 里（这是本轮审查发现的功能性 bug）：
 * 定时器原本跑在 `viewModelScope` 里，而语音页一退出 ViewModel 就被销毁 →
 * 定时任务被取消 → 用户设的"30 分钟后暂停"在翻页之后**静默失效**，
 * 音乐一直放到天亮。定时语义是"对播放器的约束"，生命周期必须与播放服务一致。
 *
 * 实现：单例 + 独立作用域，通过共享 [PlaybackServiceConnection] 的 MediaController
 * 直接控制播放器；UI 只观察 [remainingMs] 并下发 start/cancel。
 */
@OptIn(UnstableApi::class)
@Singleton
class SleepTimerController @Inject constructor(
    private val playbackServiceConnection: PlaybackServiceConnection
) {

    private companion object {
        private const val TAG = "SleepTimerController"

        /** 淡出步数 × 每步时长 = 3 秒 */
        private const val FADE_STEPS = 12
        private const val FADE_STEP_MS = 250L
    }

    /** 与进程同生命周期；不随任何页面销毁而取消 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _remainingMs = MutableStateFlow(0L)
    val remainingMs: StateFlow<Long> = _remainingMs.asStateFlow()

    private var timerJob: Job? = null

    /** 开始倒计时；[minutes] ≤ 0 等价于取消 */
    fun start(minutes: Int) {
        if (minutes <= 0) {
            cancel()
            return
        }
        timerJob?.cancel()
        val totalMs = minutes * 60_000L
        _remainingMs.value = totalMs
        timerJob = scope.launch {
            var remaining = totalMs
            while (isActive && remaining > 0) {
                delay(1_000)
                remaining -= 1_000
                _remainingMs.value = remaining.coerceAtLeast(0)
            }
            if (remaining <= 0) {
                fadeOutAndPause()
                _remainingMs.value = 0L
            }
        }
        Log.i(TAG, "sleep timer started: $minutes min")
    }

    fun cancel() {
        timerJob?.cancel()
        timerJob = null
        _remainingMs.value = 0L
        currentController()?.let { runCatching { it.volume = 1f } }
        Log.i(TAG, "sleep timer cancelled")
    }

    private suspend fun fadeOutAndPause() {
        val controller = currentController() ?: return
        for (step in FADE_STEPS downTo 0) {
            runCatching { controller.volume = step / FADE_STEPS.toFloat() }
            delay(FADE_STEP_MS)
        }
        runCatching {
            controller.pause()
            // 音量必须还原：否则用户下次点播放是"静音"的，会以为是 bug
            controller.volume = 1f
        }
    }

    /** Future 已完成才有控制器；未就绪时定时结束时什么都不做（此时本来也没在播） */
    private fun currentController(): MediaController? {
        val future = playbackServiceConnection.mediaController
        return if (future.isDone) runCatching { future.get() }.getOrNull() else null
    }
}
