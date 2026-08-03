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
import com.azurlane.blyy.util.SDResourceManager
import com.azurlane.blyy.util.SDResourceType
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

/** SD 小人基础宽度（dp），用于窗口尺寸计算 */
private val CHIBI_BASE_WIDTH = 110.dp

/** SD 小人基础高度（dp），用于窗口尺寸计算 */
private val CHIBI_BASE_HEIGHT = 165.dp

/**
 * 已解析的 SD 资源资产信息。
 *
 * 替代旧的 Triple(dirPath, assetName, isSpine)，明确区分 Spine 动画和静态图片，
 * 支持静态图片资源通过 [AsyncImage] 直接渲染（无需 Spine 运行时）。
 */
private data class ResolvedSdAsset(
    /** 资源目录绝对路径 */
    val dirPath: String,
    /** 三件套主名（.skel 去扩展名）或图片文件名去扩展名 */
    val assetName: String,
    /** 资源类型：Spine 动画 / 静态图片 */
    val type: SDResourceType,
    /** 静态图片完整文件路径（仅 [type] = STATIC_IMAGE 时有效） */
    val imageFilePath: String? = null
) {
    /** 是否为 Spine 动画资源 */
    val isSpine: Boolean get() = type == SDResourceType.SPINE
}

/**
 * 在目录中查找指定 assetName 对应的图片文件。
 * 支持 .png / .webp / .jpg / .jpeg 格式。
 */
