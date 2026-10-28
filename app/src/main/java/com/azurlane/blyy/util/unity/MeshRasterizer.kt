package com.azurlane.blyy.util.unity

/**
 * 网格 → 位图的软件光栅化器（**纯 Kotlin，不依赖 android.graphics**）。
 *
 * 立绘还原的核心：把 [UnityMesh] 的每个三角形按「顶点坐标 = 画面位置、
 * UV = 图集位置」映射，从图集里采样像素，画到网格包围盒大小的图上。
 * 等价于 Unity 里 `MeshImage` 用这张图和这套网格渲染出来的结果。
 *
 * 设计要点：
 * 1. **输出尺寸 = 网格包围盒**（不是图集尺寸）。图集 1024×556 会被还原成
 *    800×1021 的竖图；原实现直接展示图集，所以看到的是碎片拼贴。
 * 2. **y 轴翻转**：Unity 网格 y 向上，位图行号向下，顶点按
 *    `py = maxY - y` 映射；UV 的 v 同样向上，采样行按 `(1 - v) * texHeight`。
 * 3. **UV 夹紧到三角形自身的 UV 包围盒**：图集里碎片是紧挨着打包的，
 *    双线性采样跨出碎片边界会吸到隔壁碎片的颜色（细看是彩色描边）。
 *    夹紧后尺度 1:1 时采样点正好落在纹素中心，无模糊、无串色。
 * 4. **预乘 alpha 合成**：同一图层内三角形可能重叠（阴影叠在人物上），
 *    按索引顺序做 src-over，与 GPU 的混合顺序一致；最后再还原直通 alpha。
 *
 * 这个类刻意不碰 `Bitmap`，因此可以在 JVM 单元测试里用真实资源包逐像素验证。
 */
internal object MeshRasterizer {

    /** 单次光栅化的像素上限（防异常网格把内存打爆；正常立绘远小于此值） */
    private const val MAX_PIXELS = 64_000_000L

    /** 三角形覆盖判定容差：共享边上的像素两边都算，避免出现 1px 裂缝 */
    private const val EDGE_EPSILON = 0.002f

    class Output(val pixels: IntArray, val width: Int, val height: Int)

