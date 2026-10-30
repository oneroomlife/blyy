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
import androidx.compose.material.icons.rounded.AutoStories
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.azurlane.blyy.data.model.ApiConfig
import com.azurlane.blyy.data.model.JiuxinPreset
import com.azurlane.blyy.data.model.PersonaConfig
import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyBottomSheet
import com.azurlane.blyy.ui.components.BlyyConfirmDialog
import com.azurlane.blyy.ui.components.BlyyFormDialog
import com.azurlane.blyy.ui.components.BlyyPanel
import com.azurlane.blyy.ui.components.BlyyPrimaryButton
import com.azurlane.blyy.ui.components.BlyyEntrance
import com.azurlane.blyy.ui.components.RobustAvatar
import com.azurlane.blyy.ui.screens.chat.AvatarPickerSheet
import com.azurlane.blyy.ui.components.BlyySectionPanel
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.components.StableOutlinedTextField
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.ChatColors
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.util.findActivityViewModelStoreOwner
import com.azurlane.blyy.viewmodel.ConnectionTestState
import com.azurlane.blyy.viewmodel.JiuxinViewModel
import com.azurlane.blyy.viewmodel.ModelListState
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlinx.coroutines.launch
import com.azurlane.blyy.ui.screens.config.ConfigMainMenu
import com.azurlane.blyy.ui.screens.config.ConfigStatPill
import com.azurlane.blyy.ui.screens.config.ApiSection
import com.azurlane.blyy.ui.screens.config.ConfigSection
import com.azurlane.blyy.ui.screens.config.PersonaSection
import com.azurlane.blyy.ui.screens.config.PresetsSection
import com.azurlane.blyy.ui.screens.config.VoiceShipPickerSheet

