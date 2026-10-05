package com.azurlane.blyy.ui.components

// 应用外壳组件 — 玻璃胶囊底部导航栏、侧拉抽屉面板与更新弹窗
// 从 AppRoot 拆分而来，AppRoot 保留导航图与屏幕编排职责

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Leaderboard
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Support
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.azurlane.blyy.MainActivity
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import com.azurlane.blyy.ui.components.SecretaryChibiOverlay
import com.azurlane.blyy.ui.components.adaptiveGlassBorder
import com.azurlane.blyy.ui.components.adaptiveGlassSurface
import com.azurlane.blyy.ui.icons.Github
import com.azurlane.blyy.ui.screens.AboutScreen
import com.azurlane.blyy.ui.screens.AssistantConfigScreen
import com.azurlane.blyy.ui.screens.AssistantScreen
import com.azurlane.blyy.ui.screens.ConversationListScreen
import com.azurlane.blyy.ui.screens.GalleryScreen
import com.azurlane.blyy.ui.screens.GuessByImageScreen
import com.azurlane.blyy.ui.screens.GuessByVoiceScreen
import com.azurlane.blyy.ui.screens.GuessHistoryScreen
import com.azurlane.blyy.ui.screens.HomeScreen
import com.azurlane.blyy.ui.screens.IconSettingsScreen
import com.azurlane.blyy.ui.screens.ImageCropperScreen
import com.azurlane.blyy.ui.screens.JiuxinChatScreen
import com.azurlane.blyy.ui.screens.JiuxinConfigScreen
import com.azurlane.blyy.ui.screens.JiuxinShipConfigScreen
import com.azurlane.blyy.ui.screens.LeaderboardScreen
import com.azurlane.blyy.ui.screens.SecretaryShipModeScreen
import com.azurlane.blyy.ui.screens.SecretaryShipPickFromGalleryScreen
import com.azurlane.blyy.ui.screens.SecretaryShipPickFromHomeScreen
import com.azurlane.blyy.ui.screens.SecretaryShipRandomScreen
import com.azurlane.blyy.ui.screens.SecretaryShipSettingsScreen
import com.azurlane.blyy.ui.screens.SdResourceGalleryScreen
import com.azurlane.blyy.ui.screens.SettingsScreen
import com.azurlane.blyy.ui.screens.ShipGalleryScreen
import com.azurlane.blyy.ui.screens.StudentGalleryScreen
import com.azurlane.blyy.ui.screens.VoiceScreen
import com.azurlane.blyy.ui.screens.WatermarkCameraScreen
import com.azurlane.blyy.ui.screens.WatermarkEditorScreen
import com.azurlane.blyy.ui.theme.AppAnimation
import com.azurlane.blyy.ui.theme.AppColors
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.BlyyShapes
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.ui.theme.LocalUiStyle
import com.azurlane.blyy.ui.theme.isCommandCenter
import com.azurlane.blyy.ui.theme.isWatchScreen
import com.azurlane.blyy.util.AppUpdateChecker
import com.azurlane.blyy.util.UpdateInfo
import com.azurlane.blyy.viewmodel.ArchiveType
import com.azurlane.blyy.viewmodel.GalleryViewModel
import com.azurlane.blyy.viewmodel.GuessShipViewModel
import com.azurlane.blyy.viewmodel.HomeViewModel
import com.azurlane.blyy.viewmodel.SecretaryShipIntent
import com.azurlane.blyy.viewmodel.SecretaryShipViewModel
import com.azurlane.blyy.viewmodel.ShipGalleryViewModel
import com.azurlane.blyy.viewmodel.StudentGalleryViewModel
import com.azurlane.blyy.viewmodel.UpdateCheckViewModel
import com.azurlane.blyy.viewmodel.VoiceIntent
import com.azurlane.blyy.viewmodel.VoiceViewModel
import com.azurlane.blyy.ui.components.rememberBlyyHaptics
import com.azurlane.blyy.ui.components.BlyyHaptic
import kotlinx.coroutines.launch
import com.azurlane.blyy.ui.Screen

