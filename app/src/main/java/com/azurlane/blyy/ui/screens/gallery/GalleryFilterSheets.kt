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
import com.azurlane.blyy.ui.components.BlyyPrimaryButton
import com.azurlane.blyy.ui.components.BlyySecondaryButton
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModernFilterBottomSheet(
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
    val haptic = rememberBlyyHaptics()

    var tempFaction by remember { mutableStateOf(selectedFaction) }
    var tempType by remember { mutableStateOf(selectedType) }
    var tempRarity by remember { mutableStateOf(selectedRarity) }

    BlyyBottomSheet(onDismissRequest = onDismiss) {
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
                            haptic(BlyyHaptic.Tick)
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
                            haptic(BlyyHaptic.Tick)
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
                            haptic(BlyyHaptic.Tick)
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
                    BlyySecondaryButton(
                        text = "重置",
                        onClick = {
                            haptic(BlyyHaptic.Tick)
                            tempFaction = "全部"
                            tempType = "全部"
                            tempRarity = "全部"
                        },
                        modifier = Modifier.weight(1f)
                    )

                    BlyyPrimaryButton(
                        text = "应用筛选",
                        onClick = {
                            haptic(BlyyHaptic.Tick)
                            onFactionSelected(tempFaction)
                            onTypeSelected(tempType)
                            onRaritySelected(tempRarity)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
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
internal fun StudentFilterBottomSheet(
    onDismiss: () -> Unit,
    studentFilters: Map<String, String>,
    onFilterSelected: (String, String) -> Unit,
    onReset: () -> Unit
) {
    val isDark = LocalIsDark.current
    val glassSurface = if (isDark) AppColors.GlassSurfaceDark else AppColors.GlassSurfaceLight
    val haptic = rememberBlyyHaptics()

    val activeFilterCount = studentFilters.count { it.value != "全部" }

    BlyyBottomSheet(onDismissRequest = onDismiss) {
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
                            haptic(BlyyHaptic.Tick)
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
                    BlyySecondaryButton(
                        text = "重置全部",
                        onClick = {
                            haptic(BlyyHaptic.Tick)
                            onReset()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = activeFilterCount > 0
                    )

                    BlyyPrimaryButton(
                        text = "完成",
                        onClick = {
                            haptic(BlyyHaptic.Tick)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
