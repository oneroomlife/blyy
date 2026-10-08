package com.azurlane.blyy.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.azurlane.blyy.ui.theme.AppAnimation
import com.azurlane.blyy.ui.theme.AppColors
import com.azurlane.blyy.ui.theme.AppElevation
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.BlyyShapes
import com.azurlane.blyy.ui.theme.ChatColors
import com.azurlane.blyy.ui.theme.ClassicColors
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.ui.theme.LocalUiStyle
import com.azurlane.blyy.ui.theme.UiStyle
import com.azurlane.blyy.ui.theme.chamferedShape
import com.azurlane.blyy.ui.theme.isCommandCenter
import com.azurlane.blyy.ui.theme.isWatchScreen
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.sp

/**
 * 自适应页面背景 — 根据 UI 风格切换
 */
@Composable
fun AdaptiveScreenBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (LocalUiStyle.current.isCommandCenter()) {
        CommandCenterBackground(modifier = modifier, content = content)
    } else {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainer),
            content = { content() }
        )
    }
}

/**
 * 指挥中心背景 — 深海渐变 + 氛围光晕 + 顶部聚焦光
 */
@Composable
fun CommandCenterBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val isDark = LocalIsDark.current
    val bgBrush = if (isDark) AppColors.Gradient.BackgroundDark() else AppColors.Gradient.BackgroundLight()
    val primaryGlow = if (isDark) AppColors.PrimaryDark.copy(alpha = 0.06f) else AppColors.PrimaryLight.copy(alpha = 0.04f)
    val tertiaryGlow = if (isDark) AppColors.TertiaryDark.copy(alpha = 0.04f) else AppColors.TertiaryLight.copy(alpha = 0.03f)
    val topGlowColor = if (isDark) AppColors.Effect.TopGlowDark else AppColors.Effect.TopGlowLight.copy(alpha = 0.25f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgBrush)
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(topGlowColor, Color.Transparent),
                        center = Offset(size.width * 0.5f, -size.height * 0.1f),
                        radius = size.height * 0.7f
                    )
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(primaryGlow, Color.Transparent),
                        center = Offset(-size.width * 0.15f, size.height * 1.1f),
                        radius = size.width * 0.7f
                    )
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(tertiaryGlow, Color.Transparent),
                        center = Offset(size.width * 1.15f, size.height * 1.1f),
                        radius = size.width * 0.6f
                    )
                )
            }
    ) {
        content()
    }
}

/**
 * HUD 风格顶部栏
 */
