package com.azurlane.blyy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Leaderboard
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.azurlane.blyy.data.model.LeaderboardCategory
import com.azurlane.blyy.data.model.LeaderboardEntry
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyAnimatedEmptyState
import com.azurlane.blyy.ui.components.BlyyErrorState
import com.azurlane.blyy.ui.components.BlyyTabRow
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.viewmodel.LeaderboardViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaderboardScreen(
    onBack: () -> Unit,
    onNavigateToAssistantConfig: () -> Unit = {},
    viewModel: LeaderboardViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val userUid by viewModel.userUid.collectAsStateWithLifecycle()
    val userServer by viewModel.userServer.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    AdaptiveScreenBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                BlyyTopBar(
                    title = "积分排行榜",
                    subtitle = "全服排行",
                    onBackClick = onBack,
                    actions = {
                        IconButton(onClick = { viewModel.refresh(force = true) }) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = "刷新",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                )

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

                // 排行榜列表
                val entries = when (state.selectedCategory) {
                    LeaderboardCategory.IMAGE_EASY -> state.leaderboardData.image_easy
                    LeaderboardCategory.IMAGE_HARD -> state.leaderboardData.image_hard
                    LeaderboardCategory.VOICE_EASY -> state.leaderboardData.voice_easy
                    LeaderboardCategory.VOICE_HARD -> state.leaderboardData.voice_hard
                }
                // 有数据时优先展示数据（即使正在 loading），无数据时才显示 loading/error
                when {
                    entries.isNotEmpty() -> {
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
                                item {
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
                                key = { _, entry -> "${entry.uid}-${entry.score}-${entry.timestamp}" }
                            ) { index, entry ->
                                LeaderboardRankCard(
                                    rank = index + 1,
                                    entry = entry,
                                    isCurrentUser = entry.uid == userUid
                                )
                            }
                            item { Spacer(modifier = Modifier.height(AppSpacing.Xxl)) }
                        }
                    }
                    state.isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    state.error != null -> {
                        ErrorView(
                            error = state.error!!,
                            onRetry = { viewModel.refresh(force = true) }
                        )
                    }
                    else -> {
                        EmptyLeaderboardView()
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
 */
@Composable
private fun LeaderboardRankCard(
    rank: Int,
    entry: LeaderboardEntry,
    isCurrentUser: Boolean,
    modifier: Modifier = Modifier
) {
    // 前三名渐变色（金/银/铜），其余使用主题中性色
    val rankColors = when (rank) {
        1 -> listOf(Color(0xFFFFD700), Color(0xFFFFA500))
        2 -> listOf(Color(0xFFC0C0C0), Color(0xFFA0A0A0))
        3 -> listOf(Color(0xFFCD7F32), Color(0xFFB87333))
        else -> listOf(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.surfaceVariant
        )
    }
    val isTop3 = rank <= 3

    val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    // 直接读取预存储的 nickname，为空时回退为"指挥官"（兼容历史数据）
    val commanderName = entry.nickname.ifBlank { "指挥官" }

    // 卡片容器：紧凑条形 + 当前用户高亮边框
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppSpacing.Corner.Md),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentUser) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isCurrentUser) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
        } else if (isTop3) {
            BorderStroke(1.dp, rankColors.first().copy(alpha = 0.4f))
        } else null,
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isTop3) 2.dp else 1.dp
        )
    ) {
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
                        style = AppTypography.LabelLarge,
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

                // 得分（右侧主数据）
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xxs)
                ) {
                    Text(
                        text = "${entry.score}",
                        style = AppTypography.TitleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "分",
                        style = AppTypography.LabelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp)
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

@Composable
private fun EmptyLeaderboardView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        BlyyAnimatedEmptyState(
            visible = true,
            icon = Icons.Rounded.Leaderboard,
            title = "暂无排行数据",
            description = "完成游戏后从历史记录上传成绩"
        )
    }
}

@Composable
private fun ErrorView(
    error: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.Screen.Horizontal),
        contentAlignment = Alignment.Center
    ) {
        BlyyErrorState(message = error, onRetry = onRetry)
    }
}
