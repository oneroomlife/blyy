package com.azurlane.blyy.ui.screens

import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Image
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
import androidx.compose.material3.TextButton
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
import androidx.compose.material3.AlertDialog
import com.azurlane.blyy.data.model.ApiConfig
import com.azurlane.blyy.data.model.JiuxinPreset
import com.azurlane.blyy.data.model.PersonaConfig
import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyBottomSheet
import com.azurlane.blyy.ui.components.BlyyPanel
import com.azurlane.blyy.ui.components.BlyyPrimaryButton
import com.azurlane.blyy.ui.components.BlyySectionPanel
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.components.StableOutlinedTextField
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
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val apiConfigs by viewModel.apiConfigs.collectAsStateWithLifecycle()
    val personaConfigs by viewModel.personaConfigs.collectAsStateWithLifecycle()

    var showApiKey by remember { mutableStateOf(false) }
    var showAvatarPicker by remember { mutableStateOf(false) }
    var showVoiceShipPicker by remember { mutableStateOf(false) }
    var isModelExpanded by remember { mutableStateOf(false) }

    // 预设管理状态
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var editingPreset by remember { mutableStateOf<JiuxinPreset?>(null) }
    var showDeletePresetConfirm by remember { mutableStateOf<JiuxinPreset?>(null) }
    var presetNameInput by remember { mutableStateOf("") }

    // API 配置管理状态
    var showSaveApiConfigDialog by remember { mutableStateOf(false) }
    var editingApiConfig by remember { mutableStateOf<ApiConfig?>(null) }
    var showDeleteApiConfigConfirm by remember { mutableStateOf<ApiConfig?>(null) }
    var apiConfigNameInput by remember { mutableStateOf("") }

    // 舰娘人格配置管理状态
    var showSavePersonaConfigDialog by remember { mutableStateOf(false) }
    var editingPersonaConfig by remember { mutableStateOf<PersonaConfig?>(null) }
    var showDeletePersonaConfigConfirm by remember { mutableStateOf<PersonaConfig?>(null) }
    var personaConfigNameInput by remember { mutableStateOf("") }
    var showClearPersonaConfirm by remember { mutableStateOf(false) }

    // ── 分区导航状态：null 表示主菜单，非 null 表示当前展开的分区 ──
    var activeSection by remember { mutableStateOf<ConfigSection?>(null) }

    // 暗色模式判断：用于适配硬编码颜色，确保 WCAG AA 对比度
    val isDark = LocalIsDark.current
    // 连接成功状态色：暗色模式下使用更亮的绿色，确保在深色面板背景上对比度 ≥ 4.5:1
    val successColor = if (isDark) Color(0xFF7FE09B) else Color(0xFF2E7D32)

    LaunchedEffect(Unit) {
        viewModel.fetchModels()
    }

    AdaptiveScreenBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏：在分区详情页显示返回按钮，主菜单显示原始返回
            BlyyTopBar(
                title = activeSection?.title ?: "啾信配置",
                subtitle = activeSection?.subtitle ?: "API 与对话选项",
                onBackClick = {
                    if (activeSection != null) activeSection = null else onBack()
                }
            )

            val section = activeSection
            if (section == null) {
                // ── 主菜单：分区卡片列表 ──
                ConfigMainMenu(
                    presets = presets,
                    apiConfigs = apiConfigs,
                    personaConfigs = personaConfigs,
                    isApiReady = apiUrl.isNotBlank() && apiKey.isNotBlank(),
                    isPersonaReady = jiuxinName.isNotBlank() || systemPrompt.isNotBlank(),
                    voiceEnabled = voiceEnabled,
                    stickersEnabled = stickersEnabled,
                    onSectionClick = { activeSection = it }
                )
            } else {
                // ── 分区详情页 ──
                when (section) {
                    ConfigSection.PRESETS -> PresetsSection(
                        presets = presets,
                        jiuxinName = jiuxinName,
                        selectedModel = selectedModel,
                        voiceShipName = voiceShipName,
                        apiUrl = apiUrl,
                        apiKey = apiKey,
                        onSavePreset = {
                            presetNameInput = jiuxinName
                            editingPreset = null
                            showSavePresetDialog = true
                        },
                        onApplyPreset = { preset ->
                            viewModel.applyPreset(preset)
                            Toast.makeText(context, "已应用预设「${preset.name}」", Toast.LENGTH_SHORT).show()
                        },
                        onEditPreset = { preset ->
                            presetNameInput = preset.name
                            editingPreset = preset
                            showSavePresetDialog = true
                        },
                        onDeletePreset = { showDeletePresetConfirm = it }
                    )
                    ConfigSection.API -> ApiSection(
                        apiUrl = apiUrl,
                        apiKey = apiKey,
                        selectedModel = selectedModel,
                        availableModels = availableModels,
                        connectionState = connectionState,
                        showApiKey = showApiKey,
                        isModelExpanded = isModelExpanded,
                        apiConfigs = apiConfigs,
                        successColor = successColor,
                        onSaveApiUrl = viewModel::saveApiUrl,
                        onSaveApiKey = viewModel::saveApiKey,
                        onSaveModel = viewModel::saveModel,
                        onToggleShowApiKey = { showApiKey = !showApiKey },
                        onToggleModelExpanded = { isModelExpanded = !isModelExpanded },
                        onFetchModels = viewModel::fetchModels,
                        onTestConnection = viewModel::testConnection,
                        buildFullApiUrl = viewModel::buildFullApiUrl,
                        onSaveApiConfig = {
                            apiConfigNameInput = ""
                            editingApiConfig = null
                            showSaveApiConfigDialog = true
                        },
                        onApplyApiConfig = { config ->
                            viewModel.applyApiConfig(config)
                            Toast.makeText(context, "已应用 API 配置「${config.name}」", Toast.LENGTH_SHORT).show()
                        },
                        onEditApiConfig = { config ->
                            apiConfigNameInput = config.name
                            editingApiConfig = config
                            showSaveApiConfigDialog = true
                        },
                        onDeleteApiConfig = { showDeleteApiConfigConfirm = it }
                    )
                    ConfigSection.PERSONA -> PersonaSection(
                        avatarUrl = avatarUrl,
                        jiuxinName = jiuxinName,
                        systemPrompt = systemPrompt,
                        personaConfigs = personaConfigs,
                        voiceEnabled = voiceEnabled,
                        voiceRandomChance = voiceRandomChance,
                        voiceKeywords = voiceKeywords,
                        voiceShipName = voiceShipName,
                        voiceShipAvatar = voiceShipAvatar,
                        stickersEnabled = stickersEnabled,
                        stickerChance = stickerChance,
                        onPickAvatar = { showAvatarPicker = true },
                        onSaveJiuxinName = viewModel::saveJiuxinName,
                        onSaveSystemPrompt = viewModel::saveSystemPrompt,
                        onSavePersonaConfig = {
                            personaConfigNameInput = jiuxinName
                            editingPersonaConfig = null
                            showSavePersonaConfigDialog = true
                        },
                        onApplyPersonaConfig = { config ->
                            viewModel.applyPersonaConfig(config)
                            Toast.makeText(context, "已应用舰娘人格「${config.name}」", Toast.LENGTH_SHORT).show()
                        },
                        onEditPersonaConfig = { config ->
                            personaConfigNameInput = config.name
                            editingPersonaConfig = config
                            showSavePersonaConfigDialog = true
                        },
                        onDeletePersonaConfig = { showDeletePersonaConfigConfirm = it },
                        onClearPersonaFields = {
                            showClearPersonaConfirm = true
                        },
                        onSaveVoiceEnabled = viewModel::saveVoiceEnabled,
                        onSaveVoiceRandomChance = viewModel::saveVoiceRandomChance,
                        onSaveVoiceKeywords = viewModel::saveVoiceKeywords,
                        onSaveStickersEnabled = viewModel::saveStickersEnabled,
                        onSaveStickerChance = viewModel::saveStickerChance,
                        onPickVoiceShip = { showVoiceShipPicker = true }
                    )
                }
            }
        }
    }

    if (showAvatarPicker) {
        AvatarPickerSheet(viewModel = viewModel, currentAvatarUrl = avatarUrl, onDismiss = { showAvatarPicker = false }, onAvatarSelected = { viewModel.saveAvatarUrl(it); showAvatarPicker = false })
    }
    // 保存/编辑预设对话框
    if (showSavePresetDialog) {
        val isEditing = editingPreset != null
        // 检查重名（排除正在编辑的预设自身）
        val isDuplicateName = presets.any { it.name == presetNameInput.trim() && it.id != editingPreset?.id }
        // 检查 API 配置是否完整
        val isApiConfigured = apiUrl.isNotBlank() && apiKey.isNotBlank()
        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            title = { Text(if (isEditing) "编辑预设" else "保存为预设") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                    Text(
                        if (isEditing) "修改预设名称后保存将覆盖该预设的当前内容"
                        else "将当前 API、人格、语音等全部配置保存为预设，供新建对话时快速应用",
                        style = AppTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 配置摘要：让用户知道将保存哪些配置
                    Text(
                        text = buildString {
                            append("将保存：")
                            if (jiuxinName.isNotBlank()) append(" $jiuxinName")
                            if (selectedModel.isNotBlank()) append(" · $selectedModel")
                            if (voiceShipName.isNotBlank()) append(" · 语音:$voiceShipName")
                            if (isEmpty()) append("（当前无配置）")
                        },
                        style = AppTypography.LabelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    // API 配置缺失警告
                    if (!isApiConfigured) {
                        Text(
                            text = "⚠ 当前 API URL 或密钥为空，使用此预设的对话将无法发送消息",
                            style = AppTypography.LabelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    StableOutlinedTextField(
                        value = presetNameInput,
                        onValueChange = { presetNameInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("预设名称（如舰娘名）") },
                        placeholder = { Text("标枪") },
                        singleLine = true,
                        textStyle = AppTypography.BodyMedium,
                        isError = isDuplicateName,
                        supportingText = if (isDuplicateName) {
                            { Text("已存在同名预设，请使用其他名称", color = MaterialTheme.colorScheme.error) }
                        } else null,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            errorBorderColor = MaterialTheme.colorScheme.error
                        ),
                        shape = RoundedCornerShape(AppSpacing.Corner.Sm)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when {
                            presetNameInput.isBlank() -> {
                                Toast.makeText(context, "请输入预设名称", Toast.LENGTH_SHORT).show()
                            }
                            isDuplicateName -> {
                                Toast.makeText(context, "已存在同名预设，请使用其他名称", Toast.LENGTH_SHORT).show()
                            }
                            else -> {
                                viewModel.saveCurrentAsPreset(presetNameInput, editingPreset?.id)
                                val msg = if (editingPreset != null) "预设已更新" else "预设已保存"
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                showSavePresetDialog = false
                                editingPreset = null
                                presetNameInput = ""
                            }
                        }
                    }
                ) { Text("保存", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showSavePresetDialog = false; editingPreset = null }) { Text("取消") }
            }
        )
    }
    // 删除预设确认对话框
    showDeletePresetConfirm?.let { preset ->
        AlertDialog(
            onDismissRequest = { showDeletePresetConfirm = null },
            title = { Text("删除预设") },
            text = { Text("确定要删除预设「${preset.name}」吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePreset(preset.id)
                    showDeletePresetConfirm = null
                    Toast.makeText(context, "已删除预设「${preset.name}」", Toast.LENGTH_SHORT).show()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeletePresetConfirm = null }) { Text("取消") }
            }
        )
    }
    // 保存/编辑 API 配置对话框
    if (showSaveApiConfigDialog) {
        val isEditing = editingApiConfig != null
        val isDuplicateName = apiConfigs.any { it.name == apiConfigNameInput.trim() && it.id != editingApiConfig?.id }
        val isApiConfigured = apiUrl.isNotBlank() && apiKey.isNotBlank()
        AlertDialog(
            onDismissRequest = { showSaveApiConfigDialog = false },
            title = { Text(if (isEditing) "编辑 API 配置" else "保存为 API 配置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                    Text(
                        if (isEditing) "修改名称后保存将覆盖该 API 配置的当前内容"
                        else "将当前 API URL、Key、Model 保存为独立配置，可与其他舰娘人格组合使用",
                        style = AppTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = buildString {
                            append("将保存：")
                            if (selectedModel.isNotBlank()) append(" $selectedModel")
                            if (apiUrl.isNotBlank()) {
                                if (isNotEmpty()) append(" · ")
                                append(apiUrl.take(40))
                            }
                            if (isEmpty()) append("（当前无 API 配置）")
                        },
                        style = AppTypography.LabelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    if (!isApiConfigured) {
                        Text(
                            text = "⚠ 当前 API URL 或密钥为空",
                            style = AppTypography.LabelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    StableOutlinedTextField(
                        value = apiConfigNameInput,
                        onValueChange = { apiConfigNameInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("配置名称（如 OpenAI 官方）") },
                        placeholder = { Text("OpenAI 官方") },
                        singleLine = true,
                        textStyle = AppTypography.BodyMedium,
                        isError = isDuplicateName,
                        supportingText = if (isDuplicateName) {
                            { Text("已存在同名配置，请使用其他名称", color = MaterialTheme.colorScheme.error) }
                        } else null,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            errorBorderColor = MaterialTheme.colorScheme.error
                        ),
                        shape = RoundedCornerShape(AppSpacing.Corner.Sm)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when {
                            apiConfigNameInput.isBlank() -> {
                                Toast.makeText(context, "请输入配置名称", Toast.LENGTH_SHORT).show()
                            }
                            isDuplicateName -> {
                                Toast.makeText(context, "已存在同名配置，请使用其他名称", Toast.LENGTH_SHORT).show()
                            }
                            else -> {
                                viewModel.saveApiConfig(apiConfigNameInput, editingApiConfig?.id)
                                val msg = if (editingApiConfig != null) "API 配置已更新" else "API 配置已保存"
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                showSaveApiConfigDialog = false
                                editingApiConfig = null
                                apiConfigNameInput = ""
                            }
                        }
                    }
                ) { Text("保存", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveApiConfigDialog = false; editingApiConfig = null }) { Text("取消") }
            }
        )
    }
    // 删除 API 配置确认对话框
    showDeleteApiConfigConfirm?.let { config ->
        AlertDialog(
            onDismissRequest = { showDeleteApiConfigConfirm = null },
            title = { Text("删除 API 配置") },
            text = { Text("确定要删除 API 配置「${config.name}」吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteApiConfig(config.id)
                    showDeleteApiConfigConfirm = null
                    Toast.makeText(context, "已删除 API 配置「${config.name}」", Toast.LENGTH_SHORT).show()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteApiConfigConfirm = null }) { Text("取消") }
            }
        )
    }
    // 保存/编辑舰娘人格配置对话框
    if (showSavePersonaConfigDialog) {
        val isEditing = editingPersonaConfig != null
        val isDuplicateName = personaConfigs.any { it.name == personaConfigNameInput.trim() && it.id != editingPersonaConfig?.id }
        AlertDialog(
            onDismissRequest = { showSavePersonaConfigDialog = false },
            title = { Text(if (isEditing) "编辑舰娘人格" else "保存为舰娘人格") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                    Text(
                        if (isEditing) "修改名称后保存将覆盖该舰娘人格的当前内容"
                        else "将当前头像、名称、人格提示词、语音、表情包等保存为独立人格，可与不同 API 配置组合使用",
                        style = AppTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = buildString {
                            append("将保存：")
                            if (jiuxinName.isNotBlank()) append(" $jiuxinName")
                            if (voiceShipName.isNotBlank()) {
                                if (isNotEmpty()) append(" · ")
                                append("语音:$voiceShipName")
                            }
                            if (isEmpty()) append("（当前无舰娘人格配置）")
                        },
                        style = AppTypography.LabelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    StableOutlinedTextField(
                        value = personaConfigNameInput,
                        onValueChange = { personaConfigNameInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("舰娘名称（如 标枪）") },
                        placeholder = { Text("标枪") },
                        singleLine = true,
                        textStyle = AppTypography.BodyMedium,
                        isError = isDuplicateName,
                        supportingText = if (isDuplicateName) {
                            { Text("已存在同名人格，请使用其他名称", color = MaterialTheme.colorScheme.error) }
                        } else null,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            errorBorderColor = MaterialTheme.colorScheme.error
                        ),
                        shape = RoundedCornerShape(AppSpacing.Corner.Sm)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when {
                            personaConfigNameInput.isBlank() -> {
                                Toast.makeText(context, "请输入舰娘名称", Toast.LENGTH_SHORT).show()
                            }
                            isDuplicateName -> {
                                Toast.makeText(context, "已存在同名人格，请使用其他名称", Toast.LENGTH_SHORT).show()
                            }
                            else -> {
                                viewModel.savePersonaConfig(personaConfigNameInput, editingPersonaConfig?.id)
                                val msg = if (editingPersonaConfig != null) "舰娘人格已更新" else "舰娘人格已保存"
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                showSavePersonaConfigDialog = false
                                editingPersonaConfig = null
                                personaConfigNameInput = ""
                            }
                        }
                    }
                ) { Text("保存", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showSavePersonaConfigDialog = false; editingPersonaConfig = null }) { Text("取消") }
            }
        )
    }
    // 删除舰娘人格配置确认对话框
    showDeletePersonaConfigConfirm?.let { config ->
        AlertDialog(
            onDismissRequest = { showDeletePersonaConfigConfirm = null },
            title = { Text("删除舰娘人格") },
            text = { Text("确定要删除舰娘人格「${config.name}」吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePersonaConfig(config.id)
                    showDeletePersonaConfigConfirm = null
                    Toast.makeText(context, "已删除舰娘人格「${config.name}」", Toast.LENGTH_SHORT).show()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeletePersonaConfigConfirm = null }) { Text("取消") }
            }
        )
    }
    // 清空当前舰娘人格确认对话框
    if (showClearPersonaConfirm) {
        AlertDialog(
            onDismissRequest = { showClearPersonaConfirm = false },
            title = { Text("清空当前配置") },
            text = { Text("将清空当前头像、名称、人格提示词、语音和表情包设置，方便重新填写。已保存的舰娘人格不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearPersonaFields()
                    showClearPersonaConfirm = false
                    Toast.makeText(context, "已清空当前配置", Toast.LENGTH_SHORT).show()
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearPersonaConfirm = false }) { Text("取消") }
            }
        )
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

