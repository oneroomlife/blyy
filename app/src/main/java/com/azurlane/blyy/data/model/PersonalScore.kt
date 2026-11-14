package com.azurlane.blyy.data.model

import kotlin.math.roundToInt

/**
 * 「我的成绩榜」数据模型。
 *
 * 产品语义变更（v3.1）：排行榜从「全服 GitHub 榜单」改为**用户自身的历史成绩榜**。
 * 理由不只是安全（旧实现把 GitHub PAT 打包进 APK，见 docs/FEATURE_EXPANSION_2026-11.md P0-1）：
 * 猜舰娘是单机练习型玩法，玩家真正关心的是「我最好的一次」「我最近有没有进步」，
 * 而不是和一个只能在 100 条里挤出真人的全服榜较劲。
 *
 * 数据来源只有一个：本地 Room 的 `guess_history` 表（每次结束一局由 GuessShipViewModel 落库）。
 * 因此该页**完全离线**、零网络请求、零凭证。
 */

/** 玩法模式（对应 `guess_history.mode`） */
enum class ScoreMode(val rawValue: String, val label: String) {
    IMAGE("IMAGE", "看图识舰娘"),
    VOICE("VOICE", "听音识舰娘");

    companion object {
        fun fromRaw(raw: String): ScoreMode? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** 难度（对应 `guess_history.difficulty`） */
enum class ScoreDifficulty(val rawValue: String, val label: String) {
    EASY("EASY", "简单"),
    HARD("HARD", "困难");

    companion object {
        fun fromRaw(raw: String): ScoreDifficulty? = entries.firstOrNull { it.rawValue == raw }

        /** 无法识别的历史数据按简单处理，保证旧记录仍然可展示 */
        fun fromRawOrEase(raw: String): ScoreDifficulty = fromRaw(raw) ?: EASY
    }
}

/** 一局成绩（`GuessHistory` 的展示投影，只保留成绩榜需要的字段） */
data class ScoreRun(
    val historyId: Long,
    val mode: ScoreMode,
    val difficulty: ScoreDifficulty,
    val score: Int,
    val totalQuestions: Int,
    val correctAnswers: Int,
    val hintsUsed: Int,
    val skipped: Int,
    val totalPossibleScore: Int,
    val accuracy: Float,
    val timestamp: Long
) {
    val accuracyPercent: Int get() = (accuracy * 100f).roundToInt().coerceIn(0, 100)

    /** 无提示、无跳过且全对 */
    val perfect: Boolean get() = totalQuestions > 0 && correctAnswers >= totalQuestions && skipped == 0

    val categoryLabel: String get() = "${mode.label} · ${difficulty.label}"
}

/** 模式过滤（"全部"= 不限） */
enum class ScoreModeFilter(val label: String, val mode: ScoreMode?) {
    ALL("全部", null),
    IMAGE("看图", ScoreMode.IMAGE),
    VOICE("听音", ScoreMode.VOICE)
}

/** 难度过滤（"全部"= 不限） */
enum class ScoreDifficultyFilter(val label: String, val difficulty: ScoreDifficulty?) {
    ALL("全部", null),
    EASY("简单", ScoreDifficulty.EASY),
    HARD("困难", ScoreDifficulty.HARD)
}

/** 榜单行：名次 + 成绩 */
data class RankedScore(val rank: Int, val run: ScoreRun)

/** 汇总指标（全部历史，不受筛选影响） */
data class ScoreSummary(
    val totalRuns: Int = 0,
    val totalScore: Int = 0,
    val bestScore: Int = 0,
    val totalQuestions: Int = 0,
    val correctAnswers: Int = 0,
    val activeDays: Int = 0
) {
    val averageAccuracyPercent: Int
        get() = if (totalQuestions == 0) 0 else (correctAnswers * 100f / totalQuestions).roundToInt()

    val answerRateLabel: String get() = "$correctAnswers/$totalQuestions"
}

/** 「模式 × 难度」四档个人纪录 */
data class ScoreRecord(
    val mode: ScoreMode,
    val difficulty: ScoreDifficulty,
    val best: ScoreRun?,
    val runs: Int
) {
    val label: String get() = "${mode.label} · ${difficulty.label}"
}
