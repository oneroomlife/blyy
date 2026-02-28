package com.example.blyy.ui.screens

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.blyy.data.model.Ship
import com.example.blyy.ui.components.ShipCard
import com.example.blyy.ui.components.ShipCardShimmer
import com.example.blyy.ui.theme.*
import com.example.blyy.viewmodel.HomeIntent
import com.example.blyy.viewmodel.HomeViewState
import org.intellij.lang.annotations.Language

@Language("AGSL")
const val FLUID_SHADER = """
    uniform float2 iResolution;
    uniform float iTime;

    half4 main(in float2 fragCoord) {
        float2 uv = fragCoord / iResolution.xy;
        float3 color = 0.5 + 0.5 * cos(iTime * 0.5 + uv.xyx + float3(0, 2, 4));
        return half4(color * 0.08 + 0.04, 0.05); 
    }
"""

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun HomeScreen(
    state: HomeViewState,
    onIntent: (HomeIntent) -> Unit,
    onShipClick: (Ship) -> Unit,
    onNavigateToGallery: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val shader = remember { RuntimeShader(FLUID_SHADER) }
            val time by produceState(0f) {
                while (true) {
                    value = System.currentTimeMillis() / 1000f
                    withFrameNanos { }
                }
            }
            
            Canvas(modifier = Modifier.fillMaxSize()) {
                shader.setFloatUniform("iResolution", size.width, size.height)
                shader.setFloatUniform("iTime", time)
                drawRect(brush = ShaderBrush(shader))
            }
        }
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainer
                )
        )

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            "我的后宅",
                            style = AppTypography.TitleLarge
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    ),
                    windowInsets = WindowInsets(0.dp)
                )
            },
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp)
        ) { innerPadding ->
            PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = { onIntent(HomeIntent.Refresh) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (state.isLoading && state.favoriteShips.isEmpty()) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = AppSpacing.Card.MinWidth),
                            contentPadding = PaddingValues(AppSpacing.Screen.Horizontal),
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Gap.CardGrid),
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.Gap.CardGrid),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(10) { 
                                ShipCardShimmer()
                            }
                        }
                    } else if (state.favoriteShips.isEmpty()) {
                        EmptyFavoritesView(onNavigateToGallery = onNavigateToGallery)
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = AppSpacing.Card.MinWidth),
                            contentPadding = PaddingValues(AppSpacing.Screen.Horizontal),
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Gap.CardGrid),
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.Gap.CardGrid),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(items = state.favoriteShips, key = { it.name }) { ship ->
                                ShipCard(
                                    ship = ship,
                                    onClick = { onShipClick(ship) },
                                    onLongClick = { onIntent(HomeIntent.ToggleFavorite(ship)) },
                                    modifier = with(sharedTransitionScope) {
                                        Modifier.sharedElement(
                                            sharedContentState = rememberSharedContentState(key = "avatar-${ship.name}"),
                                            animatedVisibilityScope = animatedContentScope
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyFavoritesView(onNavigateToGallery: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "Floating")
    
    val offsetY by infiniteTransition.animateFloat(
        initialValue = -12f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(3500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "FloatingOffset"
    )
    
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )
    
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GlowAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
                        Color.Transparent
                    ),
                    radius = 1.5f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(AppSpacing.Xxxl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .offset(y = offsetY.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .scale(pulseScale)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha),
                                    MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha * 0.3f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(88.dp)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(72.dp),
                    shape = RoundedCornerShape(50.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    border = BorderStroke(
                        width = AppSpacing.Border.Normal,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            )
                        )
                    ),
                    shadowElevation = AppSpacing.Elevation.Lg
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(AppSpacing.Xxl))
            
            Text(
                text = "后宅空荡荡的...",
                style = AppTypography.EmptyTitle,
                color = MaterialTheme.colorScheme.onBackground
            )
            
            Spacer(modifier = Modifier.height(AppSpacing.Md))
            
            Text(
                text = "在船坞中长按舰娘头像可以与其誓约，\n构建属于你的专属后宅。",
                style = AppTypography.EmptyDescription,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(AppSpacing.Xxxl))
            
            ModernButton(
                text = "去挑选舰娘",
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                onClick = onNavigateToGallery
            )
        }
    }
}

@Composable
private fun ModernButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ButtonGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GlowAlpha"
    )

    Box(
        modifier = Modifier
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha),
                        Color.Transparent
                    )
                ),
                shape = RoundedCornerShape(AppSpacing.Corner.Xl)
            )
    ) {
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(AppSpacing.Corner.Xl),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            ),
            contentPadding = PaddingValues(
                horizontal = AppSpacing.Padding.ButtonHorizontal + AppSpacing.Sm,
                vertical = AppSpacing.Padding.ButtonVertical + AppSpacing.Xs
            ),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = AppSpacing.Elevation.Lg,
                pressedElevation = AppSpacing.Elevation.Md
            )
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(AppSpacing.Icon.Md)
            )
            Spacer(modifier = Modifier.width(AppSpacing.Sm))
            Text(
                text = text,
                style = AppTypography.ButtonText
            )
        }
    }
}
