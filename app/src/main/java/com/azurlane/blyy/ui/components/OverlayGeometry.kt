package com.azurlane.blyy.ui.components

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 悬浮窗几何纯函数集（无 Android 依赖，可 JVM 单测）。
 *
 * 从 [com.azurlane.blyy.SecretaryOverlayService] 提取，Service 与单测共用同一实现，
 * 避免"测试一份、生产一份"的漂移。
 */
object OverlayGeometry {

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