@Composable
fun BlyyTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onMenuClick: (() -> Unit)? = null,
    onBackClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    if (!LocalUiStyle.current.isCommandCenter()) {
        ClassicTopBar(
            title = title,
            modifier = modifier,
            subtitle = subtitle,
            onMenuClick = onMenuClick,
            onBackClick = onBackClick,
            actions = actions
        )
        return
    }

    val isDark = LocalIsDark.current
    val isWatch = isWatchScreen()
    val panelFill = if (isDark) AppColors.Panel.FillDark() else AppColors.Panel.FillLight()
    val shadowAmbient = if (isDark) AppColors.Depth.AmbientDark else AppColors.Depth.AmbientLight
    val shadowSpot = if (isDark) AppColors.Depth.SpotDark else AppColors.Depth.SpotLight
    val accentColor = MaterialTheme.colorScheme.primary

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Screen.Horizontal, vertical = if (isWatch) AppSpacing.Xs else AppSpacing.Sm)
                .shadow(
                    elevation = AppElevation.Level2,
                    shape = BlyyShapes.PanelMedium,
                    ambientColor = shadowAmbient,
                    spotColor = shadowSpot
                )
                .clip(BlyyShapes.PanelMedium)
                .background(panelFill)
                .border(
                    width = AppSpacing.Border.Thin,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.6f),
                            accentColor.copy(alpha = 0.15f),
                            AppColors.Accent.Gold.copy(alpha = 0.3f)
                        )
                    ),
                    shape = BlyyShapes.PanelMedium
                )
                .padding(horizontal = if (isWatch) AppSpacing.Xs else AppSpacing.Sm, vertical = AppSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                onBackClick != null -> {
                    IconButton(onClick = onBackClick, modifier = Modifier.size(if (isWatch) 32.dp else 40.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = accentColor, modifier = Modifier.size(if (isWatch) 18.dp else 24.dp))
                    }
                }
                onMenuClick != null -> {
                    IconButton(onClick = onMenuClick, modifier = Modifier.size(if (isWatch) 32.dp else 40.dp)) {
                        Icon(Icons.Default.Menu, "菜单", tint = accentColor, modifier = Modifier.size(if (isWatch) 18.dp else 24.dp))
                    }
                }
            }

            Column(modifier = Modifier.weight(1f).padding(horizontal = AppSpacing.Sm)) {
                Text(
                    text = title,
                    style = AppTypography.TitleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = AppTypography.LabelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(content = actions)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            accentColor.copy(alpha = 0.7f),
                            AppColors.Accent.Gold.copy(alpha = 0.5f),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}

@Composable
fun ClassicTopBar(
    title: String,
    modifier: Modifier,
    subtitle: String?,
    onMenuClick: (() -> Unit)?,
    onBackClick: (() -> Unit)?,
    actions: @Composable (RowScope.() -> Unit)
) {
    val isWatch = isWatchScreen()

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Screen.Horizontal, vertical = if (isWatch) AppSpacing.Xs else AppSpacing.Sm)
                .shadow(
                    elevation = AppElevation.Level1,
                    shape = RoundedCornerShape(AppSpacing.Corner.Lg),
                    ambientColor = AppColors.Depth.AmbientLight,
                    spotColor = AppColors.Depth.SpotLight
                )
                .clip(RoundedCornerShape(AppSpacing.Corner.Lg))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = if (isWatch) AppSpacing.Xs else AppSpacing.Sm, vertical = AppSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                onBackClick != null -> {
                    IconButton(onClick = onBackClick, modifier = Modifier.size(if (isWatch) 32.dp else 40.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(if (isWatch) 18.dp else 24.dp))
                    }
                }
                onMenuClick != null -> {
                    IconButton(onClick = onMenuClick, modifier = Modifier.size(if (isWatch) 32.dp else 40.dp)) {
                        Icon(Icons.Default.Menu, "菜单", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(if (isWatch) 18.dp else 24.dp))
                    }
                }
            }

            Column(modifier = Modifier.weight(1f).padding(horizontal = AppSpacing.Sm)) {
                Text(
                    text = title,
                    style = AppTypography.TitleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = AppTypography.LabelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(content = actions)
        }

        HorizontalDivider()
    }
}

@Composable
private fun HorizontalDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
    )
}

/**
 * 科技面板容器
 *
 * 质感体系（指挥中心风格）：
 * - 有色柔和投影（[AppColors.Depth]）：面板从背景上"浮起"，替代旧零阴影平面化
 * - 内部受光渐变（[AppColors.Panel.FillDark]/[FillLight]）：顶部微亮、底部微暗，模拟曲面受光
 * - 顶部 1px 内高光线，模拟玻璃材质的顶面反光
 * - 四角 L 型描边装饰（drawBehind），强化 HUD 机械感
 */
