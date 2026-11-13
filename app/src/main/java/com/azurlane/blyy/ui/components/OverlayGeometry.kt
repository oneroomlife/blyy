package com.azurlane.blyy.ui.components

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 悬浮窗几何纯函数集（无 Android 依赖，可 JVM 单测）。
 *
 * 从 [com.azurlane.blyy.SecretaryOverlayService] 提取，Service 与单测共用同一实现，
 * 避免"测试一份、生产一份"的漂移。
 */
object OverlayGeometry {

    /**
     * 内容宽高比 → 悬浮窗尺寸（px）。
     *
     * SD 小人的可见内容比例因皮肤/资源差异极大（细长、宽扁、带大武器），
     * 旧实现把窗口固定成 110×165dp 再乘倍率，导致：
     * - 宽扁模型在窗口里只占中间一条，四周全是透明区域
     * - 透明区域仍然拦触摸（窗口内返回 false 不会下传到下层 App）
     * 这里让窗口尺寸贴合内容比例：模型铺满窗口，触摸范围自然等于可见范围。
     *
     * @param contentAspect 可见内容宽高比（宽/高）；null/非法时退回基准尺寸
     * @param baseWidth/baseHeight 基准窗口尺寸（dp 转 px）
     * @param displayScale 用户显示倍率
     * @param minSide 最小边（px），防止极端比例（躺着/细长）导致无法点击
     * @param maxWidth/maxHeight 屏幕可用尺寸上限（px）
     */
    fun computeContentWindowSize(
        contentAspect: Float?,
        baseWidth: Int,
        baseHeight: Int,
        displayScale: Float,
        minSide: Int = 0,
        maxWidth: Int = Int.MAX_VALUE,
        maxHeight: Int = Int.MAX_VALUE
    ): Pair<Int, Int> {
        if (baseWidth <= 0 || baseHeight <= 0) return 0 to 0
        val scale = if (!displayScale.isFinite() || displayScale <= 0f) 1f else displayScale
        var width: Float
        var height: Float
        if (contentAspect == null || !contentAspect.isFinite() || contentAspect <= 0f) {
            width = baseWidth.toFloat()
            height = baseHeight.toFloat()
        } else {
            val boxAspect = baseWidth.toFloat() / baseHeight.toFloat()
            if (contentAspect >= boxAspect) {
                width = baseWidth.toFloat()
                height = width / contentAspect
            } else {
                height = baseHeight.toFloat()
                width = height * contentAspect
            }
        }
        width *= scale
        height *= scale
        // 短边可点性：按比例整体放大，而不是单独拉长某个轴。
        // 单独拉长会破坏宽高比 → 渲染层按 Fit 留白 → 窗口里又出现透明触摸区，
        // 正是要修复的问题。
        if (minSide > 0 && minSide != Int.MAX_VALUE) {
            val shortSide = minOf(width, height)
            if (shortSide > 0f && shortSide < minSide) {
                val boost = minSide / shortSide
                width *= boost
                height *= boost
            }
        }
        // 超屏时等比缩小，同样保持比例（宁可整只小人变小，也不要留白条）
        if (width > 0f && height > 0f) {
            var limit = 1f
            if (maxWidth in 1 until Int.MAX_VALUE) {
                limit = minOf(limit, maxWidth.toFloat() / width)
            }
            if (maxHeight in 1 until Int.MAX_VALUE) {
                limit = minOf(limit, maxHeight.toFloat() / height)
            }
            if (limit < 1f) {
                width *= limit
                height *= limit
            }
        }
        return width.roundToInt().coerceAtLeast(1) to height.roundToInt().coerceAtLeast(1)
    }

    /**
     * 从多个候选位置中选出"clamp 后窗口中心相对旧中心偏移最小"的一个（px）。
     *
     * 缩放窗口时不同锚定方案（左/右/顶/底缘、中心保持）经边界 clamp 后的小人中心
     * 偏移不同，取偏移最小者可消除放大时的异常跳位（贴边停放时向空旷侧生长）。
     *
     * @param candidates clamp 前的期望位置（各锚定方案）
     * @param minBound/maxBound 边界 clamp 范围（maxBound 可能小于 minBound 当屏幕
     *   小于窗口时，此时统一夹到 minBound 保证返回值合法）
     * @param oldCenter 缩放前的窗口中心坐标
     * @param newSize 缩放后的窗口尺寸（用于从位置反推中心）
     */
    fun pickMinShiftAnchor(
        candidates: FloatArray,
        minBound: Float,
        maxBound: Float,
        oldCenter: Float,
        newSize: Int
    ): Int {
        val best = candidates.minByOrNull { desired ->
            val clamped = desired.coerceIn(minBound, maxBound)
            abs(clamped + newSize / 2f - oldCenter)
        } ?: return oldCenter.roundToInt() - newSize / 2
        return best.roundToInt().coerceIn(minBound.roundToInt(), maxBound.roundToInt())
    }

