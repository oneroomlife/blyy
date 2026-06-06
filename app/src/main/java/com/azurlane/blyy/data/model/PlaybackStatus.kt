package com.azurlane.blyy.data.model

/**
 * 音频播放状态枚举
 * 严格管理播放器的生命周期状态
 */
enum class PlaybackStatus {
    IDLE,       // 空闲/初始状态
    LOADING,    // 加载中/缓冲中
    PLAYING,    // 正在播放
    PAUSED,     // 已暂停
    ENDED,      // 播放自然结束
    STOPPED,    // 用户主动停止
    ERROR       // 播放发生错误
}
