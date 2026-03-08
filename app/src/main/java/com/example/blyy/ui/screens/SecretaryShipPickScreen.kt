package com.example.blyy.ui.screens

import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.blyy.data.model.Ship
import com.example.blyy.ui.components.ShipCard
import com.example.blyy.ui.theme.AppColors
import com.example.blyy.ui.theme.AppSpacing
import com.example.blyy.ui.theme.AppTypography

@Composable
fun SecretaryShipPickFromHomeScreen(
    ships: List<Ship>,
    onBack: () -> Unit,
    onShipSelected: (Ship) -> Unit
) {
    SecretaryShipPickScreen(
        title = "从后宅选择",
        emptyMessage = "后宅空荡荡的，先与舰娘誓约吧",
        ships = ships,
        onBack = onBack,
        onShipSelected = onShipSelected
    )
}

@Composable
fun SecretaryShipPickFromGalleryScreen(
    ships: List<Ship>,
    onBack: () -> Unit,
    onShipSelected: (Ship) -> Unit
) {
    SecretaryShipPickScreen(
        title = "从船坞选择",
        emptyMessage = "船坞暂无舰娘数据",
        ships = ships,
        onBack = onBack,
        onShipSelected = onShipSelected
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecretaryShipPickScreen(
    title: String,
    emptyMessage: String,
    ships: List<Ship>,
    onBack: () -> Unit,
    onShipSelected: (Ship) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFaction by remember { mutableStateOf("全部") }
    var selectedType by remember { mutableStateOf("全部") }
    var selectedRarity by remember { mutableStateOf("全部") }
    var showFilterSheet by remember { mutableStateOf(false) }
    
    val allFactions = listOf("全部", "白鹰", "皇家", "重樱", "铁血", "东煌", "撒丁帝国", "北方联合", "自由鸢尾", "维希教廷", "郁金王国", "余烬", "其他", "飓风")
    val allTypes = listOf("全部", "前排先锋", "后排主力", "驱逐", "轻巡", "重巡", "超巡", "战巡", "战列", "航战", "航母", "轻航", "重炮", "维修", "潜艇", "潜母", "运输", "风帆")
    val allRarities = listOf("全部", "海上传奇", "决战方案", "超稀有", "最高方案", "精锐", "稀有", "普通")
    
    val filteredShips = remember(ships, searchQuery, selectedFaction, selectedType, selectedRarity) {
        ships.filter { ship ->
            val matchesSearch = searchQuery.isEmpty() || 
                ship.name.contains(searchQuery, ignoreCase = true)
            val matchesFaction = selectedFaction == "全部" || ship.faction == selectedFaction
            val matchesType = selectedType == "全部" || ship.type == selectedType
            val matchesRarity = selectedRarity == "全部" || ship.rarity == selectedRarity
            matchesSearch && matchesFaction && matchesType && matchesRarity
        }
    }
    
    val hasActiveFilters = selectedFaction != "全部" || selectedType != "全部" || selectedRarity != "全部"
    
    Column(modifier = Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    title,
                    style = AppTypography.TitleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            windowInsets = androidx.compose.foundation.layout.WindowInsets(0.dp)
        )
        
        SearchAndFilterBar(
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            onFilterClick = { showFilterSheet = true },
            hasActiveFilters = hasActiveFilters
        )
        
        if (filteredShips.size != ships.size) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Screen.Horizontal),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "找到 ${filteredShips.size} 艘舰娘",
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
                if (hasActiveFilters) {
                    TextButton(onClick = {
                        selectedFaction = "全部"
                        selectedType = "全部"
                        selectedRarity = "全部"
                    }) {
                        Text("清除筛选", style = AppTypography.BodySmall)
                    }
                }
            }
            Spacer(modifier = Modifier.height(AppSpacing.Xs))
        }

        if (filteredShips.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(AppSpacing.Lg),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (ships.isEmpty()) emptyMessage else "没有找到匹配的舰娘",
                    style = AppTypography.BodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = AppSpacing.Card.MinWidth),
                contentPadding = PaddingValues(AppSpacing.Screen.Horizontal),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Gap.CardGrid),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Gap.CardGrid),
                modifier = Modifier.fillMaxSize()
            ) {
                items(items = filteredShips, key = { it.name }) { ship ->
                    ShipCard(
                        ship = ship,
                        onClick = {
                            onShipSelected(ship)
                        },
                        onLongClick = { },
                        onWikiClick = null,
                        onOathClick = null,
                        onGalleryClick = null
                    )
                }
            }
        }
    }
    
    if (showFilterSheet) {
        FilterBottomSheet(
            onDismiss = { showFilterSheet = false },
            selectedFaction = selectedFaction,
            selectedType = selectedType,
            selectedRarity = selectedRarity,
            onFactionSelected = { selectedFaction = it },
            onTypeSelected = { selectedType = it },
            onRaritySelected = { selectedRarity = it },
            allFactions = allFactions,
            allTypes = allTypes,
            allRarities = allRarities
        )
    }
}

