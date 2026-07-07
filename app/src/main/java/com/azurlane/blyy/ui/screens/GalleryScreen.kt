package com.azurlane.blyy.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.components.ShipCard
import com.azurlane.blyy.ui.components.ShipCardShimmer
import com.azurlane.blyy.ui.theme.*
import com.azurlane.blyy.viewmodel.GalleryIntent
import com.azurlane.blyy.viewmodel.GalleryViewState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class, FlowPreview::class)
@Composable
fun GalleryScreen(
    state: GalleryViewState,
    filteredShips: List<Ship>,
    onIntent: (GalleryIntent) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    onShipClick: (Ship) -> Unit,
    onShowGallery: (Ship) -> Unit,
    onScrollStateChange: (isScrolling: Boolean) -> Unit = { _ -> }
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var showFilterSheet by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val allowDecorAnimation by remember {
        derivedStateOf { !gridState.isScrollInProgress }
    }

    // ── 搜索状态管理 ──
    var searchInput by rememberSaveable { mutableStateOf(state.searchQuery) }
    val searchFocusRequester = remember { FocusRequester() }
    var isSearchFocused by remember { mutableStateOf(false) }
    val searchHistory = rememberSearchHistory()

    // 搜索建议 — 从舰娘名称中实时匹配
    val suggestions by remember(state.ships, searchInput) {
        derivedStateOf {
            if (searchInput.isBlank()) emptyList()
            else state.ships
                .map { it.name }
                .filter { it.contains(searchInput, ignoreCase = true) && !it.equals(searchInput, ignoreCase = true) }
                .distinct()
                .take(5)
        }
    }

    // 实时搜索防抖 — 150ms 延迟，平衡响应性与性能
    LaunchedEffect(Unit) {
        snapshotFlow { searchInput }
            .debounce(150)
            .distinctUntilChanged()
            .collectLatest { query ->
                onIntent(GalleryIntent.Search(query))
            }
    }

    var isTopBarVisible by remember { mutableStateOf(true) }

    val topBarOffset by animateFloatAsState(
        targetValue = if (isTopBarVisible) 0f else AppSpacing.TopBar.HideOffsetPx,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "topBarOffset"
    )
    val topBarAlpha by animateFloatAsState(
        targetValue = if (isTopBarVisible) 1f else 0f,
        animationSpec = tween(durationMillis = AppAnimation.Duration.Fast, easing = LinearEasing),
        label = "topBarAlpha"
    )

    LaunchedEffect(gridState) {
        var lastIsScrollingState = false
        snapshotFlow {
            Triple(gridState.firstVisibleItemScrollOffset, gridState.firstVisibleItemIndex, gridState.isScrollInProgress)
        }.collectLatest { (offset, index, isScrolling) ->
            val isAtTop = index == 0 && offset < 50
            if (isScrolling && !isAtTop) {
                if (isTopBarVisible) isTopBarVisible = false
                if (!lastIsScrollingState) {
                    onScrollStateChange(true)
                    lastIsScrollingState = true
                }
            } else if (!isScrolling) {
                delay(200L)
                if (!isTopBarVisible) isTopBarVisible = true
                if (lastIsScrollingState) {
                    onScrollStateChange(false)
                    lastIsScrollingState = false
                }
            } else if (isAtTop) {
                if (!isTopBarVisible) isTopBarVisible = true
                if (lastIsScrollingState) {
                    onScrollStateChange(false)
                    lastIsScrollingState = false
                }
            }
        }
    }

    // 使用 remember 缓存筛选选项列表，避免每次重组时重复创建
    val allFactions = remember(state.archiveType) {
        when (state.archiveType) {
            com.azurlane.blyy.viewmodel.ArchiveType.DOCK ->
                listOf("全部", "白鹰", "皇家", "重樱", "铁血", "东煌", "撒丁帝国", "北方联合", "自由鸢尾", "维希教廷", "郁金王国", "META", "其他", "飓风")
            com.azurlane.blyy.viewmodel.ArchiveType.STUDENT ->
                listOf("全部", "蔚蓝档案")
        }
    }
    val allTypes = remember(state.archiveType) {
        when (state.archiveType) {
            com.azurlane.blyy.viewmodel.ArchiveType.DOCK ->
                listOf("全部", "前排先锋", "后排主力", "驱逐", "轻巡", "重巡", "超巡", "战巡", "战列", "航战", "航母", "轻航", "重炮", "维修", "潜艇", "潜母", "运输", "风帆")
            com.azurlane.blyy.viewmodel.ArchiveType.STUDENT ->
                listOf("全部", "学生")
        }
    }
    val allRarities = remember(state.archiveType) {
        when (state.archiveType) {
            com.azurlane.blyy.viewmodel.ArchiveType.DOCK ->
                listOf("全部", "海上传奇", "决战方案", "超稀有", "最高方案", "精锐", "稀有", "普通")
            com.azurlane.blyy.viewmodel.ArchiveType.STUDENT ->
                listOf("全部", "三星")
        }
    }

    val activeFilterCount = remember(state.selectedFaction, state.selectedType, state.selectedRarity) {
        var count = 0
        if (state.selectedFaction != "全部") count++
        if (state.selectedType != "全部") count++
        if (state.selectedRarity != "全部") count++
        count
    }

    // 下拉面板是否可见 — 用于显示遮罩
    val isDropdownVisible = isSearchFocused && isTopBarVisible &&
        ((searchInput.isEmpty() && searchHistory.history.isNotEmpty()) ||
         (searchInput.isNotEmpty() && suggestions.isNotEmpty()))

    fun openWiki(ship: Ship) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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

    AdaptiveScreenBackground(
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
    ) {
        val fixedTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
            AppSpacing.TopBar.ContentTopPadding
        val fixedBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
            AppSpacing.TopBar.ContentBottomPadding

        when {
            // 1. 首次加载中（无数据）→ 全屏 shimmer
            state.isLoading && filteredShips.isEmpty() -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = AppSpacing.Card.MinWidth),
                    contentPadding = PaddingValues(
                        start = AppSpacing.Screen.Horizontal,
                        end = AppSpacing.Screen.Horizontal,
                        top = fixedTopPadding,
                        bottom = fixedBottomPadding
                    ),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Gap.CardGrid),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Gap.CardGrid),
                    modifier = Modifier.fillMaxSize(),
                    state = gridState
                ) {
                    items(10) { ShipCardShimmer() }
                }
            }
            // 2. 无数据 + 有错误 → 全屏错误状态
            !state.isLoading && state.error != null && filteredShips.isEmpty() -> {
                GalleryErrorState(
                    message = state.error!!,
                    onRetry = { onIntent(GalleryIntent.ForceRefresh) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = fixedTopPadding)
                )
            }
            // 3. 无数据 + 无错误 → 空状态
            !state.isLoading && state.error == null && filteredShips.isEmpty() -> {
                GalleryEmptyState(
                    message = if (state.searchQuery.isNotBlank()) "未找到匹配的结果" else "暂无数据，点击刷新",
                    onRetry = { onIntent(GalleryIntent.ForceRefresh) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = fixedTopPadding)
                )
            }
            // 4. 有数据（可能同时有错误）→ 显示网格 + 顶部错误横幅
            else -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = AppSpacing.Card.MinWidth),
                        contentPadding = PaddingValues(
                            start = AppSpacing.Screen.Horizontal,
                            end = AppSpacing.Screen.Horizontal,
                            top = if (state.error != null) fixedTopPadding + AppSpacing.Lg else fixedTopPadding,
                            bottom = fixedBottomPadding
                        ),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Gap.CardGrid),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Gap.CardGrid),
                        modifier = Modifier.fillMaxSize(),
                        state = gridState
                    ) {
                        itemsIndexed(items = filteredShips, key = { _, ship -> ship.name }) { index, ship ->
                            ShipCard(
                                ship = ship,
                                decorativeAnimation = allowDecorAnimation,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onShipClick(ship)
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onIntent(GalleryIntent.ToggleFavorite(ship))
                                    Toast.makeText(context, if (ship.isFavorite) "已解除与${ship.name}的誓约" else "已与${ship.name}誓约", Toast.LENGTH_SHORT).show()
                                },
                                onWikiClick = { openWiki(ship) },
                                onOathClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onIntent(GalleryIntent.ToggleFavorite(ship))
                                    Toast.makeText(context, if (ship.isFavorite) "已解除与${ship.name}的誓约" else "已与${ship.name}誓约", Toast.LENGTH_SHORT).show()
                                },
                                onGalleryClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onShowGallery(ship)
                                },
                                modifier = with(sharedTransitionScope) {
                                    Modifier.sharedElement(
                                        sharedContentState = rememberSharedContentState(key = "avatar-${ship.name}"),
                                        animatedVisibilityScope = animatedContentScope
                                    )
                                }
                            )
                        }
                    }
                    // 有缓存数据但刷新失败 → 非侵入式错误横幅
                    if (state.error != null) {
                        GalleryErrorBanner(
                            message = "刷新失败，显示缓存数据",
                            onRetry = { onIntent(GalleryIntent.ForceRefresh) },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = fixedTopPadding)
                                .padding(horizontal = AppSpacing.Screen.Horizontal)
                        )
                    }
                }
            }
        }

        // 搜索聚焦遮罩 — 点击外部关闭下拉面板
        if (isDropdownVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            focusManager.clearFocus()
                        }
                    }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationY = topBarOffset
                    alpha = topBarAlpha
                }
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            AdaptiveGalleryTopBar(
                title = when (state.archiveType) {
                    com.azurlane.blyy.viewmodel.ArchiveType.DOCK -> "舰娘档案"
                    com.azurlane.blyy.viewmodel.ArchiveType.STUDENT -> "学生档案"
                },
                totalCount = state.ships.size,
                filteredCount = filteredShips.size,
                searchInput = searchInput,
                onSearchInputChange = { searchInput = it },
                isSearchFocused = isSearchFocused,
                onSearchFocusChange = { isSearchFocused = it },
                searchFocusRequester = searchFocusRequester,
                searchHistory = searchHistory.history,
                onHistoryItemClick = { query ->
                    searchInput = query
                    searchHistory.add(query)
                    keyboardController?.hide()
                    focusManager.clearFocus()
                },
                onClearHistory = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    searchHistory.clear()
                },
                onRemoveHistoryItem = { query ->
                    searchHistory.remove(query)
                },
                onSubmitSearch = { query ->
                    if (query.isNotBlank()) {
                        searchHistory.add(query)
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    }
                },
                suggestions = suggestions,
                onSuggestionClick = { suggestion ->
                    searchInput = suggestion
                    searchHistory.add(suggestion)
                    keyboardController?.hide()
                    focusManager.clearFocus()
                },
                onFilterClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    showFilterSheet = true
                },
                hasActiveFilters = activeFilterCount > 0,
                activeFilterCount = activeFilterCount,
                archiveType = state.archiveType,
                onSwitchArchive = { newType ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onIntent(GalleryIntent.SwitchArchive(newType))
                },
                isRefreshing = state.isRefreshing,
                isCacheHit = state.isCacheHit,
                cacheTimestamp = state.cacheTimestamp
            )
        }
    }

    if (showFilterSheet) {
        if (state.archiveType == com.azurlane.blyy.viewmodel.ArchiveType.STUDENT) {
            StudentFilterBottomSheet(
                onDismiss = { showFilterSheet = false },
                studentFilters = state.studentFilters,
                onFilterSelected = { key, value ->
                    onIntent(GalleryIntent.FilterStudent(key, value))
                },
                onReset = { onIntent(GalleryIntent.ResetStudentFilters) }
            )
        } else {
            ModernFilterBottomSheet(
                onDismiss = { showFilterSheet = false },
                selectedFaction = state.selectedFaction,
                selectedType = state.selectedType,
                selectedRarity = state.selectedRarity,
                onFactionSelected = { onIntent(GalleryIntent.FilterFaction(it)) },
                onTypeSelected = { onIntent(GalleryIntent.FilterType(it)) },
                onRaritySelected = { onIntent(GalleryIntent.FilterRarity(it)) },
                allFactions = allFactions,
                allTypes = allTypes,
                allRarities = allRarities
            )
        }
    }
}

