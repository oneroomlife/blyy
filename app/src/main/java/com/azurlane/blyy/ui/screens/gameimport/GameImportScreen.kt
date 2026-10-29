package com.azurlane.blyy.ui.screens.gameimport

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateMapOf
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
import com.azurlane.blyy.ui.components.BlyyCard
import com.azurlane.blyy.ui.components.BlyyCardElevation
import com.azurlane.blyy.ui.components.BlyySearchBar
import com.azurlane.blyy.ui.components.BlyyTabRow
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.theme.AppColors
import com.azurlane.blyy.ui.theme.AppElevation
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.ui.theme.LocalSemanticColors
import com.azurlane.blyy.util.gameimport.GameAssetInfo
import com.azurlane.blyy.util.gameimport.GameAssetKind
import com.azurlane.blyy.util.gameimport.GameBundleEntry
import com.azurlane.blyy.util.gameimport.ShellState
import com.azurlane.blyy.viewmodel.GameImportIntent
import com.azurlane.blyy.viewmodel.GameImportTab
import com.azurlane.blyy.viewmodel.GameImportViewModel
import kotlinx.coroutines.delay

/**
 * 游戏资源导入（独立功能）。
 *
 * 与 Live2D 皮肤库分离：本页面对「游戏资源包」负责到底——扫描、导入、状态、库管理与查看；
 * Live2D 皮肤库只保留本地模型文件夹 / zip 导入，两边共享同一份产物目录。
 *
 * 支持的资源类型（自动识别，无需用户指定）：
 * - `live2d` → Cubism 模型（复用既有提取器）
 * - `char` → Spine SD 小人
 * - `spinepainting` → Spine 动态皮肤
 * - `painting` → 静态立绘
 */
@OptIn(
    ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)
