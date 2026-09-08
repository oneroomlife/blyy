
package com.azurlane.blyy.service

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局共享的 [MediaController] 连接。
 *
 * 必须为 [Singleton]：否则每个注入点（如 VoiceViewModel、SecretaryManager）都会
 * 通过 [MediaController.Builder.buildAsync] 创建一个独立的 IPC 连接，且永不释放，
 * 导致进程级 MediaController 累积泄漏与 IPC 资源浪费。
 *
 * 调用方应通过 [mediaController] 获取已建立的连接；进程退出时由系统回收，
 * 无需调用方主动 release。
 */
@UnstableApi
@Singleton
class PlaybackServiceConnection @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
    val mediaController: ListenableFuture<MediaController> =
        MediaController.Builder(context, sessionToken).buildAsync()
}

/**
 * 在 MediaController Future 完成后执行 [action]。
 *
 * 统一的异步回调模式，替代各 ViewModel 自行编写的 addListener + get() 代码：
 * - 回调在 Future 完成后触发，此时 [ListenableFuture.get] 立即返回，不阻塞调用线程
 *   （直接在调用线程上调用 get() 会阻塞主线程，存在 ANR 风险）
 * - [canceled] 返回 true 时跳过执行，用于宿主（ViewModel）已销毁的场景
 */
@UnstableApi
fun ListenableFuture<MediaController>.whenReady(
    canceled: () -> Boolean = { false },
    action: (MediaController) -> Unit
) {
    addListener({
        if (canceled()) return@addListener
        val controller = get()
        action(controller)
    }, MoreExecutors.directExecutor())
}
