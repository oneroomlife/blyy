package com.azurlane.blyy.ui.components

import android.graphics.RectF
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
import com.azurlane.blyy.ui.theme.ChatColors
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.util.LocalSdResolver
import com.azurlane.blyy.util.SDResourceManager
import com.azurlane.blyy.util.SDResourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
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
internal const val CHIBI_VIEWPORT_USAGE = 0.95f

/**
 * 拖动边界余量比例：允许小人头顶超出容器顶部此比例的距离，使头顶可达屏幕顶端。
 *
 * 与气泡定位解耦：气泡需要精确的小人头顶位置，而拖动边界只需保证小人不会被拖到看不见。
 *
 * SecretaryOverlayService 的 DRAG_OVERFLOW_RATIO 与此为同一语义（悬浮窗允许向上溢出比例），
 * 直接复用本常量，避免双处定义漂移。
 */
internal const val CHIBI_DRAG_MARGIN_RATIO = 0.15f

/** SD 小人基础宽度（dp），用于窗口尺寸计算（悬浮窗服务共用，保证双端窗口尺寸一致） */
internal val CHIBI_BASE_WIDTH = 110.dp

/** SD 小人基础高度（dp），用于窗口尺寸计算（悬浮窗服务共用，保证双端窗口尺寸一致） */
internal val CHIBI_BASE_HEIGHT = 165.dp

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

/**
 * 应用内小人位置的进程级锚点。
 *
 * 拖拽位置此前只存在组合内 remember 中：进入遮蔽路由（live2d）或系统悬浮窗
 * 互斥切换导致组合销毁时位置丢失，回到可见路由后小人跳回默认落点。
 * 锚点让位置在组合重建间保留；仅进程级，不持久化跨重启（重启回默认位，
 * 与系统悬浮窗的 DataStore 持久化定位分工不同）。
 */
