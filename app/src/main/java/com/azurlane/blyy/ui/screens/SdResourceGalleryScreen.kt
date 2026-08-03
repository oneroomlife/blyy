package com.azurlane.blyy.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyPanel
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.util.SDResourceManager
import com.azurlane.blyy.util.SDResource
import com.azurlane.blyy.util.SDResourceSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * SD 资源管理页面。
 *
 * 解除"舰名列表驱动"限制后的核心交互入口：用户可浏览、搜索、预览、
 * 选择、删除任意 SD 资源（舰娘 / 自定义）。
 *
 * 数据来源：[SDResourceManager.listAll] 自动发现，无需手动维护列表。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SdResourceGalleryScreen(
    /** 当前选中的资源 ID（空表示未选择，跟随舰名匹配） */
    selectedResourceId: String,
    /** 资源选择回调 */
    onSelectResource: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // ── 本地 UI 状态 ──
    var searchQuery by remember { mutableStateOf("") }
    var refreshTrigger by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var resources by remember { mutableStateOf<List<SDResource>>(emptyList()) }

    // 预览对话框
    var previewResource by remember { mutableStateOf<SDResource?>(null) }

    // 删除确认对话框
    var deleteTarget by remember { mutableStateOf<SDResource?>(null) }

    // 重命名对话框
    var renameTarget by remember { mutableStateOf<SDResource?>(null) }
    var renameText by remember { mutableStateOf("") }

    // ── 资源加载：监听 revision 变化自动刷新 ──
    LaunchedEffect(refreshTrigger, SDResourceManager.revision.value) {
        isLoading = true
        // 在 IO 线程执行目录扫描，避免主线程卡顿
        val list = withContext(Dispatchers.IO) {
            runCatching { SDResourceManager.listAll(context) }
                .getOrElse { emptyList() }
        }
        resources = list
        isLoading = false
    }

    // ── 过滤后的资源列表（仅按搜索词过滤，不再分类筛选） ──
    val filteredResources = remember(resources, searchQuery) {
        resources.filter { res ->
            searchQuery.isBlank() ||
                res.name.contains(searchQuery, ignoreCase = true) ||
                res.id.contains(searchQuery, ignoreCase = true)
        }
    }

    AdaptiveScreenBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            BlyyTopBar(
                title = "SD 资源管理",
                subtitle = "已发现 ${resources.size} 个资源",
                onBackClick = onBack,
                actions = {
                    IconButton(onClick = {
                        SDResourceManager.refreshIndex()
                        refreshTrigger++
                    }) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "刷新",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )

            // 搜索栏
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Screen.Horizontal, vertical = AppSpacing.Sm)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "搜索资源名称或 ID…",
                            style = AppTypography.BodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "清除",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    shape = RoundedCornerShape(AppSpacing.Corner.Md),
                    textStyle = AppTypography.BodyMedium
                )
            }

            // 内容区
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    isLoading -> SdLoadingState()
                    filteredResources.isEmpty() && resources.isEmpty() -> SdEmptyState()
                    filteredResources.isEmpty() -> SdSearchEmptyState(query = searchQuery)
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 110.dp),
                        contentPadding = PaddingValues(
                            start = AppSpacing.Screen.Horizontal,
                            end = AppSpacing.Screen.Horizontal,
                            top = AppSpacing.Xs,
                            bottom = WindowInsets.navigationBars.asPaddingValues()
                                .calculateBottomPadding() + AppSpacing.Xxl
                        ),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Gap.CardGrid),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Gap.CardGrid)
                    ) {
                        items(filteredResources, key = { it.id }) { resource ->
                            SdResourceCard(
                                resource = resource,
                                isSelected = resource.id == selectedResourceId,
                                onClick = { onSelectResource(resource.id) },
                                onLongClick = { previewResource = resource }
                            )
                        }
                    }
                }
            }
        }
    }

    // ── 预览对话框 ──
    previewResource?.let { resource ->
        SdResourcePreviewDialog(
            resource = resource,
            isSelected = resource.id == selectedResourceId,
            onSelect = {
                onSelectResource(resource.id)
                previewResource = null
            },
            onRename = {
                renameTarget = resource
                renameText = resource.id.substringAfterLast("/")
                previewResource = null
            },
            onDelete = {
                deleteTarget = resource
                previewResource = null
            },
            onDismiss = { previewResource = null }
        )
    }

    // ── 删除确认对话框 ──
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除资源") },
            text = {
                Text(
                    "确认删除「${target.name}」？\n该操作会移除资源目录，不可恢复。",
                    style = AppTypography.BodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val success = SDResourceManager.deleteResource(context, target.id)
                        if (success) {
                            refreshTrigger++
                            Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "删除失败", Toast.LENGTH_SHORT).show()
                        }
                        deleteTarget = null
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }

    // ── 重命名对话框 ──
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名资源") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("新名称") },
                    singleLine = true,
                    shape = RoundedCornerShape(AppSpacing.Corner.Sm)
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank() && renameText != target.id.substringAfterLast("/"),
                    onClick = {
                        val oldId = target.id
                        val oldName = oldId.substringAfterLast("/")
                        val success = SDResourceManager.renameResource(context, oldName, renameText.trim())
                        if (success) {
                            refreshTrigger++
                            Toast.makeText(context, "已重命名", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "重命名失败", Toast.LENGTH_SHORT).show()
                        }
                        renameTarget = null
                    }
                ) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            }
        )
    }
}

