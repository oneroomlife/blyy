package com.azurlane.blyy.ui.screens

import android.content.Intent
import android.graphics.RuntimeShader
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.ui.components.ShipCard
import com.azurlane.blyy.ui.components.ShipCardShimmer
import com.azurlane.blyy.ui.theme.*
import com.azurlane.blyy.viewmodel.HomeIntent
import com.azurlane.blyy.viewmodel.HomeViewState
import kotlinx.coroutines.delay
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

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun HomeScreen(
    state: HomeViewState,
    onIntent: (HomeIntent) -> Unit,
    onShipClick: (Ship) -> Unit,
    onNavigateToGallery: () -> Unit,
    onShowGallery: (Ship) -> Unit,
    onOpenMenu: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope
) {
    val context = LocalContext.current
    fun openWiki(ship: Ship) {
        val processedName = ship.name
            .replace(".改", "")
            .replace("改", "")
            .replace("Kai", "")

        val wikiNameMapping = mapOf(
            "DEAD" to "DEAD_MASTER",
            "BLACK★ROCK" to "BLACK★ROCK_SHOOTER"
        )

        val wikiName = wikiNameMapping[processedName] ?: processedName
        val url = "https://wiki.biligame.com/blhx/${java.net.URLEncoder.encode(wikiName, "UTF-8")}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    }

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
                    navigationIcon = {
                        IconButton(onClick = onOpenMenu) {
                            Icon(
                                imageVector = Icons.Filled.Menu,
                                contentDescription = "菜单"
                            )
                        }
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
                        // 调用优化后的空状态视图
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
                                    onLongClick = {
                                        onIntent(HomeIntent.ToggleFavorite(ship))
                                        Toast.makeText(
                                            context,
                                            if (ship.isFavorite) "已解除与${ship.name}的誓约" else "已与${ship.name}誓约",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    onWikiClick = { openWiki(ship) },
                                    onOathClick = {
                                        onIntent(HomeIntent.ToggleFavorite(ship))
                                        Toast.makeText(
                                            context,
                                            if (ship.isFavorite) "已解除与${ship.name}的誓约" else "已与${ship.name}誓约",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    onGalleryClick = { onShowGallery(ship) },
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
    // 控制错落式入场动画的状态
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(100) // 轻微延迟，等待页面切换完成避免卡顿
        isVisible = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                // 模拟深邃的空间感背景
                brush = Brush.radialGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
                        Color.Transparent
                    ),
                    radius = 1200f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Xxl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. 悬浮艺术装置（图标区）
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(800)) + slideInVertically(tween(800, easing = AppAnimation.Easings.Decelerate)) { 100 }
            ) {
                PremiumFloatingArtwork()
            }

            Spacer(modifier = Modifier.height(AppSpacing.Xxxl))

            // 2. 标题（带渐变金属质感）
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(800, delayMillis = 150)) + slideInVertically(tween(800, delayMillis = 150, easing = AppAnimation.Easings.Decelerate)) { 50 }
            ) {
                Text(
                    text = "后宅空荡荡的...",
                    style = AppTypography.EmptyTitle.copy(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.onBackground,
                                MaterialTheme.colorScheme.primary
                            )
                        )
                    ),
                    modifier = Modifier.padding(bottom = AppSpacing.Md)
                )
            }

            // 3. 描述文本
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(800, delayMillis = 300)) + slideInVertically(tween(800, delayMillis = 300, easing = AppAnimation.Easings.Decelerate)) { 50 }
            ) {
                Text(
                    text = "在船坞中长按舰娘头像可以与其誓约\n构建属于你们的专属避风港",
                    style = AppTypography.EmptyDescription,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center,
                    lineHeight = AppTypography.EmptyDescription.fontSize * 1.5f // 增加行高提升呼吸感
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            // 4. 高级交互按钮
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(800, delayMillis = 450)) + slideInVertically(tween(800, delayMillis = 450, easing = AppAnimation.Easings.Decelerate)) { 50 }
            ) {
                PremiumInteractiveButton(
                    text = "去挑选舰娘",
                    onClick = onNavigateToGallery
                )
            }
        }
    }
}

