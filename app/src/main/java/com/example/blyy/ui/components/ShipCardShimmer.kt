package com.example.blyy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.example.blyy.ui.theme.*
import com.valentinilk.shimmer.shimmer

@Composable
fun ShipCardShimmer() {
    Box(
        modifier = Modifier
            .shimmer()
            .padding(AppSpacing.Padding.CardOuter)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(AppSpacing.Card.AspectRatio)
                .clip(RoundedCornerShape(AppSpacing.Card.CornerSize))
                .background(
                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                        colors = listOf(
                            AppColors.Effect.ShimmerStart,
                            AppColors.Effect.ShimmerEnd,
                            AppColors.Effect.ShimmerStart
                        )
                    )
                )
        )
    }
}
