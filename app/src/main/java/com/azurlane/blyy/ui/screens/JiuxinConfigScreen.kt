package com.azurlane.blyy.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyBottomSheet
import com.azurlane.blyy.ui.components.BlyyPanel
import com.azurlane.blyy.ui.components.BlyyPrimaryButton
import com.azurlane.blyy.ui.components.BlyySectionPanel
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.util.LocalAvatarResolver
import com.azurlane.blyy.viewmodel.ConnectionTestState
import com.azurlane.blyy.viewmodel.JiuxinViewModel
import kotlinx.coroutines.launch

@Composable
fun JiuxinConfigScreen(
    onBack: () -> Unit,
    viewModel: JiuxinViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val apiUrl by viewModel.apiUrl.collectAsStateWithLifecycle()
    val systemPrompt by viewModel.systemPrompt.collectAsStateWithLifecycle()
    val jiuxinName by viewModel.jiuxinName.collectAsStateWithLifecycle()
    val avatarUrl by viewModel.avatarUrl.collectAsStateWithLifecycle()
    val voiceEnabled by viewModel.voiceEnabled.collectAsStateWithLifecycle()
    val voiceRandomChance by viewModel.voiceRandomChance.collectAsStateWithLifecycle()
    val voiceKeywords by viewModel.voiceKeywords.collectAsStateWithLifecycle()
    val voiceShipName by viewModel.voiceShipName.collectAsStateWithLifecycle()
    val voiceShipAvatar by viewModel.voiceShipAvatar.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionTestState.collectAsStateWithLifecycle()
    val selectedModel by viewModel.selectedModel.collectAsStateWithLifecycle()
    val availableModels by viewModel.availableModels.collectAsStateWithLifecycle()
    val stickersEnabled by viewModel.stickersEnabled.collectAsStateWithLifecycle()
    val stickerChance by viewModel.stickerChance.collectAsStateWithLifecycle()

    var showApiKey by remember { mutableStateOf(false) }
    var showAvatarPicker by remember { mutableStateOf(false) }
    var showVoiceShipPicker by remember { mutableStateOf(false) }
    var isModelExpanded by remember { mutableStateOf(false) }

    // 暗色模式判断：用于适配硬编码颜色，确保 WCAG AA 对比度
    val isDark = LocalIsDark.current
    // 连接成功状态色：暗色模式下使用更亮的绿色，确保在深色面板背景上对比度 ≥ 4.5:1
    val successColor = if (isDark) Color(0xFF7FE09B) else Color(0xFF2E7D32)

    LaunchedEffect(Unit) {
        viewModel.fetchModels()
    }

    AdaptiveScreenBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            BlyyTopBar(title = "啾信配置", subtitle = "API 与对话选项", onBackClick = onBack)

            Column(
                modifier = Modifier.fillMaxSize().padding(AppSpacing.Screen.Horizontal).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Gap.Lg)
            ) {
                Spacer(modifier = Modifier.height(AppSpacing.Sm))

                // ── API 配置 ──
                BlyySectionPanel(title = "API 配置", icon = Icons.Rounded.Key, accentColor = MaterialTheme.colorScheme.primary) {
                    Column(modifier = Modifier.fillMaxWidth().padding(AppSpacing.Lg), verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)) {
                        // API Base URL（自动补全 /chat/completions）
                        OutlinedTextField(
                            value = apiUrl, onValueChange = viewModel::saveApiUrl, modifier = Modifier.fillMaxWidth(),
                            label = { Text("API Base URL") },
                            placeholder = { Text("https://api.example.com/v1") },
                            singleLine = true, textStyle = AppTypography.BodyMedium,
                            supportingText = {
                                val fullUrl = viewModel.buildFullApiUrl(apiUrl)
                                if (fullUrl.isNotBlank()) {
                                    Text("请求地址: $fullUrl", style = AppTypography.LabelSmall, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                                } else {
                                    Text("输入 Base URL，自动补全 /chat/completions", style = AppTypography.LabelSmall)
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(AppSpacing.Corner.Sm)
                        )

                        // API Key
                        OutlinedTextField(
                            value = apiKey, onValueChange = viewModel::saveApiKey, modifier = Modifier.fillMaxWidth(),
                            label = { Text("API Key") }, placeholder = { Text("输入 API Key") },
                            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = { IconButton(onClick = { showApiKey = !showApiKey }) { Icon(if (showApiKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
                            singleLine = true, textStyle = AppTypography.BodyMedium,
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(AppSpacing.Corner.Sm)
                        )

                        // Model
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = selectedModel, onValueChange = viewModel::saveModel, modifier = Modifier.fillMaxWidth(),
                                label = { Text("Model") }, placeholder = { Text("如 gpt-4o-mini") },
                                singleLine = true, textStyle = AppTypography.BodyMedium,
                                trailingIcon = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { viewModel.fetchModels() }) {
                                            Icon(Icons.Rounded.Refresh, "刷新模型列表", modifier = Modifier.size(20.dp))
                                        }
                                        IconButton(onClick = { isModelExpanded = !isModelExpanded }) {
                                            Icon(Icons.AutoMirrored.Rounded.List, "模型列表")
                                        }
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(AppSpacing.Corner.Sm)
                            )
                            DropdownMenu(
                                expanded = isModelExpanded,
                                onDismissRequest = { isModelExpanded = false },
                                modifier = Modifier.fillMaxWidth(0.8f).heightIn(max = 300.dp)
                            ) {
                                if (availableModels.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("未拉取到模型", style = AppTypography.BodySmall) },
                                        onClick = { isModelExpanded = false; viewModel.fetchModels() }
                                    )
                                } else {
                                    availableModels.forEach { model ->
                                        DropdownMenuItem(
                                            text = { Text(model, style = AppTypography.BodyMedium) },
                                            onClick = {
                                                viewModel.saveModel(model)
                                                isModelExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // 测试连接
                        BlyyPrimaryButton(
                            text = when (connectionState) { is ConnectionTestState.Testing -> "测试中..."; else -> "测试连接" },
                            onClick = viewModel::testConnection,
                            enabled = connectionState !is ConnectionTestState.Testing && apiKey.isNotBlank() && apiUrl.isNotBlank(),
                            icon = Icons.Rounded.Key, modifier = Modifier.fillMaxWidth()
                        )

                        when (val state = connectionState) {
                            is ConnectionTestState.Success -> {
                                BlyyPanel(accentColor = successColor) {
                                    Row(modifier = Modifier.padding(AppSpacing.Md).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                                        Icon(Icons.Rounded.Check, null, tint = successColor, modifier = Modifier.size(20.dp))
                                        Text("连接成功", color = successColor, style = AppTypography.BodyMedium)
                                    }
                                }
                            }
                            is ConnectionTestState.Error -> {
                                BlyyPanel(accentColor = MaterialTheme.colorScheme.error) {
                                    Row(modifier = Modifier.padding(AppSpacing.Md).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                                        Icon(Icons.Rounded.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                        Text(state.message, color = MaterialTheme.colorScheme.error, style = AppTypography.BodySmall)
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                }

                // ── 人格与名称 ──
                BlyySectionPanel(title = "人格与名称", icon = Icons.Rounded.Psychology, accentColor = MaterialTheme.colorScheme.secondary) {
                    Column(modifier = Modifier.fillMaxWidth().padding(AppSpacing.Lg), verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)) {
                            RobustAvatar(
                                url = avatarUrl,
                                modifier = Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                                    .border(AppSpacing.Border.Thin, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), CircleShape)
                                    .clickable { showAvatarPicker = true },
                                fallbackContent = {
                                    Icon(Icons.Rounded.Person, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text("啾信头像", style = AppTypography.TitleSmall, fontWeight = FontWeight.Medium)
                                Text(text = if (avatarUrl.isNotBlank()) "已选择头像" else "点击选择舰娘头像或上传图片", style = AppTypography.BodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        OutlinedTextField(value = jiuxinName, onValueChange = viewModel::saveJiuxinName, modifier = Modifier.fillMaxWidth(), label = { Text("啾信名称") }, placeholder = { Text("啾信助手") }, singleLine = true, textStyle = AppTypography.BodyMedium, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(AppSpacing.Corner.Sm))
                        OutlinedTextField(value = systemPrompt, onValueChange = viewModel::saveSystemPrompt, modifier = Modifier.fillMaxWidth().height(120.dp), label = { Text("人格提示词") }, placeholder = { Text("描述啾信的人格和行为方式...") }, textStyle = AppTypography.BodyMedium, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(AppSpacing.Corner.Sm))
                    }
                }

                // ── 语音设置 ──
                BlyySectionPanel(title = "聊天增强", icon = Icons.Rounded.SmartToy, accentColor = MaterialTheme.colorScheme.tertiary) {
                    Column(modifier = Modifier.fillMaxWidth().padding(AppSpacing.Lg), verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("发送语音", style = AppTypography.TitleSmall, fontWeight = FontWeight.Medium)
                                Text("智能标签匹配或随机发送舰娘语音", style = AppTypography.BodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = voiceEnabled, onCheckedChange = viewModel::saveVoiceEnabled)
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("发送表情包", style = AppTypography.TitleSmall, fontWeight = FontWeight.Medium)
                                Text("基于 AI 回复内容自动匹配表情包", style = AppTypography.BodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = stickersEnabled, onCheckedChange = viewModel::saveStickersEnabled)
                        }

                        if (stickersEnabled) {
                            Text("表情包发送概率: ${(stickerChance * 100).toInt()}%", style = AppTypography.BodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(
                                value = stickerChance,
                                onValueChange = viewModel::saveStickerChance,
                                valueRange = 0f..1f,
                                steps = 19
                            )
                            Text(
                                "控制 AI 每次回复时发送表情包的概率，滑动调节即可实时生效",
                                style = AppTypography.BodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(AppSpacing.Corner.Md))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
                                .clickable { showVoiceShipPicker = true }
                                .padding(AppSpacing.Md),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
                        ) {
                            RobustAvatar(
                                url = voiceShipAvatar,
                                modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)),
                                fallbackContent = {
                                    Icon(Icons.Rounded.SmartToy, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.tertiary)
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text("语音舰娘", style = AppTypography.TitleSmall, fontWeight = FontWeight.Medium)
                                Text(
                                    text = if (voiceShipName.isNotBlank()) voiceShipName else "点击选择舰娘",
                                    style = AppTypography.BodySmall,
                                    color = if (voiceShipName.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (voiceEnabled) {
                            Text("随机触发概率: ${(voiceRandomChance * 100).toInt()}%", style = AppTypography.BodySmall)
                            Slider(value = voiceRandomChance, onValueChange = viewModel::saveVoiceRandomChance, valueRange = 0f..0.5f, steps = 9)
                            OutlinedTextField(value = voiceKeywords, onValueChange = viewModel::saveVoiceKeywords, modifier = Modifier.fillMaxWidth(), label = { Text("触发关键词") }, placeholder = { Text("你好;早安;晚安") }, supportingText = { Text("用分号 ; 分隔，关键词会自动匹配对应语音场景标签") }, singleLine = true, textStyle = AppTypography.BodyMedium, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(AppSpacing.Corner.Sm))
                            BlyyPanel(accentColor = MaterialTheme.colorScheme.tertiary) {
                                Column(modifier = Modifier.padding(AppSpacing.Md).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppSpacing.Xs)) {
                                    Text("智能标签匹配", style = AppTypography.LabelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                                    Text("关键词自动映射到舰娘语音场景：\n" +
                                        "「你好/早安/晚安」→ 主界面/问候/登录台词\n" +
                                        "「登录/登录界面」→ 登录台词/登录界面\n" +
                                        "「舰船型号/自我介绍」→ 舰船型号/自我介绍\n" +
                                        "「获取台词/查看详情」→ 获取台词/查看详情\n" +
                                        "「触摸/摸头/特殊触摸」→ 触摸台词/摸头台词\n" +
                                        "「任务/邮件」→ 任务提醒/邮件提醒\n" +
                                        "「回港」→ 回港台词\n" +
                                        "「友好/喜欢/爱」→ 好感度语音\n" +
                                        "「誓约/结婚」→ 誓约台词（优先誓约皮肤）\n" +
                                        "「战斗/胜利/失败」→ 战斗场景\n" +
                                        "未匹配时按概率随机触发", style = AppTypography.BodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.Xl))
            }
        }
    }

    if (showAvatarPicker) {
        AvatarPickerSheet(viewModel = viewModel, currentAvatarUrl = avatarUrl, onDismiss = { showAvatarPicker = false }, onAvatarSelected = { viewModel.saveAvatarUrl(it); showAvatarPicker = false })
    }
    if (showVoiceShipPicker) {
        VoiceShipPickerSheet(
            viewModel = viewModel,
            currentShipName = voiceShipName,
            onDismiss = { showVoiceShipPicker = false },
            onShipSelected = { ship ->
                viewModel.saveVoiceShipName(ship.name)
                // 选中语音舰娘时，复制 asset 到内部存储，确保 file:// 路径可靠加载
                scope.launch {
                    val reliableAvatar = viewModel.resolveAndCopyShipAvatar(ship.name, ship.avatarUrl)
                    viewModel.saveVoiceShipAvatar(reliableAvatar)
                }
                showVoiceShipPicker = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AvatarPickerSheet(viewModel: JiuxinViewModel, currentAvatarUrl: String, onDismiss: () -> Unit, onAvatarSelected: (String) -> Unit) {
    val filteredShips by viewModel.filteredShipList.collectAsStateWithLifecycle()
    val searchQuery by viewModel.shipSearchQuery.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imagePickerLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            scope.launch {
                // 复制到内部存储，避免 content:// URI 重启后丢失权限
                val filePath = viewModel.copyAvatarToInternalStorage(it)
                if (filePath != null) {
                    onAvatarSelected(filePath)
                }
            }
        }
    }
    var avatarTab by remember { mutableStateOf(0) }

    BlyyBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().height(520.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text = "选择头像", style = AppTypography.TitleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "关闭") }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Lg), horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                com.azurlane.blyy.ui.components.BlyyChip(label = "舰娘头像", selected = avatarTab == 0, onClick = { avatarTab = 0 })
                com.azurlane.blyy.ui.components.BlyyChip(label = "本地上传", selected = avatarTab == 1, onClick = { avatarTab = 1 })
            }
            Spacer(modifier = Modifier.height(AppSpacing.Sm))
            if (avatarTab == 0) {
                OutlinedTextField(value = searchQuery, onValueChange = viewModel::setShipSearchQuery, modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Lg), placeholder = { Text("搜索舰娘...") }, singleLine = true, textStyle = AppTypography.BodyMedium, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(AppSpacing.Corner.Sm))
                Spacer(modifier = Modifier.height(AppSpacing.Sm))
                LazyVerticalGrid(columns = GridCells.Fixed(5), modifier = Modifier.fillMaxSize().padding(horizontal = AppSpacing.Lg), horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm), verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                    items(filteredShips, key = { it.name }) { ship ->
                        // 优先使用本地高清头像，匹配不到回退网络 URL
                        val effectiveAvatar = remember(ship.name, ship.avatarUrl) {
                            LocalAvatarResolver.resolveOrDefault(context, ship.name, ship.avatarUrl)
                        }
                        val isSelected = effectiveAvatar == currentAvatarUrl
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(modifier = Modifier.size(52.dp).clip(CircleShape).border(if (isSelected) 2.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), CircleShape).clickable {
                                // 选中舰娘头像时，复制 asset 到内部存储，确保 file:// 路径可靠加载
                                scope.launch {
                                    val reliablePath = viewModel.resolveAndCopyShipAvatar(ship.name, ship.avatarUrl)
                                    onAvatarSelected(reliablePath)
                                }
                            }, contentAlignment = Alignment.Center) { RobustAvatar(url = effectiveAvatar, modifier = Modifier.size(52.dp).clip(CircleShape), fallbackContent = { Icon(Icons.Rounded.Person, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }) }
                            Text(text = ship.name, style = AppTypography.LabelSmall.copy(fontSize = 10.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp))
                        }
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = AppSpacing.Lg), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    if (currentAvatarUrl.isNotBlank()) {
                        RobustAvatar(url = currentAvatarUrl, modifier = Modifier.size(120.dp).clip(CircleShape).border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), CircleShape), fallbackContent = { Icon(Icons.Rounded.Person, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) })
                        Spacer(modifier = Modifier.height(AppSpacing.Md)); Text("当前头像", style = AppTypography.BodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(modifier = Modifier.height(AppSpacing.Lg))
                    }
                    BlyyPrimaryButton(text = "从相册选择图片", onClick = { imagePickerLauncher.launch(arrayOf("image/*")) }, icon = Icons.Rounded.AddPhotoAlternate, modifier = Modifier.fillMaxWidth(0.7f))
                    Spacer(modifier = Modifier.height(AppSpacing.Md))
                    Text("支持 JPG、PNG 格式，建议使用正方形图片", style = AppTypography.BodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    if (currentAvatarUrl.isNotBlank()) { Spacer(modifier = Modifier.height(AppSpacing.Lg)); Text("清除头像", style = AppTypography.BodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.clickable { onAvatarSelected("") }) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceShipPickerSheet(
    viewModel: JiuxinViewModel,
    currentShipName: String,
    onDismiss: () -> Unit,
    onShipSelected: (Ship) -> Unit
) {
    val filteredShips by viewModel.filteredShipList.collectAsStateWithLifecycle()
    val searchQuery by viewModel.shipSearchQuery.collectAsStateWithLifecycle()
    val context = LocalContext.current

    BlyyBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().height(480.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "选择语音舰娘", style = AppTypography.TitleMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                    if (currentShipName.isNotBlank()) {
                        Text(
                            text = "清除",
                            style = AppTypography.BodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.clickable {
                                viewModel.saveVoiceShipName("")
                                viewModel.saveVoiceShipAvatar("")
                                onDismiss()
                            }.padding(AppSpacing.Sm)
                        )
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "关闭") }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::setShipSearchQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Lg),
                placeholder = { Text("搜索舰娘名称...") },
                singleLine = true,
                textStyle = AppTypography.BodyMedium,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(AppSpacing.Corner.Sm)
            )

            Spacer(modifier = Modifier.height(AppSpacing.Sm))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(filteredShips, key = { it.name }) { ship ->
                        val isSelected = ship.name == currentShipName
                        // 优先使用本地高清头像
                        val effectiveAvatar = remember(ship.name, ship.avatarUrl) {
                            LocalAvatarResolver.resolveOrDefault(context, ship.name, ship.avatarUrl)
                        }
                        Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(AppSpacing.Corner.Md))
                            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent)
                            .clickable { onShipSelected(ship) }
                            .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
                    ) {
                        RobustAvatar(
                            url = effectiveAvatar,
                            modifier = Modifier.size(40.dp).clip(CircleShape)
                                .border(if (isSelected) 2.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), CircleShape),
                            fallbackContent = {
                                Icon(Icons.Rounded.SmartToy, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = ship.name, style = AppTypography.TitleSmall, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(text = "${ship.type} · ${ship.faction}", style = AppTypography.BodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (isSelected) {
                            Icon(Icons.Rounded.Check, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