    /**
     * 主窗口位置的边界 clamp（拖动/转屏恢复共用）。
     *
     * - x：[0, screenWidth - windowWidth]，水平不允许超出屏幕
     * - y：允许向上溢出 [upwardOverflow]（负值，如 -15% 窗高），
     *   下界为 screenHeight - windowHeight
     */
    fun clampWindowPosition(
        x: Int,
        y: Int,
        windowWidth: Int,
        windowHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        upwardOverflow: Int
    ): Pair<Int, Int> {
        val maxX = (screenWidth - windowWidth).coerceAtLeast(0)
        // minY = -允许向上溢出的 px（如 -15% 窗高）；maxY < minY（小屏大窗）时夹到 minY
        val minY = -upwardOverflow.coerceAtLeast(0)
        val maxY = (screenHeight - windowHeight).coerceAtLeast(0)
        val clampedY = y.coerceIn(minY.coerceAtMost(maxY), maxY)
        return x.coerceIn(0, maxX) to clampedY
    }

    /** [clampWindowPosition] 的便捷重载：向上溢出按窗口高度比例计算 */
    fun clampWindowPositionByRatio(
        x: Int,
        y: Int,
        windowWidth: Int,
        windowHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        upwardOverflowRatio: Float
    ): Pair<Int, Int> = clampWindowPosition(
        x, y, windowWidth, windowHeight, screenWidth, screenHeight,
        (windowHeight * upwardOverflowRatio).toInt()
    )

    /**
     * 计算 ContentScale.Fit 后图片实际显示矩形（容器坐标，px）。
     *
     * 宽高比未知/非法或容器无效时返回 null（调用方表现为无可交互区域）。
     */
    fun computeFittedImageRect(aspect: Float?, containerW: Float, containerH: Float): RectFLike? {
        if (aspect == null || !aspect.isFinite() || aspect <= 0f || containerW <= 0f || containerH <= 0f) return null
        val containerAspect = containerW / containerH
        return if (aspect >= containerAspect) {
            // 图片更宽：宽度铺满，垂直居中
            val h = containerW / aspect
            RectFLike(0f, (containerH - h) / 2f, containerW, (containerH + h) / 2f)
        } else {
            // 图片更高：高度铺满，水平居中
            val w = containerH * aspect
            RectFLike((containerW - w) / 2f, 0f, (containerW + w) / 2f, containerH)
        }
    }

    // ── 跨应用触摸穿透（Android 12+ untrusted touch）──────────────────────────
    //
    // Android 12（API 31）起，一次触摸若要「穿过」某个窗口落到别的应用，
    // InputDispatcher 会先看窗口的 InputWindowInfo：只要该窗口不可信
    //（不是无障碍/输入法等系统窗口，且不属于接收方 UID），
    // 且合成不透明度超过阈值，事件就被静默丢弃。
    // 悬浮窗的常规做法（FLAG_NOT_TOUCHABLE）只是让窗口不接收事件，
    // 并不能让事件通过系统这一关 —— 这就是「穿透只在应用内生效」的根因来源。
    //
    // 阈值来自窗口级 LayoutParams.alpha（不是 View alpha），
    // 多窗口重叠按 1 - Π(1-αᵢ) 合成。

    /** Android 12 上 `InputManager.getMaximumObscuringOpacityForTouch()` 的取值 */
    const val DEFAULT_MAX_OBSCURING_OPACITY = 0.8f

    /**
     * 阈值余量。
     *
     * 系统侧是不透明度与阈值直接比较（float），贴着阈值容易被浮点误差判成超限，
     * 且不同厂商 ROM 可能把阈值下调；留 0.01 的余量成本极低（视觉上看不出）。
     */
    const val OBSCURING_ALPHA_SAFETY_MARGIN = 0.01f

    /** 穿透不透明度下限：再低画面就几乎看不见了，属于错误入参的兜底 */
    private const val MIN_TAP_THROUGH_ALPHA = 0.01f

