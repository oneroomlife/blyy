package com.azurlane.blyy.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.CloudDownload
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
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
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
import com.azurlane.blyy.util.SDResourceManager
import com.azurlane.blyy.util.SdResourceOrganizer
import com.azurlane.blyy.util.StoragePermissionHelper
import com.azurlane.blyy.viewmodel.SecretaryShipState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    onOpenSettings: () -> Unit = {},
    sdResourceLink: com.azurlane.blyy.util.SdResourceLink? = null,
    isRefreshingSdLink: Boolean = false,
    onRefreshSdLink: () -> Unit = {},
    onEnsureSdLinkLoaded: () -> Unit = {}
) {
    var heartExpanded by remember { mutableStateOf(false) }

    // 进入页面时确保 SD 资源链接已加载：
    // 若启动阶段获取失败（网络异常等），进入页面会自动重试获取最新链接；
    // 已加载成功或正在加载中则自动跳过（由 ensureSdResourceLinkLoaded 内部判断）。
    LaunchedEffect(Unit) {
        onEnsureSdLinkLoaded()
    }

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

                // SD 资源下载入口：远程获取网盘链接，支持刷新和加载状态
                // 独立 Section，使用 tertiary 颜色与主功能区（primary/secondary）区分，
                // 与下方"当前秘书舰" Section 视觉上形成层次。
                SecretarySection(
                    title = "SD 资源下载",
                    icon = Icons.Rounded.CloudDownload,
                    accentColor = MaterialTheme.colorScheme.tertiary
                ) {
                    ResourceDownloadCard(
                        sdResourceLink = sdResourceLink,
                        isRefreshing = isRefreshingSdLink,
                        onRefresh = onRefreshSdLink
                    )
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
 * SD 资源下载卡片 — 远程获取网盘链接并跳转浏览器下载完整 SD 小人资源包。
 *
 * 设计要点：
 * - 链接通过 [SdResourceLinkProvider] 远程获取（多源策略 + 三级缓存），
 *   避免硬编码链接失效问题，可在不更新 App 的前提下动态更新下载地址
 * - 三种状态：
 *   - 加载中（isRefreshing=true）：显示加载指示器，禁用点击
 *   - 加载成功（sdResourceLink != null）：显示网盘类型 + 资源版本，点击跳转浏览器
 *   - 加载失败（sdResourceLink == null 且 !isRefreshing）：显示错误提示 + 刷新按钮
 * - 使用 tertiary 颜色作为主色调，区别于主功能区 primary/secondary
 * - 整体复用 [BlyyPanel] 风格保持与其他卡片视觉一致
 *
 * 容错处理：
 * - [ActivityNotFoundException]：设备无浏览器可用时 Toast 提示
 */
@Composable
private fun ResourceDownloadCard(
    sdResourceLink: com.azurlane.blyy.util.SdResourceLink?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val accentColor = MaterialTheme.colorScheme.tertiary

    BlyyPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.Corner.Md)),
        accentColor = accentColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppSpacing.Corner.Sm))
                .clickable(enabled = sdResourceLink != null && !isRefreshing) {
                    val url = sdResourceLink?.url ?: return@clickable
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, "未找到可用浏览器", Toast.LENGTH_SHORT).show()
                    }
                }
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
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = accentColor
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.CloudDownload,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sdResourceLink?.label ?: if (isRefreshing) "正在获取下载链接..." else "下载链接获取失败",
                    style = AppTypography.TitleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = when {
                        sdResourceLink != null ->
                            if (sdResourceLink.note.isNotEmpty()) sdResourceLink.note else "下载 SD 小人资源包"
                        isRefreshing -> "从远程仓库获取最新链接"
                        else -> "点击右侧刷新按钮重试"
                    },
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 右侧操作区：加载成功显示箭头，失败显示刷新按钮
            if (sdResourceLink != null && !isRefreshing) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (sdResourceLink == null && !isRefreshing) {
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "刷新链接",
                        tint = accentColor
                    )
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
