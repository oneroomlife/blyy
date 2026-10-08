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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
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
import com.azurlane.blyy.ui.components.BlyyBottomSheet
import com.azurlane.blyy.ui.components.BlyyEmptyState
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.components.ShipCard
import com.azurlane.blyy.ui.components.ShipCardShimmer
import com.azurlane.blyy.ui.theme.*
import com.azurlane.blyy.viewmodel.GalleryIntent
import com.azurlane.blyy.viewmodel.GalleryViewState
import com.azurlane.blyy.ui.components.rememberBlyyHaptics
import com.azurlane.blyy.ui.components.BlyyHaptic
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import com.azurlane.blyy.ui.screens.gallery.AdaptiveGalleryTopBar
import com.azurlane.blyy.ui.screens.gallery.ModernFilterBottomSheet
import com.azurlane.blyy.ui.screens.gallery.StudentFilterBottomSheet
import com.azurlane.blyy.ui.screens.gallery.rememberSearchHistory

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
    val haptic = rememberBlyyHaptics()
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

    // 顶部合并栏实际高度（px）— 由 AdaptiveGalleryTopBar 测量上报，用于动态计算网格顶部内边距
    var headerHeightPx by remember { mutableIntStateOf(0) }

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
                listOf("全部", "白鹰", "皇家", "重樱", "铁血", "东煌", "撒丁帝国", "北方联合", "自由鸢尾", "维希教廷", "郁金王国", "晶环联盟", "META", "其他", "飓风")
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
        haptic(BlyyHaptic.LongPress)
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
        val statusBarTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        // 顶部栏已合并为单面板：用实测高度（px→dp）替代旧的固定内边距，
        // 未测得前回退固定值，避免首帧内容跳动
        val headerTopPadding = if (headerHeightPx > 0) {
            with(LocalDensity.current) { headerHeightPx.toDp() } + AppSpacing.Lg
        } else {
            AppSpacing.TopBar.ContentTopPadding
        }
        val fixedTopPadding = statusBarTopPadding + headerTopPadding
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
                BlyyEmptyState(
                    icon = Icons.Outlined.SearchOff,
                    title = if (state.searchQuery.isNotBlank()) "未找到匹配的结果" else "暂无数据",
                    description = if (state.searchQuery.isNotBlank()) "换个关键词或调整筛选条件试试" else "下拉或点击按钮刷新数据",
                    actionLabel = "刷新",
                    onAction = { onIntent(GalleryIntent.ForceRefresh) },
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
                                    haptic(BlyyHaptic.Tick)
                                    onShipClick(ship)
                                },
                                onLongClick = {
                                    haptic(BlyyHaptic.LongPress)
                                    onIntent(GalleryIntent.ToggleFavorite(ship))
                                    Toast.makeText(context, if (ship.isFavorite) "已解除与${ship.name}的誓约" else "已与${ship.name}誓约", Toast.LENGTH_SHORT).show()
                                },
                                onWikiClick = { openWiki(ship) },
                                onOathClick = {
                                    haptic(BlyyHaptic.LongPress)
                                    onIntent(GalleryIntent.ToggleFavorite(ship))
                                    Toast.makeText(context, if (ship.isFavorite) "已解除与${ship.name}的誓约" else "已与${ship.name}誓约", Toast.LENGTH_SHORT).show()
                                },
                                onGalleryClick = {
                                    haptic(BlyyHaptic.Tick)
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
                    haptic(BlyyHaptic.Tick)
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
                    haptic(BlyyHaptic.Tick)
                    showFilterSheet = true
                },
                hasActiveFilters = activeFilterCount > 0,
                activeFilterCount = activeFilterCount,
                archiveType = state.archiveType,
                onSwitchArchive = { newType ->
                    haptic(BlyyHaptic.Tick)
                    onIntent(GalleryIntent.SwitchArchive(newType))
                },
                isRefreshing = state.isRefreshing,
                isCacheHit = state.isCacheHit,
                cacheTimestamp = state.cacheTimestamp,
                onHeaderHeightChanged = { headerHeightPx = it }
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
