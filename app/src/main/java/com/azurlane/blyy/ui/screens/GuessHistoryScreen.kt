package com.azurlane.blyy.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Leaderboard
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.azurlane.blyy.data.model.GuessHistory
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyConfirmDialog
import com.azurlane.blyy.ui.components.BlyyEmptyState
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.theme.AppColors
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.ui.theme.LocalUiStyle
import com.azurlane.blyy.viewmodel.GuessHistoryViewModel
import com.azurlane.blyy.viewmodel.HistoryFilter
import com.azurlane.blyy.viewmodel.RecordUploadStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuessHistoryScreen(
    onBack: () -> Unit,
    onLeaderboard: () -> Unit = {},
    onNavigateToAssistantConfig: () -> Unit = {},
    viewModel: GuessHistoryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val uploadStatuses by viewModel.uploadStatuses.collectAsState()
    val userUid by viewModel.userUid.collectAsState()
    val userServer by viewModel.userServer.collectAsState()
    val configPromptRecord by viewModel.configPromptRecord.collectAsState()
    val nicknamePromptRecord by viewModel.nicknamePromptRecord.collectAsState()
    val suggestedNickname by viewModel.suggestedNickname.collectAsState()

    AdaptiveScreenBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                BlyyTopBar(
                    title = "历史记录",
                    subtitle = "查看游戏战绩",
                    onBackClick = onBack,
                    actions = {
                        if (!state.isMultiSelectMode) {
                            IconButton(onClick = onLeaderboard) {
                                Icon(
                                    Icons.Rounded.Leaderboard,
                                    contentDescription = "排行榜",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                )

                // 筛选栏
                FilterRow(
                    currentFilter = state.filter,
                    onFilterChange = viewModel::setFilter
                )

                // 内容区
                if (state.isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (state.isEmpty) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        BlyyEmptyState(
                            icon = Icons.Rounded.History,
                            title = "暂无历史记录",
                            description = "完成一局游戏后，战绩将显示在这里"
                        )
                    }
                } else {
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
                                uploadStatus = uploadStatuses[record.id],
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
                                onUpload = { viewModel.uploadRecord(record) },
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
                    uploadStatus = uploadStatuses[record.id],
                    isUserInfoConfigured = userUid.isNotBlank() && userServer.isNotBlank(),
                    onDismiss = {
                        viewModel.closeDetail()
                        viewModel.clearUploadStatus(record.id)
                    },
                    onUpload = { viewModel.uploadRecord(record) },
                    onRetry = { viewModel.retryUpload(record) }
                )
            }

            // 未配置 UID/服务器提示弹窗
            configPromptRecord?.let {
                ConfigPromptDialog(
                    onDismiss = { viewModel.dismissConfigPrompt() },
                    onNavigateToConfig = {
                        viewModel.dismissConfigPrompt()
                        onNavigateToAssistantConfig()
                    }
                )
            }

            // 无法查询玩家信息时的昵称输入弹窗
            nicknamePromptRecord?.let { record ->
                NicknamePromptDialog(
                    suggestedNickname = suggestedNickname,
                    onDismiss = { viewModel.dismissNicknamePrompt() },
                    onConfirm = { nickname ->
                        viewModel.uploadWithNickname(record, nickname)
                    }
                )
            }
        }
    }
}

@Composable
private fun FilterRow(
    currentFilter: HistoryFilter,
    onFilterChange: (HistoryFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Screen.Horizontal, vertical = AppSpacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
    ) {
        FilterChipItem(
            label = "全部",
            selected = currentFilter == HistoryFilter.ALL,
            onClick = { onFilterChange(HistoryFilter.ALL) }
        )
        FilterChipItem(
            label = "看图识舰娘",
            selected = currentFilter == HistoryFilter.IMAGE,
            onClick = { onFilterChange(HistoryFilter.IMAGE) }
        )
        FilterChipItem(
            label = "听音识舰娘",
            selected = currentFilter == HistoryFilter.VOICE,
            onClick = { onFilterChange(HistoryFilter.VOICE) }
        )
    }
}

@Composable
private fun FilterChipItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = AppTypography.LabelMedium) },
        shape = RoundedCornerShape(AppSpacing.Corner.Full)
    )
}

