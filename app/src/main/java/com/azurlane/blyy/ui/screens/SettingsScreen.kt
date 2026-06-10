package com.azurlane.blyy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyPanel
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.LocalUiStyle
import com.azurlane.blyy.ui.theme.UiStyle
import com.azurlane.blyy.ui.theme.isCommandCenter
import com.azurlane.blyy.ui.theme.isWatchScreen
import com.azurlane.blyy.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiStyle by viewModel.uiStyle.collectAsStateWithLifecycle()
    val forceDark by viewModel.forceDarkTheme.collectAsStateWithLifecycle()
    val autoCheckUpdate by viewModel.autoCheckUpdateEnabled.collectAsStateWithLifecycle()
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()

    // 小助手配置
    val assistantDefaultUid by viewModel.assistantDefaultUid.collectAsStateWithLifecycle()
    val assistantDefaultServer by viewModel.assistantDefaultServer.collectAsStateWithLifecycle()

    AdaptiveScreenBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            BlyyTopBar(
                title = "设置",
                subtitle = "外观与显示偏好",
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

                SettingsSection(title = "界面风格") {
                    SettingsToggleRow(
                        title = "指挥中心 UI",
                        description = if (uiStyle.isCommandCenter()) {
                            "当前：碧蓝航线科技风 HUD 界面"
                        } else {
                            "开启后切换为新版指挥中心风格"
                        },
                        checked = uiStyle.isCommandCenter(),
                        onCheckedChange = { enabled ->
                            viewModel.setUiStyle(
                                if (enabled) UiStyle.COMMAND_CENTER else UiStyle.CLASSIC
                            )
                        },
                        usePanel = isCommandCenter
                    )
                }

                SettingsSection(title = "显示") {
                    SettingsToggleRow(
                        title = "始终深色模式",
                        description = if (forceDark) "已强制深色，忽略系统设置" else "跟随系统浅色/深色设置",
                        checked = forceDark,
                        onCheckedChange = viewModel::setForceDarkTheme,
                        usePanel = isCommandCenter
                    )
                }

                SettingsSection(title = "更新") {
                    SettingsToggleRow(
                        title = "自动检测更新",
                        description = if (autoCheckUpdate) "启动时自动检查新版本" else "不会自动检查更新，可在关于页手动检查",
                        checked = autoCheckUpdate,
                        onCheckedChange = viewModel::setAutoCheckUpdateEnabled,
                        usePanel = isCommandCenter
                    )
                }

                // ── 小助手配置 ──
                SettingsSection(title = "碧蓝航线助手") {
                    AssistantConfigSection(
                        defaultUid = assistantDefaultUid,
                        defaultServer = assistantDefaultServer,
                        onUidChange = viewModel::setAssistantDefaultUid,
                        onServerChange = viewModel::setAssistantDefaultServer,
                        usePanel = isCommandCenter
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
        Text(
            text = title,
            style = AppTypography.LabelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        content()
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    usePanel: Boolean
) {
    val isWatch = isWatchScreen()

    val rowContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (isWatch) AppSpacing.Md else AppSpacing.Lg),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = AppSpacing.Md)) {
                Text(title, style = AppTypography.TitleSmall, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(AppSpacing.Xxs))
                Text(
                    description,
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }

    if (usePanel) {
        BlyyPanel(modifier = Modifier.fillMaxWidth(), content = rowContent)
    } else {
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(AppSpacing.Corner.Lg),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ) {
            rowContent()
        }
    }
}

/**
 * 小助手配置表单区域
 *
 * 包含两个输入项：
 * - 默认 UID：游戏内 UID，查询时自动填充
 * - 默认服务器：服务器名称，查询时自动填充
 */
@Composable
private fun AssistantConfigSection(
    defaultUid: String,
    defaultServer: String,
    onUidChange: (String) -> Unit,
    onServerChange: (String) -> Unit,
    usePanel: Boolean
) {
    val isWatch = isWatchScreen()

    val formContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (isWatch) AppSpacing.Md else AppSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
        ) {
            //  UID 输入
            Column {
                Text(
                    text = "UID",
                    style = AppTypography.TitleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(AppSpacing.Xxs))
                Spacer(modifier = Modifier.height(AppSpacing.Sm))
                OutlinedTextField(
                    value = defaultUid,
                    onValueChange = onUidChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("输入游戏内 UID", style = AppTypography.BodySmall) },
                    textStyle = AppTypography.BodyMedium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(AppSpacing.Corner.Sm)
                )
            }

            // 服务器输入
            Column {
                Text(
                    text = "服务器",
                    style = AppTypography.TitleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(AppSpacing.Xxs))
                Spacer(modifier = Modifier.height(AppSpacing.Sm))
                OutlinedTextField(
                    value = defaultServer,
                    onValueChange = onServerChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("服务器名", style = AppTypography.BodySmall) },
                    textStyle = AppTypography.BodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(AppSpacing.Corner.Sm)
                )
            }
        }
    }

    if (usePanel) {
        BlyyPanel(modifier = Modifier.fillMaxWidth(), content = formContent)
    } else {
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(AppSpacing.Corner.Lg),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ) {
            formContent()
        }
    }
}
