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
import androidx.compose.material.icons.rounded.Sailing
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.HeartBroken
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.ui.theme.AppElevation
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
    onGalleryClick: (() -> Unit)? = null,
    /** 列表滚动等场景关闭无限动画，保障 60fps */
    decorativeAnimation: Boolean = true
) {
    val hapticFeedback = rememberBlyyHaptics()
    
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
        targetValue = if (isPressed) AppElevation.Level1 else AppElevation.Level2,
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
    val isDark = LocalIsDark.current

    Box(
        modifier = modifier
            .padding(AppSpacing.Padding.CardOuter)
            // 与骨架屏 ShipCardShimmer 共用同一纵横比令牌，加载完成瞬间不再"跳一下"
            .aspectRatio(AppSpacing.Card.AspectRatio)
            .scale(scale)
            // V2 有色阴影规范：环境光保持全阵列统一（Depth 双色），
            // 仅直射光（spot）允许染稀有度色（@25%）——彩色只染直射光，阵列才不花
            .shadow(
                elevation = elevation,
                shape = cardShape,
                ambientColor = if (isDark) AppColors.Depth.AmbientDark else AppColors.Depth.AmbientLight,
                spotColor = rarityColor.copy(alpha = 0.25f)
            )
            .clip(cardShape)
            .border(
                width = if (isHighRarity) 1.5.dp else 1.dp,
                // V2 红线：普通卡片描边禁金（金色只属于誓约/传奇时刻——
                // 传奇档的 rarityColor 本身即金色，誓约金边由 OathSpecialEffect 承载）
                brush = Brush.linearGradient(
                    colors = listOf(
                        rarityColor.copy(alpha = borderAlpha),
                        rarityColor.copy(alpha = borderAlpha * 0.5f)
                    )
                ),
                shape = cardShape
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    hapticFeedback(BlyyHaptic.Tick)
                    onClick()
                },
                onLongClick = {
                    hapticFeedback(BlyyHaptic.LongPress)
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
                shipName = ship.name,
                avatarUrl = ship.avatarUrl,
                borderUrl = ship.borderUrl,
                archiveType = ship.archiveType,
                isOathed = ship.isFavorite,
                contentDescription = ship.name
            )

            if (isHighRarity && decorativeAnimation) {
                RarityGlow(rarityColor = rarityColor, rarityGradient = rarityGradient)
            } else if (isHighRarity) {
                RarityAccentBar(rarityColor = rarityColor)
            }

            // 底部渐晕 — 深海黑（品牌阴影同色族），黑得"深"而不"脏"，比纯黑更贴合主题
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.45f)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                AppColors.Scrim.Base.copy(alpha = 0.32f),
                                AppColors.Scrim.Base.copy(alpha = 0.68f),
                                AppColors.Scrim.Base.copy(alpha = 0.94f)
                            )
                        )
                    )
            )

            if (isHighRarity && decorativeAnimation) {
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
                        bottom = AppSpacing.Md,
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
                                        AppColors.Scrim.Base.copy(alpha = 0.28f),
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
                    // 阵营徽章 — 深海黑底保证白字对比度（稀有度色直接作底时金色/亮青达不到 AA），
                    // 稀有度信息改由渐变描边承载，全稀有度可读且保留色彩识别
                    Surface(
                        shape = RoundedCornerShape(AppSpacing.Corner.Sm),
                        color = AppColors.Scrim.Base.copy(alpha = 0.66f),
                        modifier = Modifier
                            .border(
                                width = AppSpacing.Border.Thin,
                                brush = rarityGradient,
                                shape = RoundedCornerShape(AppSpacing.Corner.Sm)
                            )
                    ) {
                        Text(
                            text = ship.faction,
                            color = Color.White,
                            style = AppTypography.CardLabel,
                            modifier = Modifier.padding(
                                horizontal = AppSpacing.Sm,
                                vertical = 3.dp
                            )
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(AppSpacing.Corner.Sm),
                        color = AppColors.Scrim.Base.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = ship.type,
                            color = Color.White,
                            style = AppTypography.CardLabel,
                            modifier = Modifier.padding(
                                horizontal = AppSpacing.Sm,
                                vertical = 3.dp
                            )
                        )
                    }
                }
            }

            if (ship.isFavorite) {
                if (decorativeAnimation) {
                    OathSpecialEffect()
                } else {
                    OathStaticBorder()
                }
                FavoriteBadge(animated = decorativeAnimation)
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
                            if (ship.isFavorite) Icons.Rounded.HeartBroken else Icons.Rounded.Favorite,
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
    shipName: String,
    avatarUrl: String,
    borderUrl: String?,
    archiveType: String = "DOCK",
    isOathed: Boolean = false,
    contentDescription: String
) {
    val context = LocalContext.current
    // 优先使用本地高清头像（誓约状态优先匹配 _h 婚皮），匹配不到时回退到网络 URL
    val effectiveAvatar = remember(shipName, avatarUrl, archiveType, isOathed) {
        com.azurlane.blyy.util.LocalAvatarResolver.resolveOrDefault(context, shipName, archiveType, isOathed, avatarUrl)
    }
    val request = remember(effectiveAvatar) {
        ImageRequest.Builder(context)
            .data(effectiveAvatar)
            .crossfade(true)
            .build()
    }
    // 使用 AsyncImage 替代 SubcomposeAsyncImage，避免子组合开销，提升网格滚动性能
    // 加载中状态通过背景色体现，错误状态通过 error painter 体现
    Box(modifier = Modifier.fillMaxSize()) {
        // 加载中背景色（图片加载完成后会被覆盖）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        )
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            // 占位/失败改用中性图标：BrokenImage（碎图）在加载中与加载失败两种场景下语义均为误导
            placeholder = rememberVectorPainter(Icons.Rounded.Sailing),
            error = rememberVectorPainter(Icons.Rounded.Sailing)
        )
    }

    if (!borderUrl.isNullOrEmpty()) {
        val borderRequest = remember(borderUrl) {
            ImageRequest.Builder(context)
                .data(borderUrl)
                .crossfade(true)
                .build()
        }
        AsyncImage(
            model = borderRequest,
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
            animation = tween<Float>(3500, easing = AppAnimation.Easings.Standard),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GlowAlpha"
    )

    val edgeGlowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(2800, easing = AppAnimation.Easings.Standard),
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
            animation = tween<Float>(3200, easing = AppAnimation.Easings.Standard),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PinkGlowAlpha"
    )
    
    val borderGlowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(2000, easing = AppAnimation.Easings.Standard),
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
    
    // 闪光粒子 — 合并为单个动画 + 相位偏移，减少并行动画数量
    val sparklePhase by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(2000, easing = AppAnimation.Easings.Standard),
            repeatMode = RepeatMode.Restart
        ), label = "Sparkle"
    )
    // Derive 3 sparkle alphas from a single animation with phase offsets
    val sparkleAlpha0 = sparklePhase
    val sparkleAlpha1 = (sparklePhase + 0.33f) % 1f
    val sparkleAlpha2 = (sparklePhase + 0.66f) % 1f
    
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
private fun RarityAccentBar(rarityColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(
                    color = rarityColor.copy(alpha = 0.85f),
                    topLeft = Offset.Zero,
                    size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)
                )
            }
    )
}

@Composable
private fun OathStaticBorder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .border(
                width = 2.dp,
                color = AppColors.Favorite.Pink.copy(alpha = 0.65f),
                shape = adaptiveCardShape()
            )
    )
}

@Composable
private fun BoxScope.FavoriteBadge(animated: Boolean = true) {
    if (animated) {
        FavoriteBadgeAnimated()
    } else {
        FavoriteBadgeStatic()
    }
}

@Composable
private fun BoxScope.FavoriteBadgeStatic() {
    Surface(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(AppSpacing.Sm)
            .size(28.dp),
        shape = CircleShape,
        color = AppColors.Favorite.Pink,
        shadowElevation = AppElevation.Level2
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = Icons.Rounded.Favorite,
                contentDescription = "誓约",
                tint = Color.White,
                modifier = Modifier.size(AppSpacing.Icon.Sm)
            )
        }
    }
}

@Composable
private fun BoxScope.FavoriteBadgeAnimated() {
    val infiniteTransition = rememberInfiniteTransition(label = "Favorite")
    
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(2200, easing = AppAnimation.Easings.Standard),
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
