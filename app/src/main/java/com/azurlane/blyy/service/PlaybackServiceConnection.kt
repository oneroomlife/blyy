
package com.azurlane.blyy.service

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
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
