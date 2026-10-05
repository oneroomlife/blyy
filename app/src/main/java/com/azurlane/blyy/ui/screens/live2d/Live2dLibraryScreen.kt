package com.azurlane.blyy.ui.screens.live2d

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyAnimatedEmptyState
import com.azurlane.blyy.ui.components.BlyyButton
import com.azurlane.blyy.ui.components.BlyyButtonVariant
import com.azurlane.blyy.ui.components.BlyySearchBar
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.components.BlyyHaptic
import com.azurlane.blyy.ui.components.rememberBlyyHaptics
import com.azurlane.blyy.ui.theme.AppColors
import com.azurlane.blyy.ui.theme.AppElevation
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.LocalSemanticColors
import com.azurlane.blyy.util.Live2dImporter
import com.azurlane.blyy.util.Live2dModelInfo
import com.azurlane.blyy.viewmodel.Live2dIntent
import com.azurlane.blyy.viewmodel.Live2dViewModel
import java.io.File
import java.util.Locale

/**
 * Live2D 皮肤库：浏览、导入（SAF 文件夹 / zip 压缩包）、查看、重命名、删除。
 *
 * 导入流程设计（人性化原则）：
 *  - 空态即引导：三行说明 + 醒目导入按钮，不让用户猜文件该放哪
 *  - 导入面板提供两种来源（文件夹/压缩包）+ 免导入的直拷路径（一键复制）
 *  - 导入全程进度可见（阶段/当前模型/文件计数），可随时取消
 *  - 结果汇总横幅（新增/更新/失败明细），失败不静默
 */
@OptIn(
    ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)
