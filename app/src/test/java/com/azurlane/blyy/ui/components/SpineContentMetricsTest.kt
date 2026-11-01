package com.azurlane.blyy.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SpineContentMetrics] 纯几何逻辑单测。
 *
 * 覆盖「SD 小人可见内容度量」的关键不变量：
 * - 并集/面积/中心计算正确
 * - 离群修剪保留主体、丢弃远处碎屑（武器光效/边界框占位层）
 * - 凸四边形与三角形命中判定符合点击语义
 */
class SpineContentMetricsTest {

    private fun box(left: Float, bottom: Float, right: Float, top: Float) =
        ContentBox(left = left, bottom = bottom, right = right, top = top)

    @Test
    fun `contentBox basic metrics`() {
        val b = box(10f, 20f, 110f, 220f)
        assertEquals(100f, b.width, 0.001f)
        assertEquals(200f, b.height, 0.001f)
        assertEquals(60f, b.centerX, 0.001f)
        assertEquals(120f, b.centerY, 0.001f)
        assertEquals(0.5f, b.aspect, 0.001f)
        assertTrue(b.isUsable)
    }

    @Test
    fun `contentBox union covers both`() {
        val a = box(0f, 0f, 10f, 10f)
        val b = box(20f, -5f, 30f, 5f)
        val u = a.union(b)
        assertEquals(0f, u.left, 0.001f)
        assertEquals(-5f, u.bottom, 0.001f)
        assertEquals(30f, u.right, 0.001f)
        assertEquals(10f, u.top, 0.001f)
    }

    @Test
    fun `contentBox of returns null for empty and skips degenerate`() {
        assertNull(ContentBox.of(emptyList()))
        assertNull(ContentBox.of(listOf(box(0f, 0f, 0f, 0f))))
        val merged = ContentBox.of(listOf(box(0f, 0f, 10f, 10f), box(0f, 0f, 0f, 0f)))
        assertNotNull(merged)
        assertEquals(10f, merged!!.width, 0.001f)
    }

    @Test
    fun `trimmer drops far small outlier`() {
        // 主体：100×200；离群：8×8 在很远处（模拟特效/武器碎屑）
        val body = WeightedPart(box(-50f, 0f, 50f, 200f), weight = 100f * 200f, slot = 0)
        val outlier = WeightedPart(box(400f, 400f, 408f, 408f), weight = 8f * 8f, slot = 1)
        val trimmed = ContentBoxTrimmer.trim(listOf(body, outlier))
        assertNotNull(trimmed)
        assertEquals(-50f, trimmed!!.left, 0.5f)
        assertEquals(50f, trimmed.right, 0.5f)
        assertEquals(0f, trimmed.bottom, 0.5f)
        assertEquals(200f, trimmed.top, 0.5f)
    }

    @Test
    fun `trimmer keeps large part far from center`() {
        // 大权重部件（披风/翅膀）即使离质心较远也必须保留
        val body = WeightedPart(box(0f, 0f, 60f, 200f), weight = 60f * 200f, slot = 0)
        val wing = WeightedPart(box(120f, 100f, 200f, 180f), weight = 80f * 80f, slot = 1)
        val trimmed = ContentBoxTrimmer.trim(listOf(body, wing))
        assertNotNull(trimmed)
        assertEquals(200f, trimmed!!.right, 0.5f)
    }

    @Test
    fun `trimmer returns null for empty input`() {
        assertNull(ContentBoxTrimmer.trim(emptyList()))
    }

    @Test
    fun `region alpha sub rect flags`() {
        // 全量 UV 也是"可用子矩形"，只是范围等于整张贴图
        assertTrue(RegionAlpha.FULL.hasUsableSubRect)
        assertTrue(RegionAlpha(0.5f).hasUsableSubRect)
        assertTrue(RegionAlpha(1f, 0.1f, 0.9f, 0.1f, 0.9f).hasUsableSubRect)
        // 退化（宽或高为 0）时不可用 → 调用方退回顶点包围盒
        assertFalse(RegionAlpha(1f, 0.5f, 0.5f, 0f, 1f).hasUsableSubRect)
        assertFalse(RegionAlpha(1f, 0f, 1f, 0.5f, 0.5f).hasUsableSubRect)
    }

    @Test
    fun `hit test - point in convex quad`() {
        // 单位正方形（BL, BR, UR, UL 顺序）
        val quad = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        assertTrue(SpineHitTest.pointInConvexQuad(quad, 5f, 5f))
        assertFalse(SpineHitTest.pointInConvexQuad(quad, 15f, 5f))
        assertFalse(SpineHitTest.pointInConvexQuad(quad, -1f, 5f))
    }

    @Test
    fun `hit test - point in triangle`() {
        assertTrue(SpineHitTest.pointInTriangle(1f, 1f, 0f, 0f, 4f, 0f, 0f, 4f))
        assertFalse(SpineHitTest.pointInTriangle(3f, 3f, 0f, 0f, 4f, 0f, 0f, 4f))
    }
}