@Composable
private fun HistoryRecordCard(
    record: GuessHistory,
    isSelected: Boolean,
    isMultiSelectMode: Boolean,
    uploadStatus: RecordUploadStatus? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onUpload: () -> Unit = {},
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val isImageMode = record.mode == "IMAGE"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(AppSpacing.Corner.Md),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
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

            // 上传按钮 + 删除按钮（非多选模式）
            if (!isMultiSelectMode) {
                Spacer(modifier = Modifier.width(AppSpacing.Xs))
                // 独立上传按钮：根据上传状态显示不同图标（唯一状态指示器，不再重复显示 Chip）
                IconButton(
                    onClick = {
                        if (uploadStatus !is RecordUploadStatus.Uploading &&
                            uploadStatus !is RecordUploadStatus.Success
                        ) {
                            onUpload()
                        }
                    },
                    modifier = Modifier.size(32.dp),
                    enabled = uploadStatus !is RecordUploadStatus.Uploading &&
                            uploadStatus !is RecordUploadStatus.Success
                ) {
                    when (uploadStatus) {
                        is RecordUploadStatus.Uploading -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        is RecordUploadStatus.Success -> {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = "已上传",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        is RecordUploadStatus.Failed -> {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = "重试上传",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                        null -> {
                            Icon(
                                Icons.Rounded.CloudUpload,
                                contentDescription = "上传排行",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
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
    uploadStatus: RecordUploadStatus? = null,
    isUserInfoConfigured: Boolean = false,
    onDismiss: () -> Unit,
    onUpload: () -> Unit = {},
    onRetry: () -> Unit = {}
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val isImageMode = record.mode == "IMAGE"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isImageMode) Icons.Rounded.Image else Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(AppSpacing.Sm))
                Text(if (isImageMode) "看图识舰娘" else "听音识舰娘")
            }
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

                // 上传状态反馈
                uploadStatus?.let { status ->
                    HorizontalDivider()
                    when (status) {
                        is RecordUploadStatus.Uploading -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(AppSpacing.Sm))
                                Text(
                                    "正在上传成绩...",
                                    style = AppTypography.BodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        is RecordUploadStatus.Success -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(AppSpacing.Sm))
                                Text(
                                    "上传成功！",
                                    style = AppTypography.BodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        is RecordUploadStatus.Failed -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.Error,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(AppSpacing.Sm))
                                Text(
                                    "上传失败: ${status.message}",
                                    style = AppTypography.BodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                // 上传前提提示
                if (!isUserInfoConfigured) {
                    HorizontalDivider()
                    Text(
                        "未配置 UID 和服务器，点击上传后将引导前往配置",
                        style = AppTypography.BodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                when (uploadStatus) {
                    is RecordUploadStatus.Failed -> {
                        // 失败时显示重试按钮
                        FilledTonalButton(
                            onClick = onRetry
                        ) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(AppSpacing.Xs))
                            Text("重试上传")
                        }
                    }
                    is RecordUploadStatus.Uploading -> {
                        FilledTonalButton(
                            onClick = {},
                            enabled = false
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(AppSpacing.Xs))
                            Text("上传中...")
                        }
                    }
                    else -> {
                        // 未上传或已成功时显示上传按钮（未配置时点击会弹出配置提示）
                        FilledTonalButton(
                            onClick = onUpload,
                            enabled = uploadStatus !is RecordUploadStatus.Success
                        ) {
                            Icon(
                                Icons.Rounded.CloudUpload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(AppSpacing.Xs))
                            Text(if (uploadStatus is RecordUploadStatus.Success) "已上传" else "上传排行")
                        }
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
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

/**
 * 未配置 UID/服务器时的提示弹窗。
 * 引导用户前往助手配置页填写 UID 和服务器。
 */
@Composable
private fun ConfigPromptDialog(
    onDismiss: () -> Unit,
    onNavigateToConfig: () -> Unit
) {
    BlyyConfirmDialog(
        title = "未配置 UID 和服务器",
        message = "上传排行榜需要先在「助手配置」中填写游戏内 UID 和服务器，" +
            "用于查询玩家昵称并标识你的成绩。\n\n是否前往配置？",
        confirmText = "去配置",
        dismissText = "取消",
        onConfirm = onNavigateToConfig,
        onDismiss = onDismiss
    )
}

/**
 * 无法通过 UID/服务器查询到玩家信息时的昵称输入弹窗。
 * 用户输入自定义昵称后保存并上传，后续上传将自动复用该昵称。
 */
@Composable
private fun NicknamePromptDialog(
    suggestedNickname: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var nickname by remember { mutableStateOf(suggestedNickname) }
    val isError = nickname.isBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Rounded.DriveFileRenameOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("无法查询到玩家信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                Text(
                    "通过当前 UID 和服务器无法查询到玩家信息，" +
                        "请输入自定义昵称用于上传排行榜。保存后后续上传将自动复用该昵称。",
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("输入自定义昵称") },
                    isError = isError,
                    supportingText = {
                        if (isError) {
                            Text("昵称不能为空", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    shape = RoundedCornerShape(AppSpacing.Corner.Sm),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(nickname) },
                enabled = !isError
            ) {
                Text("保存并上传")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
