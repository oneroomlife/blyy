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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Leaderboard
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurlane.blyy.data.model.LeaderboardCategory
import com.azurlane.blyy.data.model.LeaderboardEntry
import com.azurlane.blyy.ui.components.BlyyHaptic
import com.azurlane.blyy.ui.components.BlyyScreenScaffold
import com.azurlane.blyy.ui.components.BlyySkeletonKind
import com.azurlane.blyy.ui.components.BlyyStateScaffold
import com.azurlane.blyy.ui.components.BlyyTabRow
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.components.rememberBlyyHaptics
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.MedalColors
import com.azurlane.blyy.viewmodel.LeaderboardViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LeaderboardScreen(
    onBack: () -> Unit,
    onNavigateToAssistantConfig: () -> Unit = {},
    viewModel: LeaderboardViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val userUid by viewModel.userUid.collectAsStateWithLifecycle()
    val userServer by viewModel.userServer.collectAsStateWithLifecycle()
    val haptic = rememberBlyyHaptics()

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    BlyyScreenScaffold(
        topBar = {
            BlyyTopBar(
                title = "积分排行榜",
                subtitle = "全服排行",
                onBackClick = onBack,
                actions = {
                    IconButton(onClick = {
                        haptic(BlyyHaptic.Tick)
                        viewModel.refresh(force = true)
                    }) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "刷新",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 分类标签栏
            CategoryTabRow(
                selectedCategory = state.selectedCategory,
                onCategoryChange = viewModel::selectCategory
            )

            // 用户信息提示
            if (!viewModel.isUserInfoConfigured()) {
                UserInfoWarning(
                    uid = userUid,
                    server = userServer,
                    onNavigateToConfig = onNavigateToAssistantConfig,
                    modifier = Modifier.padding(horizontal = AppSpacing.Screen.Horizontal, vertical = AppSpacing.Sm)
                )
            }

            val entries = when (state.selectedCategory) {
                LeaderboardCategory.IMAGE_EASY -> state.leaderboardData.image_easy
                LeaderboardCategory.IMAGE_HARD -> state.leaderboardData.image_hard
                LeaderboardCategory.VOICE_EASY -> state.leaderboardData.voice_easy
                LeaderboardCategory.VOICE_HARD -> state.leaderboardData.voice_hard
            }

            // 四态渲染：有数据优先展示（刷新中顶部细 loading 条），无数据时 loading/error/empty
            BlyyStateScaffold(
                isLoading = state.isLoading,
                error = state.error,
                hasContent = entries.isNotEmpty(),
                skeleton = BlyySkeletonKind.List(itemCount = 8),
                emptyIcon = Icons.Rounded.Leaderboard,
                emptyTitle = "暂无排行数据",
                emptyDescription = "完成游戏后从历史记录上传成绩",
                onRetry = { viewModel.refresh(force = true) },
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = AppSpacing.Screen.Horizontal,
                        vertical = AppSpacing.Sm
                    ),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
                ) {
                    // 强制刷新时在列表顶部显示 loading 指示器
                    if (state.isLoading) {
                        item(key = "loading_indicator", contentType = "loading") {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }
                    itemsIndexed(
                        items = entries,
                        key = { _, entry -> "${entry.uid}-${entry.score}-${entry.timestamp}" },
                        contentType = { _, _ -> "rank_card" }
                    ) { index, entry ->
                        LeaderboardRankCard(
                            rank = index + 1,
                            entry = entry,
                            isCurrentUser = entry.uid == userUid
                        )
                    }
                    item(key = "bottom_spacer", contentType = "spacer") {
                        Spacer(modifier = Modifier.height(AppSpacing.Xxl))
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryTabRow(
    selectedCategory: LeaderboardCategory,
    onCategoryChange: (LeaderboardCategory) -> Unit
) {
    val categories = LeaderboardCategory.entries
    BlyyTabRow(
        tabs = categories.map { it.displayName },
        selectedIndex = selectedCategory.ordinal,
        onTabSelected = { index -> onCategoryChange(categories[index]) }
    )
}

@Composable
private fun UserInfoWarning(
    uid: String,
    server: String,
    onNavigateToConfig: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppSpacing.Corner.Md),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(AppSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
        ) {
            Icon(
                Icons.Rounded.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "请先配置 UID 和服务器",
                    style = AppTypography.LabelLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    "当前 UID: ${uid.ifBlank { "未设置" }} | 服务器: ${server.ifBlank { "未设置" }}",
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                )
            }
            FilledTonalButton(
                onClick = onNavigateToConfig,
                contentPadding = PaddingValues(horizontal = AppSpacing.Md, vertical = AppSpacing.Xs)
            ) {
                Text("去配置", style = AppTypography.LabelMedium)
            }
        }
    }
}

/**
 * 排行榜条目卡片 —— 精致条形布局
 *
 * 重新设计为紧凑的单卡条形结构，保留全部信息字段：
 * - 主行：排名徽标 + 指挥官名称（预存储 nickname）+ 得分
 * - 副行：服务器 / 准确率 / 答题数 / 时间（以细分标签形式横向排布）
 *
 * nickname 在上传阶段即通过"碧蓝航线助手"解析并预存储于 [LeaderboardEntry.nickname]，
 * 此处直接读取展示，无需额外网络查询。nickname 为空时回退为"指挥官"。
 * 不展示任何形式的 UID。
 *
 * V2 §7.7：名次/得分走等宽数字族（读数不跳动）；我的排名行 = PrimaryVeil 底 + 左 3dp 光条。
 */
@Composable
private fun LeaderboardRankCard(
    rank: Int,
    entry: LeaderboardEntry,
    isCurrentUser: Boolean,
    modifier: Modifier = Modifier
) {
    // 前三名渐变色（金/银/铜）统一归口 MedalColors，其余使用主题中性色
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

    val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    // 直接读取预存储的 nickname，为空时回退为"指挥官"（兼容历史数据）
    val commanderName = entry.nickname.ifBlank { "指挥官" }

    // 卡片容器：紧凑条形；当前用户 = PrimaryVeil 底（V2 §7.7），前三名保留奖牌色细边
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppSpacing.Corner.Md),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentUser) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = when {
            isCurrentUser -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
            isTop3 -> BorderStroke(1.dp, rankColors.first().copy(alpha = 0.4f))
            else -> null
        },
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isTop3) 2.dp else 1.dp
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 我的排名行左侧光条（V2 §7.7：长列表中一眼找到自己）
            if (isCurrentUser) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(56.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm)
            ) {
                // ── 主行：排名 + 指挥官 + 得分 ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
                ) {
                    // 排名徽标
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(rankColors)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            rank.toString(),
                            style = AppTypography.NumericHud,
                            fontWeight = FontWeight.Bold,
                            // 前3名金/银/铜背景为浅色，白字对比度不足 4.5:1，改用深色确保 WCAG AA
                            color = if (isTop3) Color.Black else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // 指挥官名称（直接读取预存储的 nickname）
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
                    ) {
                        Text(
                            text = commanderName,
                            style = AppTypography.BodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isCurrentUser) {
                            Text(
                                "(我)",
                                style = AppTypography.LabelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // 得分（右侧主数据，等宽读数）
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xxs)
                    ) {
                        Text(
                            text = "${entry.score}",
                            style = AppTypography.NumericTimer,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "分",
                            style = AppTypography.LabelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = AppSpacing.Xxs)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.Xs))

                // ── 副行：服务器 / 准确率 / 答题 / 时间（细分标签） ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
                ) {
                    InfoChip(
                        text = entry.server.ifBlank { "未知" },
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1
                    )
                    InfoChip(text = "准确率 ${(entry.accuracy * 100).toInt()}%")
                    InfoChip(text = "${entry.correctAnswers}/${entry.totalQuestions}")
                    // 时间标签：靠右，使用更弱化的颜色
                    Text(
                        text = sdf.format(Date(entry.timestamp)),
                        style = AppTypography.LabelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                        textAlign = TextAlign.End
                    )
                }
            }
        }
    }
}

/** 紧凑信息标签：圆角胶囊形，弱化背景，用于副行展示细分数据 */
@Composable
private fun InfoChip(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(AppSpacing.Corner.Sm))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = AppSpacing.Sm, vertical = AppSpacing.Xxs)
    ) {
        Text(
            text = text,
            style = AppTypography.LabelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}
