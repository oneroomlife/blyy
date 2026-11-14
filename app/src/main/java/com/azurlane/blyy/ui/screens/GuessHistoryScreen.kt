package com.azurlane.blyy.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurlane.blyy.data.model.GuessHistory
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyConfirmDialog
import com.azurlane.blyy.ui.components.BlyyFormDialog
import com.azurlane.blyy.ui.components.BlyySegmentOption
import com.azurlane.blyy.ui.components.BlyySegmentedControl
import com.azurlane.blyy.ui.components.BlyyStateScaffold
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.viewmodel.GuessHistoryViewModel
import com.azurlane.blyy.viewmodel.HistoryFilter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GuessHistoryScreen(
    onBack: () -> Unit,
    onScoreBoard: () -> Unit = {},
    viewModel: GuessHistoryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    AdaptiveScreenBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                BlyyTopBar(
                    title = "历史记录",
                    subtitle = "查看游戏战绩",
                    onBackClick = onBack,
                    actions = {
                        if (!state.isMultiSelectMode) {
                            IconButton(onClick = onScoreBoard) {
                                Icon(
                                    Icons.Rounded.EmojiEvents,
                                    contentDescription = "我的成绩榜",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                )

                // 筛选栏
                BlyySegmentedControl(
                    options = listOf(
                        BlyySegmentOption(HistoryFilter.ALL, "全部"),
                        BlyySegmentOption(HistoryFilter.IMAGE, "看图识舰娘"),
                        BlyySegmentOption(HistoryFilter.VOICE, "听音识舰娘")
                    ),
                    selected = state.filter,
                    onSelect = viewModel::setFilter,
                    modifier = Modifier.padding(
                        horizontal = AppSpacing.Screen.Horizontal,
                        vertical = AppSpacing.Sm
                    )
                )

                // 内容区：四态渲染（记录来自 Room，无网络错误态）
                BlyyStateScaffold(
                    isLoading = state.isLoading,
                    error = null,
                    hasContent = state.records.isNotEmpty(),
                    emptyIcon = Icons.Rounded.History,
                    emptyTitle = "暂无历史记录",
                    emptyDescription = "完成一局游戏后，战绩将显示在这里",
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
                        items(items = state.records, key = { it.id }) { record ->
                            HistoryRecordCard(
                                record = record,
                                isSelected = record.id in state.selectedIds,
                                isMultiSelectMode = state.isMultiSelectMode,
                                onClick = {
                                    if (state.isMultiSelectMode) {
                                        viewModel.toggleSelection(record.id)
                                    } else {
                                        viewModel.showDetail(record)
                                    }
                                },
                                onLongClick = {
                                    if (!state.isMultiSelectMode) {
                                        viewModel.enterMultiSelectMode()
                                    }
                                    viewModel.toggleSelection(record.id)
                                },
                                onDelete = { viewModel.deleteRecord(record.id) }
                            )
                        }
                        item { Spacer(modifier = Modifier.height(AppSpacing.Xxl)) }
                    }
                }
            }

            // 多选操作栏
            AnimatedVisibility(
                visible = state.isMultiSelectMode,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                MultiSelectActionBar(
                    selectedCount = state.selectedIds.size,
                    onSelectAll = viewModel::selectAll,
                    onDelete = viewModel::deleteSelected,
                    onCancel = viewModel::exitMultiSelectMode
                )
            }

            // 详情对话框
            state.detailRecord?.let { record ->
                HistoryDetailDialog(
                    record = record,
                    onDismiss = { viewModel.closeDetail() }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRecordCard(
    record: GuessHistory,
    isSelected: Boolean,
    isMultiSelectMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val isImageMode = record.mode == "IMAGE"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(AppSpacing.Corner.Md),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 多选复选框
            if (isMultiSelectMode) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.width(AppSpacing.Sm))
            }

            // 模式图标
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isImageMode) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.tertiaryContainer
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isImageMode) Icons.Rounded.Image else Icons.Rounded.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isImageMode) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    }
                )
            }

            Spacer(modifier = Modifier.width(AppSpacing.Sm))

            // 主要信息
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
                ) {
                    Text(
                        text = if (isImageMode) "看图识舰娘" else "听音识舰娘",
                        style = AppTypography.TitleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    // 难度标签
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(AppSpacing.Corner.Full))
                            .background(
                                if (record.difficulty == "HARD") {
                                    MaterialTheme.colorScheme.errorContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                }
                            )
                            .padding(horizontal = AppSpacing.Xs, vertical = AppSpacing.Xxs)
                    ) {
                        Text(
                            text = if (record.difficulty == "HARD") "困难" else "简单",
                            style = AppTypography.LabelSmall,
                            color = if (record.difficulty == "HARD") {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(AppSpacing.Xxs))
                // 统计信息行
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
                ) {
                    Text(
                        text = "${record.correctAnswers}/${record.totalQuestions}题",
                        style = AppTypography.LabelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "·",
                        style = AppTypography.LabelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        text = "准确率${(record.accuracy * 100).toInt()}%",
                        style = AppTypography.LabelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "·",
                        style = AppTypography.LabelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        text = dateFormat.format(Date(record.timestamp)),
                        style = AppTypography.LabelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                }
            }

            // 得分
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${record.totalScore}",
                    style = AppTypography.TitleLargeBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "分",
                    style = AppTypography.LabelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 删除按钮（非多选模式）
            if (!isMultiSelectMode) {
                Spacer(modifier = Modifier.width(AppSpacing.Xs))
                IconButton(onClick = onDelete, modifier = Modifier.minimumInteractiveComponentSize().size(32.dp)) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "删除",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun MultiSelectActionBar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "已选 $selectedCount 项",
                style = AppTypography.BodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                TextButton(onClick = onSelectAll) {
                    Icon(Icons.Rounded.SelectAll, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(AppSpacing.Xxs))
                    Text("全选")
                }
                TextButton(onClick = onDelete, enabled = selectedCount > 0) {
                    Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(AppSpacing.Xxs))
                    Text("删除")
                }
                TextButton(onClick = onCancel) {
                    Text("取消")
                }
            }
        }
    }
}

@Composable
private fun HistoryDetailDialog(
    record: GuessHistory,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val isImageMode = record.mode == "IMAGE"

    BlyyFormDialog(
        onDismiss = onDismiss,
        title = if (isImageMode) "看图识舰娘" else "听音识舰娘",
        icon = {
            Icon(
                imageVector = if (isImageMode) Icons.Rounded.Image else Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                DetailRow("难度", if (record.difficulty == "HARD") "困难" else "简单")
                DetailRow("游戏时间", dateFormat.format(Date(record.timestamp)))
                HorizontalDivider()
                DetailRow("总题数", "${record.totalQuestions}")
                DetailRow("答对题数", "${record.correctAnswers}")
                DetailRow("跳过题数", "${record.skippedQuestions}")
                DetailRow("使用提示", "${record.hintsUsedTotal} 次")
                HorizontalDivider()
                DetailRow("总得分", "${record.totalScore}", highlight = true)
                DetailRow("满分", "${record.totalPossibleScore}")
                DetailRow("准确率", "${(record.accuracy * 100).toInt()}%")
                DetailRow("平均得分", String.format(Locale.getDefault(), "%.1f", record.averageScore))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String, highlight: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = AppTypography.BodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = AppTypography.BodyMedium,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}
