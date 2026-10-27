package com.azurlane.blyy.util.unity

/**
 * 位图像素级小工具（纯 int 数组操作，不涉及 Bitmap 可变性）。
 *
 * 只在 CPU 侧的 `IntArray` 上改像素，再一次性 `Bitmap.createBitmap`：
 * 解码器返回的 Bitmap 是不可变的，`setPixels` 会直接抛异常；而先拷贝一张
 * 4096² 的可变位图又要多占 64MB —— 在数组阶段处理最省内存。
 */
internal object BitmapPixelOps {

    /**
     * 把若干矩形区域原地旋转 180°。
     *
     * @param pixels ARGB 像素（行优先，自上而下）
     * @param canvasWidth 像素数组宽度
     * @param canvasHeight 像素数组高度
     * @param rects 需要翻转的矩形（按 [srcWidth]/[srcHeight] 描述的原始坐标）
     * @param scaleX 原始坐标 → 像素数组的横向缩放
     * @param scaleY 原始坐标 → 像素数组的纵向缩放
     */
    fun rotate180(
        pixels: IntArray,
        canvasWidth: Int,
        canvasHeight: Int,
        rects: List<PixelRect>,
        scaleX: Float,
        scaleY: Float
    ) {
        if (rects.isEmpty() || canvasWidth <= 0 || canvasHeight <= 0) return
        if (pixels.size < canvasWidth * canvasHeight) return
        for (rect in rects) {
            val left = (rect.x * scaleX).toInt().coerceIn(0, canvasWidth - 1)
            val top = (rect.y * scaleY).toInt().coerceIn(0, canvasHeight - 1)
            val right = ((rect.x + rect.width) * scaleX).toInt().coerceIn(left + 1, canvasWidth)
            val bottom = ((rect.y + rect.height) * scaleY).toInt().coerceIn(top + 1, canvasHeight)
            val w = right - left
            val h = bottom - top
            if (w <= 1 || h <= 1) continue
            // 逐行首尾交换：外圈两行互换 → 再行内倒序，等价于 180° 旋转
            for (row in 0 until h / 2) {
                val topRow = (top + row) * canvasWidth + left
                val bottomRow = (top + h - 1 - row) * canvasWidth + left
                for (col in 0 until w) {
                    val a = topRow + col
                    val b = bottomRow + (w - 1 - col)
                    val tmp = pixels[a]
                    pixels[a] = pixels[b]
                    pixels[b] = tmp
                }
            }
            if (h % 2 == 1) {
                // 奇数行：中间那一行自身倒序
                val midRow = (top + h / 2) * canvasWidth + left
                var i = 0
                var j = w - 1
                while (i < j) {
                    val tmp = pixels[midRow + i]
                    pixels[midRow + i] = pixels[midRow + j]
                    pixels[midRow + j] = tmp
                    i++
                    j--
                }
            }
        }
    }
}