@Composable
fun BlyyPanel(
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    chamfer: Dp = 12.dp,
    content: @Composable () -> Unit
) {
    if (!LocalUiStyle.current.isCommandCenter()) {
        Box(
            modifier = modifier
                .shadow(
                    elevation = AppElevation.Level1,
                    shape = RoundedCornerShape(AppSpacing.Corner.Lg),
                    ambientColor = AppColors.Depth.AmbientLight,
                    spotColor = AppColors.Depth.SpotLight
                )
                .clip(RoundedCornerShape(AppSpacing.Corner.Lg))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceContainerLow,
                            MaterialTheme.colorScheme.surfaceContainer
                        )
                    )
                )
                .border(
                    width = AppSpacing.Border.Thin,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(AppSpacing.Corner.Lg)
                )
        ) {
            content()
        }
        return
    }

    val isDark = LocalIsDark.current
    val panelFill = if (isDark) AppColors.Panel.FillDark() else AppColors.Panel.FillLight()
    val shadowAmbient = if (isDark) AppColors.Depth.AmbientDark else AppColors.Depth.AmbientLight
    val shadowSpot = if (isDark) AppColors.Depth.SpotDark else AppColors.Depth.SpotLight
    val shape = chamferedShape(chamfer)
    val cornerLenPx = with(LocalDensity.current) { 14.dp.toPx() }
    val strokePx = with(LocalDensity.current) { 1.5.dp.toPx() }
    val topHighlightPx = with(LocalDensity.current) { 1.dp.toPx() }
    val decorationStrokePx = with(LocalDensity.current) { 2.dp.toPx() }

    // drawBehind 每次绘制都会执行的分配提前到组合期：
    // BlyyPanel 是全局容器组件，滚动/动画驱动的重绘非常频繁，
    // 每帧新建 2 个 Path + 渐变 Brush 会造成持续 GC 压力
    val highlightBrush = remember(isDark) {
        Brush.horizontalGradient(
            colors = listOf(
                Color.Transparent,
                Color.White.copy(alpha = if (isDark) 0.08f else 0.18f),
                Color.White.copy(alpha = if (isDark) 0.05f else 0.12f),
                Color.Transparent
            )
        )
    }
    val pathCache = remember { BlyyPanelPathCache() }

    Box(
        modifier = modifier
            .shadow(
                elevation = AppElevation.Level1,
                shape = shape,
                ambientColor = shadowAmbient,
                spotColor = shadowSpot
            )
            .clip(shape)
            .background(panelFill)
            .border(
                width = AppSpacing.Border.Thin,
                brush = Brush.linearGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.5f),
                        accentColor.copy(alpha = 0.1f)
                    )
                ),
                shape = shape
            )
            .drawBehind {
                // 左上角 L 型装饰（原设计保留并增强）
                pathCache.ensure(size.width, size.height, cornerLenPx)
                drawPath(pathCache.topLeftPath, accentColor.copy(alpha = 0.4f), style = Stroke(width = decorationStrokePx))

                // 顶部内高光 — 1px 渐变线，模拟玻璃顶面反光
                drawRect(
                    brush = highlightBrush,
                    topLeft = Offset(0f, 0f),
                    size = androidx.compose.ui.geometry.Size(size.width, topHighlightPx)
                )

                // 右下角 L 型装饰 — 与左上角呼应，对称构图
                drawPath(
                    pathCache.cornerPath,
                    AppColors.Accent.Gold.copy(alpha = 0.35f),
                    style = Stroke(width = strokePx)
                )
            }
    ) {
        content()
    }
}

/** BlyyPanel 装饰线 Path 缓存：尺寸不变时跨重绘复用，避免每次 draw 重建 Path */
private class BlyyPanelPathCache {
    private var width = Float.NaN
    private var height = Float.NaN
    private var cornerLenPx = Float.NaN
    val topLeftPath = Path()
    val cornerPath = Path()

    fun ensure(width: Float, height: Float, cornerLenPx: Float) {
        if (this.width == width && this.height == height && this.cornerLenPx == cornerLenPx) return
        this.width = width
        this.height = height
        this.cornerLenPx = cornerLenPx
        topLeftPath.rewind()
        topLeftPath.moveTo(0f, height * 0.3f)
        topLeftPath.lineTo(0f, 0f)
        topLeftPath.lineTo(width * 0.15f, 0f)
        cornerPath.rewind()
        cornerPath.moveTo(width, height - cornerLenPx)
        cornerPath.lineTo(width, height)
        cornerPath.lineTo(width - cornerLenPx, height)
    }
}

/**
 * 主操作按钮 — 切角 + 渐变 + 按压反馈 + 流光扫过
 *
 * 优化要点（高级化）：
 * - 顶部 1.5px 高光描边，模拟玻璃按钮顶面反光
 * - 按压时触发的横向流光扫过动画（drawWithContent + BlendMode.Screen）
 * - 按压时阴影减弱（模拟物理下沉），松开回弹
 */