// ============ 子组件 ============

/**
 * 单个 SD 资源卡片。
 *
 * 展示预览图、名称、类型标签、来源标签，点击选择，长按打开预览对话框。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SdResourceCard(
    resource: SDResource,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val accentColor = if (resource.source == SDResourceSource.CUSTOM)
        MaterialTheme.colorScheme.tertiary
    else MaterialTheme.colorScheme.primary

    BlyyPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.Corner.Md))
            .border(
                width = if (isSelected) AppSpacing.Border.Normal else AppSpacing.Border.Thin,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                    else accentColor.copy(alpha = 0.3f),
                shape = RoundedCornerShape(AppSpacing.Corner.Md)
            ),
        accentColor = accentColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .padding(AppSpacing.Xs)
        ) {
            // 预览图区域（固定宽高比 3:4，贴合 SD 小人形状）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f)
                    .clip(RoundedCornerShape(AppSpacing.Corner.Sm))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                val previewFile = resource.previewPath?.let { File(it) }
                if (previewFile != null && previewFile.exists()) {
                    AsyncImage(
                        model = previewFile,
                        contentDescription = resource.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    // 无预览图：显示类型图标占位
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (resource.isSpine) Icons.Rounded.Animation
                                else Icons.Rounded.Image,
                            contentDescription = null,
                            tint = accentColor.copy(alpha = 0.5f),
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.Xs))
                        Text(
                            "无预览",
                            style = AppTypography.LabelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }

                // 类型徽章（左上角）
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(AppSpacing.Xs),
                    shape = RoundedCornerShape(AppSpacing.Corner.Full),
                    color = (if (resource.isSpine) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.tertiary).copy(alpha = 0.85f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = AppSpacing.Xs, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (resource.isSpine) Icons.Rounded.Animation
                                else Icons.Rounded.Image,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(10.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = if (resource.isSpine) "动画" else "图片",
                            style = AppTypography.LabelSmall.copy(fontSize = 9.sp),
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // 已选中徽章（右上角）
                if (isSelected) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(AppSpacing.Xs),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = "已选择",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .padding(2.dp)
                                .size(14.dp)
                        )
                    }
                }

                // 来源徽章（左下角，仅自定义资源显示）
                if (resource.source == SDResourceSource.CUSTOM) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(AppSpacing.Xs),
                        shape = RoundedCornerShape(AppSpacing.Corner.Full),
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)
                    ) {
                        Text(
                            text = "自定义",
                            style = AppTypography.LabelSmall.copy(fontSize = 9.sp),
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = AppSpacing.Xs, vertical = 2.dp)
                        )
                    }
                }
            }

            // 资源名称 + 皮肤数
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Xs, vertical = AppSpacing.Xs)
            ) {
                Text(
                    text = resource.name,
                    style = AppTypography.LabelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${resource.skinCount} 个皮肤",
                    style = AppTypography.LabelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * 资源预览对话框。
 *
 * 展示大图预览、资源信息（ID/类型/来源/皮肤列表）、操作按钮。
 */
