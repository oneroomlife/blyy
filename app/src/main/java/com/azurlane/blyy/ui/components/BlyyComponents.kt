package com.azurlane.blyy.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.azurlane.blyy.ui.theme.AppAnimation
import com.azurlane.blyy.ui.theme.AppColors
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.BlyyShapes
import com.azurlane.blyy.ui.theme.ClassicColors
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.ui.theme.LocalUiStyle
import com.azurlane.blyy.ui.theme.UiStyle
import com.azurlane.blyy.ui.theme.chamferedShape
import com.azurlane.blyy.ui.theme.isCommandCenter
import com.azurlane.blyy.ui.theme.isWatchScreen

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
    val panelColor = if (isDark) AppColors.Panel.Dark else AppColors.Panel.Light
    val accentColor = MaterialTheme.colorScheme.primary

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Screen.Horizontal, vertical = if (isWatch) AppSpacing.Xs else AppSpacing.Sm)
                .clip(BlyyShapes.PanelMedium)
                .background(panelColor)
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

/**
 * 科技面板容器
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
                .clip(RoundedCornerShape(AppSpacing.Corner.Lg))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
        ) {
            content()
        }
        return
    }

    val isDark = LocalIsDark.current
    val panelColor = if (isDark) AppColors.Panel.Dark else AppColors.Panel.Light.copy(alpha = 0.95f)
    val shape = chamferedShape(chamfer)

    Box(
        modifier = modifier
            .clip(shape)
            .background(panelColor)
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
                val path = Path().apply {
                    moveTo(0f, size.height * 0.3f)
                    lineTo(0f, 0f)
                    lineTo(size.width * 0.15f, 0f)
                }
                drawPath(path, accentColor.copy(alpha = 0.4f), style = Stroke(width = 2.dp.toPx()))
            }
    ) {
        content()
    }
}

/**
 * 主操作按钮 — 切角 + 渐变 + 按压反馈
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
    val bgColor by animateColorAsState(
        targetValue = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        label = "btnBg"
    )
    val isWatch = isWatchScreen()

    Box(
        modifier = modifier
            .scale(scale)
            .clip(BlyyShapes.Button)
            .background(
                brush = if (enabled) {
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                        )
                    )
                } else {
                    Brush.linearGradient(listOf(bgColor, bgColor))
                }
            )
            .border(
                width = AppSpacing.Border.Thin,
                color = AppColors.Accent.Gold.copy(alpha = if (enabled) 0.4f else 0.1f),
                shape = BlyyShapes.Button
            )
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f)),
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = if (isWatch) AppSpacing.Padding.ButtonHorizontal - 4.dp else AppSpacing.Padding.ButtonHorizontal, vertical = AppSpacing.Padding.ButtonVertical),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon?.let {
                Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(if (isWatch) 16.dp else 20.dp))
                Spacer(Modifier.width(AppSpacing.Sm))
            }
            Text(
                text = text,
                style = AppTypography.ButtonText,
                color = MaterialTheme.colorScheme.onPrimary,
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
    val panelColor = if (isDark) AppColors.Panel.Dark else AppColors.Panel.Light

    Box(
        modifier = modifier
            .scale(scale)
            .clip(BlyyShapes.Button)
            .background(panelColor)
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
            .padding(horizontal = if (isWatch) AppSpacing.Padding.ButtonHorizontal - 4.dp else AppSpacing.Padding.ButtonHorizontal, vertical = AppSpacing.Padding.ButtonVertical),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon?.let {
                Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(if (isWatch) 16.dp else 20.dp))
                Spacer(Modifier.width(AppSpacing.Sm))
            }
            Text(
                text = text,
                style = AppTypography.ButtonText,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * 标签 Chip — 筛选/分类用
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
    val bgColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val isWatch = isWatchScreen()

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .scale(scale)
            .clip(RoundedCornerShape(AppSpacing.Corner.Sm))
            .background(bgColor)
            .border(
                width = if (selected) AppSpacing.Border.Normal else AppSpacing.Border.Thin,
                color = borderColor,
                shape = RoundedCornerShape(AppSpacing.Corner.Sm)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                onClick = onClick
            )
            .padding(horizontal = if (isWatch) AppSpacing.Padding.ChipHorizontal - 2.dp else AppSpacing.Padding.ChipHorizontal, vertical = AppSpacing.Padding.ChipVertical),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = AppTypography.LabelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