@Composable
fun BlyyPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) AppAnimation.Press.HeavyScale else 1f,
        animationSpec = AppAnimation.Press.heavy(),
        label = "btnScale"
    )
    // 按压时投影回落（模拟物理下沉），松开回弹
    val shadowElevation by animateDpAsState(
        targetValue = if (isPressed || !enabled) AppElevation.Level0 else AppElevation.Level3,
        animationSpec = AppAnimation.Press.heavy(),
        label = "btnShadow"
    )
    val primaryColor = MaterialTheme.colorScheme.primary
    // 三段垂直渐变 — 顶亮中实底深，模拟顶部受光的实体按键
    val btnBrush = if (enabled) {
        Brush.verticalGradient(
            colors = listOf(
                lerp(primaryColor, Color.White, 0.22f),
                primaryColor,
                lerp(primaryColor, Color.Black, 0.22f)
            )
        )
    } else {
        val disabledColor = MaterialTheme.colorScheme.surfaceVariant
        Brush.verticalGradient(listOf(disabledColor, disabledColor))
    }
    val isWatch = isWatchScreen()

    // 流光扫过动画 — 按压时触发一次（Animatable 惰性驱动，静止时不占用渲染帧）
    val shimmerXAnim = remember { Animatable(-1f) }
    LaunchedEffect(isPressed) {
        if (isPressed && enabled) {
            shimmerXAnim.snapTo(-1f)
            shimmerXAnim.animateTo(
                targetValue = 2f,
                animationSpec = tween(durationMillis = 700, easing = LinearEasing)
            )
        }
    }
    val shimmerX = shimmerXAnim.value

    Box(
        modifier = modifier
            .scale(scale)
            .shadow(
                elevation = shadowElevation,
                shape = BlyyShapes.Button,
                ambientColor = AppColors.Depth.AmbientDark,
                // 直射阴影带主色 — 按钮悬浮时的主色辉光
                spotColor = primaryColor.copy(alpha = 0.45f)
            )
            .clip(BlyyShapes.Button)
            .background(brush = btnBrush)
            .border(
                width = AppSpacing.Border.Thin,
                // 顶部亮、底部透 — 配合渐变形成倒角受光
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (enabled) 0.45f else 0.1f),
                        Color.White.copy(alpha = 0.04f)
                    )
                ),
                shape = BlyyShapes.Button
            )
            // 顶部高光描边 + 流光扫过
            .drawWithContent {
                drawContent()
                // 顶部 1.5px 高光线
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.35f),
                            Color.Transparent
                        )
                    ),
                    topLeft = Offset(0f, 0f),
                    size = androidx.compose.ui.geometry.Size(size.width, 1.5.dp.toPx())
                )
                // 流光扫过 — 仅 enabled 时显示
                if (enabled) {
                    val shimmerWidth = size.width * 0.4f
                    val startX = shimmerX * size.width - shimmerWidth / 2
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.0f),
                                Color.White.copy(alpha = 0.25f),
                                Color.White.copy(alpha = 0.0f),
                                Color.Transparent
                            ),
                            startX = startX,
                            endX = startX + shimmerWidth
                        ),
                        blendMode = BlendMode.Screen
                    )
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f)),
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = if (isWatch) AppSpacing.Padding.ButtonHorizontal - AppSpacing.Xs else AppSpacing.Padding.ButtonHorizontal, vertical = AppSpacing.Padding.ButtonVertical),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon?.let {
                Icon(
                    it,
                    contentDescription = null,
                    // 禁用态用 onSurfaceVariant — 灰渐变底上白字对比度不足
                    tint = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(if (isWatch) 16.dp else 20.dp)
                )
                Spacer(Modifier.width(AppSpacing.Sm))
            }
            Text(
                text = text,
                style = AppTypography.ButtonText,
                color = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * 次要按钮 — 描边风格
 */
@Composable
fun BlyySecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) AppAnimation.Press.HeavyScale else 1f,
        animationSpec = AppAnimation.Press.heavy(),
        label = "btnScale2"
    )
    val isDark = LocalIsDark.current
    val isWatch = isWatchScreen()
    val panelFill = if (isDark) AppColors.Panel.FillDark() else AppColors.Panel.FillLight()
    val shadowAmbient = if (isDark) AppColors.Depth.AmbientDark else AppColors.Depth.AmbientLight
    val shadowSpot = if (isDark) AppColors.Depth.SpotDark else AppColors.Depth.SpotLight
    // 禁用态文字/图标退为 onSurfaceVariant — 与 BlyyPrimaryButton 禁用态一致
    val contentColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .scale(scale)
            .shadow(
                elevation = if (enabled) AppElevation.Level1 else AppElevation.Level0,
                shape = BlyyShapes.Button,
                ambientColor = shadowAmbient,
                spotColor = shadowSpot
            )
            .clip(BlyyShapes.Button)
            .background(panelFill)
            .border(
                width = AppSpacing.Border.Normal,
                color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.6f else 0.2f),
                shape = BlyyShapes.Button
            )
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = if (isWatch) AppSpacing.Padding.ButtonHorizontal - AppSpacing.Xs else AppSpacing.Padding.ButtonHorizontal, vertical = AppSpacing.Padding.ButtonVertical),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon?.let {
                Icon(it, contentDescription = null, tint = contentColor, modifier = Modifier.size(if (isWatch) 16.dp else 20.dp))
                Spacer(Modifier.width(AppSpacing.Sm))
            }
            Text(
                text = text,
                style = AppTypography.ButtonText,
                color = contentColor,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * 标签 Chip — 筛选/分类用
 *
 * 优化要点（高级化）：
 * - 选中态背景色与文字色用 animateColorAsState 平滑过渡，避免硬切
 * - 选中态边框改为渐变描边（青→金），与整体 HUD 语言一致
 * - 选中态追加一层极淡的内高光，强化"激活"质感
 */
@Composable
fun BlyyChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) AppAnimation.Press.LightScale else 1f,
        animationSpec = AppAnimation.Press.light(),
        label = "chipScale"
    )
    // 颜色平滑过渡 — 替代旧的硬切 bgColor/borderColor
    // 选中态：垂直受光渐变（上密下疏），呼应主按钮的实体感
    val bgColor = if (selected) {
        Brush.verticalGradient(
            colors = listOf(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            )
        )
    }
    val textColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 250, easing = AppAnimation.Easings.Standard),
        label = "chipText"
    )
    val isWatch = isWatchScreen()

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .scale(scale)
            .clip(RoundedCornerShape(AppSpacing.Corner.Sm))
            .background(bgColor)
            .border(
                width = if (selected) AppSpacing.Border.Normal else AppSpacing.Border.Thin,
                brush = if (selected) {
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        )
                    )
                } else {
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.1f)
                        )
                    )
                },
                shape = RoundedCornerShape(AppSpacing.Corner.Sm)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                onClick = onClick
            )
            .padding(horizontal = if (isWatch) AppSpacing.Padding.ChipHorizontal - AppSpacing.Xxs else AppSpacing.Padding.ChipHorizontal, vertical = AppSpacing.Padding.ChipVertical),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = AppTypography.LabelMedium,
            color = textColor
        )
    }
}

