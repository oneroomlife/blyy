package com.azurlane.blyy.ui.screens.live2d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [GyroTiltMath] 纯逻辑单测：屏幕系重力响应信号的正确性。
 *
 * 姿态约定（测试辅助构造）：世界系 X=东 Y=北(指向用户) Z=天；
 * 设备初始姿态 X=东、Y=上、Z=指向用户（竖屏正对用户 = 零位）。
 */
class GyroTiltMathTest {

    private fun assertTilt(expectedX: Float, expectedY: Float, actual: Pair<Float, Float>, eps: Float = 0.02f) {
        assertTrue("sx expected=$expectedX actual=${actual.first}", abs(expectedX - actual.first) < eps)
        assertTrue("sy expected=$expectedY actual=${actual.second}", abs(expectedY - actual.second) < eps)
    }

    /** 竖屏正对用户（零位）的旋转矩阵 */
    private fun uprightMatrix(): FloatArray = GyroTiltMath.matrixFromDeviceBasis(
        floatArrayOf(1f, 0f, 0f),
        floatArrayOf(0f, 0f, 1f),
        floatArrayOf(0f, 1f, 0f)
    )

    /** 绕世界轴旋转初始姿态 angle 弧度后的矩阵（axisY 指向用户为视线轴 roll） */
    private fun rotatedMatrix(axisX: Float, axisY: Float, axisZ: Float, angle: Float): FloatArray {
        val (dx, dy, dz) = GyroTiltMath.deviceBasisAfterWorldRotation(axisX, axisY, axisZ, angle)
        return GyroTiltMath.matrixFromDeviceBasis(dx, dy, dz)
    }

    @Test
    fun `upright facing user is zero position`() {
        assertTilt(0f, 0f, GyroTiltMath.computeTilt(uprightMatrix(), GyroTiltMath.ROTATION_0, 1f))
    }

    @Test
    fun `roll right 30 degrees deflects fully to the right`() {
        // 绕视线轴（世界 +Y，指向用户）+30° = 顶向右倒
        val m = rotatedMatrix(0f, 1f, 0f, Math.toRadians(30.0).toFloat())
        assertTilt(1f, 0f, GyroTiltMath.computeTilt(m, GyroTiltMath.ROTATION_0, 1f))
    }

    @Test
    fun `roll left 30 degrees deflects fully to the left`() {
        val m = rotatedMatrix(0f, 1f, 0f, Math.toRadians(-30.0).toFloat())
        assertTilt(-1f, 0f, GyroTiltMath.computeTilt(m, GyroTiltMath.ROTATION_0, 1f))
    }

    @Test
    fun `pitch back 30 degrees deflects down with zero lateral signal`() {
        // 绕世界 +X（东）+30° = 顶部远离用户后仰，法线抬起
        val m = rotatedMatrix(1f, 0f, 0f, Math.toRadians(30.0).toFloat())
        assertTilt(0f, 1f, GyroTiltMath.computeTilt(m, GyroTiltMath.ROTATION_0, 1f))
    }

    @Test
    fun `pitch forward 30 degrees deflects up`() {
        val m = rotatedMatrix(1f, 0f, 0f, Math.toRadians(-30.0).toFloat())
        assertTilt(0f, -1f, GyroTiltMath.computeTilt(m, GyroTiltMath.ROTATION_0, 1f))
    }

    @Test
    fun `flat on table fades signal to zero`() {
        // 后仰 90° = 平放屏幕朝上，法线指向天顶 → fade 归零
        val m = rotatedMatrix(1f, 0f, 0f, Math.toRadians(90.0).toFloat())
        val (sx, sy) = GyroTiltMath.computeTilt(m, GyroTiltMath.ROTATION_0, 1f)
        assertTrue("平放应衰减到 0，实际 sx=$sx sy=$sy", abs(sx) < 0.02f && abs(sy) < 0.02f)
    }

    @Test
    fun `small tilt is proportional below full deflection`() {
        val m = rotatedMatrix(0f, 1f, 0f, Math.toRadians(15.0).toFloat())
        assertTilt(0.5f, 0f, GyroTiltMath.computeTilt(m, GyroTiltMath.ROTATION_0, 1f))
    }

    @Test
    fun `gain scales signal linearly`() {
        val m = rotatedMatrix(0f, 1f, 0f, Math.toRadians(15.0).toFloat())
        // 15° 基准 0.5 × gain 2 = 1.0
        assertTilt(1f, 0f, GyroTiltMath.computeTilt(m, GyroTiltMath.ROTATION_0, 2f))
        // 15° 基准 0.5 × gain 0.5 = 0.25
        assertTilt(0.25f, 0f, GyroTiltMath.computeTilt(m, GyroTiltMath.ROTATION_0, 0.5f))
    }

    @Test
    fun `landscape rotation has its own zero position facing user`() {
        // 横握（顶朝用户左）屏幕正对用户：设备 Y=西(-X)，Z=指向用户，X=下
        val m = GyroTiltMath.matrixFromDeviceBasis(
            floatArrayOf(0f, 0f, -1f),
            floatArrayOf(-1f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f)
        )
        assertTilt(0f, 0f, GyroTiltMath.computeTilt(m, GyroTiltMath.ROTATION_90, 1f))
    }

    @Test
    fun `landscape roll uses content-right axis not device axis`() {
        // 横握（顶朝用户左）+ 绕视线轴（世界 +Y）roll 30°（内容向右倒），手工构造基：
        // 内容右 = 设备 -Y 方向的世界向量应沉入地下（z<0）→ 屏幕系重力偏向内容右
        val c = Math.toRadians(30.0).toFloat()
        val devY = floatArrayOf(-kotlin.math.cos(c), 0f, kotlin.math.sin(c)) // 顶：西偏上
        val devZ = floatArrayOf(0f, 1f, 0f)                                  // 法线仍指向用户
        val devX = cross(devY, devZ)                                          // 正交基补全
        val m = GyroTiltMath.matrixFromDeviceBasis(devX, devY, devZ)
        val e = GyroTiltMath.screenRightInWorld(m, GyroTiltMath.ROTATION_90)
        assertTrue("内容右应下沉，实际 z=${e[2]}", e[2] < -0.3f)
        val (sx, sy) = GyroTiltMath.computeTilt(m, GyroTiltMath.ROTATION_90, 1f)
        assertTrue("横屏 roll 应产生强信号，实际 sx=$sx", sx > 0.5f)
        assertTrue("视线轴 roll 不应有纵向信号，实际 sy=$sy", abs(sy) < 0.02f)
    }

    /** 叉积（测试辅助：由两轴补全正交基第三轴） */
    private fun cross(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )

    @Test
    fun `quaternion matrix reconstruction matches sensor layout`() {
        // 单位四元数（无旋转）→ 单位阵
        val m = GyroTiltMath.rotationMatrixFromQuaternion(0f, 0f, 0f, 1f)
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        for (i in 0 until 9) assertEquals(identity[i], m[i], 0.001f)
    }
}
