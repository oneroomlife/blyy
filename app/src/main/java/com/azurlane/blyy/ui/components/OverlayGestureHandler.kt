package com.azurlane.blyy.ui.components

import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * 悬浮窗静态图分支的 View 级手势处理器。
 *
 * 【为什么在 Service 层而不是 Compose 层】
 * 旧的 Compose pointerInput(detectDragGestures) 方案存在"自参考系漂移"：
 * dragAmount 是**视图本地坐标**增量，而拖动回调会移动**窗口本身**——窗口跟随
 * 手指移动后，指针的本地坐标几乎不变，dragAmount 趋近 0，表现为拖拽极慢/卡死。
 * Spine 分支（SpineSdGlSurfaceView）早在其 OnTouchListener 中用 event.rawX/rawY
 * （屏幕绝对坐标，参考系恒定）修复过同一问题；本处理器把同等修复带给静态图分支。
 *
 * 挂载方式：由 [com.azurlane.blyy.SecretaryOverlayService] 设置在主窗口 ComposeView 上。
 * 事件链路：ComposeView.onTouchListener 在 AndroidComposeView.dispatchTouchEvent
 * 返回 false（无 Compose pointerInput 消费）时收到事件——因此静态图分支的
 * Composable 不再挂任何 pointerInput，本处理器接管全部交互。
 * Spine 分支激活时通过 [interactable] 置 false 放行（返回 false），
 * 事件穿透到内层 GLSurfaceView 自身的 OnTouchListener 处理。
 *
 * 手势语义与 Spine 分支完全一致（对齐 SpineSdGlSurfaceView 的实现）：
 * - 命中区域（[hitRect]）外 → return false 事件穿透到下层 App
 * - 快速拖动（超 touchSlop）或长按（系统长按超时）进入拖动
 * - 抬起/取消结束拖动；未拖动时识别为点击
 *
 * @param windowParamsProvider 读取主窗口 LayoutParams（含窗口位置 lp.x/lp.y），
 *   用于把窗口本地坐标换算到屏幕绝对坐标做命中判断
 * @param onMove 窗口移动增量（px，来自 rawX/rawY 差分，屏幕坐标系）
 * @param onTap 点击（在骨骼/图片矩形内按下且未拖动）
 * @param onDragStateChanged 拖动开始/结束（含取消），Service 据此切换辅助窗口内容并持久化位置
 */
class OverlayGestureHandler(
    private val view: View,
    private val windowParamsProvider: () -> Pair<Int, Int>,
    private val onMove: (dx: Float, dy: Float) -> Unit,
    private val onTap: () -> Unit,
    private val onDragStateChanged: (Boolean) -> Unit
) : View.OnTouchListener {

    /**
     * 可交互矩形（窗口本地坐标系，px）。
     *
     * null = 当前无可交互内容（如 SD 解析中/图片未加载）：完全不消费事件。
     * 由静态图 Composable 通过 onGloballyPositioned + 图片 Fit 矩形上报，
     * Spine 分支激活时同样置 null（事件放行给 GLSurfaceView）。
     */
    @Volatile var hitRect: RectF? = null

    /** Spine 分支激活标志：true 时本处理器放行所有事件（Spine 自己消费） */
    @Volatile var spineActive: Boolean = false

    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragging = false

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (spineActive) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val rect = hitRect ?: return false
                // 触摸点窗口本地坐标 → 屏幕坐标：lp.x/lp.y 是窗口左上角的屏幕位置
                val (winX, winY) = windowParamsProvider()
                val screenX = event.rawX - winX
                val screenY = event.rawY - winY
                // raw 坐标减窗口位置 = 相对窗口的本地坐标（与 event.x 等价，但不受
                // 窗口滚动/变换影响；直接用 event.x 亦可，此处显式换算便于理解）
                val localX = screenX
                val localY = screenY
                if (localX < rect.left || localX > rect.right ||
                    localY < rect.top || localY > rect.bottom
                ) {
                    // 触摸点在图片实际显示矩形外（透明区域）：穿透到下层 App
                    return false
                }
                downRawX = event.rawX; downRawY = event.rawY
                lastRawX = event.rawX; lastRawY = event.rawY
                dragging = false
                // 调度长按检测：超时未移动则进入长按拖动（与 Spine 分支同语义）
                longPressRunnable = Runnable {
                    if (!dragging) enterDragState(longPress = true)
                }
                longPressHandler.postDelayed(longPressRunnable!!, longPressTimeout)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (abs(event.rawX - downRawX) > touchSlop ||
                        abs(event.rawY - downRawY) > touchSlop)
                ) {
                    // 长按超时前检测到移动：取消长按计时，进入快速拖动
                    cancelLongPress()
                    enterDragState(longPress = false)
                }
                if (dragging) {
                    // 【关键】rawX/rawY 屏幕绝对坐标差分：窗口跟随手指移动不影响
                    // 参考系，dx/dy 精确反映手指实际移动量（修复自参考系漂移）
                    onMove(event.rawX - lastRawX, event.rawY - lastRawY)
                }
                lastRawX = event.rawX; lastRawY = event.rawY
            }
            MotionEvent.ACTION_UP -> {
                cancelLongPress()
                if (!dragging) {
                    onTap()
                    v.performClick()
                } else {
                    exitDragState()
                }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                exitDragState()
                dragging = false
            }
        }
        return true
    }

    private fun enterDragState(longPress: Boolean) {
        if (dragging) return
        dragging = true
        view.performHapticFeedback(
            if (longPress) HapticFeedbackConstants.LONG_PRESS
            else HapticFeedbackConstants.VIRTUAL_KEY
        )
        onDragStateChanged(true)
    }

    private fun exitDragState() {
        if (!dragging) return
        onDragStateChanged(false)
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        longPressRunnable = null
    }

    /** 视图销毁时清理 Handler 回调（Service onDestroy 时调用） */
    fun dispose() {
        cancelLongPress()
    }
}
