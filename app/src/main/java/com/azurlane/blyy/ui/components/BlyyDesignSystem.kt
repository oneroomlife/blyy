package com.azurlane.blyy.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.azurlane.blyy.ui.theme.AppAnimation
import com.azurlane.blyy.ui.theme.AppColors
import com.azurlane.blyy.ui.theme.AppElevation
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.BlyyIcon
import com.azurlane.blyy.ui.theme.BlyyShapes
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.ui.theme.LocalSemanticColors
import com.azurlane.blyy.ui.theme.LocalUiStyle
import com.azurlane.blyy.ui.theme.blyyDepthShadow
import com.azurlane.blyy.ui.theme.isCommandCenter
import com.azurlane.blyy.ui.theme.rememberReducedMotion

enum class BlyyButtonVariant { Primary, Secondary, Tertiary, Text }

@Composable
fun BlyyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: BlyyButtonVariant = BlyyButtonVariant.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    when (variant) {
        BlyyButtonVariant.Primary -> BlyyPrimaryButton(text, onClick, modifier, enabled, icon)
        BlyyButtonVariant.Secondary,
        BlyyButtonVariant.Tertiary,
        BlyyButtonVariant.Text -> BlyySecondaryButton(text, onClick, modifier, enabled, icon)
    }
}

enum class BlyyCardElevation { Flat, Raised, Emphasis }

@Composable
fun BlyyCard(
    modifier: Modifier = Modifier,
    elevation: BlyyCardElevation = BlyyCardElevation.Raised,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    contentPadding: PaddingValues = PaddingValues(AppSpacing.Lg),
    content: @Composable () -> Unit
) {
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val tonal = when (elevation) {
        BlyyCardElevation.Flat -> AppElevation.Level0
        BlyyCardElevation.Raised -> AppElevation.Level2
        BlyyCardElevation.Emphasis -> AppElevation.Level3
    }
    val shape = if (isCommandCenter) BlyyShapes.PanelMedium else RoundedCornerShape(AppSpacing.Corner.Lg)

    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(
            alpha = if (isCommandCenter) 0.72f else 0.95f
        ),
        tonalElevation = tonal,
        shadowElevation = if (elevation == BlyyCardElevation.Flat) {
            AppElevation.Level0
        } else {
            AppElevation.Level2
        }
    ) {
        Box(
            modifier = Modifier
                .then(
                    if (isCommandCenter) {
                        Modifier.border(
                            width = AppSpacing.Border.Thin,
                            color = accentColor.copy(alpha = 0.22f),
                            shape = shape
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(contentPadding)
        ) {
            content()
        }
    }
}

@Composable
fun BlyyMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    subValue: String? = null,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    BlyyCard(modifier = modifier, elevation = BlyyCardElevation.Raised, accentColor = accentColor) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
        ) {
            Text(
                text = label,
                style = AppTypography.CaptionLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = AppTypography.HeadlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            subValue?.let {
                Text(
                    text = it,
                    style = AppTypography.LabelSmall,
                    color = accentColor
                )
            }
        }
    }
}

@Composable
fun BlyyTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    ScrollableTabRow(
        selectedTabIndex = selectedIndex,
        modifier = modifier.fillMaxWidth(),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
        edgePadding = AppSpacing.Screen.Horizontal,
        indicator = { positions ->
            if (selectedIndex in positions.indices) {
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(positions[selectedIndex]),
                    height = 3.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        divider = {}
    ) {
        tabs.forEachIndexed { index, title ->
            Tab(
                selected = selectedIndex == index,
                onClick = { onTabSelected(index) },
                text = {
                    Text(
                        text = title,
                        style = if (selectedIndex == index) {
                            AppTypography.LabelLarge
                        } else {
                            AppTypography.LabelMedium
                        },
                        fontWeight = if (selectedIndex == index) FontWeight.SemiBold else FontWeight.Normal
                    )
                },
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (isCommandCenter) 0.75f else 0.65f
                )
            )
        }
    }
}

@Composable
fun BlyySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索",
    onClear: (() -> Unit)? = null
) {
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val glass = adaptiveGlassSurface()
    val shape = if (isCommandCenter) BlyyShapes.PanelSmall else RoundedCornerShape(AppSpacing.Corner.Lg)

    // 聚焦态 — 边框亮起 + 光标色，替代无反馈的静态边框
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        animationSpec = tween(AppAnimation.Duration.Fast, easing = AppAnimation.Easings.Standard),
        label = "searchBorder"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(AppSpacing.Height.Input - 8.dp)
            .clip(shape)
            .background(
                if (isCommandCenter) glass.copy(alpha = 0.88f)
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .border(
                width = AppSpacing.Border.Thin,
                color = borderColor,
                shape = shape
            )
            .padding(horizontal = AppSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = if (isFocused) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.primary.copy(alpha = BlyyIcon.AlphaSecondary),
            modifier = Modifier.size(BlyyIcon.Standard)
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            textStyle = AppTypography.BodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            singleLine = true,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            interactionSource = interactionSource,
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = AppTypography.BodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    )
                }
                inner()
            }
        )
        if (query.isNotEmpty() && onClear != null) {
            // 清除键 — 40dp 触摸目标（无障碍 48dp 基线内），圆形 ripple 反馈
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(AppSpacing.Corner.Full))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)),
                        onClick = onClear
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "清除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(BlyyIcon.Standard)
                )
            }
        }
    }
}