@Composable
internal fun ModernNavigationBar(
    screens: List<Screen>,
    currentDestination: NavDestination?,
    galleryLabel: String,
    onNavigate: (String) -> Unit
) {
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val isWatch = isWatchScreen()
    val glassSurface = adaptiveGlassSurface()
    val glassBorder = adaptiveGlassBorder()
    val navShape = if (isCommandCenter) BlyyShapes.NavBar else RoundedCornerShape(AppSpacing.Corner.Xl)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            glassSurface.copy(alpha = 0.95f)
                        )
                    )
                )
                .padding(top = if (isWatch) AppSpacing.Xs else AppSpacing.Sm, bottom = if (isWatch) AppSpacing.Xs else AppSpacing.Sm)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Lg)
                    .height(if (isWatch) 48.dp else 72.dp)
                    .clip(navShape)
                    .background(
                        brush = if (isCommandCenter) {
                            Brush.linearGradient(
                                colors = listOf(
                                    glassSurface.copy(alpha = 0.95f),
                                    glassSurface.copy(alpha = 0.85f)
                                )
                            )
                        } else {
                            Brush.linearGradient(
                                colors = listOf(
                                    glassSurface.copy(alpha = 0.9f),
                                    glassSurface.copy(alpha = 0.9f)
                                )
                            )
                        }
                    )
                    .border(
                        width = AppSpacing.Border.Thin,
                        brush = if (isCommandCenter) {
                            Brush.linearGradient(
                                colors = listOf(
                                    AppColors.Accent.Cyan.copy(alpha = 0.6f),
                                    AppColors.Accent.Gold.copy(alpha = 0.3f),
                                    glassBorder.copy(alpha = 0.2f)
                                )
                            )
                        } else {
                            Brush.linearGradient(
                                colors = listOf(glassBorder, glassBorder.copy(alpha = 0.3f))
                            )
                        },
                        shape = navShape
                    ),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                screens.forEach { screen ->
                    val isSelected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    // Gallery 屏幕使用动态标签（船坞/成员），其余屏幕使用固定标签
                    val displayLabel = if (screen.route == Screen.Gallery.route) galleryLabel else screen.label

                    ModernNavigationItem(
                        screen = screen,
                        displayLabel = displayLabel,
                        isSelected = isSelected,
                        onClick = { onNavigate(screen.route) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.ModernNavigationItem(
    screen: Screen,
    displayLabel: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val isWatch = isWatchScreen()
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val accentColor = MaterialTheme.colorScheme.primary
    val haptic = rememberBlyyHaptics()

    // 选中态缩放 — 统一 AppAnimation token（Snappy 弹性）
    val iconScale by animateFloatAsState(
        targetValue = if (isSelected) 1.15f else 1f,
        animationSpec = AppAnimation.Specs.scale(),
        label = "IconScale"
    )

    // 指示器淡入淡出 — 统一 normal token
    val indicatorAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = AppAnimation.Specs.normal(),
        label = "IndicatorAlpha"
    )

    // 选中态光晕呼吸 — gating：仅在 isSelected=true 时挂载 rememberInfiniteTransition，
    // 未选中项移出组合树，零 CPU/GPU 开销（4 个 tab 仅 1 个跑无限动画，省 75% 开销）。
    // 两个分支均返回 State<Float>，由 `by` 委托读取。
    val glowPulse by if (isSelected) {
        rememberInfiniteTransition(label = "navItemGlow").animateFloat(
            initialValue = 0.72f,
            targetValue = 1f,
            animationSpec = AppAnimation.Repeating.glow(duration = 2000),
            label = "glowPulse"
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    Surface(
        onClick = {
            // 轻量触觉反馈 — 商业级导航手感
            haptic(BlyyHaptic.LongPress)
            onClick()
        },
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        color = Color.Transparent,
        shape = RoundedCornerShape(AppSpacing.Corner.Lg)
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = AppSpacing.Sm)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(if (isWatch) 30.dp else 40.dp)
                    .then(
                        if (isCommandCenter && isSelected) {
                            Modifier
                                .background(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            accentColor.copy(alpha = 0.24f * indicatorAlpha * glowPulse),
                                            accentColor.copy(alpha = 0.10f * indicatorAlpha),
                                            Color.Transparent
                                        )
                                    ),
                                    shape = CircleShape
                                )
                                .border(
                                    width = 1.dp,
                                    brush = Brush.sweepGradient(
                                        colors = listOf(
                                            accentColor.copy(alpha = 0.65f * indicatorAlpha * glowPulse),
                                            AppColors.Accent.Gold.copy(alpha = 0.28f * indicatorAlpha),
                                            accentColor.copy(alpha = 0.32f * indicatorAlpha)
                                        )
                                    ),
                                    shape = CircleShape
                                )
                        } else if (isCommandCenter && !isSelected) {
                            // 未选中项也加一层极淡的描边，保持视觉一致性
                            Modifier.border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f),
                                shape = CircleShape
                            )
                        } else if (isSelected) {
                            Modifier
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f * indicatorAlpha),
                                    CircleShape
                                )
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = screen.icon,
                    contentDescription = displayLabel,
                    modifier = Modifier
                        .size(if (isWatch) 18.dp else 24.dp)
                        .scale(iconScale),
                    tint = if (isSelected) {
                        accentColor
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    }
                )
            }

            // 文字与指示器使用 AnimatedVisibility 平滑出入，避免布局跳变
            AnimatedVisibility(
                visible = isSelected,
                enter = fadeIn(tween(AppAnimation.Duration.Fast)) +
                    expandVertically(tween(AppAnimation.Duration.Normal)),
                exit = fadeOut(tween(AppAnimation.Duration.Instant)) +
                    shrinkVertically(tween(AppAnimation.Duration.Fast))
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Spacer(modifier = Modifier.height(if (isWatch) 1.dp else AppSpacing.Xxs))

                    Text(
                        text = displayLabel,
                        style = if (isWatch) AppTypography.NavigationLabel.copy(fontSize = 9.sp) else AppTypography.NavigationLabel,
                        color = accentColor,
                        modifier = Modifier.padding(horizontal = AppSpacing.Sm)
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    // 选中指示条 — 呼吸感与图标光晕同步，增强"活"的视觉律动
                    Box(
                        modifier = Modifier
                            .padding(horizontal = AppSpacing.Sm)
                            .height(3.dp)
                            .width(20.dp)
                            .clip(RoundedCornerShape(AppSpacing.Corner.Xxs))
                            .background(
                                if (isCommandCenter) {
                                    Brush.horizontalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            accentColor.copy(alpha = glowPulse),
                                            AppColors.Accent.Gold.copy(alpha = 0.8f * glowPulse),
                                            accentColor.copy(alpha = glowPulse),
                                            Color.Transparent
                                        )
                                    )
                                } else {
                                    Brush.horizontalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            accentColor,
                                            accentColor,
                                            Color.Transparent
                                        )
                                    )
                                }
                            )
                    )
                }
            }
        }
    }
}