// ════════════════════════════════════════════════════════════
// 配置分区导航：主菜单 + 各分区详情页
// ════════════════════════════════════════════════════════════

/**
 * 配置分区枚举：每个分区对应一个独立配置页
 */
private enum class ConfigSection(
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val description: String,
    val accentColorRole: AccentColorRole
) {
    PRESETS(
        title = "舰娘预设",
        subtitle = "完整配置快照",
        icon = Icons.Rounded.Bookmark,
        description = "保存 API + 人格 + 语音 + 表情包为完整预设，一键应用",
        accentColorRole = AccentColorRole.TERTIARY
    ),
    API(
        title = "API 配置",
        subtitle = "大模型连接",
        icon = Icons.Rounded.Key,
        description = "管理 API URL、Key、Model，可保存多套供组合使用",
        accentColorRole = AccentColorRole.PRIMARY
    ),
    PERSONA(
        title = "舰娘人格",
        subtitle = "头像 · 名称 · 语音 · 表情",
        icon = Icons.Rounded.Psychology,
        description = "舰娘头像、名称、人格提示词、语音触发、表情包等完整人格配置",
        accentColorRole = AccentColorRole.SECONDARY
    )
}

private enum class AccentColorRole { PRIMARY, SECONDARY, TERTIARY }

@Composable
private fun AccentColorRole.toColor(): Color = when (this) {
    AccentColorRole.PRIMARY -> MaterialTheme.colorScheme.primary
    AccentColorRole.SECONDARY -> MaterialTheme.colorScheme.secondary
    AccentColorRole.TERTIARY -> MaterialTheme.colorScheme.tertiary
}

