package com.azurlane.blyy.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size

private const val MinZoom = 0.5f
private const val MaxZoom = 5f
private const val DoubleTapZoom = 2.5f

@Composable
fun ZoomableImage(
    model: Any,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    minZoom: Float = MinZoom,
    maxZoom: Float = MaxZoom,
    animationSpec: AnimationSpec<Float> = spring(stiffness = Spring.StiffnessLow),
    error: Painter? = null,
    placeholder: Painter? = null,
    requestSize: Size? = null,
    cacheKey: String? = null
) {
    // 解码尺寸策略：
    // - 默认 null —— 交给 Coil 按视图尺寸降采样，列表/缩略图最省内存；
    // - 传 Size.ORIGINAL —— 按原图最高分辨率解码。大图查看器必须这么做：
    //   否则双击 2.5x / 捏合 5x 放出去的是被降采样过的位图，放大后必糊。
    // 缓存键策略：
    // - 默认 null —— Coil 对 File 只按路径做键，同路径换了内容永远命中旧图；
    // - 传 cacheKey —— 调用方把「路径 + 版本（mtime / 体积）」拼进来，重新导入后自动失效。
    val context = LocalContext.current
    val request = remember(model, requestSize, cacheKey) {
        if (requestSize == null && cacheKey == null) {
            model
        } else {
            val builder = (model as? ImageRequest)?.newBuilder()
                ?: ImageRequest.Builder(context).data(model)
            if (requestSize != null) builder.size(requestSize)
            if (cacheKey != null) {
                builder.memoryCacheKey(cacheKey)
                builder.diskCacheKey(cacheKey)
            }
            builder.build()
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var imageSize by remember { mutableStateOf(IntSize.Zero) }
    
    var isZooming by remember { mutableStateOf(false) }
    
    val animatedScale by animateFloatAsState(
        targetValue = scale,
        animationSpec = animationSpec,
        label = "scale"
    )
    
    val animatedOffsetX by animateFloatAsState(
        targetValue = offsetX,
        animationSpec = animationSpec,
        label = "offsetX"
    )
    
    val animatedOffsetY by animateFloatAsState(
        targetValue = offsetY,
        animationSpec = animationSpec,
        label = "offsetY"
    )
    
    fun calculateBoundOffset(
        currentScale: Float,
        targetOffsetX: Float,
        targetOffsetY: Float
    ): Offset {
        if (containerSize == IntSize.Zero || imageSize == IntSize.Zero) {
            return Offset(targetOffsetX, targetOffsetY)
        }
        
        val scaledWidth = imageSize.width * currentScale
        val scaledHeight = imageSize.height * currentScale
        
        val containerWidth = containerSize.width.toFloat()
        val containerHeight = containerSize.height.toFloat()
        
        val maxOffsetX = maxOf(0f, (scaledWidth - containerWidth) / 2f)
        val maxOffsetY = maxOf(0f, (scaledHeight - containerHeight) / 2f)
        
        val boundOffsetX = targetOffsetX.coerceIn(-maxOffsetX, maxOffsetX)
        val boundOffsetY = targetOffsetY.coerceIn(-maxOffsetY, maxOffsetY)
        
        if (scaledWidth <= containerWidth) {
            return Offset(0f, boundOffsetY)
        }
        if (scaledHeight <= containerHeight) {
            return Offset(boundOffsetX, 0f)
        }
        
        return Offset(boundOffsetX, boundOffsetY)
    }
    
    fun resetZoom() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        isZooming = false
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    
                    do {
                        val event = awaitPointerEvent()
                        
                        if (event.changes.size >= 2) {
                            isZooming = true
                            
                            val zoomChange = event.calculateZoom()
                            val newScale = (scale * zoomChange).coerceIn(minZoom, maxZoom)
                            
                            val panChange = event.calculatePan()
                            
                            val scaleRatio = newScale / scale
                            val adjustedOffsetX = offsetX * scaleRatio + panChange.x * 0.5f
                            val adjustedOffsetY = offsetY * scaleRatio + panChange.y * 0.5f
                            
                            scale = newScale
                            
                            val boundOffset = calculateBoundOffset(newScale, adjustedOffsetX, adjustedOffsetY)
                            offsetX = boundOffset.x
                            offsetY = boundOffset.y
                            
                            event.changes.forEach { it.consume() }
                        } else if (event.changes.size == 1 && isZooming) {
                            val panChange = event.calculatePan()
                            
                            val newOffsetX = offsetX + panChange.x
                            val newOffsetY = offsetY + panChange.y
                            
                            val boundOffset = calculateBoundOffset(scale, newOffsetX, newOffsetY)
                            offsetX = boundOffset.x
                            offsetY = boundOffset.y
                            
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    
                    if (scale < 1f) {
                        resetZoom()
                    }
                }
            }
            .pointerInput(Unit) {
                var lastTapTime = 0L
                
                awaitEachGesture {
                    awaitFirstDown()
                    
                    val currentTime = System.currentTimeMillis()
                    
                    if (currentTime - lastTapTime < 300) {
                        if (scale > 1.1f) {
                            resetZoom()
                        } else {
                            scale = DoubleTapZoom.coerceIn(minZoom, maxZoom)
                            val boundOffset = calculateBoundOffset(scale, offsetX, offsetY)
                            offsetX = boundOffset.x
                            offsetY = boundOffset.y
                            isZooming = true
                        }
                        lastTapTime = 0L
                    } else {
                        lastTapTime = currentTime
                    }
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = animatedScale
                    scaleY = animatedScale
                    translationX = animatedOffsetX
                    translationY = animatedOffsetY
                },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { imageSize = it },
                contentScale = contentScale,
                error = error,
                placeholder = placeholder
            )
        }
    }
}
