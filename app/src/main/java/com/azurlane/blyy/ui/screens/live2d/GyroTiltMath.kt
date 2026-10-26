package com.azurlane.blyy.ui.screens.live2d

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 陀螺仪注视/视差的倾斜数学核心（纯 Kotlin、无 Android 依赖，可 JVM 单测）。
 *
 * 隐喻：模型是"贴在屏幕玻璃上的存在"，倾斜设备时她的视线与位置朝
 * **屏幕坐标系内的重力方向**轻微偏移（手机右倾 → 看向右下方；顶部后仰 →
 * 看向屏幕下方）。相比"相对中性姿态的偏角"方案，重力响应：
 *  - 无需标定中性位姿（竖屏正对用户天然零位）
 *  - 无参考漂移（game rotation vector 的重力对齐分量长期稳定）
 *  - 任意握持姿态（横屏/斜握）都自动成立
 *
 * 输入是旋转矩阵 R（设备系 → 世界系，row-major 3x3，来自
 * [android.hardware.SensorManager.getRotationMatrixFromVector]，世界系
 * X=东 Y=北 Z=天）。全部信号在世界系直接计算：
 *  - n = R 第三列：屏幕法线（设备 Z 轴）的世界向量
 *  - e = R · e_dev：屏幕内容"右"方向的世界向量（e_dev 按显示旋转选择）
 *  - rawX = -e.z：屏幕系重力的水平分量（右倾 → e 沉 → 为正）
 *  - rawY =  n.z：法线仰角正弦（后仰 → 为正，即重力在屏幕系指向屏幕下方）
 *  - fade：屏幕接近水平（法线接近天顶/地底）时窗口隐喻失效，信号整体衰减
 * 输出 sx/sy ∈ [-1, 1]：视线目标归一化坐标与视差方向。
 */
object GyroTiltMath {

    /** 满偏倾斜角（弧度）：倾斜 30° 即达到 ±1 满偏，超过按比例钳制 */
    const val FULL_DEFLECTION_SIN: Float = 0.5f // sin(30°)

    /** 法线仰角衰减起点/终点（n·天顶 的余弦值）：仰角 >56° 开始衰减，>72° 归零 */
    private const val FADE_START_COS = 0.55f
    private const val FADE_END_COS = 0.95f

    // 显示旋转常量（与 android.view.Surface.ROTATION_* 同值，避免引入 Android 依赖）
    const val ROTATION_0 = 0
    const val ROTATION_90 = 1
    const val ROTATION_180 = 2
    const val ROTATION_270 = 3

    /** 屏幕内容"右"方向在设备自然坐标系下的表达（按显示旋转） */
    fun screenRightInDeviceAxes(rotation: Int): FloatArray = when (rotation) {
        ROTATION_90 -> floatArrayOf(0f, -1f, 0f)   // 逆时针横握：内容右 = 设备 -Y
        ROTATION_180 -> floatArrayOf(-1f, 0f, 0f)
        ROTATION_270 -> floatArrayOf(0f, 1f, 0f)   // 顺时针横握：内容右 = 设备 +Y
        else -> floatArrayOf(1f, 0f, 0f)
    }

    /** 屏幕法线（设备 Z 轴）的世界向量 = R 的第三列（row-major 3x3：m[2]/m[5]/m[8]） */
    fun screenNormalInWorld(matrix: FloatArray): FloatArray =
        floatArrayOf(matrix[2], matrix[5], matrix[8])

    /** 屏幕内容"右"方向的世界向量 = R · e_dev */
    fun screenRightInWorld(matrix: FloatArray, rotation: Int): FloatArray {
        val a = screenRightInDeviceAxes(rotation)
        // world = R · v（row-major）：分量 i = R[i*3+0]*v0 + R[i*3+1]*v1 + R[i*3+2]*v2
        return floatArrayOf(
            matrix[0] * a[0] + matrix[1] * a[1] + matrix[2] * a[2],
            matrix[3] * a[0] + matrix[4] * a[1] + matrix[5] * a[2],
            matrix[6] * a[0] + matrix[7] * a[1] + matrix[8] * a[2]
        )
    }