private object InAppChibiPositionAnchor {
    var initialized = false
    var offsetX = 0f
    var offsetY = 0f
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
    /** wiki 网页端 Q版小人（SD 静态图）URL：本地 SD 资源未匹配时的回退显示图，
     *  优先取「查看立绘」中用户选择的 SD 小人，跟随其皮肤选择；为空时回退 figureUrl（完整立绘） */
    webChibiUrl: String = "",
    /**
     * 悬浮窗静态图分支的可交互矩形上报（仅 [isSystemOverlay] = true 时使用）。
     *
     * 每次图片 Fit 矩形变化时上报（窗口本地坐标 px）；null = 无可交互内容。
     * Service 层 OverlayGestureHandler 据此做屏幕空间命中判断——静态图交互
     * 已上移到 Service 层（修复自参考系拖拽漂移），Composable 层不再挂 pointerInput。
     */
    onHitRectChanged: ((RectF?) -> Unit)? = null,
    /**
     * 悬浮窗分支模式上报（仅 [isSystemOverlay] = true 时使用）。
     *
     * true = 当前渲染 Spine 动画（交互由 GLSurfaceView 自身 OnTouchListener 处理，
     * Service 层手势处理器放行）；false = 静态图分支（交互由 Service 层处理）。
     */
    onSpineActiveChanged: ((Boolean) -> Unit)? = null,
    /**
     * 拖动状态变化回调（仅 [isSystemOverlay] = true 时触发）。
     *
     * Service 层据此切换辅助窗口内容：
     * - 拖动开始 → 辅助窗口显示"拖动调整位置"提示
     * - 拖动结束 → 辅助窗口恢复显示气泡（若有对话）或移除
     */
    onDragStateChanged: ((Boolean) -> Unit)? = null
) {
    // 同时检查 figureUrl / webChibiUrl / sdResourceId：
    // - 三者都空 → 无任何可渲染内容，提前返回
    // - figureUrl 空 + webChibiUrl 空 + sdResourceId 非空 → 有 SD 资源，继续渲染（Spine/静态图片不依赖 figureUrl）
    // - figureUrl 或 webChibiUrl 非空 → 有静态回退图（网页 Q版小人 / 网络立绘），继续渲染
    if (figureUrl.isEmpty() && webChibiUrl.isEmpty() && sdResourceId.isEmpty()) return

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val hapticFeedback = rememberBlyyHaptics()
    val context = LocalContext.current

    // 尝试解析 SD 小人动画资源；解析到则用 Spine 渲染，否则回退静态立绘。
    // 统一使用 SDResourceManager.resolve：
    // - 优先用 sdResourceId 直接按 ID 加载（解除舰名限制，支持任意 SD 资源）
    // - sdResourceId 为空时回退到舰名匹配（向后兼容）
    // 磁盘 I/O 后台化：resolve 首次会构建索引（全目录递归扫描），后续每次也涉及
    // listFiles/exists，在组合期主线程执行会导致悬浮窗创建/秘书舰页明显卡顿。
    // 改为 LaunchedEffect + Dispatchers.IO 异步解析：
    // - sdResolved=false 期间不回退网络立绘（避免立绘→小人闪烁），短暂空白等解析完成
    // - 结果用普通 remember 持有（produceState 在 key 变化时重置为 null），重解析期间
    //   沿用旧值（stale-while-revalidate）
    // 关键：将 SDResourceManager.revision 作为 key，
    // 整理/清理资源后 refreshIndex() 递增 revision，触发重新解析，
    // 避免整理后仍使用旧的 null 结果导致 SD 小人不可动。
    var sdResolved by remember { mutableStateOf(false) }
    var sdAsset by remember { mutableStateOf<ResolvedSdAsset?>(null) }
    LaunchedEffect(shipName, selectedSkin, sdResourceId, SDResourceManager.revision.value) {
        val resolved = withContext(Dispatchers.IO) {
            SDResourceManager.resolve(context, sdResourceId, shipName, selectedSkin)?.let { res ->
                // 将 SDResource 转换为渲染所需的 ResolvedSdAsset：
                // - Spine 动画 → 传递 dirPath + assetName 给 SpineSdView
                // - 静态图片 → 查找实际图片文件路径，用 AsyncImage 渲染
                // - 未知类型 → 返回 null，回退到网络立绘 figureUrl
                val skin = res.getSkin(selectedSkin)
                when (skin.type) {
                    SDResourceType.SPINE -> ResolvedSdAsset(
                        dirPath = skin.dirPath,
                        assetName = skin.assetName,
                        type = SDResourceType.SPINE
                    )
                    SDResourceType.STATIC_IMAGE -> findImageFile(skin.dirPath, skin.assetName)?.let { imagePath ->
                        ResolvedSdAsset(
                            dirPath = skin.dirPath,
                            assetName = skin.assetName,
                            type = SDResourceType.STATIC_IMAGE,
                            imageFilePath = imagePath
                        )
                    }
                    SDResourceType.UNKNOWN -> null
                }
            }
        }
        sdAsset = resolved
        sdResolved = true
    }
    val asset = sdAsset

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

        // 拖动/点击的视觉反馈状态由 Service 层回调驱动：
        // - Spine 分支：SpineSdView 的 onDragStart/onDragEnd/onTap 回调（下方直接接线）
        // - 静态图分支：Service 层 OverlayGestureHandler → onDragStateChanged 回调
        //   （isDragging 由 Service setContent 中的状态写入，见 SecretaryOverlayService）

        Box(modifier = modifier.size(overlayWidth, overlayHeight)) {
            if (asset != null && asset.isSpine) {
                // ── SD 小人（Spine 动画）──
                // 直接 fillMaxSize：窗口尺寸 = 渲染尺寸，无需 requiredSize + offset 补偿。
                // 触摸穿透由 SpineSdGlSurfaceView 内部用骨骼 bounds 判断，无需外部限制。
                // scaleMultiplier = 1.0：视口已反映 displayScale，SpineRenderer 自适应缩放即可。
                LaunchedEffect(asset) { onSpineActiveChanged?.invoke(true) }
                SpineSdView(
                    dirPath = asset.dirPath,
                    assetName = asset.assetName,
                    modifier = Modifier.fillMaxSize(),
                    scaleMultiplier = 1.0f,
                    onTap = {
                        isTapped = true
                        hapticFeedback(BlyyHaptic.Tick)
                        onTap()
                    },
                    onDragStart = {
                        isDragging = true
                        onDragStateChanged?.invoke(true)
                        hapticFeedback(BlyyHaptic.LongPress)
                    },
                    onDrag = { dx, dy -> onPositionChange?.invoke(dx, dy) },
                    onDragEnd = {
                        isDragging = false
                        onDragStateChanged?.invoke(false)
                    }
                )
            } else {
                // ── 静态图片资源 / 网页 Q版小人 / 网络立绘回退 ──
                // 【交互已上移到 Service 层】旧 Compose pointerInput(detectDragGestures)
                // 的 dragAmount 是视图本地坐标增量，而拖动会移动窗口本身——窗口跟随
                // 手指移动后本地坐标几乎不变，dragAmount 趋近 0，拖拽极慢/卡死
                // （与 SpineSdGlSurfaceView 注释记载的同源问题）。
                // 现由 Service 层 OverlayGestureHandler 用 rawX/rawY 屏幕绝对坐标处理，
                // 本层只上报可交互矩形（Fit 后图片实际显示区域）供命中判断，
                // 并保留点击/拖动的视觉反馈动画（状态由 Service 层回调写入）。
                LaunchedEffect(asset) { onSpineActiveChanged?.invoke(false) }
                val imagePath = asset?.imageFilePath
                val imageModel = when {
                    !sdResolved -> null
                    imagePath != null -> imagePath
                    webChibiUrl.isNotEmpty() -> webChibiUrl
                    else -> figureUrl
                }
                // 图源降级链：wiki 小人/本地静态图加载失败时回退完整立绘 figureUrl
                val fallbackModel = figureUrl.takeIf { it.isNotEmpty() && it != imageModel }
                InteractiveStaticFigure(
                    containerWidthPx = with(density) { overlayWidth.toPx() },
                    containerHeightPx = with(density) { overlayHeight.toPx() },
                    imageModel = imageModel,
                    fallbackModel = fallbackModel,
                    contentDescription = "秘书舰 $shipName",
                    onTap = {
                        isTapped = true
                        hapticFeedback(BlyyHaptic.Tick)
                        onTap()
                    },
                    onDragStart = {
                        isDragging = true
                        onDragStateChanged?.invoke(true)
                        hapticFeedback(BlyyHaptic.LongPress)
                    },
                    onDragEnd = {
                        isDragging = false
                        onDragStateChanged?.invoke(false)
                    },
                    onDrag = { dx, dy -> onPositionChange?.invoke(dx, dy) },
                    scale = staticScale,
                    alpha = staticAlpha,
                    interactive = false,
                    onHitRectChanged = onHitRectChanged
                )
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
        // coerceAtLeast(dragMinY)：小屏 + displayScale 接近 2.0 时原始值可能小于
        // dragMinY（下界反转，拖动范围整个越界），夹取保证 maxY ≥ minY 至少合法
        val dragMaxY = (screenHeight - chibiHeadTopY -
            baseHeightPx * (CHIBI_VIEWPORT_USAGE * displayScale - CHIBI_DRAG_MARGIN_RATIO))
            .coerceAtLeast(dragMinY)

    // 首次组合以默认落点初始化进程级锚点；此后组合重建从锚点恢复拖拽位置
    // （live2d 遮蔽/悬浮窗互斥销毁组合不再跳回默认位），并按当前屏幕夹取边界，
    // 防止恢复值在转屏/折叠后越界闪现
    if (!InAppChibiPositionAnchor.initialized) {
        InAppChibiPositionAnchor.offsetX = screenWidth - baseWidthPx - 24f
        InAppChibiPositionAnchor.offsetY = screenHeight * 0.5f - baseHeightPx / 2
        InAppChibiPositionAnchor.initialized = true
    }
    val restoreMaxX = (screenWidth - baseWidthPx).coerceAtLeast(0f)
    var localOffsetX by remember {
        mutableFloatStateOf(InAppChibiPositionAnchor.offsetX.coerceIn(0f, restoreMaxX))
    }
    var localOffsetY by remember {
        mutableFloatStateOf(InAppChibiPositionAnchor.offsetY.coerceIn(dragMinY, dragMaxY))
    }

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
            if (asset != null && asset.isSpine) {
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
                    dirPath = asset.dirPath,
                    assetName = asset.assetName,
                    modifier = Modifier
                        .requiredSize(renderWidth, renderHeight)
                        .offset { IntOffset(renderOffsetX.roundToInt(), renderOffsetY.roundToInt()) },
                    scaleMultiplier = characterScale,
                    // 应用内场景始终可交互：触摸穿透仅用于系统悬浮窗（FLAG_NOT_TOUCHABLE），
                    // 应用内 SD 小人是 App UI 的一部分，应始终响应点击/拖动
                    touchEnabled = true,
                    onTap = {
                        isTapped = true
                        hapticFeedback(BlyyHaptic.Tick)
                        onTap()
                    },
                    onDragStart = {
                        isDragging = true
                        hapticFeedback(BlyyHaptic.LongPress)
                    },
                    onDrag = { dx, dy ->
                        localOffsetX = (localOffsetX + dx).coerceIn(0f, (screenWidth - baseWidthPx).coerceAtLeast(0f))
                        localOffsetY = (localOffsetY + dy).coerceIn(dragMinY, dragMaxY)
                    },
                    onDragEnd = {
                        isDragging = false
                        InAppChibiPositionAnchor.offsetX = localOffsetX
                        InAppChibiPositionAnchor.offsetY = localOffsetY
                    }
                )
            } else {
                // ── 静态图片资源 / 网页 Q版小人 / 网络立绘回退 ──
                // 回退优先级：本地静态资源 → 网页 Q版小人（跟随「查看立绘」选择）→ 网络立绘。
                // 静态图片资源用 asset.imageFilePath（本地文件）；
                // 本地 SD 资源未匹配时优先用 webChibiUrl（wiki 静态小人，比完整立绘视觉更贴合 SD 场景）；
                // 无小人图时最终回退 figureUrl（网络立绘 URL）。
                // SD 解析未完成期间 imageModel = null：先不渲染，避免立绘→SD 小人的闪烁
                // 仅在此场景使用 pointerInput + scale/alpha 动画
                // 渲染层尺寸同 SD 小人场景，保证放大时渲染完整
                // 同样使用 requiredSize 突破外层 Box 的尺寸约束（见上方 SD 小人注释）
                val imagePath = asset?.imageFilePath
                val imageModel = when {
                    !sdResolved -> null
                    imagePath != null -> imagePath
                    webChibiUrl.isNotEmpty() -> webChibiUrl
                    else -> figureUrl
                }
                // 图源降级链：wiki 小人/本地静态图加载失败时回退完整立绘 figureUrl
                val fallbackModel = figureUrl.takeIf { it.isNotEmpty() && it != imageModel }
                // 容器跟随放大 + 居中补偿的定位 Box 内放置交互层：
                // 交互区域 = 图片实际显示矩形，四周透明区域的事件穿透到父级
                // （不再挡住页面滚动等父级手势）
                Box(
                    modifier = Modifier
                        .requiredSize(renderWidth, renderHeight)
                        .offset { IntOffset(renderOffsetX.roundToInt(), renderOffsetY.roundToInt()) }
                ) {
                    InteractiveStaticFigure(
                        containerWidthPx = with(density) { renderWidth.toPx() },
                        containerHeightPx = with(density) { renderHeight.toPx() },
                        imageModel = imageModel,
                        fallbackModel = fallbackModel,
                        contentDescription = "秘书舰 $shipName",
                        onTap = {
                            isTapped = true
                            hapticFeedback(BlyyHaptic.Tick)
                            onTap()
                        },
                        onDragStart = {
                            isDragging = true
                            hapticFeedback(BlyyHaptic.LongPress)
                        },
                        onDragEnd = {
                            isDragging = false
                            InAppChibiPositionAnchor.offsetX = localOffsetX
                            InAppChibiPositionAnchor.offsetY = localOffsetY
                        },
                        onDrag = { dx, dy ->
                            localOffsetX = (localOffsetX + dx).coerceIn(0f, (screenWidth - baseWidthPx).coerceAtLeast(0f))
                            localOffsetY = (localOffsetY + dy).coerceIn(dragMinY, dragMaxY)
                        },
                        scale = staticScale,
                        alpha = staticAlpha
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
                BlyySpeechBubble(text = dialogue ?: "", isDark = isDark, maxLines = 2, modifier = Modifier.widthIn(max = 280.dp))
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
            BlyySpeechBubble(text = dialogue ?: "", isDark = isDark, maxLines = 2, modifier = Modifier.widthIn(max = 280.dp))
        }
    }
}

/**
 * 静态立绘渲染层：把显示限制在图片实际显示区域（ContentScale.Fit 后的矩形）。
 *
 * 背景：ContentScale.Fit 四周的透明区域不应参与显示与交互——悬浮窗场景挡住下层 App，
 * 应用内场景挡住父级滚动手势。
 *
 * 两种交互模式：
 * - [interactive] = true（应用内）：根坐标锚定拖拽 + 点击。旧 detectDragGestures 的
 *   dragAmount 是节点本地坐标增量，而拖动回调会移动节点本身——节点跟随手指移动后
 *   本地坐标反向漂移，表现为拖拽速度减半/卡顿。现改用手势锚定会话：在 DOWN 时记录
 *   锚点（节点 offset + 指针窗口坐标），MOVE 时从锚点**绝对**推导新 offset——
 *   节点自身移动不影响窗口坐标系测量帧，一次写对，无累积误差。
 * - [interactive] = false（系统悬浮窗）：本层不挂任何 pointerInput，交互由 Service 层
 *   OverlayGestureHandler（rawX/rawY 屏幕绝对坐标）处理；本层仅通过
 *   [onHitRectChanged] 上报图片 Fit 矩形（窗口本地坐标）供命中判断。
 *
 * @param containerWidthPx/containerHeightPx 容器尺寸（px），显示矩形在其内计算
 * @param imageModel Coil 模型；null = 无图可显示，也不提供交互
 * @param scale/alpha 点击/拖动反馈动画（与原静态分支一致）
 */
@Composable
private fun InteractiveStaticFigure(
    containerWidthPx: Float,
    containerHeightPx: Float,
    imageModel: Any?,
    contentDescription: String,
    onTap: () -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    alpha: Float = 1f,
    /**
     * 主图源加载失败时的降级图源（如 wiki 小人 URL 失效 → 回退完整立绘 figureUrl）。
     *
     * 【空白修复】此前 Coil 加载失败时 imageAspect 永不写入 → fitRect 为 null →
     * 整个组件直接不渲染任何内容，即使降级图源可用也表现为"什么都不显示"。
     */
    fallbackModel: Any? = null,
    /** 交互开关：true=应用内（本层处理手势），false=悬浮窗（Service 层处理，仅上报矩形） */
    interactive: Boolean = true,
    /** 悬浮窗场景：图片 Fit 矩形（窗口本地坐标 px）变化时上报；null = 无可交互内容 */
    onHitRectChanged: ((RectF?) -> Unit)? = null
) {
    // 图片宽高比：由 Coil 加载成功回调写入；模型变化时重置（切舰娘/皮肤后重新测量）
    var imageAspect by remember { mutableStateOf<Float?>(null) }
    // 主图源加载失败 → 切换到降级图源重新加载（onError 幂等，重复触发不循环）
    var loadFailed by remember(imageModel, fallbackModel) { mutableStateOf(false) }
    val activeModel = if (loadFailed && fallbackModel != null) fallbackModel else imageModel
    LaunchedEffect(activeModel) { imageAspect = null }
    val fitRect = remember(imageAspect, containerWidthPx, containerHeightPx) {
        computeFittedImageRect(imageAspect, containerWidthPx, containerHeightPx)
    }

    // 悬浮窗场景：把 Fit 矩形上报给 Service 层手势处理器做命中判断
    if (onHitRectChanged != null) {
        LaunchedEffect(fitRect) { onHitRectChanged(fitRect) }
    }

    // 无图可加载：不组合任何内容（悬浮窗场景同时上报 null 矩形，不拦截触摸）
    if (activeModel == null) return

    val density = LocalDensity.current
    // 【空白死锁修复】此前 fitRect == null 时整个组件提前 return，而 AsyncImage 位于
    // return 之后——首次组合时 aspect 未知 → 组件直接返回 → 图片请求永不发出 →
    // onSuccess 永不回调 → aspect 永远为 null → 表现为"什么都不显示"。
    // 修复：AsyncImage 始终组合保证请求发出；aspect 未知期间以 alpha=0 的全尺寸布局
    // 预加载（不可见、不可交互），写入后重新布局为 Fit 精确矩形并启用交互。
    Box(
        modifier = modifier
            .then(
                if (fitRect != null) {
                    Modifier
                        .offset { IntOffset(fitRect.left.roundToInt(), fitRect.top.roundToInt()) }
                        .size(
                            with(density) { fitRect.width().toDp() },
                            with(density) { fitRect.height().toDp() }
                        )
                } else {
                    Modifier.fillMaxSize()
                }
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                // fitRect 未知期间仅作预加载容器：完全透明，不干扰视觉
                this.alpha = if (fitRect != null) alpha else 0f
            }
            .then(
                if (interactive && fitRect != null) {
                    // 应用内场景：根坐标锚定手势（见函数 KDoc），无自参考系漂移
                    Modifier.anchorDragGestures(
                        onTap = onTap,
                        onDragStart = onDragStart,
                        onDragEnd = onDragEnd,
                        onDrag = onDrag
                    )
                } else {
                    // 悬浮窗场景（Service 层处理交互）或 aspect 未写入前：不挂交互，
                    // 不拦截触摸（悬浮窗穿透到下层 App / 应用内穿透到父级滚动）
                    Modifier
                }
            )
    ) {
        AsyncImage(
            model = activeModel,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            // 矩形尺寸 = 图片宽高比精确计算，FillBounds 不会引入变形
            contentScale = ContentScale.FillBounds,
            onSuccess = { state ->
                val intrinsic = state.painter.intrinsicSize
                if (intrinsic.width > 0f && intrinsic.height > 0f) {
                    imageAspect = intrinsic.width / intrinsic.height
                }
            },
            onError = {
                if (fallbackModel != null) loadFailed = true
            }
        )
    }
}

/**
 * 根坐标锚定拖拽手势修饰符（Composable 工厂形式）。
 *
 * 修复 detectDragGestures 在"拖动回调移动节点自身"场景下的自参考系漂移：
 * dragAmount 是节点本地坐标增量，节点随手指移动后增量被节点位移抵消
 * （手指移动 dx 的同时节点也移动 dx，下帧本地坐标几乎不变）。
 *
 * 本实现改为**绝对推导**：每个事件把指针位置换算到窗口坐标系
 * （[LayoutCoordinates.localToWindow]，窗口坐标不随本节点移动变化），
 * 增量 = 当前窗口坐标 − 上一事件窗口坐标，与节点自身位移无关，
 * 手指移动多少节点就跟多少，无累积误差。
 *
 * 手势语义与系统一致：超 touchSlop 进入拖动，DOWN→UP 未超 slop 识别为点击。
 */
@Composable
private fun Modifier.anchorDragGestures(
    onTap: () -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit
): Modifier {
    // PointerInputScope 无 coordinates 属性（Compose 1.10），
    // 用 onGloballyPositioned 捕获本节点布局坐标做 localToWindow 换算
    var coordinatesRef by remember { mutableStateOf<LayoutCoordinates?>(null) }
    return this
        .onGloballyPositioned { coordinatesRef = it }
        .pointerInput(Unit) {
            val touchSlop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var lastWindowPos = coordinatesRef?.localToWindow(down.position) ?: return@awaitEachGesture
                var dragging = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val windowPos = coordinatesRef?.localToWindow(change.position) ?: break
                        if (!dragging) {
                            val dx = windowPos.x - lastWindowPos.x
                            val dy = windowPos.y - lastWindowPos.y
                            if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                                dragging = true
                                onDragStart()
                                // 拖动判定成立后回退到本事件起点重算增量，避免 slop 内的
                                // 位移被丢弃造成起拖瞬间的小跳变
                                lastWindowPos = coordinatesRef?.localToWindow(down.position) ?: windowPos
                            }
                        }
                        if (dragging) {
                            val dx = windowPos.x - lastWindowPos.x
                            val dy = windowPos.y - lastWindowPos.y
                            if (dx != 0f || dy != 0f) {
                                onDrag(dx, dy)
                                change.consume()
                            }
                        }
                        lastWindowPos = windowPos
                        if (event.changes.any { it.changedToUpIgnoreConsumed() }) {
                            if (dragging) onDragEnd() else onTap()
                            break
                        }
                    }
                } catch (e: CancellationException) {
                    // 手势流被取消（节点离树/其他手势接管）：恢复状态后重新抛出，
                    // awaitEachGesture 会等待所有指针抬起后进入下一轮
                    if (dragging) onDragEnd()
                    throw e
                }
            }
        }
}

/** 计算 ContentScale.Fit 后图片实际显示矩形（容器坐标，px）；宽高比未知或容器无效时返回 null。
 *  纯逻辑在 [OverlayGeometry.computeFittedImageRect]（可 JVM 单测），此处转换回 android RectF */
internal fun computeFittedImageRect(aspect: Float?, containerW: Float, containerH: Float): RectF? {
    return OverlayGeometry.computeFittedImageRect(aspect, containerW, containerH)?.let {
        RectF(it.left, it.top, it.right, it.bottom)
    }
}

