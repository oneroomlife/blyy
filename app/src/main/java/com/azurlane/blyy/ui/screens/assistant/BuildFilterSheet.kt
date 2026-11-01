package com.azurlane.blyy.ui.screens.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.azurlane.blyy.domain.BuildFilterSelection
import com.azurlane.blyy.domain.BuildPoolFilter
import com.azurlane.blyy.domain.BuildRarityFilter
import com.azurlane.blyy.ui.components.BlyyBottomSheet
import com.azurlane.blyy.ui.components.BlyyDragHandle
import com.azurlane.blyy.ui.components.BlyyHaptic
import com.azurlane.blyy.ui.components.BlyyPrimaryButton
import com.azurlane.blyy.ui.components.BlyySecondaryButton
import com.azurlane.blyy.ui.components.rememberBlyyHaptics
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography

/**
 * 建造记录「属性筛选」弹窗 — 对齐设计稿（图2）
 *
 * 结构：
 * - 标题「属性筛选」+ 收起指示
 * - 建造类型：全部 / 限时建造 / 轻型建造 / 重型建造 / 特型建造 / 祈愿建造 / 新手限定建造
 * - 稀有度：全部 / 海上传奇 / 超稀有 / 精锐 / 稀有 / 普通
 * - 底部：重置（次要按钮）+ 确认（主按钮）
 *
 * 交互约定：胶囊即点即改草稿，只有「确认」才回写 ViewModel，
 * 这样用户连续调整多个条件时不会触发多次列表重组与滚动位置跳动。
 *
 * @param selection 当前生效的筛选组合（作为草稿初值）
 * @param hasRarityIndex 本地图鉴是否已收录舰船；为 false 时稀有度筛选无法命中任何记录，
 *   需在弹窗内明确提示，避免用户以为筛选坏了
 * @param unknownRarity 命中建造类型但图鉴查无稀有度的条数，仅在有稀有度筛选时提示
 */
@Composable
fun BuildFilterSheet(
    selection: BuildFilterSelection,
    hasRarityIndex: Boolean,
    unknownRarity: Int,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    onConfirm: (BuildFilterSelection) -> Unit
) {
    val haptic = rememberBlyyHaptics()
    var draftPool by remember(selection) { mutableStateOf(selection.pool) }
    var draftRarity by remember(selection) { mutableStateOf(selection.rarity) }

    BlyyBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = AppSpacing.Lg)
                .padding(bottom = AppSpacing.Lg)
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                BlyyDragHandle()
            }

            FilterSheetTitle()

            Spacer(modifier = Modifier.height(AppSpacing.Lg))

            FilterSectionLabel("建造类型")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
            ) {
                BuildPoolFilter.entries.forEach { option ->
                    FilterPill(
                        text = option.label,
                        selected = draftPool == option,
                        onClick = {
                            haptic(BlyyHaptic.Tick)
                            draftPool = option
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.Lg))

            FilterSectionLabel("稀有度")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
            ) {
                BuildRarityFilter.entries.forEach { option ->
                    FilterPill(
                        text = option.label,
                        selected = draftRarity == option,
                        onClick = {
                            haptic(BlyyHaptic.Tick)
                            draftRarity = option
                        }
                    )
                }
            }

            // 稀有度依赖本地图鉴反查，图鉴为空/有漏收时给出明确提示
            val hint = rarityHint(
                hasRarityIndex = hasRarityIndex,
                hasRarityFilter = draftRarity != BuildRarityFilter.ALL,
                unknownRarity = unknownRarity
            )
            if (hint != null) {
                Spacer(modifier = Modifier.height(AppSpacing.Sm))
                Text(
                    text = hint,
                    style = AppTypography.CaptionSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = AppSpacing.Xs)
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.Xl))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
            ) {
                BlyySecondaryButton(
                    text = "重置",
                    onClick = {
                        haptic(BlyyHaptic.Tick)
                        draftPool = BuildPoolFilter.ALL
                        draftRarity = BuildRarityFilter.ALL
                        onReset()
                    },
                    modifier = Modifier.weight(1f)
                )
                BlyyPrimaryButton(
                    text = "确认",
                    onClick = {
                        haptic(BlyyHaptic.Confirm)
                        onConfirm(BuildFilterSelection(draftPool, draftRarity))
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** 弹窗标题行 —— 居中标题 + 右上收起指示，呼应设计稿的折叠箭头 */
@Composable
private fun FilterSheetTitle() {
    val primary = MaterialTheme.colorScheme.primary
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "属性筛选",
                style = AppTypography.TitleMediumBold,
                color = primary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.width(AppSpacing.Xs))
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowUp,
                contentDescription = null,
                tint = primary.copy(alpha = 0.7f),
                modifier = Modifier.size(AppSpacing.Icon.Sm)
            )
        }
    }
}

/** 分组标题 —— 设计稿里「建造类型：」这排左对齐小字 */
@Composable
private fun FilterSectionLabel(text: String) {
    Text(
        text = "$text：",
        style = AppTypography.LabelLargeBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = AppSpacing.Sm)
    )
}

/**
 * 胶囊筛选项 —— 选中态实心主色 + 白字，未选中态描边 + 主色字
 *
 * 刻意不复用 Material 的 FilterChip：设计稿是等半径胶囊 + 细描边，
 * 系统 FilterChip 自带选中勾选图标与前导间距，会破坏排布。
 */
@Composable
private fun FilterPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(AppSpacing.Corner.Full)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) primary else primary.copy(alpha = 0.04f))
            .border(
                width = AppSpacing.Border.Thin,
                color = if (selected) primary else primary.copy(alpha = 0.35f),
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Padding.TagVertical + 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = if (selected) AppTypography.LabelMediumBold else AppTypography.LabelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else primary,
            maxLines = 1
        )
    }
}

/** 稀有度提示文案；无提示时返回 null */
private fun rarityHint(
    hasRarityIndex: Boolean,
    hasRarityFilter: Boolean,
    unknownRarity: Int
): String? = when {
    !hasRarityIndex ->
        "本地图鉴尚未收录舰船，稀有度筛选暂不可用，可先到图鉴页刷新数据"
    hasRarityFilter && unknownRarity > 0 ->
        "另有 $unknownRarity 条记录在图鉴中查无对应舰船，已按稀有度筛选隐藏"
    else -> null
}
