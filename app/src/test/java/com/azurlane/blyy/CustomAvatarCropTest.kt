package com.azurlane.blyy

import com.azurlane.blyy.util.CustomAvatarManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 自定义头像裁剪窗口数学（computeCropWindow）纯逻辑单测。
 *
 * v2 语义：预览 = ContentScale.Fit 居中基准 + graphicsLayer 绕框中心 scale
 * 缩放后 offset 平移；裁剪窗口 = 裁剪框反推回原图坐标的矩形，
 * 允许越出原图（越出部分 = 输出黑边），窗口纵横比恒等于裁剪框。
 */
class CustomAvatarCropTest {

    private fun window(srcW: Int, srcH: Int, boxW: Float, boxH: Float, s: Float, ox: Float, oy: Float) =
        CustomAvatarManager.computeCropWindow(srcW, srcH, boxW, boxH, s, ox, oy)

    private fun assertClose(expected: Float, actual: Float, msg: String) {
        assertTrue("$msg: expected=$expected actual=$actual", abs(expected - actual) < 0.01f)
    }

    // ── 3:4 裁剪框（舰娘卡片纵横比 0.75）──

    @Test
    fun `tall artwork at scale1 shows full image with side bands`() {
        // 竖长立绘 600×1200，框 300×400：高度约束 Fit → 整图可见，左右黑边
        val w = window(600, 1200, 300f, 400f, 1f, 0f, 0f)
        assertClose(-150f, w.left, "left")
        assertClose(0f, w.top, "top")
        assertClose(900f, w.width, "width")
        assertClose(1200f, w.height, "height")
    }

    @Test
    fun `wide image at scale1 shows full image with top bottom bands`() {
        val w = window(1200, 600, 300f, 400f, 1f, 0f, 0f)
        assertClose(0f, w.left, "left")
        assertClose(-500f, w.top, "top")
        assertClose(1200f, w.width, "width")
        assertClose(1600f, w.height, "height")
    }

    @Test
    fun `same aspect image at scale1 crops nothing and has no bands`() {
        val w = window(450, 600, 300f, 400f, 1f, 0f, 0f)
        assertClose(0f, w.left, "left")
        assertClose(0f, w.top, "top")
        assertClose(450f, w.width, "width")
        assertClose(600f, w.height, "height")
    }

    @Test
    fun `square image keeps box aspect with bands`() {
        val w = window(800, 800, 300f, 400f, 1f, 0f, 0f)
        assertClose(0f, w.left, "left")
        assertClose(-400f / 3f, w.top, "top")
        assertClose(800f, w.width, "width")
        assertClose(800f * 4f / 3f, w.height, "height")
    }

    @Test
    fun `zoom halves window around center`() {
        val w1 = window(450, 600, 300f, 400f, 1f, 0f, 0f)
        val w2 = window(450, 600, 300f, 400f, 2f, 0f, 0f)
        assertClose(w1.width / 2f, w2.width, "width")
        assertClose(w1.height / 2f, w2.height, "height")
        // 居中保持：窗口中心 = 原图中心
        assertClose(225f, w2.left + w2.width / 2f, "centerX")
        assertClose(300f, w2.top + w2.height / 2f, "centerY")
    }

    @Test
    fun `offset shifts window opposite to pan`() {
        // 450×600 对框 300×400：base = 2/3（同纵横比），scale=2 → actual = 4/3；
        // offsetY=+120（图片下移）→ 窗口中心上移 120/(4/3)=90
        val w = window(450, 600, 300f, 400f, 2f, 0f, 120f)
        assertClose(300f - 90f, w.top + w.height / 2f, "centerY")
        assertClose(225f, w.left + w.width / 2f, "centerX 不受 Y 平移影响")
    }

    @Test
    fun `window aspect always equals box aspect`() {
        val cases = listOf(
            Triple(1234, 777, 1.7f), Triple(30, 41, 1f), Triple(2000, 900, 3.3f)
        )
        for ((sw, sh, s) in cases) {
            val w = window(sw, sh, 300f, 400f, s, 55f, -40f)
            assertClose(
                0.75f, w.width / w.height,
                "窗口纵横比 ($sw x $sh, s=$s)"
            )
        }
    }

    @Test
    fun `tiny image gets hairline bands instead of aspect loss`() {
        // 旧版此处会钳制成 30×40（丢纵横比，卡片 Crop 再裁 → 选区≠所见）；
        // v2 窗口保持 3:4，左右 ~0.4px 黑边
        val w = window(30, 41, 300f, 400f, 1f, 0f, 0f)
        assertClose(30.75f, w.width, "width")
        assertClose(41f, w.height, "height")
        assertClose(0.75f, w.width / w.height, "aspect")
    }
}
