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
import com.azurlane.blyy.ui.components.BlyyConfirmDialog
import com.azurlane.blyy.ui.components.BlyyFormDialog
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
        BlyyConfirmDialog(
            title = "删除资源？",
            message = "「${viewModel.displayNameFor(asset)}」（${viewModel.formatSize(asset.sizeBytes)}）将被永久删除，" +
                "游戏资源包本身不受影响，可随时重新导入。",
            confirmText = "删除",
            onConfirm = {
                viewModel.onIntent(GameImportIntent.DeleteAsset(asset.kind, asset.id))
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
            isDestructive = true,
            icon = Icons.Rounded.DeleteOutline
        )
    }

    // ---- 重命名 ----
    renameTarget?.let { asset ->
        BlyyFormDialog(
            onDismiss = { renameTarget = null },
            title = "重命名资源",
            icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
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

