package com.azurlane.blyy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.azurlane.blyy.MainActivity
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import com.azurlane.blyy.ui.components.SecretaryChibiOverlay
import com.azurlane.blyy.ui.components.SecretaryOverlayAuxiliaryContent
import com.azurlane.blyy.ui.theme.BlyyTheme
import com.azurlane.blyy.viewmodel.SecretaryManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 秘书舰悬浮窗服务（双窗口架构）。
 *
 * 【优化前】单一 220×365dp 大窗口容纳立绘(110×165dp) + 气泡(220×200dp)，
 *   透明区域被 WindowManager 吞掉触摸事件，导致用户无法操作下方 App。
 *
 * 【优化后】双窗口架构：
 * 1. 主窗口（[composeView]）：尺寸 = baseSize × displayScale，仅含 SpineSdView。
 *    触摸区域紧贴小人实际显示范围，无透明区域拦截问题。
 *    sdScale 变化时动态调整尺寸并保持小人中心位置不变。
 *
 * 2. 辅助窗口（[auxComposeView]）：WRAP_CONTENT，紧贴主窗口上方。
 *    - 非拖动且有对话 → 显示气泡
 *    - 拖动中 → 显示"拖动调整位置"提示
 *    - 其他 → 移除窗口
 *    辅助窗口独立于主窗口，不拦截主窗口的触摸事件，
 *    其自身也不消费触摸（FLAG_NOT_TOUCHABLE 透传所有事件到下层）。
 *
 * 窗口位置同步：主窗口移动/缩放时，[updateAuxiliaryPosition] 同步更新辅助窗口位置。
 */
@AndroidEntryPoint
class SecretaryOverlayService : Service() {

    @Inject
    lateinit var playerSettings: PlayerSettingsDataStore

    @Inject
    lateinit var secretaryManager: SecretaryManager

    private var windowManager: WindowManager? = null

    // ── 主窗口（立绘）──
    private var composeView: ComposeView? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null

    // ── 辅助窗口（气泡/拖动提示）──
    private var auxComposeView: ComposeView? = null
    private var auxLayoutParams: WindowManager.LayoutParams? = null
    /** 辅助窗口实际尺寸（px），由 onGloballyPositioned 测量，用于位置计算 */
    private var auxWidthPx: Int = 0
    private var auxHeightPx: Int = 0

