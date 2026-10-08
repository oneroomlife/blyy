package com.azurlane.blyy.ui.screens.gallery

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.data.model.StudentFilterData
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyBottomSheet
import com.azurlane.blyy.ui.components.BlyyEmptyState
import com.azurlane.blyy.ui.components.ShipCard
import com.azurlane.blyy.ui.components.ShipCardShimmer
import com.azurlane.blyy.ui.theme.*
import com.azurlane.blyy.viewmodel.GalleryIntent
import com.azurlane.blyy.viewmodel.GalleryViewState
import com.azurlane.blyy.ui.components.rememberBlyyHaptics
import com.azurlane.blyy.ui.components.BlyyHaptic
import kotlin.math.roundToInt
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 自适应船坞顶部栏 — 标题栏与搜索栏合并为单一组件，按 UI 风格切换外观
 * Command Center：玻璃 HUD 面板（标题 + 搜索按钮 + 档案切换 + 渐变强调线）
 * Classic：Material 卡片（标题 + 搜索按钮 + 档案切换）
 *
 * 搜索交互为「点击展开」：收起态仅显示搜索按钮，点击后同一行切换为
 * 「搜索胶囊 + 筛选按钮 + 收起按钮」（档案切换器让位于搜索），两态均为单行等高，
 * 切换时内容区无需重排，避免网格跳动。
 *
 * 头部实际高度通过 [onHeaderHeightChanged] 上报，供内容区动态计算网格顶部内边距。
 */
@Composable
internal fun AdaptiveGalleryTopBar(
    title: String,
    totalCount: Int,
    filteredCount: Int,
    searchInput: String,
    onSearchInputChange: (String) -> Unit,
    isSearchFocused: Boolean,
    onSearchFocusChange: (Boolean) -> Unit,
    searchFocusRequester: FocusRequester,
    searchHistory: List<String>,
    onHistoryItemClick: (String) -> Unit,
    onClearHistory: () -> Unit,
    onRemoveHistoryItem: (String) -> Unit,
    onSubmitSearch: (String) -> Unit,
    suggestions: List<String>,
    onSuggestionClick: (String) -> Unit,
    onFilterClick: () -> Unit,
    hasActiveFilters: Boolean,
    activeFilterCount: Int,
    archiveType: com.azurlane.blyy.viewmodel.ArchiveType = com.azurlane.blyy.viewmodel.ArchiveType.DOCK,
    onSwitchArchive: (com.azurlane.blyy.viewmodel.ArchiveType) -> Unit = {},
    isRefreshing: Boolean = false,
    isCacheHit: Boolean = false,
    cacheTimestamp: Long = 0L,
    onHeaderHeightChanged: (Int) -> Unit = {}
) {
    val uiStyle = LocalUiStyle.current
    val isCommandCenter = uiStyle.isCommandCenter()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val entityLabel = when (archiveType) {
        com.azurlane.blyy.viewmodel.ArchiveType.DOCK -> "舰娘"
        com.azurlane.blyy.viewmodel.ArchiveType.STUDENT -> "学生"
    }

    // 搜索展开态：收起时仅显示搜索按钮，展开后切换为搜索框 + 筛选
    var isSearchExpanded by remember { mutableStateOf(false) }

    // 失焦（点击遮罩关闭下拉、选择历史/建议）时自动收起
    LaunchedEffect(isSearchFocused) {
        if (!isSearchFocused && isSearchExpanded) {
            isSearchExpanded = false
        }
    }

    val onSearchToggle: () -> Unit = {
        if (isSearchExpanded) {
            isSearchExpanded = false
            keyboardController?.hide()
            focusManager.clearFocus()
        } else {
            isSearchExpanded = true
        }
    }

    Column {
        if (isCommandCenter) {
            // Command Center 风格：标题、搜索、筛选、档案切换合并为单个 HUD 面板
            // onSizeChanged 仅测量合并面板本身（不含下拉面板/进度条），供内容区避让
            Box(modifier = Modifier.onSizeChanged { onHeaderHeightChanged(it.height) }) {
                MergedCcGalleryHeader(
                    title = title,
                    totalCount = totalCount,
                    filteredCount = filteredCount,
                    searchInput = searchInput,
                    onSearchInputChange = onSearchInputChange,
                    isSearchFocused = isSearchFocused,
                    onSearchFocusChange = onSearchFocusChange,
                    searchFocusRequester = searchFocusRequester,
                    onFilterClick = onFilterClick,
                    hasActiveFilters = hasActiveFilters,
                    activeFilterCount = activeFilterCount,
                    onSubmitSearch = onSubmitSearch,
                    archiveType = archiveType,
                    onSwitchArchive = onSwitchArchive,
                    entityLabel = entityLabel,
                    isSearchExpanded = isSearchExpanded,
                    onSearchToggle = onSearchToggle
                )
            }
        } else {
            // Classic 风格：标题、搜索、筛选、档案切换合并为单个卡片
            Box(modifier = Modifier.onSizeChanged { onHeaderHeightChanged(it.height) }) {
                ClassicGallerySearchBar(
                    title = title,
                    totalCount = totalCount,
                    filteredCount = filteredCount,
                    searchInput = searchInput,
                    onSearchInputChange = onSearchInputChange,
                    isSearchFocused = isSearchFocused,
                    onSearchFocusChange = onSearchFocusChange,
                    searchFocusRequester = searchFocusRequester,
                    onFilterClick = onFilterClick,
                    hasActiveFilters = hasActiveFilters,
                    activeFilterCount = activeFilterCount,
                    onSubmitSearch = onSubmitSearch,
                    archiveType = archiveType,
                    onSwitchArchive = onSwitchArchive,
                    entityLabel = entityLabel,
                    isSearchExpanded = isSearchExpanded,
                    onSearchToggle = onSearchToggle
                )
            }
        }

        // 后台刷新进度条 — 两风格共用，置于被测面板之外避免顶距跳动
        AnimatedVisibility(
            visible = isRefreshing,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Screen.Horizontal, vertical = AppSpacing.Xxs),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            )
        }

        // 搜索建议/历史下拉面板
        SearchDropdownPanel(
            isSearchFocused = isSearchFocused,
            searchInput = searchInput,
            searchHistory = searchHistory,
            suggestions = suggestions,
            onHistoryItemClick = onHistoryItemClick,
            onSuggestionClick = onSuggestionClick,
            onClearHistory = onClearHistory,
            onRemoveHistoryItem = onRemoveHistoryItem
        )

        Spacer(modifier = Modifier.height(AppSpacing.Md))
    }
}