/**
 * 自适应船坞顶部栏 — 根据UI风格自动切换布局
 * Command Center：BlyyTopBar + 玻璃搜索栏（HUD风格）
 * Classic：集成式 Material Design 顶栏 + 搜索框
 */
@Composable
private fun AdaptiveGalleryTopBar(
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
    cacheTimestamp: Long = 0L
) {
    val uiStyle = LocalUiStyle.current
    val isCommandCenter = uiStyle.isCommandCenter()
    val entityLabel = when (archiveType) {
        com.azurlane.blyy.viewmodel.ArchiveType.DOCK -> "舰娘"
        com.azurlane.blyy.viewmodel.ArchiveType.STUDENT -> "学生"
    }

    Column {
        if (isCommandCenter) {
            // Command Center 风格：档案切换器整合到顶部栏 actions 中
            BlyyTopBar(
                title = title,
                subtitle = "共 $totalCount 位$entityLabel"
            ) {
                // 顶部栏右侧：紧凑型档案切换器
                CompactArchiveSwitcher(
                    archiveType = archiveType,
                    onSwitchArchive = onSwitchArchive
                )
            }
            // 后台刷新进度条 — 细线动画
            AnimatedVisibility(
                visible = isRefreshing,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.Screen.Horizontal),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                )
            }
            GallerySearchBar(
                searchInput = searchInput,
                onSearchInputChange = onSearchInputChange,
                isSearchFocused = isSearchFocused,
                onSearchFocusChange = onSearchFocusChange,
                searchFocusRequester = searchFocusRequester,
                onFilterClick = onFilterClick,
                hasActiveFilters = hasActiveFilters,
                activeFilterCount = activeFilterCount,
                onSubmitSearch = onSubmitSearch,
                entityLabel = entityLabel
            )
        } else {
            // Classic 风格：档案切换器整合到标题卡片中
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
                isRefreshing = isRefreshing,
                entityLabel = entityLabel
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

        // 结果计数徽章
        if (filteredCount != totalCount) {
            Spacer(modifier = Modifier.height(AppSpacing.Xs))
            ResultCountBadge(filteredCount = filteredCount, totalCount = totalCount)
        }
        Spacer(modifier = Modifier.height(AppSpacing.Md))
    }
}

