package com.azurlane.blyy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
import com.azurlane.blyy.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiStyle by viewModel.uiStyle.collectAsStateWithLifecycle()
    val forceDark by viewModel.forceDarkTheme.collectAsStateWithLifecycle()
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()

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
                    .padding(AppSpacing.Screen.Horizontal),
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
    val rowContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Lg),
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
            shape = androidx.compose.foundation.shape.RoundedCornerShape(AppSpacing.Corner.Lg),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ) {
            rowContent()
        }
    }
}