data class DrawerMenuItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val description: String,
    val color: Color,
    val group: String = ""
)

@Composable
internal fun ModernDrawerSheet(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    onClose: () -> Unit
) {
    val isDark = LocalIsDark.current
    val isWatch = isWatchScreen()
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val glassSurface = if (isDark) AppColors.GlassSurfaceDark else AppColors.GlassSurfaceLight
    val accentColor = MaterialTheme.colorScheme.primary

    val menuItems = listOf(
        DrawerMenuItem(
            route = "secretary_mode",
            label = "今日秘书舰",
            icon = Icons.Rounded.Person,
            description = "设置常驻秘书舰，点击播放语音",
            color = MaterialTheme.colorScheme.primary,
            group = "娱乐"
        ),
        DrawerMenuItem(
            route = "live2d",
            label = "Live2D 皮肤",
            icon = Icons.Rounded.ViewInAr,
            description = "导入并查看本地 Live2D 模型",
            color = MaterialTheme.colorScheme.tertiary,
            group = "娱乐"
        ),
        DrawerMenuItem(
            route = "guess_image",
            label = "看图识舰娘",
            icon = Icons.Rounded.Image,
            description = "通过图片辨认舰娘",
            color = MaterialTheme.colorScheme.primary,
            group = "挑战"
        ),
        DrawerMenuItem(
            route = "guess_voice",
            label = "听音识舰娘",
            icon = Icons.Rounded.MusicNote,
            description = "通过语音辨认舰娘",
            color = MaterialTheme.colorScheme.secondary,
            group = "挑战"
        ),
        DrawerMenuItem(
            route = "guess_history",
            label = "历史记录",
            icon = Icons.Rounded.History,
            description = "查看游戏战绩与统计",
            color = MaterialTheme.colorScheme.tertiary,
            group = "挑战"
        ),
        DrawerMenuItem(
            route = "leaderboard",
            label = "积分排行榜",
            icon = Icons.Rounded.Leaderboard,
            description = "查看全服成绩排名",
            color = MaterialTheme.colorScheme.primary,
            group = "挑战"
        ),
        DrawerMenuItem(
            route = "assistant",
            label = "碧蓝航线助手",
            icon = Icons.Rounded.Support,
            description = "查询指挥官信息与建造记录",
            color = MaterialTheme.colorScheme.secondary,
            group = "工具"
        ),
        DrawerMenuItem(
            route = "jiuxin_conversation_list",
            label = "啾信",
            icon = Icons.Rounded.SmartToy,
            description = "与AI舰娘对话",
            color = MaterialTheme.colorScheme.primary,
            group = "工具"
        ),
        DrawerMenuItem(
            route = "watermark_camera",
            label = "水印相机",
            icon = Icons.Rounded.PhotoCamera,
            description = "拍照或选图添加舰娘水印",
            color = MaterialTheme.colorScheme.secondary,
            group = "工具"
        ),
        DrawerMenuItem(
            route = "settings",
            label = "设置",
            icon = Icons.Rounded.Settings,
            description = "界面风格与显示偏好",
            color = MaterialTheme.colorScheme.tertiary,
            group = "系统"
        ),
        DrawerMenuItem(
            route = Screen.About.route,
            label = "关于",
            icon = Icons.Rounded.Star,
            description = "了解更多信息",
            color = MaterialTheme.colorScheme.primary,
            group = "系统"
        )
    )

    val groupedItems = menuItems.groupBy { it.group }

    ModalDrawerSheet(
        modifier = if (isWatch) Modifier.fillMaxWidth(0.95f) else Modifier.fillMaxWidth(0.85f)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        glassSurface,
                        glassSurface.copy(alpha = 0.95f)
                    )
                )
            ),
        drawerContainerColor = Color.Transparent,
        drawerContentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = if (isDark) {
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                Color.Transparent
                            )
                        } else {
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
                                Color.Transparent
                            )
                        }
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md)
            ) {
                // 顶部标题栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = AppSpacing.Lg),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
                    ) {
                        if (isCommandCenter) {
                            // 指挥中心风格：切角矩形图标
                            Box(
                                modifier = Modifier
                                    .size(if (isWatch) 32.dp else 40.dp)
                                    .background(
                                        brush = Brush.linearGradient(
                                            colors = listOf(
                                                accentColor.copy(alpha = 0.2f),
                                                accentColor.copy(alpha = 0.05f)
                                            )
                                        ),
                                        shape = BlyyShapes.Button
                                    )
                                    .border(
                                        width = AppSpacing.Border.Thin,
                                        color = accentColor.copy(alpha = 0.4f),
                                        shape = BlyyShapes.Button
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Menu,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(if (isWatch) 16.dp else 20.dp)
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(if (isWatch) 32.dp else 40.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Menu,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(if (isWatch) 16.dp else 20.dp)
                                )
                            }
                        }
                        Text(
                            text = "玩法菜单",
                            style = if (isWatch) AppTypography.TitleLarge.copy(fontSize = 18.sp) else AppTypography.TitleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(48.dp) // 触摸目标 ≥48dp（WCAG/Material 可访问性）
                            .then(
                                if (isCommandCenter) {
                                    Modifier
                                        .background(
                                            color = Color.Transparent,
                                            shape = BlyyShapes.Button
                                        )
                                        .border(
                                            width = AppSpacing.Border.Thin,
                                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                            shape = BlyyShapes.Button
                                        )
                                } else {
                                    Modifier
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                            CircleShape
                                        )
                                }
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "关闭菜单",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // 分组菜单项
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    groupedItems.forEach { (group, items) ->
                        // 分组标题
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = AppSpacing.Md, bottom = AppSpacing.Xs),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isCommandCenter) {
                                    Box(
                                        modifier = Modifier
                                            .width(3.dp)
                                            .height(14.dp)
                                            .background(
                                                brush = Brush.verticalGradient(
                                                    colors = listOf(accentColor, accentColor.copy(alpha = 0.3f))
                                                ),
                                                shape = RoundedCornerShape(AppSpacing.Corner.Xxs)
                                            )
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .width(3.dp)
                                            .height(14.dp)
                                            .background(
                                                MaterialTheme.colorScheme.primary,
                                                RoundedCornerShape(AppSpacing.Corner.Xxs)
                                            )
                                    )
                                }
                                Spacer(modifier = Modifier.width(AppSpacing.Sm))
                                Text(
                                    text = group,
                                    style = AppTypography.LabelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }

                        // 分组内菜单项
                        items(count = items.size) { index ->
                            val item = items[index]
                            ModernDrawerItem(
                                item = item,
                                isSelected = currentRoute == item.route,
                                isLastInGroup = index == items.size - 1,
                                onClick = { onNavigate(item.route) }
                            )
                        }
                    }
                }

                // 底部应用信息
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = AppSpacing.Sm),
                    shape = if (isCommandCenter) BlyyShapes.PanelSmall else RoundedCornerShape(AppSpacing.Corner.Lg),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = accentColor.copy(alpha = 0.6f),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "碧蓝航线语音图鉴",
                            style = AppTypography.LabelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernDrawerItem(
    item: DrawerMenuItem,
    isSelected: Boolean,
    isLastInGroup: Boolean,
    onClick: () -> Unit
) {
    val isWatch = isWatchScreen()
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()

    val selectedBg = item.color.copy(alpha = 0.10f)
    val selectedBorder = item.color.copy(alpha = 0.25f)

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = if (isLastInGroup) AppSpacing.Xs else AppSpacing.None),
        shape = if (isCommandCenter) BlyyShapes.PanelSmall else RoundedCornerShape(AppSpacing.Corner.Lg),
        color = if (isSelected) selectedBg else Color.Transparent,
        shadowElevation = 0.dp,
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, selectedBorder) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
        ) {
            Box(
                modifier = Modifier
                    .size(if (isWatch) 36.dp else 42.dp)
                    .then(
                        if (isCommandCenter) {
                            Modifier
                                .background(
                                    color = item.color.copy(alpha = if (isSelected) 0.18f else 0.08f),
                                    shape = BlyyShapes.Button
                                )
                                .border(
                                    width = AppSpacing.Border.Thin,
                                    color = item.color.copy(alpha = if (isSelected) 0.35f else 0.12f),
                                    shape = BlyyShapes.Button
                                )
                        } else {
                            Modifier.background(
                                color = item.color.copy(alpha = if (isSelected) 0.18f else 0.08f),
                                shape = CircleShape
                            )
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = item.color,
                    modifier = Modifier.size(if (isWatch) 18.dp else 22.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = item.label,
                    style = AppTypography.TitleMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (isSelected) item.color else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = item.description,
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(20.dp)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(item.color, item.color.copy(alpha = 0.3f))
                            ),
                            shape = RoundedCornerShape(AppSpacing.Corner.Xxs)
                        )
                )
            }
        }
    }
}

