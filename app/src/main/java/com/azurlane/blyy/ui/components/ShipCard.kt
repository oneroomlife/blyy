package com.azurlane.blyy.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.ui.theme.*
import com.azurlane.blyy.ui.components.adaptiveCardShape

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShipCard(
    ship: Ship,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onWikiClick: (() -> Unit)? = null,
    onOathClick: (() -> Unit)? = null,
    onGalleryClick: (() -> Unit)? = null
) {
    val hapticFeedback = LocalHapticFeedback.current
    
    val rarityColor = remember(ship.rarity) { AppColors.Rarity.getRarityColor(ship.rarity) }
    val rarityGradient = remember(ship.rarity) { AppColors.Rarity.getRarityGradient(ship.rarity) }
    val isHighRarity = remember(ship.rarity) { AppColors.Rarity.isHighRarity(ship.rarity) }
    
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    var showMenu by remember { mutableStateOf(false) }
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed) AppAnimation.Press.StandardScale else 1f,
        animationSpec = AppAnimation.Press.standard(),
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

    val cardShape = adaptiveCardShape()

    Box(
        modifier = modifier
            .padding(AppSpacing.Padding.CardOuter)
            .aspectRatio(0.8f)
            .scale(scale)
            .shadow(
                elevation = elevation,
                shape = cardShape,
                ambientColor = rarityColor.copy(alpha = 0.3f),
                spotColor = rarityColor.copy(alpha = 0.2f)
            )
            .clip(cardShape)
            .border(
                width = if (isHighRarity) 1.5.dp else 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        rarityColor.copy(alpha = borderAlpha),
                        AppColors.Accent.Gold.copy(alpha = borderAlpha * 0.4f),
                        rarityColor.copy(alpha = borderAlpha * 0.5f)
                    )
                ),
                shape = cardShape
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
                    if (onWikiClick != null || onOathClick != null || onGalleryClick != null) {
                        showMenu = true
                    } else {
                        onLongClick()
                    }
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
                    .fillMaxHeight(0.45f)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.3f),
                                Color.Black.copy(alpha = 0.65f),
                                Color.Black.copy(alpha = 0.92f)
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
                    color = if (ship.isFavorite) AppColors.Favorite.Pink else Color.White,
                    style = AppTypography.CardTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(
                            if (ship.isFavorite) {
                                Brush.verticalGradient(
                                    colors = listOf(
                                        AppColors.Favorite.Pink.copy(alpha = 0.35f),
                                        AppColors.Favorite.Pink.copy(alpha = 0.15f)
                                    )
                                )
                            } else {
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.25f),
                                        Color.Transparent
                                    )
                                )
                            },
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
                        color = rarityColor.copy(alpha = 0.55f),
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
                        color = Color.White.copy(alpha = 0.28f)
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
                OathSpecialEffect()
                FavoriteBadge()
            }
        }
        
        if (showMenu) {
            ShipCardDropdownMenu(
                ship = ship,
                expanded = showMenu,
                onDismiss = { showMenu = false },
                onWikiClick = onWikiClick,
                onOathClick = {
                    showMenu = false
                    onLongClick()
                },
                onGalleryClick = onGalleryClick
            )
        }
    }
}

