package com.azurlane.blyy.ui

// 应用根组合函数 — 导航图、底部导航栏、抽屉菜单与更新弹窗
// 从 MainActivity 拆分而来，Activity 仅保留生命周期与悬浮窗服务控制职责

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
import com.azurlane.blyy.ui.screens.live2d.Live2dLibraryScreen
import com.azurlane.blyy.ui.screens.live2d.Live2dViewerScreen
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
import com.azurlane.blyy.ui.components.ModernNavigationBar
import com.azurlane.blyy.ui.components.ModernDrawerSheet
import com.azurlane.blyy.ui.components.UpdateAvailableDialog

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Home : Screen("home", "后宅", Icons.Default.Home)
    object Gallery : Screen("gallery", "船坞", Icons.AutoMirrored.Filled.List)
    object About : Screen("about", "关于", Icons.Default.Info)
}

private const val TAG = "AppRoot"

/** Material Motion — Fade Through，用于底部 Tab 同级切换 */
private fun tabFadeThroughEnter(): EnterTransition =
    fadeIn(
        animationSpec = tween(300, delayMillis = 90, easing = AppAnimation.Easings.EmphasizedDecelerate)
    ) + scaleIn(
        initialScale = 0.96f,
        animationSpec = tween(300, delayMillis = 90, easing = AppAnimation.Easings.EmphasizedDecelerate)
    )

