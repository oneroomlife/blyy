package com.azurlane.blyy.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.azurlane.blyy.ui.components.BlyyButton
import com.azurlane.blyy.ui.components.BlyyButtonVariant

object GameStyles {

    object Card {
        val CornerSize: androidx.compose.ui.unit.Dp get() = AppSpacing.Game.Card.CornerSize
        val Elevation: androidx.compose.ui.unit.Dp get() = AppSpacing.Game.Card.Elevation
        val InnerPadding: androidx.compose.ui.unit.Dp get() = AppSpacing.Game.Card.InnerPadding
        val OuterPadding: androidx.compose.ui.unit.Dp get() = AppSpacing.Game.Card.OuterPadding
    }

    object Button {
        val Height: androidx.compose.ui.unit.Dp get() = AppSpacing.Game.Button.Height
        val CornerSize: androidx.compose.ui.unit.Dp get() = AppSpacing.Game.Button.CornerSize
        val IconSize: androidx.compose.ui.unit.Dp get() = AppSpacing.Game.Button.IconSize
    }

    object Input {
        val Height: androidx.compose.ui.unit.Dp get() = AppSpacing.Game.Input.Height
        val CornerSize: androidx.compose.ui.unit.Dp get() = AppSpacing.Game.Input.CornerSize
    }

    object Score {
        val ChipCornerSize: androidx.compose.ui.unit.Dp get() = AppSpacing.Game.Score.ChipCornerSize
        val BannerCornerSize: androidx.compose.ui.unit.Dp get() = AppSpacing.Game.Score.BannerCornerSize
    }

    object Animation {
        val PressScale = 0.97f
        val HoverScale = 1.02f

        val enterAnimation: AnimationSpec<Float> = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        )

        val scaleAnimation: AnimationSpec<Float> = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        )
    }

    object Colors {
        @Composable
        fun gradientBackground(isDark: Boolean): Brush {
            return if (isDark) {
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        MaterialTheme.colorScheme.surface
                    )
                )
            } else {
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                        MaterialTheme.colorScheme.surface
                    )
                )
            }
        }

        @Composable
        fun cardGradient(isDark: Boolean): Brush {
            return if (isDark) {
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                )
            } else {
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                )
            }
        }
    }
}

@Composable
fun GameScoreChip(
    totalScore: Int,
    color: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer
) {
    Surface(
        shape = RoundedCornerShape(GameStyles.Score.ChipCornerSize),
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Star,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "$totalScore",
                style = AppTypography.TitleMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

@Composable
fun GameScoreBanner(
    score: Int,
    text: String = "本题可得",
    color: Color = MaterialTheme.colorScheme.secondary,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(GameStyles.Score.BannerCornerSize),
        color = containerColor.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Star,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp)
            )
            Text(
                "$text $score 分",
                style = AppTypography.BodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
fun GameHintBanner(
    text: String,
    icon: ImageVector,
    color: Color = MaterialTheme.colorScheme.tertiary,
    containerColor: Color = MaterialTheme.colorScheme.tertiaryContainer
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = containerColor.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text,
                style = AppTypography.BodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
fun GameResultCard(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    iconColor: Color,
    containerColor: Color,
    content: @Composable () -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = containerColor.copy(alpha = 0.5f),
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(28.dp)
                )
                Column {
                    Text(
                        title,
                        style = AppTypography.TitleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    subtitle?.let {
                        Text(
                            it,
                            style = AppTypography.BodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            content()
        }
    }
}

@Composable
fun GameWrongCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
    ) {
        Text(
            "好像不太对，再想想？",
            style = AppTypography.BodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
fun GameButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    isPrimary: Boolean = true
) {
    BlyyButton(
        text = text,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        icon = icon,
        variant = if (isPrimary) BlyyButtonVariant.Primary else BlyyButtonVariant.Secondary
    )
}

@Composable
fun GameStatItem(
    label: String,
    value: String,
    subValue: String,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = AppTypography.LabelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = AppTypography.TitleMediumBold)
            Text(subValue, style = AppTypography.LabelSmall, color = accentColor)
        }
    }
}

@Composable
fun GameGlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val isDark = LocalIsDark.current
    val glassSurface = if (isDark) AppColors.GlassSurfaceDark else AppColors.GlassSurfaceLight
    val glassBorder = if (isDark) AppColors.GlassBorderDark else AppColors.GlassBorderLight

    Box(
        modifier = modifier
            .clip(BlyyShapes.PanelLarge)
            .background(glassSurface.copy(alpha = 0.9f))
            .border(
                width = AppSpacing.Border.Thin,
                brush = Brush.linearGradient(
                    colors = listOf(
                        glassBorder,
                        AppColors.Accent.Gold.copy(alpha = 0.2f),
                        glassBorder.copy(alpha = 0.3f)
                    )
                ),
                shape = BlyyShapes.PanelLarge
            )
    ) {
        content()
    }
}

@Composable
fun GameIconBadge(
    icon: ImageVector,
    backgroundColor: Color,
    iconTint: Color,
    size: Int = 40
) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        backgroundColor.copy(alpha = 0.2f),
                        Color.Transparent
                    )
                ),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size((size * 0.6).dp)
        )
    }
}
