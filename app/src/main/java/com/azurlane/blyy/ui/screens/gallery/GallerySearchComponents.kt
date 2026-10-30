package com.azurlane.blyy.ui.screens.gallery

/*
 * 船坞顶栏搜索组件簇 — 自 GalleryTopBar.kt 机械拆分而来，纯移动零逻辑改动。
 * SearchToggleButton / ModernFilterButton / SearchDropdownPanel 供顶栏调用（internal），
 * HistoryItem / SuggestionItem 为下拉面板私有助手随迁。
 */
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.azurlane.blyy.ui.theme.*
import com.azurlane.blyy.viewmodel.GalleryIntent
import com.azurlane.blyy.viewmodel.GalleryViewState
import com.azurlane.blyy.ui.components.rememberBlyyHaptics
import com.azurlane.blyy.ui.components.BlyyHaptic
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * 搜索开关按钮 — 收起态显示放大镜（点击展开搜索），展开态显示关闭图标（点击收起）
 *
 * 与 [ModernFilterButton] 保持一致的圆形按钮规格，使顶栏右侧操作区视觉统一。
 */
@Composable
internal fun SearchToggleButton(
    expanded: Boolean,
    onClick: () -> Unit,
    isWatch: Boolean = false
) {
    val isDark = LocalIsDark.current
    val scale by animateFloatAsState(
        targetValue = if (expanded) 1.05f else 1f,
        animationSpec = AppAnimation.Specs.scale(),
        label = "SearchToggleScale"
    )
    val containerColor = if (expanded) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    } else if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = containerColor,
        modifier = Modifier
            .size(if (isWatch) 36.dp else 40.dp)
            .scale(scale)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.Close else Icons.Default.Search,
                contentDescription = if (expanded) "收起搜索" else "搜索",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(if (isWatch) AppSpacing.Icon.Sm else AppSpacing.Icon.Md)
            )
        }
    }
}

@Composable
internal fun ModernFilterButton(
    hasActiveFilters: Boolean,
    activeFilterCount: Int,
    onClick: () -> Unit,
    isWatch: Boolean = false
) {
    val isDark = LocalIsDark.current
    val buttonScale by animateFloatAsState(
        targetValue = if (hasActiveFilters) 1.05f else 1f,
        animationSpec = AppAnimation.Specs.scale(),
        label = "FilterButtonScale"
    )

    // 未激活时背景色：暗色下使用 surfaceContainerHigh 提高对比度，避免过于暗淡
    val inactiveBgColor = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    Box {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = if (hasActiveFilters) {
                MaterialTheme.colorScheme.primary
            } else {
                inactiveBgColor
            },
            modifier = Modifier
                .size(if (isWatch) 36.dp else 40.dp)
                .scale(buttonScale)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.FilterList,
                    contentDescription = "筛选",
                    tint = if (hasActiveFilters) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(if (isWatch) AppSpacing.Icon.Sm else AppSpacing.Icon.Md)
                )
            }
        }
        // 激活筛选数量徽章
        if (activeFilterCount > 0) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .size(16.dp)
                    .align(Alignment.TopEnd)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$activeFilterCount",
                        style = AppTypography.LabelSmallBold,
                        color = MaterialTheme.colorScheme.onError
                    )
                }
            }
        }
    }
}

/**
 * 搜索下拉面板 — 历史记录（输入为空时）或搜索建议（输入非空时）
 */
@Composable
internal fun SearchDropdownPanel(
    isSearchFocused: Boolean,
    searchInput: String,
    searchHistory: List<String>,
    suggestions: List<String>,
    onHistoryItemClick: (String) -> Unit,
    onSuggestionClick: (String) -> Unit,
    onClearHistory: () -> Unit,
    onRemoveHistoryItem: (String) -> Unit
) {
    val isDark = LocalIsDark.current
    val glassSurface = if (isDark) AppColors.GlassSurfaceDark else AppColors.GlassSurfaceLight

    val showHistory = isSearchFocused && searchInput.isEmpty() && searchHistory.isNotEmpty()
    val showSuggestions = isSearchFocused && searchInput.isNotEmpty() && suggestions.isNotEmpty()
    val isVisible = showHistory || showSuggestions

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(animationSpec = tween(AppAnimation.Duration.Normal)) +
            expandVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)),
        exit = fadeOut(animationSpec = tween(AppAnimation.Duration.Fast)) +
            shrinkVertically(animationSpec = tween(AppAnimation.Duration.Fast))
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Screen.Horizontal),
            shape = BlyyShapes.PanelMedium,
            color = glassSurface.copy(alpha = 0.98f),
            shadowElevation = AppSpacing.Elevation.Lg
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = AppSpacing.Sm)
            ) {
                if (showHistory) {
                    // 标题行
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Xs),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "搜索历史",
                            style = AppTypography.LabelLargeBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.clickable { onClearHistory() },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteSweep,
                                contentDescription = "清除历史",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(AppSpacing.Icon.Sm)
                            )
                            Spacer(modifier = Modifier.width(AppSpacing.Xs))
                            Text(
                                text = "清除",
                                style = AppTypography.LabelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    searchHistory.forEach { item ->
                        HistoryItem(
                            text = item,
                            onClick = { onHistoryItemClick(item) },
                            onRemove = { onRemoveHistoryItem(item) }
                        )
                    }
                }

                if (showSuggestions) {
                    // 标题行
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Xs),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "建议",
                            style = AppTypography.LabelLargeBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${suggestions.size} 个匹配",
                            style = AppTypography.LabelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }

                    suggestions.forEach { suggestion ->
                        SuggestionItem(
                            text = suggestion,
                            searchInput = searchInput,
                            onClick = { onSuggestionClick(suggestion) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryItem(
    text: String,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(AppSpacing.Icon.Sm)
        )
        Spacer(modifier = Modifier.width(AppSpacing.Md))
        Text(
            text = text,
            style = AppTypography.BodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier.minimumInteractiveComponentSize().size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(AppSpacing.Icon.Xs)
            )
        }
    }
}

@Composable
private fun SuggestionItem(
    text: String,
    searchInput: String,
    onClick: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val annotatedText = buildAnnotatedString {
        if (searchInput.isEmpty()) {
            append(text)
            return@buildAnnotatedString
        }
        val highlightStart = text.indexOf(searchInput, ignoreCase = true)
        if (highlightStart >= 0) {
            append(text.substring(0, highlightStart))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = primaryColor)) {
                append(text.substring(highlightStart, highlightStart + searchInput.length))
            }
            append(text.substring(highlightStart + searchInput.length))
        } else {
            append(text)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = primaryColor.copy(alpha = 0.7f),
            modifier = Modifier.size(AppSpacing.Icon.Sm)
        )
        Spacer(modifier = Modifier.width(AppSpacing.Md))
        Text(
            text = annotatedText,
            style = AppTypography.BodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(AppSpacing.Icon.Sm)
        )
    }
}
