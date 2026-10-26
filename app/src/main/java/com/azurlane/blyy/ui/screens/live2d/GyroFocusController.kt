package com.azurlane.blyy.ui.screens.live2d

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import android.os.Handler
import android.os.Looper

/**
 * Live2D 查看器陀螺仪注视控制器：监听设备方向，把"屏幕系重力倾斜"归一化信号
 * 通过回调送到宿主（宿主经 evaluateJavascript 下发给 WebView 渲染端）。
 *
 * 设计要点：
 *  - 优先 TYPE_GAME_ROTATION_VECTOR（无磁力计参与，室内无地磁抖动、 yaw 漂移不影响
 *    重力对齐分量），设备不支持时回退 TYPE_ROTATION_VECTOR
 *  - SENSOR_DELAY_UI（~16Hz）：渲染端有指数平滑补间，低采样率观感无损且省电
 *  - 事件回调注册在主线程 Handler：宿主回调里可直接 evaluateJavascript
 *  - 幅值变化 < [SEND_EPSILON] 不重复发送，静止姿态下零 JS 调用
 *  - 全部无传感器可用时 [start] 静默 no-op，查看器回退到指针视线跟随
 */
class GyroFocusController(
    context: Context,
    private val onTilt: (x: Float, y: Float) -> Unit
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val matrix = FloatArray(9)
    private var running = false
    private var hasSent = false
    private var lastSentX = 0f
    private var lastSentY = 0f

    /** 两次发送之间的最小幅值变化（归一化信号），低于该值视为静止 */
    private val sendEpsilon = 0.004f

    /** 是否具备运行条件（设备至少有一种旋转矢量传感器） */
    fun isAvailable(): Boolean = pickSensor() != null

    /** 注册监听（幂等）。无可用传感器时不做任何事 */
    fun start() {
        if (running) return
        val sensor = pickSensor() ?: return
        running = sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI, mainHandler) == true
        hasSent = false
    }

    /** 注销监听（幂等） */
    fun stop() {
        if (!running) return
        sensorManager?.unregisterListener(this)
        running = false
    }

    private fun pickSensor(): Sensor? {
        val manager = sensorManager ?: return null
        return manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }

    /** 当前显示旋转（横竖屏/反向各自的屏幕基向量不同） */
    private fun currentDisplayRotation(): Int =
        displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> Unit
            else -> return
        }
        // 旋转矢量 values 长度可为 3/4/5，SensorManager 内部自行处理
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        val (sx, sy) = GyroTiltMath.computeTilt(
            matrix, currentDisplayRotation(), /* gain = */ 1f
        )
        if (hasSent &&
            kotlin.math.abs(sx - lastSentX) < sendEpsilon &&
            kotlin.math.abs(sy - lastSentY) < sendEpsilon
        ) {
            return
        }
        hasSent = true
        lastSentX = sx
        lastSentY = sy
        onTilt(sx, sy)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
