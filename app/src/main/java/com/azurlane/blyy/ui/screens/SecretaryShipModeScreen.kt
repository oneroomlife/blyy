package com.azurlane.blyy.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyPanel
import com.azurlane.blyy.ui.components.BlyySectionPanel
import com.azurlane.blyy.ui.components.BlyySettingsRow
import com.azurlane.blyy.ui.components.BlyyChip
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.util.LocalSdResolver
import com.azurlane.blyy.util.OrganizePhase
import com.azurlane.blyy.util.OrganizeProgress
import com.azurlane.blyy.util.OrganizeResult
import com.azurlane.blyy.util.SdResourceOrganizer
import com.azurlane.blyy.util.StoragePermissionHelper
import com.azurlane.blyy.viewmodel.SecretaryShipState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SecretaryShipModeScreen(
    secretaryState: SecretaryShipState,
    onBack: () -> Unit,
    onRandomFlip: () -> Unit,
    onSelectFromHome: () -> Unit,
    onSelectFromGallery: () -> Unit,
    onClearSecretary: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    var heartExpanded by remember { mutableStateOf(false) }

    AdaptiveScreenBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            BlyyTopBar(
                title = "今日秘书舰",
                subtitle = if (secretaryState.shipName.isNotEmpty()) "当前：${secretaryState.shipName}" else "选择你的专属秘书",
                onBackClick = onBack
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(AppSpacing.Screen.Horizontal)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Gap.Lg)
            ) {
                Spacer(modifier = Modifier.height(AppSpacing.Sm))

                // 选择秘书舰的方式：使用自定义透明背景 Section，
                // 让 ModeOptionCard 各自保持独立 BlyyPanel 卡片背景，
                // 卡片之间透出 screen 背景色，形成清晰边界，避免白色桥接。
                SecretarySection(
                    title = "选择秘书舰的方式",
                    icon = Icons.Rounded.Casino,
                    accentColor = MaterialTheme.colorScheme.primary
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
                    ) {
                        ModeOptionCard(
                            title = "随机翻牌",
                            description = "基于稀有度权重随机抽取一名舰娘",
                            icon = Icons.Rounded.Casino,
                            color = MaterialTheme.colorScheme.primary,
                            onClick = onRandomFlip
                        )
                        ModeOptionCard(
                            title = "心有所属",
                            description = "指定一名心仪的舰娘作为秘书舰",
                            icon = Icons.Rounded.Favorite,
                            color = MaterialTheme.colorScheme.secondary,
                            onClick = { heartExpanded = !heartExpanded }
                        )
                        AnimatedVisibility(
                            visible = heartExpanded,
                            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
                            ) {
                                SubOptionCard(
                                    title = "从后宅选择",
                                    description = "在誓约舰娘中选择",
                                    icon = Icons.Rounded.Home,
                                    onClick = onSelectFromHome
                                )
                                SubOptionCard(
                                    title = "从船坞选择",
                                    description = "在船坞档案中选择",
                                    icon = Icons.AutoMirrored.Rounded.List,
                                    onClick = onSelectFromGallery
                                )
                            }
                        }
                    }
                }

                if (secretaryState.shipName.isNotEmpty()) {
                    // 当前秘书舰：同样使用 SecretarySection + 独立卡片，
                    // 让舰名信息和设置入口各自有清晰边界。
                    SecretarySection(
                        title = "当前秘书舰",
                        icon = Icons.Rounded.Favorite,
                        accentColor = MaterialTheme.colorScheme.secondary
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
                        ) {
                            // 舰名信息卡
                            BlyyPanel(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(AppSpacing.Corner.Md)),
                                accentColor = MaterialTheme.colorScheme.secondary
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(AppSpacing.Lg),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        secretaryState.shipName,
                                        style = AppTypography.TitleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    TextButton(onClick = onClearSecretary) {
                                        Text("清除", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }

                            // 设置入口卡（点击导航到独立设置界面）
                            SettingsEntryCard(
                                onClick = onOpenSettings
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 秘书舰设置入口卡 — 点击导航到独立设置界面
 */
@Composable
private fun SettingsEntryCard(onClick: () -> Unit) {
    val accentColor = MaterialTheme.colorScheme.primary
    BlyyPanel(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(AppSpacing.Corner.Md)),
        accentColor = accentColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppSpacing.Corner.Sm))
                .clickable(onClick = onClick)
                .padding(AppSpacing.Lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(accentColor.copy(alpha = 0.3f), Color.Transparent)
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "秘书舰设置",
                    style = AppTypography.TitleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "悬浮窗 · 语音 · 台词 · SD 小人",
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 秘书舰设置独立界面。
 *
 * 全屏页面，包含所有秘书舰相关设置项：SD 小人大小/皮肤、显示设置、语音设置、SD 资源管理。
 * SD 小人本身在主界面通过 [com.azurlane.blyy.ui.components.SecretaryChibiOverlay] 悬浮显示，
 * 调整大小滑块时通过 StateFlow 实时驱动主界面小人 scaleMultiplier，无需在本界面重复预览。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SecretaryShipSettingsScreen(
    secretaryState: SecretaryShipState,
    onBack: () -> Unit,
    isOverlayEnabled: Boolean,
    onToggleOverlay: (Boolean) -> Unit,
    onToggleDialogue: (Boolean) -> Unit,
    onSetAutoPlay: (Boolean, Int) -> Unit,
    onSetSdSkin: (String) -> Unit,
    onSetSdScale: (Float) -> Unit
) {
    AdaptiveScreenBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            BlyyTopBar(
                title = "秘书舰设置",
                subtitle = if (secretaryState.shipName.isNotEmpty()) secretaryState.shipName else "未选择秘书舰",
                onBackClick = onBack
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppSpacing.Screen.Horizontal)
                    .padding(bottom = AppSpacing.Lg)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Gap.Lg)
            ) {
                // 1. SD 小人显示大小 + 皮肤切换
                //    SD 小人本身已在主界面通过 SecretaryChibiOverlay 悬浮显示，
                //    此处仅保留滑块用于调整大小，StateFlow 实时驱动主界面小人缩放。
                BlyySectionPanel(
                    title = "SD 小人",
                    icon = Icons.Rounded.SmartToy,
                    accentColor = MaterialTheme.colorScheme.primary
                ) {
                    SdScaleSlider(
                        sdScale = secretaryState.sdScale,
                        onSetSdScale = onSetSdScale
                    )
                    HorizontalDivider()
                    SdSkinSelector(
                        shipName = secretaryState.shipName,
                        selectedSkin = secretaryState.sdSkin,
                        onSetSdSkin = onSetSdSkin
                    )
                }

                // 2. 显示设置（悬浮窗 + 台词弹窗）
                BlyySectionPanel(
                    title = "显示设置",
                    icon = Icons.Rounded.Visibility,
                    accentColor = MaterialTheme.colorScheme.secondary
                ) {
                    BlyySettingsRow(
                        icon = Icons.Rounded.Visibility,
                        title = "桌面悬浮窗",
                        description = "在桌面或其他应用上显示秘书舰",
                        checked = isOverlayEnabled,
                        onCheckedChange = onToggleOverlay
                    )
                    HorizontalDivider()
                    BlyySettingsRow(
                        icon = Icons.Rounded.Palette,
                        title = "台词弹窗",
                        description = "播放语音时在秘书舰上方显示台词字幕",
                        checked = secretaryState.dialogueEnabled,
                        onCheckedChange = onToggleDialogue
                    )
                }

                // 3. 语音设置（自动播放 + 间隔）
                BlyySectionPanel(
                    title = "语音设置",
                    icon = Icons.Rounded.GraphicEq,
                    accentColor = MaterialTheme.colorScheme.tertiary
                ) {
                    BlyySettingsRow(
                        icon = Icons.Rounded.GraphicEq,
                        title = "语音自动播放",
                        description = "定时播放秘书舰语音",
                        checked = secretaryState.autoPlayEnabled,
                        onCheckedChange = { onSetAutoPlay(it, secretaryState.autoPlayIntervalMinutes) }
                    )
                    AnimatedVisibility(
                        visible = secretaryState.autoPlayEnabled,
                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
                    ) {
                        Column(modifier = Modifier.padding(AppSpacing.Lg)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("播放间隔", style = AppTypography.BodySmall)
                                Text(
                                    "${secretaryState.autoPlayIntervalMinutes} 分钟",
                                    style = AppTypography.BodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Slider(
                                value = secretaryState.autoPlayIntervalMinutes.toFloat(),
                                onValueChange = { onSetAutoPlay(true, it.toInt().coerceIn(1, 60)) },
                                valueRange = 1f..60f,
                                steps = 58,
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }
                }

                // 4. SD 资源管理
                BlyySectionPanel(
                    title = "SD 资源管理",
                    icon = Icons.Rounded.SdStorage,
                    accentColor = MaterialTheme.colorScheme.tertiary
                ) {
                    SdResourceManagement()
                }
            }
        }
    }
}

/**
 * SD 小人显示大小滑块。
 *
 * SD 小人本身已在主界面通过 [com.azurlane.blyy.ui.components.SecretaryChibiOverlay] 悬浮显示，
 * 此处不再重复渲染预览窗。拖动滑块时通过 StateFlow 实时驱动主界面小人 scaleMultiplier，
 * SpineSdView 的 update 块立即生效，无需重建 GLSurfaceView。
 */
@Composable
private fun SdScaleSlider(
    sdScale: Float,
    onSetSdScale: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = AppSpacing.Lg)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("显示大小", style = AppTypography.BodySmall)
            Text(
                "${(sdScale * 100).roundToInt()}%",
                style = AppTypography.BodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        Slider(
            value = sdScale.coerceIn(0.5f, 2.0f),
            onValueChange = { onSetSdScale(it) },
            valueRange = 0.5f..2.0f,
            steps = 14,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SdSkinSelector(
    shipName: String,
    selectedSkin: String,
    onSetSdSkin: (String) -> Unit
) {
    val context = LocalContext.current
    var refreshTrigger by remember { mutableStateOf(0) }

    // 将 LocalSdResolver.revision 作为 remember key，
    // 整理/清理资源后自动刷新皮肤列表，无需手动点击刷新按钮
    val skins = remember(shipName, refreshTrigger, LocalSdResolver.revision.value) {
        if (shipName.isBlank()) emptyList()
        else LocalSdResolver.listSkins(context, shipName)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("皮肤切换", style = AppTypography.BodySmall)
        TextButton(onClick = {
            LocalSdResolver.refreshIndex()
            refreshTrigger++
        }) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.size(AppSpacing.Xs))
            Text("刷新")
        }
    }

    if (skins.isNotEmpty()) {
        FlowRow(
            modifier = Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Xs),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
        ) {
            val currentSkin = selectedSkin.ifEmpty { "default" }
            skins.forEach { skin ->
                BlyyChip(
                    label = skinDisplayName(skin),
                    selected = currentSkin == skin,
                    onClick = { onSetSdSkin(skin) }
                )
            }
        }
    } else {
        Column(modifier = Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Xs)) {
            Text(
                if (shipName.isNotBlank()) "未找到「$shipName」的 SD 资源" else "未选择秘书舰",
                style = AppTypography.BodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "导入方法：将 SD 三件套（.skel + .atlas + .png）放入以下目录的舰娘拼音子文件夹中：",
                style = AppTypography.BodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Download/BLYY/blhx_sd/<舰娘拼音>/",
                style = AppTypography.BodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun SdResourceManagement() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshTrigger by remember { mutableStateOf(0) }

    // 整理状态：null=空闲，非 null=进行中或已完成
    var organizeProgress by remember { mutableStateOf<OrganizeProgress?>(null) }
    var organizeResult by remember { mutableStateOf<OrganizeResult?>(null) }
    var isOrganizing by remember { mutableStateOf(false) }

    // 将 LocalSdResolver.revision 作为 remember key，
    // 整理/清理资源后自动刷新资源计数和路径显示
    val importedCount = remember(refreshTrigger, organizeResult, LocalSdResolver.revision.value) {
        LocalSdResolver.listAllAssets(context).size
    }
    val resourceDir = remember(refreshTrigger, organizeResult, LocalSdResolver.revision.value) {
        LocalSdResolver.getResourceDir(context)
    }
    val resourcePath = resourceDir.absolutePath
    val isAppPrivateDir = resourceDir.absolutePath.contains("/Android/data/")

    // SAF 选择源目录的 Launcher
    val pickDirectoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            // 用户取消选择，无操作
            return@rememberLauncherForActivityResult
        }
        // 持久化 SAF URI，便于后续读取
        StoragePermissionHelper.persistSafUri(context, uri)
        // 启动整理任务
        scope.launch {
            isOrganizing = true
            organizeResult = null
            organizeProgress = OrganizeProgress(
                OrganizePhase.SCANNING,
                message = "正在扫描源目录…"
            )
            val result = SdResourceOrganizer.organize(context, uri) { progress ->
                organizeProgress = progress
            }
            organizeResult = result
            isOrganizing = false
            if (result.isSuccess) {
                // 触发刷新本地资源计数
                refreshTrigger++
            }
        }
    }

    Column(modifier = Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Xs)) {
        // 资源状态概览
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "已导入 $importedCount 个舰娘资源",
                    style = AppTypography.BodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (isAppPrivateDir) "应用专属目录（免权限）" else "公共 Download 目录",
                    style = AppTypography.LabelSmall,
                    color = if (isAppPrivateDir) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.primary
                )
            }
            if (importedCount > 0) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f),
                                    Color.Transparent
                                )
                            ),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.Xs))
        Text(
            "资源路径：$resourcePath",
            style = AppTypography.LabelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 整理操作区
        Spacer(modifier = Modifier.height(AppSpacing.Md))

        if (isOrganizing) {
            // 整理中：显示进度条
            OrganizeProgressCard(progress = organizeProgress)
        } else {
            // 整理按钮
            Button(
                onClick = { pickDirectoryLauncher.launch(null) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AppSpacing.Corner.Sm),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(AppSpacing.Icon.Md)
                )
                Spacer(modifier = Modifier.size(AppSpacing.Xs))
                Text("导入并整理 SD 资源", style = AppTypography.LabelLarge)
            }

            // 整理结果展示
            organizeResult?.let { result ->
                Spacer(modifier = Modifier.height(AppSpacing.Sm))
                OrganizeResultCard(result = result)
            }

            // 清空已整理资源按钮（仅应用专属目录时有意义）
            if (importedCount > 0 && isAppPrivateDir) {
                Spacer(modifier = Modifier.height(AppSpacing.Xs))
                TextButton(
                    onClick = {
                        if (SdResourceOrganizer.clearAppSdDir(context)) {
                            LocalSdResolver.refreshIndex()
                            refreshTrigger++
                            organizeResult = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(AppSpacing.Icon.Sm),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.size(AppSpacing.Xs))
                    Text("清空已整理资源", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // 使用说明
        Spacer(modifier = Modifier.height(AppSpacing.Sm))
        Text(
            "使用说明：将 SD 资源（.skel/.atlas/.png 三件套）放到任意文件夹，" +
                "点击上方按钮选择该文件夹，App 会自动按舰娘和皮肤分类整理到应用专属目录，" +
                "整理后即可在主界面显示可动 SD 小人。",
            style = AppTypography.LabelSmall.copy(lineHeight = 16.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 整理进度卡片 — 显示当前阶段、进度条和状态文字。
 */
@Composable
private fun OrganizeProgressCard(progress: OrganizeProgress?) {
    val accentColor = MaterialTheme.colorScheme.tertiary
    val phaseLabel = when (progress?.phase) {
        OrganizePhase.SCANNING -> "扫描源目录中"
        OrganizePhase.ORGANIZING -> "整理资源中"
        OrganizePhase.FINALIZING -> "写入清单中"
        OrganizePhase.COMPLETED -> "已完成"
        OrganizePhase.ERROR -> "失败"
        null -> "准备中"
    }

    BlyyPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.Corner.Md)),
        accentColor = accentColor
    ) {
        Column(modifier = Modifier.padding(AppSpacing.Lg)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
            ) {
                if (progress?.phase != OrganizePhase.ERROR) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = accentColor
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    phaseLabel,
                    style = AppTypography.TitleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.Xs))
            Text(
                progress?.message ?: "请稍候…",
                style = AppTypography.BodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 进度条：仅整理阶段显示具体进度
            if (progress?.phase == OrganizePhase.ORGANIZING && progress.total > 0) {
                Spacer(modifier = Modifier.height(AppSpacing.Sm))
                LinearProgressIndicator(
                    progress = { (progress.current.toFloat() / progress.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = accentColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${progress.current}/${progress.total}",
                        style = AppTypography.LabelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "舰娘 ${progress.shipCount} · 皮肤 ${progress.skinCount}",
                        style = AppTypography.LabelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (progress?.phase == OrganizePhase.SCANNING) {
                // 扫描阶段使用不确定进度条
                Spacer(modifier = Modifier.height(AppSpacing.Sm))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = accentColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                if (progress.shipCount > 0) {
                    Text(
                        "已发现 ${progress.shipCount} 个舰娘 · ${progress.skinCount} 个皮肤",
                        style = AppTypography.LabelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 整理结果卡片 — 显示整理完成后的统计信息。
 */
@Composable
private fun OrganizeResultCard(result: OrganizeResult) {
    val isSuccess = result.isSuccess
    val accentColor = if (isSuccess) MaterialTheme.colorScheme.tertiary
        else MaterialTheme.colorScheme.error

    BlyyPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.Corner.Md)),
        accentColor = accentColor
    ) {
        Row(
            modifier = Modifier.padding(AppSpacing.Lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
        ) {
            Icon(
                imageVector = if (isSuccess) Icons.Rounded.CheckCircle
                    else Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                if (isSuccess) {
                    Text(
                        "整理完成",
                        style = AppTypography.TitleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "${result.shipCount} 个舰娘 · ${result.skinCount} 个皮肤 · " +
                            "复制 ${result.copiedCount} 项 · 跳过 ${result.skippedCount} 项",
                        style = AppTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "整理失败",
                        style = AppTypography.TitleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        result.errorMessage ?: "未知错误",
                        style = AppTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 皮肤分类名 → 中文显示名 */
private fun skinDisplayName(skin: String): String = when (skin.lowercase()) {
    "default" -> "默认"
    "gai" -> "改造"
    "huan" -> "换装"
    "huangxiang" -> "幻象"
    "doa" -> "DOA联动"
    "teyao" -> "特别计划"
    "mirror" -> "镜像"
    else -> if (skin.startsWith("skin")) "皮肤${skin.removePrefix("skin")}" else skin
}

/**
 * 秘书舰界面专用 Section 标题组件。
 *
 * 与 [BlyySectionPanel] 不同，此组件不带背景 Panel，只渲染标题行。
 * 配合内部独立的 [BlyyPanel] 卡片使用，卡片之间透出 screen 背景色，
 * 形成清晰的组件边界，避免卡片被统一背景"糊"在一起。
 */
@Composable
private fun SecretarySection(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accentColor.copy(alpha = 0.25f),
                                accentColor.copy(alpha = 0.08f),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
                    .border(
                        width = AppSpacing.Border.Thin,
                        color = accentColor.copy(alpha = 0.4f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = accentColor
                )
            }
            Text(
                text = title,
                style = AppTypography.LabelLarge,
                color = accentColor,
                fontWeight = FontWeight.SemiBold
            )
        }
        content()
    }
}

/**
 * 模式选项卡片 — 随机/心有所属。
 *
 * 恢复为独立 [BlyyPanel] 卡片，与相邻卡片之间透出 screen 背景色，
 * 保证每张卡片都有清晰的边界，不会与周围组件糊在一起。
 */
@Composable
private fun ModeOptionCard(
    title: String,
    description: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    BlyyPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.Corner.Md)),
        accentColor = color
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppSpacing.Corner.Sm))
                .clickable(onClick = onClick)
                .padding(AppSpacing.Lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(color.copy(alpha = 0.3f), Color.Transparent)
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = AppTypography.TitleSmall,
                    fontWeight = FontWeight.Medium,
                    color = color
                )
                Text(
                    text = description,
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 子选项卡片 — 从后宅/船坞选择。
 *
 * 同样恢复为独立 [BlyyPanel] 卡片，与 ModeOptionCard 风格保持一致。
 */
@Composable
private fun SubOptionCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    val accentColor = MaterialTheme.colorScheme.primary
    BlyyPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.Corner.Sm)),
        accentColor = accentColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppSpacing.Corner.Xs))
                .clickable(onClick = onClick)
                .padding(AppSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = AppTypography.BodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