private fun findImageFile(dirPath: String, assetName: String): String? {
    val dir = java.io.File(dirPath)
    if (!dir.isDirectory) return null
    val extensions = listOf(".png", ".webp", ".jpg", ".jpeg")
    return extensions.firstNotNullOfOrNull { ext ->
        val file = java.io.File(dir, "$assetName$ext")
        if (file.exists()) file.absolutePath else null
    }
}

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
    sdScale: Float = 1.0f,
    /** 自定义 SD 资源 ID（空=跟随舰名匹配，非空=直接按 ID 加载该资源，解除舰名限制） */
    sdResourceId: String = "",
    /**
     * 悬浮窗触摸穿透（true=触摸事件穿透到下层应用，false=正常响应触摸交互）。
     *
     * - 系统悬浮窗场景：由 Service 层 FLAG_NOT_TOUCHABLE 控制，此参数不影响渲染层
     * - 应用内场景：触摸穿透不生效，SD 小人始终可交互（点击/拖动）。
     *   触摸穿透是系统悬浮窗专属功能，应用内 SD 小人是 App UI 的一部分，应始终响应触摸
     */
    overlayTouchPassthrough: Boolean = false,
    /**
     * 拖动状态变化回调（仅 [isSystemOverlay] = true 时触发）。
     *
     * Service 层据此切换辅助窗口内容：
     * - 拖动开始 → 辅助窗口显示"拖动调整位置"提示
     * - 拖动结束 → 辅助窗口恢复显示气泡（若有对话）或移除
     */
    onDragStateChanged: ((Boolean) -> Unit)? = null
) {
    // 同时检查 figureUrl 和 sdResourceId：
    // - 两者都空 → 无任何可渲染内容，提前返回
    // - figureUrl 空 + sdResourceId 非空 → 有 SD 资源，继续渲染（Spine/静态图片不依赖 figureUrl）
    // - figureUrl 非空 → 有网络立绘回退，继续渲染
    if (figureUrl.isEmpty() && sdResourceId.isEmpty()) return

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val hapticFeedback = LocalHapticFeedback.current
    val context = LocalContext.current

    // 尝试解析 SD 小人动画资源；解析到则用 Spine 渲染，否则回退静态立绘。
    // 统一使用 SDResourceManager.resolve：
    // - 优先用 sdResourceId 直接按 ID 加载（解除舰名限制，支持任意 SD 资源）
    // - sdResourceId 为空时回退到舰名匹配（向后兼容）
    // 关键：将 SDResourceManager.revision 作为 remember key，
    // 整理/清理资源后 refreshIndex() 递增 revision，触发 sdAsset 重新解析，
    // 避免整理后仍使用旧的 null 结果导致 SD 小人不可动。
    val sdResource = remember(shipName, selectedSkin, sdResourceId, SDResourceManager.revision.value) {
        SDResourceManager.resolve(context, sdResourceId, shipName, selectedSkin)
    }
    // 将 SDResource 转换为渲染所需的 ResolvedSdAsset：
    // - Spine 动画 → 传递 dirPath + assetName 给 SpineSdView
    // - 静态图片 → 查找实际图片文件路径，用 AsyncImage 渲染
    // - 未知类型 → 返回 null，回退到网络立绘 figureUrl
    val sdAsset = sdResource?.let { res ->
        val skin = res.getSkin(selectedSkin)
        when (skin.type) {
            SDResourceType.SPINE -> ResolvedSdAsset(
                dirPath = skin.dirPath,
                assetName = skin.assetName,
                type = SDResourceType.SPINE
            )
            SDResourceType.STATIC_IMAGE -> {
                val imagePath = findImageFile(skin.dirPath, skin.assetName)
                if (imagePath != null) {
                    ResolvedSdAsset(
                        dirPath = skin.dirPath,
                        assetName = skin.assetName,
                        type = SDResourceType.STATIC_IMAGE,
                        imageFilePath = imagePath
                    )
                } else null
            }
            SDResourceType.UNKNOWN -> null
        }
    }

    val screenWidth = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeight = with(density) { configuration.screenHeightDp.dp.toPx() }

    val displayScale = sdScale.coerceIn(0.3f, 2.0f)
    val baseWidthPx = with(density) { CHIBI_BASE_WIDTH.toPx() }
    val baseHeightPx = with(density) { CHIBI_BASE_HEIGHT.toPx() }

    // ════════════════════════════════════════════════════════════════════════════
    // 系统悬浮窗场景：主窗口尺寸紧贴小人实际显示范围
    // ════════════════════════════════════════════════════════════════════════════
    //
    // 【优化前】单一 220×365dp 大窗口容纳立绘(110×165dp) + 气泡(220×200dp)，
    //   透明区域被 WindowManager 吞掉触摸事件，导致用户无法操作下方 App。
    //
    // 【优化后】主窗口仅包含 SpineSdView，尺寸 = baseSize × displayScale：
    //   - sdScale=1.0 → 110×165dp（面积仅占原 220×365dp 的 22.5%）
    //   - sdScale=0.5 → 55×82dp
    //   - sdScale=2.0 → 220×330dp
    //   触摸区域天然贴合小人显示范围，无透明区域拦截问题。
    //   气泡和拖动提示由 Service 层独立辅助窗口处理（紧贴主窗口上方）。
    //
    // SpineSdView scaleMultiplier = 1.0：
    //   视口尺寸已反映 displayScale（窗口=baseSize×displayScale），
    //   SpineRenderer 自适应缩放 min(vw/boundsW, vh/boundsH)*0.95 已正确，
    //   无需额外 userScale。小人渲染高度 = baseHeightPx × displayScale × 0.95（高瘦型）。
    if (isSystemOverlay) {
        val overlayWidth = CHIBI_BASE_WIDTH * displayScale
        val overlayHeight = CHIBI_BASE_HEIGHT * displayScale

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

        Box(modifier = modifier.size(overlayWidth, overlayHeight)) {
            if (sdAsset != null && sdAsset.isSpine) {
                // ── SD 小人（Spine 动画）──
                // 直接 fillMaxSize：窗口尺寸 = 渲染尺寸，无需 requiredSize + offset 补偿。
                // 触摸穿透由 SpineSdGlSurfaceView 内部用骨骼 bounds 判断，无需外部限制。
                // scaleMultiplier = 1.0：视口已反映 displayScale，SpineRenderer 自适应缩放即可。
                SpineSdView(
                    dirPath = sdAsset.dirPath,
                    assetName = sdAsset.assetName,
                    modifier = Modifier.fillMaxSize(),
                    scaleMultiplier = 1.0f,
                    onTap = {
                        isTapped = true
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onTap()
                    },
                    onDragStart = {
                        isDragging = true
                        onDragStateChanged?.invoke(true)
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDrag = { dx, dy -> onPositionChange?.invoke(dx, dy) },
                    onDragEnd = {
                        isDragging = false
                        onDragStateChanged?.invoke(false)
                    }
                )
            } else {
                // ── 静态图片资源 / 网络立绘回退 ──
                // 静态图片资源用 sdAsset.imageFilePath（本地文件），无资源时回退 figureUrl（网络 URL）。
                // 窗口尺寸 = baseSize × displayScale，AsyncImage ContentScale.Fit 自动适配。
                // 拖动/点击通过 pointerInput 处理，位置变化通过 onPositionChange 通知 Service。
                val imageModel = sdAsset?.imageFilePath ?: figureUrl
                Box(
                    modifier = Modifier
                        .fillMaxSize()
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
                                    onDragStateChanged?.invoke(true)
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDragEnd = {
                                    isDragging = false
                                    onDragStateChanged?.invoke(false)
                                },
                                onDragCancel = {
                                    isDragging = false
                                    onDragStateChanged?.invoke(false)
                                }
                            ) { change, dragAmount ->
                                change.consume()
                                onPositionChange?.invoke(dragAmount.x, dragAmount.y)
                            }
                        }
                ) {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = "秘书舰 $shipName",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
    } else {
        // ════════════════════════════════════════════════════════════════════════════
        // 内嵌场景（isSystemOverlay=false）：保持原有逻辑
        //
        // 内嵌场景下 Compose 事件分发正常穿透（不像 WindowManager 窗口吞掉事件），
    // 无透明区域拦截问题，保持原有的容器跟随放大 + 中心位置补偿方案。
    //
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
    val containerScale = displayScale.coerceAtLeast(1.0f)
    val characterScale = displayScale / containerScale  // 放大时=1.0，缩小时=sdScale

    // 渲染容器尺寸：放大时跟随放大，保证 Spine 骨骼渲染完整不裁剪
    val renderWidth = CHIBI_BASE_WIDTH * containerScale
    val renderHeight = CHIBI_BASE_HEIGHT * containerScale

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
            modifier = Modifier
                .size(CHIBI_BASE_WIDTH, CHIBI_BASE_HEIGHT)
                .offset { IntOffset(localOffsetX.roundToInt(), localOffsetY.roundToInt()) }
        ) {
            if (sdAsset != null && sdAsset.isSpine) {
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
                // 触摸穿透由 SpineSdGlSurfaceView 内部用骨骼 bounds 判断：
                // 放大时 GLSurfaceView 容器变大（renderWidth > baseWidth），
                // 但骨骼 bounds 只占视口 95%（0.95 系数），bounds 外的透明区域事件穿透。
                // 无需外层 touchAreaRatio 限制，bounds 判断更精确（贴合角色实际形状）。
                SpineSdView(
                    dirPath = sdAsset.dirPath,
                    assetName = sdAsset.assetName,
                    modifier = Modifier
                        .requiredSize(renderWidth, renderHeight)
                        .offset { IntOffset(renderOffsetX.roundToInt(), renderOffsetY.roundToInt()) },
                    scaleMultiplier = characterScale,
                    // 应用内场景始终可交互：触摸穿透仅用于系统悬浮窗（FLAG_NOT_TOUCHABLE），
                    // 应用内 SD 小人是 App UI 的一部分，应始终响应点击/拖动
                    touchEnabled = true,
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
                        localOffsetX = (localOffsetX + dx).coerceIn(0f, (screenWidth - baseWidthPx).coerceAtLeast(0f))
                        localOffsetY = (localOffsetY + dy).coerceIn(dragMinY, dragMaxY)
                    },
                    onDragEnd = { isDragging = false }
                )
            } else {
                // ── 静态图片资源 / 网络立绘回退 ──
                // 静态图片资源用 sdAsset.imageFilePath（本地文件），无资源时回退 figureUrl（网络 URL）。
                // 仅在此场景使用 pointerInput + scale/alpha 动画
                // 渲染层尺寸同 SD 小人场景，保证放大时渲染完整
                // 同样使用 requiredSize 突破外层 Box 的尺寸约束（见上方 SD 小人注释）
                val imageModel = sdAsset?.imageFilePath ?: figureUrl
                Box(
                    modifier = Modifier
                        .requiredSize(renderWidth, renderHeight)
                        .offset { IntOffset(renderOffsetX.roundToInt(), renderOffsetY.roundToInt()) }
                        .graphicsLayer {
                            scaleX = staticScale
                            scaleY = staticScale
                            this.alpha = staticAlpha
                        }
                        // 应用内场景始终可交互：触摸穿透仅用于系统悬浮窗
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
                                localOffsetX = (localOffsetX + dragAmount.x).coerceIn(0f, (screenWidth - baseWidthPx).coerceAtLeast(0f))
                                localOffsetY = (localOffsetY + dragAmount.y).coerceIn(dragMinY, dragMaxY)
                            }
                        }
                ) {
                    AsyncImage(
                        model = imageModel,
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
                        val minBubbleY = -localOffsetY
                        if (bubbleTopY < minBubbleY) bubbleTopY = minBubbleY
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
    } // end else (isSystemOverlay=false)
}

/**
 * 悬浮窗辅助窗口内容：根据状态显示气泡或拖动提示。
 *
 * 供 [SecretaryOverlayService] 的独立辅助窗口使用，与主窗口（仅含 SpineSdView）分离。
 * - 非拖动且有对话 → 显示气泡（紧贴主窗口上方）
 * - 拖动中 → 显示"拖动调整位置"提示
 * - 其他 → 空内容（Service 会移除辅助窗口）
 *
 * @param onSizeChanged 辅助内容尺寸变化回调，Service 据此更新窗口位置（紧贴主窗口上方）
 */
@Composable
fun SecretaryOverlayAuxiliaryContent(
    dialogue: String?,
    isDragging: Boolean,
    modifier: Modifier = Modifier,
    onSizeChanged: ((widthPx: Int, heightPx: Int) -> Unit)? = null
) {
    if (dialogue == null && !isDragging) return
    val isDark = LocalIsDark.current
    Box(
        modifier = modifier.onGloballyPositioned {
            onSizeChanged?.invoke(it.size.width, it.size.height)
        }
    ) {
        if (isDragging) {
            Surface(
                shape = RoundedCornerShape(AppSpacing.Corner.Sm),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                shadowElevation = 4.dp
            ) {
                Text(
                    text = "拖动调整位置",
                    style = AppTypography.LabelSmallBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = AppSpacing.Sm, vertical = AppSpacing.Xxs)
                )
            }
        } else {
            SecretarySpeechBubble(text = dialogue ?: "", isDark = isDark)
        }
    }
}

@Composable
internal fun SecretarySpeechBubble(text: String, isDark: Boolean) {
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