private fun tabFadeThroughExit(): ExitTransition =
    fadeOut(animationSpec = tween(90, easing = AppAnimation.Easings.EmphasizedAccelerate)) +
        scaleOut(
            targetScale = 1.04f,
            animationSpec = tween(90, easing = AppAnimation.Easings.EmphasizedAccelerate)
        )

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
@UnstableApi
fun AppContent() {
    val navController = rememberNavController()
    val screens = listOf(Screen.Home, Screen.Gallery)
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val context = LocalContext.current
    val haptic = rememberBlyyHaptics()
    val scope = rememberCoroutineScope()

    val showBottomBar = currentDestination?.route?.startsWith("voice/") != true &&
            currentDestination?.route?.startsWith("gallery/") != true &&
            currentDestination?.route?.startsWith("student_voice/") != true &&
            currentDestination?.route?.startsWith("student_gallery/") != true &&
            currentDestination?.route?.startsWith("guess_image") != true &&
            currentDestination?.route?.startsWith("guess_voice") != true &&
            currentDestination?.route?.startsWith("guess_history") != true &&
            currentDestination?.route != "leaderboard" &&
            currentDestination?.route != Screen.About.route &&
            currentDestination?.route?.startsWith("secretary") != true &&
            currentDestination?.route != "settings" &&
            currentDestination?.route?.startsWith("live2d") != true &&
            currentDestination?.route != "assistant" &&
            currentDestination?.route != "assistant_config" &&
            currentDestination?.route != "jiuxin_config" &&
            currentDestination?.route != "jiuxin_chat" &&
            currentDestination?.route != "jiuxin_ship_config" &&
            currentDestination?.route != "jiuxin_conversation_list" &&
            currentDestination?.route != "app_icon_settings" &&
            currentDestination?.route != "sd_resource_gallery" &&
            currentDestination?.route?.startsWith("image_cropper") != true &&
            currentDestination?.route != "watermark_camera" &&
            currentDestination?.route?.startsWith("watermark_editor") != true

    val drawerState = remember { DrawerState(initialValue = DrawerValue.Closed) }

    // 导航目标变化时自动关闭菜单，防止切换到其他界面时侧拉菜单错误显示
    LaunchedEffect(currentDestination?.route) {
        if (drawerState.isOpen) {
            drawerState.close()
        }
    }

    // 屏幕方向变化时自动关闭菜单，防止横屏模式下侧拉菜单错误保持显示
    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration.orientation) {
        if (drawerState.isOpen && configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            drawerState.close()
        }
    }

    var isBottomBarVisible by remember { mutableStateOf(true) }

    // 底部导航 Gallery 标签动态文本 — DOCK 模式"船坞"，STUDENT 模式"成员"
    var galleryLabel by remember { mutableStateOf("船坞") }

    val bottomBarOffset by animateFloatAsState(
        targetValue = if (showBottomBar && isBottomBarVisible) 0f else 300f,
        animationSpec = AppAnimation.Springs.Gentle,
        label = "bottomBarOffset"
    )
    val bottomBarAlpha by animateFloatAsState(
        targetValue = if (showBottomBar && isBottomBarVisible) 1f else 0f,
        animationSpec = tween(durationMillis = AppAnimation.Duration.Fast, easing = AppAnimation.Easings.Standard),
        label = "bottomBarAlpha"
    )

    SharedTransitionLayout {
        // ── 启动时自动检测更新 ──
        // 状态由 UpdateCheckViewModel 持有，确保配置变更时不丢失，且 init 块只执行一次
        val updateCheckViewModel: UpdateCheckViewModel = hiltViewModel()
        val updateChecker: AppUpdateChecker = updateCheckViewModel.updateChecker
        val updateInfo by updateCheckViewModel.updateInfo.collectAsStateWithLifecycle()
        val isRefreshingDriveLink by updateCheckViewModel.isRefreshingDriveLink.collectAsStateWithLifecycle()

        if (updateInfo != null) {
            // 先捕获当前值，避免回调中 updateInfo 已被置空导致 NPE
            val currentUpdateInfo = updateInfo!!
            UpdateAvailableDialog(
                updateInfo = currentUpdateInfo,
                isRefreshingDriveLink = isRefreshingDriveLink,
                onUpdateViaGithub = {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUpdateInfo.downloadUrl))
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to open GitHub update URL", e)
                        Toast.makeText(context, "无法打开浏览器，请稍后重试", Toast.LENGTH_SHORT).show()
                    }
                    updateCheckViewModel.dismissUpdate()
                },
                onUpdateViaDrive = {
                    // 点击"通过网盘更新"时，先强制刷新网盘链接，确保打开的是 GitHub 仓库中最新的链接
                    // 而非启动检查时缓存的旧链接。刷新期间 UI 显示 loading 状态。
                    updateCheckViewModel.refreshDriveLink { latestLink ->
                        val linkToOpen = latestLink ?: currentUpdateInfo.driveLink
                        if (linkToOpen != null) {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(linkToOpen.url))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to open drive URL", e)
                                Toast.makeText(context, "无法打开浏览器，请稍后重试", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "网盘链接获取失败，请稍后重试或使用 GitHub 更新", Toast.LENGTH_SHORT).show()
                        }
                        updateCheckViewModel.dismissUpdate()
                    }
                },
                onSkipVersion = {
                    // 用户点击"稍后自行更新"按钮 → 记录跳过版本，下次启动不再提示
                    scope.launch {
                        try {
                            updateChecker.skipVersion(currentUpdateInfo.versionName)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to skip version", e)
                        }
                    }
                    updateCheckViewModel.dismissUpdate()
                },
                onDismiss = {
                    // 用户点击空白区域或按返回键 → 仅关闭弹窗，不记录跳过，下次启动仍会提示
                    updateCheckViewModel.dismissUpdate()
                }
            )
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            // Live2D 查看器内横向拖动用于平移模型：关闭抽屉边缘手势，
            // 否则从左边缘起手的拖动会被抽屉抢走并弹出菜单栏
            gesturesEnabled = currentDestination?.route?.startsWith("live2d/view") != true,
            drawerContent = {
                ModernDrawerSheet(
                    currentRoute = currentDestination?.route,
                    onNavigate = { route ->
                        haptic(BlyyHaptic.Tick)
                        // 先关闭抽屉，等待关闭动画完成后再导航，避免菜单与页面切换动画冲突
                        scope.launch {
                            drawerState.close()
                            navController.navigate(route) { launchSingleTop = true }
                        }
                    },
                    onClose = {
                        haptic(BlyyHaptic.Tick)
                        scope.launch { drawerState.close() }
                    }
                )
            }
        ) {
            val secretaryViewModel: SecretaryShipViewModel = hiltViewModel()
            val secretaryState by secretaryViewModel.state.collectAsStateWithLifecycle()

            Box(modifier = Modifier.fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = Screen.Home.route,
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = {
                        // Material Motion — Container Transform 风格：横向滑入 + 淡入
                        // 注：slideInHorizontally 需 FiniteAnimationSpec<IntOffset>，
                        // AppAnimation.Springs.Gentle 是 spring<Float> 无法直接复用，
                        // 故内联 spring 与 Gentle 同参（dampingRatio=0.9, stiffness=MediumLow）
                        slideInHorizontally(
                            initialOffsetX = { it / 3 },
                            animationSpec = spring(
                                dampingRatio = 0.9f,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        ) + fadeIn(
                            tween(
                                durationMillis = AppAnimation.Duration.Normal,
                                delayMillis = 50,
                                easing = AppAnimation.Easings.EmphasizedDecelerate
                            )
                        )
                    },
                    exitTransition = {
                        slideOutHorizontally(
                            targetOffsetX = { -it / 5 },
                            animationSpec = tween(
                                durationMillis = AppAnimation.Duration.Normal,
                                easing = AppAnimation.Easings.EmphasizedAccelerate
                            )
                        ) + fadeOut(
                            tween(
                                durationMillis = AppAnimation.Duration.Fast,
                                easing = AppAnimation.Easings.EmphasizedAccelerate
                            )
                        )
                    },
                    popEnterTransition = {
                        slideInHorizontally(
                            initialOffsetX = { -it / 5 },
                            animationSpec = spring(
                                dampingRatio = 0.9f,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        ) + fadeIn(
                            tween(
                                durationMillis = AppAnimation.Duration.Normal,
                                delayMillis = 50,
                                easing = AppAnimation.Easings.EmphasizedDecelerate
                            )
                        )
                    },
                    popExitTransition = {
                        slideOutHorizontally(
                            targetOffsetX = { it / 3 },
                            animationSpec = tween(
                                durationMillis = AppAnimation.Duration.Normal,
                                easing = AppAnimation.Easings.EmphasizedAccelerate
                            )
                        ) + fadeOut(
                            tween(
                                durationMillis = AppAnimation.Duration.Fast,
                                easing = AppAnimation.Easings.EmphasizedAccelerate
                            )
                        )
                    }
                ) {
                    composable(
                        route = Screen.Home.route,
                        enterTransition = { tabFadeThroughEnter() },
                        exitTransition = { tabFadeThroughExit() },
                        popEnterTransition = { tabFadeThroughEnter() },
                        popExitTransition = { tabFadeThroughExit() }
                    ) {
                        val viewModel: HomeViewModel = hiltViewModel()
                        val state by viewModel.state.collectAsStateWithLifecycle()
                        HomeScreen(
                            state = state,
                            onIntent = viewModel::onIntent,
                            onShipClick = { ship -> navController.navigate("voice/${ship.name}?avatarUrl=${Uri.encode(ship.avatarUrl)}") },
                            onNavigateToGallery = {
                                haptic(BlyyHaptic.Tick)
                                navController.navigate(Screen.Gallery.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            onShowGallery = { ship -> navController.navigate("gallery/${ship.name}?avatarUrl=${Uri.encode(ship.avatarUrl)}") },
                            onOpenMenu = {
                                haptic(BlyyHaptic.Tick)
                                scope.launch { drawerState.open() }
                            },
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedContentScope = this
                        )
                    }
                    composable(
                        route = Screen.Gallery.route,
                        enterTransition = { tabFadeThroughEnter() },
                        exitTransition = { tabFadeThroughExit() },
                        popEnterTransition = { tabFadeThroughEnter() },
                        popExitTransition = { tabFadeThroughExit() }
                    ) {
                        val viewModel: GalleryViewModel = hiltViewModel()
                        val state by viewModel.state.collectAsStateWithLifecycle()
                        val filteredShips by viewModel.filteredShips.collectAsStateWithLifecycle()
                        // 档案类型变化时同步底部导航标签：DOCK→"船坞"，STUDENT→"成员"
                        LaunchedEffect(state.archiveType) {
                            galleryLabel = when (state.archiveType) {
                                ArchiveType.STUDENT -> "成员"
                                ArchiveType.DOCK -> "船坞"
                            }
                        }
                        GalleryScreen(
                            state = state,
                            filteredShips = filteredShips,
                            onIntent = viewModel::onIntent,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedContentScope = this,
                            onShipClick = { ship ->
                                when (state.archiveType) {
                                    ArchiveType.DOCK -> navController.navigate("voice/${ship.name}?avatarUrl=${Uri.encode(ship.avatarUrl)}")
                                    ArchiveType.STUDENT -> navController.navigate("student_voice/${ship.name}?avatarUrl=${Uri.encode(ship.avatarUrl)}&studentLink=${Uri.encode(ship.link)}")
                                }
                            },
                            onShowGallery = { ship ->
                                when (state.archiveType) {
                                    ArchiveType.DOCK -> navController.navigate("gallery/${ship.name}?avatarUrl=${Uri.encode(ship.avatarUrl)}")
                                    ArchiveType.STUDENT -> navController.navigate("student_gallery/${ship.name}?avatarUrl=${Uri.encode(ship.avatarUrl)}&studentLink=${Uri.encode(ship.link)}")
                                }
                            },
                            onScrollStateChange = { isScrolling ->
                                isBottomBarVisible = !isScrolling
                            }
                        )
                    }
                    composable(
                        route = "voice/{shipName}?avatarUrl={avatarUrl}",
                        arguments = listOf(
                            navArgument("shipName") { type = NavType.StringType },
                            navArgument("avatarUrl") { type = NavType.StringType }
                        ),
                        deepLinks = listOf(
                            navDeepLink { uriPattern = "blyy://voice/{shipName}?avatarUrl={avatarUrl}" }
                        )
                    ) { backStackEntry ->
                        val shipName = backStackEntry.arguments?.getString("shipName")
                        val avatarUrl = backStackEntry.arguments?.getString("avatarUrl")
                        val viewModel: VoiceViewModel = hiltViewModel()

                        LaunchedEffect(shipName, avatarUrl) {
                            if (shipName != null && avatarUrl != null) {
                                viewModel.onIntent(VoiceIntent.LoadVoices(shipName, avatarUrl))
                            }
                        }

                        VoiceScreen(
                            onBack = { navController.popBackStack() },
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedContentScope = this,
                            voiceViewModel = viewModel
                        )
                    }
                    composable(
                        route = "gallery/{shipName}?avatarUrl={avatarUrl}",
                        arguments = listOf(
                            navArgument("shipName") { type = NavType.StringType },
                            navArgument("avatarUrl") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        val shipName = backStackEntry.arguments?.getString("shipName") ?: ""
                        val avatarUrl = backStackEntry.arguments?.getString("avatarUrl") ?: ""
                        val viewModel: ShipGalleryViewModel = hiltViewModel()
                        val galleryState by viewModel.state.collectAsStateWithLifecycle()

                        LaunchedEffect(shipName) {
                            viewModel.loadGallery(shipName)
                        }

                        ShipGalleryScreen(
                            shipName = shipName,
                            avatarUrl = avatarUrl,
                            state = galleryState,
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(
                        route = "student_voice/{studentName}?avatarUrl={avatarUrl}&studentLink={studentLink}",
                        arguments = listOf(
                            navArgument("studentName") { type = NavType.StringType },
                            navArgument("avatarUrl") { type = NavType.StringType },
                            navArgument("studentLink") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        val studentName = backStackEntry.arguments?.getString("studentName") ?: ""
                        val avatarUrl = backStackEntry.arguments?.getString("avatarUrl") ?: ""
                        val studentLink = backStackEntry.arguments?.getString("studentLink") ?: ""
                        val viewModel: VoiceViewModel = hiltViewModel()

                        LaunchedEffect(studentName, avatarUrl, studentLink) {
                            if (studentName.isNotEmpty() && avatarUrl.isNotEmpty() && studentLink.isNotEmpty()) {
                                viewModel.onIntent(
                                    VoiceIntent.LoadStudentVoices(studentName, avatarUrl, studentLink)
                                )
                            }
                        }

                        VoiceScreen(
                            onBack = { navController.popBackStack() },
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedContentScope = this,
                            voiceViewModel = viewModel
                        )
                    }
                    composable(
                        route = "student_gallery/{studentName}?avatarUrl={avatarUrl}&studentLink={studentLink}",
                        arguments = listOf(
                            navArgument("studentName") { type = NavType.StringType },
                            navArgument("avatarUrl") { type = NavType.StringType },
                            navArgument("studentLink") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        val studentName = backStackEntry.arguments?.getString("studentName") ?: ""
                        val avatarUrl = backStackEntry.arguments?.getString("avatarUrl") ?: ""
                        val studentLink = backStackEntry.arguments?.getString("studentLink") ?: ""
                        val viewModel: StudentGalleryViewModel = hiltViewModel()
                        val galleryState by viewModel.state.collectAsStateWithLifecycle()

                        LaunchedEffect(studentLink) {
                            if (studentLink.isNotEmpty()) {
                                viewModel.loadGallery(studentLink)
                            }
                        }

                        StudentGalleryScreen(
                            studentName = studentName,
                            avatarUrl = avatarUrl,
                            state = galleryState,
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("guess_image") {
                        val viewModel: GuessShipViewModel = hiltViewModel()
                        GuessByImageScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() },
                            onHistory = { navController.navigate("guess_history") }
                        )
                    }
                    composable("guess_voice") {
                        val viewModel: GuessShipViewModel = hiltViewModel()
                        GuessByVoiceScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() },
                            onHistory = { navController.navigate("guess_history") }
                        )
                    }
                    composable("guess_history") {
                        GuessHistoryScreen(
                            onBack = { navController.popBackStack() },
                            onLeaderboard = { navController.navigate("leaderboard") },
                            onNavigateToAssistantConfig = { navController.navigate("assistant_config") }
                        )
                    }
                    composable("watermark_camera") {
                        WatermarkCameraScreen(
                            onBack = { navController.popBackStack() },
                            onEditImage = { imageUri, watermarkId ->
                                // URI 必须编码，避免 content:// 中的特殊字符破坏 query 参数
                                val encodedUri = Uri.encode(imageUri.toString())
                                val encodedWid = watermarkId?.let { Uri.encode(it) } ?: ""
                                navController.navigate("watermark_editor?uri=$encodedUri&wid=$encodedWid")
                            }
                        )
                    }
                    composable(
                        route = "watermark_editor?uri={uri}&wid={wid}",
                        arguments = listOf(
                            navArgument("uri") {
                                type = NavType.StringType
                                defaultValue = ""
                            },
                            navArgument("wid") {
                                type = NavType.StringType
                                defaultValue = ""
                            }
                        )
                    ) { entry ->
                        val uriStr = entry.arguments?.getString("uri").orEmpty()
                        val widStr = entry.arguments?.getString("wid").orEmpty()
                        WatermarkEditorScreen(
                            imageUri = uriStr.takeIf { it.isNotBlank() }?.let(Uri::parse),
                            initialWatermarkId = widStr.takeIf { it.isNotBlank() },
                            onBack = { navController.popBackStack() },
                            onRetake = {
                                // 返回相机页重拍；相机页不在栈中时兜底导航
                                if (!navController.popBackStack("watermark_camera", false)) {
                                    navController.navigate("watermark_camera") { launchSingleTop = true }
                                }
                            }
                        )
                    }
                    composable("leaderboard") {
                        LeaderboardScreen(
                            onBack = { navController.popBackStack() },
                            onNavigateToAssistantConfig = { navController.navigate("assistant_config") }
                        )
                    }
                    composable(Screen.About.route) {
                        AboutScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            onBack = { navController.popBackStack() },
                            onNavigateToAssistantConfig = { navController.navigate("assistant_config") },
                            onNavigateToJiuxinConfig = { navController.navigate("jiuxin_config") },
                            onNavigateToAppIconSettings = { navController.navigate("app_icon_settings") }
                        )
                    }
                    composable("app_icon_settings") {
                        IconSettingsScreen(
                            onBack = { navController.popBackStack() },
                            onNavigateToCropper = { imageUri ->
                                // 将图片 URI 编码后作为路径参数传递
                                val encoded = android.net.Uri.encode(imageUri.toString())
                                navController.navigate("image_cropper/$encoded")
                            }
                        )
                    }
                    composable(
                        route = "image_cropper/{imageUri}",
                        arguments = listOf(
                            navArgument("imageUri") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        val imageUriStr = backStackEntry.arguments?.getString("imageUri") ?: ""
                        val imageUri = android.net.Uri.parse(imageUriStr)
                        ImageCropperScreen(
                            imageUri = imageUri,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("assistant") {
                        AssistantScreen(
                            onBack = { navController.popBackStack() },
                            onNavigateToSettings = {
                                navController.navigate("settings") { launchSingleTop = true }
                            }
                        )
                    }
                    composable("assistant_config") {
                        AssistantConfigScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("jiuxin_config") {
                        JiuxinConfigScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("jiuxin_ship_config") {
                        JiuxinShipConfigScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("jiuxin_chat") {
                        JiuxinChatScreen(
                            onBack = { navController.popBackStack() },
                            onNavigateToConfig = { navController.navigate("jiuxin_config") },
                            onNavigateToShipConfig = { navController.navigate("jiuxin_ship_config") }
                        )
                    }
                    composable("jiuxin_conversation_list") {
                        ConversationListScreen(
                            onBack = { navController.popBackStack() },
                            onNavigateToChat = { navController.navigate("jiuxin_chat") },
                            onNavigateToConfig = { navController.navigate("jiuxin_config") }
                        )
                    }
                    composable("secretary_mode") {
                        val sdResourceLink by secretaryViewModel.sdResourceLink.collectAsStateWithLifecycle()
                        val isRefreshingSdLink by secretaryViewModel.isRefreshingSdLink.collectAsStateWithLifecycle()
                        SecretaryShipModeScreen(
                            secretaryState = secretaryState,
                            onBack = { navController.popBackStack() },
                            onRandomFlip = { navController.navigate("secretary_random") },
                            onSelectFromHome = { navController.navigate("secretary_pick_home") },
                            onSelectFromGallery = { navController.navigate("secretary_pick_gallery") },
                            onClearSecretary = {
                                secretaryViewModel.onIntent(SecretaryShipIntent.ClearSecretary)
                            },
                            onOpenSettings = { navController.navigate("secretary_settings") },
                            sdResourceLink = sdResourceLink,
                            isRefreshingSdLink = isRefreshingSdLink,
                            onRefreshSdLink = { secretaryViewModel.refreshSdResourceLink() },
                            onEnsureSdLinkLoaded = { secretaryViewModel.ensureSdResourceLinkLoaded() }
                        )
                    }
                    composable("secretary_settings") {
                        // 使用 collectAsState 实现响应式状态更新
                        val overlayEnabled by MainActivity.overlayState.collectAsState()

                        SecretaryShipSettingsScreen(
                            secretaryState = secretaryState,
                            onBack = { navController.popBackStack() },
                            isOverlayEnabled = overlayEnabled,
                            onToggleOverlay = { enabled ->
                                val activity = context as? MainActivity
                                if (enabled) {
                                    activity?.startOverlayService()
                                } else {
                                    activity?.stopOverlayService()
                                }
                            },
                            onToggleDialogue = { enabled ->
                                secretaryViewModel.onIntent(SecretaryShipIntent.SetDialogueEnabled(enabled))
                            },
                            onSetAutoPlay = { enabled, interval ->
                                secretaryViewModel.onIntent(SecretaryShipIntent.SetAutoPlay(enabled, interval))
                            },
                            onSetSdSkin = { skin ->
                                secretaryViewModel.onIntent(SecretaryShipIntent.SetSdSkin(skin))
                            },
                            onSetSdScale = { scale ->
                                secretaryViewModel.onIntent(SecretaryShipIntent.SetSdScale(scale))
                            },
                            onOpenSdGallery = { navController.navigate("sd_resource_gallery") },
                            onClearSdResource = {
                                secretaryViewModel.onIntent(SecretaryShipIntent.SetSdResourceId(""))
                            },
                            onToggleOverlayTouchPassthrough = { enabled ->
                                secretaryViewModel.onIntent(SecretaryShipIntent.SetOverlayTouchPassthrough(enabled))
                            }
                        )
                    }
                    composable("sd_resource_gallery") {
                        SdResourceGalleryScreen(
                            selectedResourceId = secretaryState.sdResourceId,
                            onSelectResource = { resourceId ->
                                secretaryViewModel.onIntent(SecretaryShipIntent.SetSdResourceId(resourceId))
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("live2d") {
                        Live2dLibraryScreen(
                            onBack = { navController.popBackStack() },
                            onOpenViewer = { modelId, displayName ->
                                // id 与显示名都编码，避免特殊字符破坏路由
                                navController.navigate(
                                    "live2d/view/${Uri.encode(modelId)}?name=${Uri.encode(displayName)}"
                                )
                            }
                        )
                    }
                    composable(
                        route = "live2d/view/{modelId}?name={name}",
                        arguments = listOf(
                            navArgument("modelId") { type = NavType.StringType },
                            navArgument("name") {
                                type = NavType.StringType
                                defaultValue = ""
                            }
                        )
                    ) {
                        Live2dViewerScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("secretary_random") {
                        SecretaryShipRandomScreen(
                            viewModel = secretaryViewModel,
                            onBack = { navController.popBackStack() },
                            onComplete = { navController.navigate("home") { popUpTo("secretary_mode") { inclusive = true } } }
                        )
                    }
                    composable("secretary_pick_home") {
                        val homeVm: HomeViewModel = hiltViewModel()
                        val homeState by homeVm.state.collectAsStateWithLifecycle()
                        SecretaryShipPickFromHomeScreen(
                            ships = homeState.favoriteShips,
                            onBack = { navController.popBackStack() },
                            onShipSelected = { ship ->
                                secretaryViewModel.onIntent(SecretaryShipIntent.SelectShip(ship))
                                navController.navigate("home") { popUpTo("secretary_mode") { inclusive = true } }
                            }
                        )
                    }
                    composable("secretary_pick_gallery") {
                        val galleryVm: GalleryViewModel = hiltViewModel()
                        val filteredShips by galleryVm.filteredShips.collectAsStateWithLifecycle()
                        SecretaryShipPickFromGalleryScreen(
                            ships = filteredShips,
                            onBack = { navController.popBackStack() },
                            onShipSelected = { ship ->
                                secretaryViewModel.onIntent(SecretaryShipIntent.SelectShip(ship))
                                navController.navigate("home") { popUpTo("secretary_mode") { inclusive = true } }
                            }
                        )
                    }
                }

                // 只有当悬浮窗未开启时才在应用内显示立绘
                // 显示条件：figureUrl 非空（网络立绘回退）或 sdResourceId 非空（SD 资源直接渲染）
                val overlayEnabledForChibi by MainActivity.overlayState.collectAsState()
                val hasChibiContent = secretaryState.figureUrl.isNotEmpty() ||
                    secretaryState.sdResourceId.isNotEmpty()
                if (hasChibiContent && !overlayEnabledForChibi) {
                    SecretaryChibiOverlay(
                        figureUrl = secretaryState.figureUrl,
                        shipName = secretaryState.shipName,
                        dialogue = if (secretaryState.dialogueEnabled) secretaryState.currentDialogue else null,
                        modifier = Modifier.fillMaxSize(),
                        onTap = {
                            secretaryViewModel.ensureVoicesLoaded(secretaryState.shipName)
                            secretaryViewModel.onIntent(SecretaryShipIntent.PlayRandomVoice)
                        },
                        selectedSkin = secretaryState.sdSkin,
                        sdScale = secretaryState.sdScale,
                        sdResourceId = secretaryState.sdResourceId
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .graphicsLayer {
                            translationY = bottomBarOffset
                            alpha = bottomBarAlpha
                        }
                ) {
                    ModernNavigationBar(
                        screens = screens,
                        currentDestination = currentDestination,
                        galleryLabel = galleryLabel,
                        onNavigate = { route ->
                            haptic(BlyyHaptic.Tick)
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    }
}
