package com.azurlane.blyy.domain

import com.azurlane.blyy.data.model.ScoreDifficulty
import com.azurlane.blyy.data.model.ScoreDifficultyFilter
import com.azurlane.blyy.data.model.ScoreMode
import com.azurlane.blyy.data.model.ScoreModeFilter
import com.azurlane.blyy.data.model.ScoreRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「我的成绩榜」纯计算层单测。
 *
 * 守护点（这些规则一旦被改坏，用户看到的名次/纪录就是错的）：
 * 1. 排序：分数降序 → 准确率降序 → 时间降序；
 * 2. 名次只在当前筛选内计算，不跨筛选串联；
 * 3. 四档纪录各自独立，无记录的档位 best 为 null；
 * 4. 汇总统计基于全量历史（不受筛选影响），活跃天数按本地日期去重；
 * 5. 趋势按时间正序、少于 2 局不画。
 */
class PersonalScoreBoardTest {

    private fun run(
        id: Long,
        mode: ScoreMode = ScoreMode.IMAGE,
        difficulty: ScoreDifficulty = ScoreDifficulty.EASY,
        score: Int,
        accuracy: Float = 0.8f,
        correct: Int = 8,
        total: Int = 10,
        hints: Int = 0,
        skipped: Int = 0,
        timestamp: Long = 1_700_000_000_000L + id * 60_000L
    ) = ScoreRun(
        historyId = id,
        mode = mode,
        difficulty = difficulty,
        score = score,
        totalQuestions = total,
        correctAnswers = correct,
        hintsUsed = hints,
        skipped = skipped,
        totalPossibleScore = total * 10,
        accuracy = accuracy,
        timestamp = timestamp
    )

    @Test
    fun `rank sorts by score then accuracy then recency`() {
        val runs = listOf(
            run(id = 1, score = 80, accuracy = 0.8f),
            run(id = 2, score = 100, accuracy = 0.7f),
            run(id = 3, score = 80, accuracy = 0.9f),
            run(id = 4, score = 80, accuracy = 0.9f, timestamp = 1_800_000_000_000L)
        )

        val ranked = PersonalScoreBoard.rank(runs)

        assertEquals(listOf(2L, 4L, 3L, 1L), ranked.map { it.run.historyId })
        assertEquals(listOf(1, 2, 3, 4), ranked.map { it.rank })
    }

    @Test
    fun `rank truncates to limit`() {
        val runs = (1..10).map { run(id = it.toLong(), score = it) }
        assertEquals(3, PersonalScoreBoard.rank(runs, limit = 3).size)
    }

    @Test
    fun `filter by mode and difficulty keeps only matching runs`() {
        val runs = listOf(
            run(id = 1, mode = ScoreMode.IMAGE, difficulty = ScoreDifficulty.EASY, score = 10),
            run(id = 2, mode = ScoreMode.IMAGE, difficulty = ScoreDifficulty.HARD, score = 20),
            run(id = 3, mode = ScoreMode.VOICE, difficulty = ScoreDifficulty.EASY, score = 30)
        )

        val images = PersonalScoreBoard.filter(runs, ScoreModeFilter.IMAGE, ScoreDifficultyFilter.ALL)
        assertEquals(listOf(1L, 2L), images.map { it.historyId })

        val hardOnly = PersonalScoreBoard.filter(runs, ScoreModeFilter.ALL, ScoreDifficultyFilter.HARD)
        assertEquals(listOf(2L), hardOnly.map { it.historyId })

        val voiceEasy = PersonalScoreBoard.filter(
            runs,
            ScoreModeFilter.VOICE,
            ScoreDifficultyFilter.EASY
        )
        assertEquals(listOf(3L), voiceEasy.map { it.historyId })

        assertEquals(3, PersonalScoreBoard.filter(runs, ScoreModeFilter.ALL, ScoreDifficultyFilter.ALL).size)
    }

    @Test
    fun `rank restarts numbering inside filtered subset`() {
        val runs = listOf(
            run(id = 1, mode = ScoreMode.IMAGE, score = 100),
            run(id = 2, mode = ScoreMode.VOICE, score = 50),
            run(id = 3, mode = ScoreMode.VOICE, score = 70)
        )

        val voiceRanked = PersonalScoreBoard.rank(
            PersonalScoreBoard.filter(runs, ScoreModeFilter.VOICE, ScoreDifficultyFilter.ALL)
        )

        assertEquals(listOf(3L, 2L), voiceRanked.map { it.run.historyId })
        assertEquals(1, voiceRanked.first().rank)
    }