@Composable
private fun SdResourcePreviewDialog(
    resource: SDResource,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                resource.name,
                style = AppTypography.TitleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 预览图（大尺寸）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(AppSpacing.Corner.Md))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    val previewFile = resource.previewPath?.let { File(it) }
                    if (previewFile != null && previewFile.exists()) {
                        AsyncImage(
                            model = previewFile,
                            contentDescription = resource.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = if (resource.isSpine) Icons.Rounded.Animation
                                    else Icons.Rounded.BrokenImage,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(AppSpacing.Sm))
                            Text(
                                "无预览图",
                                style = AppTypography.BodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.Md))

                // 资源信息
                SdInfoRow(label = "ID", value = resource.id)
                SdInfoRow(label = "类型", value = if (resource.isSpine) "Spine 动画" else "静态图片")
                SdInfoRow(label = "来源", value = if (resource.source == SDResourceSource.SHIP) "舰娘" else "自定义")
                SdInfoRow(label = "皮肤数", value = "${resource.skinCount}")

                if (resource.skinCount > 1) {
                    Spacer(modifier = Modifier.height(AppSpacing.Xs))
                    Text(
                        "皮肤列表：${resource.skinNames.joinToString("、") { skinDisplayNameSafe(it) }}",
                        style = AppTypography.LabelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSelect) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(AppSpacing.Xs))
                Text(
                    if (isSelected) "当前使用" else "使用此资源",
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                )
            }
        },
        dismissButton = {
            Row {
                // 仅自定义资源可重命名/删除
                if (resource.source == SDResourceSource.CUSTOM) {
                    TextButton(onClick = onRename) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.Xs))
                        Text("重命名")
                    }
                    TextButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.Xs))
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}

@Composable
private fun SdInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.Xxs),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = AppTypography.LabelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = AppTypography.LabelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SdLoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(AppSpacing.Md))
            Text(
                "扫描资源中…",
                style = AppTypography.BodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SdEmptyState() {
    val accentColor = MaterialTheme.colorScheme.tertiary
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.Xxl),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(accentColor.copy(alpha = 0.3f), Color.Transparent)
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.SdStorage,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(40.dp)
                )
            }
            Text(
                "暂无 SD 资源",
                style = AppTypography.TitleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "将 SD 三件套（.skel + .atlas + .png）或图片放入文件夹，\n通过设置页「SD 资源库」分区整理资源即可自动识别。",
                style = AppTypography.BodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SdSearchEmptyState(query: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp)
            )
            Text(
                "未找到匹配「$query」的资源",
                style = AppTypography.BodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 皮肤名 → 中文显示名（本地辅助函数，避免与 util 包重复定义冲突） */
private fun skinDisplayNameSafe(skin: String): String = when (skin.lowercase()) {
    "default" -> "默认"
    "gai" -> "改造"
    "huan" -> "换装"
    "huangxiang" -> "幻象"
    "doa" -> "DOA联动"
    "teyao" -> "特别计划"
    "mirror" -> "镜像"
    "left" -> "左半身"
    "right" -> "右半身"
    else -> if (skin.startsWith("skin")) "皮肤${skin.removePrefix("skin")}" else skin
}