/**
 * 自适应毛玻璃表面颜色
 */
@Composable
fun adaptiveGlassSurface(): Color {
    val isDark = LocalIsDark.current
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()

    return if (isCommandCenter) {
        if (isDark) AppColors.GlassSurfaceDark else AppColors.GlassSurfaceLight
    } else {
        if (isDark) ClassicColors.GlassSurfaceDark else ClassicColors.GlassSurfaceLight
    }
}

/**
 * 自适应毛玻璃描边颜色
 */
@Composable
fun adaptiveGlassBorder(): Color {
    val isDark = LocalIsDark.current
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()

    return if (isCommandCenter) {
        if (isDark) AppColors.GlassBorderDark else AppColors.GlassBorderLight
    } else {
        if (isDark) ClassicColors.GlassBorderDark else ClassicColors.GlassBorderLight
    }
}

/**
 * 自适应卡片形状
 */
@Composable
fun adaptiveCardShape(): androidx.compose.ui.graphics.Shape {
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val isWatch = isWatchScreen()

    return if (isCommandCenter) {
        if (isWatch) chamferedShape(7.dp) else BlyyShapes.Card
    } else {
        if (isWatch) RoundedCornerShape(AppSpacing.Corner.Md) else RoundedCornerShape(AppSpacing.Corner.Lg)
    }
}

// ══════════════════════════════════════════════════════════════
//  统一状态组件 — 空状态 / 错误状态 / 加载状态
// ══════════════════════════════════════════════════════════════

/**
 * 统一空状态组件
 *
 * @param icon 主题图标（如 Icons.Rounded.SearchOff）
 * @param title 主标题
 * @param description 描述文字
 * @param actionLabel 可选操作按钮文字
 * @param onAction 可选操作回调
 */
