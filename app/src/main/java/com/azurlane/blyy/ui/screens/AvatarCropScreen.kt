package com.azurlane.blyy.ui.screens

// 自定义头像·裁切页：3:4（舰娘卡片纵横比）自由缩放裁切并应用
//
// 与 ImageCropperScreen（应用图标，1:1/432px）同源的变换手势，泛化为任意纵横比：
// - 裁剪框 = 屏宽 80% × (宽/0.75) 高，所见即所得（ShipCard 即 3:4 Crop 显示）
// - 单指拖动平移、双指缩放 1x~5x，两轴独立 clamp
// 源物化：网络立绘先经 Coil 下载到缓存文件，相册 content Uri 先拷贝到私有缓存
// （读取权限是临时的，不能推迟到应用时再读），本页裁切与应用只面对 file:// 源。

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.Coil
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

/** 源物化阶段 */
private sealed interface CropSourceState {
    data object Loading : CropSourceState
    data class Ready(val file: File) : CropSourceState
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

    // ── 阶段一：源物化为本地文件 ──
    var sourceState by remember(source) { mutableStateOf<CropSourceState>(CropSourceState.Loading) }
    LaunchedEffect(source) {
        sourceState = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    source.startsWith("file://") -> {
                        val f = File(Uri.parse(source).path ?: "")
                        if (f.isFile) CropSourceState.Ready(f)
                        else CropSourceState.Failed("源文件不存在")
                    }
                    source.startsWith("content://") -> {
                        // 相册 Uri 理论上已在入口处拷贝；兜底再拷一次（入口遗漏/进程重启场景）
                        CustomAvatarManager.copyToCacheFile(context, Uri.parse(source))
                            ?.let { CropSourceState.Ready(it) }
                            ?: CropSourceState.Failed("无法读取所选图片")
                    }
                    source.startsWith("http") -> {
                        downloadToCache(context, source)
                            ?.let { CropSourceState.Ready(it) }
                            ?: CropSourceState.Failed("立绘下载失败，请检查网络")
                    }
                    else -> CropSourceState.Failed("不支持的图片来源")
                }
            }.getOrElse { CropSourceState.Failed(it.message ?: "图片加载失败") }
        }
    }

    // ── 裁剪状态（Ready 后有意义）──
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var isApplying by remember { mutableStateOf(false) }

    // 裁剪框：宽 = 屏宽 80%，高 = 宽 / 0.75（与舰娘卡片纵横比一致，所见即所得）
    val cropBoxWidthPx = with(LocalDensity.current) {
        (LocalConfiguration.current.screenWidthDp.dp * 0.8f).toPx()
    }
    val cropBoxHeightPx = cropBoxWidthPx / AppSpacing.Card.AspectRatio

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
                            Box(
                                modifier = Modifier
                                    .aspectRatio(AppSpacing.Card.AspectRatio)
                                    .fillMaxWidth(0.8f)
                                    .clip(RectangleShape)
                                    .border(2.dp, Color.White, RectangleShape)
                                    .pointerInput(Unit) {
                                        detectTransformGestures { _, pan, zoom, _ ->
                                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                                            // 两轴独立 clamp：scale≥1 保证 maxOffset 非负，
                                            // 约束维（Crop 贴合维）maxOffset≈0 自然锁死该轴
                                            val maxOffX = cropBoxWidthPx * (newScale - 1f) / 2f
                                            val maxOffY = cropBoxHeightPx * (newScale - 1f) / 2f
                                            scale = newScale
                                            offset = Offset(
                                                x = (offset.x + pan.x).coerceIn(-maxOffX, maxOffX),
                                                y = (offset.y + pan.y).coerceIn(-maxOffY, maxOffY)
                                            )
                                        }
                                    }
                            ) {
                                coil.compose.AsyncImage(
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
                                    contentScale = ContentScale.Crop
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
                            if (st is CropSourceState.Ready && !isApplying) {
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
                                        boxW = cropBoxWidthPx,
                                        boxH = cropBoxHeightPx
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
                        enabled = sourceState is CropSourceState.Ready && !isApplying
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
            dest.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            dest
        }.getOrNull()
    }