/**
 * 应用更新可用时的模态弹窗。
 *
 * 显示新版本号、更新内容摘要，并提供两种更新渠道：
 * - 通过 GitHub 更新（直链 APK 或 Releases 页面）
 * - 通过网盘更新（在线获取自 GitHub 仓库的备用链接）
 *
 * 若网盘链接未配置，则仅显示 GitHub 更新按钮，保持向后兼容。
 *
 * 暗色模式优化：
 * - 版本号卡片使用渐变背景 + 左侧装饰条，提高暗色下的视觉锚点
 * - 渠道选项卡片在暗色下使用更鲜明的背景与边框，增强可点击感
 * - Command Center 风格下保持 HUD 玻璃质感；Classic 风格下使用 Material 配色
 */
@Composable
internal fun UpdateAvailableDialog(
    updateInfo: UpdateInfo,
    isRefreshingDriveLink: Boolean = false,
    onUpdateViaGithub: () -> Unit,
    onUpdateViaDrive: () -> Unit,
    onSkipVersion: () -> Unit,
    onDismiss: () -> Unit
) {
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val isDark = LocalIsDark.current
    val isWatch = isWatchScreen()
    val accentColor = if (isCommandCenter) AppColors.Accent.Cyan else MaterialTheme.colorScheme.primary
    val driveLink = updateInfo.driveLink

    // 图标呼吸脉冲动画 — 吸引注意力，让更新提示更有"活力"
    // 共享同一 infiniteTransition 与 glow token，两个 animateFloat 同步呼吸
    val infiniteTransition = rememberInfiniteTransition(label = "updateIconPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = if (isDark) 0.2f else 0.12f,
        targetValue = if (isDark) 0.5f else 0.35f,
        animationSpec = AppAnimation.Repeating.glow(duration = 1200),
        label = "pulseAlpha"
    )
    val iconScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.08f,
        animationSpec = AppAnimation.Repeating.glow(duration = 1200),
        label = "iconScale"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(if (isWatch) 36.dp else 52.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accentColor.copy(alpha = pulseAlpha),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier
                        .size(24.dp)
                        .scale(iconScale)
                )
            }
        },
        title = {
            Text(
                text = "发现新版本",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 版本号卡片 — 渐变背景 + 左侧装饰条 + 边框 + 版本对比
                Surface(
                    shape = BlyyShapes.PanelSmall,
                    color = Color.Transparent,
                    border = androidx.compose.foundation.BorderStroke(
                        width = AppSpacing.Border.Thin,
                        color = accentColor.copy(alpha = if (isDark) 0.4f else 0.25f)
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                brush = Brush.linearGradient(
                                    colors = if (isDark) {
                                        listOf(
                                            accentColor.copy(alpha = 0.22f),
                                            accentColor.copy(alpha = 0.06f)
                                        )
                                    } else {
                                        listOf(
                                            accentColor.copy(alpha = 0.12f),
                                            accentColor.copy(alpha = 0.03f)
                                        )
                                    }
                                )
                            )
                    ) {
                        // 左侧装饰条 — 强化视觉锚点
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .width(3.dp)
                                .height(36.dp)
                                .background(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            accentColor,
                                            accentColor.copy(alpha = 0.4f)
                                        )
                                    )
                                )
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = AppSpacing.Lg, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 当前版本 → 新版本 对比，让用户直观看到变化
                            Text(
                                text = "v${updateInfo.currentVersion}",
                                style = AppTypography.LabelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = accentColor.copy(alpha = 0.7f),
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "v${updateInfo.versionName}",
                                style = AppTypography.TitleMedium,
                                fontWeight = FontWeight.Bold,
                                color = accentColor
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            // NEW 徽章
                            Box(
                                modifier = Modifier
                                    .background(
                                        brush = Brush.linearGradient(
                                            colors = listOf(
                                                accentColor,
                                                accentColor.copy(alpha = 0.7f)
                                            )
                                        ),
                                        shape = RoundedCornerShape(AppSpacing.Corner.Xxs)
                                    )
                                    .padding(horizontal = 6.dp, vertical = AppSpacing.Xxs)
                            ) {
                                Text(
                                    text = "NEW",
                                    style = AppTypography.LabelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDark) Color.Black else Color.White,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }

                if (updateInfo.changelog.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "更新内容",
                            style = AppTypography.LabelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (isWatch) Modifier.heightIn(max = 100.dp) else Modifier.heightIn(max = 160.dp))
                        ) {
                            LazyColumn {
                                item {
                                    Text(
                                        text = updateInfo.changelog,
                                        style = AppTypography.BodySmall,
                                        lineHeight = 20.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.9f else 1f)
                                    )
                                }
                            }
                        }
                    }
                }

                // 更新渠道选择
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "选择更新方式",
                        style = AppTypography.LabelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // GitHub 渠道
                    UpdateChannelOption(
                        icon = Icons.Rounded.Github,
                        title = "通过 GitHub 更新",
                        subtitle = "官方仓库直链，速度可能较慢",
                        accentColor = accentColor,
                        onClick = onUpdateViaGithub
                    )

                    // 网盘渠道（仅在有可用链接时显示）
                    if (driveLink != null) {
                        UpdateChannelOption(
                            icon = Icons.Rounded.CloudDownload,
                            title = "通过${driveLink.label}更新",
                            subtitle = driveLink.note.ifEmpty { "备用下载渠道" },
                            accentColor = AppColors.Accent.GoldDark,
                            isLoading = isRefreshingDriveLink,
                            onClick = onUpdateViaDrive
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onSkipVersion) {
                Text("稍后自行更新")
            }
        },
        shape = if (isWatch) RoundedCornerShape(AppSpacing.Corner.Lg) else RoundedCornerShape(AppSpacing.Corner.Xxl)
    )
}

