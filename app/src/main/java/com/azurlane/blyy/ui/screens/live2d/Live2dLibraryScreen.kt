package com.azurlane.blyy.ui.screens.live2d

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.WarningAmber
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
import androidx.compose.ui.graphics.lerp
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
import com.azurlane.blyy.ui.components.BlyyConfirmDialog
import com.azurlane.blyy.ui.components.BlyyFormDialog
import com.azurlane.blyy.ui.components.BlyySearchBar
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.components.BlyyHaptic
import com.azurlane.blyy.ui.components.rememberBlyyHaptics
import com.azurlane.blyy.ui.theme.AppColors
import com.azurlane.blyy.ui.theme.AppElevation
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.ui.theme.LocalSemanticColors
import com.azurlane.blyy.util.Live2dImporter
import com.azurlane.blyy.util.Live2dModelInfo
import com.azurlane.blyy.util.Live2dSelection
import com.azurlane.blyy.viewmodel.Live2dIntent
import com.azurlane.blyy.viewmodel.Live2dViewModel
import java.util.Locale

/**
 * Live2D 皮肤库：浏览、导入（SAF 文件夹 / zip 压缩包）、查看、重命名、删除。
 *
 * 导入流程设计（人性化原则）：
 *  - 空态即引导：三行说明 + 醒目导入按钮，不让用户猜文件该放哪
 *  - 导入面板提供两种来源：文件夹（SAF 目录树）与 zip 压缩包，自动识别嵌套目录
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
    onOpenViewer: (modelId: String, displayName: String) -> Unit,
    onOpenGameBundles: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: Live2dViewModel = hiltViewModel()
    // 皮肤名注册表更新时（换装N → 真实皮肤名）触发卡片/详情自动刷新
    val displayNames by viewModel.displayNames.collectAsStateWithLifecycle()
    val displayNameOf: (String) -> String = { id -> displayNames[id] ?: viewModel.displayNameFor(id) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptic = rememberBlyyHaptics()

    var searchQuery by remember { mutableStateOf("") }
    var showImportSheet by remember { mutableStateOf(false) }
    var detailModel by remember { mutableStateOf<Live2dModelInfo?>(null) }
    var deleteTarget by remember { mutableStateOf<Live2dModelInfo?>(null) }
    var renameTarget by remember { mutableStateOf<Live2dModelInfo?>(null) }
    var renameText by remember { mutableStateOf("") }
    // 批量删除二次确认（展示数量 + 占用空间，避免误触大规模删库）
    var confirmBatchDelete by remember { mutableStateOf(false) }
    val selectionMode = state.selecting

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

    // 批量删除结果横幅 8 秒后自动消失
    LaunchedEffect(state.deleteResultText) {
        if (state.deleteResultText != null) {
            kotlinx.coroutines.delay(8000)
            viewModel.onIntent(Live2dIntent.ConsumeDeleteResult)
        }
    }

    // 结果横幅 8 秒后自动消失
    LaunchedEffect(state.importResult) {
        if (state.importResult != null) {
            kotlinx.coroutines.delay(8000)
            viewModel.onIntent(Live2dIntent.ConsumeImportResult)
        }
    }

    // 从资源包页/查看器返回时刷新模型库（导入完成后列表立即可见，无需手动点刷新）
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.onIntent(Live2dIntent.Refresh)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val filteredModels = remember(state.models, searchQuery) {
        if (searchQuery.isBlank()) state.models
        else state.models.filter {
            it.id.contains(searchQuery, ignoreCase = true) ||
                displayNameOf(it.id).contains(searchQuery, ignoreCase = true)
        }
    }

    AdaptiveScreenBackground {
        Column(Modifier.fillMaxSize()) {
            if (selectionMode) {
                // 多选模式顶栏：退出 / 已选统计 / 全选 / 删除
                BlyyTopBar(
                    title = if (state.selectedIds.isEmpty()) "选择模型" else "已选 ${state.selectedIds.size} 个",
                    subtitle = if (state.selectedIds.isEmpty()) {
                        "点按卡片选择要删除的模型"
                    } else {
                        "共 ${formatSize(state.selectedBytes)}"
                    },
                    onBackClick = {
                        haptic(BlyyHaptic.Tick)
                        viewModel.onIntent(Live2dIntent.StopSelecting)
                    },
                    actions = {
                        val allSelected = Live2dSelection.isAllSelected(
                            state.selectedIds, filteredModels.map { it.id }
                        )
                        IconButton(
                            onClick = {
                                haptic(BlyyHaptic.Tick)
                                viewModel.onIntent(
                                    Live2dIntent.SelectAll(
                                        filteredModels.map { it.id }.toSet(),
                                        !allSelected
                                    )
                                )
                            },
                            enabled = filteredModels.isNotEmpty()
                        ) {
                            Icon(
                                if (allSelected) Icons.Rounded.Deselect else Icons.Rounded.SelectAll,
                                contentDescription = if (allSelected) "取消全选" else "全选",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                haptic(BlyyHaptic.LongPress)
                                confirmBatchDelete = true
                            },
                            enabled = state.selectedIds.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Rounded.DeleteOutline,
                                contentDescription = "删除所选",
                                tint = if (state.selectedIds.isEmpty()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    }
                )
            } else {
                BlyyTopBar(
                    title = "Live2D 皮肤",
                    subtitle = if (state.models.isEmpty()) "支持 Cubism 3/4 模型" else "已导入 ${state.models.size} 个模型",
                    onBackClick = onBack,
                    actions = {
                        // 模型数 ≥2 才提供多选（单个模型没必要进入多选模式）
                        if (state.models.size >= 2) {
                            IconButton(onClick = {
                                haptic(BlyyHaptic.Tick)
                                viewModel.onIntent(Live2dIntent.StartSelecting())
                            }) {
                                Icon(
                                    Icons.Rounded.Checklist,
                                    contentDescription = "多选管理",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(21.dp)
                                )
                            }
                        }
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
                        IconButton(onClick = {
                            haptic(BlyyHaptic.Tick)
                            onOpenGameBundles()
                        }) {
                            Icon(
                                Icons.Rounded.CloudDownload,
                                contentDescription = "从游戏资源包导入",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                )
            }

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

            // 批量删除结果横幅（成功/部分失败；独立于导入结果，语义更准确）
            state.deleteResultText?.let { text ->
                DeleteResultBanner(
                    text = text,
                    hasProblem = text.contains("删除失败"),
                    onDismiss = { viewModel.onIntent(Live2dIntent.ConsumeDeleteResult) }
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
                            val selected = model.id in state.selectedIds
                            Live2dModelCard(
                                model = model,
                                displayName = displayNameOf(model.id),
                                selecting = selectionMode,
                                selected = selected,
                                onClick = {
                                    if (selectionMode) {
                                        // 多选模式：点按 = 勾选/取消，不进入查看器（避免误开模型）
                                        haptic(BlyyHaptic.Tick)
                                        viewModel.onIntent(Live2dIntent.ToggleSelected(model.id))
                                    } else {
                                        haptic(BlyyHaptic.Tick)
                                        onOpenViewer(model.id, displayNameOf(model.id))
                                    }
                                },
                                onLongClick = {
                                    if (selectionMode) {
                                        // 多选模式下长按 = 快捷单选/取消（长按进入批量对单个模型也顺手）
                                        haptic(BlyyHaptic.Tick)
                                        viewModel.onIntent(Live2dIntent.ToggleSelected(model.id))
                                    } else {
                                        haptic(BlyyHaptic.LongPress)
                                        detailModel = model
                                    }
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
                    description = "选择包含 Cubism 模型的文件夹（*.model3.json；游戏资源包请走「从游戏资源包导入」）",
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
                    description = "支持 .zip 压缩包，自动解包并识别其中的全部 Cubism 模型",
                    enabled = state.importProgress == null,
                    onClick = {
                        showImportSheet = false
                        zipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                    }
                )
                Spacer(Modifier.height(AppSpacing.Md))
                ImportOptionCard(
                    icon = Icons.Rounded.CloudDownload,
                    title = "从游戏资源包导入",
                    description = "打开「游戏资源导入」：Shizuku 扫描 / 本地文件导入，自动识别 Live2D、SD 小人、动态皮肤与立绘",
                    enabled = state.importProgress == null,
                    onClick = {
                        showImportSheet = false
                        onOpenGameBundles()
                    }
                )
            }
        }
    }

    // ── 模型详情 + 操作面板 ──
    detailModel?.let { model ->
        ModalBottomSheet(onDismissRequest = { detailModel = null }) {
            ModelDetailSheet(
                model = model,
                displayName = displayNameOf(model.id),
                onPlay = {
                    detailModel = null
                    onOpenViewer(model.id, displayNameOf(model.id))
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
        BlyyConfirmDialog(
            title = "删除模型？",
            message = "「${displayNameOf(model.id)}」（${formatSize(model.sizeBytes)}）将被永久删除，此操作不可恢复。",
            confirmText = "删除",
            onConfirm = {
                viewModel.onIntent(Live2dIntent.Delete(model.id))
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
            isDestructive = true,
            icon = Icons.Rounded.DeleteOutline
        )
    }

    // ── 批量删除确认 ──
    if (confirmBatchDelete) {
        val count = state.selectedIds.size
        BlyyConfirmDialog(
            title = "删除所选的 $count 个模型？",
            message = "共 ${formatSize(state.selectedBytes)} 将被永久删除，此操作不可恢复。",
            confirmText = "删除 $count 个",
            onConfirm = {
                haptic(BlyyHaptic.LongPress)
                viewModel.onIntent(Live2dIntent.DeleteSelected(state.selectedIds.toSet()))
                confirmBatchDelete = false
            },
            onDismiss = { confirmBatchDelete = false },
            isDestructive = true,
            icon = Icons.Rounded.DeleteOutline
        )
    }

    // ── 重命名 ──
    renameTarget?.let { model ->
        BlyyFormDialog(
            onDismiss = { renameTarget = null },
            title = "重命名模型",
            icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
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

/**
 * 次级文字色：暗色下 onSurfaceVariant（#94A8BE）在深蓝卡片（#1A3050）上对比仅 ~5.4:1，
 * 9-12sp 小字号观感偏暗 → 向 onSurface 混合 45% 提亮至 ~7.6:1；浅色保持原色。
 * 按 onSurface/onSurfaceVariant 动态混合，Command Center 与 Classic 两套主题自动适配。
 */