@Composable
fun Live2dLibraryScreen(
    onBack: () -> Unit,
    onOpenViewer: (modelId: String, displayName: String) -> Unit
) {
    val context = LocalContext.current
    val viewModel: Live2dViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptic = rememberBlyyHaptics()

    var searchQuery by remember { mutableStateOf("") }
    var showImportSheet by remember { mutableStateOf(false) }
    var detailModel by remember { mutableStateOf<Live2dModelInfo?>(null) }
    var deleteTarget by remember { mutableStateOf<Live2dModelInfo?>(null) }
    var renameTarget by remember { mutableStateOf<Live2dModelInfo?>(null) }
    var renameText by remember { mutableStateOf("") }

    // ── SAF 启动器：文件夹导入 ──
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.onIntent(Live2dIntent.ImportFolder(uri))
        }
    }

    // ── SAF 启动器：zip 压缩包导入 ──
    val zipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.onIntent(Live2dIntent.ImportZip(uri))
        }
    }

    // 结果横幅 8 秒后自动消失
    LaunchedEffect(state.importResult) {
        if (state.importResult != null) {
            kotlinx.coroutines.delay(8000)
            viewModel.onIntent(Live2dIntent.ConsumeImportResult)
        }
    }

    val filteredModels = remember(state.models, searchQuery) {
        if (searchQuery.isBlank()) state.models
        else state.models.filter {
            it.id.contains(searchQuery, ignoreCase = true) ||
                viewModel.displayNameFor(it.id).contains(searchQuery, ignoreCase = true)
        }
    }

    val storageHintPath = remember {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        File(base, "live2d").absolutePath
    }

    AdaptiveScreenBackground {
        Column(Modifier.fillMaxSize()) {
            BlyyTopBar(
                title = "Live2D 皮肤",
                subtitle = if (state.models.isEmpty()) "支持 Cubism 3/4 模型" else "已导入 ${state.models.size} 个模型",
                onBackClick = onBack,
                actions = {
                    IconButton(onClick = {
                        haptic(BlyyHaptic.Tick)
                        viewModel.onIntent(Live2dIntent.Refresh)
                    }) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "刷新",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = {
                        haptic(BlyyHaptic.Tick)
                        showImportSheet = true
                    }) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = "导入模型",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            )

            // 搜索（模型较多时才有意义）
            if (state.models.size >= 6) {
                BlyySearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    modifier = Modifier.padding(
                        horizontal = AppSpacing.Screen.Horizontal,
                        vertical = AppSpacing.Sm
                    ),
                    placeholder = "搜索模型名称…",
                    onClear = { searchQuery = "" }
                )
            }

            // 导入进行中：进度卡片（替代内容区顶部，进度可见可取消）
            state.importProgress?.let { progress ->
                ImportProgressCard(
                    progress = progress,
                    onCancel = { viewModel.onIntent(Live2dIntent.CancelImport) }
                )
            }

            // 导入结果横幅
            state.importResult?.let { result ->
                ImportResultBanner(
                    result = result,
                    onDismiss = { viewModel.onIntent(Live2dIntent.ConsumeImportResult) }
                )
            }

            // 无效目录提示（扫描发现但不完整的目录）
            if (state.invalidDirs.isNotEmpty() && state.importResult == null) {
                ImportResultBanner(
                    result = Live2dImporter.ImportResult(
                        imported = emptyList(),
                        updated = emptyList(),
                        failed = state.invalidDirs.take(3),
                        cancelled = false
                    ),
                    onDismiss = null,
                    neutralTitle = "以下目录无法识别为模型"
                )
            }

            when {
                // 加载态
                state.loading && state.models.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }

                // 错误态（扫描失败）
                state.scanError != null && state.models.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Rounded.WarningAmber,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(Modifier.height(AppSpacing.Md))
                            Text(
                                "扫描模型库失败：${state.scanError}",
                                style = AppTypography.BodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // 空态（即引导）
                state.models.isEmpty() -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = AppSpacing.Screen.Horizontal),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        BlyyAnimatedEmptyState(
                            visible = true,
                            icon = Icons.Rounded.ViewInAr,
                            title = "还没有 Live2D 模型",
                            description = "导入包含 *.model3.json 的模型文件夹或压缩包，\n自动识别并归档到本地模型库",
                            actionLabel = "导入模型",
                            onAction = { showImportSheet = true }
                        )
                        Spacer(Modifier.height(AppSpacing.Lg))
                        StorageHintRow(
                            hintPath = storageHintPath,
                            onCopy = {
                                copyToClipboard(context, storageHintPath)
                            }
                        )
                    }
                }

                // 模型网格
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = AppSpacing.Screen.Horizontal,
                            vertical = AppSpacing.Sm
                        ),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
                    ) {
                        items(filteredModels, key = { it.id }) { model ->
                            Live2dModelCard(
                                model = model,
                                displayName = viewModel.displayNameFor(model.id),
                                onClick = {
                                    haptic(BlyyHaptic.Tick)
                                    onOpenViewer(model.id, viewModel.displayNameFor(model.id))
                                },
                                onLongClick = {
                                    haptic(BlyyHaptic.LongPress)
                                    detailModel = model
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // ── 导入面板 ──
    if (showImportSheet) {
        ModalBottomSheet(onDismissRequest = { showImportSheet = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Screen.Horizontal)
                    .padding(bottom = AppSpacing.Xl)
            ) {
                Text(
                    "导入 Live2D 模型",
                    style = AppTypography.TitleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "自动识别嵌套目录中的 *.model3.json，同名模型重新导入视为更新",
                    style = AppTypography.CaptionSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = AppSpacing.Xs, bottom = AppSpacing.Md)
                )

                ImportOptionCard(
                    icon = Icons.Rounded.FolderOpen,
                    title = "从文件夹导入",
                    description = "选择包含一个或多个模型的文件夹（可直接选中整个 live2d 根目录）",
                    enabled = state.importProgress == null,
                    onClick = {
                        showImportSheet = false
                        folderLauncher.launch(null)
                    }
                )
                Spacer(Modifier.height(AppSpacing.Md))
                ImportOptionCard(
                    icon = Icons.Rounded.Archive,
                    title = "从压缩包导入",
                    description = "支持 .zip 压缩包，自动解包并识别其中的全部模型",
                    enabled = state.importProgress == null,
                    onClick = {
                        showImportSheet = false
                        zipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                    }
                )

                Box(
                    Modifier
                        .padding(vertical = AppSpacing.Lg)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                )

                Text(
                    "免导入方式（电脑 USB / adb）",
                    style = AppTypography.LabelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                StorageHintRow(
                    hintPath = storageHintPath,
                    onCopy = { copyToClipboard(context, storageHintPath) },
                    modifier = Modifier.padding(top = AppSpacing.Sm)
                )
            }
        }
    }

    // ── 模型详情 + 操作面板 ──
    detailModel?.let { model ->
        ModalBottomSheet(onDismissRequest = { detailModel = null }) {
            ModelDetailSheet(
                model = model,
                displayName = viewModel.displayNameFor(model.id),
                onPlay = {
                    detailModel = null
                    onOpenViewer(model.id, viewModel.displayNameFor(model.id))
                },
                onRename = {
                    renameTarget = model
                    renameText = model.id
                    detailModel = null
                },
                onDelete = {
                    deleteTarget = model
                    detailModel = null
                }
            )
        }
    }

    // ── 删除确认 ──
    deleteTarget?.let { model ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("删除模型？") },
            text = {
                Text(
                    "「${viewModel.displayNameFor(model.id)}」（${formatSize(model.sizeBytes)}）将被永久删除，此操作不可恢复。",
                    style = AppTypography.BodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onIntent(Live2dIntent.Delete(model.id))
                    deleteTarget = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }

    // ── 重命名 ──
    renameTarget?.let { model ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
            title = { Text("重命名模型") },
            text = {
                Column {
                    Text(
                        "修改模型目录名（模型内部引用均为相对路径，重命名安全）",
                        style = AppTypography.CaptionSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = AppSpacing.Md)
                    )
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        label = { Text("模型名称") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onIntent(Live2dIntent.Rename(model.id, renameText))
                        renameTarget = null
                    },
                    enabled = renameText.isNotBlank()
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            }
        )
    }
}

// ---------- 网格卡片 ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Live2dModelCard(
    model: Live2dModelInfo,
    displayName: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(AppSpacing.Corner.Lg),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
        tonalElevation = AppElevation.Level2
    ) {
        Column {
            // 封面：捕获缩略图 > 内置舰娘头像 > 渐变占位
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.85f)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                AppColors.Live2DViewer.BgDark,
                                Color(0xFF16233C)
                            )
                        )
                    )
            ) {
                when {
                    model.thumbFile != null -> AsyncImage(
                        model = model.thumbFile,
                        contentDescription = displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    model.avatarAsset != null -> AsyncImage(
                        model = "file:///android_asset/blhx_avatar/${model.avatarAsset}",
                        contentDescription = displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    else -> Icon(
                        Icons.Rounded.ViewInAr,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.35f),
                        modifier = Modifier
                            .size(52.dp)
                            .align(Alignment.Center)
                    )
                }

                // 动作数量角标
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(AppSpacing.Sm),
                    shape = RoundedCornerShape(AppSpacing.Corner.Full),
                    color = Color(0xCC0F1826)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Animation,
                            contentDescription = null,
                            tint = Color(0xFF8EC9F0),
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            "${model.totalMotions}",
                            style = AppTypography.LabelSmall,
                            color = Color.White
                        )
                    }
                }
            }

            Column(Modifier.padding(AppSpacing.Md)) {
                Text(
                    text = displayName,
                    style = AppTypography.LabelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatSize(model.sizeBytes),
                    style = AppTypography.CaptionSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------- 导入进度 / 结果 ----------

@Composable
private fun ImportProgressCard(
    progress: Live2dImporter.ImportProgress,
    onCancel: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Screen.Horizontal, vertical = AppSpacing.Sm),
        shape = RoundedCornerShape(AppSpacing.Corner.Lg),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
        tonalElevation = AppElevation.Level2
    ) {
        Column(Modifier.padding(AppSpacing.Md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (progress.phase) {
                        Live2dImporter.Phase.SCANNING -> "正在扫描模型…"
                        Live2dImporter.Phase.EXTRACTING -> "正在解压压缩包…"
                        Live2dImporter.Phase.COPYING -> "正在导入模型…"
                        Live2dImporter.Phase.FINALIZING -> "即将完成…"
                    },
                    style = AppTypography.LabelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onCancel) { Text("取消") }
            }
            if (progress.phase == Live2dImporter.Phase.COPYING && progress.totalFiles > 0) {
                LinearProgressIndicator(
                    progress = {
                        progress.copiedFiles.toFloat() / progress.totalFiles.toFloat()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(AppSpacing.Xs))
                Text(
                    text = buildString {
                        progress.currentModel?.let { append(it); append(" · ") }
                        append("${progress.copiedFiles}/${progress.totalFiles} 个文件")
                    },
                    style = AppTypography.CaptionSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ImportResultBanner(
    result: Live2dImporter.ImportResult,
    onDismiss: (() -> Unit)?,
    neutralTitle: String? = null
) {
    val hasProblem = result.failed.isNotEmpty() || result.cancelled
    val semantics = LocalSemanticColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Screen.Horizontal, vertical = AppSpacing.Sm),
        shape = RoundedCornerShape(AppSpacing.Corner.Lg),
        color = when {
            result.cancelled || (neutralTitle != null) -> MaterialTheme.colorScheme.surfaceContainerHigh
            hasProblem -> semantics.warningContainer
            else -> semantics.successContainer
        },
        tonalElevation = AppElevation.Level1
    ) {
        Row(
            modifier = Modifier.padding(start = AppSpacing.Md, top = AppSpacing.Sm, bottom = AppSpacing.Sm, end = AppSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = neutralTitle
                    ?.let { title -> listOf(title).plus(result.failed).joinToString("；") }
                    ?: result.summaryText(),
                style = AppTypography.CaptionMedium,
                color = when {
                    result.cancelled || (neutralTitle != null) -> MaterialTheme.colorScheme.onSurface
                    hasProblem -> semantics.onWarningContainer
                    else -> semantics.onSuccessContainer
                },
                modifier = Modifier.weight(1f)
            )
            onDismiss?.let {
                IconButton(onClick = it, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "关闭",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun Live2dImporter.ImportResult.summaryText(): String {
    val parts = mutableListOf<String>()
    if (imported.isNotEmpty()) parts.add("新增 ${imported.size}")
    if (updated.isNotEmpty()) parts.add("更新 ${updated.size}")
    if (failed.isNotEmpty()) parts.add("失败 ${failed.size}")
    if (cancelled) return "导入已取消"
    if (parts.isEmpty()) return "未发现可导入的模型"
    val head = parts.joinToString("，")
    val detail = failed.firstOrNull()?.let { "（$it）" } ?: ""
    return "$head$detail"
}

// ---------- 导入面板组件 ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImportOptionCard(
    icon: ImageVector,
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.Corner.Lg))
            .combinedClickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(AppSpacing.Corner.Lg),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
        tonalElevation = AppElevation.Level1
    ) {
        Row(
            modifier = Modifier.padding(AppSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
        ) {
            Surface(
                shape = RoundedCornerShape(AppSpacing.Corner.Md),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(AppSpacing.Md)
                        .size(26.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = AppTypography.LabelLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    style = AppTypography.CaptionSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StorageHintRow(
    hintPath: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppSpacing.Corner.Lg),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = hintPath,
                style = AppTypography.CaptionSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = "复制路径",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ---------- 详情面板 ----------

@Composable
private fun ModelDetailSheet(
    model: Live2dModelInfo,
    displayName: String,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Screen.Horizontal)
            .padding(bottom = AppSpacing.Xl)
    ) {
        Text(
            displayName,
            style = AppTypography.TitleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            model.id,
            style = AppTypography.CaptionSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = AppSpacing.Md)
        )

        DetailRow("动作", "${model.totalMotions} 个（${model.motionGroups.size} 组）")
        if (model.hasExpressions) {
            DetailRow("表情", "${model.expressions.size} 个")
        }
        DetailRow("贴图", "${model.textures.size} 张")
        DetailRow("物理", if (model.physicsFile != null) "有（摆动模拟）" else "无")
        DetailRow("文件", "${model.fileCount} 个 · ${formatSize(model.sizeBytes)}")
        model.version?.let { DetailRow("Cubism 版本", it) }

        Spacer(Modifier.height(AppSpacing.Lg))
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)) {
            BlyyButton(
                text = "查看模型",
                onClick = onPlay,
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.PlayCircle
            )
            BlyyButton(
                text = "重命名",
                onClick = onRename,
                modifier = Modifier.weight(1f),
                variant = BlyyButtonVariant.Secondary,
                icon = Icons.Rounded.Edit
            )
            BlyyButton(
                text = "删除",
                onClick = onDelete,
                modifier = Modifier.weight(1f),
                variant = BlyyButtonVariant.Secondary,
                icon = Icons.Rounded.DeleteOutline
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = AppTypography.BodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = AppTypography.BodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

// ---------- 工具 ----------

private fun formatSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1 shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun copyToClipboard(context: android.content.Context, text: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("path", text))
    Toast.makeText(context, "路径已复制", Toast.LENGTH_SHORT).show()
}