/**
 * 主菜单：分区卡片列表
 *
 * 每张卡片显示分区图标、标题、描述和当前状态徽章，
 * 点击进入对应分区详情页。状态徽章实时反映该分区配置的可用性。
 */
@Composable
private fun ConfigMainMenu(
    presets: List<JiuxinPreset>,
    apiConfigs: List<ApiConfig>,
    personaConfigs: List<PersonaConfig>,
    isApiReady: Boolean,
    isPersonaReady: Boolean,
    voiceEnabled: Boolean,
    stickersEnabled: Boolean,
    onSectionClick: (ConfigSection) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.Screen.Horizontal)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
    ) {
        Spacer(modifier = Modifier.height(AppSpacing.Sm))

        // 顶部概览卡片
        BlyyPanel(accentColor = MaterialTheme.colorScheme.primary) {
            Column(modifier = Modifier.padding(AppSpacing.Lg).fillMaxWidth()) {
                Text(
                    "配置中心",
                    style = AppTypography.TitleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(AppSpacing.Xs))
                Text(
                    "按分区管理啾信配置。API 配置与舰娘人格解耦，可独立保存多套并在新建对话时自由组合。",
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(AppSpacing.Md))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
                ) {
                    ConfigStatPill(label = "预设", count = presets.size, color = MaterialTheme.colorScheme.tertiary)
                    ConfigStatPill(label = "API", count = apiConfigs.size, color = MaterialTheme.colorScheme.primary)
                    ConfigStatPill(label = "人格", count = personaConfigs.size, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }

        // 分区卡片
        ConfigSection.entries.forEach { section ->
            val statusText = when (section) {
                ConfigSection.PRESETS -> if (presets.isNotEmpty()) "${presets.size} 套" else "未配置"
                ConfigSection.API -> if (isApiReady) "已配置" else "未配置"
                ConfigSection.PERSONA -> buildString {
                    if (isPersonaReady) append("已配置") else append("未配置")
                    val extras = mutableListOf<String>()
                    if (voiceEnabled) extras.add("语音")
                    if (stickersEnabled) extras.add("表情")
                    if (extras.isNotEmpty()) append(" · ${extras.joinToString("/")}")
                }
            }
            val statusColor = when (section) {
                ConfigSection.PRESETS -> if (presets.isNotEmpty()) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
                ConfigSection.API -> if (isApiReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                ConfigSection.PERSONA -> if (isPersonaReady) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline
            }
            ConfigSectionCard(
                section = section,
                statusText = statusText,
                statusColor = statusColor,
                onClick = { onSectionClick(section) }
            )
        }

        Spacer(modifier = Modifier.height(AppSpacing.Xl))
    }
}

@Composable
private fun ConfigStatPill(label: String, count: Int, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppSpacing.Corner.Sm))
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            "$count",
            style = AppTypography.TitleSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            label,
            style = AppTypography.LabelSmall,
            color = color.copy(alpha = 0.8f)
        )
    }
}

