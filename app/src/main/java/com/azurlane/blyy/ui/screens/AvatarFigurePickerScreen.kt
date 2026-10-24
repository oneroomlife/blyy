package com.azurlane.blyy.ui.screens

// 自定义头像·立绘选择页：展示当前舰娘全部皮肤立绘，点选进入 3:4 自由裁切

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyEmptyState
import com.azurlane.blyy.ui.components.BlyyErrorState
import com.azurlane.blyy.ui.components.BlyyHaptic
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.components.adaptiveCardShape
import com.azurlane.blyy.ui.components.rememberBlyyHaptics
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.viewmodel.ShipGalleryViewModel

/**
 * @param onCropFigure 选中某张立绘后进入裁切（url + 皮肤名）
 */
@Composable
fun AvatarFigurePickerScreen(
    shipName: String,
    viewModel: ShipGalleryViewModel,
    onBack: () -> Unit,
    onCropFigure: (figureUrl: String, skinName: String) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptic = rememberBlyyHaptics()

    LaunchedEffect(shipName) {
        if (state.gallery?.shipName != shipName) viewModel.loadGallery(shipName)
    }

    AdaptiveScreenBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            BlyyTopBar(
                title = "选择立绘",
                subtitle = "选择 ${shipName} 的立绘进入裁切",
                onBackClick = onBack
            )

            val illustrations = state.gallery?.illustrations ?: emptyList()
            when {
                state.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.error != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        BlyyErrorState(
                            message = state.error ?: "加载失败",
                            onRetry = { viewModel.loadGallery(shipName) }
                        )
                    }
                }
                illustrations.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        BlyyEmptyState(
                            icon = Icons.Outlined.SearchOff,
                            title = "暂无立绘数据",
                            description = "无法获取该舰娘的立绘资源，可稍后重试或改用相册选择",
                            actionLabel = "返回",
                            onAction = onBack
                        )
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 110.dp),
                        contentPadding = PaddingValues(
                            start = AppSpacing.Screen.Horizontal,
                            end = AppSpacing.Screen.Horizontal,
                            top = AppSpacing.Lg,
                            bottom = AppSpacing.Xxl
                        ),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(illustrations, key = { _, it -> it.second }) { _, (skinName, url) ->
                            FigureThumb(
                                skinName = skinName,
                                url = url,
                                onClick = {
                                    haptic(BlyyHaptic.Tick)
                                    onCropFigure(url, skinName)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FigureThumb(
    skinName: String,
    url: String,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .aspectRatio(AppSpacing.Card.AspectRatio)
            .clip(adaptiveCardShape())
            .border(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                adaptiveCardShape()
            )
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = remember(url) {
                ImageRequest.Builder(context)
                    .data(url)
                    .crossfade(true)
                    .build()
            },
            contentDescription = skinName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            placeholder = rememberVectorPainter(Icons.Rounded.Image),
            error = rememberVectorPainter(Icons.Rounded.BrokenImage)
        )
        // 底部渐晕 + 皮肤名（与 ShipCard 信息条同视觉）
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(44.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))
                    )
                )
        )
        Text(
            text = skinName,
            style = AppTypography.LabelSmall,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Xs, vertical = AppSpacing.Xs)
        )
    }
}
