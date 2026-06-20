package com.azurlane.blyy.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.azurlane.blyy.data.model.GuessHistory
import kotlinx.coroutines.flow.Flow

/**
 * 猜舰娘游戏历史记录 DAO
 */
@Dao
abstract class GuessHistoryDao {

    /** 查询所有历史记录，按时间倒序 */
    @Query("SELECT * FROM guess_history ORDER BY timestamp DESC")
    abstract fun getAllHistory(): Flow<List<GuessHistory>>

    /** 查询指定模式的历史记录 */
    @Query("SELECT * FROM guess_history WHERE mode = :mode ORDER BY timestamp DESC")
    abstract fun getHistoryByMode(mode: String): Flow<List<GuessHistory>>

    /** 查询最近 N 条记录 */
    @Query("SELECT * FROM guess_history ORDER BY timestamp DESC LIMIT :limit")
    abstract fun getRecentHistory(limit: Int): Flow<List<GuessHistory>>

    /** 查询指定 ID 的记录 */
    @Query("SELECT * FROM guess_history WHERE id = :id")
    abstract suspend fun getHistoryById(id: Long): GuessHistory?

    /**
     * 预检查：判断指定 sessionId + mode 的记录是否已存在
     * 返回 true 表示已存在（重复录入），应阻止插入
     */
    @Query("SELECT EXISTS(SELECT 1 FROM guess_history WHERE sessionId = :sessionId AND mode = :mode)")
    abstract suspend fun existsBySession(sessionId: String, mode: String): Boolean

    /**
     * 插入一条记录
     * 使用 IGNORE 策略：当复合唯一索引 [sessionId, mode] 冲突时静默跳过，不抛异常
     * 返回插入的 rowId；若因冲突被跳过则返回 -1
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(history: GuessHistory): Long

    /** 删除一条记录 */
    @Delete
    abstract suspend fun delete(history: GuessHistory)

    /** 按 ID 删除单条记录 */
    @Query("DELETE FROM guess_history WHERE id = :id")
    abstract suspend fun deleteById(id: Long)

    /** 批量删除记录 */
    @Query("DELETE FROM guess_history WHERE id IN (:ids)")
    abstract suspend fun deleteByIds(ids: List<Long>)

    /** 删除所有记录 */
    @Query("DELETE FROM guess_history")
    abstract suspend fun deleteAll()

    /** 获取总记录数 */
    @Query("SELECT COUNT(*) FROM guess_history")
    abstract fun getCount(): Flow<Int>

    /** 获取最高分 */
    @Query("SELECT MAX(totalScore) FROM guess_history")
    abstract fun getHighestScore(): Flow<Int?>

    // ── 统计聚合查询（数据库端预计算，避免客户端遍历） ──

    /** 总得分 */
    @Query("SELECT COALESCE(SUM(totalScore), 0) FROM guess_history")
    abstract fun getTotalScoreSum(): Flow<Int>

    /** 总题数 */
    @Query("SELECT COALESCE(SUM(totalQuestions), 0) FROM guess_history")
    abstract fun getTotalQuestionsSum(): Flow<Int>

    /** 总答对题数 */
    @Query("SELECT COALESCE(SUM(correctAnswers), 0) FROM guess_history")
    abstract fun getTotalCorrectSum(): Flow<Int>

    /** 按模式统计记录数 */
    @Query("SELECT mode, COUNT(*) as count FROM guess_history GROUP BY mode")
    abstract fun getCountByMode(): Flow<List<ModeCount>>
}

/** 模式统计结果 */
data class ModeCount(
    val mode: String,
    val count: Int
)
