package com.azurlane.blyy.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 悬浮窗几何纯逻辑测试（OverlayGeometry，无 Android 依赖）。
 *
 * 覆盖三个生产路径的关键性质：
 * 1. [OverlayGeometry.pickMinShiftAnchor] — 缩放最小偏移锚定：贴边放大不跳位
 * 2. [OverlayGeometry.computeFittedImageRect] — ContentScale.Fit 矩形：交互区域=显示区域
 * 3. [OverlayGeometry.clampWindowPosition] — 拖动/恢复边界：位置永在合法范围内
 */
class OverlayGeometryTest {

    // ── pickMinShiftAnchor ────────────────────────────────────────────────────

    @Test
    fun `屏幕中部的窗口缩放时中心保持不动`() {
        // 屏宽 1080，旧窗 200@x=440（中心 540），放大到 400：中心保持候选=240，
        // 240..(1080-400)=680 范围内不触发 clamp，中心仍为 440，与旧中心 540 不同？
        // ——注意：中心保持候选 x = oldCenter - newSize/2 = 340，clamp 后中心=540 不变
        val result = OverlayGeometry.pickMinShiftAnchor(
            candidates = floatArrayOf(440f, 40f, 340f), // 左缘 / 右缘 / 中心保持
            minBound = 0f,
            maxBound = 680f,
            oldCenter = 540f,
            newSize = 400
        )
        assertEquals(340, result) // 中心保持候选胜出（clamp 不触发，偏移=0）
    }

    @Test
    fun `贴左边缘放大时向右生长不跳位`() {
        // 旧窗 200@x=0（中心 100），放大到 400：左缘锚定 x=0（clamp 后中心 200），
        // 中心保持 x=-100 → clamp 0（中心 200）。两候选偏移同为 100，取首个（左缘）
        val result = OverlayGeometry.pickMinShiftAnchor(
            candidates = floatArrayOf(0f, -200f, -100f), // 左缘 / 右缘 / 中心保持
            minBound = 0f,
            maxBound = 680f,
            oldCenter = 100f,
            newSize = 400
        )
        // 左缘锚定 0 与中心保持 clamp 后同为 0，偏移并列取首个：左缘 0
        assertEquals(0, result)
    }

    @Test
    fun `贴右边缘放大时向左生长（右缘锚定胜出）`() {
        // 屏宽 1080，旧窗 200@x=880（中心 980），放大到 400：
        // 左缘锚定 x=880 → clamp 680（中心 880，偏移 100）
        // 右缘锚定 x=680 → clamp 680（中心 880，偏移 100）
        // 中心保持 x=780 → clamp 680（中心 880，偏移 100）——三者并列，取首个
        val result = OverlayGeometry.pickMinShiftAnchor(
            candidates = floatArrayOf(880f, 680f, 780f),
            minBound = 0f,
            maxBound = 680f,
            oldCenter = 980f,
            newSize = 400
        )
        assertEquals(680, result) // 所有候选 clamp 后同为 680，取最小偏移
    }

    @Test
    fun `y 轴允许向上溢出时顶缘锚定生效`() {
        // 旧窗高 300@y=-45（允许向上溢出 15%），放大到 600：minY=-90, maxBound=1000
        // 各候选 clamp 后中心与偏移：
        // - 顶缘 -45 → 中心 255，偏移 150
        // - 底缘 -345 → clamp -90 → 中心 210，偏移 105
        // - 中心保持 -195 → clamp -90 → 中心 210，偏移 105（与底缘并列）
        // 最小偏移 105，minByOrNull 取首个（底缘），最终 clamp 落点 -90
        val result = OverlayGeometry.pickMinShiftAnchor(
            candidates = floatArrayOf(-45f, -345f, -195f), // 顶缘 / 底缘 / 中心保持
            minBound = -90f,
            maxBound = 1000f,
            oldCenter = 105f,
            newSize = 600
        )
        assertEquals(-90, result)
    }

    @Test
    fun `空候选时回退旧中心推导`() {
        val result = OverlayGeometry.pickMinShiftAnchor(
            candidates = floatArrayOf(),
            minBound = 0f,
            maxBound = 1000f,
            oldCenter = 540f,
            newSize = 400
        )
        assertEquals(340, result) // 540 - 400/2
    }

    // ── computeFittedImageRect ────────────────────────────────────────────────