@Composable
fun GameImportScreen(
    onBack: () -> Unit,
    onOpenLive2d: (id: String, name: String) -> Unit,
    onOpenAsset: (kind: GameAssetKind, id: String, name: String) -> Unit,
    initialKind: GameAssetKind? = null
) {
    val viewModel: GameImportViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var detailAsset by remember { mutableStateOf<GameAssetInfo?>(null) }
    var deleteTarget by remember { mutableStateOf<GameAssetInfo?>(null) }
    var renameTarget by remember { mutableStateOf<GameAssetInfo?>(null) }
    var renameText by remember { mutableStateOf("") }

    LaunchedEffect(initialKind) {
        if (initialKind != null) viewModel.onIntent(GameImportIntent.SelectKind(initialKind))
    }

    // 提示横幅 8 秒自动收起
    LaunchedEffect(state.batchMessage) {
        if (state.batchMessage != null) {
            delay(8000)
            viewModel.onIntent(GameImportIntent.ConsumeMessage)
        }
    }

    val filteredEntries = remember(state.entries, state.kindFilter, searchQuery) {
        val base = state.filteredEntries
        if (searchQuery.isBlank()) base
        else base.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
                it.fileName.contains(searchQuery, ignoreCase = true) ||
                viewModel.displayNameOfEntry(it).contains(searchQuery, ignoreCase = true)
        }
    }

    AdaptiveScreenBackground {
        Column(Modifier.fillMaxSize()) {
            BlyyTopBar(
                title = "游戏资源导入",
                subtitle = if (state.entries.isEmpty()) {
                    "支持 Live2D / SD 小人 / 动态皮肤 / 静态立绘"
                } else {
                    "${state.importedCount}/${state.entries.size} 已导入 · 库内 ${state.totalAssets} 个资源"
                },
                onBackClick = onBack,
                actions = {
                    IconButton(
                        onClick = { viewModel.onIntent(GameImportIntent.Scan) },
                        enabled = !state.busy
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "重新扫描",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )

            BlyyTabRow(
                tabs = GameImportTab.entries.map { "${it.label}${countSuffix(state, it)}" },
                selectedIndex = GameImportTab.entries.indexOf(state.tab),
                onTabSelected = { index ->
                    viewModel.onIntent(GameImportIntent.SelectTab(GameImportTab.entries[index]))
                }
            )

            when (state.tab) {
                GameImportTab.BUNDLES -> BundlesTab(
                    state = state,
                    viewModel = viewModel,
                    entries = filteredEntries,
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                    onImport = { entry -> viewModel.onIntent(GameImportIntent.ImportEntry(entry)) },
                    onImportAll = { viewModel.onIntent(GameImportIntent.ImportPending) },
                    onOpen = { entry ->
                        val name = viewModel.displayNameOfEntry(entry)
                        if (entry.kind == GameAssetKind.LIVE2D) onOpenLive2d(entry.assetId, name)
                        else onOpenAsset(entry.kind, entry.assetId, name)
                    },
                    onSetSecretary = { entry ->
                        viewModel.onIntent(
                            GameImportIntent.SetAsSecretary(entry.kind, entry.assetId)
                        )
                    }
                )

                GameImportTab.LIBRARY -> LibraryTab(
                    state = state,
                    viewModel = viewModel,
                    onOpen = { asset ->
                        val name = viewModel.displayNameFor(asset)
                        if (asset.kind == GameAssetKind.LIVE2D) onOpenLive2d(asset.id, name)
                        else onOpenAsset(asset.kind, asset.id, name)
                    },
                    onDetail = { detailAsset = it },
                    onRequestRename = { asset ->
                        renameTarget = asset
                        renameText = asset.id
                    },
                    onGoBundles = { viewModel.onIntent(GameImportIntent.SelectTab(GameImportTab.BUNDLES)) }
                )
            }
        }
    }

    // ---- 资源详情 ----
    detailAsset?.let { asset ->
        ModalBottomSheet(onDismissRequest = { detailAsset = null }) {
            AssetDetailSheet(
                asset = asset,
                displayName = viewModel.displayNameFor(asset),
                sizeText = viewModel.formatSize(asset.sizeBytes),
                onOpen = {
                    detailAsset = null
                    val name = viewModel.displayNameFor(asset)
                    if (asset.kind == GameAssetKind.LIVE2D) onOpenLive2d(asset.id, name)
                    else onOpenAsset(asset.kind, asset.id, name)
                },
                onRename = {
                    renameTarget = asset
                    renameText = asset.id
                    detailAsset = null
                },
                onDelete = {
                    deleteTarget = asset
                    detailAsset = null
                }
            )
        }
    }

    // ---- 删除确认 ----
    deleteTarget?.let { asset ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("删除资源？") },
            text = {
                Text(
                    "「${viewModel.displayNameFor(asset)}」（${viewModel.formatSize(asset.sizeBytes)}）将被永久删除，" +
                        "游戏资源包本身不受影响，可随时重新导入。",
                    style = AppTypography.BodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onIntent(GameImportIntent.DeleteAsset(asset.kind, asset.id))
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

    // ---- 重命名 ----
    renameTarget?.let { asset ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
            title = { Text("重命名资源") },
            text = {
                Column {
                    Text(
                        "只改资源目录名，骨骼与图集内部引用均为相对路径，重命名安全。",
                        style = AppTypography.CaptionSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = AppSpacing.Md)
                    )
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        label = { Text("资源名称") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onIntent(GameImportIntent.RenameAsset(asset.kind, asset.id, renameText))
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

private fun countSuffix(state: GameImportViewModel.State, tab: GameImportTab): String = when (tab) {
    GameImportTab.BUNDLES -> if (state.entries.isEmpty()) "" else " ${state.entries.size}"
    GameImportTab.LIBRARY -> if (state.totalAssets == 0) "" else " ${state.totalAssets}"
}

// ---------------------------------------------------------------------------- 资源包分区

@Composable
private fun BundlesTab(
    state: GameImportViewModel.State,
    viewModel: GameImportViewModel,
    entries: List<GameBundleEntry>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onImport: (GameBundleEntry) -> Unit,
    onImportAll: () -> Unit,
    onOpen: (GameBundleEntry) -> Unit,
    onSetSecretary: (GameBundleEntry) -> Unit
) {
    val progress = state.progress
    // 「需要导入」= 从未导入过（NEW）或上次失败（FAILED）。
    // CHANGED 表示「已导入但源包/管线更新过」，属于可选更新，
    // 不能算进待导入数量——否则用户已经导入过的资源会一直显示导入按钮。
    val pendingInScope = state.filteredEntries.count {
        it.state == GameBundleEntry.State.NEW || it.state == GameBundleEntry.State.FAILED
    }
    // 可更新数量（单独提示，不占「导入」按钮名额）
    val updatableInScope = state.filteredEntries.count {
        it.state == GameBundleEntry.State.CHANGED
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = AppSpacing.Screen.Horizontal,
            end = AppSpacing.Screen.Horizontal,
            top = AppSpacing.Sm,
            bottom = AppSpacing.Screen.Bottom + 72.dp
        ),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
    ) {
        // Shizuku 提示只在「没跑起来 / 没授权」时出现：连接成功后这张卡片纯属噪音
        if (state.shellNeedsAttention) {
            item(key = "shell") { ShellStateCard(state, viewModel) }
        }
        state.deviceWarning?.let { warning ->
            item(key = "device-warning") { DeviceWarningCard(warning) }
        }

        if (progress != null) {
            item(key = "progress") {
                ImportProgressCard(
                    progress = progress,
                    onCancel = { viewModel.onIntent(GameImportIntent.CancelImport) }
                )
            }
        }

        state.batchMessage?.let { message ->
            item(key = "batch") {
                MessageBanner(
                    message = message,
                    tone = if (message.contains("失败")) BannerTone.Warning else BannerTone.Success,
                    onDismiss = { viewModel.onIntent(GameImportIntent.ConsumeMessage) }
                )
            }
        }

        state.error?.let { error ->
            item(key = "error") {
                MessageBanner(
                    message = error,
                    tone = BannerTone.Warning,
                    onDismiss = { viewModel.onIntent(GameImportIntent.ConsumeMessage) }
                )
            }
        }

        item(key = "filters") { KindFilterRow(state = state, viewModel = viewModel) }

        item(key = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Xs)) {
                BlyyButton(
                    text = if (pendingInScope > 0) "全部导入 $pendingInScope" else "全部已导入",
                    onClick = onImportAll,
                    enabled = !state.busy && pendingInScope > 0,
                    modifier = Modifier.fillMaxWidth()
                )
                if (updatableInScope > 0) {
                    Text(
                        "另有 $updatableInScope 个已导入资源可更新（提取管线升级）",
                        style = AppTypography.CaptionSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (state.entries.size >= 6) {
            item(key = "search") {
                BlyySearchBar(
                    query = searchQuery,
                    onQueryChange = onSearchChange,
                    placeholder = "搜索资源名 / 文件名",
                    onClear = { onSearchChange("") }
                )
            }
        }

        if (state.scanning) {
            item(key = "scanning") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = AppSpacing.Md),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.size(AppSpacing.Sm))
                    Text(
                        "正在扫描游戏资源目录…",
                        style = AppTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (entries.isEmpty() && !state.scanning) {
            item(key = "empty") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = AppSpacing.Xl),
                    contentAlignment = Alignment.Center
                ) {
                    BlyyAnimatedEmptyState(
                        visible = true,
                        icon = Icons.Rounded.CloudDownload,
                        title = "没有待导入的资源包",
                        description = if (state.shellState == ShellState.READY) {
                            "Shizuku 已连接，点右上角刷新可重新扫描资源目录\n（若游戏资源未下载完，请先在游戏内下载）"
                        } else {
                            "接通 Shizuku 后点右上角刷新，自动扫描游戏资源目录\n（支持官服 / 渠道服 / 外服包名）"
                        }
                    )
                }
            }
        }

        items(entries, key = { it.path }) { entry ->
            BundleEntryCard(
                entry = entry,
                displayName = viewModel.displayNameOfEntry(entry),
                sizeText = viewModel.formatSize(entry.size),
                busy = state.busy,
                onImport = { onImport(entry) },
                onOpen = { onOpen(entry) },
                onSetSecretary = { onSetSecretary(entry) }
            )
        }
    }
}

@Composable
private fun ShellStateCard(
    state: GameImportViewModel.State,
    viewModel: GameImportViewModel
) {
    // 连接成功后整张卡片不再渲染（BundlesTab 也不会给它 item 位置），这里再兜一层
    if (state.shellState == ShellState.READY) return
    val (icon, title, description) = when (state.shellState) {
        ShellState.NO_PERMISSION -> Triple(
            Icons.Rounded.Shield,
            "等待 Shizuku 授权",
            "授权后可直接读取 Android/data 下的游戏资源包（免复制）"
        )
        ShellState.NOT_RUNNING -> Triple(
            Icons.Rounded.WarningAmber,
            "未检测到 Shizuku 服务",
            "启动 Shizuku 后可一键扫描游戏目录；也可以直接从本地文件导入"
        )
        // 已授权但 UserService 绑不上/不应答：以前会被误显示成「已连接」，
        // 用户看到的是「连上了却扫不到资源」，这里把真实原因摆出来
        ShellState.SERVICE_UNREACHABLE -> Triple(
            Icons.Rounded.WarningAmber,
            "Shizuku 通道未连通",
            state.shellDetail?.takeIf { it.isNotBlank() } ?: "服务未响应，点「重试」重新绑定"
        )
        ShellState.READY, ShellState.UNKNOWN -> Triple(
            Icons.Rounded.Shield,
            "正在检查 Shizuku 状态",
            "首次进入需要一次授权，之后无需重复操作"
        )
    }

    val accent = when (state.shellState) {
        ShellState.NO_PERMISSION -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }

    BlyyCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = BlyyCardElevation.Flat,
        accentColor = accent
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = AppTypography.TitleSmall)
                    Text(
                        description,
                        style = AppTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                when (state.shellState) {
                    ShellState.NO_PERMISSION -> TextButton(
                        onClick = { viewModel.onIntent(GameImportIntent.RequestPermission) }
                    ) { Text("授权") }

                    ShellState.SERVICE_UNREACHABLE -> TextButton(
                        onClick = { viewModel.onIntent(GameImportIntent.RequestPermission) }
                    ) { Text("重试") }

                    else -> Unit
                }
            }
        }
    }
}

/**
 * 设备能力提示（GPU 不支持 ASTC 6x6 时）。
 *
 * 不阻断操作：用户仍可导入，只是这类资源会在解码阶段失败——先把原因摆在明面上。
 */
@Composable
private fun DeviceWarningCard(text: String) {
    val accent = MaterialTheme.colorScheme.tertiary
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppSpacing.Corner.Md),
        color = accent.copy(alpha = 0.12f),
        border = androidx.compose.foundation.BorderStroke(AppSpacing.Border.Thin, accent.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
        ) {
            Icon(
                Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text,
                style = AppTypography.LabelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun KindFilterRow(
    state: GameImportViewModel.State,
    viewModel: GameImportViewModel
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
        item(key = "all") {
            FilterChip(
                selected = state.kindFilter == null,
                onClick = { viewModel.onIntent(GameImportIntent.SelectKind(null)) },
                label = {
                    Text(
                        "全部 ${if (state.tab == GameImportTab.BUNDLES) state.entries.size else state.totalAssets}",
                        style = AppTypography.LabelSmall
                    )
                }
            )
        }
        items(GameAssetKind.ordered, key = { it.key }) { kind ->
            val count = if (state.tab == GameImportTab.BUNDLES) {
                state.countOf(kind)
            } else {
                state.importedCountOf(kind)
            }
            FilterChip(
                selected = state.kindFilter == kind,
                onClick = { viewModel.onIntent(GameImportIntent.SelectKind(kind)) },
                label = {
                    Text("${kind.shortLabel} $count", style = AppTypography.LabelSmall)
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BundleEntryCard(
    entry: GameBundleEntry,
    displayName: String,
    sizeText: String,
    busy: Boolean,
    onImport: () -> Unit,
    onOpen: () -> Unit,
    onSetSecretary: () -> Unit
) {
    val isDark = LocalIsDark.current
    val stateColor = when (entry.state) {
        GameBundleEntry.State.IMPORTED -> MaterialTheme.colorScheme.primary
        GameBundleEntry.State.CHANGED -> MaterialTheme.colorScheme.tertiary
        GameBundleEntry.State.FAILED -> MaterialTheme.colorScheme.error
        GameBundleEntry.State.NEW -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val stateLabel = when (entry.state) {
        GameBundleEntry.State.IMPORTED -> "已导入"
        GameBundleEntry.State.CHANGED -> "有更新"
        GameBundleEntry.State.FAILED -> "失败"
        GameBundleEntry.State.NEW -> "待导入"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppSpacing.Corner.Lg),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isDark) 0.95f else 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = AppElevation.Level1,
        border = BorderStroke(
            AppSpacing.Border.Thin,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.5f else 0.4f)
        )
    ) {
        Column(Modifier.padding(AppSpacing.Md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindBadge(entry.kind)
                Spacer(Modifier.size(AppSpacing.Sm))
                Column(Modifier.weight(1f)) {
                    Text(
                        displayName,
                        style = AppTypography.LabelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${entry.fileName} · $sizeText",
                        style = AppTypography.CaptionSmall,
                        color = secondaryTextColor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    shape = RoundedCornerShape(AppSpacing.Corner.Full),
                    color = stateColor.copy(alpha = 0.14f)
                ) {
                    Text(
                        stateLabel,
                        style = AppTypography.LabelSmall,
                        color = stateColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            if (!entry.error.isNullOrBlank()) {
                Text(
                    entry.error,
                    style = AppTypography.CaptionSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = AppSpacing.Xs),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AppSpacing.Sm),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
            ) {
                when (entry.state) {
                    GameBundleEntry.State.IMPORTED -> {
                        BlyyButton(
                            text = "查看",
                            onClick = onOpen,
                            icon = Icons.Rounded.ViewInAr,
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        )
                        BlyyButton(
                            text = "重新导入",
                            onClick = onImport,
                            variant = BlyyButtonVariant.Secondary,
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    GameBundleEntry.State.NEW -> BlyyButton(
                        text = "导入",
                        onClick = onImport,
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    )

                    GameBundleEntry.State.CHANGED -> {
                        BlyyButton(
                            text = "更新",
                            onClick = onImport,
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        )
                        BlyyButton(
                            text = "查看",
                            onClick = onOpen,
                            variant = BlyyButtonVariant.Secondary,
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    GameBundleEntry.State.FAILED -> BlyyButton(
                        text = "重试",
                        onClick = onImport,
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // SD 小人导入后可以直接设成今日秘书舰：省掉「去秘书舰设置里翻资源库」这一步。
            // 其它类型（Live2D/立绘/过场图）不属于秘书舰 SD 资源，不显示该入口。
            if (entry.kind == GameAssetKind.SD_CHAR &&
                (entry.state == GameBundleEntry.State.IMPORTED ||
                    entry.state == GameBundleEntry.State.CHANGED)
            ) {
                TextButton(
                    onClick = onSetSecretary,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = AppSpacing.Xs)
                ) {
                    Icon(
                        Icons.Rounded.SmartToy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.size(AppSpacing.Xs))
                    Text("设为今日秘书舰", style = AppTypography.LabelMedium)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- 已导入分区

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryTab(
    state: GameImportViewModel.State,
    viewModel: GameImportViewModel,
    onOpen: (GameAssetInfo) -> Unit,
    onDetail: (GameAssetInfo) -> Unit,
    onRequestRename: (GameAssetInfo) -> Unit,
    onGoBundles: () -> Unit
) {
    val assets = state.filteredAssets

    // ---- 多选（编辑/删除）----
    // 长按任意卡片进入多选；多选下单击切换选中，顶部工具条支持全选/重命名/删除。
    // 键用 "kind/id" 保证跨类型不撞车（同一 id 可能在立绘与动态皮肤里都存在）。
    var selectionMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateMapOf<String, GameAssetInfo>() }
    var confirmBatchDelete by remember { mutableStateOf(false) }

    fun keyOf(asset: GameAssetInfo) = "${asset.kind.key}/${asset.id}"

    fun exitSelection() {
        selectionMode = false
        selected.clear()
    }

    fun toggle(asset: GameAssetInfo) {
        val key = keyOf(asset)
        if (selected.remove(key) == null) selected[key] = asset
    }

    // 资源被外部删除（导入/重命名/单删）后，选中表里可能残留已失效的键
    LaunchedEffect(assets) {
        if (selected.isEmpty()) return@LaunchedEffect
        val alive = assets.map(::keyOf).toSet()
        selected.keys.retainAll(alive)
        if (selectionMode && selected.isEmpty()) selectionMode = false
    }

    Column(Modifier.fillMaxSize()) {
        KindFilterRow(state = state, viewModel = viewModel)

        if (selectionMode) {
            LibrarySelectionBar(
                selectedCount = selected.size,
                allSelected = assets.isNotEmpty() && selected.size == assets.size,
                singleSelected = selected.size == 1,
                onToggleAll = {
                    if (selected.size == assets.size) {
                        selected.clear()
                    } else {
                        assets.forEach { selected[keyOf(it)] = it }
                    }
                },
                onRename = {
                    selected.values.singleOrNull()?.let { asset ->
                        exitSelection()
                        onRequestRename(asset)
                    }
                },
                onDetail = {
                    selected.values.singleOrNull()?.let(onDetail)
                },
                onDelete = { confirmBatchDelete = true },
                onExit = { exitSelection() }
            )
        }

        // 批量删除确认：一次说清数量与总体积，避免误删
        if (confirmBatchDelete && selected.isNotEmpty()) {
            val targets = selected.values.toList()
            AlertDialog(
                onDismissRequest = { confirmBatchDelete = false },
                icon = {
                    Icon(
                        Icons.Rounded.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                title = { Text("删除 ${targets.size} 个资源？") },
                text = {
                    Text(
                        "将永久删除已选资源（共 ${viewModel.formatSize(targets.sumOf { it.sizeBytes })}），" +
                            "游戏资源包本身不受影响，可随时重新导入。",
                        style = AppTypography.BodyMedium
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmBatchDelete = false
                        val keys = targets.map { it.kind to it.id }
                        exitSelection()
                        viewModel.onIntent(GameImportIntent.DeleteAssets(keys))
                    }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmBatchDelete = false }) { Text("取消") }
                }
            )
        }

        if (assets.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(AppSpacing.Screen.Horizontal),
                contentAlignment = Alignment.Center
            ) {
                BlyyAnimatedEmptyState(
                    visible = true,
                    icon = Icons.Rounded.ViewInAr,
                    title = "资源库还是空的",
                    description = "在「资源包」里导入后，模型与立绘会出现在这里",
                    actionLabel = if (state.entries.isNotEmpty()) "去导入" else null,
                    onAction = if (state.entries.isNotEmpty()) onGoBundles else null
                )
            }
        } else {
            LazyVerticalGrid(
                // 自适应列数：横屏/平板上固定 2 列会把卡片拉得过大
                columns = GridCells.Adaptive(minSize = 168.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = AppSpacing.Screen.Horizontal,
                    end = AppSpacing.Screen.Horizontal,
                    top = AppSpacing.Sm,
                    bottom = AppSpacing.Screen.Bottom + 72.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
            ) {
                // 库容量概览：告诉用户当前占用，避免「导入了很多但不知道占多少」
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "共 ${assets.size} 个资源 · 占用 ${viewModel.formatSize(assets.sumOf { it.sizeBytes })}",
                        style = AppTypography.CaptionSmall,
                        color = secondaryTextColor(),
                        modifier = Modifier.padding(top = AppSpacing.Xs, bottom = AppSpacing.Xs)
                    )
                }
                items(assets, key = { "${it.kind.key}/${it.id}" }) { asset ->
                    AssetCard(
                        asset = asset,
                        displayName = viewModel.displayNameFor(asset),
                        sizeText = viewModel.formatSize(asset.sizeBytes),
                        isSelected = selectionMode && selected.containsKey(keyOf(asset)),
                        selectionMode = selectionMode,
                        onClick = {
                            if (selectionMode) toggle(asset) else onOpen(asset)
                        },
                        onLongClick = {
                            if (selectionMode) {
                                toggle(asset)
                            } else {
                                selectionMode = true
                                selected[keyOf(asset)] = asset
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * 资源库多选工具条。
 *
 * 只在多选态出现：已选数量 + 全选/取消全选 + 详情（单选）+ 重命名（单选）+ 删除 + 退出。
 * 重命名与详情对多选无意义，因此仅在恰好选中 1 个时可用（置灰而非隐藏，避免控件跳动）。
 */
@Composable
private fun LibrarySelectionBar(
    selectedCount: Int,
    allSelected: Boolean,
    singleSelected: Boolean,
    onToggleAll: () -> Unit,
    onRename: () -> Unit,
    onDetail: () -> Unit,
    onDelete: () -> Unit,
    onExit: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Screen.Horizontal, vertical = AppSpacing.Xs),
        shape = RoundedCornerShape(AppSpacing.Corner.Lg),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        border = BorderStroke(
            AppSpacing.Border.Thin,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
        )
    ) {
        Row(
            modifier = Modifier.padding(start = AppSpacing.Md, end = AppSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
        ) {
            Text(
                "已选 $selectedCount",
                style = AppTypography.LabelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onToggleAll) {
                Text(
                    if (allSelected) "取消全选" else "全选",
                    style = AppTypography.LabelMedium
                )
            }
            TextButton(onClick = onDetail, enabled = singleSelected) {
                Text("详情", style = AppTypography.LabelMedium)
            }
            TextButton(onClick = onRename, enabled = singleSelected) {
                Text("重命名", style = AppTypography.LabelMedium)
            }
            TextButton(onClick = onDelete) {
                Text(
                    "删除",
                    style = AppTypography.LabelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            IconButton(onClick = onExit) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "退出多选",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssetCard(
    asset: GameAssetInfo,
    displayName: String,
    sizeText: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isSelected: Boolean = false,
    selectionMode: Boolean = false
) {
    val isDark = LocalIsDark.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(AppSpacing.Corner.Lg),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f)
        },
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = AppElevation.Level2,
        border = BorderStroke(
            if (isSelected) AppSpacing.Border.Thick else AppSpacing.Border.Thin,
            if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.55f else 0.4f)
            }
        )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.85f)
                    .background(
                        Brush.verticalGradient(
                            listOf(AppColors.Live2DViewer.BgDark, Color(0xFF16233C))
                        )
                    )
            ) {
                val preview = asset.previewFile
                when {
                    preview != null -> AsyncImage(
                        model = preview,
                        contentDescription = displayName,
                        contentScale = if (asset.isImage) ContentScale.Fit else ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )

                    asset.avatarAsset != null -> AsyncImage(
                        model = "file:///android_asset/blhx_avatar/${asset.avatarAsset}",
                        contentDescription = displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )

                    else -> Icon(
                        if (asset.isSpine) Icons.Rounded.Animation else Icons.Rounded.Image,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.35f),
                        modifier = Modifier
                            .size(48.dp)
                            .align(Alignment.Center)
                    )
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(AppSpacing.Sm),
                    shape = RoundedCornerShape(AppSpacing.Corner.Full),
                    color = Color(0xCC0F1826),
                    border = BorderStroke(AppSpacing.Border.Thin, Color.White.copy(alpha = 0.22f))
                ) {
                    Text(
                        asset.kind.shortLabel,
                        style = AppTypography.LabelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                asset.variantLabel?.let { label ->
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(AppSpacing.Sm),
                        shape = RoundedCornerShape(AppSpacing.Corner.Full),
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)
                    ) {
                        Text(
                            label,
                            style = AppTypography.LabelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                // 多选态：右下角勾选指示（选中=主色实心勾，未选=半透明空圈）
                if (selectionMode) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(AppSpacing.Sm)
                            .size(24.dp)
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color(0x66000000)
                                },
                                CircleShape
                            )
                            .border(
                                AppSpacing.Border.Thin,
                                Color.White.copy(alpha = 0.7f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }

            Column(Modifier.padding(AppSpacing.Md)) {
                Text(
                    displayName,
                    style = AppTypography.LabelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(sizeText)
                        asset.versionLabel?.let { append(" · ").append(it) }
                        // 分层素材：图集是碎片，静态预览看起来「拼不起来」
                        when (asset.paintingMode) {
                            "layout" -> if (asset.paintingLayers > 0) {
                                append(" · ").append(asset.paintingLayers).append(" 层合成")
                            }
                            "mesh" -> append(" · 网格还原")
                        }
                    },
                    style = AppTypography.CaptionSmall,
                    color = secondaryTextColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------- 通用组件

private enum class BannerTone { Success, Warning }

@Composable
private fun MessageBanner(message: String, tone: BannerTone, onDismiss: () -> Unit) {
    val semantics = LocalSemanticColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppSpacing.Corner.Lg),
        color = when (tone) {
            BannerTone.Success -> semantics.successContainer
            BannerTone.Warning -> semantics.warningContainer
        },
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
                if (tone == BannerTone.Success) Icons.Rounded.Check else Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = when (tone) {
                    BannerTone.Success -> semantics.onSuccessContainer
                    BannerTone.Warning -> semantics.onWarningContainer
                },
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.size(AppSpacing.Sm))
            Text(
                message,
                style = AppTypography.CaptionMedium,
                color = when (tone) {
                    BannerTone.Success -> semantics.onSuccessContainer
                    BannerTone.Warning -> semantics.onWarningContainer
                },
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "关闭",
                    tint = when (tone) {
                        BannerTone.Success -> semantics.onSuccessContainer
                        BannerTone.Warning -> semantics.onWarningContainer
                    },
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun ImportProgressCard(
    progress: GameImportViewModel.ImportProgress,
    onCancel: () -> Unit
) {
    val isDark = LocalIsDark.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppSpacing.Corner.Lg),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isDark) 0.97f else 0.95f),
        tonalElevation = AppElevation.Level2,
        border = BorderStroke(
            AppSpacing.Border.Thin,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.55f else 0.4f)
        )
    ) {
        Column(Modifier.padding(AppSpacing.Md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "正在导入 ${progress.index}/${progress.total}",
                    style = AppTypography.LabelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onCancel) { Text("停止") }
            }
            LinearProgressIndicator(
                progress = { progress.fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(AppSpacing.Xs))
            Text(
                "${progress.fileName} · ${progress.message}",
                style = AppTypography.CaptionSmall,
                color = secondaryTextColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun KindBadge(kind: GameAssetKind) {
    val color = when (kind) {
        GameAssetKind.LIVE2D -> MaterialTheme.colorScheme.primary
        GameAssetKind.SD_CHAR -> MaterialTheme.colorScheme.secondary
        GameAssetKind.DYNAMIC_SKIN -> MaterialTheme.colorScheme.tertiary
        GameAssetKind.PAINTING -> MaterialTheme.colorScheme.primary
        GameAssetKind.LOADING_BG -> MaterialTheme.colorScheme.secondary
    }
    Surface(
        shape = RoundedCornerShape(AppSpacing.Corner.Sm),
        color = color.copy(alpha = 0.16f)
    ) {
        Text(
            kind.shortLabel,
            style = AppTypography.LabelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun AssetDetailSheet(
    asset: GameAssetInfo,
    displayName: String,
    sizeText: String,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Screen.Horizontal)
            .padding(bottom = AppSpacing.Xl)
    ) {
        Text(displayName, style = AppTypography.TitleMedium, fontWeight = FontWeight.Bold)
        Text(
            "${asset.kind.label} · ${asset.id}",
            style = AppTypography.CaptionSmall,
            color = secondaryTextColor(),
            modifier = Modifier.padding(bottom = AppSpacing.Md)
        )

        DetailRow("类型", asset.kind.description)
        when {
            asset.isSpine -> {
                DetailRow("骨骼", "${asset.skeletons.size} 套 · 默认 ${asset.primarySkeleton ?: "-"}")
                DetailRow("图集页", "${asset.pageCount} 页")
                asset.spineVersion?.let { DetailRow("Spine 版本", it) }
            }
            asset.isImage -> {
                if (asset.imageWidth > 0) {
                    DetailRow("分辨率", "${asset.imageWidth}×${asset.imageHeight}")
                }
                // 立绘才有「几层合成」的概念；过场图是整张图
                when (asset.paintingMode) {
                    "layout" -> DetailRow("合成", "${asset.paintingLayers} 层（背景/人物/舰装）")
                    "mesh" -> DetailRow("合成", "网格还原（碎片图集）")
                    "raw" -> DetailRow("合成", "原始贴图")
                }
            }
            else -> {
                if (asset.pageCount > 0) DetailRow("贴图", "${asset.pageCount} 张")
            }
        }
        DetailRow("占用", "$sizeText · ${asset.fileCount} 个文件")
        asset.sourceNote?.let { DetailRow("备注", it) }

        Spacer(Modifier.height(AppSpacing.Lg))
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)) {
            BlyyButton(
                text = "打开",
                onClick = onOpen,
                icon = Icons.Rounded.ViewInAr,
                modifier = Modifier.weight(1f)
            )
            BlyyButton(
                text = "重命名",
                onClick = onRename,
                variant = BlyyButtonVariant.Secondary,
                icon = Icons.Rounded.Edit,
                modifier = Modifier.weight(1f)
            )
            BlyyButton(
                text = "删除",
                onClick = onDelete,
                variant = BlyyButtonVariant.Secondary,
                icon = Icons.Rounded.DeleteOutline,
                modifier = Modifier.weight(1f)
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
        Text(label, style = AppTypography.BodyMedium, color = secondaryTextColor())
        Text(
            value,
            style = AppTypography.BodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = AppSpacing.Md)
        )
    }
}

/**
 * 次级文字色：暗色下把 onSurfaceVariant 向 onSurface 混 45%，提升小字号对比度
 * （与 Live2D 皮肤库的视觉处理保持一致）。
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
