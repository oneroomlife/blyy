package com.azurlane.blyy.ui.screens.gallery

// 舰娘卡片长按菜单 — 船坞界面的精致底部弹窗
//
// 取代 ShipCard 内置 DropdownMenu 在船坞场景的职责（旧菜单三项纯文字行，
// 无舰娘上下文、无层级）。结构：
// - 头部：头像（与卡片同解析链，所见即所得）+ 舰名 + 稀有度徽标 + 自定义图标记
// - 快捷操作行：查看立绘 / 去Wiki / 誓约
// - 自定义图片区：从舰娘立绘选（DOCK）/ 从相册选择 两张入口卡
// - 恢复默认头像（存在自定义时显示）

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
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.HeartBroken
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.ui.components.BlyyBottomSheet
import com.azurlane.blyy.ui.components.BlyyHaptic
import com.azurlane.blyy.ui.components.adaptiveCardShape
import com.azurlane.blyy.ui.components.rememberBlyyHaptics
import com.azurlane.blyy.ui.theme.AppColors
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.util.CustomAvatarManager
import com.azurlane.blyy.util.LocalAvatarResolver

/**
 * @param onPickFigure 「从舰娘立绘选择」入口（仅 DOCK 显示，宿主自行过滤）
 * @param onPickAlbum 「从相册选择」入口
 */
@Composable
fun ShipContextMenuSheet(
    ship: Ship,
    onDismiss: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenWiki: () -> Unit,
    onToggleOath: () -> Unit,
    onPickFigure: () -> Unit,
    onPickAlbum: () -> Unit,
    onResetAvatar: () -> Unit
) {
    val haptic = rememberBlyyHaptics()
    val context = LocalContext.current

    // 自定义头像存在性：随 revision 即时刷新（应用/恢复后菜单标记同步）
    val avatarRevision by CustomAvatarManager.revision.collectAsStateWithLifecycle()
    val hasCustomAvatar by produceState(false, ship.name, ship.archiveType, avatarRevision) {
        value = CustomAvatarManager.resolveFile(context, ship.name, ship.archiveType) != null
    }

    val rarityColor = remember(ship.rarity) { AppColors.Rarity.getRarityColor(ship.rarity) }
    val isDock = ship.archiveType != "STUDENT"

    BlyyBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Screen.Horizontal)
                .padding(bottom = AppSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
        ) {
            // ── 头部：所见即所得的头像预览 + 舰娘信息 ──
            MenuHeader(ship = ship, rarityColor = rarityColor, hasCustomAvatar = hasCustomAvatar)

            // ── 快捷操作（三列）──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
            ) {
                QuickAction(
                    icon = Icons.Rounded.Image,
                    label = "查看立绘",
                    accent = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        haptic(BlyyHaptic.Tick)
                        onDismiss()
                        onOpenGallery()
                    }
                )
                QuickAction(
                    icon = Icons.Rounded.Language,
                    label = "Wiki",
                    accent = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        haptic(BlyyHaptic.Tick)
                        onDismiss()
                        onOpenWiki()
                    }
                )
                QuickAction(
                    icon = if (ship.isFavorite) Icons.Rounded.HeartBroken else Icons.Rounded.Favorite,
                    label = if (ship.isFavorite) "解除誓约" else "誓约",
                    accent = if (ship.isFavorite) MaterialTheme.colorScheme.error else AppColors.Favorite.Gold,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        haptic(BlyyHaptic.LongPress)
                        onDismiss()
                        onToggleOath()
                    }
                )
            }

            // ── 自定义图片区 ──
            MenuSectionLabel(text = "自定义图片", tint = MaterialTheme.colorScheme.primary)
            CustomSourceCard(
                icon = Icons.Rounded.Palette,
                title = "从舰娘立绘选择",
                description = if (isDock) "挑选 ${ship.name} 的皮肤立绘，自由裁切" else "学生档案暂不支持，可从相册选择",
                accent = MaterialTheme.colorScheme.tertiary,
                enabled = isDock,
                onClick = {
                    haptic(BlyyHaptic.Tick)
                    onDismiss()
                    onPickFigure()
                }
            )
            CustomSourceCard(
                icon = Icons.Rounded.PhotoLibrary,
                title = "从相册选择",
                description = "选取手机中的任意图片，自由裁切",
                accent = MaterialTheme.colorScheme.secondary,
                enabled = true,
                onClick = {
                    haptic(BlyyHaptic.Tick)
                    onDismiss()
                    onPickAlbum()
                }
            )

            // ── 恢复默认（存在自定义时）──
            if (hasCustomAvatar) {
                CustomSourceCard(
                    icon = Icons.Rounded.Restore,
                    title = "恢复默认头像",
                    description = "移除自定义图片，恢复本地/网络头像",
                    accent = MaterialTheme.colorScheme.error,
                    enabled = true,
                    onClick = {
                        haptic(BlyyHaptic.Tick)
                        CustomAvatarManager.remove(context, ship.name, ship.archiveType)
                        onDismiss()
                        onResetAvatar()
                    }
                )
            }
        }
    }
}