    /**
     * 算出「允许触摸穿透」的窗口不透明度。
     *
     * 关键点：InputDispatcher 统计的不透明度是**同一 UID 在该触点上所有遮挡图层**的合成值，
     * 不只是我们那个 Window。`SurfaceView`（本项目里 SD 小人用的 GLSurfaceView）会额外
     * 生成一条 BLAST 子图层，且它继承父窗口的 alpha —— 于是窗口 0.79 会被算成
     * `1-(1-0.79)² = 0.96`，超过 0.80 阈值，整条触摸照样被丢：
     *
     * ```
     * W InputDispatcher: Untrusted touch due to occlusion by <pkg>/<uid>
     *                    (obscuring opacity = 0.96, maximum allowed = 0.80)
     *   * alpha=0.79 window={SurfaceView[](BLAST)#1116}
     *   * alpha=0.79 window={<pkg>}   ← 主窗口
     * ```
     *
     * 所以必须按「实际贡献的图层数」摊薄：要求 `1-(1-a)^L ≤ 阈值`，
     * 解得 `a = 1 - (1-阈值)^(1/L)`。L=1 时退回 `阈值-余量`（0.79），
     * L=2 时为 0.5417（合成 0.79），L=3 时为 0.4152。
     *
     * @param maxObscuringOpacity 系统阈值（非法值回退 [DEFAULT_MAX_OBSCURING_OPACITY]）
     * @param safetyMargin 余量（非法值按 0 处理）
     * @param obscuringLayerCount 该触点上本应用贡献的遮挡图层数（1 = 仅窗口本身）
     * @return ≤ 阈值的 alpha；阈值本身 ≥1 表示系统无限制，返回 1.0
     */
    fun tapThroughWindowAlpha(
        maxObscuringOpacity: Float = DEFAULT_MAX_OBSCURING_OPACITY,
        safetyMargin: Float = OBSCURING_ALPHA_SAFETY_MARGIN,
        obscuringLayerCount: Int = 1
    ): Float {
        val max = if (maxObscuringOpacity.isFinite() && maxObscuringOpacity > 0f) {
            maxObscuringOpacity
        } else {
            DEFAULT_MAX_OBSCURING_OPACITY
        }
        // 阈值 ≥1：系统不限制遮挡（Android 12 以下 / 阈值被放宽），无需降低不透明度
        if (max >= 1f) return 1f
        val margin = if (safetyMargin.isFinite() && safetyMargin > 0f) safetyMargin else 0f
        val target = (max - margin).coerceAtLeast(MIN_TAP_THROUGH_ALPHA)
        val layers = obscuringLayerCount.coerceAtLeast(1)
        if (layers == 1) return target
        // 1-(1-a)^L = target  =>  a = 1 - (1-target)^(1/L)
        val remaining = (1f - target).coerceIn(MIN_TAP_THROUGH_ALPHA, 1f)
        return (1f - remaining.pow(1f / layers)).coerceIn(MIN_TAP_THROUGH_ALPHA, 1f)
    }

    /**
     * 多个窗口在同一触点上的合成遮蔽不透明度：`1 - Π(1 - αᵢ)`。
     *
     * 非法 alpha（NaN/∞/负数）按 0 处理，>1 按 1 处理。
     */
    fun combinedObscuringOpacity(alphas: List<Float>): Float {
        var clear = 1f // Π(1-α)
        for (raw in alphas) {
            val a = when {
                !raw.isFinite() || raw <= 0f -> 0f
                raw >= 1f -> 1f
                else -> raw
            }
            clear *= (1f - a)
        }
        return (1f - clear).coerceIn(0f, 1f)
    }

    /**
     * 这些窗口叠在同一触点上时，触摸是否会被系统判为「不可信」而丢弃。
     *
     * @param alphas 覆盖该触点的所有窗口 alpha（含本应用的多个悬浮窗）
     */
    fun blocksTouchThrough(
        alphas: List<Float>,
        maxObscuringOpacity: Float = DEFAULT_MAX_OBSCURING_OPACITY
    ): Boolean {
        val max = if (maxObscuringOpacity.isFinite() && maxObscuringOpacity > 0f) {
            maxObscuringOpacity
        } else {
            DEFAULT_MAX_OBSCURING_OPACITY
        }
        return combinedObscuringOpacity(alphas) > max + 1e-4f
    }

    /**
     * 决定辅助（气泡/提示）窗口的 y 坐标，尽量不与主窗口重叠。
     *
     * 原因：两个窗口叠在一起时合成 alpha 会超过阈值
     *（0.79 + 0.79 → 0.956 > 0.8），重叠区域从此不再穿透。
     * 优先级：主窗上方 → 主窗下方 → 屏幕顶部（实在放不下才接受重叠）。
     *
     * @param auxHeight 辅助窗口高度（未知时传 0）
     */
    fun resolveAuxiliaryY(mainTop: Int, mainHeight: Int, auxHeight: Int, screenHeight: Int): Int {
        val screen = screenHeight.coerceAtLeast(0)
        if (auxHeight <= 0) return mainTop.coerceIn(0, screen)
        val above = mainTop - auxHeight
        if (above >= 0) return above
        val below = mainTop + mainHeight.coerceAtLeast(0)
        if (below >= 0 && below + auxHeight <= screen) return below
        // 上下都放不下（小屏 + 大窗）：退回顶部，此时必然与主窗重叠，交给上层告警
        return 0
    }

    /** 辅助窗口是否与主窗口的纵向区间相交（用于重叠告警） */
    fun auxiliaryOverlapsMain(mainTop: Int, mainHeight: Int, auxTop: Int, auxHeight: Int): Boolean {
        if (auxHeight <= 0 || mainHeight <= 0) return false
        return auxTop < mainTop + mainHeight && auxTop + auxHeight > mainTop
    }

    /** 纯 Kotlin 矩形（避免 android.graphics.RectF 的 Android 依赖，可 JVM 直测） */
    data class RectFLike(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }
}
