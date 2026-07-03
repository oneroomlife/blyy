package com.azurlane.blyy

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Leaderboard
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Support
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.collectAsState
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
import com.azurlane.blyy.ui.components.adaptiveGlassBorder
import com.azurlane.blyy.ui.components.adaptiveGlassSurface
import com.azurlane.blyy.ui.screens.SettingsScreen
import com.azurlane.blyy.ui.theme.LocalUiStyle
import com.azurlane.blyy.ui.theme.isCommandCenter
import com.azurlane.blyy.ui.theme.*
import com.azurlane.blyy.service.PlaybackService
import com.azurlane.blyy.ui.screens.GalleryScreen
import com.azurlane.blyy.ui.screens.GuessByImageScreen
import com.azurlane.blyy.ui.screens.GuessByVoiceScreen
import com.azurlane.blyy.ui.screens.GuessHistoryScreen
import com.azurlane.blyy.ui.screens.HomeScreen
import com.azurlane.blyy.ui.screens.ShipGalleryScreen
import com.azurlane.blyy.ui.screens.StudentGalleryScreen
import com.azurlane.blyy.ui.screens.VoiceScreen
import com.azurlane.blyy.ui.screens.AboutScreen
import com.azurlane.blyy.ui.screens.AssistantScreen
import com.azurlane.blyy.ui.screens.Live2DScreen
import com.azurlane.blyy.ui.screens.SecretaryShipModeScreen
import com.azurlane.blyy.ui.screens.SecretaryShipPickFromGalleryScreen
import com.azurlane.blyy.ui.screens.SecretaryShipPickFromHomeScreen
import com.azurlane.blyy.ui.screens.SecretaryShipRandomScreen
import com.azurlane.blyy.ui.screens.AssistantConfigScreen
import com.azurlane.blyy.ui.screens.JiuxinConfigScreen
import com.azurlane.blyy.ui.screens.JiuxinChatScreen
import com.azurlane.blyy.ui.screens.LeaderboardScreen
import com.azurlane.blyy.ui.components.SecretaryChibiOverlay
import com.azurlane.blyy.viewmodel.SecretaryShipIntent
import com.azurlane.blyy.viewmodel.SecretaryShipViewModel
import com.azurlane.blyy.viewmodel.GalleryViewModel
import com.azurlane.blyy.viewmodel.GuessShipViewModel
import com.azurlane.blyy.viewmodel.HomeViewModel
import com.azurlane.blyy.viewmodel.ShipGalleryViewModel
import com.azurlane.blyy.viewmodel.StudentGalleryViewModel
import com.azurlane.blyy.viewmodel.VoiceIntent
import com.azurlane.blyy.viewmodel.VoiceViewModel
import com.azurlane.blyy.viewmodel.ArchiveType
import com.azurlane.blyy.viewmodel.UpdateCheckViewModel
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import com.azurlane.blyy.utils.OverlayPermissionHelper
import com.azurlane.blyy.SecretaryOverlayService
import com.azurlane.blyy.util.AppUpdateChecker
import com.azurlane.blyy.util.UpdateInfo
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.GlobalScope
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Home : Screen("home", "后宅", Icons.Default.Home)
    object Gallery : Screen("gallery", "船坞", Icons.AutoMirrored.Filled.List)
    object About : Screen("about", "关于", Icons.Default.Info)
}

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

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var playerSettings: PlayerSettingsDataStore

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    companion object {
        // 用于跨组件通信的悬浮窗状态
        private val _overlayState = MutableStateFlow(SecretaryOverlayService.isServiceRunning())
        val overlayState = _overlayState.asStateFlow()
        
        fun updateOverlayState(isRunning: Boolean) {
            _overlayState.value = isRunning
        }
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        
        hideSystemBars()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val intent = Intent(this, PlaybackService::class.java)
        startService(intent)

        setContent {
            val uiStyle by playerSettings.uiStyle.collectAsStateWithLifecycle(
                initialValue = UiStyle.COMMAND_CENTER
            )
            val uiStyleReady by produceState(false) {
                playerSettings.uiStyle.collect {
                    value = true
                }
            }
            val forceDarkTheme by playerSettings.forceDarkTheme.collectAsStateWithLifecycle(
                initialValue = false
            )
            val dynamicColorEnabled by playerSettings.dynamicColorEnabled.collectAsStateWithLifecycle(
                initialValue = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            )
            val systemDark = isSystemInDarkTheme()
            BlyyTheme(
                darkTheme = if (forceDarkTheme) true else systemDark,
                uiStyle = uiStyle,
                dynamicColor = dynamicColorEnabled
            ) {
                KeepSystemBarsHidden()
                // 等待UI样式加载完成后再渲染，避免旧UI模式下新UI短暂闪现
                if (uiStyleReady) {
                    AppContent()
                }
            }
        }
    }
    
    private fun hideSystemBars() {
        val window = this.window
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.hide(WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /**
     * 请求悬浮窗权限并显示提示
     */
    private fun requestOverlayPermission() {
        if (!OverlayPermissionHelper.hasOverlayPermission(this)) {
            Toast.makeText(
                this,
                "需要悬浮窗权限才能在其他应用上层显示秘书舰",
                Toast.LENGTH_LONG
            ).show()
            OverlayPermissionHelper.requestOverlayPermission(this)
        }
    }

    /**
     * 启动系统悬浮窗服务
     */
    fun startOverlayService() {
        Log.d("SecretaryOverlay", "startOverlayService: 开始启动悬浮窗服务")
        
        if (OverlayPermissionHelper.hasOverlayPermission(this)) {
            Log.d("SecretaryOverlay", "startOverlayService: 权限已授予，启动服务")
            
            // 保存状态
            GlobalScope.launch {
                playerSettings.setSecretaryOverlayEnabled(true)
            }
            
            val intent = Intent(this, SecretaryOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            
            // 显示成功提示
            Toast.makeText(this, "悬浮窗已开启", Toast.LENGTH_SHORT).show()
        } else {
            Log.d("SecretaryOverlay", "startOverlayService: 权限未授予，请求权限")
            Toast.makeText(this, "需要悬浮窗权限才能显示", Toast.LENGTH_SHORT).show()
            requestOverlayPermission()
        }
    }

    /**
     * 停止系统悬浮窗服务
     */
    fun stopOverlayService() {
        // 保存状态
        GlobalScope.launch {
            playerSettings.setSecretaryOverlayEnabled(false)
        }
        
        val intent = Intent(this, SecretaryOverlayService::class.java)
        stopService(intent)
        
        Toast.makeText(this, "悬浮窗已关闭", Toast.LENGTH_SHORT).show()
    }

    /**
     * 切换悬浮窗显示状态
     */
    fun toggleOverlayService() {
        if (OverlayPermissionHelper.hasOverlayPermission(this)) {
            val isCurrentlyRunning = SecretaryOverlayService.isServiceRunning()
            
            if (isCurrentlyRunning) {
                stopOverlayService()
            } else {
                startOverlayService()
            }
        } else {
            Toast.makeText(this, "需要悬浮窗权限才能显示", Toast.LENGTH_SHORT).show()
            requestOverlayPermission()
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
@UnstableApi
fun AppContent() {
    val navController = rememberNavController()
    val screens = listOf(Screen.Home, Screen.Gallery)
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
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
            currentDestination?.route != "live2d" &&
            currentDestination?.route != "assistant" &&
            currentDestination?.route != "assistant_config" &&
            currentDestination?.route != "jiuxin_config" &&
            currentDestination?.route != "jiuxin_chat"

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
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "bottomBarOffset"
    )
    val bottomBarAlpha by animateFloatAsState(
        targetValue = if (showBottomBar && isBottomBarVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "bottomBarAlpha"
    )

    SharedTransitionLayout {
        // ── 启动时自动检测更新 ──
        val updateChecker: AppUpdateChecker = hiltViewModel<UpdateCheckViewModel>().updateChecker
        var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }

        LaunchedEffect(Unit) {
            // 延迟1秒执行，避免影响启动性能
            kotlinx.coroutines.delay(1000L)
            updateInfo = updateChecker.checkForUpdate()
        }

        if (updateInfo != null) {
            // 先捕获当前值，避免回调中 updateInfo 已被置空导致 NPE
            val currentUpdateInfo = updateInfo!!
            UpdateAvailableDialog(
                updateInfo = currentUpdateInfo,
                onUpdate = {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUpdateInfo.downloadUrl))
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Failed to open update URL", e)
                    }
                    updateInfo = null
                },
                onDismiss = {
                    scope.launch {
                        try {
                            updateChecker.skipVersion(currentUpdateInfo.versionName)
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Failed to skip version", e)
                        }
                    }
                    updateInfo = null
                }
            )
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModernDrawerSheet(
                    currentRoute = currentDestination?.route,
                    onNavigate = { route ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        // 先关闭抽屉，等待关闭动画完成后再导航，避免菜单与页面切换动画冲突
                        scope.launch {
                            drawerState.close()
                            navController.navigate(route) { launchSingleTop = true }
                        }
                    },
                    onClose = { 
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
                        slideInHorizontally(
                            initialOffsetX = { it / 3 },
                            animationSpec = spring(
                                dampingRatio = 0.85f,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        ) + fadeIn(
                            tween(
                                durationMillis = 300,
                                delayMillis = 50,
                                easing = AppAnimation.Easings.EmphasizedDecelerate
                            )
                        )
                    },
                    exitTransition = {
                        slideOutHorizontally(
                            targetOffsetX = { -it / 5 },
                            animationSpec = tween(
                                durationMillis = 300,
                                easing = AppAnimation.Easings.EmphasizedAccelerate
                            )
                        ) + fadeOut(
                            tween(
                                durationMillis = 200,
                                easing = AppAnimation.Easings.EmphasizedAccelerate
                            )
                        )
                    },
                    popEnterTransition = {
                        slideInHorizontally(
                            initialOffsetX = { -it / 5 },
                            animationSpec = spring(
                                dampingRatio = 0.85f,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        ) + fadeIn(
                            tween(
                                durationMillis = 300,
                                delayMillis = 50,
                                easing = AppAnimation.Easings.EmphasizedDecelerate
                            )
                        )
                    },
                    popExitTransition = {
                        slideOutHorizontally(
                            targetOffsetX = { it / 3 },
                            animationSpec = tween(
                                durationMillis = 300,
                                easing = AppAnimation.Easings.EmphasizedAccelerate
                            )
                        ) + fadeOut(
                            tween(
                                durationMillis = 200,
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
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                navController.navigate(Screen.Gallery.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            onShowGallery = { ship -> navController.navigate("gallery/${ship.name}?avatarUrl=${Uri.encode(ship.avatarUrl)}") },
                            onOpenMenu = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
                            onNavigateToJiuxinConfig = { navController.navigate("jiuxin_config") }
                        )
                    }
                    composable("live2d") {
                        Live2DScreen(
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
                    composable("jiuxin_chat") {
                        JiuxinChatScreen(
                            onBack = { navController.popBackStack() },
                            onNavigateToConfig = { navController.navigate("jiuxin_config") }
                        )
                    }
                    composable("secretary_mode") {
                        // 使用 collectAsState 实现响应式状态更新
                        val overlayEnabled by MainActivity.overlayState.collectAsState()
                        
                        SecretaryShipModeScreen(
                            secretaryState = secretaryState,
                            onBack = { navController.popBackStack() },
                            onRandomFlip = { navController.navigate("secretary_random") },
                            onSelectFromHome = { navController.navigate("secretary_pick_home") },
                            onSelectFromGallery = { navController.navigate("secretary_pick_gallery") },
                            onSetAutoPlay = { enabled, interval ->
                                secretaryViewModel.onIntent(SecretaryShipIntent.SetAutoPlay(enabled, interval))
                            },
                            onClearSecretary = {
                                secretaryViewModel.onIntent(SecretaryShipIntent.ClearSecretary)
                            },
                            onToggleOverlay = { enabled ->
                                val activity = context as? MainActivity
                                if (enabled) {
                                    activity?.startOverlayService()
                                } else {
                                    activity?.stopOverlayService()
                                }
                            },
                            isOverlayEnabled = overlayEnabled
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
                val overlayEnabledForChibi by MainActivity.overlayState.collectAsState()
                if (secretaryState.figureUrl.isNotEmpty() && !overlayEnabledForChibi) {
                    SecretaryChibiOverlay(
                        figureUrl = secretaryState.figureUrl,
                        shipName = secretaryState.shipName,
                        dialogue = null,
                        modifier = Modifier.fillMaxSize(),
                        onTap = {
                            secretaryViewModel.ensureVoicesLoaded(secretaryState.shipName)
                            secretaryViewModel.onIntent(SecretaryShipIntent.PlayRandomVoice)
                        }
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
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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

@Composable
private fun KeepSystemBarsHidden() {
    val view = LocalView.current
    
    DisposableEffect(view) {
        val window = (view.context as? ComponentActivity)?.window
        if (window != null) {
            val controller = WindowInsetsControllerCompat(window, view)
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.hide(WindowInsetsCompat.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        
        onDispose {
            // 当 Composable 销毁时恢复系统栏显示
            val currentWindow = (view.context as? ComponentActivity)?.window
            if (currentWindow != null) {
                val controller = WindowInsetsControllerCompat(currentWindow, view)
                controller.show(WindowInsetsCompat.Type.statusBars())
                controller.show(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }
}

@Composable
private fun ModernNavigationBar(
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
                .padding(top = if (isWatch) 4.dp else AppSpacing.Sm, bottom = if (isWatch) 4.dp else AppSpacing.Sm)
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

    val iconScale by animateFloatAsState(
        targetValue = if (isSelected) 1.15f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "IconScale"
    )

    val indicatorAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "IndicatorAlpha"
    )

    Surface(
        onClick = onClick,
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
                                            accentColor.copy(alpha = 0.2f * indicatorAlpha),
                                            Color.Transparent
                                        )
                                    ),
                                    shape = CircleShape
                                )
                                .border(
                                    width = 1.dp,
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            accentColor.copy(alpha = 0.5f * indicatorAlpha),
                                            AppColors.Accent.Gold.copy(alpha = 0.2f * indicatorAlpha)
                                        )
                                    ),
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

            if (isSelected) {
                Spacer(modifier = Modifier.height(if (isWatch) 1.dp else AppSpacing.Xxs))

                Text(
                    text = displayLabel,
                    style = if (isWatch) AppTypography.NavigationLabel.copy(fontSize = 9.sp) else AppTypography.NavigationLabel,
                    color = accentColor,
                    modifier = Modifier.padding(horizontal = AppSpacing.Sm)
                )

                Spacer(modifier = Modifier.height(2.dp))

                // 选中指示点
                Box(
                    modifier = Modifier
                        .padding(horizontal = AppSpacing.Sm)
                        .height(3.dp)
                        .clip(RoundedCornerShape(AppSpacing.Corner.Xxs))
                        .background(
                            if (isCommandCenter) {
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        accentColor,
                                        AppColors.Accent.Gold.copy(alpha = 0.6f)
                                    )
                                )
                            } else {
                                Brush.horizontalGradient(
                                    colors = listOf(accentColor, accentColor)
                                )
                            }
                        )
                )
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
private fun ModernDrawerSheet(
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
            label = "查看Live2D",
            icon = Icons.Rounded.ViewInAr,
            description = "浏览Live2D模型库",
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
            route = "jiuxin_chat",
            label = "啾信",
            icon = Icons.Rounded.SmartToy,
            description = "与AI舰娘对话",
            color = MaterialTheme.colorScheme.primary,
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
                            .size(36.dp)
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
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.02f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "itemScale"
    )
    val isWatch = isWatchScreen()
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .padding(bottom = if (isLastInGroup) AppSpacing.Xs else 0.dp),
        shape = if (isCommandCenter) BlyyShapes.PanelSmall else RoundedCornerShape(AppSpacing.Corner.Lg),
        color = if (isSelected) {
            item.color.copy(alpha = 0.12f)
        } else {
            Color.Transparent
        },
        shadowElevation = if (isSelected) 4.dp else 0.dp
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
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            item.color.copy(alpha = if (isSelected) 0.2f else 0.1f),
                                            item.color.copy(alpha = 0.02f)
                                        )
                                    ),
                                    shape = BlyyShapes.Button
                                )
                                .border(
                                    width = AppSpacing.Border.Thin,
                                    color = item.color.copy(alpha = if (isSelected) 0.4f else 0.15f),
                                    shape = BlyyShapes.Button
                                )
                        } else {
                            Modifier
                                .background(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            item.color.copy(alpha = if (isSelected) 0.25f else 0.15f),
                                            Color.Transparent
                                        )
                                    ),
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

            // 选中指示器 — 渐变竖条
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
 * 显示新版本号、更新内容摘要，以及"立即更新"和"稍后提醒"两个操作按钮。
 */
@Composable
private fun UpdateAvailableDialog(
    updateInfo: UpdateInfo,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit
) {
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val isWatch = isWatchScreen()
    val accentColor = if (isCommandCenter) AppColors.Accent.Cyan else MaterialTheme.colorScheme.primary

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(if (isWatch) 36.dp else 48.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accentColor.copy(alpha = 0.2f),
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
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        title = {
            Text(
                text = "发现新版本",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = BlyyShapes.PanelSmall,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "v${updateInfo.versionName}",
                            style = AppTypography.TitleMedium,
                            fontWeight = FontWeight.Bold,
                            color = accentColor
                        )
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
                                        lineHeight = 20.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Surface(
                onClick = onUpdate,
                shape = RoundedCornerShape(AppSpacing.Corner.Sm),
                color = accentColor
            ) {
                Text(
                    text = "立即更新",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    style = AppTypography.LabelMedium,
                    color = Color.White
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后提醒")
            }
        },
        shape = if (isWatch) RoundedCornerShape(AppSpacing.Corner.Lg) else RoundedCornerShape(AppSpacing.Corner.Xxl)
    )
}