/**
 * Command Center 风格搜索栏 — HUD 玻璃面板 + 聚焦高亮 + 动画清除按钮
 */
@Composable
private fun GallerySearchBar(
    searchInput: String,
    onSearchInputChange: (String) -> Unit,
    isSearchFocused: Boolean,
    onSearchFocusChange: (Boolean) -> Unit,
    searchFocusRequester: FocusRequester,
    onFilterClick: () -> Unit,
    hasActiveFilters: Boolean,
    activeFilterCount: Int,
    onSubmitSearch: (String) -> Unit,
    entityLabel: String = "舰娘"
) {
    val isDark = LocalIsDark.current
    val isWatch = isWatchScreen()

    val glassSurface = if (isDark) AppColors.GlassSurfaceDark else AppColors.GlassSurfaceLight
    val glassBorder = if (isDark) AppColors.GlassBorderDark else AppColors.GlassBorderLight

    val searchIconScale by animateFloatAsState(
        targetValue = if (searchInput.isNotEmpty()) 1.1f else 1f,
        animationSpec = AppAnimation.Specs.scale(),
        label = "SearchIconScale"
    )

    // 聚焦时边框高亮 + 阴影增强
    val borderAlpha by animateFloatAsState(
        targetValue = if (isSearchFocused) 1f else 0.5f,
        animationSpec = AppAnimation.Specs.fast(),
        label = "BorderAlpha"
    )
    val iconBgAlpha by animateFloatAsState(
        targetValue = if (isSearchFocused) 0.45f else 0.3f,
        animationSpec = AppAnimation.Specs.fast(),
        label = "IconBgAlpha"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Screen.Horizontal)
            .height(if (isWatch) AppSpacing.Height.Input - 8.dp else AppSpacing.Height.Input),
        shape = BlyyShapes.PanelMedium,
        color = glassSurface.copy(alpha = if (isSearchFocused) 0.98f else 0.92f),
        shadowElevation = if (isSearchFocused) AppSpacing.Elevation.Lg else AppSpacing.Elevation.Md
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    width = AppSpacing.Border.Thin,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            glassBorder.copy(alpha = borderAlpha),
                            glassBorder.copy(alpha = borderAlpha * 0.2f)
                        )
                    ),
                    shape = BlyyShapes.PanelMedium
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppSpacing.Padding.InputHorizontal),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 搜索图标 — 带圆形背景
                Box(
                    modifier = Modifier
                        .size(if (isWatch) 28.dp else 36.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = iconBgAlpha),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(if (isWatch) AppSpacing.Icon.Sm else AppSpacing.Icon.Md)
                            .scale(searchIconScale)
                    )
                }

                Spacer(modifier = Modifier.width(AppSpacing.Md))

                // 输入区域
                Box(modifier = Modifier.weight(1f)) {
                    if (searchInput.isEmpty()) {
                        Text(
                            text = "搜索$entityLabel...",
                            style = AppTypography.BodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                    BasicTextField(
                        value = searchInput,
                        onValueChange = onSearchInputChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(searchFocusRequester)
                            .onFocusChanged { onSearchFocusChange(it.isFocused) },
                        textStyle = AppTypography.BodyLarge.copy(
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
                    IconButton(
                        onClick = {
                            onSearchInputChange("")
                            searchFocusRequester.requestFocus()
                        },
                        modifier = Modifier.size(if (isWatch) 28.dp else 32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "清除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(AppSpacing.Icon.Sm)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(AppSpacing.Xs))

                // 筛选按钮 — 带激活计数徽章
                ModernFilterButton(
                    hasActiveFilters = hasActiveFilters,
                    activeFilterCount = activeFilterCount,
                    onClick = onFilterClick,
                    isWatch = isWatch
                )
            }
        }
    }
}

/**
 * 经典风格船坞搜索栏 — 双层卡片结构
 * 标题卡片（带舰娘总数 + 档案切换器） + 独立搜索卡片
 *
 * 设计优化：
 * - 标题卡片使用 surfaceContainerHigh 背景 + outlineVariant 边框，与 ClassicTopBar 配色协调
 * - 标题左侧添加主色装饰条，增强视觉锚点
 * - 搜索框添加搜索图标圆形背景装饰，与 Command Center 风格视觉一致
 * - 未聚焦时保留淡边框，避免突兀；聚焦时主色边框高亮
 * - 暗色下提高对比度，确保文字清晰可读
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
    isRefreshing: Boolean = false,
    entityLabel: String = "舰娘"
) {
    val isDark = LocalIsDark.current
    val isWatch = isWatchScreen()

    // 聚焦时搜索框边框高亮（未聚焦时保留淡边框，避免突兀）
    val searchBorderAlpha by animateFloatAsState(
        targetValue = if (isSearchFocused) 1f else 0.4f,
        animationSpec = AppAnimation.Specs.fast(),
        label = "SearchBorderAlpha"
    )
    // 搜索图标圆形背景透明度动画
    val searchIconBgAlpha by animateFloatAsState(
        targetValue = if (isSearchFocused) 0.4f else 0.2f,
        animationSpec = AppAnimation.Specs.fast(),
        label = "SearchIconBgAlpha"
    )

    Column {
        // 标题卡片 — surfaceContainerHigh 背景 + 主色装饰条 + 档案切换器
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Screen.Horizontal),
            shape = RoundedCornerShape(AppSpacing.Corner.Lg),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isDark) 0.9f else 0.7f),
            border = androidx.compose.foundation.BorderStroke(
                width = AppSpacing.Border.Thin,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.5f else 0.4f)
            ),
            shadowElevation = AppSpacing.Elevation.Sm
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧主色装饰条 — 增强视觉锚点
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(if (isWatch) 28.dp else 32.dp)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                )
                            )
                        )
                )
                Spacer(modifier = Modifier.width(AppSpacing.Md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = AppTypography.TitleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.Xxs))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "共 $totalCount 位$entityLabel",
                            style = AppTypography.LabelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDark) 0.9f else 0.85f)
                        )
                        if (filteredCount != totalCount) {
                            Spacer(modifier = Modifier.width(AppSpacing.Sm))
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.25f else 0.15f)
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

                // 档案切换器 — 紧凑型，整合到标题卡片右侧
                CompactArchiveSwitcher(
                    archiveType = archiveType,
                    onSwitchArchive = onSwitchArchive
                )
            }
        }

        // 后台刷新进度条 — 细线动画
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

        // 标题与搜索框之间的呼吸间距
        Spacer(modifier = Modifier.height(AppSpacing.Md))

        // 搜索框卡片 — 聚焦时边框高亮 + 阴影增强 + 搜索图标圆形背景
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Screen.Horizontal)
                .height(if (isWatch) 40.dp else 48.dp),
            shape = RoundedCornerShape(AppSpacing.Corner.Lg),
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = if (isSearchFocused) 0.98f else 0.9f),
            shadowElevation = if (isSearchFocused) AppSpacing.Elevation.Md else AppSpacing.Elevation.Sm
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        width = AppSpacing.Border.Thin,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = searchBorderAlpha * 0.6f),
                        shape = RoundedCornerShape(AppSpacing.Corner.Lg)
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = AppSpacing.Padding.InputHorizontal),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 搜索图标 — 带圆形背景装饰，与 Command Center 视觉一致
                    Box(
                        modifier = Modifier
                            .size(if (isWatch) 28.dp else 32.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = searchIconBgAlpha),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(if (isWatch) AppSpacing.Icon.Sm else AppSpacing.Icon.Md)
                        )
                    }

                    Spacer(modifier = Modifier.width(AppSpacing.Md))

                    Box(modifier = Modifier.weight(1f)) {
                        if (searchInput.isEmpty()) {
                            Text(
                                text = "搜索$entityLabel...",
                                style = AppTypography.BodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDark) 0.7f else 0.6f)
                            )
                        }
                        BasicTextField(
                            value = searchInput,
                            onValueChange = onSearchInputChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester)
                                .onFocusChanged { onSearchFocusChange(it.isFocused) },
                            textStyle = AppTypography.BodyLarge.copy(
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
                        IconButton(
                            onClick = {
                                onSearchInputChange("")
                                searchFocusRequester.requestFocus()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "清除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(AppSpacing.Icon.Sm)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(AppSpacing.Xs))

                    // 筛选按钮 — 带激活计数徽章（与 Command Center 搜索栏位置一致）
                    ModernFilterButton(
                        hasActiveFilters = hasActiveFilters,
                        activeFilterCount = activeFilterCount,
                        onClick = onFilterClick,
                        isWatch = isWatch
                    )
                }
            }
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

@Composable
fun ResultCountBadge(
    filteredCount: Int,
    totalCount: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Screen.Horizontal),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(AppSpacing.Corner.Lg),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$filteredCount",
                    style = AppTypography.LabelLargeBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = " / $totalCount",
                    style = AppTypography.LabelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
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

private data class SearchHistoryState(
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
private fun rememberSearchHistory(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModernFilterBottomSheet(
    onDismiss: () -> Unit,
    selectedFaction: String,
    selectedType: String,
    selectedRarity: String,
    onFactionSelected: (String) -> Unit,
    onTypeSelected: (String) -> Unit,
    onRaritySelected: (String) -> Unit,
    allFactions: List<String>,
    allTypes: List<String>,
    allRarities: List<String>
) {
    val isDark = LocalIsDark.current
    val glassSurface = if (isDark) AppColors.GlassSurfaceDark else AppColors.GlassSurfaceLight
    val haptic = LocalHapticFeedback.current

    var tempFaction by remember { mutableStateOf(selectedFaction) }
    var tempType by remember { mutableStateOf(selectedType) }
    var tempRarity by remember { mutableStateOf(selectedRarity) }

    val sheetShape = if (LocalUiStyle.current.isCommandCenter()) {
        CutCornerShape(topStart = 16.dp, topEnd = 16.dp)
    } else {
        RoundedCornerShape(topStart = AppSpacing.Corner.Xxl, topEnd = AppSpacing.Corner.Xxl)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = glassSurface.copy(alpha = 0.98f),
        shape = sheetShape,
        dragHandle = {
            ModernDragHandle()
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Screen.Horizontal),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "筛选条件",
                    style = AppTypography.TitleLarge
                )
                Surface(
                    onClick = onDismiss,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(AppSpacing.Icon.Sm)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.Md))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = AppSpacing.Screen.Horizontal),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Gap.Section),
                userScrollEnabled = true
            ) {
                item {
                    ModernFilterSection(
                        title = "阵营",
                        options = allFactions,
                        selected = tempFaction,
                        onSelected = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            tempFaction = it
                        }
                    )
                }

                item {
                    ModernFilterSection(
                        title = "类型",
                        options = allTypes,
                        selected = tempType,
                        onSelected = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            tempType = it
                        }
                    )
                }

                item {
                    ModernFilterSection(
                        title = "稀有度",
                        options = allRarities,
                        selected = tempRarity,
                        onSelected = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            tempRarity = it
                        }
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = glassSurface.copy(alpha = 0.95f),
                shadowElevation = AppSpacing.Elevation.Lg
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(AppSpacing.Screen.Horizontal)
                        .padding(top = AppSpacing.Sm),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
                ) {
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            tempFaction = "全部"
                            tempType = "全部"
                            tempRarity = "全部"
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(AppSpacing.Corner.Lg)
                    ) {
                        Text("重置")
                    }

                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onFactionSelected(tempFaction)
                            onTypeSelected(tempType)
                            onRaritySelected(tempRarity)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(AppSpacing.Corner.Lg)
                    ) {
                        Text("应用筛选")
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernDragHandle() {
    Box(
        modifier = Modifier
            .padding(top = AppSpacing.Md, bottom = AppSpacing.Sm)
            .width(40.dp)
            .height(4.dp)
            .background(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                shape = RoundedCornerShape(2.dp)
            )
    )
}

@Composable
private fun ModernFilterSection(
    title: String,
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
        Text(
            text = title,
            style = AppTypography.TitleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                val optionColor = when (title) {
                    "稀有度" -> AppColors.Rarity.getRarityColor(option).takeIf { isSelected }
                    else -> null
                }

                FilterChip(
                    selected = isSelected,
                    onClick = { onSelected(option) },
                    label = { Text(option) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = optionColor ?: MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = if (optionColor != null) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier.then(
                        if (isSelected && optionColor != null) {
                            Modifier.border(
                                width = AppSpacing.Border.Thin,
                                color = optionColor,
                                shape = RoundedCornerShape(AppSpacing.Corner.Sm)
                            )
                        } else Modifier
                    )
                )
            }
        }
    }
}

/**
 * 学生档案筛选底部弹窗 — 14 维筛选
 *
 * 显示所有 14 个筛选维度（星级、限定/常驻、攻击类型等），
 * 每个维度使用 FilterChip 行，筛选实时生效无需"应用"按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudentFilterBottomSheet(
    onDismiss: () -> Unit,
    studentFilters: Map<String, String>,
    onFilterSelected: (String, String) -> Unit,
    onReset: () -> Unit
) {
    val isDark = LocalIsDark.current
    val glassSurface = if (isDark) AppColors.GlassSurfaceDark else AppColors.GlassSurfaceLight
    val haptic = LocalHapticFeedback.current

    val sheetShape = if (LocalUiStyle.current.isCommandCenter()) {
        CutCornerShape(topStart = 16.dp, topEnd = 16.dp)
    } else {
        RoundedCornerShape(topStart = AppSpacing.Corner.Xxl, topEnd = AppSpacing.Corner.Xxl)
    }

    val activeFilterCount = studentFilters.count { it.value != "全部" }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = glassSurface.copy(alpha = 0.98f),
        shape = sheetShape,
        dragHandle = { ModernDragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            // 标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Screen.Horizontal),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "学生筛选",
                        style = AppTypography.TitleLarge
                    )
                    if (activeFilterCount > 0) {
                        Text(
                            text = "已选 $activeFilterCount 项",
                            style = AppTypography.LabelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Surface(
                    onClick = onDismiss,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(AppSpacing.Icon.Sm)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.Md))

            // 14 维筛选列表
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = AppSpacing.Screen.Horizontal),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Gap.Section),
                userScrollEnabled = true
            ) {
                items(StudentFilterData.FILTER_CATEGORIES) { category ->
                    val selected = studentFilters[category.key] ?: "全部"
                    ModernFilterSection(
                        title = category.label,
                        options = listOf("全部") + category.options,
                        selected = selected,
                        onSelected = { option ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onFilterSelected(category.key, option)
                        }
                    )
                }
            }

            // 底部重置按钮
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = glassSurface.copy(alpha = 0.95f),
                shadowElevation = AppSpacing.Elevation.Lg
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(AppSpacing.Screen.Horizontal)
                        .padding(top = AppSpacing.Sm),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
                ) {
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onReset()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(AppSpacing.Corner.Lg),
                        enabled = activeFilterCount > 0
                    ) {
                        Text("重置全部")
                    }

                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(AppSpacing.Corner.Lg)
                    ) {
                        Text("完成")
                    }
                }
            }
        }
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

/**
 * 全屏错误状态 — 无数据且加载失败时显示
 */
@Composable
private fun GalleryErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = AppSpacing.Screen.Horizontal * 2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(AppSpacing.Lg))
        Text(
            text = "加载失败",
            style = AppTypography.TitleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(AppSpacing.Sm))
        Text(
            text = message,
            style = AppTypography.BodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(AppSpacing.Lg))
        Button(
            onClick = onRetry,
            shape = RoundedCornerShape(AppSpacing.Corner.Lg)
        ) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(AppSpacing.Xs))
            Text("重试")
        }
    }
}

/**
 * 空状态 — 无数据且无错误时显示
 */
@Composable
private fun GalleryEmptyState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = AppSpacing.Screen.Horizontal * 2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.SearchOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(AppSpacing.Lg))
        Text(
            text = message,
            style = AppTypography.BodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(AppSpacing.Lg))
        OutlinedButton(
            onClick = onRetry,
            shape = RoundedCornerShape(AppSpacing.Corner.Lg)
        ) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(AppSpacing.Xs))
            Text("刷新")
        }
    }
}

/**
 * 非侵入式错误横幅 — 有缓存数据但刷新失败时显示在网格顶部
 */
@Composable
private fun GalleryErrorBanner(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(AppSpacing.Corner.Md),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f),
        shadowElevation = AppSpacing.Elevation.Md
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(AppSpacing.Sm))
            Text(
                text = message,
                style = AppTypography.LabelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = onRetry) {
                Text("重试")
            }
        }
    }
}

