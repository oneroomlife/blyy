package com.azurlane.blyy.ui.screens

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.azurlane.blyy.R
import com.azurlane.blyy.data.model.ApiConfig
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyPanel
import com.azurlane.blyy.ui.components.BlyyPrimaryButton
import com.azurlane.blyy.ui.components.BlyySectionPanel
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.components.StableOutlinedTextField
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.JuusPalette
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.viewmodel.ConnectionTestState
import com.azurlane.blyy.viewmodel.JiuxinViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JiuxinShipConfigScreen(
    onBack: () -> Unit,
    viewModel: JiuxinViewModel = hiltViewModel()
) {
    val scope = rememberCoroutineScope()
    val session by viewModel.currentSession.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionTestState.collectAsStateWithLifecycle()
    val availableModels by viewModel.availableModels.collectAsStateWithLifecycle()
    val apiConfigs by viewModel.apiConfigs.collectAsStateWithLifecycle()

    var showAvatarPicker by remember { mutableStateOf(false) }
    var showVoiceShipPicker by remember { mutableStateOf(false) }
    var showModelDropdown by remember { mutableStateOf(false) }

    val primaryColor = MaterialTheme.colorScheme.primary
    // 暗色模式判断：用于适配硬编码颜色，确保 WCAG AA 对比度
    // 与 JiuxinConfigScreen 保持一致的暗色适配策略
    val isDark = LocalIsDark.current
    // 连接成功状态色：暗色模式下使用更亮的绿色，确保在深色面板背景上对比度 ≥ 4.5:1
    val successColor = if (isDark) Color(0xFF7FE09B) else Color(0xFF2E7D32)
    val selectedBg = primaryColor.copy(alpha = 0.08f)
    val selectedBorder = primaryColor.copy(alpha = 0.3f)
    val cardRounded = RoundedCornerShape(AppSpacing.Corner.Md)

    AdaptiveScreenBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            BlyyTopBar(
                title = "当前舰娘配置",
                subtitle = "仅影响当前对话，不影响全局设置",
                onBackClick = onBack
            )

            val currentSession = session
            if (currentSession == null) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(AppSpacing.Xl),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Rounded.Psychology,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.Md))
                        Text("当前没有活跃的对话", style = AppTypography.BodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("请先进入一个对话后再配置舰娘", style = AppTypography.BodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Lg)
                ) {
                    BlyyPanel(accentColor = primaryColor) {
                        Row(
                            modifier = Modifier.padding(AppSpacing.Md).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
                        ) {
                            Icon(Icons.Rounded.Person, contentDescription = null, tint = primaryColor, modifier = Modifier.size(20.dp))
                            Text(
                                text = currentSession.jiuxinName.ifBlank { "未命名舰娘" },
                                style = AppTypography.TitleSmall,
                                fontWeight = FontWeight.Medium,
                                color = primaryColor
                            )
                        }
                    }

                    // ── 人格与名称 ──
                    BlyySectionPanel(
                        title = "人格与名称",
                        icon = Icons.Rounded.Psychology,
                        accentColor = MaterialTheme.colorScheme.secondary
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(AppSpacing.Lg),
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
                            ) {
                                ShipAvatarDisplay(
                                    avatarUrl = currentSession.avatarUrl,
                                    size = 56.dp,
                                    placeholderIcon = Icons.Rounded.Person,
                                    placeholderIconSize = 24.dp,
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                    iconTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                    showBorder = true,
                                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                    onClick = { showAvatarPicker = true }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("舰娘头像", style = AppTypography.TitleSmall, fontWeight = FontWeight.Medium)
                                    Text(
                                        text = if (currentSession.avatarUrl.isNotBlank()) "已选择头像" else "点击选择舰娘头像或上传图片",
                                        style = AppTypography.BodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            StableOutlinedTextField(
                                value = currentSession.jiuxinName,
                                onValueChange = { viewModel.updateCurrentSessionConfig { s -> s.copy(jiuxinName = it) } },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("舰娘名称") },
                                placeholder = { Text("啾信助手") },
                                singleLine = true,
                                textStyle = AppTypography.BodyMedium,
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primaryColor),
                                shape = RoundedCornerShape(AppSpacing.Corner.Sm)
                            )
                            StableOutlinedTextField(
                                value = currentSession.systemPrompt,
                                onValueChange = { viewModel.updateCurrentSessionConfig { s -> s.copy(systemPrompt = it) } },
                                modifier = Modifier.fillMaxWidth().height(120.dp),
                                label = { Text("人格提示词") },
                                placeholder = { Text("描述舰娘的人格和行为方式...") },
                                textStyle = AppTypography.BodyMedium,
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primaryColor),
                                shape = RoundedCornerShape(AppSpacing.Corner.Sm)
                            )
                        }
                    }

                    // ── API 配置（列表选择模式，无全局配置选项） ──
                    BlyySectionPanel(
                        title = "API 配置",
                        icon = Icons.Rounded.Key,
                        accentColor = primaryColor
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(AppSpacing.Lg),
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
                        ) {
                            // 已保存的 API 配置列表
                            if (apiConfigs.isNotEmpty()) {
                                apiConfigs.forEach { config ->
                                    val isSelected =
                                        currentSession.apiUrl == config.apiUrl &&
                                        currentSession.apiKey == config.apiKey &&
                                        currentSession.model == config.model
                                    ApiConfigOptionRow(
                                        name = config.name.ifBlank { "未命名配置" },
                                        subtitle = buildString {
                                            if (config.model.isNotBlank()) append(config.model)
                                            if (config.apiUrl.isNotBlank()) {
                                                if (isNotEmpty()) append(" · ")
                                                val url = config.apiUrl
                                                append(if (url.length > 40) url.take(40) + "…" else url)
                                            }
                                        },
                                        isSelected = isSelected,
                                        primaryColor = primaryColor,
                                        selectedBg = selectedBg,
                                        selectedBorder = selectedBorder,
                                        onClick = { viewModel.applyApiConfigToCurrentSession(config) }
                                    )
                                }
                            } else {
                                Text(
                                    "暂无已保存的 API 配置，请在总设置中添加",
                                    style = AppTypography.BodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = AppSpacing.Sm)
                                )
                            }

                            // 当前生效配置摘要 + 模型选择 + 测试
                            Spacer(modifier = Modifier.height(AppSpacing.Xs))
                            val currentApiInfo = if (currentSession.apiUrl.isBlank()) {
                                "请选择一个 API 配置"
                            } else {
                                val cfgName = apiConfigs.firstOrNull { it.apiUrl == currentSession.apiUrl && it.apiKey == currentSession.apiKey }?.name
                                "当前使用：${cfgName ?: "自定义"}"
                            }
                            Text(currentApiInfo, style = AppTypography.LabelSmall, color = primaryColor.copy(alpha = 0.7f))

                            // 模型选择（已选择API配置时显示）
                            if (currentSession.apiUrl.isNotBlank()) {
                                Spacer(modifier = Modifier.height(AppSpacing.Xs))
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    StableOutlinedTextField(
                                        value = currentSession.model,
                                        onValueChange = { viewModel.updateCurrentSessionConfig { s -> s.copy(model = it) } },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Model") },
                                        singleLine = true,
                                        textStyle = AppTypography.BodyMedium,
                                        trailingIcon = {
                                            androidx.compose.material3.IconButton(onClick = { showModelDropdown = !showModelDropdown }) {
                                                Icon(Icons.AutoMirrored.Rounded.List, "模型列表")
                                            }
                                        },
                                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primaryColor),
                                        shape = RoundedCornerShape(AppSpacing.Corner.Sm)
                                    )
                                    DropdownMenu(
                                        expanded = showModelDropdown,
                                        onDismissRequest = { showModelDropdown = false },
                                        modifier = Modifier.fillMaxWidth(0.8f).heightIn(max = 240.dp)
                                    ) {
                                        if (availableModels.isEmpty()) {
                                            DropdownMenuItem(
                                                text = { Text("点击刷新拉取模型", style = AppTypography.BodySmall) },
                                                onClick = { viewModel.fetchModelsForCurrentSession() }
                                            )
                                        } else {
                                            availableModels.forEach { model ->
                                                DropdownMenuItem(
                                                    text = { Text(model, style = AppTypography.BodyMedium) },
                                                    onClick = {
                                                        viewModel.updateCurrentSessionConfig { s -> s.copy(model = model) }
                                                        showModelDropdown = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            BlyyPrimaryButton(
                                text = when (connectionState) {
                                    is ConnectionTestState.Testing -> "测试中..."
                                    else -> "测试连接"
                                },
                                onClick = viewModel::testConnectionForCurrentSession,
                                enabled = connectionState !is ConnectionTestState.Testing,
                                icon = Icons.Rounded.Key,
                                modifier = Modifier.fillMaxWidth()
                            )

                            when (val state = connectionState) {
                                is ConnectionTestState.Success -> {
                                    BlyyPanel(accentColor = successColor) {
                                        Row(
                                            modifier = Modifier.padding(AppSpacing.Md).fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
                                        ) {
                                            Icon(Icons.Rounded.Check, null, tint = successColor, modifier = Modifier.size(20.dp))
                                            Text("连接成功", color = successColor, style = AppTypography.BodyMedium)
                                        }
                                    }
                                }
                                is ConnectionTestState.Error -> {
                                    BlyyPanel(accentColor = MaterialTheme.colorScheme.error) {
                                        Row(
                                            modifier = Modifier.padding(AppSpacing.Md).fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
                                        ) {
                                            Icon(Icons.Rounded.BrokenImage, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                            Text(state.message, color = MaterialTheme.colorScheme.error, style = AppTypography.BodySmall)
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }
                    }

                    // ── 聊天增强 ──
                    BlyySectionPanel(
                        title = "聊天增强",
                        icon = Icons.Rounded.SmartToy,
                        accentColor = MaterialTheme.colorScheme.tertiary
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(AppSpacing.Lg),
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("发送语音", style = AppTypography.TitleSmall, fontWeight = FontWeight.Medium)
                                    Text("智能标签匹配或随机发送舰娘语音", style = AppTypography.BodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = currentSession.voiceEnabled,
                                    onCheckedChange = { viewModel.updateCurrentSessionConfig { s -> s.copy(voiceEnabled = it) } }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("发送表情包", style = AppTypography.TitleSmall, fontWeight = FontWeight.Medium)
                                    Text("基于 AI 回复内容自动匹配表情包", style = AppTypography.BodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = currentSession.stickersEnabled,
                                    onCheckedChange = { viewModel.updateCurrentSessionConfig { s -> s.copy(stickersEnabled = it) } }
                                )
                            }

                            if (currentSession.stickersEnabled) {
                                Text("表情包发送概率: ${(currentSession.stickerChance * 100).toInt()}%", style = AppTypography.BodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Slider(
                                    value = currentSession.stickerChance,
                                    onValueChange = { viewModel.updateCurrentSessionConfig { s -> s.copy(stickerChance = it) } },
                                    valueRange = 0f..1f,
                                    steps = 19
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth().clip(cardRounded)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
                                    .clickable { showVoiceShipPicker = true }
                                    .padding(AppSpacing.Md),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
                            ) {
                                ShipAvatarDisplay(
                                    avatarUrl = currentSession.voiceShipAvatar,
                                    size = 40.dp,
                                    placeholderIcon = Icons.Rounded.SmartToy,
                                    placeholderIconSize = 20.dp,
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
                                    iconTint = MaterialTheme.colorScheme.tertiary
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("语音舰娘", style = AppTypography.TitleSmall, fontWeight = FontWeight.Medium)
                                    Text(
                                        text = if (currentSession.voiceShipName.isNotBlank()) currentSession.voiceShipName else "点击选择舰娘",
                                        style = AppTypography.BodySmall,
                                        color = if (currentSession.voiceShipName.isNotBlank()) primaryColor else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (currentSession.voiceEnabled) {
                                Text("随机触发概率: ${(currentSession.voiceRandomChance * 100).toInt()}%", style = AppTypography.BodySmall)
                                Slider(
                                    value = currentSession.voiceRandomChance,
                                    onValueChange = { viewModel.updateCurrentSessionConfig { s -> s.copy(voiceRandomChance = it) } },
                                    valueRange = 0f..1f,
                                    steps = 19
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(AppSpacing.Xl))
                }
            }
        }
    }

    if (showAvatarPicker) {
        AvatarPickerSheet(
            viewModel = viewModel,
            currentAvatarUrl = session?.avatarUrl ?: "",
            onDismiss = { showAvatarPicker = false },
            onAvatarSelected = { url ->
                viewModel.updateCurrentSessionConfig { it.copy(avatarUrl = url) }
                showAvatarPicker = false
            }
        )
    }

    if (showVoiceShipPicker) {
        VoiceShipPickerSheet(
            viewModel = viewModel,
            currentShipName = session?.voiceShipName ?: "",
            onDismiss = { showVoiceShipPicker = false },
            onShipSelected = { ship ->
                viewModel.updateCurrentSessionConfig { it.copy(voiceShipName = ship.name) }
                scope.launch {
                    val reliableAvatar = viewModel.resolveAndCopyShipAvatar(ship.name, ship.avatarUrl, ship.archiveType)
                    viewModel.updateCurrentSessionConfig { it.copy(voiceShipAvatar = reliableAvatar) }
                }
                showVoiceShipPicker = false
            }
        )
    }
}

@Composable
private fun ApiConfigOptionRow(
    name: String,
    subtitle: String,
    isSelected: Boolean,
    primaryColor: Color,
    selectedBg: Color,
    selectedBorder: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.Corner.Md))
            .background(if (isSelected) selectedBg else Color.Transparent)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) selectedBorder else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                RoundedCornerShape(AppSpacing.Corner.Md)
            )
            .clickable { onClick() }
            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (isSelected) primaryColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Key,
                contentDescription = null,
                tint = if (isSelected) primaryColor else primaryColor.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = AppTypography.BodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                color = if (isSelected) primaryColor else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = AppTypography.LabelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        if (isSelected) {
            Icon(Icons.Rounded.Check, contentDescription = "已选择", tint = primaryColor, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * 统一的舰娘头像展示组件（合并原 ShipAvatarDisplay + ShipVoiceAvatarDisplay 的重复实现）。
 *
 * 支持复合格式 URL（primary||fallback），主图加载失败自动回退到 fallback URL，
 * 全部失败时显示占位图标。
 *
 * @param avatarUrl 头像 URL（支持 "primary||fallback" 复合格式）
 * @param size 头像尺寸（dp）
 * @param placeholderIcon 空状态/加载失败的占位图标
 * @param placeholderIconSize 占位图标尺寸（dp）
 * @param containerColor 背景容器色
 * @param iconTint 占位图标 tint 色
 * @param showBorder 是否显示边框
 * @param onClick 点击回调，null 表示不可点击
 */
@Composable
private fun ShipAvatarDisplay(
    avatarUrl: String,
    size: androidx.compose.ui.unit.Dp,
    placeholderIcon: androidx.compose.ui.graphics.vector.ImageVector,
    placeholderIconSize: androidx.compose.ui.unit.Dp,
    containerColor: Color,
    iconTint: Color,
    showBorder: Boolean = false,
    borderColor: Color = Color.Transparent,
    onClick: (() -> Unit)? = null
) {
    val baseModifier = Modifier
        .size(size)
        .clip(CircleShape)
        .background(containerColor)
        .let { if (showBorder) it.border(AppSpacing.Border.Thin, borderColor, CircleShape) else it }
        .let { if (onClick != null) it.clickable { onClick() } else it }

    Box(modifier = baseModifier, contentAlignment = Alignment.Center) {
        if (avatarUrl.isNotBlank()) {
            val (primary, fallback) = remember(avatarUrl) { decodeAvatarUrlForShip(avatarUrl) }
            var currentTarget by remember(avatarUrl) { mutableStateOf(primary) }
            var allFailed by remember(avatarUrl) { mutableStateOf(false) }
            if (currentTarget.isNotBlank() && !allFailed) {
                AsyncImage(
                    model = currentTarget,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onState = { state ->
                        if (state is AsyncImagePainter.State.Error) {
                            if (fallback != null && currentTarget != fallback) currentTarget = fallback else allFailed = true
                        }
                    }
                )
            }
            if (currentTarget.isBlank() || allFailed) {
                Icon(placeholderIcon, null, modifier = Modifier.size(placeholderIconSize), tint = iconTint)
            }
        } else {
            Icon(placeholderIcon, null, modifier = Modifier.size(placeholderIconSize), tint = iconTint)
        }
    }
}

private const val SHIP_AVATAR_DELIMITER = "||"

private fun decodeAvatarUrlForShip(url: String): Pair<String, String?> {
    if (url.isBlank()) return "" to null
    val idx = url.indexOf(SHIP_AVATAR_DELIMITER)
    return if (idx >= 0) {
        url.substring(0, idx) to url.substring(idx + SHIP_AVATAR_DELIMITER.length).takeIf { it.isNotBlank() }
    } else {
        url to null
    }
}