@Composable
fun BlyyEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val accentColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.Xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .then(
                    if (isCommandCenter) {
                        Modifier
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        accentColor.copy(alpha = 0.15f),
                                        Color.Transparent
                                    )
                                ),
                                shape = CircleShape
                            )
                            .border(
                                width = AppSpacing.Border.Thin,
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        accentColor.copy(alpha = 0.4f),
                                        AppColors.Accent.Gold.copy(alpha = 0.2f)
                                    )
                                ),
                                shape = CircleShape
                            )
                    } else {
                        Modifier.background(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                            CircleShape
                        )
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = accentColor.copy(alpha = 0.7f)
            )
        }

        Text(
            text = title,
            style = AppTypography.TitleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Text(
            text = description,
            style = AppTypography.BodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = AppSpacing.Xxl)
        )

        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(AppSpacing.Sm))
            BlyyPrimaryButton(
                text = actionLabel,
                onClick = onAction
            )
        }
    }
}

/**
 * 统一错误状态组件
 */
@Composable
fun BlyyErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    BlyyPanel(
        modifier = modifier.fillMaxWidth(),
        accentColor = MaterialTheme.colorScheme.error
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
        ) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
            )
            Text(
                text = message,
                style = AppTypography.BodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            if (onRetry != null) {
                Spacer(modifier = Modifier.height(AppSpacing.Xs))
                BlyySecondaryButton(
                    text = "重试",
                    onClick = onRetry
                )
            }
        }
    }
}

/**
 * 统一加载状态组件
 */
@Composable
fun BlyyLoadingState(
    message: String = "加载中...",
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.Xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(36.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp
        )
        Text(
            text = message,
            style = AppTypography.BodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 增强版设置行 — 带图标与动画开关
 *
 * 双模式：
 * - [asCard] = true（默认）：行自带 [BlyyPanel] 外壳，作为**单独组件**与相邻行保持间距、
 *   互不相连（配合 [BlyySectionPanel] 的 separateItems = true 使用）
 * - [asCard] = false：扁平行，面板边框由外层 BlyySectionPanel 统一承载（用于行内混排
 *   滑块/输入框等非卡片内容的分区）
 */
@Composable
fun BlyySettingsRow(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    asCard: Boolean = true
) {
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val accentColor = MaterialTheme.colorScheme.primary
    val isWatch = isWatchScreen()

    // 注意：卡片模式下不要在行内再叠加 surfaceContainerHigh 背景，否则会与 BlyyPanel 自身的
    // surfaceVariant.copy(alpha=0.45f) 背景形成双层叠加，导致浅色主题下出现白色背景断层。
    val rowContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (isWatch) AppSpacing.Md else AppSpacing.Lg),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(if (isWatch) 32.dp else 40.dp)
                        .then(
                            if (isCommandCenter) {
                                Modifier
                                    .background(
                                        brush = Brush.radialGradient(
                                            colors = listOf(
                                                accentColor.copy(alpha = if (checked) 0.25f else 0.1f),
                                                Color.Transparent
                                            )
                                        ),
                                        shape = CircleShape
                                    )
                                    .border(
                                        width = AppSpacing.Border.Thin,
                                        color = accentColor.copy(alpha = if (checked) 0.4f else 0.15f),
                                        shape = CircleShape
                                    )
                            } else {
                                Modifier.background(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (checked) 0.4f else 0.2f),
                                    CircleShape
                                )
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(if (isWatch) 16.dp else 20.dp),
                        tint = if (checked) accentColor else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(AppSpacing.Md))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = AppTypography.TitleSmall,
                        fontWeight = FontWeight.Medium,
                        color = if (checked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.Xxs))
                    Text(
                        text = description,
                        style = AppTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = accentColor.copy(alpha = 0.5f),
                    checkedThumbColor = accentColor
                )
            )
        }
    }

    if (asCard) {
        BlyyPanel(modifier = modifier.fillMaxWidth()) { rowContent() }
    } else {
        Row(modifier = modifier.fillMaxWidth()) { rowContent() }
    }
}

/**
 * 增强版分段面板 — 带图标标题
 *
 * 优化要点（高级化）：
 * - 标题图标包裹圆形渐变背景徽章，强化标题视觉锚点
 * - 装饰线改为三段渐变（实→虚→透明），层次更细腻
 */
