package com.example.blyy.ui.components

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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.blyy.ui.theme.AppSpacing
import com.example.blyy.ui.theme.AppTypography
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

    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val bubbleMaxHeight = 200.dp
    val bubbleWidth = 220.dp
    
    // 我们用一个外层 Box 来包裹。
    // 如果是 App 内，使用 fillMaxSize 并根据 localOffset 定位。
    // 如果是 悬浮窗，由于外层 WindowManager 是 WRAP_CONTENT，我们这里不需要全屏，也不需要 offset。
    Box(
        modifier = if (isSystemOverlay) modifier else modifier.fillMaxSize()
    ) {
        
        // 将气泡和小人放在一个相对独立的容器里
        Box(
            modifier = Modifier
                .run {
                    if (isSystemOverlay) {
                        this // 悬浮窗模式下，此 Box 处于坐标 (0,0)，WindowManager 控制整体位置
                    } else {
                        offset { IntOffset(localOffsetX.roundToInt(), localOffsetY.roundToInt()) }
                    }
                }
        ) {
            
            // 气泡绘制
            AnimatedVisibility(
                visible = dialogue != null && !isDragging,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom) + scaleIn(transformOrigin = TransformOrigin(0.5f, 1f)),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom) + scaleOut(transformOrigin = TransformOrigin(0.5f, 1f)),
                modifier = Modifier
                    // 将气泡定位在小人的正上方居中位置
                    .align(Alignment.TopCenter)
                    .offset(y = (-bubbleMaxHeight + 10.dp)) // 向上偏移
                    .size(bubbleWidth, bubbleMaxHeight)
            ) {
                Box(contentAlignment = Alignment.BottomCenter) {
                    SecretarySpeechBubble(text = dialogue ?: "", isDark = isDark)
                }
            }

            // 立绘小人本体
            Box(
                modifier = Modifier
                    .size(figureWidth, figureHeight)
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
                AsyncImage(
                    model = figureUrl,
                    contentDescription = "秘书舰 $shipName",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
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
                            style = AppTypography.LabelSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
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
    val bubbleColor = if (isDark) androidx.compose.ui.graphics.Color(0xFF2C2C2E).copy(alpha = 0.9f) else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.95f)
    val textColor = if (isDark) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black
    val hPadding = if (text.length > 40) 16.dp else 12.dp
    val vPadding = if (text.length > 40) 10.dp else 8.dp
    val cornerRadius = if (text.length > 60) 16.dp else 12.dp

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(cornerRadius),
            color = bubbleColor,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = hPadding, vertical = vPadding),
                style = AppTypography.BodySmall.copy(
                    fontSize = if (text.length > 80) 11.sp else 13.sp,
                    lineHeight = if (text.length > 80) 15.sp else 18.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                ),
                color = textColor,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        Canvas(modifier = Modifier.size(12.dp, 6.dp)) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width / 2, size.height)
                close()
            }
            drawPath(path, color = bubbleColor)
        }
    }
}