@Composable
private fun secondaryTextColor(): Color = if (LocalIsDark.current) {
    lerp(
        MaterialTheme.colorScheme.onSurfaceVariant,
        MaterialTheme.colorScheme.onSurface,
        0.45f
    )
} else {
    MaterialTheme.colorScheme.onSurfaceVariant
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Live2dModelCard(
    model: Live2dModelInfo,
    displayName: String,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val isDark = LocalIsDark.current
    val accent = MaterialTheme.colorScheme.primary
    // 选中态用主色描边加粗 + 轻微色染，未选中保持原设计语言
    val borderColor = when {
        selected -> accent
        isDark -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(AppSpacing.Corner.Lg),
        color = if (selected) {
            accent.copy(alpha = if (isDark) 0.16f else 0.10f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f)
        },
        // color 加了 alpha 后 contentColorFor 无法按值匹配，必须显式指定内容色，
        // 否则标题回落到外层继承的暗灰内容色（暗色下可读性差）
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = AppElevation.Level2,
        // 暗色下深灰卡片与近黑背景对比低，亮描边勾出轮廓（浅色淡描边统一设计语言）
        border = BorderStroke(
            width = if (selected) AppSpacing.Border.Thick else AppSpacing.Border.Thin,
            color = borderColor
        )
    ) {
        Column {
            // 封面：捕获缩略图 > 内置舰娘头像 > 渐变占位
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.85f)
                    .background(
                        Brush.verticalGradient(AppColors.Live2DViewer.CoverGradient)
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

                // 多选勾选圈（固定在封面左上，与右上动作角标不冲突）
                if (selecting) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(AppSpacing.Sm)
                            .size(24.dp)
                            .clip(RoundedCornerShape(AppSpacing.Corner.Full))
                            // 已选：主色实心 + 白勾；未选：半透明底 + 白描边空圈
                            .background(if (selected) accent else AppColors.Live2DViewer.SelectedScrim)
                            .border(
                                width = AppSpacing.Border.Thick,
                                color = if (selected) accent else Color.White.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(AppSpacing.Corner.Full)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = "已选中",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // 动作数量角标
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(AppSpacing.Sm),
                    shape = RoundedCornerShape(AppSpacing.Corner.Full),
                    color = AppColors.Live2DViewer.ChipScrim,
                    // 角标底色与封面渐变同色系，加细亮描边避免胶囊边界消失
                    border = BorderStroke(AppSpacing.Border.Thin, Color.White.copy(alpha = 0.22f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Animation,
                            contentDescription = null,
                            tint = AppColors.Live2DViewer.AccentLight,
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
                    color = secondaryTextColor()
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
    val isDark = LocalIsDark.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Screen.Horizontal, vertical = AppSpacing.Sm),
        shape = RoundedCornerShape(AppSpacing.Corner.Lg),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isDark) 0.97f else 0.95f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = AppElevation.Level2,
        border = BorderStroke(
            width = AppSpacing.Border.Thin,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.55f else 0.4f)
        )
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
                    color = secondaryTextColor()
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
                IconButton(onClick = it, modifier = Modifier.minimumInteractiveComponentSize().size(28.dp)) {
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
/**
 * 批量删除结果横幅。
 *
 * 与导入结果横幅分开：删除是"用户主动破坏性操作"，成功时应给中性/成功观感，
 * 仅在存在失败明细时才用警告容器，避免把"已删除 15 个"渲染成报错。
 */
@Composable
private fun DeleteResultBanner(
    text: String,
    hasProblem: Boolean,
    onDismiss: () -> Unit
) {
    val semantics = LocalSemanticColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Screen.Horizontal, vertical = AppSpacing.Sm),
        shape = RoundedCornerShape(AppSpacing.Corner.Lg),
        color = if (hasProblem) semantics.warningContainer else semantics.successContainer,
        tonalElevation = AppElevation.Level1
    ) {
        Row(
            modifier = Modifier.padding(
                start = AppSpacing.Md,
                top = AppSpacing.Sm,
                bottom = AppSpacing.Sm,
                end = AppSpacing.Xs
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (hasProblem) Icons.Rounded.WarningAmber else Icons.Rounded.Check,
                contentDescription = null,
                tint = if (hasProblem) semantics.onWarningContainer else semantics.onSuccessContainer,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.size(AppSpacing.Sm))
            Text(
                text = text,
                style = AppTypography.CaptionMedium,
                color = if (hasProblem) semantics.onWarningContainer else semantics.onSuccessContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "关闭",
                    tint = if (hasProblem) semantics.onWarningContainer else semantics.onSuccessContainer,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun ImportOptionCard(
    icon: ImageVector,
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val isDark = LocalIsDark.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.Corner.Lg))
            .combinedClickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(AppSpacing.Corner.Lg),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isDark) 0.97f else 0.9f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = AppElevation.Level1,
        border = BorderStroke(
            width = AppSpacing.Border.Thin,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.55f else 0.4f)
        )
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
                    color = secondaryTextColor()
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
            color = secondaryTextColor(),
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
            color = secondaryTextColor()
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
