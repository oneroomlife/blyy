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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

/**
 * SpineRenderer 自适应缩放时的视口利用率（见 SpineSdView.kt `* 0.95f`）。
 *
 * 边界会缩放到视口的 95%（留 5% 边距），剩余 5% 上下平分各 2.5%。
 *
 * 【气泡定位基准】SpineRenderer scale = min(viewW/boundsW, viewH/boundsH) * 0.95，
 * 骨骼渲染高度 = boundsH * scale：
 * - 高瘦型骨骼（height 为限制维度）：骨骼渲染高度 = viewH * 0.95（占视口 95%）
 * - 矮胖型骨骼（width 为限制维度）：骨骼渲染高度 < viewH * 0.95
 *
 * 因此骨骼渲染高度占视口比例最大为 [CHIBI_VIEWPORT_USAGE]（0.95），
 * 气泡定位用此值作为保守估计：高瘦型精确贴合头顶，矮胖型气泡稍高但不会遮挡头部。
 */
private const val CHIBI_VIEWPORT_USAGE = 0.95f

/**
 * 拖动边界余量比例：允许小人头顶超出容器顶部此比例的距离，使头顶可达屏幕顶端。
 *
 * 与气泡定位解耦：气泡需要精确的小人头顶位置，而拖动边界只需保证小人不会被拖到看不见。
 */
private const val CHIBI_DRAG_MARGIN_RATIO = 0.15f

