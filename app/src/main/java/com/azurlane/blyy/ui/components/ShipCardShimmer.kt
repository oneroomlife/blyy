package com.azurlane.blyy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import com.azurlane.blyy.ui.theme.BlyyShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.azurlane.blyy.ui.theme.*
import com.valentinilk.shimmer.shimmer

@Composable
fun ShipCardShimmer() {
    val isDark = LocalIsDark.current
    val shimmerStart = if (isDark) AppColors.Effect.ShimmerStartDark else AppColors.Effect.ShimmerStartLight
    val shimmerEnd = if (isDark) AppColors.Effect.ShimmerEndDark else AppColors.Effect.ShimmerEndLight

    Box(
        modifier = Modifier
            .shimmer()
            .padding(AppSpacing.Padding.CardOuter)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(AppSpacing.Card.AspectRatio)
                .clip(adaptiveCardShape())
                .background(
                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                        colors = listOf(
                            shimmerStart,
                            shimmerEnd,
                            shimmerStart
                        )
                    )
                )
        )
    }
}
