package com.example.blyy

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.collectAsState
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.example.blyy.ui.theme.*
import com.example.blyy.service.PlaybackService
import com.example.blyy.ui.screens.GalleryScreen
import com.example.blyy.ui.screens.GuessByImageScreen
import com.example.blyy.ui.screens.GuessByVoiceScreen
import com.example.blyy.ui.screens.HomeScreen
import com.example.blyy.ui.screens.ShipGalleryScreen
import com.example.blyy.ui.screens.VoiceScreen
import com.example.blyy.ui.screens.AboutScreen
import com.example.blyy.ui.screens.SecretaryShipModeScreen
import com.example.blyy.ui.screens.SecretaryShipPickFromGalleryScreen
import com.example.blyy.ui.screens.SecretaryShipPickFromHomeScreen
import com.example.blyy.ui.screens.SecretaryShipRandomScreen
import com.example.blyy.ui.components.SecretaryChibiOverlay
import com.example.blyy.viewmodel.SecretaryShipIntent
import com.example.blyy.viewmodel.SecretaryShipViewModel
import com.example.blyy.viewmodel.GalleryViewModel
import com.example.blyy.viewmodel.GuessShipViewModel
import com.example.blyy.viewmodel.HomeViewModel
import com.example.blyy.viewmodel.ShipGalleryViewModel
import com.example.blyy.viewmodel.VoiceIntent
import com.example.blyy.viewmodel.VoiceViewModel
import com.example.blyy.data.local.PlayerSettingsDataStore
import com.example.blyy.utils.OverlayPermissionHelper
import com.example.blyy.SecretaryOverlayService
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Home : Screen("home", "后宅", Icons.Default.Home)
    object Gallery : Screen("gallery", "船坞", Icons.AutoMirrored.Filled.List)
    object About : Screen("about", "关于", Icons.Default.Info)
}

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
            BlyyTheme {
                KeepSystemBarsHidden()
                AppContent()
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
        android.util.Log.d("SecretaryOverlay", "startOverlayService: 开始启动悬浮窗服务")
        
        if (OverlayPermissionHelper.hasOverlayPermission(this)) {
            android.util.Log.d("SecretaryOverlay", "startOverlayService: 权限已授予，启动服务")
            
            // 保存状态
            kotlinx.coroutines.GlobalScope.launch {
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
            android.util.Log.d("SecretaryOverlay", "startOverlayService: 权限未授予，请求权限")
            Toast.makeText(this, "需要悬浮窗权限才能显示", Toast.LENGTH_SHORT).show()
            requestOverlayPermission()
        }
    }

    /**
     * 停止系统悬浮窗服务
     */
    fun stopOverlayService() {
        // 保存状态
        kotlinx.coroutines.GlobalScope.launch {
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
            currentDestination?.route?.startsWith("guess_image") != true &&
            currentDestination?.route?.startsWith("guess_voice") != true &&
            currentDestination?.route != Screen.About.route &&
            currentDestination?.route?.startsWith("secretary") != true

    val drawerState = remember { androidx.compose.material3.DrawerState(initialValue = androidx.compose.material3.DrawerValue.Closed) }
    
    var isBottomBarVisible by remember { mutableStateOf(true) }

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
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModernDrawerSheet(
                    currentRoute = currentDestination?.route,
                    onNavigate = { route ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        scope.launch { drawerState.close() }
                        navController.navigate(route) { launchSingleTop = true }
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
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable(Screen.Home.route) {
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
                    composable(Screen.Gallery.route) {
                        val viewModel: GalleryViewModel = hiltViewModel()
                        val state by viewModel.state.collectAsStateWithLifecycle()
                        val filteredShips by viewModel.filteredShips.collectAsStateWithLifecycle()
                        GalleryScreen(
                            state = state,
                            filteredShips = filteredShips,
                            onIntent = viewModel::onIntent,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedContentScope = this,
                            onShipClick = { ship -> navController.navigate("voice/${ship.name}?avatarUrl=${Uri.encode(ship.avatarUrl)}") },
                            onShowGallery = { ship -> navController.navigate("gallery/${ship.name}?avatarUrl=${Uri.encode(ship.avatarUrl)}") },
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
                    composable("guess_image") {
                        val viewModel: GuessShipViewModel = hiltViewModel()
                        GuessByImageScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("guess_voice") {
                        val viewModel: GuessShipViewModel = hiltViewModel()
                        GuessByVoiceScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(Screen.About.route) {
                        AboutScreen(
                            onBack = { navController.popBackStack() }
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
    currentDestination: androidx.navigation.NavDestination?,
    onNavigate: (String) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    
    val glassSurface = if (isDark) AppColors.GlassSurfaceDark else AppColors.GlassSurfaceLight
    val glassBorder = if (isDark) AppColors.GlassBorderDark else AppColors.GlassBorderLight
    
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
                .padding(top = AppSpacing.Sm, bottom = AppSpacing.Sm)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Lg)
                    .height(72.dp)
                    .clip(RoundedCornerShape(AppSpacing.Corner.Xl))
                    .background(
                        glassSurface.copy(alpha = 0.9f)
                    )
                    .border(
                        width = AppSpacing.Border.Thin,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                glassBorder,
                                glassBorder.copy(alpha = 0.3f)
                            )
                        ),
                        shape = RoundedCornerShape(AppSpacing.Corner.Xl)
                    ),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                screens.forEach { screen ->
                    val isSelected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    
                    ModernNavigationItem(
                        screen = screen,
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
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val iconScale by animateFloatAsState(
        targetValue = if (isSelected) 1.1f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "IconScale"
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
                    .size(40.dp)
                    .then(
                        if (isSelected) {
                            Modifier
                                .background(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                            Color.Transparent
                                        )
                                    ),
                                    shape = CircleShape
                                )
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = screen.icon,
                    contentDescription = screen.label,
                    modifier = Modifier
                        .size(24.dp)
                        .scale(iconScale),
                    tint = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    }
                )
            }
            
            if (isSelected) {
                Spacer(modifier = Modifier.height(AppSpacing.Xxs))
                
                Text(
                    text = screen.label,
                    style = AppTypography.NavigationLabel,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = AppSpacing.Sm)
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
    val color: Color
)

@Composable
private fun ModernDrawerSheet(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    onClose: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val glassSurface = if (isDark) AppColors.GlassSurfaceDark else AppColors.GlassSurfaceLight
    val glassBorder = if (isDark) AppColors.GlassBorderDark else AppColors.GlassBorderLight

    val menuItems = listOf(
        DrawerMenuItem(
            route = "secretary_mode",
            label = "今日秘书舰",
            icon = Icons.Rounded.Person,
            description = "设置常驻秘书舰，点击播放语音",
            color = MaterialTheme.colorScheme.primary
        ),
        DrawerMenuItem(
            route = "guess_image",
            label = "看图识舰娘",
            icon = Icons.Rounded.Image,
            description = "通过图片辨认舰娘",
            color = MaterialTheme.colorScheme.primary
        ),
        DrawerMenuItem(
            route = "guess_voice",
            label = "听音识舰娘",
            icon = Icons.Rounded.MusicNote,
            description = "通过语音辨认舰娘",
            color = MaterialTheme.colorScheme.secondary
        ),
        DrawerMenuItem(
            route = Screen.About.route,
            label = "关于",
            icon = Icons.Rounded.Star,
            description = "了解更多信息",
            color = MaterialTheme.colorScheme.tertiary
        )
    )

    ModalDrawerSheet(
        drawerState = remember { androidx.compose.material3.DrawerState(androidx.compose.material3.DrawerValue.Closed) },
        modifier = Modifier
            .fillMaxWidth(0.85f)
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
                    .padding(AppSpacing.Lg)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = AppSpacing.Xl),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                            Color.Transparent
                                        )
                                    ),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Menu,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "玩法菜单",
                                style = AppTypography.TitleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "选择游戏模式",
                                style = AppTypography.BodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "关闭菜单",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
                ) {
                    menuItems.forEach { item ->
                        ModernDrawerItem(
                            item = item,
                            isSelected = currentRoute == item.route,
                            onClick = { onNavigate(item.route) }
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(AppSpacing.Corner.Lg),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ) {
                    Row(
                        modifier = Modifier.padding(AppSpacing.Md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "完成挑战获取积分",
                            style = AppTypography.LabelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.02f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "itemScale"
    )

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale),
        shape = RoundedCornerShape(AppSpacing.Corner.Lg),
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
                .padding(AppSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                item.color.copy(alpha = if (isSelected) 0.25f else 0.15f),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = item.color,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(item.color, CircleShape)
                )
            }
        }
    }
}