@Composable
fun SecretaryChibiOverlay(
    figureUrl: String,
    shipName: String,
    dialogue: String?,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    isSystemOverlay: Boolean = false,
    onPositionChange: ((Float, Float) -> Unit)? = null,
    /** SD 小人皮肤名（null 用默认皮肤，"gai"/"skin2" 等对应 LocalSdResolver 皮肤分类） */
    selectedSkin: String? = null,
    /** SD 小人显示缩放倍率（1.0 = 默认自适应大小，0.5 = 半尺寸，1.5 = 放大 50%）；实时生效无需重建 */
    sdScale: Float = 1.0f
) {
    if (figureUrl.isEmpty()) return

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val hapticFeedback = LocalHapticFeedback.current
    val context = LocalContext.current

    // 尝试解析 SD 小人动画资源；解析到则用 Spine 渲染，否则回退静态立绘。
    // 关键：将 LocalSdResolver.revision 作为 remember key，
    // 整理/清理资源后 refreshIndex() 递增 revision，触发 sdAsset 重新解析，
    // 避免整理后仍使用旧的 null 结果导致 SD 小人不可动。
    val sdAsset = remember(shipName, selectedSkin, LocalSdResolver.revision.value) {
        LocalSdResolver.resolve(context, shipName, selectedSkin)
    }

    val screenWidth = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeight = with(density) { configuration.screenHeightDp.dp.toPx() }

    // ── 容器跟随放大 + 中心位置补偿方案 ──
    //
    // 目标：
    // 1. 缩放实时生效（SpineRenderer userScale 直接控制）
    // 2. 放大后不裁剪（GLSurfaceView 容器跟随放大，保证渲染完整）
    // 3. 放大时小人不移动（通过 offset 补偿让小人中心位置不变）
    // 4. 气泡/拖动提示间距不随放大变化（外层 Box 固定基础尺寸作为布局锚点）
    //
    // 方案：
    // - 外层 Box：固定基础尺寸（110×165dp），作为气泡/拖动提示/拖动边界的布局锚点
    // - GLSurfaceView：尺寸 = 基础尺寸 × containerScale（放大时容器跟随，渲染完整不裁剪）
    //   通过 offset 居中放置在外层 Box 内：offset = (baseSize - renderSize) / 2（负值，向外扩展）
    // - SpineRenderer userScale：
    //   - sdScale ≤ 1.0：containerScale=1.0，userScale=sdScale，在固定容器内缩小
    //   - sdScale > 1.0：containerScale=sdScale，userScale=1.0，容器变大 SpineRenderer 自适应放大
    //   这样避免"容器放大 + userScale > 1.0"双重放大导致的二次缩放问题
    // - offset 补偿：GLSurfaceView 相对外层 Box 居中（offset = (baseSize - renderSize) / 2），
    //   外层 Box 位置不变（基于 baseSize 计算），GLSurfaceView 中心 = 外层 Box 中心，
    //   SpineRenderer 内部骨骼也居中渲染，因此小人中心位置始终保持不变
    val displayScale = sdScale.coerceIn(0.3f, 2.0f)
    val containerScale = displayScale.coerceAtLeast(1.0f)
    val characterScale = displayScale / containerScale  // 放大时=1.0，缩小时=sdScale

    // 固定基础尺寸：外层布局 Box 的尺寸（气泡/提示/拖动边界基于此）
    val baseWidth = 110.dp
    val baseHeight = 165.dp
    val baseWidthPx = with(density) { baseWidth.toPx() }
    val baseHeightPx = with(density) { baseHeight.toPx() }

    // 渲染容器尺寸：放大时跟随放大，保证 Spine 骨骼渲染完整不裁剪
    val renderWidth = baseWidth * containerScale
    val renderHeight = baseHeight * containerScale

    // GLSurfaceView 相对外层 Box 的 offset（居中放置，放大时向外扩展）
    // renderOffset = (baseSize - renderSize) / 2，放大时为负值（向左上方扩展）
    // 由于 SpineRenderer 内部骨骼居中渲染，GLSurfaceView 中心 = 外层 Box 中心 = 小人中心
    // 因此外层 Box 位置不变时，小人中心位置也不变（不会向左上方移动）
    val renderOffsetX = (baseWidthPx - with(density) { renderWidth.toPx() }) / 2f
    val renderOffsetY = (baseHeightPx - with(density) { renderHeight.toPx() }) / 2f

    // 小人头顶相对外层 Box 顶部的 Y 坐标（放大时为负值，表示头顶溢出容器顶部）
    // SpineRenderer 把骨骼边界框中心垂直居中到 GLSurfaceView 中心（skel.y 计算保证），
    // 骨骼渲染高度 = baseHeightPx * CHIBI_VIEWPORT_USAGE * displayScale（高瘦型精确等于此值），
    // 因此头顶 Y = baseHeightPx/2 - 骨骼渲染高度/2 = baseHeightPx * (1 - 0.95*displayScale) / 2
    // 复用于气泡定位和拖动边界计算，确保三者位置一致
    val chibiHeadTopY = baseHeightPx * (1f - CHIBI_VIEWPORT_USAGE * displayScale) / 2f

    // 拖动边界：基于小人实际顶部/底部位置计算，保证放大时小人不会过度超出屏幕
    // 允许小人顶部/底部最多超出屏幕 CHIBI_DRAG_MARGIN_RATIO * baseHeightPx（让头顶可达屏幕顶端）
    //
    // 【隐藏问题修复】旧实现 dragMinY = -baseHeightPx * 0.15 是固定值，不考虑 displayScale，
    // 放大时小人会溢出基础容器（头顶/脚底超出 baseHeightPx 范围），若仍用固定 dragMinY 会导致：
    //   displayScale=2.0 时 dragMinY=-baseHeightPx*0.15，但小人顶部 = localOffsetY + chibiHeadTopY
    //   = -baseHeightPx*0.15 + (-baseHeightPx*0.45) = -baseHeightPx*0.6，小人头部超出屏幕 60% 被裁剪
    // 修复后 dragMinY 考虑 chibiHeadTopY，保证小人顶部最多超出屏幕 margin（15% baseHeightPx）
    val dragMinY = -chibiHeadTopY - baseHeightPx * CHIBI_DRAG_MARGIN_RATIO
    val dragMaxY = screenHeight - chibiHeadTopY -
        baseHeightPx * (CHIBI_VIEWPORT_USAGE * displayScale - CHIBI_DRAG_MARGIN_RATIO)

    var localOffsetX by remember { mutableFloatStateOf(screenWidth - baseWidthPx - 24f) }
    var localOffsetY by remember { mutableFloatStateOf(screenHeight * 0.5f - baseHeightPx / 2) }

    // 角色尺寸变化（用户调整 sdScale）时自动收紧位置边界
    // 依赖 displayScale：放大/缩小时拖动边界变化，需重新检查 localOffsetY 是否越界
    LaunchedEffect(baseWidthPx, baseHeightPx, screenWidth, screenHeight, displayScale) {
        val maxX = (screenWidth - baseWidthPx).coerceAtLeast(0f)
        if (localOffsetX > maxX) localOffsetX = maxX
        if (localOffsetX < 0f) localOffsetX = 0f
        if (localOffsetY > dragMaxY) localOffsetY = dragMaxY
        if (localOffsetY < dragMinY) localOffsetY = dragMinY
    }

    var isDragging by remember { mutableStateOf(false) }
    var isTapped by remember { mutableStateOf(false) }

    // scale/alpha 动画仅用于静态立绘回退场景；
    // SD 小人（SpineSdView）不应用 scale 动画（GLSurfaceView 缩放会导致渲染异常），
    // 也不应用 alpha 动画（GLSurfaceView 的 Surface 独立于 Compose 图层，alpha 不生效）。
    val staticScale by animateFloatAsState(
        targetValue = when {
            isDragging -> 1.25f
            isTapped -> 1.15f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        finishedListener = { isTapped = false },
        label = "StaticFigureScale"
    )
    val staticAlpha by animateFloatAsState(
        targetValue = if (isDragging) 0.7f else 1f,
        animationSpec = tween(250),
        label = "StaticFigureAlpha"
    )

    val isDark = LocalIsDark.current

    // 气泡实际高度（px），由 onGloballyPositioned 动态测量
    var bubbleHeightPx by remember { mutableFloatStateOf(0f) }

    Box(modifier = modifier.fillMaxSize()) {
        // ── 外层 Box：固定基础尺寸，作为布局锚点 ──
        // GLSurfaceView 容器尺寸固定 = 基础尺寸（永不变化），
        // SpineRenderer 通过 userScale = displayScale 直接控制骨骼缩放（可 > 1.0 放大）。
        // 放大时骨骼会溢出 GL viewport，Spine 小人通常垂直长条形，
        // SpineRenderer 内部将骨骼锚点设为"底部居中"（脚底对齐容器底部），
        // 放大时主要向上溢出，头部和身体仍可见，避免关键部位被裁剪。
        // 气泡定位基于固定的基础尺寸，不随 sdScale 变化。
        Box(
            modifier = if (isSystemOverlay) {
                Modifier.size(baseWidth, baseHeight).align(Alignment.BottomCenter)
            } else {
                Modifier
                    .size(baseWidth, baseHeight)
                    .offset { IntOffset(localOffsetX.roundToInt(), localOffsetY.roundToInt()) }
            }
        ) {
            if (sdAsset != null) {
                // ── SD 小人（Spine 动画）──
                // GLSurfaceView 容器尺寸 = renderWidth × renderHeight（放大时跟随放大，渲染完整不裁剪）。
                //
                // 【关键】必须使用 requiredSize 而非 size：
                // 外层 Box 固定为 baseWidth × baseHeight 作为布局锚点（气泡/拖动提示基于此），
                // 若用 size(renderWidth, renderHeight)，放大时 renderWidth > baseWidth 会被
                // 父容器 Constraints 截断为 baseWidth，导致 GLSurfaceView 实际尺寸未变大，
                // SpineRenderer 的 viewWidth/viewHeight 不变 → scale 不变 → 缩放失效；
                // 同时 offset 为负值但尺寸未变大 → GLSurfaceView 被偏移到左上方 → 小人往左上方移动。
                // requiredSize 忽略父 Constraints 强制设置尺寸，溢出部分向外扩展不裁剪（外层 Box 无 clip）。
                //
                // 通过 offset 居中放置在外层 Box 内：放大时 offset 为负值，向外扩展，
                // GLSurfaceView 中心 = 外层 Box 中心 = 小人中心，位置不移动。
                //
                // SpineRenderer userScale = characterScale：
                // - sdScale ≤ 1.0：characterScale=sdScale，在固定容器内缩小
                // - sdScale > 1.0：characterScale=1.0，容器变大 SpineRenderer 自适应放大
                // 缩放实时生效（update 回调更新 scaleMultiplier，SpineRenderer 每帧读取）。
                //
                // touchAreaRatio：放大时限制触摸区域为中心基础尺寸部分，避免溢出区域误触。
                // ratio = baseWidth / renderWidth = 1 / containerScale。
                val touchRatio = if (containerScale > 1.0f) (1.0f / containerScale) else 1.0f
                SpineSdView(
                    dirPath = sdAsset.dirPath,
                    assetName = sdAsset.assetName,
                    modifier = Modifier
                        .requiredSize(renderWidth, renderHeight)
                        .offset { IntOffset(renderOffsetX.roundToInt(), renderOffsetY.roundToInt()) },
                    scaleMultiplier = characterScale,
                    touchAreaRatio = touchRatio,
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
                            localOffsetX = (localOffsetX + dx).coerceIn(0f, (screenWidth - baseWidthPx).coerceAtLeast(0f))
                            localOffsetY = (localOffsetY + dy).coerceIn(dragMinY, dragMaxY)
                        }
                    },
                    onDragEnd = { isDragging = false }
                )
            } else {
                // ── 静态立绘回退 ──
                // 仅在此场景使用 pointerInput + scale/alpha 动画
                // 渲染层尺寸同 SD 小人场景，保证放大时渲染完整
                // 同样使用 requiredSize 突破外层 Box 的尺寸约束（见上方 SD 小人注释）
                Box(
                    modifier = Modifier
                        .requiredSize(renderWidth, renderHeight)
                        .offset { IntOffset(renderOffsetX.roundToInt(), renderOffsetY.roundToInt()) }
                        .graphicsLayer {
                            scaleX = staticScale
                            scaleY = staticScale
                            this.alpha = staticAlpha
                        }
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
                                    onPositionChange?.invoke(dragAmount.x, dragAmount.y)
                                } else {
                                    localOffsetX = (localOffsetX + dragAmount.x).coerceIn(0f, (screenWidth - baseWidthPx).coerceAtLeast(0f))
                                    localOffsetY = (localOffsetY + dragAmount.y).coerceIn(dragMinY, dragMaxY)
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
                }
            }

            // 气泡：对齐到小人头顶正上方，跟随 sdScale 实时调整位置。
            //
            // 【定位推导】
            // SpineRenderer 把骨骼边界框中心垂直居中到 GLSurfaceView 中心（skel.y 计算保证），
            // GLSurfaceView 通过 renderOffsetY 居中放置在外层 Box 内，
            // 因此骨骼边界框中心 = GLSurfaceView 中心 = 外层 Box 中心 = baseHeightPx/2。
            //
            // 骨骼渲染高度 = boundsH * scale，其中 scale = min(viewW/boundsW, viewH/boundsH) * 0.95 * userScale
            // - 高瘦型骨骼（height 限制）：骨骼渲染高度 = viewH * 0.95 * userScale
            // - 矮胖型骨骼（width 限制）：骨骼渲染高度 < viewH * 0.95 * userScale
            //
            // 由于 GLSurfaceView 高度 = baseHeightPx * containerScale，userScale = characterScale，
            // containerScale * characterScale = displayScale，
            // 因此骨骼渲染高度 ≤ baseHeightPx * 0.95 * displayScale（最大值，高瘦型精确等于此值）。
            //
            // 小人头顶 Y（相对外层 Box 顶部）= baseHeightPx/2 - 骨骼渲染高度/2
            //                                 = baseHeightPx × (1 - 0.95 × displayScale) / 2
            //
            // - sdScale < 1.053：headTopY 为正值，头顶在容器内
            // - sdScale > 1.053：headTopY 为负值，头顶溢出容器顶部（气泡需跟随上移避免遮挡）
            //
            // 气泡定位流程：
            // 1. align(TopCenter) → 气泡顶部在 y=0
            // 2. 下移 headTopY → 气泡顶部对齐到小人头顶
            // 3. 上移 bubbleHeightPx → 气泡底部对齐到小人头顶（紧贴，无额外间距）
            //
            // 屏幕顶部边界保护：非系统悬浮窗场景下，外层 Box 屏幕坐标 = localOffsetY，
            // 气泡屏幕坐标 = localOffsetY + bubbleTopY，限制其 ≥ 0 避免气泡超出屏幕顶部。
            AnimatedVisibility(
                visible = dialogue != null && !isDragging,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom) + scaleIn(transformOrigin = TransformOrigin(0.5f, 1f)),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom) + scaleOut(transformOrigin = TransformOrigin(0.5f, 1f)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .onGloballyPositioned { bubbleHeightPx = it.size.height.toFloat() }
                    .offset {
                        // 复用已计算的 chibiHeadTopY（与拖动边界共用同一基准，保证位置一致）
                        // 高瘦型骨骼精确贴合头顶，矮胖型骨骼气泡稍高但不遮挡头部
                        // 放大时 chibiHeadTopY 为负值（头顶溢出容器顶部），气泡跟随上移
                        val headTopY = chibiHeadTopY
                        // 气泡顶部 Y = headTopY - bubbleHeightPx（气泡底部紧贴小人头顶）
                        var bubbleTopY = headTopY - bubbleHeightPx
                        // 屏幕顶部边界保护：防止气泡跟随小人头顶上移时超出屏幕顶部
                        if (!isSystemOverlay) {
                            val minBubbleY = -localOffsetY
                            if (bubbleTopY < minBubbleY) bubbleTopY = minBubbleY
                        }
                        IntOffset(0, bubbleTopY.roundToInt())
                    }
            ) {
                SecretarySpeechBubble(text = dialogue ?: "", isDark = isDark)
            }

            // 拖动提示标签：对齐到基础容器底部，不随放大变化
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
