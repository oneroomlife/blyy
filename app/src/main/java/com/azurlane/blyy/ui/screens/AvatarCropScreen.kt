package com.azurlane.blyy.ui.screens

// 自定义头像·裁切页：3:4（舰娘卡片纵横比）自由缩放裁切并应用
//
// v2 裁切模型（与 CustomAvatarManager.applyFromCrop 严格一致）：
// - 预览基准 ContentScale.Fit：初始（scale=1）完整可见整张立绘。立绘多为
//   2:3~1:2 竖长图，旧版 Crop 基准在最小缩放时只显示中段（头脚被裁），
//   表现为"立绘被错误裁切过导致不全"；Fit 基准下用户从全图出发自选范围。
// - 裁剪框尺寸用 onSizeChanged 实测：旧版按屏宽推导（0.8×屏宽）与真实框
//   （0.8×(屏宽−左右padding)）有 ~10% 偏差，平移量换算进原图坐标随偏移
//   放大，表现为"选区与实际应用范围不一致"。
// - 缩放带手势质心补偿（手指下的图像点在缩放前后保持不动），平移按
//   "图像超出框的一半"两轴独立 clamp（Fit 下未覆盖轴 clamp=0）。
// - 图像未覆盖区域为黑边（OUTPUT_BANDS_COLOR），应用输出以同色补齐——
//   预览与产物逐像素一致。
// 源物化：网络立绘先经 Coil 下载到缓存文件，相册 content Uri 先拷贝到私有缓存
// （读取权限是临时的，不能推迟到应用时再读），本页裁切与应用只面对 file:// 源。

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.Coil
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyPrimaryButton
import com.azurlane.blyy.ui.components.BlyySecondaryButton
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.util.CustomAvatarManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 缩放上限（Fit 基准下 1 = 整图，5 = 放大 5 倍细选） */
private const val MAX_SCALE = 5f

/** 源物化阶段（Ready 附带原图尺寸，供手势 clamp 与缩放计算） */
private sealed interface CropSourceState {
    data object Loading : CropSourceState
    data class Ready(val file: File, val srcW: Int, val srcH: Int) : CropSourceState
    data class Failed(val reason: String) : CropSourceState
}

