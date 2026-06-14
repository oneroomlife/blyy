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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.Sailing
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyPanel
import com.azurlane.blyy.ui.components.BlyySectionPanel
import com.azurlane.blyy.ui.components.BlyySettingsRow
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.theme.AppColors
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.ui.theme.LocalUiStyle
import com.azurlane.blyy.ui.theme.UiStyle
import com.azurlane.blyy.ui.theme.isCommandCenter
import com.azurlane.blyy.ui.theme.isWatchScreen
import com.azurlane.blyy.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToAssistantConfig: () -> Unit = {},
    onNavigateToJiuxinConfig: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiStyle by viewModel.uiStyle.collectAsStateWithLifecycle()
    val forceDark by viewModel.forceDarkTheme.collectAsStateWithLifecycle()
    val autoCheckUpdate by viewModel.autoCheckUpdateEnabled.collectAsStateWithLifecycle()

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

                // ── 界面风格 ──
                BlyySectionPanel(
                    title = "界面风格",
                    icon = Icons.Rounded.Sailing,
                    accentColor = MaterialTheme.colorScheme.primary
                ) {
                    BlyySettingsRow(
                        icon = Icons.Rounded.Sailing,
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
                        }
                    )
                }

                // ── 显示 ──
                BlyySectionPanel(
                    title = "显示",
                    icon = Icons.Rounded.AutoMode,
                    accentColor = MaterialTheme.colorScheme.secondary
                ) {
                    BlyySettingsRow(
                        icon = Icons.Rounded.AutoMode,
                        title = "始终深色模式",
                        description = if (forceDark) "已强制深色，忽略系统设置" else "跟随系统浅色/深色设置",
                        checked = forceDark,
                        onCheckedChange = viewModel::setForceDarkTheme
                    )
                }

                // ── 更新 ──
                BlyySectionPanel(
                    title = "更新",
                    icon = Icons.Rounded.CloudSync,
                    accentColor = MaterialTheme.colorScheme.tertiary
                ) {
                    BlyySettingsRow(
                        icon = Icons.Rounded.CloudSync,
                        title = "自动检测更新",
                        description = if (autoCheckUpdate) "启动时自动检查新版本" else "不会自动检查更新，可在关于页手动检查",
                        checked = autoCheckUpdate,
                        onCheckedChange = viewModel::setAutoCheckUpdateEnabled
                    )
                }

                // ── 碧蓝航线助手 — 跳转入口 ──
                BlyySectionPanel(
                    title = "碧蓝航线助手",
                    icon = Icons.Rounded.PersonSearch,
                    accentColor = MaterialTheme.colorScheme.primary
                ) {
                    SettingsNavigationRow(
                        icon = Icons.Rounded.PersonSearch,
                        title = "助手配置",
                        description = "配置 UID、服务器等查询参数",
                        onClick = onNavigateToAssistantConfig
                    )
                }

                // ── 啾信功能 — 跳转入口 ──
                BlyySectionPanel(
                    title = "啾信",
                    icon = Icons.Rounded.SmartToy,
                    accentColor = MaterialTheme.colorScheme.secondary
                ) {
                    SettingsNavigationRow(
                        icon = Icons.Rounded.SmartToy,
                        title = "啾信配置",
                        description = "配置 API 密钥、人格提示词、聊天选项",
                        onClick = onNavigateToJiuxinConfig
                    )
                }

                Spacer(modifier = Modifier.height(AppSpacing.Xl))
            }
        }
    }
}

/**
 * 设置页导航行 — 点击跳转到子配置页
 */
@Composable
private fun SettingsNavigationRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val isDark = LocalIsDark.current
    val accentColor = MaterialTheme.colorScheme.primary
    val isWatch = isWatchScreen()

    val containerModifier = if (isCommandCenter) {
        Modifier.fillMaxWidth()
    } else {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.Corner.Lg))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    }

    BlyyPanel(modifier = containerModifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppSpacing.Corner.Lg))
                .clickable(onClick = onClick)
                .padding(if (isWatch) AppSpacing.Md else AppSpacing.Lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(if (isWatch) 32.dp else 40.dp)
                    .then(
                        if (isCommandCenter) {
                            Modifier
                                .background(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            accentColor.copy(alpha = 0.15f),
                                            Color.Transparent
                                        )
                                    ),
                                    shape = CircleShape
                                )
                                .border(
                                    width = AppSpacing.Border.Thin,
                                    color = accentColor.copy(alpha = 0.3f),
                                    shape = CircleShape
                                )
                        } else {
                            Modifier.background(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                CircleShape
                            )
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(if (isWatch) 16.dp else 20.dp),
                    tint = accentColor
                )
            }

            Spacer(modifier = Modifier.width(AppSpacing.Md))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = AppTypography.TitleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(AppSpacing.Xxs))
                Text(
                    text = description,
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}
