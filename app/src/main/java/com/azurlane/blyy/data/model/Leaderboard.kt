package com.azurlane.blyy.data.model

import kotlinx.serialization.Serializable

/** 当前数据条目格式版本 */
const val LEADERBOARD_ENTRY_FORMAT_VERSION = 1

/** 当前排行榜文件结构版本 */
const val LEADERBOARD_SCHEMA_VERSION = 1

/**
 * 排行榜条目 — 对应一次游戏成绩
 *
 * 格式版本 [formatVersion] 用于前向兼容，旧数据缺少该字段时默认为 1。
 * [appVersion] 记录上传时的应用版本，便于追踪数据来源。
 *
 * [nickname] 在上传阶段即确定并存储：优先使用"碧蓝航线助手"解析到的玩家 ID，
 * 解析失败时回退为"指挥官"。排行榜加载时直接读取该字段展示，无需额外查询。
 */
@Serializable
data class LeaderboardEntry(
    /** 碧蓝航线游戏 UID（用于上传时查询玩家 ID，不在界面直接展示） */
    val uid: String,
    /** 服务器名称 */
    val server: String,
    /** 玩家昵称（上传时通过"碧蓝航线助手"解析并预存储，展示时直接读取） */
    val nickname: String,
    /** 总得分 */
    val score: Int,
    /** 准确率 (0~1) */
    val accuracy: Float,
    /** 总题数 */
    val totalQuestions: Int,
    /** 答对题数 */
    val correctAnswers: Int,
    /** 提交时间戳（毫秒） */
    val timestamp: Long,
    /** 上传时的应用版本号（元数据） */
    val appVersion: String = "",
    /** 数据条目格式版本（元数据） */
    val formatVersion: Int = LEADERBOARD_ENTRY_FORMAT_VERSION
)

/**
 * 排行榜完整数据 — 四个独立榜单
 *
 * [schema_version] 标识整体文件结构版本，便于后续迁移。
 */
@Serializable
data class LeaderboardData(
    val schema_version: Int = LEADERBOARD_SCHEMA_VERSION,
    val image_easy: List<LeaderboardEntry> = emptyList(),
    val image_hard: List<LeaderboardEntry> = emptyList(),
    val voice_easy: List<LeaderboardEntry> = emptyList(),
    val voice_hard: List<LeaderboardEntry> = emptyList()
)

/**
 * 排行榜分类
 */
enum class LeaderboardCategory(val key: String, val displayName: String) {
    IMAGE_EASY("image_easy", "看图识舰娘 · 简单"),
    IMAGE_HARD("image_hard", "看图识舰娘 · 困难"),
    VOICE_EASY("voice_easy", "听音识舰娘 · 简单"),
    VOICE_HARD("voice_hard", "听音识舰娘 · 困难");

    companion object {
        fun fromModeAndDifficulty(mode: String, difficulty: String): LeaderboardCategory {
            return when {
                mode == "IMAGE" && difficulty == "EASY" -> IMAGE_EASY
                mode == "IMAGE" && difficulty == "HARD" -> IMAGE_HARD
                mode == "VOICE" && difficulty == "EASY" -> VOICE_EASY
                mode == "VOICE" && difficulty == "HARD" -> VOICE_HARD
                else -> IMAGE_EASY
            }
        }
    }
}