@Composable
private fun SearchAndFilterBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onFilterClick: () -> Unit,
    hasActiveFilters: Boolean
) {
    val isDark = isSystemInDarkTheme()
    val glassSurface = if (isDark) AppColors.GlassSurfaceDark else AppColors.GlassSurfaceLight

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Screen.Horizontal, vertical = AppSpacing.Sm)
            .height(AppSpacing.Height.Input),
        shape = RoundedCornerShape(AppSpacing.Corner.Xxl),
        color = glassSurface.copy(alpha = 0.95f),
        shadowElevation = AppSpacing.Elevation.Md
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AppSpacing.Padding.InputHorizontal),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(AppSpacing.Icon.Md)
            )
            
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
            
            if (searchQuery.isNotEmpty()) {
                IconButton(
                    onClick = { onSearchQueryChange("") },
                    modifier = Modifier.size(AppSpacing.Icon.Lg)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "清除搜索",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(AppSpacing.Xs))
            
            Surface(
                onClick = onFilterClick,
                shape = CircleShape,
                color = if (hasActiveFilters) 
                    MaterialTheme.colorScheme.primaryContainer 
                else 
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Icon(
                    imageVector = Icons.Default.FilterList,
                    contentDescription = "筛选",
                    tint = if (hasActiveFilters) 
                        MaterialTheme.colorScheme.onPrimaryContainer 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(AppSpacing.Sm)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBottomSheet(
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md)
        ) {
            Text(
                "筛选舰娘",
                style = AppTypography.TitleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = AppSpacing.Md)
            )
            
            Text(
                "阵营",
                style = AppTypography.LabelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = AppSpacing.Sm, bottom = AppSpacing.Xs)
            )
            
            FilterChipRow(
                options = allFactions,
                selectedOption = selectedFaction,
                onOptionSelected = onFactionSelected
            )
            
            Text(
                "舰种",
                style = AppTypography.LabelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = AppSpacing.Md, bottom = AppSpacing.Xs)
            )
            
            FilterChipRow(
                options = allTypes,
                selectedOption = selectedType,
                onOptionSelected = onTypeSelected
            )
            
            Text(
                "稀有度",
                style = AppTypography.LabelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = AppSpacing.Md, bottom = AppSpacing.Xs)
            )
            
            FilterChipRow(
                options = allRarities,
                selectedOption = selectedRarity,
                onOptionSelected = onRaritySelected
            )
            
            Spacer(modifier = Modifier.height(AppSpacing.Xl))
        }
    }
}

@Composable
private fun FilterChipRow(
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
    ) {
        options.chunked(4).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
            ) {
                rowOptions.forEach { option ->
                    FilterChip(
                        selected = option == selectedOption,
                        onClick = { onOptionSelected(option) },
                        label = { Text(option, style = AppTypography.BodySmall) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(AppSpacing.Corner.Full)
                    )
                }
                repeat(4 - rowOptions.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
