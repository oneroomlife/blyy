@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.components.ShipCard
import com.azurlane.blyy.ui.components.ShipCardShimmer
import com.azurlane.blyy.ui.components.adaptiveGlassSurface
import com.azurlane.blyy.ui.theme.*
import com.azurlane.blyy.viewmodel.HomeIntent
import com.azurlane.blyy.viewmodel.HomeViewState
import kotlinx.coroutines.delay
import org.intellij.lang.annotations.Language
import kotlin.math.sin

@Language("AGSL")
const val FLUID_SHADER = """
    uniform float2 iResolution;
    uniform float iTime;
    uniform float iOathIntensity;

    half4 main(in float2 fragCoord) {
        float2 uv = fragCoord / iResolution.xy;
        
        // 深海蓝绿基调流体
        float3 baseColor = 0.5 + 0.5 * cos(iTime * 0.3 + uv.xyx + float3(0, 2, 4));
        baseColor = baseColor * 0.08 + 0.04;
        
        // 誓约粉色光晕层 — 从底部中心向上扩散
        float2 oathCenter = float2(0.5, 1.2);
        float oathDist = length(uv - oathCenter);
        float3 oathColor = float3(1.0, 0.41, 0.71) * 0.12 * exp(-oathDist * 2.5);
        oathColor *= (0.7 + 0.3 * sin(iTime * 0.8));
        
        // 誓约金色光点层 — 右上角散射
        float2 goldCenter = float2(0.85, 0.15);
        float goldDist = length(uv - goldCenter);
        float3 goldColor = float3(1.0, 0.84, 0.0) * 0.06 * exp(-goldDist * 3.0);
        goldColor *= (0.6 + 0.4 * sin(iTime * 0.5 + 1.0));
        
        // 合成
        float3 finalColor = baseColor + oathColor * iOathIntensity + goldColor * iOathIntensity;
        return half4(finalColor, 0.06 + 0.04 * iOathIntensity);
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
    val uiStyle = LocalUiStyle.current
    val isCommandCenter = uiStyle.isCommandCenter()
    
    fun openWiki(ship: Ship) {
        val url = if (ship.archiveType == com.azurlane.blyy.viewmodel.ArchiveType.STUDENT.name) {
            // 学生档案（蔚蓝档案）：link 已存储 gamekee 学生详情页完整 URL
            ship.link.ifBlank { "https://www.gamekee.com/ba/" }
        } else {
            // 舰娘档案（碧蓝航线）：构建 biligame wiki URL
            val processedName = ship.name
                .replace(".改", "")
                .replace("改", "")
                .replace("Kai", "")

            val wikiNameMapping = mapOf(
                "DEAD" to "DEAD_MASTER",
                "BLACK★ROCK" to "BLACK★ROCK_SHOOTER"
            )

            val wikiName = wikiNameMapping[processedName] ?: processedName
            "https://wiki.biligame.com/blhx/${java.net.URLEncoder.encode(wikiName, "UTF-8")}"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        // ── 背景层 ──
        if (isCommandCenter) {
            // 指挥中心风格：AGSL 流体着色器 + 誓约氛围层
            val hasOathShips = state.favoriteShips.isNotEmpty()
            val oathIntensity = if (hasOathShips) 1f else 0f

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val shader = remember { RuntimeShader(FLUID_SHADER) }
                // Throttle to ~30fps to reduce CPU/GPU load while keeping fluid motion
                val time by produceState(0f) {
                    while (true) {
                        value = System.currentTimeMillis() / 1000f
                        delay(33)
                    }
                }
                Canvas(modifier = Modifier.fillMaxSize()) {
                    shader.setFloatUniform("iResolution", size.width, size.height)
                    shader.setFloatUniform("iTime", time)
                    shader.setFloatUniform("iOathIntensity", oathIntensity)
                    drawRect(brush = ShaderBrush(shader))
                }
            }

            // 誓约舰娘专属背景层：粉色光晕 + 漂浮粒子 + 渐变
            if (hasOathShips) {
                OathAmbientBackground()
            }
        } else {
            // 经典风格：誓约氛围渐变背景
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (state.favoriteShips.isNotEmpty()) {
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.surfaceContainer,
                                    AppColors.Favorite.Pink.copy(alpha = 0.04f),
                                    MaterialTheme.colorScheme.surfaceContainer
                                )
                            )
                        } else {
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.surfaceContainer,
                                    MaterialTheme.colorScheme.surfaceContainer
                                )
                            )
                        }
                    )
            )
        }

        AdaptiveHomeScreenTopBar(
            title = "我的后宅",
            onOpenMenu = onOpenMenu
        ) { innerPadding ->
            PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = { onIntent(HomeIntent.Refresh) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                val gridState = rememberLazyGridState()
                val allowDecorAnimation by remember {
                    derivedStateOf { !gridState.isScrollInProgress }
                }
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
                            modifier = Modifier.fillMaxSize(),
                            state = gridState
                        ) {
                            items(items = state.favoriteShips, key = { it.name }) { ship ->
                                ShipCard(
                                    ship = ship,
                                    decorativeAnimation = allowDecorAnimation,
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

/**
 * 自适应首页顶部栏 - 根据UI风格自动适配
 * 新UI: 科技风HUD面板设计，带有切角边框和渐变装饰
 * 旧UI: 经典Material Design顶栏，清晰简洁
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdaptiveHomeScreenTopBar(
    title: String,
    onOpenMenu: () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    val uiStyle = LocalUiStyle.current
    val isCommandCenter = uiStyle.isCommandCenter()
    
    Scaffold(
        topBar = {
            if (isCommandCenter) {
                BlyyTopBar(
                    title = title,
                    onMenuClick = onOpenMenu
                )
            } else {
                ClassicHomeTopBar(
                    title = title,
                    onMenuClick = onOpenMenu
                )
            }
        },
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0.dp)
    ) { innerPadding ->
        content(innerPadding)
    }
}

/**
 * 经典风格首页顶栏 - 简洁清晰，符合Material Design规范
 */
@Composable
private fun ClassicHomeTopBar(
    title: String,
    onMenuClick: () -> Unit
) {
    val glassSurface = adaptiveGlassSurface()
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .statusBarsPadding()
            .padding(horizontal = AppSpacing.Screen.Horizontal, vertical = AppSpacing.Md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 菜单按钮 - 带渐变边框的圆形背景
        IconButton(onClick = onMenuClick) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(glassSurface.copy(alpha = 0.6f))
                    .border(
                        width = AppSpacing.Border.Thin,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                            )
                        ),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = "菜单",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.Center)
                )
            }
        }
        
        Spacer(modifier = Modifier.width(AppSpacing.Lg))
        
        // 标题
        Text(
            text = title,
            style = AppTypography.TitleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * 誓约舰娘专属氛围背景
 * 三层结构：粉色光晕底层 + 漂浮粒子中层 + 渐变纹理顶层
 */
@Composable
private fun OathAmbientBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "OathAmbient")

    // 光晕呼吸动画
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "OathGlow"
    )

    // 次级光晕呼吸（相位偏移）
    val secondaryGlowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.03f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(3500, easing = FastOutSlowInEasing, delayMillis = 1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "OathGlow2"
    )

    // 金色光晕呼吸
    val goldGlowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.02f,
        targetValue = 0.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(5000, easing = FastOutSlowInEasing, delayMillis = 800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GoldGlow"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // ── 第1层：底部粉色径向光晕（誓约主色调）──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    // 中心底部大光晕
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                AppColors.Favorite.Pink.copy(alpha = glowAlpha),
                                AppColors.Favorite.PinkLight.copy(alpha = glowAlpha * 0.4f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.5f, size.height * 1.1f),
                            radius = size.height * 0.8f
                        )
                    )
                    // 左下角暖粉光晕
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                AppColors.Favorite.PinkDark.copy(alpha = secondaryGlowAlpha),
                                Color.Transparent
                            ),
                            center = Offset(size.width * -0.1f, size.height * 1.15f),
                            radius = size.width * 0.6f
                        )
                    )
                    // 右上角金色光晕
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                AppColors.Favorite.Gold.copy(alpha = goldGlowAlpha),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 1.1f, size.height * -0.1f),
                            radius = size.width * 0.5f
                        )
                    )
                }
        )

        // ── 第2层：漂浮光粒子 ──
        OathFloatingParticles()

        // ── 第3层：顶部到底部的极淡渐变纹理 ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            AppColors.Favorite.Pink.copy(alpha = 0.015f),
                            AppColors.Favorite.PinkLight.copy(alpha = 0.025f),
                            AppColors.Favorite.Pink.copy(alpha = 0.015f),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}

/**
 * 誓约漂浮光粒子 — 模拟花瓣/光尘飘散效果
 */
@Composable
private fun OathFloatingParticles() {
    val infiniteTransition = rememberInfiniteTransition(label = "OathParticles")

    // 粒子数据：预定义位置和动画参数，避免每帧随机
    val particles = remember {
        listOf(
            ParticleData(xRatio = 0.12f, yStart = -0.05f, speed = 0.018f, size = 3f, phase = 0f),
            ParticleData(xRatio = 0.28f, yStart = -0.15f, speed = 0.014f, size = 2.5f, phase = 1.2f),
            ParticleData(xRatio = 0.45f, yStart = -0.08f, speed = 0.02f, size = 3.5f, phase = 2.4f),
            ParticleData(xRatio = 0.62f, yStart = -0.12f, speed = 0.016f, size = 2f, phase = 0.8f),
            ParticleData(xRatio = 0.78f, yStart = -0.03f, speed = 0.022f, size = 3f, phase = 1.8f),
            ParticleData(xRatio = 0.88f, yStart = -0.1f, speed = 0.015f, size = 2.5f, phase = 3.0f),
            ParticleData(xRatio = 0.35f, yStart = -0.2f, speed = 0.012f, size = 2f, phase = 0.5f),
            ParticleData(xRatio = 0.55f, yStart = -0.18f, speed = 0.019f, size = 3f, phase = 2.0f),
            ParticleData(xRatio = 0.08f, yStart = -0.25f, speed = 0.013f, size = 2.5f, phase = 1.5f),
            ParticleData(xRatio = 0.92f, yStart = -0.22f, speed = 0.017f, size = 2f, phase = 2.8f),
            ParticleData(xRatio = 0.2f, yStart = -0.3f, speed = 0.011f, size = 1.8f, phase = 3.5f),
            ParticleData(xRatio = 0.7f, yStart = -0.28f, speed = 0.02f, size = 2.8f, phase = 0.3f),
        )
    }

    // 全局时间驱动
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(60000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ParticleTime"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        particles.forEach { p ->
            // Y轴：从上方飘落到下方，循环
            val rawY = (p.yStart + time * p.speed * 60f) % 1.3f
            val y = rawY * h

            // X轴：正弦横向漂移
            val x = (p.xRatio + 0.03f * sin(time * 30f + p.phase)) * w

            // 透明度：在顶部和底部淡入淡出
            val edgeFade = when {
                rawY < 0.1f -> rawY / 0.1f
                rawY > 1.0f -> (1.3f - rawY) / 0.3f
                else -> 1f
            }
            val alpha = edgeFade * 0.35f

            // 绘制柔和光点（径向渐变圆）
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        AppColors.Favorite.PinkLight.copy(alpha = alpha),
                        AppColors.Favorite.Pink.copy(alpha = alpha * 0.3f),
                        Color.Transparent
                    ),
                    center = Offset(x, y),
                    radius = p.size * 6f
                ),
                radius = p.size * 6f,
                center = Offset(x, y)
            )

            // 中心亮点
            drawCircle(
                color = AppColors.Favorite.PinkLight.copy(alpha = alpha * 0.8f),
                radius = p.size * 1.5f,
                center = Offset(x, y)
            )
        }
    }
}

/**
 * 粒子数据类
 */
private data class ParticleData(
    val xRatio: Float,   // X 位置比例 (0~1)
    val yStart: Float,   // Y 起始位置比例
    val speed: Float,    // 下落速度
    val size: Float,     // 粒子大小
    val phase: Float     // 动画相位偏移
)

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
                enter = fadeIn(tween(800)) + slideInVertically(tween(800, easing = FastOutSlowInEasing)) { 100 }
            ) {
                PremiumFloatingArtwork()
            }
            
            Spacer(modifier = Modifier.height(AppSpacing.Xxxl))
            
            // 2. 标题（带渐变金属质感）
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(800, delayMillis = 150)) + slideInVertically(tween(800, delayMillis = 150, easing = FastOutSlowInEasing)) { 50 }
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
                enter = fadeIn(tween(800, delayMillis = 300)) + slideInVertically(tween(800, delayMillis = 300, easing = FastOutSlowInEasing)) { 50 }
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
                enter = fadeIn(tween(800, delayMillis = 450)) + slideInVertically(tween(800, delayMillis = 450, easing = FastOutSlowInEasing)) { 50 }
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
                            MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha),
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
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                            Color.Transparent,
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
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
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
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
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
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
                    .size(38.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun PremiumInteractiveButton(
    text: String,
    onClick: () -> Unit
) {
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
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
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
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
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
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(modifier = Modifier.width(AppSpacing.Sm))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}