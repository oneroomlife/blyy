package com.azurlane.blyy.domain

import com.azurlane.blyy.data.model.RankedScore
import com.azurlane.blyy.data.model.ScoreDifficulty
import com.azurlane.blyy.data.model.ScoreDifficultyFilter
import com.azurlane.blyy.data.model.ScoreMode
import com.azurlane.blyy.data.model.ScoreModeFilter
import com.azurlane.blyy.data.model.ScoreRecord
import com.azurlane.blyy.data.model.ScoreRun
import com.azurlane.blyy.data.model.ScoreSummary
import java.time.Instant
import java.time.ZoneId

/**
 * 「我的成绩榜」纯计算层：无 Android 依赖、无 IO，可直接 JVM 单测。
 *
 * 之所以把排序/聚合从 ViewModel 拆出来：这些规则会被 UI、诊断包、未来的周报共用，
 * 而放在 ViewModel 里只能靠 Robolectric 才测得动（本项目 45 个测试文件全是纯 JVM 测试）。
 */
object PersonalScoreBoard {

    /** 榜单展示上限：个人榜不需要虚拟化，但避免极端数据把整表塞进 Compose */
    const val RANK_LIMIT = 200

    /** 趋势图取样局数 */
    const val TREND_SIZE = 12

    /** 排序规则：分数降序 → 准确率降序 → 时间降序（同分看谁更稳、谁更新） */
    private val rankComparator: Comparator<ScoreRun> =
        compareByDescending<ScoreRun> { it.score }
            .thenByDescending { it.accuracy }
            .thenByDescending { it.timestamp }

    /** 按筛选条件过滤（模式 / 难度） */
    fun filter(
        runs: List<ScoreRun>,
        mode: ScoreModeFilter,
        difficulty: ScoreDifficultyFilter
    ): List<ScoreRun> = runs.filter { run ->
        (mode.mode == null || run.mode == mode.mode) &&
            (difficulty.difficulty == null || run.difficulty == difficulty.difficulty)
    }

    /** 生成带名次的个人榜（已按 [rankComparator] 排序并截断到 [limit]） */
    fun rank(runs: List<ScoreRun>, limit: Int = RANK_LIMIT): List<RankedScore> =
        runs.sortedWith(rankComparator)
            .take(limit)
            .mapIndexed { index, run -> RankedScore(rank = index + 1, run = run) }

    /** 全部历史的汇总（不受筛选影响，作为页面顶部的"总账"） */
    fun summarize(runs: List<ScoreRun>): ScoreSummary {
        if (runs.isEmpty()) return ScoreSummary()
        var totalScore = 0
        var totalQuestions = 0
        var correct = 0
        var best = 0
        val days = HashSet<Long>()
        val zone = ZoneId.systemDefault()
        runs.forEach { run ->
            totalScore += run.score
            totalQuestions += run.totalQuestions
            correct += run.correctAnswers
            if (run.score > best) best = run.score
            // 按本地日期去重得到"活跃天数"；用 epochDay 而不是字符串，省掉格式化开销
            days += Instant.ofEpochMilli(run.timestamp).atZone(zone).toLocalDate().toEpochDay()
        }
        return ScoreSummary(
            totalRuns = runs.size,
            totalScore = totalScore,
            bestScore = best,
            totalQuestions = totalQuestions,
            correctAnswers = correct,
            activeDays = days.size
        )
    }

    /** 四档个人纪录（看图/听音 × 简单/困难），无记录的档位 best = null */
    fun records(runs: List<ScoreRun>): List<ScoreRecord> {
        // ⚠️ 方向坑：rankComparator 是「降序」比较器（compareByDescending），
        // 对它用 maxWith() 取到的是**最差**一局（比较器语义下“更大”= 分数更低）。
        // 最佳记录 = 按该比较器排序后的首元素，所以用 firstOrNull（等价于 minWith）。
        // PersonalScoreBoardTest#records 守着这条语义。
        val bestByCategory = runs.groupBy { it.mode to it.difficulty }
            .mapValues { (_, list) -> list.sortedWith(rankComparator).firstOrNull() }
        return buildList {
            ScoreMode.entries.forEach { mode ->
                ScoreDifficulty.entries.forEach { difficulty ->
                    val key = mode to difficulty
                    add(
                        ScoreRecord(
                            mode = mode,
                            difficulty = difficulty,
                            best = bestByCategory[key],
                            runs = runs.count { it.mode == mode && it.difficulty == difficulty }
                        )
                    )
                }
            }
        }
    }

    /** 各档最高分记录的历史 id 集合，用于给列表行打「个人纪录」徽标 */
    fun bestHistoryIds(runs: List<ScoreRun>): Set<Long> =
        records(runs).mapNotNull { it.best?.historyId }.toSet()

    /**
     * 趋势：取筛选后最近 [limit] 局的得分，**时间正序**（左旧右新）。
     * 少于 2 局时返回空列表（画不出趋势，UI 直接隐藏该卡片）。
     */
    fun trend(runs: List<ScoreRun>, limit: Int = TREND_SIZE): List<Int> {
        if (runs.size < 2) return emptyList()
        return runs.sortedBy { it.timestamp }
            .takeLast(limit)
            .map { it.score }
    }

    /** 最近一次成绩（用于「上次 12 分 → 本次 18 分」这类对比文案） */
    fun latest(runs: List<ScoreRun>): ScoreRun? = runs.maxByOrNull { it.timestamp }
}