@Composable
fun AvatarCropScreen(
    shipName: String,
    archiveType: String,
    source: String,
    label: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── 阶段一：源物化为本地文件（含尺寸探测）──
    var sourceState by remember(source) { mutableStateOf<CropSourceState>(CropSourceState.Loading) }
    LaunchedEffect(source) {
        sourceState = withContext(Dispatchers.IO) {
            runCatching {
                val file: File? = when {
                    source.startsWith("file://") -> {
                        val f = File(Uri.parse(source).path ?: "")
                        if (f.isFile) f else null
                    }
                    source.startsWith("content://") -> {
                        // 相册 Uri 理论上已在入口处拷贝；兜底再拷一次（入口遗漏/进程重启场景）
                        CustomAvatarManager.copyToCacheFile(context, Uri.parse(source))
                    }
                    source.startsWith("http") -> downloadToCache(context, source)
                    else -> null
                }
                if (file == null || !file.isFile) {
                    return@runCatching CropSourceState.Failed(
                        when {
                            source.startsWith("http") -> "立绘下载失败，请检查网络"
                            source.startsWith("content://") -> "无法读取所选图片"
                            else -> "源文件不存在"
                        }
                    )
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    CropSourceState.Failed("图片解码失败")
                } else {
                    CropSourceState.Ready(file, bounds.outWidth, bounds.outHeight)
                }
            }.getOrElse { CropSourceState.Failed(it.message ?: "图片加载失败") }
        }
    }

    // ── 裁切状态 ──
    var scale by remember(source) { mutableFloatStateOf(1f) }
    var offset by remember(source) { mutableStateOf(Offset.Zero) }
    var isApplying by remember { mutableStateOf(false) }

    // 裁剪框实际渲染尺寸（onSizeChanged 实测）——apply 与 clamp 共用，
    // 杜绝"推导尺寸 ≠ 真实框"导致的应用范围漂移
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    val srcDims = (sourceState as? CropSourceState.Ready)?.let { it.srcW to it.srcH }
    val boxReady = boxSize.width > 0 && boxSize.height > 0 && srcDims != null

    AdaptiveScreenBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            BlyyTopBar(
                title = "裁剪头像",
                subtitle = if (label.isNotBlank()) "$shipName · $label" else shipName,
                onBackClick = onBack
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppSpacing.Screen.Horizontal),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Lg),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    when (val st = sourceState) {
                        CropSourceState.Loading -> CircularProgressIndicator()
                        is CropSourceState.Failed -> Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
                        ) {
                            Text(
                                st.reason,
                                style = AppTypography.BodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            BlyySecondaryButton(text = "返回", onClick = onBack)
                        }
                        is CropSourceState.Ready -> {
                            val bandsColor = Color(CustomAvatarManager.OUTPUT_BANDS_COLOR)
                            Box(
                                modifier = Modifier
                                    .aspectRatio(AppSpacing.Card.AspectRatio)
                                    .fillMaxWidth(0.8f)
                                    .clip(RectangleShape)
                                    .background(bandsColor)
                                    .border(2.dp, Color.White, RectangleShape)
                                    .onSizeChanged { boxSize = it }
                                    .pointerInput(st.srcW, st.srcH) {
                                        detectTransformGestures { centroid, pan, zoom, _ ->
                                            if (boxSize.width <= 0 || boxSize.height <= 0) return@detectTransformGestures
                                            val boxW = boxSize.width.toFloat()
                                            val boxH = boxSize.height.toFloat()
                                            val oldScale = scale
                                            val newScale = (oldScale * zoom).coerceIn(1f, MAX_SCALE)

                                            // 质心补偿：保持手指下的图像点不动。
                                            // screen = c + (q−c)*s + t → 变换后
                                            // t' = centroid − c − (centroid − t − c) * (s'/s)
                                            val cx = boxW / 2f
                                            val cy = boxH / 2f
                                            var nx = centroid.x - cx -
                                                (centroid.x - offset.x - cx) * (newScale / oldScale) + pan.x
                                            var ny = centroid.y - cy -
                                                (centroid.y - offset.y - cy) * (newScale / oldScale) + pan.y

                                            // 两轴独立 clamp：图像超出框的一半（Fit 下未覆盖轴为 0）
                                            val base = minOf(boxW / st.srcW, boxH / st.srcH)
                                            val maxOffX = ((st.srcW * base * newScale - boxW) / 2f)
                                                .coerceAtLeast(0f)
                                            val maxOffY = ((st.srcH * base * newScale - boxH) / 2f)
                                                .coerceAtLeast(0f)
                                            nx = nx.coerceIn(-maxOffX, maxOffX)
                                            ny = ny.coerceIn(-maxOffY, maxOffY)

                                            scale = newScale
                                            offset = Offset(nx, ny)
                                        }
                                    }
                            ) {
                                AsyncImage(
                                    model = remember(st.file) {
                                        ImageRequest.Builder(context)
                                            .data(st.file)
                                            .build()
                                    },
                                    contentDescription = "待裁剪图片",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer(
                                            scaleX = scale,
                                            scaleY = scale,
                                            translationX = offset.x,
                                            translationY = offset.y
                                        ),
                                    // Fit 基准：scale=1 完整可见整图（黑边补齐未覆盖区域）
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    }
                }

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
                        text = if (isApplying) "应用中..." else "应用",
                        onClick = {
                            val st = sourceState
                            if (st is CropSourceState.Ready && boxReady && !isApplying) {
                                isApplying = true
                                scope.launch {
                                    val result = CustomAvatarManager.applyFromCrop(
                                        context = context,
                                        sourceUri = Uri.fromFile(st.file),
                                        shipName = shipName,
                                        archiveType = archiveType,
                                        scale = scale,
                                        offsetX = offset.x,
                                        offsetY = offset.y,
                                        boxW = boxSize.width.toFloat(),
                                        boxH = boxSize.height.toFloat()
                                    )
                                    isApplying = false
                                    result.fold(
                                        onSuccess = {
                                            // 裁切源临时文件使命完成，立即清理
                                            //（取消/失败路径残留的由启动时 cleanCropCache 兜底清扫）
                                            withContext(Dispatchers.IO) { st.file.delete() }
                                            Toast.makeText(context, "已应用自定义头像", Toast.LENGTH_SHORT).show()
                                            onBack()
                                        },
                                        onFailure = {
                                            Toast.makeText(context, "应用失败：${it.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = boxReady && !isApplying
                    )
                }

                Spacer(modifier = Modifier.padding(bottom = AppSpacing.Lg))
            }
        }
    }
}

/** 经应用 Coil 实例下载网络立绘到缓存 PNG（走共享 OkHttpClient，wiki 图源带 Referer） */
private suspend fun downloadToCache(context: Context, url: String): File? =
    withContext(Dispatchers.IO) {
        runCatching {
            val request = ImageRequest.Builder(context)
                .data(url)
                .allowHardware(false)
                .size(Size.ORIGINAL)
                .build()
            val bitmap = (Coil.imageLoader(context).execute(request).drawable as? BitmapDrawable)?.bitmap
                ?: return@runCatching null
            val dest = File(context.cacheDir, "avatar_crop_src_net_${System.currentTimeMillis()}.png")
            dest.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            dest
        }.getOrNull()
    }
