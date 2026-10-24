package com.azurlane.blyy

import com.azurlane.blyy.util.CustomAvatarManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 自定义头像裁剪数学（computeCropRect）纯逻辑单测。
 *
 * 预览层语义：图片 ContentScale.Crop 填满裁剪框（baseScale = max 两轴比例），
 * 用户叠加 scale 缩放与 offset 平移（图片中心相对框中心的偏移）。
 */
class CustomAvatarCropTest {

    private fun rect(vararg p: Int) =
        intArrayOf(p[0], p[1], p[2], p[3])

    private fun assertRect(expected: IntArray, actual: CustomAvatarManager.CropRect) {
        assertEquals(
            "left,top,w,h",
            expected.joinToString(),
            rect(actual.left, actual.top, actual.width, actual.height).joinToString()
        )
    }

    // ── 3:4 裁剪框（舰娘卡片纵横比 0.75）──

    @Test
    fun `3x4 box on square source scale1 centers full width`() {
        // 600×600 原图，框 300×400：Crop 由高度约束 → 裁剪区域 450×600（宽反推），水平居中
        val r = CustomAvatarManager.computeCropRect(600, 600, 300f, 400f, 1f, 0f, 0f)
        assertRect(rect(75, 0, 450, 600), r)
    }

    @Test
    fun `3x4 box on 3x4 source scale1 crops nothing`() {
        // 原图与裁剪框同比例：baseScale 两轴同时约束，裁剪区域 = 整图
        val r = CustomAvatarManager.computeCropRect(450, 600, 300f, 400f, 1f, 0f, 0f)
        assertRect(rect(0, 0, 450, 600), r)
    }

    @Test
    fun `3x4 box on wide source scale1 centers horizontally`() {
        // 1200×600 宽图：高度约束 → 裁剪 450×600，水平居中
        val r = CustomAvatarManager.computeCropRect(1200, 600, 300f, 400f, 1f, 0f, 0f)
        assertRect(rect(375, 0, 450, 600), r)
    }

    @Test
    fun `offset shifts crop rect and clamps at borders`() {
        // 1200×600 宽图，水平平移：offsetX=+120（图片右移）→ 裁剪窗口左移 180px（120/actualScale=120/(400/600)=180）
        val actualScale = 400f / 600f
        val r = CustomAvatarManager.computeCropRect(1200, 600, 300f, 400f, 1f, 120f, 0f)
        assertEquals((120f / actualScale).toInt(), 375 - r.left)
        // 超界平移被 clamp：offsetX 巨大 → left=0
        val rClamped = CustomAvatarManager.computeCropRect(1200, 600, 300f, 400f, 1f, 100000f, 0f)
        assertEquals(0, rClamped.left)
        val rClampedNeg = CustomAvatarManager.computeCropRect(1200, 600, 300f, 400f, 1f, -100000f, 0f)
        assertEquals(1200 - rClampedNeg.width, rClampedNeg.left)
    }

    @Test
    fun `zoom halves crop region around center`() {
        // scale=2：裁剪区域两维减半，仍居中
        val r1 = CustomAvatarManager.computeCropRect(450, 600, 300f, 400f, 1f, 0f, 0f)
        val r2 = CustomAvatarManager.computeCropRect(450, 600, 300f, 400f, 2f, 0f, 0f)
        assertEquals(r1.width / 2, r2.width)
        assertEquals(r1.height / 2, r2.height)
        assertEquals(450 / 2 - r2.width / 2, r2.left)
        assertEquals(600 / 2 - r2.height / 2, r2.top)
    }

    @Test
    fun `square box on square source crops full image`() {
        // 图标裁剪（1:1）兼容路径：与 AppIconManager 方形数学一致
        val r = CustomAvatarManager.computeCropRect(800, 800, 400f, 400f, 1f, 0f, 0f)
        assertRect(rect(0, 0, 800, 800), r)
    }

    @Test
    fun `tiny and odd-sized sources stay in bounds`() {
        // 小图：baseScale<1 时 Crop 放大铺满，约束维（宽）贴满，另一维按框比例反推（40≠41）
        val r = CustomAvatarManager.computeCropRect(30, 41, 300f, 400f, 1f, 0f, 0f)
        assertEquals(30, r.width)
        assertEquals(40, r.height)
        assertTrue(r.left >= 0 && r.top >= 0 && r.left + r.width <= 30 && r.top + r.height <= 41)
        // 非整除尺寸：裁剪区域不越界
        val r2 = CustomAvatarManager.computeCropRect(301, 601, 300f, 400f, 1.5f, 33f, -40f)
        assertTrue(r2.left >= 0 && r2.top >= 0)
        assertTrue(r2.left + r2.width <= 301)
        assertTrue(r2.top + r2.height <= 601)
    }

    @Test
    fun `aspect ratio of crop region matches box`() {
        val r = CustomAvatarManager.computeCropRect(1234, 777, 300f, 400f, 1.7f, 55f, -40f)
        val boxRatio = 300f / 400f
        val cropRatio = r.width.toFloat() / r.height.toFloat()
        assertTrue("裁剪区域纵横比应等于裁剪框（${r.width}x${r.height}）", abs(boxRatio - cropRatio) < 0.01f)
    }
}
