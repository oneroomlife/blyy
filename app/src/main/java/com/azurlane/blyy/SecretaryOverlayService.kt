package com.azurlane.blyy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.RectF
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
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import com.azurlane.blyy.ui.components.CHIBI_BASE_HEIGHT
import com.azurlane.blyy.ui.components.CHIBI_BASE_WIDTH
import com.azurlane.blyy.ui.components.CHIBI_DRAG_MARGIN_RATIO
import com.azurlane.blyy.ui.components.OverlayGestureHandler
import com.azurlane.blyy.ui.components.OverlayGeometry
import com.azurlane.blyy.ui.components.SecretaryChibiOverlay
import com.azurlane.blyy.ui.components.SecretaryOverlayAuxiliaryContent
import com.azurlane.blyy.ui.theme.BlyyTheme
import com.azurlane.blyy.viewmodel.SecretaryManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 秘书舰悬浮窗服务（双窗口架构）。
 *
 * 【优化前】单一 220×365dp 大窗口容纳立绘(110×165dp) + 气泡(220×200dp)，
 *   透明区域被 WindowManager 吞掉触摸事件，导致用户无法操作下方 App。
 *
 * 【优化后】双窗口架构：
 * 1. 主窗口（[composeView]）：尺寸 = baseSize × displayScale，仅含 SpineSdView。
 *    触摸区域紧贴小人实际显示范围，无透明区域拦截问题。
 *    sdScale 变化时按"最小偏移锚定"策略动态调整尺寸（贴边停放时向空旷侧生长，不跳位）。
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

    // ── 静态图分支手势处理（Service 层 rawX/rawY 拖拽）──
    // 静态图 Composable 不挂 pointerInput，交互全部由此处理器在 View 层完成；
    // Spine 分支激活时 spineActive=true 放行事件给 GLSurfaceView 自身处理
    private var gestureHandler: OverlayGestureHandler? = null
    private val hitRectState = mutableStateOf<RectF?>(null)
    private val spineActiveState = mutableStateOf(false)

    /**
     * 主窗口是否因内容为空被临时移除（僵尸窗口修复）。
     *
     * true = ComposeView 已从 WindowManager removeView，但 view/params/生命周期宿主
     * 保留在 Service 中，内容恢复时 [ensureMainWindowAttached] 直接 re-add。
     */
    @Volatile private var mainWindowDetached = false

    // ── 辅助窗口（气泡/拖动提示）──
    private var auxComposeView: ComposeView? = null
    private var auxLayoutParams: WindowManager.LayoutParams? = null
    /** 辅助窗口实际尺寸（px），由 onGloballyPositioned 测量，用于位置计算 */
    private var auxWidthPx: Int = 0
    private var auxHeightPx: Int = 0

    // 辅助窗口内容状态：窗口只创建一次，内容由状态驱动自动更新
    // （此前每次对话变化都重新 setContent 重建整个组合）
    private val auxDialogueState = mutableStateOf<String?>(null)
    private val auxDraggingState = mutableStateOf(false)

    private var lifecycleOwner: FloatingWindowLifecycleOwner? = null

    /** 通知"关闭悬浮窗" action 的动态接收器（onDestroy 注销） */
    private var closeActionReceiver: BroadcastReceiver? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    companion object {
        private const val TAG = "SecretaryOverlay"
        private const val ANIMATION_DURATION_MS = 300

        /** 通知关闭 action（setPackage 限定本应用内部广播） */
        private const val ACTION_CLOSE_OVERLAY = "com.azurlane.blyy.action.CLOSE_SECRETARY_OVERLAY"

        /** 未保存过位置时的默认落点（px） */
        private const val DEFAULT_POS_X = 100
        private const val DEFAULT_POS_Y = 300

        private val isRunning = AtomicBoolean(false)
        fun isServiceRunning(): Boolean = isRunning.get()
    }

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        secretaryManager.updateOverlayRunning(true)
        Log.d(TAG, "onCreate: 服务创建")
        startForegroundServiceNotification()
        // 窗口创建移入协程：先读取上次保存的位置再 addView，
        // 小人直接出现在用户上次摆放的位置（而非先出现在默认位再跳转）
        serviceScope.launch { showOverlay() }
    }

    /**
     * 读取持久化的悬浮窗位置；无保存记录时返回默认落点。
     *
     * 恢复时按当前屏幕与窗口尺寸重新夹取：分辨率/旋转/缩放后保存值可能越界。
     */
    private suspend fun resolveWindowPosition(windowWidth: Int, windowHeight: Int): Pair<Int, Int> {
        val savedX = playerSettings.secretaryOverlayPosX.first()
        val savedY = playerSettings.secretaryOverlayPosY.first()
        if (savedX == null || savedY == null) return DEFAULT_POS_X to DEFAULT_POS_Y
        val dm = resources.displayMetrics
        val maxX = (dm.widthPixels - windowWidth).coerceAtLeast(0)
        val minY = -(windowHeight * CHIBI_DRAG_MARGIN_RATIO).toInt()
        val maxY = (dm.heightPixels - windowHeight).coerceAtLeast(0)
        return savedX.coerceIn(0, maxX) to savedY.coerceIn(minY, maxY)
    }

    /** 拖动结束/缩放调整后把当前位置写回 DataStore（下次服务启动恢复） */
    private fun persistWindowPosition() {
        val lp = overlayLayoutParams ?: return
        val x = lp.x
        val y = lp.y
        serviceScope.launch {
            runCatching { playerSettings.setSecretaryOverlayPosition(x, y) }
                .onFailure { Log.w(TAG, "persistWindowPosition: 保存位置失败", it) }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun showOverlay() {
        Log.d(TAG, "showOverlay: 开始显示悬浮窗（双窗口架构）")

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val density = resources.displayMetrics.density
            // 基础尺寸与 SecretaryChibiOverlay 共用同一常量，保证双端渲染/交互范围一致
            val baseWidthPx = (CHIBI_BASE_WIDTH.value * density).toInt()
            val baseHeightPx = (CHIBI_BASE_HEIGHT.value * density).toInt()
            // 【重启偏移修复】主窗口初始尺寸必须与持久化的 sdScale 一致。
            // 旧实现固定按 baseSize（sdScale=1.0）建窗：用户上次放大后保存的位置是
            // 大窗口左上角，先按小窗恢复、再由 updateMainWindowSize 以"小窗中心"为基准
            // 缩放，最终小人中心整体向左上偏移 (新尺寸-旧尺寸)/2，表现为放大后
            // 重启/重开悬浮窗时位置异常偏移。
            val savedScale = playerSettings.secretarySdScale.first().coerceIn(0.3f, 2.0f)
            val overlayWidthPx = (baseWidthPx * savedScale).toInt()
            val overlayHeightPx = (baseHeightPx * savedScale).toInt()

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
            val (posX, posY) = resolveWindowPosition(overlayWidthPx, overlayHeightPx)
            params.x = posX
            params.y = posY
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

                        // 是否有可渲染内容（figureUrl / webChibiUrl / sdResourceId 任一非空）。
                        // 同时驱动：辅助窗口增删 + 主窗口增删（僵尸窗口修复）+ 内容可见性动画
                        val hasChibiContent = secretaryState.figureUrl.isNotEmpty() ||
                            secretaryState.webChibiUrl.isNotEmpty() ||
                            secretaryState.sdResourceId.isNotEmpty()

                        // 监听对话/拖动状态变化：添加或移除辅助窗口
                        LaunchedEffect(dialogue, isDragging, secretaryState.figureUrl, secretaryState.webChibiUrl, secretaryState.sdResourceId) {
                            if (!hasChibiContent) {
                                hideAuxiliaryWindow()
                            } else {
                                updateAuxiliaryWindow(dialogue, isDragging)
                            }
                        }
                        // 静态图分支的交互参数上报同步：Composable 写 Compose State，
                        // 此处镜像到手势处理器的 @Volatile 字段（View 层触摸读取）
                        val reportedHitRect by hitRectState
                        val reportedSpineActive by spineActiveState
                        LaunchedEffect(reportedHitRect) {
                            gestureHandler?.hitRect = reportedHitRect
                        }
                        LaunchedEffect(reportedSpineActive) {
                            gestureHandler?.spineActive = reportedSpineActive
                        }

                        // 【僵尸窗口修复】主窗口随内容增删：秘书舰被清空（三 URL 全空）时
                        // removeView 释放窗口/ComposeView 资源，不再常驻一块透明窗口；
                        // 内容恢复时按最新持久化位置 re-add。
                        // 原实现仅隐藏内容（AnimatedVisibility），窗口对象与 GL 资源持续存在。
                        LaunchedEffect(hasChibiContent) {
                            if (hasChibiContent) {
                                ensureMainWindowAttached()
                            } else {
                                removeMainWindowTemporarily()
                            }
                        }

                        // 使用动画过渡效果
                        AnimatedVisibility(
                            visible = hasChibiContent,
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
                                figureUrl = secretaryState.figureUrl,
                                shipName = secretaryState.shipName,
                                dialogue = dialogue,
                                isSystemOverlay = true,
                                selectedSkin = secretaryState.sdSkin,
                                sdScale = secretaryState.sdScale,
                                sdResourceId = secretaryState.sdResourceId,
                                webChibiUrl = secretaryState.webChibiUrl,
                                // 静态图分支交互上报：可交互矩形（窗口本地坐标）与分支模式。
                                // Service 层 OverlayGestureHandler 据此做屏幕空间命中判断——
                                // Compose 层 pointerInput 已摘除（修复自参考系拖拽漂移）
                                onHitRectChanged = { rect -> hitRectState.value = rect },
                                onSpineActiveChanged = { active -> spineActiveState.value = active },
                                onTap = {
                                    Log.d(TAG, "showOverlay: 悬浮窗被点击")
                                    if (secretaryState.shipName.isNotEmpty()) {
                                        // playRandomVoice 内部会等待语音列表就绪（首次点击也出声）
                                        serviceScope.launch {
                                            try {
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
                                onDragStateChanged = { dragging ->
                                    isDragging = dragging
                                    // 拖动结束（含取消）时持久化最终位置，下次启动恢复
                                    if (!dragging) persistWindowPosition()
                                }
                            )
                        }
                    }
                }
            }

            // 静态图分支手势处理器：View 层 rawX/rawY 拖拽（修复 Compose 本地坐标漂移）。
            // hitRect/spineActive 由 Composable 状态上报，经 setter 同步到处理器
            val handler = OverlayGestureHandler(
                view = composeView!!,
                windowParamsProvider = {
                    val lp = overlayLayoutParams
                    (lp?.x ?: 0) to (lp?.y ?: 0)
                },
                onMove = { dx, dy -> moveMainWindow(dx, dy) },
                onTap = {
                    Log.d(TAG, "GestureHandler: 悬浮窗被点击")
                    if (secretaryManager.state.value.shipName.isNotEmpty()) {
                        serviceScope.launch {
                            try {
                                secretaryManager.playRandomVoice()
                            } catch (e: Exception) {
                                Log.e(TAG, "GestureHandler: 语音播放失败", e)
                            }
                        }
                    }
                },
                onDragStateChanged = { dragging ->
                    if (!dragging) persistWindowPosition()
                }
            )
            // 状态上报接线：Composable 写 mutableState → 同步到处理器 @Volatile 字段
            // （setContent 中不直接持 handler 引用，用 Service 级状态中转）
            gestureHandler = handler
            composeView?.setOnTouchListener(handler)

            windowManager?.addView(composeView, params)
            Log.d(TAG, "showOverlay: 主窗口添加成功 (尺寸=${overlayWidthPx}x${overlayHeightPx}px, pos=($posX,$posY))")

        } catch (e: Exception) {
            Log.e(TAG, "showOverlay: 悬浮窗显示失败", e)
        }
    }

    /**
     * 动态调整主窗口尺寸（sdScale 变化时调用）。
     *
     * 【最小偏移锚定】从多候选锚定方案（左缘/右缘/顶缘/底缘/中心保持）中，
     * 选择"边界 clamp 后小人中心相对旧中心偏移最小"的一个：
     * - 屏幕中部的窗口等价于中心保持（clamp 不触发，中心不动）
     * - 贴边停放的窗口向空旷侧生长，clamp 不触发，视觉零跳位
     * 旧实现固定"中心保持"，贴边放大时新窗口越界被 clamp 反向推移，表现为放大跳位。
     *
     * 同步更新辅助窗口位置（紧贴新主窗口上方）。
     */
    private fun updateMainWindowSize(sdScale: Float) {
        val lp = overlayLayoutParams ?: return
        val view = composeView ?: return
        val density = resources.displayMetrics.density
        val baseWidthPx = (CHIBI_BASE_WIDTH.value * density).toInt()
        val baseHeightPx = (CHIBI_BASE_HEIGHT.value * density).toInt()
        val displayScale = sdScale.coerceIn(0.3f, 2.0f)
        val newWidth = (baseWidthPx * displayScale).toInt()
        val newHeight = (baseHeightPx * displayScale).toInt()

        if (lp.width == newWidth && lp.height == newHeight) return

        val dm = resources.displayMetrics
        val oldWidth = lp.width
        val oldHeight = lp.height
        val oldX = lp.x
        val oldY = lp.y
        val oldCenterX = oldX + oldWidth / 2f
        val oldCenterY = oldY + oldHeight / 2f
        lp.width = newWidth
        lp.height = newHeight

        // x 允许范围：限制在屏幕内（与 moveMainWindow 的边界限制一致）
        val maxX = (dm.widthPixels - newWidth).coerceAtLeast(0)
        lp.x = pickMinShiftAnchor(
            candidates = floatArrayOf(
                oldX.toFloat(),                             // 左缘锚定：向右生长
                (oldX + oldWidth - newWidth).toFloat(),     // 右缘锚定：向左生长
                oldCenterX - newWidth / 2f                  // 中心保持
            ),
            minBound = 0f,
            maxBound = maxX.toFloat(),
            oldCenter = oldCenterX,
            newSize = newWidth
        )

        // y 允许范围：允许向上溢出 CHIBI_DRAG_MARGIN_RATIO 比例（与 moveMainWindow 一致），
        // 确保缩放后小人仍可位于屏幕上方区域
        val minY = -(newHeight * CHIBI_DRAG_MARGIN_RATIO)
        val maxY = (dm.heightPixels - newHeight).coerceAtLeast(0)
        lp.y = pickMinShiftAnchor(
            candidates = floatArrayOf(
                oldY.toFloat(),                             // 顶缘锚定：向下生长
                (oldY + oldHeight - newHeight).toFloat(),   // 底缘锚定：向上生长（脚底不动）
                oldCenterY - newHeight / 2f                 // 中心保持
            ),
            minBound = minY.toFloat(),
            maxBound = maxY.toFloat(),
            oldCenter = oldCenterY,
            newSize = newHeight
        )

        try {
            windowManager?.updateViewLayout(view, lp)
            Log.d(TAG, "updateMainWindowSize: sdScale=$sdScale → ${newWidth}x${newHeight}px, pos=(${lp.x},${lp.y})")
        } catch (e: Exception) {
            Log.e(TAG, "updateMainWindowSize: updateViewLayout failed", e)
        }

        updateAuxiliaryPosition()
        // 缩放调整过位置，持久化保持"重启后回到当前位置"
        persistWindowPosition()
    }

    /**
     * 从多个候选位置中选出"clamp 后窗口中心相对旧中心偏移最小"的一个（px）。
     *
     * 纯逻辑在 [OverlayGeometry.pickMinShiftAnchor]（可 JVM 单测），
     * 此处保留同名薄委托供本类调用点使用。
     */
    private fun pickMinShiftAnchor(
        candidates: FloatArray,
        minBound: Float,
        maxBound: Float,
        oldCenter: Float,
        newSize: Int
    ): Int = OverlayGeometry.pickMinShiftAnchor(candidates, minBound, maxBound, oldCenter, newSize)

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
        // 允许向上溢出：minY = -窗口高度 × CHIBI_DRAG_MARGIN_RATIO
        // 这样小人头顶最多超出屏幕顶端 15% 窗口高度，仍保持可见可拖动
        val minY = -(lp.height * CHIBI_DRAG_MARGIN_RATIO).toInt()
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
    // 主窗口增删（僵尸窗口修复）
    // ───────────────────────────────────────────────────────────────────────────

    /**
     * 主窗口已移除且内容恢复时重新挂到 WindowManager。
     *
     * 位置策略：优先沿用当前 LayoutParams 中的位置（服务存活期间用户拖动过的位置）；
     * 若窗口从未真正显示过（onCreate 时内容即空），按持久化位置解析。
     */
    private suspend fun ensureMainWindowAttached() {
        val view = composeView ?: return
        val lp = overlayLayoutParams ?: return
        if (!mainWindowDetached) return
        mainWindowDetached = false
        // 服务存活期间的移除→恢复沿用原位置；首次显示（onCreate 时无内容）走持久化解析
        if (lp.width <= 0 || lp.height <= 0) {
            rebuildLayoutParamsFromPersisted(lp)
        }
        try {
            windowManager?.addView(view, lp)
            Log.d(TAG, "ensureMainWindowAttached: 主窗口重新挂载 pos=(${lp.x},${lp.y})")
        } catch (e: Exception) {
            Log.e(TAG, "ensureMainWindowAttached: addView failed", e)
            mainWindowDetached = true
        }
    }

    /** 内容为空时移除主窗口（窗口/ComposeView/GL 资源全部释放，view 对象保留供恢复） */
    private fun removeMainWindowTemporarily() {
        val view = composeView ?: return
        if (mainWindowDetached) return
        mainWindowDetached = true
        try {
            windowManager?.removeView(view)
            Log.d(TAG, "removeMainWindowTemporarily: 主窗口移除（无内容）")
        } catch (e: Exception) {
            Log.e(TAG, "removeMainWindowTemporarily: removeView failed", e)
        }
    }

    /** 按持久化 sdScale/位置重建 LayoutParams 尺寸与落点（尺寸合法后仍需 clamp） */
    private suspend fun rebuildLayoutParamsFromPersisted(lp: WindowManager.LayoutParams) {
        val density = resources.displayMetrics.density
        val baseWidthPx = (CHIBI_BASE_WIDTH.value * density).toInt()
        val baseHeightPx = (CHIBI_BASE_HEIGHT.value * density).toInt()
        val savedScale = playerSettings.secretarySdScale.first().coerceIn(0.3f, 2.0f)
        lp.width = (baseWidthPx * savedScale).toInt()
        lp.height = (baseHeightPx * savedScale).toInt()
        val (posX, posY) = resolveWindowPosition(lp.width, lp.height)
        lp.x = posX
        lp.y = posY
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
            // 内容经状态驱动更新：已存在的窗口无需 setContent 重建
            auxDialogueState.value = dialogue
            auxDraggingState.value = isDragging
            showAuxiliaryWindow()
        }
    }

    /**
     * 显示辅助窗口（若已存在则由状态自动更新内容）。
     *
     * 辅助窗口使用 WRAP_CONTENT 自动适应内容尺寸，位置紧贴主窗口上方。
     * FLAG_LAYOUT_NO_LIMITS 允许窗口 y 为负（主窗口在屏幕顶部时辅助窗口上方溢出）。
     * FLAG_NOT_TOUCHABLE 确保辅助窗口完全不拦截触摸事件（纯展示，不需要交互）。
     */
    @Suppress("DEPRECATION")
    private fun showAuxiliaryWindow() {
        val owner = lifecycleOwner ?: return
        val wm = windowManager ?: return
        if (auxComposeView != null) return

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
                    AuxiliaryWindowContent()
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
    }

    /**
     * 辅助窗口 Compose 内容。
     *
     * 通过 [SecretaryOverlayAuxiliaryContent] 渲染气泡或拖动提示，
     * 内容读取 [auxDialogueState]/[auxDraggingState]，变化时自动重组；
     * [onSizeChanged] 回调测量实际尺寸并更新窗口位置。
     */
    @androidx.compose.runtime.Composable
    private fun AuxiliaryWindowContent() {
        SecretaryOverlayAuxiliaryContent(
            dialogue = auxDialogueState.value,
            isDragging = auxDraggingState.value,
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
        // 清空内容状态，避免下次创建瞬间闪现旧内容
        auxDialogueState.value = null
        auxDraggingState.value = false
    }

    /**
     * 更新辅助窗口位置（紧贴主窗口上方，水平居中）。
     *
     * - 期望 x = 主窗口中心 x - 辅助窗口宽度/2
     * - 期望 y = 主窗口顶部 y - 辅助窗口高度
     *
     * 【屏幕外消失修复】原实现 x/y 完全不 clamp：
     * - 主窗贴左/右边缘时气泡水平溢出屏幕（FLAG_LAYOUT_NO_LIMITS 渲染到屏幕外）
     * - 主窗拖到顶部（y 允许 -15% 窗高）时气泡 y < 0，整个气泡不可见
     * 现在 x clamp 到 [0, 屏宽-辅宽]、y 下限 0：气泡宁可横向偏移/压住小人头顶，
     * 也不消失到屏幕外（对话内容必须可见）。
     *
     * 辅窗尺寸未知（onGloballyPositioned 回调前 auxWidthPx=0）时跳过 x 方向的
     * 边缘 clamp（0 尺寸 clamp 结果无意义），首次测量回调后会再次触发定位纠正。
     */
    private fun updateAuxiliaryPosition() {
        val auxLp = auxLayoutParams ?: return
        val auxView = auxComposeView ?: return
        val mainLp = overlayLayoutParams ?: return
        val dm = resources.displayMetrics

        // 紧贴主窗口上方，水平居中
        val desiredX = mainLp.x + (mainLp.width - auxWidthPx) / 2
        val desiredY = mainLp.y - auxHeightPx
        // x clamp：辅宽已知时限制在屏幕内（未知时中心定位，等待测量回调纠正）
        auxLp.x = if (auxWidthPx > 0) {
            desiredX.coerceIn(0, (dm.widthPixels - auxWidthPx).coerceAtLeast(0))
        } else {
            desiredX
        }
        // y clamp：下限 0（屏幕顶部），气泡不消失到屏幕外（宁可压住小人头顶）
        auxLp.y = desiredY.coerceAtLeast(0)

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

    /**
     * 【转屏/折叠恢复】屏幕尺寸/方向变化时按新 displayMetrics 重新 clamp 主窗位置。
     *
     * 原实现无此回调：转屏后（横屏↔竖屏、折叠屏展开）窗口可能停在屏幕外或越界，
     * 直到用户下次拖动才被 clamp 纠正。注意 Service 的 manifest 未声明
     * android:configChanges，默认行为下旋转会导致 Service 重建（onCreate 重新走
     * 持久化恢复路径，本身已按新屏幕 clamp）；此回调主要覆盖不触发重建的尺寸变化
     *（多窗口模式拖动调整大小、分屏边界变化等）。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val lp = overlayLayoutParams ?: return
        val view = composeView ?: return
        if (mainWindowDetached) return
        val dm = resources.displayMetrics
        val maxX = (dm.widthPixels - lp.width).coerceAtLeast(0)
        val minY = -(lp.height * CHIBI_DRAG_MARGIN_RATIO).toInt()
        val maxY = (dm.heightPixels - lp.height).coerceAtLeast(0)
        val newX = lp.x.coerceIn(0, maxX)
        val newY = lp.y.coerceIn(minY, maxY)
        if (newX != lp.x || newY != lp.y) {
            lp.x = newX
            lp.y = newY
            try {
                windowManager?.updateViewLayout(view, lp)
                Log.d(TAG, "onConfigurationChanged: 主窗位置 clamp 到 (${newX},${newY})")
            } catch (e: Exception) {
                Log.e(TAG, "onConfigurationChanged: updateViewLayout failed", e)
            }
            updateAuxiliaryPosition()
            persistWindowPosition()
        }
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
        secretaryManager.updateOverlayRunning(false)

        try {
            // 先移除辅助窗口
            hideAuxiliaryWindow()
            // 再移除主窗口：先从 WindowManager 移除 View（触发 detach），再销毁 LifecycleOwner
            composeView?.let { view ->
                windowManager?.removeView(view)
            }
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy: 移除视图失败", e)
        } finally {
            // dispose 与 removeView 解耦：removeView 抛异常（如 view 本就未附加）时，
            // LifecycleOwner/ViewModelStore 的清理也不能跳过（handleLifecycleEvent 幂等）
            try { lifecycleOwner?.dispose() } catch (_: Exception) {}
            // 清理手势处理器的 Handler 回调
            gestureHandler?.dispose()
            gestureHandler = null
            // 注销通知关闭 action 接收器
            closeActionReceiver?.let {
                runCatching { unregisterReceiver(it) }
            }
            closeActionReceiver = null
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

        // 【通知交互】点击通知打开 App（回 MainActivity，无需新建任务栈）；
        // "关闭" action 经 LocalBroadcast 直接 stopSelf（悬浮窗场景标准做法，
        // 免去用户进设置页找开关的两步操作）
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val closeIntent = PendingIntent.getBroadcast(
            this, 1,
            Intent(ACTION_CLOSE_OVERLAY).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val baseBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
        }
        val notification = baseBuilder
            .setContentTitle("秘书舰陪伴中")
            .setContentText("秘书舰正在屏幕上")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "关闭悬浮窗",
                closeIntent
            )
            .build()

        // Android 14+ 必须显式传入与 Manifest 声明匹配的 foregroundServiceType
        // 不再 try/catch 吞异常：失败应立即 stopSelf，避免 5 秒未 startForeground 崩溃
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }

        // 关闭 action 的接收器：动态注册（setPackage 已限定本应用，接收器无需导出）
        if (closeActionReceiver == null) {
            closeActionReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == ACTION_CLOSE_OVERLAY) {
                        Log.d(TAG, "通知关闭 action：停止悬浮窗服务")
                        serviceScope.launch {
                            runCatching { playerSettings.setSecretaryOverlayEnabled(false) }
                        }
                        stopSelf()
                    }
                }
            }
            val filter = IntentFilter(ACTION_CLOSE_OVERLAY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(closeActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(closeActionReceiver, filter)
            }
        }
        Log.d(TAG, "startForegroundServiceNotification: 前台服务启动成功")
    }
}