/**
 * Command Center 风格合并头部 — 标题块 / 搜索胶囊 + 筛选 / 档案切换器整合为单个 HUD 面板
 *
 * 交互（点击展开）：
 * - 收起态：`标题块（含计数徽章）· 搜索按钮 · 档案切换器`
 * - 展开态：`搜索胶囊（自适应宽度）· 筛选按钮 · 搜索按钮（变为收起）`（档案切换器让位于搜索）
 * 动画：以「搜索按钮」为固定锚点，搜索胶囊与筛选按钮自其左侧水平弹开（expandHorizontally），
 * 标题块与档案切换器同步水平收起；行高锁定，全程顶栏高度不变，内容区不发生上下位移。
 * - 面板底部保留 2dp 主色→金色渐变强调线，延续指挥中心 HUD 视觉签名
 * - 聚焦搜索时面板描边高亮、阴影增强
 */
@Composable
private fun MergedCcGalleryHeader(
    title: String,
    totalCount: Int,
    filteredCount: Int,
    searchInput: String,
    onSearchInputChange: (String) -> Unit,
    isSearchFocused: Boolean,
    onSearchFocusChange: (Boolean) -> Unit,
    searchFocusRequester: FocusRequester,
    onFilterClick: () -> Unit,
    hasActiveFilters: Boolean,
    activeFilterCount: Int,
    onSubmitSearch: (String) -> Unit,
    archiveType: com.azurlane.blyy.viewmodel.ArchiveType,
    onSwitchArchive: (com.azurlane.blyy.viewmodel.ArchiveType) -> Unit,
    entityLabel: String = "舰娘",
    isSearchExpanded: Boolean = false,
    onSearchToggle: () -> Unit = {}
) {
    val isDark = LocalIsDark.current
    val isWatch = isWatchScreen()

    val glassSurface = if (isDark) AppColors.GlassSurfaceDark else AppColors.GlassSurfaceLight
    val glassBorder = if (isDark) AppColors.GlassBorderDark else AppColors.GlassBorderLight
    val accentColor = MaterialTheme.colorScheme.primary

    // 聚焦时边框高亮
    val borderAlpha by animateFloatAsState(
        targetValue = if (isSearchFocused) 1f else 0.5f,
        animationSpec = AppAnimation.Specs.fast(),
        label = "BorderAlpha"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Screen.Horizontal),
            shape = BlyyShapes.PanelMedium,
            color = glassSurface,
            shadowElevation = if (isSearchFocused) AppElevation.Level3 else AppElevation.Level2,
            border = androidx.compose.foundation.BorderStroke(
                width = AppSpacing.Border.Thin,
                brush = Brush.linearGradient(
                    colors = listOf(
                        glassBorder.copy(alpha = borderAlpha),
                        glassBorder.copy(alpha = borderAlpha * 0.2f)
                    )
                ),
            )
        ) {
            // 行高由左侧自适应区的最小高度锁定（见 GalleryLeftAdaptiveRegion），
            // 此处仅留垂直内边距，保证切换全程顶栏高度恒定、内容区不发生上下位移
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AppSpacing.Padding.InputHorizontal,
                        vertical = AppSpacing.Sm
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ── 左侧自适应区 ──
                // 收起 = 标题块，展开 = 搜索胶囊 + 筛选按钮；
                // 两态叠放并共用同一右端锚点，均自「搜索按钮」处向左水平弹开。
                GalleryLeftAdaptiveRegion(
                    isSearchExpanded = isSearchExpanded,
                    modifier = Modifier.weight(1f),
                    titleBlock = {
                        GalleryHeaderTitleBlock(
                            title = title,
                            totalCount = totalCount,
                            filteredCount = filteredCount,
                            entityLabel = entityLabel,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    searchBlock = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GallerySearchFieldContent(
                                modifier = Modifier.weight(1f),
                                searchInput = searchInput,
                                onSearchInputChange = onSearchInputChange,
                                onSearchFocusChange = onSearchFocusChange,
                                searchFocusRequester = searchFocusRequester,
                                onSubmitSearch = onSubmitSearch,
                                entityLabel = entityLabel,
                                isFocused = isSearchFocused,
                                isWatch = isWatch,
                                autoFocusOnAppear = true
                            )
                            Spacer(modifier = Modifier.width(AppSpacing.Sm))
                            ModernFilterButton(
                                hasActiveFilters = hasActiveFilters,
                                activeFilterCount = activeFilterCount,
                                onClick = onFilterClick,
                                isWatch = isWatch
                            )
                        }
                    }
                )

                Spacer(modifier = Modifier.width(AppSpacing.Sm))

                // 锚点按钮 — 展开/收起均在此位置，搜索与筛选自其左侧弹出
                SearchToggleButton(
                    expanded = isSearchExpanded,
                    onClick = onSearchToggle,
                    isWatch = isWatch
                )

                // 档案切换器 — 仅收起态显示，展开时向右水平收起让位于搜索
                // 过渡与左侧区域共用 HeaderRevealEnter/Exit，动画曲线与时长完全一致
                AnimatedVisibility(
                    visible = !isSearchExpanded,
                    enter = HeaderRevealEnter,
                    exit = HeaderRevealExit
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(modifier = Modifier.width(AppSpacing.Md))
                        CompactArchiveSwitcher(
                            archiveType = archiveType,
                            onSwitchArchive = onSwitchArchive
                        )
                    }
                }
            }
        }

        // 顶部栏视觉签名：面板底部 2dp 主色→金色强调渐变线
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Screen.Horizontal)
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
 * 左侧自适应区最小高度（兜底值）
 *
 * 高度锚点本体是常驻组合的标题块（自然高度 ≈ `TitleLarge` 28sp + `LabelLarge` 20sp = 48dp，
 * 高于搜索胶囊 44dp，且随系统字体缩放自适应）。区域高度全程等于标题块自然高度，
 * 此下限仅作兜底，防止极端情况下区域高度低于行高预期。
 */