/**
 * 更新渠道选项卡片 — 用于 UpdateAvailableDialog 内的渠道选择。
 *
 * 暗色模式优化：
 * - Command Center 风格：保持 HUD 玻璃面板质感，暗色下提高边框亮度
 * - Classic 风格：使用 surfaceContainerHigh 而非 AppColors.Panel，与 AlertDialog 背景协调
 * - 图标圆形背景在暗色下提高饱和度，增强视觉吸引力
 */
@Composable
private fun UpdateChannelOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
    isLoading: Boolean = false,
    onClick: () -> Unit
) {
    val isDark = LocalIsDark.current
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val shape = if (isCommandCenter) BlyyShapes.Button else RoundedCornerShape(AppSpacing.Corner.Md)

    // Classic 风格下使用 Material 配色与 AlertDialog 背景协调；
    // Command Center 风格下保持 Panel 玻璃质感
    val containerColor = when {
        isCommandCenter && isDark -> AppColors.Panel.Dark.copy(alpha = 0.85f)
        isCommandCenter && !isDark -> AppColors.Panel.Light.copy(alpha = 0.75f)
        isDark -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
    }
    val borderColor = accentColor.copy(alpha = if (isDark) 0.5f else 0.3f)
    val iconBgAlpha = if (isDark) 0.22f else 0.15f

    Surface(
        onClick = onClick,
        enabled = !isLoading,
        shape = shape,
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(
            width = AppSpacing.Border.Thin,
            color = borderColor
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Md, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accentColor.copy(alpha = iconBgAlpha),
                                accentColor.copy(alpha = iconBgAlpha * 0.4f)
                            )
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = accentColor
                    )
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = title,
                    style = AppTypography.LabelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