@Composable
fun JiuxinConfigScreen(
    onBack: () -> Unit,
    viewModel: JiuxinViewModel = hiltViewModel(
        // 优先使用 Activity 作为 ViewModelStoreOwner，确保跨页面共享同一 ViewModel 实例。
        // 通过 findActivityViewModelStoreOwner() 解包 ContextWrapper，
        // 避免直接 `as ViewModelStoreOwner` 强转在部分设备/ROM 上抛出 ClassCastException 导致闪退。
        // 回退到 LocalViewModelStoreOwner.current（NavBackStackEntry 或 Activity）。
        viewModelStoreOwner = LocalContext.current.findActivityViewModelStoreOwner()
            ?: LocalViewModelStoreOwner.current
            ?: error("No ViewModelStoreOwner available in Context hierarchy")
    )
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
    val modelListState by viewModel.modelListState.collectAsStateWithLifecycle()
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

    // 长期记忆管理状态
    val personaMemory by viewModel.currentPersonaMemory.collectAsStateWithLifecycle()
    var showEditMemoryDialog by remember { mutableStateOf(false) }
    var memoryTextInput by remember { mutableStateOf("") }
    var showClearMemoryConfirm by remember { mutableStateOf(false) }

    // ── 分区导航状态：null 表示主菜单，非 null 表示当前展开的分区 ──
    var activeSection by remember { mutableStateOf<ConfigSection?>(null) }

    // 暗色模式判断：用于适配硬编码颜色，确保 WCAG AA 对比度
    val isDark = LocalIsDark.current
    // 连接成功状态色：暗色模式下使用更亮的绿色，确保在深色面板背景上对比度 ≥ 4.5:1
    val successColor = if (isDark) ChatColors.SuccessDark else ChatColors.SuccessLight

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
                        modelListState = modelListState,
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
                        memoryText = personaMemory?.text.orEmpty(),
                        memoryUpdatedAt = personaMemory?.updatedAt ?: 0L,
                        onEditMemory = {
                            memoryTextInput = personaMemory?.text.orEmpty()
                            showEditMemoryDialog = true
                        },
                        onSaveMemory = { viewModel.savePersonaMemoryText(it) },
                        onClearMemory = { showClearMemoryConfirm = true },
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
        BlyyFormDialog(
            onDismiss = { showSavePresetDialog = false },
            title = if (isEditing) "编辑预设" else "保存为预设",
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
        BlyyConfirmDialog(
            title = "删除预设",
            message = "确定要删除预设「${preset.name}」吗？此操作不可撤销。",
            confirmText = "删除",
            onConfirm = {
                viewModel.deletePreset(preset.id)
                showDeletePresetConfirm = null
                Toast.makeText(context, "已删除预设「${preset.name}」", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showDeletePresetConfirm = null },
            isDestructive = true
        )
    }
    // 保存/编辑 API 配置对话框
    if (showSaveApiConfigDialog) {
        val isEditing = editingApiConfig != null
        val isDuplicateName = apiConfigs.any { it.name == apiConfigNameInput.trim() && it.id != editingApiConfig?.id }
        val isApiConfigured = apiUrl.isNotBlank() && apiKey.isNotBlank()
        BlyyFormDialog(
            onDismiss = { showSaveApiConfigDialog = false },
            title = if (isEditing) "编辑 API 配置" else "保存为 API 配置",
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
        BlyyConfirmDialog(
            title = "删除 API 配置",
            message = "确定要删除 API 配置「${config.name}」吗？此操作不可撤销。",
            confirmText = "删除",
            onConfirm = {
                viewModel.deleteApiConfig(config.id)
                showDeleteApiConfigConfirm = null
                Toast.makeText(context, "已删除 API 配置「${config.name}」", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showDeleteApiConfigConfirm = null },
            isDestructive = true
        )
    }
    // 保存/编辑舰娘人格配置对话框
    if (showSavePersonaConfigDialog) {
        val isEditing = editingPersonaConfig != null
        val isDuplicateName = personaConfigs.any { it.name == personaConfigNameInput.trim() && it.id != editingPersonaConfig?.id }
        BlyyFormDialog(
            onDismiss = { showSavePersonaConfigDialog = false },
            title = if (isEditing) "编辑舰娘人格" else "保存为舰娘人格",
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
        BlyyConfirmDialog(
            title = "删除舰娘人格",
            message = "确定要删除舰娘人格「${config.name}」吗？此操作不可撤销。",
            confirmText = "删除",
            onConfirm = {
                viewModel.deletePersonaConfig(config.id)
                showDeletePersonaConfigConfirm = null
                Toast.makeText(context, "已删除舰娘人格「${config.name}」", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showDeletePersonaConfigConfirm = null },
            isDestructive = true
        )
    }
    // 清空当前舰娘人格确认对话框
    if (showClearPersonaConfirm) {
        BlyyConfirmDialog(
            title = "清空当前配置",
            message = "将清空当前头像、名称、人格提示词、语音和表情包设置，方便重新填写。已保存的舰娘人格不受影响。",
            confirmText = "清空",
            onConfirm = {
                viewModel.clearPersonaFields()
                showClearPersonaConfirm = false
                Toast.makeText(context, "已清空当前配置", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showClearPersonaConfirm = false },
            isDestructive = true
        )
    }
    // 编辑长期记忆对话框
    if (showEditMemoryDialog) {
        BlyyFormDialog(
            onDismiss = { showEditMemoryDialog = false },
            title = "编辑长期记忆",
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                    Text(
                        "这段记忆会注入舰娘的每次对话。自动摘要也会在此基础上合并更新，可以放心手工修订。",
                        style = AppTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    StableOutlinedTextField(
                        value = memoryTextInput,
                        onValueChange = { memoryTextInput = it },
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        label = { Text("记忆内容（上限 1200 字）") },
                        placeholder = { Text("她喜欢被称呼……你们聊过……约定了……") },
                        textStyle = AppTypography.BodyMedium,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(AppSpacing.Corner.Sm)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.savePersonaMemoryText(memoryTextInput)
                    showEditMemoryDialog = false
                    Toast.makeText(context, "记忆已保存", Toast.LENGTH_SHORT).show()
                }) { Text("保存", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showEditMemoryDialog = false }) { Text("取消") }
            }
        )
    }
    // 清空长期记忆确认对话框
    if (showClearMemoryConfirm) {
        BlyyConfirmDialog(
            title = "清空长期记忆",
            message = "确定要清空「${jiuxinName.ifBlank { "当前舰娘" }}」的长期记忆吗？她会忘记跨会话记住的内容，后续聊天会重新积累。此操作不可撤销。",
            confirmText = "清空",
            onConfirm = {
                viewModel.clearPersonaMemory()
                showClearMemoryConfirm = false
                Toast.makeText(context, "已清空长期记忆", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showClearMemoryConfirm = false },
            isDestructive = true
        )
    }

    if (showVoiceShipPicker) {
        VoiceShipPickerSheet(
            viewModel = viewModel,
            currentShipName = voiceShipName,
            onDismiss = { showVoiceShipPicker = false },
            onShipSelected = { ship ->
                viewModel.saveVoiceShipName(ship.name)
                // 选中语音舰娘时，归一化网络 URL 后保存
                scope.launch {
                    val reliableAvatar = viewModel.resolveAndCopyShipAvatar(ship.name, ship.avatarUrl)
                    viewModel.saveVoiceShipAvatar(reliableAvatar)
                }
                showVoiceShipPicker = false
            }
        )
    }
}