@Composable
fun BlyyScreenScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit,
    floatingBottomInset: Dp = AppSpacing.None,
    content: @Composable (contentPadding: PaddingValues) -> Unit
) {
    AdaptiveScreenBackground(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.statusBarsPadding()) {
                topBar()
            }
            content(
                PaddingValues(
                    start = AppSpacing.Screen.Horizontal,
                    end = AppSpacing.Screen.Horizontal,
                    top = AppSpacing.Md,
                    bottom = floatingBottomInset + AppSpacing.Screen.Bottom
                )
            )
        }
    }
}

@Composable
fun BlyyAnimatedEmptyState(
    visible: Boolean,
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    // 减动效模式下退化为无动画直切（仅保留可见性语义）
    val reducedMotion = rememberReducedMotion()
    if (reducedMotion) {
        if (visible) {
            BlyyEmptyState(
                icon = icon,
                title = title,
                description = description,
                actionLabel = actionLabel,
                onAction = onAction
            )
        }
        return
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(AppAnimation.Duration.Normal)) +
            scaleIn(initialScale = 0.92f, animationSpec = tween(AppAnimation.Duration.Normal))
    ) {
        BlyyEmptyState(
            icon = icon,
            title = title,
            description = description,
            actionLabel = actionLabel,
            onAction = onAction
        )
    }
}

@Composable
fun BlyySuccessBanner(
    message: String,
    modifier: Modifier = Modifier,
    visible: Boolean = true
) {
    val semantics = LocalSemanticColors.current
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(AppAnimation.Duration.Smooth)) + scaleIn(initialScale = 0.96f)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = BlyyShapes.PanelSmall,
            color = semantics.successContainer.copy(alpha = 0.85f),
            tonalElevation = AppElevation.Level1
        ) {
            Text(
                text = message,
                modifier = Modifier.padding(AppSpacing.Md),
                style = AppTypography.BodyMedium,
                color = semantics.onSuccessContainer,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ───────────────────────── 四态内容渲染器（V2 §6.10 / 审计 T1.3） ─────────────────────────

/** [BlyyStateScaffold] 首屏加载骨架的形态 */
sealed interface BlyySkeletonKind {
    /** 列表行骨架（圆形头像 + 两行文字） */
    data class List(val itemCount: Int = 8) : BlyySkeletonKind

    /** 网格卡片骨架，minSize 语义与 GridCells.Adaptive 一致 */
    data class Grid(val minSize: Dp) : BlyySkeletonKind

    /** 居中转圈（无明确形状预期的轻量场景） */
    data object Spinner : BlyySkeletonKind
}

/**
 * 四态内容渲染器 —— loading / error / empty / data 统一分支，配「缓存数据 + 顶部错误条」。
 *
 * 分支优先级（对齐 GalleryScreen 既有心智模型，审计 §3.1）：
 * - 已有内容（[hasContent]）：始终渲染 [content]；[error] 非空时顶部滑入细错误条（非阻断，
 *   缓存数据可用、刷新失败的语义），[onRetry] 非空时错误条带「重试」
 * - 无内容：加载中按 [skeleton] 出骨架；[error] 非空出全屏错误态；否则出空态
 *
 * 各屏手写的四态 when 分支应收编到此组件，保证「重试 = 真重试」与骨架形态全局一致。
 *
 * @param hasContent 数据非空（缓存或网络）；与 [isLoading] 独立，两者同时为 true 表示刷新中仍展示旧数据
 * @param error 最近一次加载/刷新失败的文案；null 表示无错误
 * @param onRetry 错误重试回调（全 app 统一「重试 = 真重试」）
 */
@Composable
fun BlyyStateScaffold(
    isLoading: Boolean,
    error: String?,
    hasContent: Boolean,
    modifier: Modifier = Modifier,
    skeleton: BlyySkeletonKind = BlyySkeletonKind.List(),
    emptyIcon: ImageVector = Icons.Rounded.Inbox,
    emptyTitle: String = "暂无内容",
    emptyDescription: String = "",
    emptyActionLabel: String? = null,
    onEmptyAction: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier) {
        // 四态键 — 与原 when 分支同优先级（hasContent 最高：缓存数据 + 错误条并存）
        val stateKey = when {
            hasContent -> "content"
            isLoading -> "loading"
            error != null -> "error"
            else -> "empty"
        }
        Crossfade(
            targetState = stateKey,
            animationSpec = tween(AppAnimation.Duration.Smooth, easing = AppAnimation.Easings.Standard),
            label = "stateScaffold"
        ) { key ->
            when (key) {
                "content" -> {
                    Box(modifier = Modifier.fillMaxSize()) { content() }
                    // 错误条退出动画期间保留最后一条文案，避免收起时文字塌缩
                    var lastError by remember { mutableStateOf("") }
                    if (error != null) lastError = error
                    AnimatedVisibility(
                        visible = error != null,
                        modifier = Modifier.align(Alignment.TopCenter),
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f),
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(
                                    horizontal = AppSpacing.Screen.Horizontal,
                                    vertical = AppSpacing.Xs
                                ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(AppSpacing.Sm))
                                Text(
                                    text = lastError,
                                    style = AppTypography.BodySmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2
                                )
                                if (onRetry != null) {
                                    TextButton(onClick = onRetry) {
                                        Text("重试", style = AppTypography.LabelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
                "loading" -> when (val kind = skeleton) {
                    is BlyySkeletonKind.List -> BlyySkeletonList(
                        itemCount = kind.itemCount,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = AppSpacing.Screen.Horizontal)
                    )
                    is BlyySkeletonKind.Grid -> BlyySkeletonGrid(
                        minSize = kind.minSize,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = AppSpacing.Screen.Horizontal)
                    )
                    BlyySkeletonKind.Spinner -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        BlyyLoadingState()
                    }
                }
                "error" -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = AppSpacing.Screen.Horizontal),
                    contentAlignment = Alignment.Center
                ) {
                    BlyyErrorState(message = error ?: "", onRetry = onRetry)
                }
                else -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    BlyyEmptyState(
                        icon = emptyIcon,
                        title = emptyTitle,
                        description = emptyDescription,
                        actionLabel = emptyActionLabel,
                        onAction = onEmptyAction
                    )
                }
            }
        }
    }
}

