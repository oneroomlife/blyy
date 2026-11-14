package com.azurlane.blyy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurlane.blyy.data.model.RankedScore
import com.azurlane.blyy.data.model.ScoreDifficultyFilter
import com.azurlane.blyy.data.model.ScoreModeFilter
import com.azurlane.blyy.data.model.ScoreRecord
import com.azurlane.blyy.ui.components.BlyyChip
import com.azurlane.blyy.ui.components.BlyyHaptic
import com.azurlane.blyy.ui.components.BlyyScreenScaffold
import com.azurlane.blyy.ui.components.BlyyTabRow
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.components.rememberBlyyHaptics
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.MedalColors
import com.azurlane.blyy.viewmodel.PersonalScoreUiState
import com.azurlane.blyy.viewmodel.PersonalScoreViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 我的成绩榜 —— **纯本地**的个人历史成绩排行。
 *
 * 与旧「积分排行榜」的差别（对应 docs/FEATURE_EXPANSION_2026-11.md P0-1/P0-2）：
 * - 没有全服榜、没有 GitHub 上传、没有客户端 PAT、没有 UID/服务器前置条件；
 * - 数据源是本地 `guess_history`，冷启动与断网都能看；
 * - 排序规则：分数 → 准确率 → 时间（同分看谁更稳、谁更新），名次只在当前筛选内计算。
 *
 * 页面结构：总账卡 → 趋势 → 四档纪录 → 筛选 → 榜单列表（每行可删）。
 */
@Composable
fun PersonalScoreScreen(
    onBack: () -> Unit,
    onStartGame: () -> Unit = {},
    viewModel: PersonalScoreViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptic = rememberBlyyHaptics()

    BlyyScreenScaffold(
        topBar = {
            BlyyTopBar(
                title = "我的成绩榜",
                subtitle = if (state.nickname.isBlank()) {
                    "本地历史成绩"
                } else {
                    "${state.nickname} · 本地历史成绩"
                },
                onBackClick = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                horizontal = AppSpacing.Screen.Horizontal,
                vertical = AppSpacing.Sm
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
        ) {
            item(key = "summary", contentType = "summary") {
                SummaryCard(state)
            }

            if (state.trend.size >= 2) {
                item(key = "trend", contentType = "trend") {
                    TrendCard(
                        trend = state.trend,
                        best = state.trend.max()
                    )
                }
            }

            item(key = "records", contentType = "records") {
                RecordStripCard(records = state.records)
            }

            item(key = "filters", contentType = "filters") {
                FilterSection(
                    state = state,
                    onModeSelected = { filter ->
                        haptic(BlyyHaptic.Tick)
                        viewModel.setModeFilter(filter)
                    },
                    onDifficultySelected = { filter ->
                        haptic(BlyyHaptic.Tick)
                        viewModel.setDifficultyFilter(filter)
                    },
                    onReset = { viewModel.resetFilters() }
                )
            }

            when {
                state.isEmpty -> item(key = "empty", contentType = "empty") {
                    EmptyBlock(onStartGame = onStartGame)
                }

                state.isFilteredEmpty -> item(key = "filtered_empty", contentType = "empty") {
                    FilteredEmptyBlock(onReset = { viewModel.resetFilters() })
                }

                else -> {
                    items(
                        items = state.ranked,
                        key = { it.run.historyId },
                        contentType = { "rank_row" }
                    ) { ranked ->
                        RankRow(
                            ranked = ranked,
                            isPersonalBest = ranked.run.historyId in state.bestHistoryIds,
                            onDelete = {
                                haptic(BlyyHaptic.Tick)
                                viewModel.deleteRun(ranked.run.historyId)
                            }
                        )
                    }
                }
            }

            item(key = "bottom_spacer", contentType = "spacer") {
                Spacer(modifier = Modifier.height(AppSpacing.Xxl))
            }
        }
    }
}

// ─────────────────────────────── 总账卡 ───────────────────────────────

@Composable
private fun SummaryCard(state: PersonalScoreUiState) {
    val summary = state.summary
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppSpacing.Corner.Md),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(AppSpacing.Md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Insights,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(AppSpacing.Xs))
                Text(
                    text = "个人总览",
                    style = AppTypography.TitleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (summary.activeDays > 0) "活跃 ${summary.activeDays} 天" else "尚无记录",
                    style = AppTypography.LabelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(AppSpacing.Sm))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCell(
                    label = "总局数",
                    value = "${summary.totalRuns}",
                    modifier = Modifier.weight(1f)
                )
                StatCell(
                    label = "单局最高",
                    value = "${summary.bestScore}",
                    highlight = true,
                    modifier = Modifier.weight(1f)
                )
                StatCell(
                    label = "累计得分",
                    value = "${summary.totalScore}",
                    modifier = Modifier.weight(1f)
                )
                StatCell(
                    label = "平均准确率",
                    value = "${summary.averageAccuracyPercent}%",
                    modifier = Modifier.weight(1f)
                )
            }
            if (state.totalRuns > 0) {
                Spacer(modifier = Modifier.height(AppSpacing.Xs))
                Text(
                    text = "累计答对 ${summary.answerRateLabel} 题",
                    style = AppTypography.LabelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = AppTypography.NumericHud,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
        Text(
            text = label,
            style = AppTypography.LabelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

// ─────────────────────────────── 趋势 ───────────────────────────────

/**
 * 最近若干局的得分柱状图（无第三方图表库：12 根柱子用 Box 画就够，
 * 引一个图表库只为一个 sparkline 不划算）。
 */
@Composable
private fun TrendCard(trend: List<Int>, best: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppSpacing.Corner.Md),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(AppSpacing.Md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.TrendingUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(AppSpacing.Xs))
                Text(
                    text = "近期走势",
                    style = AppTypography.LabelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "最近 ${trend.size} 局 · 最高 $best",
                    style = AppTypography.LabelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(AppSpacing.Sm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val safeMax = best.coerceAtLeast(1)
                trend.forEachIndexed { index, score ->
                    val ratio = (score.toFloat() / safeMax).coerceIn(0.08f, 1f)
                    val isLatest = index == trend.lastIndex
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height((56 * ratio).dp)
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(
                                if (isLatest) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                }
                            )
                    )
                }
            }
        }
    }
}