private val HeaderAdaptiveRegionMinHeight = 50.dp

/** 展开时长 — 尺寸与滑动共用同曲线同时长，形成"揭示边缘推着内容走"的视差 */
private const val HeaderRevealDurationMs = AppAnimation.Duration.Normal

/** 收拢时长 — 快于展开，收起干脆 */
private const val HeaderHideDurationMs = AppAnimation.Duration.Fast

/** 内容淡出时长 — 明显快于尺寸收拢，旧内容先退场，避免两段文字交叠发糊 */
private const val HeaderFadeOutDurationMs = 120

/**
 * 弹开进入过渡 — 三层动作叠加：
 * 1. 尺寸自右向左展开（减速曲线：起步快、落位柔）
 * 2. 内容带 20% 轻微右→左滑动视差，如同从「搜索按钮」下方滑出
 * 3. 淡入延迟 60ms 再启动，让揭示边缘先走一段，消除"瞬间整块浮现"的生硬感
 */
private val HeaderRevealEnter: EnterTransition =
    expandHorizontally(
        expandFrom = Alignment.End,
        animationSpec = tween(HeaderRevealDurationMs, easing = AppAnimation.Easings.EmphasizedDecelerate)
    ) + slideInHorizontally(
        initialOffsetX = { it / 5 },
        animationSpec = tween(HeaderRevealDurationMs, easing = AppAnimation.Easings.EmphasizedDecelerate)
    ) + fadeIn(
        tween(durationMillis = 240, delayMillis = 60, easing = AppAnimation.Easings.EmphasizedDecelerate)
    )