@Composable
fun BlyySectionPanel(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    /** true = 内容各项为自带面板的独立卡片（单独组件、互不相连），section 只提供标题不包外壳面板 */
    separateItems: Boolean = false,
    content: @Composable () -> Unit
) {
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
        ) {
            // 图标徽章 — 圆形渐变背景 + 描边，替代裸图标
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
            if (isCommandCenter) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    accentColor.copy(alpha = 0.4f),
                                    accentColor.copy(alpha = 0.15f),
                                    Color.Transparent
                                )
                            )
                        )
                )
            }
        }
        if (separateItems) {
            // 独立卡片模式：各项自带面板外壳，section 不再包外壳面板——
            // 否则双重边框会在卡片之间形成"幽灵面板"细带
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Gap.Md)) {
                content()
            }
        } else {
            BlyyPanel(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Gap.Sm)) {
                    content()
                }
            }
        }
    }
}

/**
 * 统一语音气泡组件 — 替代 SecretaryChibiOverlay / VoiceScreen / ShipGalleryScreen 中的重复实现。
 *
 * 支持两种变体：
 * - [SpeechBubbleVariant.WithTail]：带三角尾尖，深色主题下深底白字，浅色主题下白底黑字（原 SecretaryChibiOverlay/VoiceScreen 实现）
 * - [SpeechBubbleVariant.Compact]：无尾尖，单行省略，颜色与 WithTail 相反（原 ShipGalleryScreen 实现）
 *
 * @param text 气泡文字
 * @param variant 气泡变体，默认 [SpeechBubbleVariant.WithTail]
 * @param modifier 修饰符
 * @param isDark 是否暗色模式，默认读取 [LocalIsDark]
 */
@Composable
fun BlyySpeechBubble(
    text: String,
    variant: SpeechBubbleVariant = SpeechBubbleVariant.WithTail,
    modifier: Modifier = Modifier,
    isDark: Boolean = LocalIsDark.current,
    maxLines: Int = Int.MAX_VALUE
) {
    when (variant) {
        SpeechBubbleVariant.WithTail -> WithTailBubble(text = text, isDark = isDark, modifier = modifier, maxLines = maxLines)
        SpeechBubbleVariant.Compact -> CompactBubble(text = text, isDark = isDark, modifier = modifier)
    }
}

enum class SpeechBubbleVariant { WithTail, Compact }

@Composable
private fun WithTailBubble(text: String, isDark: Boolean, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val bubbleColor = if (isDark) ChatColors.SpeechBubbleDark else ChatColors.SpeechBubbleLight
    val textColor = if (isDark) Color.White else Color.Black
    val hPadding = if (text.length > 40) AppSpacing.Lg else AppSpacing.Md
    val vPadding = if (text.length > 40) AppSpacing.Padding.ListItemVertical else AppSpacing.Sm
    val cornerRadius = if (text.length > 60) AppSpacing.Corner.Lg else AppSpacing.Corner.Md

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(cornerRadius),
            color = bubbleColor,
            tonalElevation = AppSpacing.Elevation.Md,
            shadowElevation = AppSpacing.Elevation.Lg,
            border = BorderStroke(AppSpacing.Border.Thin, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = hPadding, vertical = vPadding),
                style = AppTypography.BodySmallMedium.copy(
                    fontSize = if (text.length > 80) 11.sp else 13.sp,
                    lineHeight = if (text.length > 80) 15.sp else 18.sp
                ),
                color = textColor,
                textAlign = TextAlign.Center,
                maxLines = maxLines
            )
        }
        Canvas(modifier = Modifier.size(12.dp, 6.dp)) {
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width / 2f, size.height)
                close()
            }
            drawPath(path, color = bubbleColor)
        }
    }
}

@Composable
private fun CompactBubble(text: String, isDark: Boolean, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(AppSpacing.Corner.Md),
        color = if (isDark) Color.White.copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.8f),
        modifier = modifier
    ) {
        Text(
            text = text,
            style = AppTypography.LabelSmall,
            color = if (isDark) Color.Black else Color.White,
            modifier = Modifier.padding(horizontal = AppSpacing.Sm, vertical = AppSpacing.Xxs),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