    @Test
    fun `records exposes four categories and null for untouched ones`() {
        val runs = listOf(
            run(id = 1, mode = ScoreMode.IMAGE, difficulty = ScoreDifficulty.EASY, score = 60),
            run(id = 2, mode = ScoreMode.IMAGE, difficulty = ScoreDifficulty.EASY, score = 90),
            run(id = 3, mode = ScoreMode.VOICE, difficulty = ScoreDifficulty.HARD, score = 40)
        )

        val records = PersonalScoreBoard.records(runs)

        assertEquals(4, records.size)
        val imageEasy = records.first { it.mode == ScoreMode.IMAGE && it.difficulty == ScoreDifficulty.EASY }
        assertEquals(90, imageEasy.best?.score)
        assertEquals(2, imageEasy.runs)

        val imageHard = records.first { it.mode == ScoreMode.IMAGE && it.difficulty == ScoreDifficulty.HARD }
        assertNull(imageHard.best)
        assertEquals(0, imageHard.runs)

        val voiceHard = records.first { it.mode == ScoreMode.VOICE && it.difficulty == ScoreDifficulty.HARD }
        assertEquals(40, voiceHard.best?.score)
    }

    @Test
    fun `bestHistoryIds marks one record per category`() {
        val runs = listOf(
            run(id = 1, mode = ScoreMode.IMAGE, difficulty = ScoreDifficulty.EASY, score = 60),
            run(id = 2, mode = ScoreMode.IMAGE, difficulty = ScoreDifficulty.EASY, score = 90),
            run(id = 3, mode = ScoreMode.VOICE, difficulty = ScoreDifficulty.EASY, score = 30)
        )

        val ids = PersonalScoreBoard.bestHistoryIds(runs)

        assertEquals(setOf(2L, 3L), ids)
    }

    @Test
    fun `summary aggregates full history and counts distinct active days`() {
        // 同一天的两局 + 另一天的一局 → activeDays = 2
        val day1 = 1_700_000_000_000L
        val day2 = day1 + 24 * 60 * 60 * 1000L
        val runs = listOf(
            run(id = 1, score = 10, correct = 5, total = 10, timestamp = day1),
            run(id = 2, score = 20, correct = 5, total = 10, timestamp = day1 + 3_600_000L),
            run(id = 3, score = 30, correct = 10, total = 10, timestamp = day2)
        )

        val summary = PersonalScoreBoard.summarize(runs)

        assertEquals(3, summary.totalRuns)
        assertEquals(60, summary.totalScore)
        assertEquals(30, summary.bestScore)
        assertEquals(20, summary.correctAnswers)
        assertEquals(30, summary.totalQuestions)
        assertEquals(67, summary.averageAccuracyPercent) // 20/30 = 66.67 -> 67
        assertEquals(2, summary.activeDays)
    }

    @Test
    fun `summary of empty list is all zeros`() {
        val summary = PersonalScoreBoard.summarize(emptyList())
        assertEquals(0, summary.totalRuns)
        assertEquals(0, summary.bestScore)
        assertEquals(0, summary.averageAccuracyPercent)
        assertEquals("0/0", summary.answerRateLabel)
    }

    @Test
    fun `trend is time ascending and needs at least two runs`() {
        val runs = (1..3).map { run(id = it.toLong(), score = it * 10) }

        val trend = PersonalScoreBoard.trend(runs)

        assertEquals(listOf(10, 20, 30), trend)
        assertTrue(PersonalScoreBoard.trend(listOf(run(id = 1, score = 10))).isEmpty())
    }

    @Test
    fun `trend keeps only the latest window`() {
        val runs = (1..20).map { run(id = it.toLong(), score = it) }
        val trend = PersonalScoreBoard.trend(runs, limit = 5)
        assertEquals(listOf(16, 17, 18, 19, 20), trend)
    }

    @Test
    fun `latest returns most recent run by timestamp`() {
        val runs = listOf(
            run(id = 1, score = 10, timestamp = 100L),
            run(id = 2, score = 20, timestamp = 300L),
            run(id = 3, score = 30, timestamp = 200L)
        )
        assertEquals(2L, PersonalScoreBoard.latest(runs)?.historyId)
        assertNull(PersonalScoreBoard.latest(emptyList()))
    }
}