@Composable
private fun PremiumFloatingArtwork() {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val infiniteTransition = rememberInfiniteTransition(label = "ArtworkAnim")

    // Y轴悬浮
    val offsetY by infiniteTransition.animateFloat(
        initialValue = -15f,
        targetValue = 15f,
        animationSpec = infiniteRepeatable(
            animation = tween(3500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "FloatingOffset"
    )

    // 外环缓慢旋转（体现机械/罗盘感）
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RingRotation"
    )

    // 核心呼吸光晕
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "CoreGlow"
    )

    Box(
        modifier = Modifier
            .size(160.dp)
            .offset(y = offsetY.dp),
        contentAlignment = Alignment.Center
    ) {
        // 最底层大光晕
        Box(
            modifier = Modifier
                .fillMaxSize()
                .scale(1.2f)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = glowAlpha),
                            Color.Transparent
                        )
                    )
                )
        )

        // 旋转的科技感外环
        Box(
            modifier = Modifier
                .size(110.dp)
                .rotate(rotation)
                .border(
                    width = 1.dp,
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Transparent,
                            primaryColor.copy(alpha = 0.8f),
                            Color.Transparent,
                            secondaryColor.copy(alpha = 0.5f)
                        )
                    ),
                    shape = CircleShape
                )
        )

        // 中心玻璃态主体
        Box(
            modifier = Modifier
                .size(86.dp)
                .clip(CircleShape)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            surfaceColor.copy(alpha = 0.8f),
                            surfaceColor.copy(alpha = 0.4f)
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                    )
                )
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.5f), // 高光边缘
                            primaryColor.copy(alpha = 0.2f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            // 中心图标，带有微弱的渐变映射
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                modifier = Modifier
                    .size(38.dp)
                    .graphicsLayer {
                        shadowElevation = 8.dp.toPx()
                        shape = CircleShape
                        ambientShadowColor = primaryColor
                        spotShadowColor = primaryColor
                    },
                tint = primaryColor
            )
        }
    }
}

@Composable
fun PremiumInteractiveButton(
    text: String,
    onClick: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // 按压时的物理回弹缩放
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "ButtonScale"
    )

    // 流光特效动画
    val infiniteTransition = rememberInfiniteTransition(label = "ButtonShimmer")
    val shimmerTranslate by infiniteTransition.animateFloat(
        initialValue = -200f,
        targetValue = 600f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing, delayMillis = 1000),
            repeatMode = RepeatMode.Restart
        ),
        label = "Shimmer"
    )

    Box(
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(AppSpacing.Corner.Xl))
            // 按钮外部的发光阴影
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.4f),
                        Color.Transparent
                    ),
                    radius = 200f
                )
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null, // 移除原生涟漪，纯用缩放与透明度表现
                onClick = onClick
            )
    ) {
        Row(
            modifier = Modifier
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            primaryColor,
                            primaryColor.copy(alpha = 0.8f)
                        )
                    )
                )
                // 绘制内部流光 (Shimmer)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.25f),
                                Color.Transparent
                            ),
                            start = Offset(shimmerTranslate, 0f),
                            end = Offset(shimmerTranslate + 150f, size.height)
                        ),
                        blendMode = BlendMode.Screen
                    )
                }
                // 顶部高光描边 (模拟玻璃质感)
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.3f),
                            Color.Transparent
                        )
                    ),
                    shape = RoundedCornerShape(AppSpacing.Corner.Xl)
                )
                .padding(
                    horizontal = AppSpacing.Padding.ButtonHorizontal + 12.dp,
                    vertical = AppSpacing.Padding.ButtonVertical + 6.dp
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = text,
                style = AppTypography.ButtonText,
                color = onPrimaryColor
            )
            Spacer(modifier = Modifier.width(AppSpacing.Sm))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = onPrimaryColor
            )
        }
    }
}