/** 收拢退出过渡 — 内容先快速淡出并轻微滑向锚点，尺寸随后向右收拢（加速曲线，利落退场） */
private val HeaderRevealExit: ExitTransition =
    fadeOut(tween(HeaderFadeOutDurationMs, easing = AppAnimation.Easings.EmphasizedAccelerate)) +
        slideOutHorizontally(
            targetOffsetX = { it / 5 },
            animationSpec = tween(HeaderHideDurationMs, easing = AppAnimation.Easings.EmphasizedAccelerate)
        ) + shrinkHorizontally(
            shrinkTowards = Alignment.End,
            animationSpec = tween(HeaderHideDurationMs, easing = AppAnimation.Easings.EmphasizedAccelerate)
        )

/**
 * 顶部栏左侧自适应区 — 标题块与「搜索胶囊 + 筛选按钮」两态叠放于同一区域
 *
 * 两态共用同一右端锚点：收起态标题块自右向左收起，展开态搜索胶囊与筛选按钮自右向左弹开，
 * 视觉上搜索栏如同从右侧「搜索按钮」处向左展开。
 *
 * 顶栏高度恒定的关键：标题块「常驻组合」—— 只用透明度与自定义水平收拢动画切换可见性，
 * 而非 [AnimatedVisibility]（其退出完成后会把内容移出组合，区域高度回落到搜索胶囊高度，
 * 与标题块自然高度不一致 → 实测头部高度变化 → 网格顶部内边距重算 → 顶栏抖动）。
 * 区域高度全程等于常驻标题块的自然高度，任何字体缩放下均恒定，切换全程顶栏高度不变。
 */
