package com.azurlane.blyy.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.azurlane.blyy.ui.theme.AppAnimation
import com.azurlane.blyy.ui.theme.AppColors
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.BlyyShapes
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.ui.theme.LocalUiStyle
import com.azurlane.blyy.ui.theme.isCommandCenter
import com.azurlane.blyy.ui.theme.isWatchScreen

/**
 * 统一列表项 — 设置行/导航行的唯一实现（V2 §6.9 规格）。
 *
 * 设计规范：
 * - 触摸高度 = [AppSpacing.Height.ListItem]（64dp），可点击时按压缩放 0.98
 * - 图标徽章 40dp（手表 32dp）：指挥中心风格为 accentColor 受光径向渐变 + 细描边，
 *   经典风格为容器色圆底 —— 与 [BlyySettingsRow] 的徽章规格一致
 * - [asCard] = true 时自带 [BlyyPanel] 外壳（设置类页面「每个组件独立成卡」定稿风格，
 *   配合 BlyySectionPanel(separateItems = true) 使用）；false 时为扁平行
 *
 * @param title 主标题（单行省略）
 * @param subtitle 副标题/描述（可选，单行省略）
 * @param leadingIcon 前置图标（可选，着 [accentColor]）
 * @param accentColor 徽章/图标强调色
 * @param trailingIcon 后置图标（默认右箭头，设为 null 隐藏）
 * @param onClick 点击回调（null 表示不可点击）
 * @param asCard 自带面板外壳
 */
@Composable
fun BlyyListItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    trailingIcon: ImageVector? = Icons.AutoMirrored.Rounded.ArrowForward,
    onClick: (() -> Unit)? = null,
    asCard: Boolean = false
) {
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val isWatch = isWatchScreen()

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && onClick != null) AppAnimation.Press.StandardScale else 1f,
        animationSpec = AppAnimation.Press.standard(),
        label = "listItemPress"
    )

    val rowContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(AppSpacing.Height.ListItem)
                .clip(if (isCommandCenter) BlyyShapes.PanelSmall else RoundedCornerShape(AppSpacing.Corner.Md))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isPressed) 0.10f else 0f))
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onClick
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = AppSpacing.Padding.ListItemHorizontal),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
        ) {
            leadingIcon?.let {
                Box(
                    modifier = Modifier
                        .size(if (isWatch) 32.dp else 40.dp)
                        .then(
                            if (isCommandCenter) {
                                Modifier
                                    .background(
                                        brush = Brush.radialGradient(
                                            colors = listOf(
                                                accentColor.copy(alpha = 0.15f),
                                                Color.Transparent
                                            )
                                        ),
                                        shape = CircleShape
                                    )
                                    .border(
                                        width = AppSpacing.Border.Thin,
                                        color = accentColor.copy(alpha = 0.3f),
                                        shape = CircleShape
                                    )
                            } else {
                                Modifier.background(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                    CircleShape
                                )
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        modifier = Modifier.size(if (isWatch) 16.dp else 20.dp),
                        tint = accentColor
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = AppTypography.TitleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                subtitle?.let {
                    Spacer(modifier = Modifier.height(AppSpacing.Xxs))
                    Text(
                        text = it,
                        style = AppTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            trailingIcon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    if (asCard) {
        BlyyPanel(modifier = modifier.fillMaxWidth()) {
            Box(Modifier.scale(scale)) { rowContent() }
        }
    } else {
        Box(modifier = modifier.fillMaxWidth()) {
            Box(Modifier.scale(scale)) { rowContent() }
        }
    }
}

/**
 * 统一 Snackbar 宿主 — 替代各 Screen 直接使用 Toast。
 *
 * 使用方式：
 * ```
 * val snackbarHostState = remember { SnackbarHostState() }
 * // 在 Scaffold 中传入 snackbarHost = { BlyySnackbarHost(snackbarHostState) }
 * // 触发：scope.launch { snackbarHostState.showSnackbar("消息") }
 * ```
 */
@Composable
fun BlyySnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier
    ) { data ->
        val isCommandCenter = LocalUiStyle.current.isCommandCenter()
        Snackbar(
            modifier = Modifier
                .clip(if (isCommandCenter) BlyyShapes.PanelSmall else RoundedCornerShape(AppSpacing.Corner.Md))
                .border(
                    width = AppSpacing.Border.Thin,
                    brush = AppColors.Gradient.PanelBorder,
                    shape = if (isCommandCenter) BlyyShapes.PanelSmall else RoundedCornerShape(AppSpacing.Corner.Md)
                ),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Text(
                text = data.visuals.message,
                style = AppTypography.BodyMedium
            )
        }
    }
}