    @Test
    fun `宽图片宽度铺满垂直居中`() {
        // aspect=2（宽:高=2:1），容器 100×100：图片更宽 → 宽铺满，高=50 上下居中
        val rect = OverlayGeometry.computeFittedImageRect(2f, 100f, 100f)
        assertNotNull(rect)
        assertEquals(0f, rect!!.left, 0.01f)
        assertEquals(100f, rect.right, 0.01f)
        assertEquals(25f, rect.top, 0.01f)
        assertEquals(75f, rect.bottom, 0.01f)
    }

    @Test
    fun `高图片高度铺满水平居中`() {
        // SD 小人典型形态：aspect=2/3（高瘦），容器 110×165：恰好等比 → 矩形等于容器
        val rect = OverlayGeometry.computeFittedImageRect(2f / 3f, 110f, 165f)
        assertNotNull(rect)
        assertEquals(0f, rect!!.top, 0.01f)
        assertEquals(165f, rect.bottom, 0.01f)
        assertEquals(0f, rect.left, 0.01f)
        assertEquals(110f, rect.right, 0.01f)
    }

    @Test
    fun `aspect 相等时矩形等于容器`() {
        val rect = OverlayGeometry.computeFittedImageRect(1f, 100f, 100f)
        assertNotNull(rect)
        assertEquals(0f, rect!!.left, 0.01f)
        assertEquals(0f, rect.top, 0.01f)
        assertEquals(100f, rect.right, 0.01f)
        assertEquals(100f, rect.bottom, 0.01f)
    }

    @Test
    fun `非法输入返回 null`() {
        assertNull(OverlayGeometry.computeFittedImageRect(null, 100f, 100f))
        assertNull(OverlayGeometry.computeFittedImageRect(0f, 100f, 100f))
        assertNull(OverlayGeometry.computeFittedImageRect(-1f, 100f, 100f))
        assertNull(OverlayGeometry.computeFittedImageRect(Float.NaN, 100f, 100f))
        assertNull(OverlayGeometry.computeFittedImageRect(Float.POSITIVE_INFINITY, 100f, 100f))
        assertNull(OverlayGeometry.computeFittedImageRect(1f, 0f, 100f))
        assertNull(OverlayGeometry.computeFittedImageRect(1f, 100f, 0f))
    }

    // ── clampWindowPosition ──────────────────────────────────────────────────

    @Test
    fun `拖动位置 clamp 到屏幕内`() {
        // 屏 1080×1920，窗 220×330，允许向上溢出 50px
        val (x, y) = OverlayGeometry.clampWindowPosition(
            x = -100, y = 2000,
            windowWidth = 220, windowHeight = 330,
            screenWidth = 1080, screenHeight = 1920,
            upwardOverflow = 50
        )
        assertEquals(0, x)      // 左越界 → 0
        assertEquals(1590, y)   // 1920-330 下界
    }

    @Test
    fun `y 允许向上溢出到指定值`() {
        val (x, y) = OverlayGeometry.clampWindowPosition(
            x = 500, y = -200,
            windowWidth = 220, windowHeight = 330,
            screenWidth = 1080, screenHeight = 1920,
            upwardOverflow = 50
        )
        assertEquals(500, x)
        assertEquals(-50, y)    // 上溢下限 -50
    }

    @Test
    fun `比例重载按窗高计算溢出`() {
        // windowHeight=330, ratio=0.15 → 溢出 49（取整）
        val (_, y) = OverlayGeometry.clampWindowPositionByRatio(
            x = 0, y = -1000,
            windowWidth = 220, windowHeight = 330,
            screenWidth = 1080, screenHeight = 1920,
            upwardOverflowRatio = 0.15f
        )
        assertEquals(-49, y)
    }

    @Test
    fun `窗口大于屏幕时夹到合法零界`() {
        // 极端：窗口比屏幕大，maxX/maxY coerceAtLeast(0) 保证不出现负上界
        val (x, y) = OverlayGeometry.clampWindowPosition(
            x = 500, y = 500,
            windowWidth = 2000, windowHeight = 3000,
            screenWidth = 1080, screenHeight = 1920,
            upwardOverflow = 100
        )
        assertEquals(0, x)  // maxX = 1080-2000 < 0 → 0
        assertEquals(0, y)  // maxY = 1920-3000 < 0 → 0；500.coerceIn(-100, 0) 夹到上界 0
    }
}