    private var lifecycleOwner: FloatingWindowLifecycleOwner? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    companion object {
        private const val TAG = "SecretaryOverlay"
        private const val ANIMATION_DURATION_MS = 300

        /** SD 小人基础宽度（dp），与 SecretaryChibiOverlay 的 CHIBI_BASE_WIDTH 一致 */
        private const val CHIBI_BASE_WIDTH_DP = 110f

        /** SD 小人基础高度（dp），与 SecretaryChibiOverlay 的 CHIBI_BASE_HEIGHT 一致 */
        private const val CHIBI_BASE_HEIGHT_DP = 165f

        /**
         * 拖动到屏幕上方时允许的向上溢出比例（相对于窗口高度）。
         *
         * 与 SecretaryChibiOverlay 应用内场景的 CHIBI_DRAG_MARGIN_RATIO 保持一致（0.15f），
         * 允许小人头顶超出屏幕顶端 15% 窗口高度的距离，
         * 既能让用户把小人拖到屏幕上方靠近状态栏区域，又不会完全拖出屏幕不可见。
         */
        private const val DRAG_OVERFLOW_RATIO = 0.15f

        private val isRunning = AtomicBoolean(false)
        fun isServiceRunning(): Boolean = isRunning.get()
    }

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        MainActivity.updateOverlayState(true)
        Log.d(TAG, "onCreate: 服务创建")
        startForegroundServiceNotification()
        showOverlay()
    }

    @Suppress("DEPRECATION")
    private fun showOverlay() {
        Log.d(TAG, "showOverlay: 开始显示悬浮窗（双窗口架构）")

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val density = resources.displayMetrics.density
            val baseWidthPx = (CHIBI_BASE_WIDTH_DP * density).toInt()
            val baseHeightPx = (CHIBI_BASE_HEIGHT_DP * density).toInt()
            // 主窗口初始尺寸 = baseSize（sdScale 初始 1.0）；sdScale 变化后由 updateMainWindowSize 动态调整
            val overlayWidthPx = baseWidthPx
            val overlayHeightPx = baseHeightPx

            // 主窗口 LayoutParams：
            // - FLAG_NOT_FOCUSABLE：不抢占输入焦点
            // - FLAG_LAYOUT_IN_SCREEN：允许窗口覆盖状态栏区域
            // - FLAG_NOT_TOUCH_MODAL：窗口外事件穿透到下层 App
            // - FLAG_LAYOUT_NO_LIMITS：允许窗口位置超出屏幕边界（y 可为负），
            //   使小人可拖动到屏幕上方靠近状态栏区域。
            //   安全性保障：SpineSdView 的 OnTouchListener 使用 event.rawX/rawY
            //   （屏幕绝对坐标）计算拖动偏移，不依赖 view 自身位置，因此窗口超出
            //   屏幕边界不会导致触摸坐标异常或拖动卡死。
            val params = WindowManager.LayoutParams(
                overlayWidthPx,
                overlayHeightPx,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 100
            params.y = 300
            overlayLayoutParams = params

            lifecycleOwner = FloatingWindowLifecycleOwner()
            lifecycleOwner?.attachToDecor()

            composeView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(lifecycleOwner!!)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner!!)
                setViewTreeViewModelStoreOwner(lifecycleOwner!!)

                setContent {
                    BlyyTheme {
                        // 实时监听秘书舰状态变化
                        val secretaryState by secretaryManager.state.collectAsState()

                        val displayFigureUrl = secretaryState.figureUrl.ifEmpty {
                            "https://patchwiki.biligame.com/images/blhx/7/7c/lum7av08ir2klicda1h1v3neccoykmu.png"
                        }
                        val displayShipName = secretaryState.shipName.ifEmpty { "秘书舰" }

                        // 拖动状态（Compose State，让 LaunchedEffect 感知变化）
                        var isDragging by remember { mutableStateOf(false) }

                        // 当前对话内容（dialogueEnabled=false 时传 null 隐藏气泡）
                        val dialogue = if (secretaryState.dialogueEnabled) secretaryState.currentDialogue else null

                        // 监听 sdScale 变化：动态调整主窗口尺寸，保持小人中心位置不变
                        LaunchedEffect(secretaryState.sdScale) {
                            updateMainWindowSize(secretaryState.sdScale)
                        }

                        // 监听触摸穿透开关变化：动态切换主窗口 FLAG_NOT_TOUCHABLE
                        // 开启 → 主窗口所有触摸事件穿透到下层 App（纯展示模式）
                        // 关闭 → 恢复正常点击/拖动/缩放交互（含骨骼 bounds 外透明区域穿透）
                        LaunchedEffect(secretaryState.overlayTouchPassthrough) {
                            updateMainWindowTouchMode(secretaryState.overlayTouchPassthrough)
                        }

                        // 监听对话/拖动/立绘状态变化：添加或移除辅助窗口
                        // 显示条件：figureUrl 非空 或 sdResourceId 非空（SD 资源直接渲染时不依赖 figureUrl）
                        LaunchedEffect(dialogue, isDragging, secretaryState.figureUrl, secretaryState.sdResourceId) {
                            val hasContent = secretaryState.figureUrl.isNotEmpty() ||
                                secretaryState.sdResourceId.isNotEmpty()
                            if (!hasContent) {
                                hideAuxiliaryWindow()
                            } else {
                                updateAuxiliaryWindow(dialogue, isDragging)
                            }
                        }

                        // 使用动画过渡效果
                        AnimatedVisibility(
                            visible = secretaryState.figureUrl.isNotEmpty() ||
                                secretaryState.sdResourceId.isNotEmpty(),
                            enter = fadeIn(
                                animationSpec = tween(ANIMATION_DURATION_MS, easing = FastOutSlowInEasing)
                            ) + scaleIn(
                                animationSpec = tween(ANIMATION_DURATION_MS, easing = FastOutSlowInEasing),
                                initialScale = 0.8f
                            ),
                            exit = fadeOut(
                                animationSpec = tween(ANIMATION_DURATION_MS)
                            ) + scaleOut(
                                animationSpec = tween(ANIMATION_DURATION_MS),
                                targetScale = 0.8f
                            )
                        ) {
                            SecretaryChibiOverlay(
                                figureUrl = displayFigureUrl,
                                shipName = displayShipName,
                                dialogue = dialogue,
                                isSystemOverlay = true,
                                selectedSkin = secretaryState.sdSkin,
                                sdScale = secretaryState.sdScale,
                                sdResourceId = secretaryState.sdResourceId,
                                onTap = {
                                    Log.d(TAG, "showOverlay: 悬浮窗被点击")

                                    if (secretaryState.shipName.isNotEmpty()) {
                                        serviceScope.launch {
                                            try {
                                                secretaryManager.ensureVoicesLoaded(secretaryState.shipName)
                                                secretaryManager.playRandomVoice()
                                                Log.d(TAG, "showOverlay: 语音播放成功")
                                            } catch (e: Exception) {
                                                Log.e(TAG, "showOverlay: 语音播放失败", e)
                                            }
                                        }
                                    }
                                },
                                onPositionChange = { dx, dy ->
                                    moveMainWindow(dx, dy)
                                },
                                onDragStateChanged = { dragging -> isDragging = dragging }
                            )
                        }
                    }
                }
            }

            windowManager?.addView(composeView, params)
            Log.d(TAG, "showOverlay: 主窗口添加成功 (尺寸=${overlayWidthPx}x${overlayHeightPx}px)")

        } catch (e: Exception) {
            Log.e(TAG, "showOverlay: 悬浮窗显示失败", e)
        }
    }

    /**
     * 动态调整主窗口尺寸（sdScale 变化时调用）。
     *
     * 保持小人中心位置不变：
     * - 变化前中心 = (x + width/2, y + height/2)
     * - 变化后位置 = 旧中心 - 新尺寸/2
     *
     * 同步更新辅助窗口位置（紧贴新主窗口上方）。
     */
    private fun updateMainWindowSize(sdScale: Float) {
        val lp = overlayLayoutParams ?: return
        val view = composeView ?: return
        val density = resources.displayMetrics.density
        val baseWidthPx = (CHIBI_BASE_WIDTH_DP * density).toInt()
        val baseHeightPx = (CHIBI_BASE_HEIGHT_DP * density).toInt()
        val displayScale = sdScale.coerceIn(0.3f, 2.0f)
        val newWidth = (baseWidthPx * displayScale).toInt()
        val newHeight = (baseHeightPx * displayScale).toInt()

        if (lp.width == newWidth && lp.height == newHeight) return

        // 保持小人中心位置不变
        val oldCenterX = lp.x + lp.width / 2
        val oldCenterY = lp.y + lp.height / 2
        lp.width = newWidth
        lp.height = newHeight
        lp.x = oldCenterX - newWidth / 2
        lp.y = oldCenterY - newHeight / 2

        // 边界检查：x 限制在屏幕内；y 允许向上溢出 DRAG_OVERFLOW_RATIO 比例
        // 与 moveMainWindow 的边界限制保持一致，确保缩放后小人仍可位于屏幕上方区域
        val dm = resources.displayMetrics
        lp.x = lp.x.coerceIn(0, (dm.widthPixels - newWidth).coerceAtLeast(0))
        val minY = -(newHeight * DRAG_OVERFLOW_RATIO).toInt()
        val maxY = (dm.heightPixels - newHeight).coerceAtLeast(0)
        lp.y = lp.y.coerceIn(minY, maxY)

        try {
            windowManager?.updateViewLayout(view, lp)
            Log.d(TAG, "updateMainWindowSize: sdScale=$sdScale → ${newWidth}x${newHeight}px, pos=(${lp.x},${lp.y})")
        } catch (e: Exception) {
            Log.e(TAG, "updateMainWindowSize: updateViewLayout failed", e)
        }

        updateAuxiliaryPosition()
    }

    /**
     * 动态切换主窗口触摸穿透模式（[overlayTouchPassthrough] 变化时调用）。
     *
     * - touchPassthrough=true：追加 [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE]，
     *   主窗口所有触摸事件直接穿透到下层 App，SD 小人变为纯展示模式（不可点击/拖动/缩放）。
     *   显示层、语音播放、动画运行不受影响。
     * - touchPassthrough=false：移除 FLAG_NOT_TOUCHABLE，恢复正常交互。
     *   透明区域触摸穿透由 [SpineSdGlSurfaceView] 内部骨骼 bounds 判断处理。
     *
     * 通过 [WindowManager.updateViewLayout] 实时更新 flags，无需重建窗口，无需重启 App。
     */
    private fun updateMainWindowTouchMode(touchPassthrough: Boolean) {
        val lp = overlayLayoutParams ?: return
        val view = composeView ?: return

        // 基础 flags（始终需要）：
        // - FLAG_NOT_FOCUSABLE：不抢占输入焦点
        // - FLAG_LAYOUT_IN_SCREEN：允许窗口覆盖状态栏区域
        // - FLAG_NOT_TOUCH_MODAL：窗口外事件穿透到下层 App
        // - FLAG_LAYOUT_NO_LIMITS：允许窗口位置超出屏幕边界（y 可为负），
        //   使小人可拖动到屏幕上方区域
        val baseFlags = (
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        // 触摸穿透模式追加 FLAG_NOT_TOUCHABLE：窗口完全不接收触摸事件
        lp.flags = if (touchPassthrough) {
            baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            baseFlags
        }

        try {
            windowManager?.updateViewLayout(view, lp)
            Log.d(TAG, "updateMainWindowTouchMode: touchPassthrough=$touchPassthrough, flags=${lp.flags}")
        } catch (e: Exception) {
            Log.e(TAG, "updateMainWindowTouchMode: updateViewLayout failed", e)
        }
    }

    /**
     * 移动主窗口（拖动时调用）。
     *
     * 边界限制：
     * - x：限制在 [0, 屏幕宽度 - 窗口宽度]，水平方向不允许超出屏幕
     * - y：允许向上溢出一定距离（[DRAG_OVERFLOW_RATIO] × 窗口高度），
     *   使小人可拖动到屏幕上方靠近状态栏区域；向下限制为屏幕底部
     *
     * 同步更新辅助窗口位置（紧贴主窗口上方，FLAG_LAYOUT_NO_LIMITS 允许 y 为负）。
     */
    private fun moveMainWindow(dx: Float, dy: Float) {
        val lp = overlayLayoutParams ?: return
        val view = composeView ?: return
        val dm = resources.displayMetrics
        val maxX = (dm.widthPixels - lp.width).coerceAtLeast(0)
        // 允许向上溢出：minY = -窗口高度 × DRAG_OVERFLOW_RATIO
        // 这样小人头顶最多超出屏幕顶端 15% 窗口高度，仍保持可见可拖动
        val minY = -(lp.height * DRAG_OVERFLOW_RATIO).toInt()
        val maxY = (dm.heightPixels - lp.height).coerceAtLeast(0)
        lp.x = (lp.x + dx.toInt()).coerceIn(0, maxX)
        lp.y = (lp.y + dy.toInt()).coerceIn(minY, maxY)
        try {
            windowManager?.updateViewLayout(view, lp)
        } catch (e: Exception) {
            Log.e(TAG, "moveMainWindow: updateViewLayout failed", e)
        }
        updateAuxiliaryPosition()
    }

    // ───────────────────────────────────────────────────────────────────────────
    // 辅助窗口管理（气泡 / 拖动提示）
    // ───────────────────────────────────────────────────────────────────────────

    /**
     * 根据对话和拖动状态添加或移除辅助窗口。
     *
     * - 有对话或正在拖动 → 显示辅助窗口（[showAuxiliaryWindow]）
     * - 否则 → 移除辅助窗口（[hideAuxiliaryWindow]）
     */
    private fun updateAuxiliaryWindow(dialogue: String?, isDragging: Boolean) {
        if (dialogue == null && !isDragging) {
            hideAuxiliaryWindow()
        } else {
            showAuxiliaryWindow(dialogue, isDragging)
        }
    }

    /**
     * 显示辅助窗口（若已存在则更新内容）。
     *
     * 辅助窗口使用 WRAP_CONTENT 自动适应内容尺寸，位置紧贴主窗口上方。
     * FLAG_LAYOUT_NO_LIMITS 允许窗口 y 为负（主窗口在屏幕顶部时辅助窗口上方溢出）。
     * FLAG_NOT_TOUCHABLE 确保辅助窗口完全不拦截触摸事件（纯展示，不需要交互）。
     */
    @Suppress("DEPRECATION")
    private fun showAuxiliaryWindow(dialogue: String?, isDragging: Boolean) {
        val owner = lifecycleOwner ?: return
        val wm = windowManager ?: return

        if (auxComposeView == null) {
            // 创建辅助窗口
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                // FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL：与主窗口一致
                // FLAG_NOT_TOUCHABLE：辅助窗口纯展示，不拦截任何触摸事件，全部穿透到下层
                // FLAG_LAYOUT_NO_LIMITS：允许 y 为负（紧贴主窗口上方时可能超出屏幕顶部）
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START

            val view = ComposeView(this).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setContent {
                    BlyyTheme {
                        AuxiliaryWindowContent(dialogue, isDragging)
                    }
                }
            }

            try {
                wm.addView(view, params)
                auxComposeView = view
                auxLayoutParams = params
                Log.d(TAG, "showAuxiliaryWindow: 辅助窗口添加成功")
            } catch (e: Exception) {
                Log.e(TAG, "showAuxiliaryWindow: addView failed", e)
            }
        } else {
            // 更新辅助窗口内容（dialogue 或 isDragging 变化）
            auxComposeView?.setContent {
                BlyyTheme {
                    AuxiliaryWindowContent(dialogue, isDragging)
                }
            }
        }
    }

    /**
     * 辅助窗口 Compose 内容。
     *
     * 通过 [SecretaryOverlayAuxiliaryContent] 渲染气泡或拖动提示，
     * [onSizeChanged] 回调测量实际尺寸并更新窗口位置。
     */
    @androidx.compose.runtime.Composable
    private fun AuxiliaryWindowContent(dialogue: String?, isDragging: Boolean) {
        SecretaryOverlayAuxiliaryContent(
            dialogue = dialogue,
            isDragging = isDragging,
            onSizeChanged = { w, h ->
                if (w != auxWidthPx || h != auxHeightPx) {
                    auxWidthPx = w
                    auxHeightPx = h
                    updateAuxiliaryPosition()
                }
            }
        )
    }

    /**
     * 移除辅助窗口。
     */
    private fun hideAuxiliaryWindow() {
        auxComposeView?.let { view ->
            try {
                windowManager?.removeView(view)
                Log.d(TAG, "hideAuxiliaryWindow: 辅助窗口移除成功")
            } catch (e: Exception) {
                Log.e(TAG, "hideAuxiliaryWindow: removeView failed", e)
            }
        }
        auxComposeView = null
        auxLayoutParams = null
        auxWidthPx = 0
        auxHeightPx = 0
    }

    /**
     * 更新辅助窗口位置（紧贴主窗口上方，水平居中）。
     *
     * - x = 主窗口中心 x - 辅助窗口宽度/2
     * - y = 主窗口顶部 y - 辅助窗口高度
     *
     * 主窗口在屏幕顶部时 y 为负，FLAG_LAYOUT_NO_LIMITS 允许溢出（辅助窗口部分不可见，可接受）。
     */
    private fun updateAuxiliaryPosition() {
        val auxLp = auxLayoutParams ?: return
        val auxView = auxComposeView ?: return
        val mainLp = overlayLayoutParams ?: return

        // 紧贴主窗口上方，水平居中
        auxLp.x = mainLp.x + (mainLp.width - auxWidthPx) / 2
        auxLp.y = mainLp.y - auxHeightPx

        try {
            windowManager?.updateViewLayout(auxView, auxLp)
        } catch (e: Exception) {
            Log.e(TAG, "updateAuxiliaryPosition: updateViewLayout failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: startId=$startId")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户从最近任务划掉 App 时停止悬浮窗，避免无感知常驻
        Log.d(TAG, "onTaskRemoved: 停止悬浮窗服务")
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: 服务销毁")
        isRunning.set(false)
        MainActivity.updateOverlayState(false)

        try {
            // 先移除辅助窗口
            hideAuxiliaryWindow()
            // 再移除主窗口：先从 WindowManager 移除 View（触发 detach），再销毁 LifecycleOwner
            composeView?.let { view ->
                windowManager?.removeView(view)
                lifecycleOwner?.dispose()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy: 移除视图失败", e)
        } finally {
            // 主动断开引用，加速 GC
            composeView = null
            overlayLayoutParams = null
            lifecycleOwner = null
            windowManager = null
        }

        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundServiceNotification() {
        // 前台服务通知渠道必须先于 startForeground 创建（Android 8+）
        val channelId = "secretary_overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "秘书舰悬浮窗", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = android.app.Notification.Builder(this, channelId)
            .setContentTitle("秘书舰陪伴中")
            .setContentText("秘书舰正在屏幕上")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

        // Android 14+ 必须显式传入与 Manifest 声明匹配的 foregroundServiceType
        // 不再 try/catch 吞异常：失败应立即 stopSelf，避免 5 秒未 startForeground 崩溃
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
        Log.d(TAG, "startForegroundServiceNotification: 前台服务启动成功")
    }
}
