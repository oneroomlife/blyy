package com.azurlane.blyy.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.util.LocalSdResolver
import kotlin.math.roundToInt

@Composable
fun SecretaryChibiOverlay(
    figureUrl: String,
    shipName: String,
    dialogue: String?,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    isSystemOverlay: Boolean = false,
    onPositionChange: ((Float, Float) -> Unit)? = null
) {
    if (figureUrl.isEmpty()) return

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val hapticFeedback = LocalHapticFeedback.current
    val context = LocalContext.current

    // 尝试解析 SD 小人动画资源；解析到则用 Spine 渲染，否则回退静态立绘
    val sdAssetName = remember(shipName) { LocalSdResolver.resolve(context, shipName) }

    val screenWidth = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeight = with(density) { configuration.screenHeightDp.dp.toPx() }

    val figureWidth = 110.dp
    val figureHeight = 165.dp
    val figureWidthPx = with(density) { figureWidth.toPx() }
    val figureHeightPx = with(density) { figureHeight.toPx() }

    // 注意：如果是悬浮窗模式，内部不需要 offsetX/Y，因为位移由 WindowManager 控制
    var localOffsetX by remember { mutableFloatStateOf(screenWidth - figureWidthPx - 24f) }
    var localOffsetY by remember { mutableFloatStateOf(screenHeight * 0.5f - figureHeightPx / 2) }

    var isDragging by remember { mutableStateOf(false) }
    var isTapped by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = when {
            isDragging -> 1.25f
            isTapped -> 1.15f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        finishedListener = { isTapped = false },
        label = "FigureScale"
    )

    val alpha by animateFloatAsState(
        targetValue = if (isDragging) 0.7f else 1f,
        animationSpec = tween(250),
        label = "FigureAlpha"
    )

    val isDark = LocalIsDark.current

    // 气泡实际高度（px），由 onGloballyPositioned 动态测量，用于让气泡底部始终位于立绘头顶上方固定间距，
    // 避免气泡高度随文本行数变化时与立绘重叠。
    var bubbleHeightPx by remember { mutableFloatStateOf(0f) }
    
    // 我们用一个外层 Box 来包裹。
    // 如果是 App 内，使用 fillMaxSize 并根据 localOffset 定位。
    // 如果是 悬浮窗，由于外层 WindowManager 是 WRAP_CONTENT，我们这里不需要全屏，也不需要 offset。
    Box(
        modifier = modifier.fillMaxSize()
    ) {
        
        // 将气泡和小人放在一个相对独立的容器里。
        // 容器尺寸固定为立绘尺寸，确保气泡 TopCenter 对齐到立绘水平中线（而非更宽的气泡宽度中线），
        // 这样气泡三角尖正好指向立绘头顶中心，视觉美观。
        // 悬浮窗模式下容器 align BottomCenter，让气泡向上偏移后仍在 WindowManager 可见区域内。
        Box(
            modifier = if (isSystemOverlay) {
                Modifier.size(figureWidth, figureHeight).align(Alignment.BottomCenter)
            } else {
                Modifier
                    .size(figureWidth, figureHeight)
                    .offset { IntOffset(localOffsetX.roundToInt(), localOffsetY.roundToInt()) }
            }
        ) {

            // 气泡绘制：对齐到容器顶部中心（= 立绘水平中线），动态测量气泡高度后整体上移，
            // 使气泡底部（三角尖顶点）位于立绘头顶上方 6dp，无论文本长短都不与立绘重叠。
            AnimatedVisibility(
                visible = dialogue != null && !isDragging,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom) + scaleIn(transformOrigin = TransformOrigin(0.5f, 1f)),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom) + scaleOut(transformOrigin = TransformOrigin(0.5f, 1f)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .onGloballyPositioned { bubbleHeightPx = it.size.height.toFloat() }
                    .offset {
                        // align(TopCenter) 使气泡顶部在容器顶部(y=0)，气泡底部在 y=bubbleHeightPx。
                        // 向上偏移 bubbleHeightPx + 6dp，使气泡底部位于容器顶部上方 6dp（立绘头顶上方）。
                        val gapPx = with(density) { 6.dp.toPx() }
                        IntOffset(0, -(bubbleHeightPx + gapPx).roundToInt())
                    }
            ) {
                SecretarySpeechBubble(text = dialogue ?: "", isDark = isDark)
            }

            // 立绘小人本体
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .scale(scale)
                    .graphicsLayer { this.alpha = alpha }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                isTapped = true
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onTap()
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                isDragging = true
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragEnd = { isDragging = false },
                            onDragCancel = { isDragging = false }
                        ) { change, dragAmount ->
                            change.consume()
                            if (isSystemOverlay) {
                                // 悬浮窗模式：把偏移量丢给外部 Service 的 WindowManager
                                onPositionChange?.invoke(dragAmount.x, dragAmount.y)
                            } else {
                                // App 内模式：修改本地状态
                                localOffsetX = (localOffsetX + dragAmount.x).coerceIn(0f, screenWidth - figureWidthPx)
                                localOffsetY = (localOffsetY + dragAmount.y).coerceIn(0f, screenHeight - figureHeightPx)
                            }
                        }
                    }
            ) {
                if (sdAssetName != null) {
                    // SD 小人 Spine 动画渲染
                    // SpineSdView 内部的 GLSurfaceView 会消费整个触摸序列，
                    // 因此点击与拖动都需通过回调传入 SpineSdView，而非依赖外层 Box 的 pointerInput。
                    // 拖动逻辑镜像下方 detectDragGestures：悬浮窗模式转发给 Service，App 内模式更新本地坐标。
                    SpineSdView(
                        assetName = sdAssetName,
                        modifier = Modifier.fillMaxSize(),
                        onTap = {
                            isTapped = true
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onTap()
                        },
                        onDragStart = {
                            isDragging = true
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDrag = { dx, dy ->
                            if (isSystemOverlay) {
                                onPositionChange?.invoke(dx, dy)
                            } else {
                                localOffsetX = (localOffsetX + dx).coerceIn(0f, screenWidth - figureWidthPx)
                                localOffsetY = (localOffsetY + dy).coerceIn(0f, screenHeight - figureHeightPx)
                            }
                        },
                        onDragEnd = { isDragging = false }
                    )
                } else {
                    // 回退：静态立绘图片
                    AsyncImage(
                        model = figureUrl,
                        contentDescription = "秘书舰 $shipName",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
                if (isDragging) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = AppSpacing.Xs),
                        shape = RoundedCornerShape(AppSpacing.Corner.Sm),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
                    ) {
                        Text(
                            text = "拖动调整位置",
                            style = AppTypography.LabelSmallBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = AppSpacing.Sm, vertical = AppSpacing.Xxs)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SecretarySpeechBubble(text: String, isDark: Boolean) {
    val bubbleColor = if (isDark) Color(0xFF2C2C2E).copy(alpha = 0.9f) else Color.White.copy(alpha = 0.95f)
    val textColor = if (isDark) Color.White else Color.Black

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = bubbleColor,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
        ) {
            Text(
                text = text,
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = AppTypography.BodySmall.copy(
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = textColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Canvas(modifier = Modifier.size(12.dp, 6.dp)) {
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width / 2, size.height)
                close()
            }
            drawPath(path, color = bubbleColor)
        }
    }
}