/** 头部：头像预览走与卡片完全相同的解析链（自定义 > 本地 assets > 网络） */
@Composable
private fun MenuHeader(ship: Ship, rarityColor: Color, hasCustomAvatar: Boolean) {
    val context = LocalContext.current
    val avatarRevision by CustomAvatarManager.revision.collectAsStateWithLifecycle()

    val avatarModel = remember(ship.name, ship.avatarUrl, ship.archiveType, ship.isFavorite, avatarRevision) {
        CustomAvatarManager.resolveOrDefault(
            context, ship.name, ship.archiveType,
            LocalAvatarResolver.resolveOrDefault(context, ship.name, ship.archiveType, ship.isFavorite, ship.avatarUrl)
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AppSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        listOf(rarityColor.copy(alpha = 0.9f), rarityColor.copy(alpha = 0.35f))
                    ),
                    shape = adaptiveCardShape()
                )
                .padding(2.dp)
                .clip(adaptiveCardShape())
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(avatarModel).crossfade(true).build(),
                contentDescription = ship.name,
                modifier = Modifier.size(60.dp),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(modifier = Modifier.width(AppSpacing.Md))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = ship.name,
                    style = AppTypography.TitleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (ship.isFavorite) AppColors.Favorite.Pink else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (ship.isFavorite) {
                    Spacer(modifier = Modifier.width(AppSpacing.Sm))
                    Icon(
                        Icons.Rounded.Favorite,
                        contentDescription = "已誓约",
                        tint = AppColors.Favorite.Pink,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${ship.faction} · ${ship.type} · ${ship.rarity}",
                style = AppTypography.LabelSmall,
                color = rarityColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (hasCustomAvatar) {
            Surface(
                shape = RoundedCornerShape(AppSpacing.Corner.Sm),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
            ) {
                Text(
                    text = "自定义图",
                    style = AppTypography.LabelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = AppSpacing.Sm, vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun MenuSectionLabel(text: String, tint: Color) {
    Text(
        text = text,
        style = AppTypography.LabelLarge,
        color = tint,
        fontWeight = FontWeight.SemiBold
    )
}

/** 快捷操作格：径向渐变图标底 + 按压缩放反馈（与 ModeOptionCard 同视觉语言） */
@Composable
private fun QuickAction(
    icon: ImageVector,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        label = "QuickActionScale"
    )

    Surface(
        shape = RoundedCornerShape(AppSpacing.Corner.Md),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = AppSpacing.Elevation.Sm,
        modifier = modifier
            .scale(pressScale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AppSpacing.Md),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        brush = Brush.radialGradient(
                            listOf(accent.copy(alpha = 0.32f), accent.copy(alpha = 0.06f))
                        ),
                        shape = CircleShape
                    )
                    .border(1.dp, accent.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = label, tint = accent, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.height(AppSpacing.Sm))
            Text(
                text = label,
                style = AppTypography.LabelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 自定义图片来源入口卡（列表式宽卡） */
@Composable
private fun CustomSourceCard(
    icon: ImageVector,
    title: String,
    description: String,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val contentAlpha = if (enabled) 1f else 0.45f
    Surface(
        shape = RoundedCornerShape(AppSpacing.Corner.Md),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = AppSpacing.Elevation.Sm,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        brush = Brush.radialGradient(
                            listOf(accent.copy(alpha = 0.3f), Color.Transparent)
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = title,
                    tint = accent.copy(alpha = contentAlpha),
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = AppTypography.TitleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
                )
                Text(
                    text = description,
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