@Composable
private fun GalleryLeftAdaptiveRegion(
    isSearchExpanded: Boolean,
    titleBlock: @Composable () -> Unit,
    searchBlock: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    // 高度稳定性关键：标题块「常驻组合」作为区域高度锚点 —— 只用透明度 + 自定义水平收拢
    // 切换可见性，而不是 AnimatedVisibility（后者退出完成后会把内容移出组合，区域高度会
    // 回落到搜索胶囊高度，与标题块自然高度不一致 → 实测头部高度变化 → 顶栏抖动）。
    val titleVisible = !isSearchExpanded
    val titleProgress by animateFloatAsState(
        targetValue = if (titleVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (titleVisible) HeaderRevealDurationMs else HeaderHideDurationMs,
            delayMillis = if (titleVisible) 60 else 0,
            easing = if (titleVisible) AppAnimation.Easings.EmphasizedDecelerate
            else AppAnimation.Easings.EmphasizedAccelerate
        ),
        label = "TitleCollapseProgress"
    )
    val titleAlpha by animateFloatAsState(
        targetValue = if (titleVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (titleVisible) 240 else HeaderFadeOutDurationMs,
            delayMillis = if (titleVisible) 60 else 0,
            easing = if (titleVisible) AppAnimation.Easings.EmphasizedDecelerate
            else AppAnimation.Easings.EmphasizedAccelerate
        ),
        label = "TitleCollapseAlpha"
    )
    Box(
        modifier = modifier.heightIn(min = HeaderAdaptiveRegionMinHeight),
        contentAlignment = Alignment.CenterStart
    ) {
        // 标题块 — 常驻组合，区域高度锚点；宽度按 titleProgress 右端锚定收放
        // （等价于 shrinkHorizontally(End)，但内容永不离开组合，高度测量恒定）
        Box(
            modifier = Modifier
                .graphicsLayer { alpha = titleAlpha }
                .clipToBounds()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val width = (placeable.width * titleProgress).roundToInt()
                    layout(width, placeable.height) {
                        // 右端锚定：可见窗口自右向左收放，超出窗口左缘的内容被裁切
                        placeable.placeRelative(x = width - placeable.width, y = 0)
                    }
                }
        ) {
            titleBlock()
        }

        AnimatedVisibility(
            visible = isSearchExpanded,
            enter = HeaderRevealEnter,
            exit = HeaderRevealExit
        ) {
            searchBlock()
        }
    }
}

/**
 * 合并头部的标题块：主色装饰条 + 标题 + 总数（含筛选命中徽章）
 */
@Composable
private fun GalleryHeaderTitleBlock(
    title: String,
    totalCount: Int,
    filteredCount: Int,
    entityLabel: String,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(32.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        )
                    )
                )
        )
        Spacer(modifier = Modifier.width(AppSpacing.Sm))
        Column {
            Text(
                text = title,
                style = AppTypography.TitleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "共 $totalCount 位$entityLabel",
                    style = AppTypography.LabelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (filteredCount != totalCount) {
                    Spacer(modifier = Modifier.width(AppSpacing.Sm))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "$filteredCount/$totalCount",
                            style = AppTypography.LabelSmallBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = AppSpacing.Sm, vertical = AppSpacing.Xxs)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 搜索输入行（合并头部共用）— 内嵌胶囊输入框
 *
 * 设计：
 * - 圆角胶囊外形，内含前置搜索图标 + 输入区 + 动画清除按钮，明确"这是一个可输入框"
 * - 未聚焦：中性薄底 + 淡描边；聚焦：主色薄纱底 + 主色描边，形成清晰焦点反馈
 * - 前置图标：空文字时点击聚焦并唤起键盘，有文字时点击直接提交搜索
 */
@Composable
private fun GallerySearchFieldContent(
    searchInput: String,
    onSearchInputChange: (String) -> Unit,
    onSearchFocusChange: (Boolean) -> Unit,
    searchFocusRequester: FocusRequester,
    onSubmitSearch: (String) -> Unit,
    entityLabel: String,
    isFocused: Boolean,
    modifier: Modifier = Modifier,
    isWatch: Boolean = false,
    autoFocusOnAppear: Boolean = false
) {
    val isDark = LocalIsDark.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusInput: () -> Unit = {
        searchFocusRequester.requestFocus()
        keyboardController?.show()
    }
    val capsuleShape = RoundedCornerShape(AppSpacing.Corner.Full)

    // 展开时自动聚焦并唤起键盘（仅在该输入框首次进入组合时执行一次）
    LaunchedEffect(Unit) {
        if (autoFocusOnAppear) {
            // 等待淡入/布局完成，确保输入框节点已挂载
            delay(60)
            runCatching { searchFocusRequester.requestFocus() }
            keyboardController?.show()
        }
    }

    // 聚焦时底色升为"主色薄纱"，未聚焦为中性薄底
    val containerColor by animateColorAsState(
        targetValue = if (isFocused) {
            MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.18f else 0.10f)
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.06f else 0.04f)
        },
        animationSpec = AppAnimation.Specs.fast(),
        label = "SearchCapsuleBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.70f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f)
        },
        animationSpec = AppAnimation.Specs.fast(),
        label = "SearchCapsuleBorder"
    )

    Row(
        modifier = modifier
            .height(if (isWatch) 36.dp else 44.dp)
            .clip(capsuleShape)
            .background(containerColor)
            .border(AppSpacing.Border.Thin, borderColor, capsuleShape)
            .padding(horizontal = AppSpacing.Md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 搜索图标 — 空文字时点击聚焦，有文字时点击提交
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = if (searchInput.isEmpty()) "搜索" else "搜索$searchInput",
            tint = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(if (isWatch) AppSpacing.Icon.Sm else AppSpacing.Icon.Md)
                .clickable {
                    if (searchInput.isEmpty()) {
                        focusInput()
                    } else {
                        onSubmitSearch(searchInput)
                        keyboardController?.hide()
                    }
                }
        )

        Spacer(modifier = Modifier.width(AppSpacing.Sm))

        // 输入区域
        Box(
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "搜索$entityLabel" },
            contentAlignment = Alignment.CenterStart
        ) {
            if (searchInput.isEmpty()) {
                Text(
                    text = "搜索$entityLabel…",
                    style = AppTypography.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = searchInput,
                onValueChange = onSearchInputChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(searchFocusRequester)
                    .onFocusChanged { onSearchFocusChange(it.isFocused) },
                textStyle = AppTypography.BodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { onSubmitSearch(searchInput) }
                )
            )
        }

        // 清除按钮 — 带动画进出
        AnimatedVisibility(
            visible = searchInput.isNotEmpty(),
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally()
        ) {
            Box(
                modifier = Modifier
                    .padding(start = AppSpacing.Xs)
                    .size(if (isWatch) 22.dp else 26.dp)
                    .clip(CircleShape)
                    .clickable {
                        onSearchInputChange("")
                        searchFocusRequester.requestFocus()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "清除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(AppSpacing.Icon.Sm)
                )
            }
        }
    }
}