// ─────────────────────────────── 四档纪录 ───────────────────────────────

@Composable
private fun RecordStripCard(records: List<ScoreRecord>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppSpacing.Corner.Md),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = AppSpacing.Md)) {
            Row(
                modifier = Modifier.padding(horizontal = AppSpacing.Md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.EmojiEvents,
                    contentDescription = null,
                    tint = MedalColors.GoldGradient.first(),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(AppSpacing.Xs))
                Text(
                    text = "个人纪录",
                    style = AppTypography.LabelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(AppSpacing.Sm))
            LazyRow(
                contentPadding = PaddingValues(horizontal = AppSpacing.Md),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
            ) {
                items(items = records, key = { "${it.mode.rawValue}_${it.difficulty.rawValue}" }) { record ->
                    RecordCell(record)
                }
            }
        }
    }
}

@Composable
private fun RecordCell(record: ScoreRecord) {
    val best = record.best
    Column(
        modifier = Modifier
            .width(104.dp)
            .clip(RoundedCornerShape(AppSpacing.Corner.Sm))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = AppSpacing.Sm, vertical = AppSpacing.Xs)
    ) {
        Text(
            text = record.label,
            style = AppTypography.LabelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(AppSpacing.Xxs))
        Text(
            text = best?.let { "${it.score}" } ?: "--",
            style = AppTypography.NumericTimer,
            color = if (best != null) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Text(
            text = if (record.runs > 0) "${record.runs} 局" else "未挑战",
            style = AppTypography.LabelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            maxLines = 1
        )
    }
}

// ─────────────────────────────── 筛选 ───────────────────────────────

