package com.azurlane.blyy.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Scale
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyPrimaryButton
import com.azurlane.blyy.ui.components.BlyySecondaryButton
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图片裁剪页面
 *
 * 自建裁剪组件，用户可拖动/缩放图片，方形遮罩指示裁剪区域。
 * 确认后由 ViewModel 按裁剪参数解码原图对应区域，输出 432×432 正方形 Bitmap。
 *
 * 交互：
 * - 单指拖动：平移图片
 * - 双指缩放：放大图片（1x ~ 5x，不可小于裁剪框）
 * - 裁剪框固定为屏幕宽度的 80%，正方形
 */
@Composable
fun ImageCropperScreen(
    imageUri: Uri,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val switching by viewModel.iconSwitching.collectAsStateWithLifecycle()
    val isProcessing = switching is SettingsViewModel.IconSwitchState.Switching

    // 裁剪状态：缩放 + 偏移
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // 裁剪框尺寸（屏幕宽度的 80%，px）
    val cropBoxSizePx = with(LocalDensity.current) {
        (LocalConfiguration.current.screenWidthDp.dp * 0.8f).toPx()
    }

    // 解码原图尺寸（仅用于日志/调试，实际裁剪由 ViewModel 处理）
    LaunchedEffect(imageUri) {
        withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(imageUri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }
    }

    val imageRequest = remember(imageUri) {
        ImageRequest.Builder(context)
            .data(imageUri)
            .scale(Scale.FILL)
            .build()
    }

    AdaptiveScreenBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            BlyyTopBar(
                title = "裁剪图标",
                subtitle = "拖动调整位置，双指缩放",
                onBackClick = onBack
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppSpacing.Screen.Horizontal),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Lg),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── 裁剪区域 ──
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .fillMaxWidth(0.8f)
                            .clip(RectangleShape)
                            .border(2.dp, Color.White, RectangleShape)
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                                    // newScale >= 1f 保证 maxOffset 始终非负，
                                    // 避免 coerceIn(-maxOffset, maxOffset) 出现 min > max 的崩溃
                                    val maxOffset = cropBoxSizePx * (newScale - 1f) / 2f
                                    scale = newScale
                                    offset = Offset(
                                        x = (offset.x + pan.x).coerceIn(-maxOffset, maxOffset),
                                        y = (offset.y + pan.y).coerceIn(-maxOffset, maxOffset)
                                    )
                                }
                            }
                    ) {
                        // 图片 — 填满裁剪框，应用用户缩放和偏移
                        // clip(RectangleShape) 裁掉缩放后溢出裁剪框的部分，
                        // border 在裁剪框边缘绘制白色边界线
                        AsyncImage(
                            model = imageRequest,
                            contentDescription = "待裁剪图片",
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offset.x,
                                    translationY = offset.y
                                ),
                            contentScale = ContentScale.Crop
                        )
                    }
                }

                // ── 操作按钮 ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
                ) {
                    BlyySecondaryButton(
                        text = "取消",
                        onClick = onBack,
                        modifier = Modifier.weight(1f)
                    )
                    BlyyPrimaryButton(
                        text = if (isProcessing) "处理中..." else "应用",
                        onClick = {
                            viewModel.applyCustomIconFromBitmap(
                                imageUri = imageUri,
                                scale = scale,
                                offsetX = offset.x,
                                offsetY = offset.y,
                                cropBoxSizePx = cropBoxSizePx,
                                onApplied = onBack
                            )
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isProcessing
                    )
                }

                Spacer(modifier = Modifier.padding(bottom = AppSpacing.Lg))
            }
        }
    }
}
