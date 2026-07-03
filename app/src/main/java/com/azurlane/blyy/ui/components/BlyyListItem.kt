package com.azurlane.blyy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.azurlane.blyy.ui.theme.AppColors
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.BlyyShapes
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.ui.theme.LocalUiStyle
import com.azurlane.blyy.ui.theme.isCommandCenter

/**
 * 统一列表项 — 替代各 Screen 重复实现的设置行/导航行。
 *
 * 设计规范：
 * - 触摸高度 ≥ [AppSpacing.Height.ListItem]（64dp）
 * - 指挥中心风格：切角容器 + 渐变描边
 * - 经典风格：简洁背景 + 底部分隔线
 * - 支持 leadingIcon / trailingIcon / 描述文字
 *
 * @param title 主标题
 * @param subtitle 副标题/描述（可选）
 * @param leadingIcon 前置图标（可选）
 * @param trailingIcon 后置图标（默认右箭头，设为 null 隐藏）
 * @param onClick 点击回调（null 表示不可点击）
 */
@Composable
fun BlyyListItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = Icons.AutoMirrored.Filled.KeyboardArrowRight,
    onClick: (() -> Unit)? = null
) {
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val isDark = LocalIsDark.current

    val containerColor = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.3f)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(AppSpacing.Height.ListItem)
            .clip(if (isCommandCenter) BlyyShapes.PanelSmall else RoundedCornerShape(AppSpacing.Corner.Md))
            .background(containerColor)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = AppSpacing.Padding.ListItemHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
    ) {
        leadingIcon?.let {
            Box(
                modifier = Modifier
                    .size(AppSpacing.Icon.Xxl)
                    .clip(RoundedCornerShape(AppSpacing.Corner.Chamfer))
                    .background(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(AppSpacing.Icon.Md)
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                style = AppTypography.TitleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.let {
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
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(AppSpacing.Icon.Lg)
            )
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
