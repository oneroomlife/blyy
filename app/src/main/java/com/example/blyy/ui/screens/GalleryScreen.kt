package com.example.blyy.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.blyy.data.model.Ship
import com.example.blyy.ui.components.ShipCard
import com.example.blyy.ui.components.ShipCardShimmer
import com.example.blyy.ui.theme.*
import com.example.blyy.viewmodel.GalleryIntent
import com.example.blyy.viewmodel.GalleryViewState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

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
    var showFilterSheet by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    
    var isTopBarVisible by remember { mutableStateOf(true) }
    
    val topBarOffset by animateFloatAsState(
        targetValue = if (isTopBarVisible) 0f else -300f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "topBarOffset"
    )
    val topBarAlpha by animateFloatAsState(
        targetValue = if (isTopBarVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 200, easing = LinearEasing),
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
    
    val allFactions = listOf("全部", "白鹰", "皇家", "重樱", "铁血", "东煌", "撒丁帝国", "北方联合", "自由鸢尾", "维希教廷", "郁金王国", "余烬", "其他", "飓风")
    val allTypes = listOf("全部", "前排先锋", "后排主力", "驱逐", "轻巡", "重巡", "超巡", "战巡", "战列", "航战", "航母", "轻航", "重炮", "维修", "潜艇", "潜母", "运输", "风帆")
    val allRarities = listOf("全部", "海上传奇", "决战方案", "超稀有", "最高方案", "精锐", "稀有", "普通")

    fun openWiki(ship: Ship) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color = MaterialTheme.colorScheme.surfaceContainer)
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
    ) {
        val fixedTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 150.dp
        val fixedBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 90.dp

        if (state.isLoading && filteredShips.isEmpty()) {
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
        } else {
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
                itemsIndexed(items = filteredShips, key = { _, ship -> ship.name }) { index, ship ->
                    ShipCard(
                        ship = ship,
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
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationY = topBarOffset
                    alpha = topBarAlpha
                }
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f),
                            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.8f),
                            Color.Transparent
                        )
                    )
                )
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            CenterAlignedTopAppBar(
                title = { Text("船坞档案", style = AppTypography.TitleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
            
            ModernSearchAndFilterBar(
                searchQuery = state.searchQuery,
                onSearchQueryChange = { query -> onIntent(GalleryIntent.Search(query)) },
                onFilterClick = { 
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    showFilterSheet = true 
                },
                hasActiveFilters = state.selectedFaction != "全部" || state.selectedType != "全部" || state.selectedRarity != "全部"
            )
            
            if (filteredShips.size != state.ships.size) {
                Spacer(modifier = Modifier.height(AppSpacing.Xs))
                ResultCountBadge(filteredCount = filteredShips.size, totalCount = state.ships.size)
            }
            Spacer(modifier = Modifier.height(AppSpacing.Md))
        }
    }

    if (showFilterSheet) {
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

@Composable
private fun ModernSearchAndFilterBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onFilterClick: () -> Unit,
    hasActiveFilters: Boolean
) {
    val isDark = isSystemInDarkTheme()
    
    val glassSurface = if (isDark) AppColors.GlassSurfaceDark else AppColors.GlassSurfaceLight
    val glassBorder = if (isDark) AppColors.GlassBorderDark else AppColors.GlassBorderLight
    
    val searchIconScale by animateFloatAsState(
        targetValue = if (searchQuery.isNotEmpty()) 1.1f else 1f,
        animationSpec = spring(dampingRatio = 0.6f),
        label = "SearchIconScale"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Screen.Horizontal)
            .height(AppSpacing.Height.Input),
        shape = RoundedCornerShape(AppSpacing.Corner.Xxl),
        color = glassSurface.copy(alpha = 0.95f),
        shadowElevation = AppSpacing.Elevation.Md
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    width = AppSpacing.Border.Thin,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            glassBorder,
                            glassBorder.copy(alpha = 0.2f)
                        )
                    ),
                    shape = RoundedCornerShape(AppSpacing.Corner.Xxl)
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppSpacing.Padding.InputHorizontal),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(AppSpacing.Icon.Md)
                            .scale(searchIconScale)
                    )
                }
                
                Spacer(modifier = Modifier.width(AppSpacing.Md))
                
                Box(modifier = Modifier.weight(1f)) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = "搜索舰娘...",
                            style = AppTypography.BodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = AppTypography.BodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                    )
                }
                
                Spacer(modifier = Modifier.width(AppSpacing.Sm))
                
                ModernFilterButton(
                    hasActiveFilters = hasActiveFilters,
                    onClick = onFilterClick
                )
            }
        }
    }
}

@Composable
private fun ModernFilterButton(
    hasActiveFilters: Boolean,
    onClick: () -> Unit
) {
    val buttonScale by animateFloatAsState(
        targetValue = if (hasActiveFilters) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.6f),
        label = "FilterButtonScale"
    )

    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (hasActiveFilters) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        modifier = Modifier
            .size(40.dp)
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
                modifier = Modifier.size(AppSpacing.Icon.Md)
            )
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
                    style = AppTypography.LabelLarge.copy(fontWeight = FontWeight.Bold),
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
    val isDark = isSystemInDarkTheme()
    val glassSurface = if (isDark) AppColors.GlassSurfaceDark else AppColors.GlassSurfaceLight
    val haptic = LocalHapticFeedback.current
    
    var tempFaction by remember { mutableStateOf(selectedFaction) }
    var tempType by remember { mutableStateOf(selectedType) }
    var tempRarity by remember { mutableStateOf(selectedRarity) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = glassSurface.copy(alpha = 0.98f),
        shape = RoundedCornerShape(topStart = AppSpacing.Corner.Xxl, topEnd = AppSpacing.Corner.Xxl),
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
