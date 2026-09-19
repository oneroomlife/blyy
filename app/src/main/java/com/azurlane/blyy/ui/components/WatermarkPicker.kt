package com.azurlane.blyy.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.util.WatermarkAssets
import com.azurlane.blyy.util.WatermarkEntry

/** 缩略图解码目标边长（px）：216px 的 _small 资源按原生分辨率解码，保证清晰 */
private const val THUMB_DECODE_MAX_PX = 256

/**
 * 水印选择器 — 相机页底部弹层与编辑页共用。
 *
 * 横向滚动缩略图列表：
 * - 首项为「无水印」（不叠加任何水印，直接导出原图）
 * - 缩略图优先使用 `_small` 资源，缺失时回退正式图（按目标尺寸降采样解码）
 * - 选中态：主色描边 + 主色容器底色 + 轻微放大，反馈明显但克制
 * - 单个资源解码失败显示占位图标，不影响整列表
 */
@Composable
fun WatermarkPickerRow(
    watermarks: List<WatermarkEntry>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    thumbSize: Dp = 64.dp
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = AppSpacing.Lg)
    ) {
        item(key = "none") {
            WatermarkThumbCard(
                label = "无水印",
                assetPath = null,
                isSelected = selectedId == null,
                onSelect = { onSelect(null) },
                thumbSize = thumbSize
            )
        }
        items(watermarks, key = { it.id }) { entry ->
            WatermarkThumbCard(
                label = entry.displayName,
                assetPath = entry.preferredThumbPath,
                isSelected = selectedId == entry.id,
                onSelect = { onSelect(entry.id) },
                thumbSize = thumbSize
            )
        }
    }
}

/**
 * 直接解码 assets 渲染的相框图。
 * 不走 Coil 的 asset URI 链路：相框 PNG 大面积透明，垫底占位图标会从透明区域
 * 透出、被误认为加载失败；直接解码可精确控制加载中/失败的表现。
 * 解码完成前（及失败时）不渲染任何内容，由调用方决定占位表现。
 */
@Composable
fun WatermarkAssetImage(
    assetPath: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    maxSidePx: Int = THUMB_DECODE_MAX_PX,
    contentScale: ContentScale = ContentScale.Fit
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, assetPath, maxSidePx) {
        value = WatermarkAssets.decodeScaled(context, assetPath, maxSidePx)
    }
    bitmap?.let { bmp ->
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier
        )
    }
}

/** 缩略图加载状态：加载中 / 解码失败 / 就绪 */
private sealed interface ThumbState {
    data object Loading : ThumbState
    data object Failed : ThumbState
    data class Ready(val bitmap: Bitmap) : ThumbState
}

@Composable
private fun WatermarkThumbCard(
    label: String,
    assetPath: String?,
    isSelected: Boolean,
    onSelect: () -> Unit,
    thumbSize: Dp
) {
    val accent = MaterialTheme.colorScheme.primary
    val bg by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            else -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
        },
        label = "wmThumbBg"
    )
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.08f else 1f,
        animationSpec = tween(200),
        label = "wmThumbScale"
    )
    val context = LocalContext.current

    val thumbState by produceState<ThumbState>(ThumbState.Loading, assetPath) {
        value = if (assetPath == null) {
            ThumbState.Loading // "无水印"入口不走本状态渲染
        } else {
            WatermarkAssets.decodeScaled(context, assetPath, THUMB_DECODE_MAX_PX)
                ?.let { ThumbState.Ready(it) } ?: ThumbState.Failed
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(thumbSize)
                .scale(scale)
                .clip(RoundedCornerShape(AppSpacing.Corner.Md))
                .background(bg)
                .border(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) accent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(AppSpacing.Corner.Md)
                )
                .clickable(onClick = onSelect),
            contentAlignment = Alignment.Center
        ) {
            when (val st = thumbState) {
                is ThumbState.Ready -> Image(
                    bitmap = st.bitmap.asImageBitmap(),
                    contentDescription = label,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(AppSpacing.Xs)
                )
                // 资源解码失败（损坏/格式异常）才显示碎图图标
                ThumbState.Failed -> Icon(
                    Icons.Rounded.BrokenImage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(thumbSize / 2.2f)
                )
                else -> Unit // 加载中或"无水印"入口：无占位图标
            }
        }
        Spacer(Modifier.height(AppSpacing.Xs))
        Text(
            text = label,
            style = AppTypography.LabelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (isSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(thumbSize + 16.dp) // 限制宽度避免长名挤压布局
        )
    }
}
