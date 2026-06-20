package com.azurlane.blyy.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 猜舰娘游戏历史记录实体
 * 每条记录对应一局完整的游戏（看图识舰娘或听音识舰娘）
 *
 * 复合唯一索引 [sessionId, mode] 确保同一局游戏不会被重复录入
 */
@Entity(
    tableName = "guess_history",
    indices = [Index(value = ["sessionId", "mode"], unique = true)]
)
data class GuessHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 游戏模式：IMAGE=看图识舰娘, VOICE=听音识舰娘 */
    val mode: String,
    /** 难度：EASY=简单, HARD=困难 */
    val difficulty: String,
    /** 总题数 */
    val totalQuestions: Int,
    /** 答对题数 */
    val correctAnswers: Int,
    /** 总得分 */
    val totalScore: Int,
    /** 使用提示次数 */
    val hintsUsedTotal: Int,
    /** 跳过题数 */
    val skippedQuestions: Int,
    /** 满分（总题数 * 10） */
    val totalPossibleScore: Int,
    /** 游戏时间戳（毫秒） */
    val timestamp: Long,
    /** 准确率（0~1） */
    val accuracy: Float,
    /** 平均得分 */
    val averageScore: Float,
    /** 游戏会话 ID（每局游戏唯一，防止重复录入） */
    val sessionId: String = ""
)