    /**
     * @param texture   图集像素（ARGB，直通 alpha；行序**自上而下**，即解码出的 PNG 方向）
     * @param texWidth  图集宽
     * @param texHeight 图集高
     * @param scale     输出像素 / 网格单位（1 = 原始分辨率）
     */
    fun rasterize(
        mesh: UnityMesh,
        texture: IntArray,
        texWidth: Int,
        texHeight: Int,
        scale: Float = 1f
    ): Output? {
        if (!mesh.usable || texWidth <= 0 || texHeight <= 0) return null
        if (texture.size < texWidth * texHeight) return null
        // 索引越界会直接打穿顶点数组（Unity 的脏数据/截断包），先整体校验
        if (!mesh.hasValidIndices()) return null

        val safeScale = if (scale.isFinite() && scale > 0f) scale else 1f
        val width = Math.ceil((mesh.width * safeScale).toDouble()).toInt().coerceAtLeast(1)
        val height = Math.ceil((mesh.height * safeScale).toDouble()).toInt().coerceAtLeast(1)
        if (width.toLong() * height > MAX_PIXELS) return null

        val out = IntArray(width * height)
        val indices = mesh.indices
        val positions = mesh.positions
        val uvs = mesh.uvs
        val originX = mesh.minX
        val originY = mesh.maxY

        // 复用的三角形缓冲，避免每个三角形都分配一堆 FloatArray
        val vx = FloatArray(3)
        val vy = FloatArray(3)
        val tu = FloatArray(3)
        val tv = FloatArray(3)

        var t = 0
        while (t + 2 < indices.size) {
            val i0 = indices[t]
            val i1 = indices[t + 1]
            val i2 = indices[t + 2]
            t += 3

            vx[0] = (positions[i0 * 2] - originX) * safeScale
            vy[0] = (originY - positions[i0 * 2 + 1]) * safeScale
            vx[1] = (positions[i1 * 2] - originX) * safeScale
            vy[1] = (originY - positions[i1 * 2 + 1]) * safeScale
            vx[2] = (positions[i2 * 2] - originX) * safeScale
            vy[2] = (originY - positions[i2 * 2 + 1]) * safeScale

            tu[0] = uvs[i0 * 2] * texWidth
            tv[0] = (1f - uvs[i0 * 2 + 1]) * texHeight
            tu[1] = uvs[i1 * 2] * texWidth
            tv[1] = (1f - uvs[i1 * 2 + 1]) * texHeight
            tu[2] = uvs[i2 * 2] * texWidth
            tv[2] = (1f - uvs[i2 * 2 + 1]) * texHeight

            // 有向面积：0 表示退化三角形（图集里被压成一条线的碎片）
            val area = (vx[1] - vx[0]) * (vy[2] - vy[0]) - (vx[2] - vx[0]) * (vy[1] - vy[0])
            if (area == 0f || !area.isFinite()) continue

            val minPx = maxOf(0, Math.floor(minOf(vx[0], vx[1], vx[2]).toDouble()).toInt())
            val maxPx = minOf(width, Math.ceil(maxOf(vx[0], vx[1], vx[2]).toDouble()).toInt())
            val minPy = maxOf(0, Math.floor(minOf(vy[0], vy[1], vy[2]).toDouble()).toInt())
            val maxPy = minOf(height, Math.ceil(maxOf(vy[0], vy[1], vy[2]).toDouble()).toInt())
            if (maxPx <= minPx || maxPy <= minPy) continue

            // 采样夹紧区间：三角形自身的 UV 包围盒（纹素中心坐标）
            val uMin = minOf(tu[0], tu[1], tu[2])
            val uMax = maxOf(tu[0], tu[1], tu[2])
            val vMin = minOf(tv[0], tv[1], tv[2])
            val vMax = maxOf(tv[0], tv[1], tv[2])
            val clampUMin = if (uMax - uMin >= 1f) uMin + 0.5f else (uMin + uMax) * 0.5f
            val clampUMax = if (uMax - uMin >= 1f) uMax - 0.5f else clampUMin
            val clampVMin = if (vMax - vMin >= 1f) vMin + 0.5f else (vMin + vMax) * 0.5f
            val clampVMax = if (vMax - vMin >= 1f) vMax - 0.5f else clampVMin

            val invArea = 1f / area
            for (py in minPy until maxPy) {
                val sampleY = py + 0.5f
                val rowOffset = py * width
                for (px in minPx until maxPx) {
                    val sampleX = px + 0.5f
                    // 重心坐标
                    val w0 = ((vx[2] - vx[1]) * (sampleY - vy[1]) + (vy[1] - vy[2]) * (sampleX - vx[1])) * invArea
                    val w1 = ((vx[0] - vx[2]) * (sampleY - vy[2]) + (vy[2] - vy[0]) * (sampleX - vx[2])) * invArea
                    val w2 = 1f - w0 - w1
                    if (w0 < -EDGE_EPSILON || w1 < -EDGE_EPSILON || w2 < -EDGE_EPSILON) continue

                    val u = w0 * tu[0] + w1 * tu[1] + w2 * tu[2]
                    val v = w0 * tv[0] + w1 * tv[1] + w2 * tv[2]
                    val color = sample(
                        texture, texWidth, texHeight,
                        u.coerceIn(clampUMin, clampUMax),
                        v.coerceIn(clampVMin, clampVMax)
                    )
                    if (color == 0) continue
                    val at = rowOffset + px
                    out[at] = blendOver(out[at], color)
                }
            }
        }

        unpremultiply(out)
        return Output(out, width, height)
    }

