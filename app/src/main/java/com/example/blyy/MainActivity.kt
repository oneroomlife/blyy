package com.example.blyy

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.example.blyy.ui.screens.HomeScreen
import com.example.blyy.ui.screens.VoiceScreen
import com.example.blyy.viewmodel.GalleryViewModel
import com.example.blyy.viewmodel.HomeViewModel
import com.example.blyy.viewmodel.VoiceIntent
import com.example.blyy.viewmodel.VoiceViewModel
import dagger.hilt.android.AndroidEntryPoint

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Home : Screen("home", "后宅", Icons.Default.Home)
    object Gallery : Screen("gallery", "船坞", Icons.AutoMirrored.Filled.List)
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    @OptIn(ExperimentalSharedTransitionApi::class)
    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val intent = Intent(this, PlaybackService::class.java)
        startService(intent)

        setContent {
            BlyyTheme {
                AppContent()
            }
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
    
    // 判断是否显示底部导航栏（语音界面隐藏）
    val showBottomBar = currentDestination?.route?.startsWith("voice/") != true

    SharedTransitionLayout {
        Scaffold(
            bottomBar = {
                AnimatedVisibility(
                    visible = showBottomBar,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut()
                ) {
                    ModernNavigationBar(
                        screens = screens,
                        currentDestination = currentDestination,
                        onNavigate = { route ->
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                modifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
            ) {
                composable(Screen.Home.route) { 
                    val viewModel: HomeViewModel = hiltViewModel()
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    HomeScreen(
                        state = state,
                        onIntent = viewModel::onIntent,
                        onShipClick = { ship -> navController.navigate("voice/${ship.name}?avatarUrl=${Uri.encode(ship.avatarUrl)}") },
                        onNavigateToGallery = { 
                            navController.navigate(Screen.Gallery.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
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
                        onShipClick = { ship -> navController.navigate("voice/${ship.name}?avatarUrl=${Uri.encode(ship.avatarUrl)}") }
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