// ───────────────────────── 通用分段控件（V2 §6.8，自 GuessDifficultySelector 抽取） ─────────────────────────

/** [BlyySegmentedControl] 的单个分段 */
data class BlyySegmentOption<T>(
    val value: T,
    val label: String,
    val icon: ImageVector? = null
)

/**
 * 通用分段控件 —— 容器与选中段规格与 GuessDifficultySelector 一致：
 * 容器为 L1 受光渐变面板；选中段为 primary 三段受光渐变实体 + 主色辉光投影 + 按压缩放。
 * 适用于筛选行、语言切换等 2-5 个互斥选项的场景（Tab 场景仍用 BlyyTabRow）。
 */
@Composable
fun <T> BlyySegmentedControl(
    options: List<BlyySegmentOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val containerShape = RoundedCornerShape(AppSpacing.Corner.Lg)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .blyyDepthShadow(
                elevation = AppElevation.Level1,
                shape = containerShape
            )
            .clip(containerShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceContainerLowest,
                        MaterialTheme.colorScheme.surfaceContainerLow
                    )
                )
            )
            .border(
                width = AppSpacing.Border.Thin,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                shape = containerShape
            )
            .padding(AppSpacing.Xs),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
    ) {
        options.forEach { option ->
            BlyySegment(
                label = option.label,
                icon = option.icon,
                isSelected = selected == option.value,
                onClick = { onSelect(option.value) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 单个分段：选中态 = primary 受光渐变实体按键 */
@Composable
private fun BlyySegment(
    label: String,
    icon: ImageVector?,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) AppAnimation.Press.LightScale else 1f,
        animationSpec = AppAnimation.Press.light(),
        label = "segmentScale"
    )

    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    // 选中段：与 BlyyPrimaryButton 一致的三段垂直受光渐变（顶亮中实底深）
    val segmentShape = RoundedCornerShape(AppSpacing.Corner.Lg - AppSpacing.Xs)
    val segmentBrush = if (isSelected) {
        Brush.verticalGradient(
            colors = listOf(
                lerp(primary, Color.White, 0.22f),
                primary,
                lerp(primary, Color.Black, 0.22f)
            )
        )
    } else {
        Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
    }

    val labelColor by animateColorAsState(
        targetValue = if (isSelected) onPrimary else onSurfaceVariant,
        animationSpec = AppAnimation.Specs.fast(),
        label = "segmentLabel"
    )

    Row(
        modifier = modifier
            .scale(scale)
            .blyyDepthShadow(
                elevation = if (isSelected) AppElevation.Level1 else AppElevation.Level0,
                shape = segmentShape,
                // 直射阴影带主色 — 选中段的辉光投影
                spotColor = primary.copy(alpha = if (isSelected) 0.45f else 0f)
            )
            .clip(segmentShape)
            .background(segmentBrush)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = onPrimary.copy(alpha = if (isSelected) 0.2f else 0.08f)),
                onClick = onClick
            )
            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        icon?.let {
            Icon(
                it,
                contentDescription = null,
                tint = labelColor,
                modifier = Modifier.size(AppSpacing.Icon.Md)
            )
            Spacer(Modifier.width(AppSpacing.Sm))
        }
        Text(
            text = label,
            style = AppTypography.LabelLarge,
            fontWeight = FontWeight.Bold,
            color = labelColor,
            maxLines = 1
        )
    }
}