    /**
     * 双线性采样（输入为纹素连续坐标，纹素中心在 `k + 0.5`）。
     *
     * 返回**预乘** ARGB：`A` 在最高字节，`R/G/B` 已乘上 alpha。
     * 采样前对四个纹素分别预乘，透明边缘不会拖出黑边。
     */
    private fun sample(texture: IntArray, texWidth: Int, texHeight: Int, u: Float, v: Float): Int {
        val x = u - 0.5f
        val y = v - 0.5f
        val x0 = Math.floor(x.toDouble()).toInt()
        val y0 = Math.floor(y.toDouble()).toInt()
        val fx = x - x0
        val fy = y - y0
        val cx0 = x0.coerceIn(0, texWidth - 1)
        val cy0 = y0.coerceIn(0, texHeight - 1)

        // 快路径：采样点正好落在纹素中心（尺度 1:1 时占绝大多数），直接取原色
        if (fx == 0f && fy == 0f) {
            return premultiply(texture[cy0 * texWidth + cx0])
        }

        val cx1 = (x0 + 1).coerceIn(0, texWidth - 1)
        val cy1 = (y0 + 1).coerceIn(0, texHeight - 1)
        val row0 = cy0 * texWidth
        val row1 = cy1 * texWidth

        val w00 = (1f - fx) * (1f - fy)
        val w10 = fx * (1f - fy)
        val w01 = (1f - fx) * fy
        val w11 = fx * fy

        var a = 0f
        var r = 0f
        var g = 0f
        var b = 0f

        // 每个纹素先按自身 alpha 预乘再插值：透明边缘不会拖出黑边
        val p00 = texture[row0 + cx0]
        var pa = ((p00 ushr 24) and 0xFF)
        var k = w00 * pa / 255f
        a += pa * w00
        r += ((p00 shr 16) and 0xFF) * k
        g += ((p00 shr 8) and 0xFF) * k
        b += (p00 and 0xFF) * k

        val p10 = texture[row0 + cx1]
        pa = ((p10 ushr 24) and 0xFF)
        k = w10 * pa / 255f
        a += pa * w10
        r += ((p10 shr 16) and 0xFF) * k
        g += ((p10 shr 8) and 0xFF) * k
        b += (p10 and 0xFF) * k

        val p01 = texture[row1 + cx0]
        pa = ((p01 ushr 24) and 0xFF)
        k = w01 * pa / 255f
        a += pa * w01
        r += ((p01 shr 16) and 0xFF) * k
        g += ((p01 shr 8) and 0xFF) * k
        b += (p01 and 0xFF) * k

        val p11 = texture[row1 + cx1]
        pa = ((p11 ushr 24) and 0xFF)
        k = w11 * pa / 255f
        a += pa * w11
        r += ((p11 shr 16) and 0xFF) * k
        g += ((p11 shr 8) and 0xFF) * k
        b += (p11 and 0xFF) * k

        val alpha = a.toInt().coerceIn(0, 255)
        if (alpha == 0) return 0
        return (alpha shl 24) or
            (r.toInt().coerceIn(0, 255) shl 16) or
            (g.toInt().coerceIn(0, 255) shl 8) or
            b.toInt().coerceIn(0, 255)
    }

    /** 直通 ARGB → 预乘 ARGB */
    private fun premultiply(pixel: Int): Int {
        val a = (pixel ushr 24) and 0xFF
        if (a == 0) return 0
        if (a == 255) return pixel
        val r = (((pixel shr 16) and 0xFF) * a / 255)
        val g = (((pixel shr 8) and 0xFF) * a / 255)
        val b = ((pixel and 0xFF) * a / 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 预乘 src-over：`dst = src + dst * (1 - srcA)` */
    private fun blendOver(dst: Int, src: Int): Int {
        val sa = (src ushr 24) and 0xFF
        if (sa == 0) return dst
        if (sa == 255) return src
        val inv = 255 - sa
        val da = (dst ushr 24) and 0xFF
        val outA = sa + da * inv / 255
        val outR = ((src shr 16) and 0xFF) + ((dst shr 16) and 0xFF) * inv / 255
        val outG = ((src shr 8) and 0xFF) + ((dst shr 8) and 0xFF) * inv / 255
        val outB = (src and 0xFF) + (dst and 0xFF) * inv / 255
        return (outA.coerceAtMost(255) shl 24) or
            (outR.coerceAtMost(255) shl 16) or
            (outG.coerceAtMost(255) shl 8) or
            outB.coerceAtMost(255)
    }

    /** 预乘 → 直通 alpha（Bitmap 要求直通；alpha 为 0 的像素保持全透明） */
    private fun unpremultiply(pixels: IntArray) {
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xFF
            if (a == 0) {
                pixels[i] = 0
                continue
            }
            if (a == 255) continue
            val scale = 255f / a
            val r = (((p shr 16) and 0xFF) * scale).toInt().coerceIn(0, 255)
            val g = (((p shr 8) and 0xFF) * scale).toInt().coerceIn(0, 255)
            val b = ((p and 0xFF) * scale).toInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
