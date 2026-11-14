package com.azurlane.blyy.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurlane.blyy.data.backup.BackupFileInfo
import com.azurlane.blyy.data.backup.BackupManager
import com.azurlane.blyy.data.backup.BackupPayload
import com.azurlane.blyy.ui.components.BlyyConfirmDialog
import com.azurlane.blyy.ui.components.BlyyListItem
import com.azurlane.blyy.ui.components.BlyyScreenScaffold
import com.azurlane.blyy.ui.components.BlyySectionPanel
import com.azurlane.blyy.ui.components.BlyySettingsRow
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.viewmodel.BackupRestoreUiState
import com.azurlane.blyy.viewmodel.BackupRestoreViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份与恢复。
 *
 * 产品判断：这是"换机不丢"的唯一出口，所以入口要显眼（侧栏 + 设置页），
 * 但**恢复必须二次确认**——它是覆盖型操作，而且用户可能选错文件。
 *
 * 交互分工：
 * - 「备份到文件」走 SAF（用户自己选位置，最容易跨设备）；
 * - 「备份到本机」写私有目录，配合「自动备份」做滚动快照（防误删/回滚）；
 * - 「从文件恢复」先解析出摘要再弹确认框，避免拿错文件直接覆盖。
 */
@Composable
fun BackupRestoreScreen(
    onBack: () -> Unit,
    viewModel: BackupRestoreViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 删除备份前二次确认：图标就在「恢复」旁边，误触代价是丢一份回滚点
    var pendingDelete by remember { mutableStateOf<BackupFileInfo?>(null) }

    // 导出：让用户选保存位置（云盘/下载目录/数据线目录都行）
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(BackupManager.MIME_TYPE)
    ) { uri ->
        if (uri != null) viewModel.exportTo(uri)
    }

    // 导入：只做"选择 + 解析 + 预览"，真正写入在确认框里
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.loadImportPreview(uri)
    }

    // 每次点击现算文件名：同一次会话里连点两次备份不会因为同名被 SAF 覆盖
    fun suggestedFileName(): String =
        "blyy-backup-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.json"

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.clearMessage()
        }
    }

    BlyyScreenScaffold(
        topBar = {
            BlyyTopBar(
                title = "备份与恢复",
                subtitle = "誓约 · 成绩 · 设置",
                onBackClick = onBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            if (state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            OverviewCard(state = state)

            Spacer(modifier = Modifier.height(AppSpacing.Md))

            BlyySectionPanel(
                title = "备份",
                icon = Icons.Rounded.Backup,
                accentColor = MaterialTheme.colorScheme.primary,
                separateItems = true
            ) {
                BlyyListItem(
                    leadingIcon = Icons.Rounded.CloudUpload,
                    title = "备份到文件",
                    subtitle = "选择保存位置（网盘/下载目录/数据线），用于换机迁移",
                    onClick = { createDocumentLauncher.launch(suggestedFileName()) },
                    asCard = true
                )
                BlyyListItem(
                    leadingIcon = Icons.Rounded.Download,
                    title = "备份到本机",
                    subtitle = "写入应用私有目录，保留最近 5 份，可用于回滚",
                    onClick = { viewModel.backupToLocal() },
                    asCard = true
                )
                BlyySettingsRow(
                    icon = Icons.Rounded.Schedule,
                    title = "自动备份",
                    description = if (state.autoBackupEnabled) {
                        "每天首次启动时自动备份一份到本机"
                    } else {
                        "已关闭：不会自动生成备份"
                    },
                    checked = state.autoBackupEnabled,
                    onCheckedChange = viewModel::setAutoBackupEnabled
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.Md))

            BlyySectionPanel(
                title = "恢复",
                icon = Icons.Rounded.Restore,
                accentColor = MaterialTheme.colorScheme.secondary,
                separateItems = true
            ) {
                BlyyListItem(
                    leadingIcon = Icons.Rounded.Restore,
                    title = "从文件恢复",
                    subtitle = "选择备份 JSON，确认后覆盖当前的誓约/成绩/设置",
                    onClick = { openDocumentLauncher.launch(arrayOf("*/*")) },
                    asCard = true
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.Md))

            LocalBackupsSection(
                backups = state.localBackups,
                enabled = !state.busy,
                onPreview = viewModel::loadLocalPreview,
                onDelete = { info -> pendingDelete = info }
            )

            Spacer(modifier = Modifier.height(AppSpacing.Xl))
        }
    }

    // 恢复确认：展示来源版本/时间/条目数，让"覆盖"这件事可预期
    state.pendingImport?.let { payload ->
        RestoreConfirmDialog(
            payload = payload,
            onConfirm = viewModel::confirmImport,
            onDismiss = viewModel::dismissImport
        )
    }

    pendingDelete?.let { info ->
        BlyyConfirmDialog(
            title = "删除这份备份？",
            message = "将删除本机备份「${info.file.name}」，该操作不可撤销。",
            confirmText = "删除",
            dismissText = "取消",
            onConfirm = {
                viewModel.deleteLocal(info)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
            isDestructive = true,
            icon = Icons.Rounded.DeleteOutline
        )
    }
}

// ─────────────────────────────── 概览 ───────────────────────────────

@Composable
private fun OverviewCard(state: BackupRestoreUiState) {
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = AppSpacing.Screen.Horizontal,
                vertical = AppSpacing.Sm
            ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(AppSpacing.Corner.Md),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(AppSpacing.Md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(AppSpacing.Xs))
                Text(
                    text = "备份包含：誓约/收藏 · 答题成绩 · 外观与功能设置",
                    style = AppTypography.LabelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.height(AppSpacing.Xs))
            Text(
                text = "不包含：AI API Key、聊天会话、啾信预设、游戏 UID（这些在本机加密存放，跨设备无法解密）",
                style = AppTypography.LabelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(AppSpacing.Sm))
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Lg)) {
                Column {
                    Text(
                        text = "上次备份",
                        style = AppTypography.LabelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (state.lastBackupAt > 0) {
                            formatter.format(Date(state.lastBackupAt))
                        } else {
                            "从未备份"
                        },
                        style = AppTypography.LabelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Column {
                    Text(
                        text = "上次恢复",
                        style = AppTypography.LabelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (state.lastRestoreAt > 0) {
                            formatter.format(Date(state.lastRestoreAt))
                        } else {
                            "从未恢复"
                        },
                        style = AppTypography.LabelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

// ─────────────────────────────── 本机备份列表 ───────────────────────────────

@Composable
private fun LocalBackupsSection(
    backups: List<BackupFileInfo>,
    enabled: Boolean,
    onPreview: (BackupFileInfo) -> Unit,
    onDelete: (BackupFileInfo) -> Unit
) {
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    BlyySectionPanel(
        title = "本机备份（${backups.size}）",
        icon = Icons.Rounded.Schedule,
        accentColor = MaterialTheme.colorScheme.tertiary,
        separateItems = true
    ) {
        if (backups.isEmpty()) {
            Text(
                text = "还没有本机备份。点上方「备份到本机」或开启自动备份。",
                style = AppTypography.BodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(AppSpacing.Md)
            )
        } else {
            backups.forEach { info ->
                LocalBackupRow(
                    info = info,
                    timeLabel = formatter.format(Date(info.createdAt)),
                    enabled = enabled,
                    onRestore = { onPreview(info) },
                    onDelete = { onDelete(info) }
                )
            }
        }
    }
}

@Composable
private fun LocalBackupRow(
    info: BackupFileInfo,
    timeLabel: String,
    enabled: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = timeLabel,
                style = AppTypography.LabelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append(info.summary)
                    append(" · ${info.sizeBytes / 1024} KB")
                    if (info.appVersion.isNotBlank()) append(" · v${info.appVersion}")
                },
                style = AppTypography.LabelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = onRestore, enabled = enabled) { Text("恢复") }
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = "删除该备份",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

// ─────────────────────────────── 恢复确认 ───────────────────────────────

@Composable
private fun RestoreConfirmDialog(
    payload: BackupPayload,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val message = buildString {
        appendLine("将用这份备份覆盖当前数据：")
        appendLine()
        appendLine("· 备份时间：${formatter.format(Date(payload.createdAt))}")
        appendLine("· 备份来源：v${payload.appVersion.ifBlank { "未知" }}")
        appendLine("· 内容：${payload.summary()}")
        appendLine()
        append("当前未备份的数据不会被删除，但同名项会被覆盖；成绩按局去重、不会重复累加。")
    }
    BlyyConfirmDialog(
        title = "确认恢复？",
        message = message,
        confirmText = "覆盖恢复",
        dismissText = "取消",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        isDestructive = true,
        icon = Icons.Rounded.Restore
    )
}
