package com.example.blyy.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import com.example.blyy.ui.theme.AppColors
import com.example.blyy.ui.theme.AppSpacing
import com.example.blyy.ui.theme.AppTypography
import com.example.blyy.viewmodel.SecretaryShipState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretaryShipModeScreen(
    secretaryState: SecretaryShipState,
    onBack: () -> Unit,
    onRandomFlip: () -> Unit,
    onSelectFromHome: () -> Unit,
    onSelectFromGallery: () -> Unit,
    onSetAutoPlay: (Boolean, Int) -> Unit,
    onClearSecretary: () -> Unit = {},
    onToggleOverlay: (Boolean) -> Unit = {},
    isOverlayEnabled: Boolean = false
) {
    val isDark = isSystemInDarkTheme()
    val glassSurface = if (isDark) AppColors.GlassSurfaceDark else AppColors.GlassSurfaceLight
    var heartExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    "今日秘书舰",
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
            windowInsets = WindowInsets(0.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AppSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Md),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(AppSpacing.Xl))

            Text(
                "选择秘书舰的方式",
                style = AppTypography.TitleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            ModeOptionCard(
                title = "随机翻牌",
                description = "基于稀有度权重随机抽取一名舰娘",
                icon = Icons.Rounded.Casino,
                color = MaterialTheme.colorScheme.primary,
                onClick = onRandomFlip
            )

            ModeOptionCard(
                title = "心有所属",
                description = "指定一名心仪的舰娘作为秘书舰",
                icon = Icons.Rounded.Favorite,
                color = MaterialTheme.colorScheme.secondary,
                onClick = { heartExpanded = !heartExpanded }
            )

            AnimatedVisibility(
                visible = heartExpanded,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = AppSpacing.Xl, top = AppSpacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
                ) {
                    SubOptionCard(
                        title = "从后宅选择",
                        description = "在誓约舰娘中选择",
                        icon = Icons.Rounded.Home,
                        onClick = onSelectFromHome
                    )
                    SubOptionCard(
                        title = "从船坞选择",
                        description = "在船坞档案中选择",
                        icon = Icons.AutoMirrored.Rounded.List,
                        onClick = onSelectFromGallery
                    )
                }
            }

            if (secretaryState.shipName.isNotEmpty()) {
                Spacer(modifier = Modifier.height(AppSpacing.Xl))
                Text(
                    "当前秘书舰：${secretaryState.shipName}",
                    style = AppTypography.BodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                androidx.compose.material3.TextButton(onClick = onClearSecretary) {
                    Text("清除秘书舰", color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.height(AppSpacing.Sm))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(AppSpacing.Corner.Lg),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Column(
                        modifier = Modifier.padding(AppSpacing.Lg),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "桌面悬浮窗",
                                style = AppTypography.TitleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Switch(
                                checked = isOverlayEnabled,
                                onCheckedChange = onToggleOverlay
                            )
                        }
                        Text(
                            "在桌面或其他应用上显示秘书舰",
                            style = AppTypography.BodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.Md))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(AppSpacing.Corner.Lg),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Column(
                        modifier = Modifier.padding(AppSpacing.Lg),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
                    ) {
                        Text(
                            "语音自动播放",
                            style = AppTypography.TitleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("定时播放秘书舰语音", style = AppTypography.BodyMedium)
                            Switch(
                                checked = secretaryState.autoPlayEnabled,
                                onCheckedChange = { onSetAutoPlay(it, secretaryState.autoPlayIntervalMinutes) }
                            )
                        }
                        if (secretaryState.autoPlayEnabled) {
                            Text(
                                "间隔 ${secretaryState.autoPlayIntervalMinutes} 分钟",
                                style = AppTypography.BodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = secretaryState.autoPlayIntervalMinutes.toFloat(),
                                onValueChange = { onSetAutoPlay(true, it.toInt().coerceIn(1, 60)) },
                                valueRange = 1f..60f,
                                steps = 58
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeOptionCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "scale"
    )

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .border(
                1.dp,
                Brush.linearGradient(
                    colors = listOf(color.copy(alpha = 0.5f), color.copy(alpha = 0.1f))
                ),
                RoundedCornerShape(AppSpacing.Corner.Xl)
            ),
        shape = RoundedCornerShape(AppSpacing.Corner.Xl),
        color = color.copy(alpha = 0.08f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(color.copy(alpha = 0.3f), Color.Transparent)
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(28.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = AppTypography.TitleMedium,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                Text(
                    text = description,
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SubOptionCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.border(
            AppSpacing.Border.Thin,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
            RoundedCornerShape(AppSpacing.Corner.Lg)
        ),
        shape = RoundedCornerShape(AppSpacing.Corner.Lg),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = AppTypography.TitleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