/**
 * 分区导航卡片：图标 + 标题 + 描述 + 状态徽章 + 右箭头
 */
@Composable
private fun ConfigSectionCard(
    section: ConfigSection,
    statusText: String,
    statusColor: Color,
    onClick: () -> Unit
) {
    val accentColor = section.accentColorRole.toColor()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.Corner.Lg))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(AppSpacing.Corner.Lg))
            .clickable(onClick = onClick)
            .padding(AppSpacing.Lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
    ) {
        // 图标徽章
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(AppSpacing.Corner.Md))
                .background(accentColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                section.icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(24.dp)
            )
        }
        // 标题 + 描述
        Column(modifier = Modifier.weight(1f)) {
            Text(
                section.title,
                style = AppTypography.TitleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                section.description,
                style = AppTypography.BodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 状态徽章
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(AppSpacing.Corner.Sm))
                .background(statusColor.copy(alpha = 0.12f))
                .padding(horizontal = AppSpacing.Sm, vertical = 4.dp)
        ) {
            Text(
                statusText,
                style = AppTypography.LabelSmall,
                fontWeight = FontWeight.Medium,
                color = statusColor
            )
        }
        // 右箭头
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = "进入",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
    }
}

// ════════════════════════════════════════════════════════════
// 分区详情页：预设管理
// ════════════════════════════════════════════════════════════

