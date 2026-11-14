package com.azurlane.blyy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.azurlane.blyy.ui.theme.AppColors
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.valentinilk.shimmer.shimmer

/**
 * 骨架屏基础件 — 微光占位块。
 *
 * 与 [ShipCardShimmer] 同源的双色微光令牌；圆角走 [AppSpacing.Corner]。
 * 注意：本组件只画占位块，shimmer 动画应由外层容器统一施加
 * （同组占位共享一个 shimmer 实例，相位才一致）。
 */
@Composable
fun BlyyShimmerBlock(
    modifier: Modifier = Modifier,
    corner: Dp = AppSpacing.Corner.Md
) {
    val isDark = LocalIsDark.current
    val shimmerStart = if (isDark) AppColors.Effect.ShimmerStartDark else AppColors.Effect.ShimmerStartLight
    val shimmerEnd = if (isDark) AppColors.Effect.ShimmerEndDark else AppColors.Effect.ShimmerEndLight

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(shimmerStart, shimmerEnd, shimmerStart)
                )
            )
    )
}

/**
 * 列表骨架屏 — 圆形头像 + 两行文字的占位行。
 *
 * 用于整页列表（成绩榜/历史记录等）首屏加载，
 * 替代孤零零的 CircularProgressIndicator。
 */
@Composable
fun BlyySkeletonList(
    itemCount: Int = 6,
    modifier: Modifier = Modifier,
    contentPadding: Dp = AppSpacing.Lg
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(contentPadding),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Gap.ListItem),
        userScrollEnabled = false
    ) {
        items((0 until itemCount).toList()) {
            Row(
                modifier = Modifier.shimmer(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BlyyShimmerBlock(
                    modifier = Modifier.size(AppSpacing.Avatar.Lg),
                    corner = AppSpacing.Corner.Full
                )
                Spacer(Modifier.width(AppSpacing.Md))
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Xs)) {
                    BlyyShimmerBlock(
                        modifier = Modifier
                            .fillMaxWidth(0.45f)
                            .height(14.dp)
                    )
                    BlyyShimmerBlock(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(12.dp)
                    )
                }
            }
        }
    }
}

/**
 * 网格骨架屏 — 卡片比例占位块。
 *
 * 用于图鉴/资源网格首屏加载；minSize 语义与 LazyVerticalGrid 的
 * [GridCells.Adaptive] 一致，保证骨架与真实网格同构。
 */
@Composable
fun BlyySkeletonGrid(
    minSize: Dp,
    modifier: Modifier = Modifier,
    itemCount: Int = 10
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = minSize),
        modifier = modifier,
        userScrollEnabled = false,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Gap.CardGrid),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Gap.CardGrid)
    ) {
        items((0 until itemCount).toList()) {
            Column(
                Modifier.shimmer(),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
            ) {
                BlyyShimmerBlock(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(AppSpacing.Card.AspectRatio)
                )
                BlyyShimmerBlock(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(12.dp)
                )
            }
        }
    }
}