/**
 * 经典风格船坞顶部栏 — 单卡片合并结构
 *
 * 交互（点击展开）：
 * - 收起态：`标题块（标题 + 计数）· 搜索按钮 · 档案切换器`
 * - 展开态：`搜索胶囊 · 筛选按钮 · 搜索按钮（变为收起）`（档案切换器让位于搜索）
 * 动画：以「搜索按钮」为固定锚点，搜索胶囊与筛选按钮自其左侧水平弹开（expandHorizontally），
 * 标题块与档案切换器同步水平收起；行高锁定，全程卡片高度不变，内容区不发生上下位移。
 *
 * 设计优化：
 * - 卡片使用 surfaceContainerHigh 背景 + outlineVariant 描边，与 ClassicTopBar 配色协调
 * - 标题复用 [GalleryHeaderTitleBlock]（主色装饰条 + 标题 + 计数徽章），两风格标题表现统一
 * - 搜索复用共享的胶囊输入框，交互与观感与 Command Center 风格一致
 * - 聚焦时卡片描边过渡到主色并抬升阴影；暗色下提高对比度确保文字清晰
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClassicGallerySearchBar(
    title: String,
    totalCount: Int,
    filteredCount: Int,
    searchInput: String,
    onSearchInputChange: (String) -> Unit,
    isSearchFocused: Boolean,
    onSearchFocusChange: (Boolean) -> Unit,
    searchFocusRequester: FocusRequester,
    onFilterClick: () -> Unit,
    hasActiveFilters: Boolean,
    activeFilterCount: Int,
    onSubmitSearch: (String) -> Unit,
    archiveType: com.azurlane.blyy.viewmodel.ArchiveType = com.azurlane.blyy.viewmodel.ArchiveType.DOCK,
    onSwitchArchive: (com.azurlane.blyy.viewmodel.ArchiveType) -> Unit = {},
    entityLabel: String = "舰娘",
    isSearchExpanded: Boolean = false,
    onSearchToggle: () -> Unit = {}
) {
    val isDark = LocalIsDark.current
    val isWatch = isWatchScreen()

    // 聚焦时卡片描边高亮（未聚焦时保留淡描边，避免突兀）
    val searchBorderAlpha by animateFloatAsState(
        targetValue = if (isSearchFocused) 1f else 0.4f,
        animationSpec = AppAnimation.Specs.fast(),
        label = "SearchBorderAlpha"
    )

    // 合并卡片 — surfaceContainerHigh 背景 + 主色描边过渡 + 档案切换器
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Screen.Horizontal),
        shape = RoundedCornerShape(AppSpacing.Corner.Lg),
        // 全不透明：半透明卡片叠在 surfaceContainer 灰背景上会发浑，
        // 且网格滚动时卡片从顶栏后方穿过会透出"脏色"
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(
            width = AppSpacing.Border.Thin,
            color = lerp(
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.5f else 0.4f),
                MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                searchBorderAlpha
            )
        ),
        shadowElevation = if (isSearchFocused) AppSpacing.Elevation.Md else AppSpacing.Elevation.Sm
    ) {
        // 行高由左侧自适应区的最小高度锁定（见 GalleryLeftAdaptiveRegion），
        // 此处仅留内边距，保证切换全程卡片高度恒定、内容区不发生上下位移
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ── 左侧自适应区 ──
            // 收起 = 标题块，展开 = 搜索胶囊 + 筛选按钮；
            // 两态叠放并共用同一右端锚点，均自「搜索按钮」处向左水平弹开。
            GalleryLeftAdaptiveRegion(
                isSearchExpanded = isSearchExpanded,
                modifier = Modifier.weight(1f),
                titleBlock = {
                    GalleryHeaderTitleBlock(
                        title = title,
                        totalCount = totalCount,
                        filteredCount = filteredCount,
                        entityLabel = entityLabel,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                searchBlock = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GallerySearchFieldContent(
                            modifier = Modifier.weight(1f),
                            searchInput = searchInput,
                            onSearchInputChange = onSearchInputChange,
                            onSearchFocusChange = onSearchFocusChange,
                            searchFocusRequester = searchFocusRequester,
                            onSubmitSearch = onSubmitSearch,
                            entityLabel = entityLabel,
                            isFocused = isSearchFocused,
                            isWatch = isWatch,
                            autoFocusOnAppear = true
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.Sm))
                        ModernFilterButton(
                            hasActiveFilters = hasActiveFilters,
                            activeFilterCount = activeFilterCount,
                            onClick = onFilterClick,
                            isWatch = isWatch
                        )
                    }
                }
            )

            Spacer(modifier = Modifier.width(AppSpacing.Sm))

            // 锚点按钮 — 展开/收起均在此位置，搜索与筛选自其左侧弹出
            SearchToggleButton(
                expanded = isSearchExpanded,
                onClick = onSearchToggle,
                isWatch = isWatch
            )

            // 档案切换器 — 仅收起态显示，展开时向右水平收起让位于搜索
            // 过渡与左侧区域共用 HeaderRevealEnter/Exit，动画曲线与时长完全一致
            AnimatedVisibility(
                visible = !isSearchExpanded,
                enter = HeaderRevealEnter,
                exit = HeaderRevealExit
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(AppSpacing.Md))
                    CompactArchiveSwitcher(
                        archiveType = archiveType,
                        onSwitchArchive = onSwitchArchive
                    )
                }
            }
        }
    }
}

/**
 * 搜索开关按钮 — 收起态显示放大镜（点击展开搜索），展开态显示关闭图标（点击收起）
 *
 * 与 [ModernFilterButton] 保持一致的圆形按钮规格，使顶栏右侧操作区视觉统一。
 */