@Composable
private fun PresetsSection(
    presets: List<JiuxinPreset>,
    jiuxinName: String,
    selectedModel: String,
    voiceShipName: String,
    apiUrl: String,
    apiKey: String,
    onSavePreset: () -> Unit,
    onApplyPreset: (JiuxinPreset) -> Unit,
    onEditPreset: (JiuxinPreset) -> Unit,
    onDeletePreset: (JiuxinPreset) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.Screen.Horizontal)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
    ) {
        Spacer(modifier = Modifier.height(AppSpacing.Sm))

        BlyySectionPanel(
            title = "舰娘预设",
            icon = Icons.Rounded.Bookmark,
            accentColor = MaterialTheme.colorScheme.tertiary
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(AppSpacing.Lg),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
            ) {
                Text(
                    "预设是完整的配置快照，包含 API、人格、语音、表情包等全部设置。新建对话时可一键应用，无需重复配置。",
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 当前配置摘要
                BlyyPanel(accentColor = MaterialTheme.colorScheme.tertiary) {
                    Column(modifier = Modifier.padding(AppSpacing.Md).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppSpacing.Xs)) {
                        Text("当前将保存的配置", style = AppTypography.LabelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                        Text(
                            buildString {
                                if (jiuxinName.isNotBlank()) append("舰娘: $jiuxinName")
                                if (selectedModel.isNotBlank()) {
                                    if (isNotEmpty()) append("\n")
                                    append("模型: $selectedModel")
                                }
                                if (voiceShipName.isNotBlank()) {
                                    if (isNotEmpty()) append("\n")
                                    append("语音: $voiceShipName")
                                }
                                if (apiUrl.isNotBlank()) {
                                    if (isNotEmpty()) append("\n")
                                    append("API: ${apiUrl.take(30)}")
                                }
                                if (isEmpty()) append("（当前无配置）")
                            },
                            style = AppTypography.BodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                BlyyPrimaryButton(
                    text = "保存当前配置为预设",
                    onClick = onSavePreset,
                    icon = Icons.Rounded.Bookmark,
                    modifier = Modifier.fillMaxWidth()
                )
                if (presets.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(AppSpacing.Xs))
                    Text(
                        "已保存预设 (${presets.size})",
                        style = AppTypography.LabelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    presets.forEach { preset ->
                        PresetCard(
                            preset = preset,
                            onApply = { onApplyPreset(preset) },
                            onEdit = { onEditPreset(preset) },
                            onDelete = { onDeletePreset(preset) }
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(AppSpacing.Lg),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                            Icon(Icons.Rounded.Bookmark, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f), modifier = Modifier.size(40.dp))
                            Text("暂无保存的预设", style = AppTypography.BodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("保存当前配置为预设后，可在此查看和管理", style = AppTypography.BodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(AppSpacing.Xl))
    }
}

// ════════════════════════════════════════════════════════════
// 分区详情页：API 配置
// ════════════════════════════════════════════════════════════

@Composable
private fun ApiSection(
    apiUrl: String,
    apiKey: String,
    selectedModel: String,
    availableModels: List<String>,
    connectionState: ConnectionTestState,
    showApiKey: Boolean,
    isModelExpanded: Boolean,
    apiConfigs: List<ApiConfig>,
    successColor: Color,
    onSaveApiUrl: (String) -> Unit,
    onSaveApiKey: (String) -> Unit,
    onSaveModel: (String) -> Unit,
    onToggleShowApiKey: () -> Unit,
    onToggleModelExpanded: () -> Unit,
    onFetchModels: () -> Unit,
    onTestConnection: () -> Unit,
    buildFullApiUrl: (String) -> String,
    onSaveApiConfig: () -> Unit,
    onApplyApiConfig: (ApiConfig) -> Unit,
    onEditApiConfig: (ApiConfig) -> Unit,
    onDeleteApiConfig: (ApiConfig) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.Screen.Horizontal)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
    ) {
        Spacer(modifier = Modifier.height(AppSpacing.Sm))

        BlyySectionPanel(title = "当前 API 配置", icon = Icons.Rounded.Key, accentColor = MaterialTheme.colorScheme.primary) {
            Column(modifier = Modifier.fillMaxWidth().padding(AppSpacing.Lg), verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)) {
                StableOutlinedTextField(
                    value = apiUrl, onValueChange = onSaveApiUrl, modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Base URL") },
                    placeholder = { Text("https://api.example.com/v1") },
                    singleLine = true, textStyle = AppTypography.BodyMedium,
                    supportingText = {
                        val fullUrl = buildFullApiUrl(apiUrl)
                        if (fullUrl.isNotBlank()) {
                            Text("请求地址: $fullUrl", style = AppTypography.LabelSmall, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                        } else {
                            Text("输入 Base URL，自动补全 /chat/completions", style = AppTypography.LabelSmall)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(AppSpacing.Corner.Sm)
                )
                StableOutlinedTextField(
                    value = apiKey, onValueChange = onSaveApiKey, modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key") }, placeholder = { Text("输入 API Key") },
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = onToggleShowApiKey) { Icon(if (showApiKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
                    singleLine = true, textStyle = AppTypography.BodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(AppSpacing.Corner.Sm)
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    StableOutlinedTextField(
                        value = selectedModel, onValueChange = onSaveModel, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Model") }, placeholder = { Text("如 gpt-4o-mini") },
                        singleLine = true, textStyle = AppTypography.BodyMedium,
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = onFetchModels) {
                                    Icon(Icons.Rounded.Refresh, "刷新模型列表", modifier = Modifier.size(20.dp))
                                }
                                IconButton(onClick = onToggleModelExpanded) {
                                    Icon(Icons.AutoMirrored.Rounded.List, "模型列表")
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(AppSpacing.Corner.Sm)
                    )
                    DropdownMenu(
                        expanded = isModelExpanded,
                        onDismissRequest = { if (isModelExpanded) onToggleModelExpanded() },
                        modifier = Modifier.fillMaxWidth(0.8f).heightIn(max = 300.dp)
                    ) {
                        if (availableModels.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("未拉取到模型", style = AppTypography.BodySmall) },
                                onClick = { onToggleModelExpanded(); onFetchModels() }
                            )
                        } else {
                            availableModels.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model, style = AppTypography.BodyMedium) },
                                    onClick = {
                                        onSaveModel(model)
                                        if (isModelExpanded) onToggleModelExpanded()
                                    }
                                )
                            }
                        }
                    }
                }
                BlyyPrimaryButton(
                    text = when (connectionState) { is ConnectionTestState.Testing -> "测试中..."; else -> "测试连接" },
                    onClick = onTestConnection,
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

        // ── 已保存 API 配置列表 ──
        BlyySectionPanel(title = "已保存 API 配置", icon = Icons.Rounded.Storage, accentColor = MaterialTheme.colorScheme.primary) {
            Column(modifier = Modifier.fillMaxWidth().padding(AppSpacing.Lg), verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)) {
                Text(
                    "保存多套 API 配置，可在新建对话时与不同舰娘人格自由组合。点击列表项可快速应用。",
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                BlyyPrimaryButton(
                    text = "保存当前 API 配置",
                    onClick = onSaveApiConfig,
                    icon = Icons.Rounded.Bookmark,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = apiUrl.isNotBlank() || apiKey.isNotBlank() || selectedModel.isNotBlank()
                )
                if (apiConfigs.isNotEmpty()) {
                    Text(
                        "共 ${apiConfigs.size} 套配置",
                        style = AppTypography.LabelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    apiConfigs.forEach { config ->
                        ApiConfigCard(
                            config = config,
                            isCurrentActive = config.apiUrl == apiUrl && config.apiKey == apiKey && config.model == selectedModel,
                            onApply = { onApplyApiConfig(config) },
                            onEdit = { onEditApiConfig(config) },
                            onDelete = { onDeleteApiConfig(config) }
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(AppSpacing.Lg),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                            Icon(Icons.Rounded.Storage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f), modifier = Modifier.size(40.dp))
                            Text("暂无保存的 API 配置", style = AppTypography.BodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("保存当前配置后，可在此查看和管理", style = AppTypography.BodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(AppSpacing.Xl))
    }
}

// ════════════════════════════════════════════════════════════
// 分区详情页：舰娘人格
// ════════════════════════════════════════════════════════════

@Composable
private fun PersonaSection(
    avatarUrl: String,
    jiuxinName: String,
    systemPrompt: String,
    personaConfigs: List<PersonaConfig>,
    voiceEnabled: Boolean,
    voiceRandomChance: Float,
    voiceKeywords: String,
    voiceShipName: String,
    voiceShipAvatar: String,
    stickersEnabled: Boolean,
    stickerChance: Float,
    onPickAvatar: () -> Unit,
    onSaveJiuxinName: (String) -> Unit,
    onSaveSystemPrompt: (String) -> Unit,
    onSavePersonaConfig: () -> Unit,
    onApplyPersonaConfig: (PersonaConfig) -> Unit,
    onEditPersonaConfig: (PersonaConfig) -> Unit,
    onDeletePersonaConfig: (PersonaConfig) -> Unit,
    onClearPersonaFields: () -> Unit,
    onSaveVoiceEnabled: (Boolean) -> Unit,
    onSaveVoiceRandomChance: (Float) -> Unit,
    onSaveVoiceKeywords: (String) -> Unit,
    onSaveStickersEnabled: (Boolean) -> Unit,
    onSaveStickerChance: (Float) -> Unit,
    onPickVoiceShip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.Screen.Horizontal)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
    ) {
        Spacer(modifier = Modifier.height(AppSpacing.Sm))

        BlyySectionPanel(title = "当前舰娘人格", icon = Icons.Rounded.Psychology, accentColor = MaterialTheme.colorScheme.secondary) {
            Column(modifier = Modifier.fillMaxWidth().padding(AppSpacing.Lg), verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)) {
                    RobustAvatar(
                        url = avatarUrl,
                        modifier = Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                            .border(AppSpacing.Border.Thin, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), CircleShape)
                            .clickable(onClick = onPickAvatar),
                        fallbackContent = {
                            Icon(Icons.Rounded.Person, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                        }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("啾信头像", style = AppTypography.TitleSmall, fontWeight = FontWeight.Medium)
                        Text(text = if (avatarUrl.isNotBlank()) "已选择头像" else "点击选择舰娘头像或上传图片", style = AppTypography.BodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                StableOutlinedTextField(value = jiuxinName, onValueChange = onSaveJiuxinName, modifier = Modifier.fillMaxWidth(), label = { Text("啾信名称") }, placeholder = { Text("啾信助手") }, singleLine = true, textStyle = AppTypography.BodyMedium, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(AppSpacing.Corner.Sm))
                StableOutlinedTextField(value = systemPrompt, onValueChange = onSaveSystemPrompt, modifier = Modifier.fillMaxWidth().height(120.dp), label = { Text("人格提示词") }, placeholder = { Text("描述啾信的人格和行为方式...") }, textStyle = AppTypography.BodyMedium, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(AppSpacing.Corner.Sm))
                // 一键清空按钮 — 解决配置新人格时需手动逐个清除字段的痛点
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onClearPersonaFields,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Rounded.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "清空当前配置",
                            style = AppTypography.LabelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // ── 语音设置（合并到舰娘人格分区） ──
        BlyySectionPanel(title = "语音设置", icon = Icons.Rounded.VolumeUp, accentColor = MaterialTheme.colorScheme.tertiary) {
            Column(modifier = Modifier.fillMaxWidth().padding(AppSpacing.Lg), verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("发送语音", style = AppTypography.TitleSmall, fontWeight = FontWeight.Medium)
                        Text("智能标签匹配或随机发送舰娘语音", style = AppTypography.BodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = voiceEnabled, onCheckedChange = onSaveVoiceEnabled)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(AppSpacing.Corner.Md))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
                        .clickable(onClick = onPickVoiceShip)
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
                        Text("舰娘语音", style = AppTypography.TitleSmall, fontWeight = FontWeight.Medium)
                        Text(
                            text = if (voiceShipName.isNotBlank()) voiceShipName else "点击选择舰娘",
                            style = AppTypography.BodySmall,
                            color = if (voiceShipName.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (voiceEnabled) {
                    Text("随机触发概率: ${(voiceRandomChance * 100).toInt()}%", style = AppTypography.BodySmall)
                    Slider(value = voiceRandomChance, onValueChange = onSaveVoiceRandomChance, valueRange = 0f..1f, steps = 19)
                    StableOutlinedTextField(value = voiceKeywords, onValueChange = onSaveVoiceKeywords, modifier = Modifier.fillMaxWidth(), label = { Text("触发关键词") }, placeholder = { Text("你好;早安;晚安") }, supportingText = { Text("用分号 ; 分隔，关键词会自动匹配对应语音场景标签") }, singleLine = true, textStyle = AppTypography.BodyMedium, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(AppSpacing.Corner.Sm))
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

        // ── 表情包设置（合并到舰娘人格分区） ──
        BlyySectionPanel(title = "表情包设置", icon = Icons.Rounded.Image, accentColor = MaterialTheme.colorScheme.tertiary) {
            Column(modifier = Modifier.fillMaxWidth().padding(AppSpacing.Lg), verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("发送表情包", style = AppTypography.TitleSmall, fontWeight = FontWeight.Medium)
                        Text("基于 AI 回复内容自动匹配表情包", style = AppTypography.BodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = stickersEnabled, onCheckedChange = onSaveStickersEnabled)
                }
                if (stickersEnabled) {
                    Text("表情包发送概率: ${(stickerChance * 100).toInt()}%", style = AppTypography.BodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        value = stickerChance,
                        onValueChange = onSaveStickerChance,
                        valueRange = 0f..1f,
                        steps = 19
                    )
                    Text(
                        "控制 AI 每次回复时发送表情包的概率，滑动调节即可实时生效",
                        style = AppTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }

        BlyySectionPanel(title = "已保存舰娘人格", icon = Icons.Rounded.Storage, accentColor = MaterialTheme.colorScheme.secondary) {
            Column(modifier = Modifier.fillMaxWidth().padding(AppSpacing.Lg), verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)) {
                Text(
                    "保存多套舰娘人格（含头像、名称、提示词、语音、表情包），可在新建对话时与不同 API 配置自由组合。点击列表项可快速应用。",
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                BlyyPrimaryButton(
                    text = "保存当前舰娘人格",
                    onClick = onSavePersonaConfig,
                    icon = Icons.Rounded.Psychology,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = jiuxinName.isNotBlank() || systemPrompt.isNotBlank() || avatarUrl.isNotBlank()
                )
                if (personaConfigs.isNotEmpty()) {
                    Text(
                        "共 ${personaConfigs.size} 套人格",
                        style = AppTypography.LabelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    personaConfigs.forEach { config ->
                        PersonaConfigCard(
                            config = config,
                            isCurrentActive = config.jiuxinName == jiuxinName && config.systemPrompt == systemPrompt && config.avatarUrl == avatarUrl,
                            onApply = { onApplyPersonaConfig(config) },
                            onEdit = { onEditPersonaConfig(config) },
                            onDelete = { onDeletePersonaConfig(config) }
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(AppSpacing.Lg),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                            Icon(Icons.Rounded.Storage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f), modifier = Modifier.size(40.dp))
                            Text("暂无保存的舰娘人格", style = AppTypography.BodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("保存当前配置后，可在此查看和管理", style = AppTypography.BodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(AppSpacing.Xl))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceShipPickerSheet(
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
                Text(text = "选择舰娘语音", style = AppTypography.TitleMedium, fontWeight = FontWeight.Bold)
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

            StableOutlinedTextField(
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
                        val effectiveAvatar = remember(ship.name, ship.avatarUrl, ship.archiveType) {
                            LocalAvatarResolver.resolveOrDefault(context, ship.name, ship.archiveType, ship.avatarUrl)
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

/**
 * 预设卡片：显示预设名称、模型、舰娘信息，提供应用/编辑/删除操作
 */
@Composable
private fun PresetCard(
    preset: JiuxinPreset,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.Corner.Md))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(AppSpacing.Corner.Md))
            .padding(AppSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
    ) {
        // 头像
        RobustAvatar(
            url = preset.avatarUrl,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
            fallbackContent = {
                Icon(
                    Icons.Rounded.Bookmark,
                    null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.tertiary
                )
            }
        )
        // 信息
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = preset.name.ifBlank { "未命名预设" },
                style = AppTypography.TitleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    if (preset.jiuxinName.isNotBlank()) append(preset.jiuxinName)
                    if (preset.model.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(preset.model)
                    }
                    if (preset.voiceShipName.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append("语音: ${preset.voiceShipName}")
                    }
                    if (isEmpty()) append("点击应用以加载配置")
                },
                style = AppTypography.BodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 操作按钮
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onApply, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = "应用",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = "编辑",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * API 配置卡片：显示配置名、URL、Model，提供应用/编辑/删除操作
 * 与 [PresetCard] 区别：仅包含 API 连接信息，不绑定舰娘人格
 */
@Composable
private fun ApiConfigCard(
    config: ApiConfig,
    isCurrentActive: Boolean,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.Corner.Md))
            .background(
                if (isCurrentActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
            )
            .border(
                if (isCurrentActive) 1.5.dp else 1.dp,
                if (isCurrentActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                RoundedCornerShape(AppSpacing.Corner.Md)
            )
            .padding(AppSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
    ) {
        // 配置图标
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Key,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        // 信息
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = config.name.ifBlank { "未命名 API 配置" },
                    style = AppTypography.TitleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isCurrentActive) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "当前",
                            style = AppTypography.LabelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 10.sp
                        )
                    }
                }
            }
            Text(
                text = buildString {
                    if (config.model.isNotBlank()) append(config.model)
                    if (config.apiUrl.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        // 截断长 URL 提升可读性
                        val url = config.apiUrl
                        append(if (url.length > 40) url.take(40) + "…" else url)
                    }
                    if (isEmpty()) append("点击应用以加载配置")
                },
                style = AppTypography.BodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 操作按钮
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onApply, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = "应用",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = "编辑",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * 舰娘人格配置卡片：显示头像、名称、摘要，提供应用/编辑/删除操作
 * 与 [PresetCard] 区别：仅包含舰娘人格信息，不绑定 API 配置
 */
@Composable
private fun PersonaConfigCard(
    config: PersonaConfig,
    isCurrentActive: Boolean,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.Corner.Md))
            .background(
                if (isCurrentActive) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
            )
            .border(
                if (isCurrentActive) 1.5.dp else 1.dp,
                if (isCurrentActive) MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                RoundedCornerShape(AppSpacing.Corner.Md)
            )
            .padding(AppSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
    ) {
        // 头像
        RobustAvatar(
            url = config.avatarUrl,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)),
            fallbackContent = {
                Icon(
                    Icons.Rounded.Psychology,
                    null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
        )
        // 信息
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = config.name.ifBlank { "未命名舰娘" },
                    style = AppTypography.TitleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isCurrentActive) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "当前",
                            style = AppTypography.LabelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontSize = 10.sp
                        )
                    }
                }
            }
            Text(
                text = buildString {
                    if (config.jiuxinName.isNotBlank()) append(config.jiuxinName)
                    if (config.voiceShipName.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append("语音: ${config.voiceShipName}")
                    }
                    if (config.systemPrompt.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        // 仅显示提示词前 30 字作为摘要
                        val promptPreview = config.systemPrompt.take(30)
                        append(if (config.systemPrompt.length > 30) "$promptPreview…" else promptPreview)
                    }
                    if (isEmpty()) append("点击应用以加载配置")
                },
                style = AppTypography.BodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 操作按钮
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onApply, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = "应用",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = "编辑",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
