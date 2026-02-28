package com.example.blyy.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.blyy.data.model.Ship
import com.example.blyy.ui.components.ShipCard
import com.example.blyy.ui.components.ShipCardShimmer
import com.example.blyy.ui.theme.*
import com.example.blyy.viewmodel.GalleryIntent
import com.example.blyy.viewmodel.GalleryViewState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun GalleryScreen(
    state: GalleryViewState,
    filteredShips: List<Ship>,
    onIntent: (GalleryIntent) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    onShipClick: (Ship) -> Unit
) {
    var showFilterSheet by remember { mutableStateOf(false) }
    val allFactions = listOf("全部", "白鹰", "皇家", "铁血", "重樱", "东煌", "撒丁帝国", "北方联合", "自由鸢尾", "维希教廷")
    val allTypes = listOf("全部", "驱逐", "轻巡", "重巡", "战列", "航母", "维修", "潜艇")
    val allRarities = listOf("全部", "海上传奇", "决战方案", "超稀有", "最高方案", "精锐", "稀有", "普通")

    Scaffold(
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            "船坞档案",
                            style = AppTypography.TitleLarge
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    ),
                    windowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                    Color.Transparent
                                )
                            )
                        )
                )
            }
        },
        containerColor = Color.Transparent
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainer
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Spacer(modifier = Modifier.height(AppSpacing.Sm))
                    
                    ModernSearchAndFilterBar(
                        searchQuery = state.searchQuery,
                        onSearchQueryChange = { query -> onIntent(GalleryIntent.Search(query)) },
                        onFilterClick = { showFilterSheet = true },
                        hasActiveFilters = state.selectedFaction != "全部" || 
                                          state.selectedType != "全部" || 
                                          state.selectedRarity != "全部"
                    )
                    
                    if (filteredShips.size != state.ships.size) {
                        Spacer(modifier = Modifier.height(AppSpacing.Xs))
                        ResultCountBadge(
                            filteredCount = filteredShips.size,
                            totalCount = state.ships.size
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(AppSpacing.Md))
                    
                    if (state.isLoading && filteredShips.isEmpty()) {
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
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = AppSpacing.Card.MinWidth),
                            contentPadding = PaddingValues(AppSpacing.Screen.Horizontal),
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Gap.CardGrid),
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.Gap.CardGrid),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(items = filteredShips, key = { _, ship -> ship.name }) { index, ship ->
                                AnimatedVisibility(
                                    visible = true,
                                    enter = fadeIn() + slideInVertically(
                                        initialOffsetY = { 50 },
                                        animationSpec = tween(
                                            durationMillis = AppAnimation.Duration.Normal,
                                            delayMillis = index * AppAnimation.Duration.StaggerDelay,
                                            easing = AppAnimation.Easings.Emphasized
                                        )
                                    ),
                                    exit = fadeOut()
                                ) {
                                    ShipCard(
                                        ship = ship,
                                        onClick = { onShipClick(ship) },
                                        onLongClick = { onIntent(GalleryIntent.ToggleFavorite(ship)) },
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
                .padding(AppSpacing.Screen.Horizontal)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Gap.Section)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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

            ModernFilterSection(
                title = "阵营",
                options = allFactions,
                selected = tempFaction,
                onSelected = { tempFaction = it }
            )

            ModernFilterSection(
                title = "类型",
                options = allTypes,
                selected = tempType,
                onSelected = { tempType = it }
            )

            ModernFilterSection(
                title = "稀有度",
                options = allRarities,
                selected = tempRarity,
                onSelected = { tempRarity = it }
            )

            Spacer(modifier = Modifier.height(AppSpacing.Sm))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
            ) {
                OutlinedButton(
                    onClick = {
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
            
            Spacer(modifier = Modifier.height(AppSpacing.Sm))
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