    /**
     * 计算归一化倾斜信号 (sx, sy) ∈ [-1,1]²。
     *
     * @param matrix 旋转矩阵（getRotationMatrixFromVector 输出的 3x3 row-major）
     * @param rotation 当前显示旋转（Surface.ROTATION_*）
     * @param gain 灵敏度倍率（1.0 = 默认；只缩放未钳制的原始信号）
     */
    fun computeTilt(matrix: FloatArray, rotation: Int, gain: Float): Pair<Float, Float> {
        val n = screenNormalInWorld(matrix)
        val e = screenRightInWorld(matrix, rotation)
        val rawX = -e[2]
        val rawY = n[2]
        // 近水平衰减：法线越接近天顶/地底，重力在屏幕内的方向分量越失真
        val fade = ((FADE_END_COS - abs(n[2])) / (FADE_END_COS - FADE_START_COS)).coerceIn(0f, 1f)
        val sx = (rawX / FULL_DEFLECTION_SIN * gain).coerceIn(-1f, 1f) * fade
        val sy = (rawY / FULL_DEFLECTION_SIN * gain).coerceIn(-1f, 1f) * fade
        return sx to sy
    }

    /** 由 game/rotation vector 事件值构造 3x3 row-major 旋转矩阵（包装 SensorManager 行为，供单测复现） */
    fun rotationMatrixFromQuaternion(x: Float, y: Float, z: Float, w: Float): FloatArray {
        val length = 2f * (x * x + y * y + z * z + w * w)
        val norm = if (length == 0f) 0f else 1f / length
        val xx = x * x * norm * 2f
        val xy = x * y * norm * 2f
        val xz = x * z * norm * 2f
        val xw = x * w * norm * 2f
        val yy = y * y * norm * 2f
        val yz = y * z * norm * 2f
        val yw = y * w * norm * 2f
        val zz = z * z * norm * 2f
        val zw = z * w * norm * 2f
        // 与 SensorManager.getRotationMatrixFromVector 相同的输出布局：
        // world = R · device，R 依行填充
        return floatArrayOf(
            1f - (yy + zz), xy - zw, xz + yw,
            xy + zw, 1f - (xx + zz), yz - xw,
            xz - yw, yz + xw, 1f - (xx + yy)
        )
    }

    /** 绕世界轴 axis（单位向量）旋转 angle 弧度后，设备基的世界表达（测试辅助：构造已知姿态） */
    fun deviceBasisAfterWorldRotation(
        axisX: Float, axisY: Float, axisZ: Float, angle: Float
    ): Triple<FloatArray, FloatArray, FloatArray> {
        val c = cos(angle)
        val s = sin(angle)
        val t = 1f - c
        // Rodrigues 旋转矩阵（列向量被 R_world 作用）
        val r = floatArrayOf(
            t * axisX * axisX + c, t * axisX * axisY - s * axisZ, t * axisX * axisZ + s * axisY,
            t * axisX * axisY + s * axisZ, t * axisY * axisY + c, t * axisY * axisZ - s * axisX,
            t * axisX * axisZ - s * axisY, t * axisY * axisZ + s * axisX, t * axisZ * axisZ + c
        )
        fun apply(v: FloatArray): FloatArray = floatArrayOf(
            r[0] * v[0] + r[1] * v[1] + r[2] * v[2],
            r[3] * v[0] + r[4] * v[1] + r[5] * v[2],
            r[6] * v[0] + r[7] * v[1] + r[8] * v[2]
        )
        // 设备初始姿态：X=世界东(1,0,0)，Y=世界上(0,0,1)，Z=世界北/指向用户(0,1,0)
        return Triple(
            apply(floatArrayOf(1f, 0f, 0f)),
            apply(floatArrayOf(0f, 0f, 1f)),
            apply(floatArrayOf(0f, 1f, 0f))
        )
    }

    /** 把设备基三轴组装为 row-major 3x3 旋转矩阵（world = R · device） */
    fun matrixFromDeviceBasis(
        devX: FloatArray, devY: FloatArray, devZ: FloatArray
    ): FloatArray = floatArrayOf(
        devX[0], devY[0], devZ[0],
        devX[1], devY[1], devZ[1],
        devX[2], devY[2], devZ[2]
    )
}
