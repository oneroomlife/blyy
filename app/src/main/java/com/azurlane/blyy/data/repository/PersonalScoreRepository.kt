package com.azurlane.blyy.data.repository

import com.azurlane.blyy.data.local.GuessHistoryDao
import com.azurlane.blyy.data.model.GuessHistory
import com.azurlane.blyy.data.model.ScoreDifficulty
import com.azurlane.blyy.data.model.ScoreMode
import com.azurlane.blyy.data.model.ScoreRun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 个人成绩榜数据源：只读本地 `guess_history`。
 *
 * 替代旧的「GitHub Contents API + 客户端 PAT」排行榜实现。当前实现：
 * - 无网络、无凭证、无缓存层（Room Flow 本身就是唯一事实源）
 * - 成绩写入仍由答题流程负责（`GuessHistoryDao.upsertBySession`），本类只读 + 提供清除
 *
 * 之所以保留这一层而不是让 ViewModel 直接注入 DAO：
 * ① Room → 展示模型的投影（mode/difficulty 枚举解析、脏数据丢弃）需要集中一处；
 * ② 以后若要做「云端备份/多设备合并」，替换点在这里，UI 不用动。
 */
@Singleton
class PersonalScoreRepository @Inject constructor(
    private val guessHistoryDao: GuessHistoryDao
) {

    /** 全部历史成绩流（时间倒序由 DAO 保证，业务排序在 domain 层做） */
    val runs: Flow<List<ScoreRun>> = guessHistoryDao.getAllHistory()
        .map { history -> history.mapNotNull(::toRun) }
        .flowOn(Dispatchers.Default)

    /** 删除单条成绩 */
    suspend fun deleteRun(historyId: Long) = withContext(Dispatchers.IO) {
        guessHistoryDao.deleteById(historyId)
    }

    /** 清空全部成绩 */
    suspend fun clearRuns() = withContext(Dispatchers.IO) {
        guessHistoryDao.deleteAll()
    }

    /**
     * 投影：`GuessHistory` → [ScoreRun]。
     *
     * mode 无法识别（例如未来新增玩法）时丢弃该行，避免榜单出现"未知模式"的脏行；
     * difficulty 无法识别时降级 EASY —— 难度只影响标签，丢整条记录不划算。
     */
    private fun toRun(history: GuessHistory): ScoreRun? {
        val mode = ScoreMode.fromRaw(history.mode) ?: return null
        return ScoreRun(
            historyId = history.id,
            mode = mode,
            difficulty = ScoreDifficulty.fromRawOrEase(history.difficulty),
            score = history.totalScore,
            totalQuestions = history.totalQuestions,
            correctAnswers = history.correctAnswers,
            hintsUsed = history.hintsUsedTotal,
            skipped = history.skippedQuestions,
            totalPossibleScore = history.totalPossibleScore,
            accuracy = history.accuracy,
            timestamp = history.timestamp
        )
    }
}