@Composable
private fun FilterSection(
    state: PersonalScoreUiState,
    onModeSelected: (ScoreModeFilter) -> Unit,
    onDifficultySelected: (ScoreDifficultyFilter) -> Unit,
    onReset: () -> Unit
) {
    Column {
        BlyyTabRow(
            tabs = ScoreModeFilter.entries.map { it.label },
            selectedIndex = state.modeFilter.ordinal,
            onTabSelected = { index -> onModeSelected(ScoreModeFilter.entries[index]) }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = AppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
        ) {
            ScoreDifficultyFilter.entries.forEach { filter ->
                BlyyChip(
                    label = filter.label,
                    selected = state.difficultyFilter == filter,
                    onClick = { onDifficultySelected(filter) }
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${state.filteredRuns} / ${state.totalRuns} 局",
                style = AppTypography.LabelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.hasActiveFilter) {
                Spacer(modifier = Modifier.width(AppSpacing.Xs))
                BlyyChip(label = "重置", selected = false, onClick = onReset)
            }
        }
    }
}

// ─────────────────────────────── 榜单行 ───────────────────────────────

@Composable
private fun RankRow(
    ranked: RankedScore,
    isPersonalBest: Boolean,
    onDelete: () -> Unit
) {
    val run = ranked.run
    val rank = ranked.rank
    val rankColors = when (rank) {
        1 -> MedalColors.GoldGradient
        2 -> MedalColors.SilverGradient
        3 -> MedalColors.BronzeGradient
        else -> listOf(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.surfaceVariant
        )
    }
    val isTop3 = rank <= 3
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppSpacing.Corner.Md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = when {
            isPersonalBest -> BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            )
            isTop3 -> BorderStroke(1.dp, rankColors.first().copy(alpha = 0.4f))
            else -> null
        },
        elevation = CardDefaults.cardElevation(defaultElevation = if (isTop3) 2.dp else 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
        ) {
            // 名次徽标（前三金/银/铜，其余中性）
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(rankColors)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$rank",
                    style = AppTypography.NumericHud,
                    // 奖牌色为浅色底，白字对比度不足，用深色保证可读（同旧板卡片的处理）
                    color = if (isTop3) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
                ) {
                    Text(
                        text = run.mode.label,
                        style = AppTypography.BodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    DifficultyTag(hard = run.difficulty.rawValue == "HARD")
                    if (isPersonalBest) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Star,
                                contentDescription = "个人纪录",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "纪录",
                                style = AppTypography.LabelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(AppSpacing.Xxs))
                Text(
                    text = buildString {
                        append("${run.correctAnswers}/${run.totalQuestions} 题")
                        append(" · 准确率 ${run.accuracyPercent}%")
                        if (run.hintsUsed > 0) append(" · 提示 ${run.hintsUsed}")
                        if (run.skipped > 0) append(" · 跳过 ${run.skipped}")
                        append(" · ${dateFormat.format(Date(run.timestamp))}")
                    },
                    style = AppTypography.LabelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${run.score}",
                    style = AppTypography.NumericTimer,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "分",
                    style = AppTypography.LabelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = "删除这条成绩",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                )
            }
        }
    }
}

@Composable
private fun DifficultyTag(hard: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(AppSpacing.Corner.Sm))
            .background(
                if (hard) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.secondaryContainer
            )
            .padding(horizontal = AppSpacing.Xxs, vertical = 1.dp)
    ) {
        Text(
            text = if (hard) "困难" else "简单",
            style = AppTypography.LabelSmall,
            color = if (hard) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

// ─────────────────────────────── 空态 ───────────────────────────────

@Composable
private fun EmptyBlock(onStartGame: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.Xxxl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.EmojiEvents,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(AppSpacing.Sm))
        Text(
            text = "还没有成绩",
            style = AppTypography.TitleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(AppSpacing.Xxs))
        Text(
            text = "完成一局「看图识舰娘」或「听音识舰娘」，成绩会自动出现在这里",
            style = AppTypography.BodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(AppSpacing.Md))
        FilledTonalButton(onClick = onStartGame) {
            Text("去答题")
        }
    }
}

@Composable
private fun FilteredEmptyBlock(onReset: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.Xxl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "当前筛选下没有成绩",
            style = AppTypography.TitleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(AppSpacing.Xxs))
        Text(
            text = "换个模式或难度看看",
            style = AppTypography.BodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(AppSpacing.Md))
        FilledTonalButton(onClick = onReset) {
            Text("重置筛选")
        }
    }
}
