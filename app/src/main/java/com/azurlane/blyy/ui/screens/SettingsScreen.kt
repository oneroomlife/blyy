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
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.Sailing
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.widget.Toast
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
    onNavigateToBackup: () -> Unit = {},
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

    // ── 诊断与反馈 ──
    val context = LocalContext.current
    val diagnosticsInfo by viewModel.diagnosticsInfo.collectAsStateWithLifecycle()
    val exportingDiagnostics by viewModel.exportingDiagnostics.collectAsStateWithLifecycle()
    val diagnosticsMessage by viewModel.diagnosticsMessage.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshDiagnostics() }

    // 导出/清空的结果用 Toast 反馈（与导出入口同一交互层级，不打断滚动）
    LaunchedEffect(diagnosticsMessage) {
        diagnosticsMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearDiagnosticsMessage()
        }
    }

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

            BlyyEntrance(index = 6) {
                // ── 数据与备份 ──
                BlyySectionPanel(
                    title = "数据与备份",
                    icon = Icons.Rounded.Backup,
                    accentColor = MaterialTheme.colorScheme.primary,
                    separateItems = true
                ) {
                    BlyyListItem(
                        leadingIcon = Icons.Rounded.Backup,
                        title = "备份与恢复",
                        subtitle = "换机迁移 / 回滚誓约、成绩与设置",
                        onClick = onNavigateToBackup,
                        asCard = true
                    )
                }
            }

            BlyyEntrance(index = 7) {
                // ── 诊断与反馈 ──
                // 崩溃/日志此前完全不可见（P0-4）。这里提供「导出诊断包」这一条用户可操作的自救路径：
                // 打包后的 zip 里凭证字段已打码，用户可自行发到 Issue。
                val logKb = (diagnosticsInfo.logBytes / 1024).coerceAtLeast(0)
                val crashSummary = if (diagnosticsInfo.crashCount > 0) {
                    "崩溃记录 ${diagnosticsInfo.crashCount} 份 · 日志 ${logKb} KB"
                } else {
                    "暂无崩溃记录 · 日志 ${logKb} KB"
                }
                BlyySectionPanel(
                    title = "诊断与反馈",
                    icon = Icons.Rounded.BugReport,
                    accentColor = MaterialTheme.colorScheme.error,
                    separateItems = true
                ) {
                    BlyyListItem(
                        leadingIcon = Icons.Rounded.FileDownload,
                        title = if (exportingDiagnostics) "正在生成诊断包…" else "导出诊断包",
                        subtitle = "日志 + 崩溃现场 + 设备信息（API Key/UID 等已打码）",
                        onClick = {
                            if (!exportingDiagnostics) {
                                viewModel.exportDiagnostics { intent ->
                                    // 极简 ROM 可能没有 chooser 承载者：兜底提示而不是崩溃
                                    runCatching {
                                        context.startActivity(Intent.createChooser(intent, "分享诊断包"))
                                    }.onFailure {
                                        Toast.makeText(context, "没有可用的分享应用", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        asCard = true
                    )
                    BlyyListItem(
                        leadingIcon = Icons.Rounded.DeleteSweep,
                        title = "清空诊断日志",
                        subtitle = crashSummary,
                        onClick = { viewModel.clearDiagnostics() },
                        asCard = true
                    )
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.Xl))
        }
    }
}
