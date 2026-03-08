package com.example.blyy.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import coil.compose.AsyncImage
import com.example.blyy.data.model.Ship
import com.example.blyy.ui.theme.AppColors
import com.example.blyy.ui.theme.AppSpacing
import com.example.blyy.ui.theme.AppTypography
import com.example.blyy.viewmodel.SecretaryShipIntent
import com.example.blyy.viewmodel.SecretaryShipViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretaryShipRandomScreen(
    viewModel: SecretaryShipViewModel,
    onBack: () -> Unit,
    onComplete: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var revealed by remember { mutableStateOf(false) }

    LaunchedEffect(state.flipRevealedShip) {
        if (state.flipRevealedShip != null) {
            revealed = true
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    "随机翻牌",
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

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(AppSpacing.Lg),
            contentAlignment = Alignment.Center
        ) {
            when {
                state.isFlipping && !revealed -> {
                    FlipCardAnimation(
                        onFlipComplete = {
                            viewModel.onIntent(SecretaryShipIntent.SelectRandom)
                        }
                    )
                }
                state.isLoadingVoices && state.flipRevealedShip != null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Lg)
                    ) {
                        CircularProgressIndicator()
                        Text("正在加载秘书舰…", style = AppTypography.BodyMedium)
                    }
                }
                state.flipRevealedShip != null && revealed -> {
                    RevealedShipCard(
                        ship = state.flipRevealedShip!!,
                        onConfirm = {
                            viewModel.confirmFlipAndClose()
                            onComplete()
                        }
                    )
                }
                else -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Xl)
                    ) {
                        Icon(
                            Icons.Rounded.Casino,
                            contentDescription = null,
                            modifier = Modifier.size(80.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        )
                        Text(
                            "点击下方按钮随机抽取",
                            style = AppTypography.BodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        androidx.compose.material3.FilledTonalButton(
                            onClick = {
                                viewModel.onIntent(SecretaryShipIntent.StartFlipAnimation)
                            },
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .height(52.dp)
                        ) {
                            Icon(Icons.Rounded.Casino, contentDescription = null, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.size(AppSpacing.Sm))
                            Text("开始翻牌")
                        }
                    }
                }
            }
        }
    }

}

@Composable
private fun FlipCardAnimation(onFlipComplete: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "flip")
    val rotationDegrees by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    val scaleAmount by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1500)
        onFlipComplete()
    }

    Box(
        modifier = Modifier
            .size(180.dp)
            .graphicsLayer {
                scaleX = scaleAmount
                scaleY = scaleAmount
                rotationY = rotationDegrees
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
            }
            .clip(RoundedCornerShape(AppSpacing.Corner.Xl))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                    )
                )
            )
            .border(
                2.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                RoundedCornerShape(AppSpacing.Corner.Xl)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.Casino,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun RevealedShipCard(
    ship: Ship,
    onConfirm: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "reveal"
    )
    val rarityGradient = remember(ship.rarity) { AppColors.Rarity.getRarityGradient(ship.rarity) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Lg),
        modifier = Modifier.scale(scale)
    ) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(AppSpacing.Corner.Xl))
                .border(
                    3.dp,
                    rarityGradient,
                    RoundedCornerShape(AppSpacing.Corner.Xl)
                )
        ) {
            AsyncImage(
                model = ship.avatarUrl,
                contentDescription = ship.name,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(AppSpacing.Corner.Xl)),
                contentScale = ContentScale.Crop
            )
        }
        Text(
            ship.name,
            style = AppTypography.TitleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            ship.rarity,
            style = AppTypography.BodyMedium,
            color = AppColors.Rarity.getRarityColor(ship.rarity)
        )
        androidx.compose.material3.FilledTonalButton(
            onClick = onConfirm
        ) {
            Text("确认为今日秘书舰")
        }
    }
}
