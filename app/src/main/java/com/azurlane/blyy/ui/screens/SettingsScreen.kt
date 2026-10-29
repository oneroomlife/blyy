package com.azurlane.blyy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AppShortcut
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.Sailing
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurlane.blyy.ui.components.BlyyEntrance
import com.azurlane.blyy.ui.components.BlyyListItem
import com.azurlane.blyy.ui.components.BlyyScreenScaffold
import com.azurlane.blyy.ui.components.BlyySectionPanel
import com.azurlane.blyy.ui.components.BlyySettingsRow
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.UiStyle
import com.azurlane.blyy.ui.theme.isCommandCenter
import com.azurlane.blyy.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToAssistantConfig: () -> Unit = {},
    onNavigateToJiuxinConfig: () -> Unit = {},
    onNavigateToAppIconSettings: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiStyle by viewModel.uiStyle.collectAsStateWithLifecycle()
    val forceDark by viewModel.forceDarkTheme.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColorEnabled.collectAsStateWithLifecycle()
    val hideStatusBar by viewModel.hideStatusBar.collectAsStateWithLifecycle()
    val showArchiveSwitcher by viewModel.showArchiveSwitcher.collectAsStateWithLifecycle()
    // null=设置尚未加载完成，开关行按默认开启回显
    val archiveSwitcherChecked = showArchiveSwitcher ?: true
    val autoCheckUpdate by viewModel.autoCheckUpdateEnabled.collectAsStateWithLifecycle()

    BlyyScreenScaffold(
        topBar = {
            BlyyTopBar(
                title = "设置",
                subtitle = "外观与显示偏好",
                onBackClick = onBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Gap.Lg)
        ) {
            BlyyEntrance(index = 0) {
                // ── 界面风格 ──
                BlyySectionPanel(
                    title = "界面风格",
                    icon = Icons.Rounded.Sailing,
                    accentColor = MaterialTheme.colorScheme.primary,
                    separateItems = true
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
            }

            BlyyEntrance(index = 1) {
                // ── 显示 ──
                BlyySectionPanel(
                    title = "显示",
                    icon = Icons.Rounded.AutoMode,
                    accentColor = MaterialTheme.colorScheme.secondary,
                    separateItems = true
                ) {
                    BlyySettingsRow(
                        icon = Icons.Rounded.AutoMode,
                        title = "始终深色模式",
                        description = if (forceDark) "已强制深色，忽略系统设置" else "跟随系统浅色/深色设置",
                        checked = forceDark,
                        onCheckedChange = viewModel::setForceDarkTheme
                    )
                    BlyySettingsRow(
                        icon = Icons.Rounded.Palette,
                        title = "Material You 动态取色",
                        description = if (dynamicColor) {
                            "跟随壁纸取色，品牌主色保持不变"
                        } else {
                            "使用固定品牌配色"
                        },
                        checked = dynamicColor,
                        onCheckedChange = viewModel::setDynamicColorEnabled
                    )
                    BlyySettingsRow(
                        icon = Icons.Rounded.Fullscreen,
                        title = "沉浸式状态栏",
                        description = if (hideStatusBar) {
                            "已隐藏状态栏，从屏幕顶部下滑可临时呼出"
                        } else {
                            "显示系统状态栏"
                        },
                        checked = hideStatusBar,
                        onCheckedChange = viewModel::setHideStatusBar
                    )
                    BlyySettingsRow(
                        icon = Icons.Rounded.SwapHoriz,
                        title = "档案切换按钮",
                        description = if (archiveSwitcherChecked) {
                            "在船坞顶栏显示舰娘/学生切换按钮"
                        } else {
                            "已隐藏，船坞顶栏自动回收按钮空间"
                        },
                        checked = archiveSwitcherChecked,
                        onCheckedChange = viewModel::setShowArchiveSwitcher
                    )
                }
            }

            BlyyEntrance(index = 2) {
                // ── 更新 ──
                BlyySectionPanel(
                    title = "更新",
                    icon = Icons.Rounded.CloudSync,
                    accentColor = MaterialTheme.colorScheme.tertiary,
                    separateItems = true
                ) {
                    BlyySettingsRow(
                        icon = Icons.Rounded.CloudSync,
                        title = "自动检测更新",
                        description = if (autoCheckUpdate) "启动时自动检查新版本" else "不会自动检查更新，可在关于页手动检查",
                        checked = autoCheckUpdate,
                        onCheckedChange = viewModel::setAutoCheckUpdateEnabled
                    )
                }
            }

            BlyyEntrance(index = 3) {
                // ── 碧蓝航线助手 — 跳转入口 ──
                BlyySectionPanel(
                    title = "碧蓝航线助手",
                    icon = Icons.Rounded.PersonSearch,
                    accentColor = MaterialTheme.colorScheme.primary,
                    separateItems = true
                ) {
                    BlyyListItem(
                        leadingIcon = Icons.Rounded.PersonSearch,
                        title = "助手配置",
                        subtitle = "配置 UID、服务器等查询参数",
                        onClick = onNavigateToAssistantConfig,
                        asCard = true
                    )
                }
            }

            BlyyEntrance(index = 4) {
                // ── 啾信功能 — 跳转入口 ──
                BlyySectionPanel(
                    title = "啾信",
                    icon = Icons.Rounded.SmartToy,
                    accentColor = MaterialTheme.colorScheme.secondary,
                    separateItems = true
                ) {
                    BlyyListItem(
                        leadingIcon = Icons.Rounded.SmartToy,
                        title = "啾信配置",
                        subtitle = "配置 API 密钥、人格提示词、聊天选项",
                        onClick = onNavigateToJiuxinConfig,
                        asCard = true
                    )
                }
            }

            BlyyEntrance(index = 5) {
                // ── 应用快捷方式 — 跳转入口 ──
                BlyySectionPanel(
                    title = "应用快捷方式",
                    icon = Icons.Rounded.AppShortcut,
                    accentColor = MaterialTheme.colorScheme.tertiary,
                    separateItems = true
                ) {
                    BlyyListItem(
                        leadingIcon = Icons.Rounded.AppShortcut,
                        title = "自定义 app 快捷方式",
                        subtitle = "从相册选择图片创建桌面快捷方式",
                        onClick = onNavigateToAppIconSettings,
                        asCard = true
                    )
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.Xl))
        }
    }
}