@Composable
private fun SearchToggleButton(
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
private fun ModernFilterButton(
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
private fun SearchDropdownPanel(
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
            modifier = Modifier.size(28.dp)
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

// ── 搜索历史持久化 ──

private const val SEARCH_HISTORY_PREFS = "gallery_search"
private const val SEARCH_HISTORY_KEY = "history"
private const val SEARCH_HISTORY_MAX = 10
private const val SEARCH_HISTORY_DELIMITER = "\n"

internal data class SearchHistoryState(
    val history: List<String>,
    val add: (String) -> Unit,
    val remove: (String) -> Unit,
    val clear: () -> Unit
)

/**
 * 搜索历史管理 — 基于 SharedPreferences 持久化
 * 使用换行符分隔保持顺序，最多保留 10 条
 */
@Composable
internal fun rememberSearchHistory(
    maxItems: Int = SEARCH_HISTORY_MAX
): SearchHistoryState {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(SEARCH_HISTORY_PREFS, 0) }
    val historyState = remember {
        mutableStateOf(
            prefs.getString(SEARCH_HISTORY_KEY, "")
                ?.split(SEARCH_HISTORY_DELIMITER)
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        )
    }

    return remember(historyState.value) {
        SearchHistoryState(
            history = historyState.value,
            add = { query ->
                if (query.isBlank()) return@SearchHistoryState
                val updated = (listOf(query) + historyState.value.filter { it != query }).take(maxItems)
                historyState.value = updated
                prefs.edit().putString(SEARCH_HISTORY_KEY, updated.joinToString(SEARCH_HISTORY_DELIMITER)).apply()
            },
            remove = { query ->
                val updated = historyState.value.filter { it != query }
                historyState.value = updated
                prefs.edit().putString(SEARCH_HISTORY_KEY, updated.joinToString(SEARCH_HISTORY_DELIMITER)).apply()
            },
            clear = {
                historyState.value = emptyList()
                prefs.edit().remove(SEARCH_HISTORY_KEY).apply()
            }
        )
    }
}
/**
 * 紧凑型档案切换器 — 整合到顶部栏 actions 中
 *
 * 设计要点：
 * - 使用图标 + 文字的紧凑布局，节省垂直空间
 * - 选中项带主色背景 + 白色文字，未选中项透明背景
 * - 切换时带缩放动画，提升交互反馈
 * - 适配手表等小屏幕设备
 */
@Composable
private fun CompactArchiveSwitcher(
    archiveType: com.azurlane.blyy.viewmodel.ArchiveType,
    onSwitchArchive: (com.azurlane.blyy.viewmodel.ArchiveType) -> Unit
) {
    val isDark = LocalIsDark.current
    val isWatch = isWatchScreen()
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val accentColor = MaterialTheme.colorScheme.primary

    val tabs = listOf(
        com.azurlane.blyy.viewmodel.ArchiveType.DOCK to "舰娘",
        com.azurlane.blyy.viewmodel.ArchiveType.STUDENT to "学生"
    )

    // 根据 UI 风格切换容器配色：
    // - Command Center：HUD 玻璃面板色（AppColors.Panel）
    // - Classic：Material Design surfaceContainerHigh，与 ClassicTopBar 协调
    val containerColor = if (isCommandCenter) {
        if (isDark) AppColors.Panel.Dark.copy(alpha = 0.6f) else AppColors.Panel.Light.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isDark) 0.85f else 0.7f)
    }
    val containerBorderColor = if (isCommandCenter) {
        accentColor.copy(alpha = 0.3f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    }
    // 选中态：Command Center 用 primary + White 文字；Classic 用 primaryContainer + onPrimaryContainer
    val selectedBgColor = if (isCommandCenter) accentColor else MaterialTheme.colorScheme.primaryContainer
    val selectedTextColor = if (isCommandCenter) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
    val unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        shape = RoundedCornerShape(AppSpacing.Corner.Full),
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(
            width = AppSpacing.Border.Thin,
            color = containerBorderColor
        )
    ) {
        Row(
            modifier = Modifier.padding(AppSpacing.Xxs)
        ) {
            tabs.forEach { (type, label) ->
                val isSelected = archiveType == type
                val targetColor = if (isSelected) selectedBgColor else Color.Transparent
                val textColor = if (isSelected) selectedTextColor else unselectedTextColor
                val scale by animateFloatAsState(
                    targetValue = if (isSelected) 1f else 0.95f,
                    animationSpec = AppAnimation.Specs.scale(),
                    label = "ArchiveTabScale"
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppSpacing.Corner.Full))
                        .background(targetColor)
                        .clickable { onSwitchArchive(type) }
                        .padding(
                            horizontal = if (isWatch) AppSpacing.Xs else AppSpacing.Sm,
                            vertical = AppSpacing.Xxs
                        )
                        .scale(scale),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = AppTypography.LabelMedium,
                        color = textColor,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
