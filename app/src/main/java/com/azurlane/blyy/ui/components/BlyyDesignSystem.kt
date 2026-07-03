package com.azurlane.blyy.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.azurlane.blyy.ui.theme.AppElevation
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.BlyyIcon
import com.azurlane.blyy.ui.theme.BlyyShapes
import com.azurlane.blyy.ui.theme.LocalSemanticColors
import com.azurlane.blyy.ui.theme.LocalUiStyle
import com.azurlane.blyy.ui.theme.isCommandCenter

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
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                shape = shape
            )
            .padding(horizontal = AppSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = BlyyIcon.AlphaSecondary),
            modifier = Modifier.size(BlyyIcon.Standard)
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            textStyle = AppTypography.BodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            singleLine = true,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
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
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "清除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(BlyyIcon.Standard)
                    .clip(RoundedCornerShape(AppSpacing.Corner.Full))
                    .clickable(onClick = onClear)
            )
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
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(350)) + scaleIn(initialScale = 0.92f, animationSpec = tween(350))
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
        enter = fadeIn(tween(280)) + scaleIn(initialScale = 0.96f)
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