@Composable
private fun ShipCardDropdownMenu(
    ship: Ship,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onWikiClick: (() -> Unit)?,
    onOathClick: (() -> Unit)?,
    onGalleryClick: (() -> Unit)?
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(AppSpacing.Corner.Lg),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = AppSpacing.Elevation.Xl
    ) {
        onWikiClick?.let {
            DropdownMenuItem(
                text = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Language,
                            contentDescription = null,
                            modifier = Modifier.size(AppSpacing.Icon.Md),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.Sm))
                        Text("去Wiki中查看", style = AppTypography.BodyMedium)
                    }
                },
                onClick = {
                    onDismiss()
                    it()
                }
            )
        }
        
        onOathClick?.let {
            DropdownMenuItem(
                text = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (ship.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.Favorite,
                            contentDescription = null,
                            modifier = Modifier.size(AppSpacing.Icon.Md),
                            tint = if (ship.isFavorite) MaterialTheme.colorScheme.error else AppColors.Favorite.Gold
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.Sm))
                        Text(if (ship.isFavorite) "解除誓约" else "誓约", style = AppTypography.BodyMedium)
                    }
                },
                onClick = {
                    onDismiss()
                    it()
                }
            )
        }
        
        onGalleryClick?.let {
            DropdownMenuItem(
                text = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Image,
                            contentDescription = null,
                            modifier = Modifier.size(AppSpacing.Icon.Md),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.Sm))
                        Text("查看立绘", style = AppTypography.BodyMedium)
                    }
                },
                onClick = {
                    onDismiss()
                    it()
                }
            )
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
        initialValue = 0.06f,
        targetValue = 0.22f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(3500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GlowAlpha"
    )

    val edgeGlowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "EdgeGlowAlpha"
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
                    radius = 0.75f
                )
            )
    )

    // 边缘光晕
    Box(
        modifier = Modifier
            .fillMaxSize()
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        rarityColor.copy(alpha = edgeGlowAlpha),
                        rarityColor.copy(alpha = edgeGlowAlpha * 0.3f),
                        rarityColor.copy(alpha = edgeGlowAlpha * 0.6f)
                    )
                ),
                shape = adaptiveCardShape()
            )
    )
}

@Composable
private fun BoxScope.OathSpecialEffect() {
    val infiniteTransition = rememberInfiniteTransition(label = "OathEffect")
    
    val pinkGlowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.22f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PinkGlowAlpha"
    )
    
    val borderGlowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "BorderGlowAlpha"
    )
    
    // 粉色径向光晕
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        AppColors.Favorite.Pink.copy(alpha = pinkGlowAlpha),
                        AppColors.Favorite.PinkLight.copy(alpha = pinkGlowAlpha * 0.4f),
                        Color.Transparent
                    ),
                    radius = 0.85f
                )
            )
    )
    
    // 渐变边框
    Box(
        modifier = Modifier
            .fillMaxSize()
            .border(
                width = 2.5.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        AppColors.Favorite.Pink.copy(alpha = borderGlowAlpha),
                        AppColors.Favorite.PinkLight.copy(alpha = borderGlowAlpha * 0.7f),
                        AppColors.Favorite.PinkDark.copy(alpha = borderGlowAlpha)
                    )
                ),
                shape = adaptiveCardShape()
            )
    )
    
    // 闪光粒子 — 合并为单个 drawBehind 减少布局层级
    val sparkleAlpha0 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ), label = "Sparkle0"
    )
    val sparkleAlpha1 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ), label = "Sparkle1"
    )
    val sparkleAlpha2 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ), label = "Sparkle2"
    )
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val sparkleRadius = 3.dp.toPx()
                val sparkleColor = AppColors.Favorite.PinkLight
                val w = this.size.width
                val h = this.size.height
                drawCircle(
                    color = sparkleColor.copy(alpha = sparkleAlpha0 * 0.9f),
                    radius = sparkleRadius,
                    center = Offset(w * 0.15f, h * 0.15f)
                )
                drawCircle(
                    color = sparkleColor.copy(alpha = sparkleAlpha1 * 0.9f),
                    radius = sparkleRadius,
                    center = Offset(w * 0.85f, h * 0.12f)
                )
                drawCircle(
                    color = sparkleColor.copy(alpha = sparkleAlpha2 * 0.9f),
                    radius = sparkleRadius,
                    center = Offset(w * 0.5f, h * 0.92f)
                )
            }
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
                            AppColors.Favorite.Pink.copy(alpha = glowAlpha),
                            AppColors.Favorite.Pink.copy(alpha = glowAlpha * 0.4f),
                            Color.Transparent
                        )
                    )
                )
        )

        Surface(
            modifier = Modifier.size(28.dp),
            shape = CircleShape,
            color = AppColors.Favorite.Pink,
            shadowElevation = AppSpacing.Elevation.Lg,
            tonalElevation = AppSpacing.Elevation.Sm
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Favorite,
                    contentDescription = "誓约",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
