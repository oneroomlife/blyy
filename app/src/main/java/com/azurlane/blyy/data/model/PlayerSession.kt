package com.azurlane.blyy.data.model

import kotlinx.serialization.Serializable

/**
 * 播放会话快照（用于「杀进程后继续听」）。
 *
 * 只保存**恢复播放所必需**的最小信息：URL、进度、队列关系。
 * 不保存 MediaItem 全量元数据（通知栏标题/封面这类信息丢了可以重建，
 * 但把 MediaItem 序列化进 DataStore 会让格式随 media3 版本漂移）。
 */
@Serializable
data class PlayerSession(
    /** 当前曲目 URL（mediaId 即 URL） */
    val url: String = "",
    /** 播放进度（毫秒） */
    val positionMs: Long = 0L,
    /** 是否处于「稍后播放」队列模式 */
    val fromQueue: Boolean = false,
    /** 队列模式下当前条目 URL（用于恢复队列高亮） */
    val queueItemUrl: String = "",
    /** 保存时间（毫秒）；超过 [MAX_AGE_MS] 的会话不再恢复，避免"几天前听了一半"的突兀 */
    val savedAt: Long = 0L
) {
    fun isRestorable(now: Long = System.currentTimeMillis()): Boolean =
        url.isNotBlank() && now - savedAt in 0..MAX_AGE_MS

    companion object {
        /** 会话有效期：24 小时 */
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
}
