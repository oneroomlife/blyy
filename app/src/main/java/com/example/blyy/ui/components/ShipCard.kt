package com.example.blyy.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.blyy.data.model.Ship
import com.example.blyy.ui.theme.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShipCard(
    ship: Ship,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val hapticFeedback = LocalHapticFeedback.current
    
    val rarityColor = AppColors.Rarity.getRarityColor(ship.rarity)
    val rarityGradient = AppColors.Rarity.getRarityGradient(ship.rarity)
    val isHighRarity = AppColors.Rarity.isHighRarity(ship.rarity)
    
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = 400f
        ),
        label = "CardScale"
    )
    
    val elevation by animateDpAsState(
        targetValue = if (isPressed) 2.dp else 8.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "CardElevation"
    )
    
    val borderAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.8f else 0.4f,
        animationSpec = tween(150),
        label = "BorderAlpha"
    )

    Box(
        modifier = modifier
            .padding(AppSpacing.Padding.CardOuter)
            .aspectRatio(0.8f)
            .scale(scale)
            .shadow(
                elevation = elevation,
                shape = RoundedCornerShape(20.dp),
                ambientColor = rarityColor.copy(alpha = 0.25f),
                spotColor = rarityColor.copy(alpha = 0.15f)
            )
            .clip(RoundedCornerShape(20.dp))
            .border(
                width = if (isHighRarity) 1.5.dp else 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        rarityColor.copy(alpha = borderAlpha),
                        rarityColor.copy(alpha = borderAlpha * 0.5f)
                    )
                ),
                shape = RoundedCornerShape(20.dp)
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
                onLongClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ShipImage(
                avatarUrl = ship.avatarUrl,
                borderUrl = ship.borderUrl,
                contentDescription = ship.name
            )

            if (isHighRarity) {
                RarityGlow(rarityColor = rarityColor, rarityGradient = rarityGradient)
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.38f)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.6f),
                                Color.Black.copy(alpha = 0.85f)
                            )
                        )
                    )
            )

            if (isHighRarity) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(70.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    rarityColor.copy(alpha = 0.12f),
                                    Color.Transparent
                                )
                            )
                        )
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        bottom = 12.dp,
                        start = AppSpacing.Sm,
                        end = AppSpacing.Sm
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = ship.name,
                    color = Color.White,
                    style = AppTypography.CardTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.25f),
                                    Color.Transparent
                                )
                            ),
                            RoundedCornerShape(AppSpacing.Corner.Xs)
                        )
                        .padding(horizontal = AppSpacing.Sm)
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(AppSpacing.Corner.Sm),
                        color = rarityColor.copy(alpha = 0.45f),
                        modifier = Modifier
                            .then(
                                if (isHighRarity) {
                                    Modifier.border(
                                        width = AppSpacing.Border.Thin,
                                        brush = rarityGradient,
                                        shape = RoundedCornerShape(AppSpacing.Corner.Sm)
                                    )
                                } else Modifier
                            )
                    ) {
                        Text(
                            text = ship.faction,
                            color = Color.White,
                            style = AppTypography.CardLabel,
                            modifier = Modifier.padding(
                                horizontal = 8.dp,
                                vertical = 3.dp
                            )
                        )
                    }
                    
                    Surface(
                        shape = RoundedCornerShape(AppSpacing.Corner.Sm),
                        color = Color.White.copy(alpha = 0.18f)
                    ) {
                        Text(
                            text = ship.type,
                            color = Color.White,
                            style = AppTypography.CardLabel,
                            modifier = Modifier.padding(
                                horizontal = 8.dp,
                                vertical = 3.dp
                            )
                        )
                    }
                }
            }

            if (ship.isFavorite) {
                FavoriteBadge()
            }
        }
    }
}

@Composable
private fun ShipImage(
    avatarUrl: String,
    borderUrl: String?,
    contentDescription: String
) {
    AsyncImage(
        model = avatarUrl,
        contentDescription = contentDescription,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        error = rememberVectorPainter(Icons.Default.BrokenImage)
    )

    if (!borderUrl.isNullOrEmpty()) {
        AsyncImage(
            model = borderUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )
    }
}

@Composable
private fun RarityGlow(rarityColor: Color, rarityGradient: Brush) {
    val infiniteTransition = rememberInfiniteTransition(label = "RarityGlow")
    
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(3500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GlowAlpha"
    )
    
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        rarityColor.copy(alpha = glowAlpha),
                        rarityColor.copy(alpha = glowAlpha * 0.6f),
                        rarityColor.copy(alpha = glowAlpha * 0.2f),
                        Color.Transparent
                    ),
                    radius = 0.75f * pulseScale
                )
            )
    )
}

@Composable
private fun BoxScope.FavoriteBadge() {
    val infiniteTransition = rememberInfiniteTransition(label = "Favorite")
    
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GlowAlpha"
    )
    
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(AppSpacing.Sm)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .scale(pulseScale)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            AppColors.Favorite.Gold.copy(alpha = glowAlpha),
                            AppColors.Favorite.Gold.copy(alpha = glowAlpha * 0.4f),
                            Color.Transparent
                        )
                    )
                )
        )

        Surface(
            modifier = Modifier.size(28.dp),
            shape = CircleShape,
            color = AppColors.Favorite.Gold,
            shadowElevation = AppSpacing.Elevation.Lg,
            tonalElevation = AppSpacing.Elevation.Sm
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = "誓约",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
