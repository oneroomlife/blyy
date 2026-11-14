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
import android.hardware.input.InputManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt
import com.azurlane.blyy.util.SECRETARY_SD_SCALE_MAX
import com.azurlane.blyy.util.SECRETARY_SD_SCALE_MIN

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
     * 主窗口「触摸穿透」状态（唯一真值）。
     *
     * 所有会写 `lp.flags` 的路径（首次 addView / 缩放 updateViewLayout /
     * 转屏重建 / 僵尸窗口 re-add）都必须经 [applyWindowFlags] 用这个字段重算，
     * 保证穿透开关不会因为某条路径"忘了重放 flags"而偶发失效。
     */
    @Volatile private var touchPassthrough = false

    /**
     * 当前 SD 小人的可见内容宽高比（宽/高），由 [SecretaryChibiOverlay] 首个
     * 渲染帧上报。用于把主窗口尺寸收紧到"内容形状"，从而同时解决：
     * - 窗口比小人形体大得多（触摸面积虚高、随放大进一步虚高）
     * - 小人被固定宽高比窗口压小
     */
    private val contentAspectState = mutableStateOf<Float?>(null)

    /**
     * 主窗口是否因内容为空被临时移除（僵尸窗口修复）。
     *
     * true = ComposeView 已从 WindowManager removeView，但 view/params/生命周期宿主
     * 保留在 Service 中，内容恢复时 [ensureMainWindowAttached] 直接 re-add。
     */
    @Volatile private var mainWindowDetached = false

    /**
     * 主窗口是否真的挂在 WindowManager 上（对外可见）。
     *
     * 与 [mainWindowDetached] 互为反面，但语义不同：这里用于告诉 UI 层
     * 「悬浮窗现在看得见吗」。旧实现只在 Service onCreate/onDestroy 发布
     * overlayRunning，窗口被移除或 addView 失败时对外仍是「已开启」——
     * [com.azurlane.blyy.ui.AppRoot] 因此把应用内小人也隐藏掉，
     * 用户看到的是「哪儿都没有小人」。
     */
    @Volatile private var overlayWindowVisible = false

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

    /**
     * 屏幕是否处于交互状态（熄屏 = false）。
     *
     * 系统悬浮窗的 LifecycleOwner（[FloatingWindowLifecycleOwner]）恒为 RESUMED，
     * 拿不到 Activity 的 onPause —— 熄屏后 GL 线程仍会被 vsync 唤醒出帧。
     * 这里由息屏/亮屏广播维护，下发给 [SecretaryChibiOverlay] 的 renderingEnabled
     * 停掉出帧循环。[SecretaryChibiOverlay] 侧只是停止 requestRender，
     * EGL context 与纹理都不销毁，亮屏后一帧即恢复。
     */
    private val screenInteractiveState = mutableStateOf(true)

    /** 息屏/亮屏广播接收器（onDestroy 注销） */
    private var screenStateReceiver: BroadcastReceiver? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    companion object {
        private const val TAG = "SecretaryOverlay"
        private const val ANIMATION_DURATION_MS = 300

        /** 通知关闭 action（setPackage 限定本应用内部广播） */
        private const val ACTION_CLOSE_OVERLAY = "com.azurlane.blyy.action.CLOSE_SECRETARY_OVERLAY"

        /** 未保存过位置时的默认落点（px） */
        private const val DEFAULT_POS_X = 100
        private const val DEFAULT_POS_Y = 300

        /**
         * 悬浮窗短边下限（dp）。
         *
         * 窗口尺寸改为贴合内容形状后，极瘦/极扁的 SD 骨骼会算出很小的短边，
         * 导致「能看见但点不到」。48dp 是 Android 推荐的最小可触控尺寸，
         * 短边不足时由 [OverlayGeometry.computeContentWindowSize] 抬到该值——
         * 此时窗口比内容略大，但仍在形体周围，不会形成大面积死区。
         */
        private const val MIN_TOUCH_SIDE_DP = 48f

        /**
         * 主窗口 addView 的最大尝试次数。
         *
         * 内容恢复时窗口可能撞上 WindowManager 的瞬时异常
         *（BadTokenException / "view already added"——转屏、解锁、
         * 权限页返回的瞬间都可能出现），单次失败就放弃会让窗口永久缺席。
         */
        private const val ATTACH_RETRY_COUNT = 3

        /** 看门狗对账周期：窗口缺席且内容就绪时按此间隔尝试重挂 */
        private const val WATCHDOG_INTERVAL_MS = 3000L

        /**
         * 窗口宽/高相对屏幕尺寸的上限比例。
         *
         * 放大到 3 倍时窗口可能超过屏幕，必须夹取到可用区域内，
         * 否则 WindowManager 会把窗口裁掉或顶到屏幕外。
         */
        private const val MAX_WINDOW_SCREEN_RATIO = 0.98f

        /**
         * Android 12+ 取不到系统阈值时的兜底上限（[InputManager.getMaximumObscuringOpacityForTouch]
         * 在 Android 12 上为 0.8）。
         */
        private const val FALLBACK_MAX_OBSCURING_OPACITY = 0.8f

        /**
         * 辅助气泡窗口的遮挡图层数。
         *
         * 它是纯 Compose 文本气泡，没有 SurfaceView，所以只有窗口本身这一层。
         * 若将来给它加 SurfaceView/视频渲染，这里必须同步 +1。
         */
        private const val AUXILIARY_OBSCURING_LAYERS = 1

        /**
         * 组合完成后再对账 alpha 的延迟。
         *
         * SD 小人的 GLSurfaceView 是 Compose 组合后才挂进视图树的：addView 那一刻
         * 图层数还是 1（alpha=0.79），子图层出现后合并不透明度会变成 0.96 并被系统拦下，
         * 因此必须在首帧之后重算一次（0.54）。
         */
        private const val LAYER_RECHECK_DELAY_MS = 300L

        private val isRunning = AtomicBoolean(false)
        fun isServiceRunning(): Boolean = isRunning.get()
    }

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        // 不在这里发布 overlayRunning=true：以「窗口是否真的挂上」为准，
        // 见 [publishOverlayWindowVisible]。服务活着但窗口没挂上时，
        // 应用内小人与悬浮窗必须至少有一个可见。
        Log.d(TAG, "onCreate: 服务创建")
        // 先取当前真实状态：服务可能在熄屏时被拉起（开机自启/后台恢复），
        // 此时不应先按「亮屏」渲染一帧再被广播纠正
        screenInteractiveState.value = isScreenInteractive()
        registerScreenStateReceiver()
        startForegroundServiceNotification()
        // 窗口创建移入协程：先读取上次保存的位置再 addView，
        // 小人直接出现在用户上次摆放的位置（而非先出现在默认位再跳转）
        serviceScope.launch { showOverlay() }
        // 【与组合生命周期解耦的观察链】内容有无 / 穿透开关由 Service 级协程驱动，
        // 不再依赖 ComposeView 内部的 LaunchedEffect（见 startOverlayStateObservers）
        startOverlayStateObservers()
    }

    /** 当前屏幕是否处于交互状态（PowerManager 不可用时按「亮屏」处理，不影响功能） */
    private fun isScreenInteractive(): Boolean =
        runCatching {
            (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
        }.getOrDefault(true)

    /**
     * 注册息屏/亮屏广播（幂等）。
     *
     * 用 [ContextCompat.registerReceiver] 统一 API 分支：Android 14+ 对动态注册
     * 后台广播强制要求 flag，缺了直接 SecurityException。个别 ROM 仍在后台限制
     * 广播投递，注册失败只意味着「熄屏不停帧」（多耗电、不丢功能），记一条 warning。
     */
    private fun registerScreenStateReceiver() {
        if (screenStateReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val interactive = when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> true
                    Intent.ACTION_SCREEN_OFF -> false
                    else -> return
                }
                updateScreenInteractive(interactive)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching {
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onSuccess {
            screenStateReceiver = receiver
            Log.d(TAG, "registerScreenStateReceiver: 息屏/亮屏监听已注册")
        }.onFailure {
            Log.w(TAG, "registerScreenStateReceiver: 注册失败，息屏停帧不可用", it)
        }
    }

    /** 更新屏幕交互状态；仅在真正变化时写 Compose State（避免无谓重组） */
    private fun updateScreenInteractive(interactive: Boolean) {
        if (screenInteractiveState.value == interactive) return
        screenInteractiveState.value = interactive
        Log.d(TAG, "updateScreenInteractive: interactive=$interactive")
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

    // ───────────────────────────────────────────────────────────────────────────
    // 主窗口尺寸与 flags 的唯一计算入口
    // ───────────────────────────────────────────────────────────────────────────

    /**
     * 主窗口基础 flags（不含穿透位）：
     * - FLAG_NOT_FOCUSABLE：不抢占输入焦点
     * - FLAG_LAYOUT_IN_SCREEN：允许覆盖状态栏区域
     * - FLAG_NOT_TOUCH_MODAL：窗口**外**事件穿透到下层 App
     * - FLAG_LAYOUT_NO_LIMITS：允许 y 为负（小人可拖到屏幕上方）
     */
    private fun baseWindowFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            // 【硬件加速】WindowManagerGlobal.addView 会给「无父窗口的独立窗口」自动补这一位
            // （parentWindow == null && ApplicationInfo.FLAG_HARDWARE_ACCELERATED），
            // 但它补的是调用方传进去的那个 LayoutParams 对象；而 [applyWindowFlags] 等路径
            // 用 `lp.flags = windowFlagsFor(...)` **整体赋值**，会把这个位连同其它系统
            // 补的位一起抹掉，窗口随即退化成软件绘制（GLSurfaceView 走 CPU 拷贝，掉帧、发烫）。
            // 这里显式写进 base flags，让每次重放都保持硬件加速。
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    /** 按穿透状态算出完整 flags（幂等纯函数） */
    private fun windowFlagsFor(passthrough: Boolean): Int =
        if (passthrough) {
            baseWindowFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            baseWindowFlags()
        }

    /**
     * 系统允许的「最大遮蔽不透明度」——Android 12 起穿透触摸的硬门槛。
     *
     * 【行为变更】Android 12（API 31）引入 untrusted touch：
     * 触摸要**穿过**一个窗口落到另一个应用时，若该窗口不是可信窗口，
     * InputDispatcher 会直接丢弃事件。豁免条件只有三条：
     * 1) 同 UID（= 应用内，这就是"穿透只在应用内生效"的原因）；
     * 2) 窗口 alpha ≤ [InputManager.getMaximumObscuringOpacityForTouch]（当前 0.8）；
     * 3) 系统可信浮层（无障碍/输入法）。
     *
     * 因此 `FLAG_NOT_TOUCHABLE` 只是必要条件：窗口还必须"足够透明"。
     * 注意阈值来自窗口级 alpha（[WindowManager.LayoutParams.alpha]），
     * 改 View/compose 的 alpha 无效——InputDispatcher 读的是 InputWindowInfo，
     * 它由 WindowState 依据 `LayoutParams.alpha` 填充。
     *
     * 多窗口在同一触点重叠时按 1 - Π(1-αᵢ) 合成，所以主窗与气泡窗不能叠在一起
     *（见 [OverlayGeometry.resolveAuxiliaryY]）。
     */
    private val maxObscuringOpacityForTouch: Float by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // Android 12 之前没有 untrusted touch 限制，窗口可以完全不透明
            1f
        } else {
            runCatching {
                (getSystemService(Context.INPUT_SERVICE) as? InputManager)
                    ?.maximumObscuringOpacityForTouch
            }.getOrNull()
                ?.takeIf { it.isFinite() && it > 0f }
                ?: FALLBACK_MAX_OBSCURING_OPACITY
        }
    }

    /**
     * 主窗口的窗口级 alpha。
     *
     * - 穿透关闭：1.0（完全不透明，视觉最佳）
     * - 穿透开启：按「本应用在该触点上贡献的遮挡图层数」摊薄，
     *   否则跨应用触摸会被 InputDispatcher 丢弃，表现为"穿透只在应用内生效"
     *
     * 【为什么不能只写 0.79】SD 小人用 `SpineSdGlSurfaceView`（GLSurfaceView）渲染，
     * 它会额外生成一条 BLAST 子图层，遮挡统计里继承父窗口 alpha，于是
     * `1-(1-0.79)² = 0.96 > 0.80`，触摸照样被丢。实测日志：
     *
     * ```
     * W InputDispatcher: Untrusted touch due to occlusion by <pkg>/<uid>
     *                    (obscuring opacity = 0.96, maximum allowed = 0.80)
     *   * alpha=0.79 window={SurfaceView[](BLAST)#1116}
     *   * alpha=0.79 window={<pkg>}
     * ```
     *
     * 所以 alpha 必须由 [countObscuringLayers] 算出的图层数决定（2 层 → 0.54）。
     * 代价是小人更透一些——这是系统防 tapjacking 的强制取舍，没有别的开关可以绕开。
     */
    private fun windowAlphaFor(passthrough: Boolean, obscuringLayerCount: Int): Float = when {
        !passthrough -> 1f
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> 1f
        else -> OverlayGeometry.tapThroughWindowAlpha(
            maxObscuringOpacity = maxObscuringOpacityForTouch,
            obscuringLayerCount = obscuringLayerCount
        )
    }

    /** 主窗口（含其子视图）在 InputDispatcher 里贡献的遮挡图层数。 */
    private fun mainWindowObscuringLayers(): Int = countObscuringLayers(composeView)

    /**
     * 统计一棵视图树在遮挡计算里占几层。
     *
     * 普通 View 与父窗口共用同一个 Surface → 1 层；`SurfaceView`（含 GLSurfaceView）
     * 拥有独立 Surface，在 InputDispatcher 里是**单独一条遮挡条目**（即使
     * `NO_INPUT_CHANNEL`、touchableRegion 为空、并不接收触摸），并且在遮挡统计时
     * 继承父窗口的 alpha。所以图层数 = 1 + 树里的 SurfaceView 数量。
     *
     * 递归遍历成本极低（视图树只有个位数节点），每次应用 alpha 时现算，
     * 避免缓存与组合/销毁竞态。
     */
    private fun countObscuringLayers(root: View?): Int {
        if (root == null) return 1
        var surfaceViewCount = 0
        fun walk(view: View) {
            if (view is SurfaceView) surfaceViewCount++
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) walk(view.getChildAt(index))
            }
        }
        walk(root)
        return 1 + surfaceViewCount
    }

    /**
     * 把穿透真值同时写进 `lp.flags` 与 `lp.alpha`（唯一入口，避免只改一半）。
     *
     * flags 与 alpha 必须成对更新：只加 FLAG_NOT_TOUCHABLE 而不降 alpha，
     * 在 Android 12+ 上等价于"没穿透"（事件被系统拦下）；只降 alpha 不加 flag，
     * 则窗口仍然吃掉触摸。两者缺一不可。
     */
    private fun applyWindowTouchAttributes(lp: WindowManager.LayoutParams) {
        lp.flags = windowFlagsFor(touchPassthrough)
        lp.alpha = windowAlphaFor(touchPassthrough, mainWindowObscuringLayers())
    }

    /**
     * 把当前穿透状态（flags + 窗口 alpha）重放到窗口。
     *
     * 幂等且可重复调用：addView 之前、updateViewLayout 之后、转屏重建、
     * re-add 前都可以安全调用；`lp` 已正确时不触发多余布局。
     *
     * 这是修复「触摸穿透偶发失效」的关键——旧实现只在
     * `LaunchedEffect(overlayTouchPassthrough)` 一处写 flags，
     * 窗口被移除后重新 addView（僵尸窗口修复）、转屏、缩放重建时
     * 都会回到不带 FLAG_NOT_TOUCHABLE / 满 alpha 的默认状态。
     */
    private fun applyWindowFlags(forceUpdate: Boolean = false) {
        val lp = overlayLayoutParams ?: return
        val desiredFlags = windowFlagsFor(touchPassthrough)
        val desiredAlpha = windowAlphaFor(touchPassthrough, mainWindowObscuringLayers())
        if (!forceUpdate && lp.flags == desiredFlags && lp.alpha == desiredAlpha) return
        lp.flags = desiredFlags
        lp.alpha = desiredAlpha
        Log.d(TAG, "applyWindowFlags: flags=0x${desiredFlags.toString(16)} alpha=$desiredAlpha")
        val view = composeView ?: return
        if (mainWindowDetached) return
        runCatching { windowManager?.updateViewLayout(view, lp) }
            .onFailure { Log.w(TAG, "applyWindowFlags: updateViewLayout 失败（穿透=$touchPassthrough）", it) }
    }

    /**
     * 组合首帧之后重新对账 flags + alpha。
     *
     * SD 小人的 GLSurfaceView 是 Compose 组合完成、下一帧才挂进视图树的：
     * addView 那一刻 [countObscuringLayers] 只看到 1 层（alpha=0.79），
     * 子图层出现后合成不透明度变成 0.96 又会被系统拦下，所以必须在首帧后重算（0.54）。
     * [applyWindowFlags] 自带比较，图层数没变时不会触发多余布局。
     */
    private fun scheduleTouchAttributeRecheck() {
        val view = composeView ?: return
        view.post { applyWindowFlags() }
        view.postDelayed({ applyWindowFlags() }, LAYER_RECHECK_DELAY_MS)
    }

    /**
     * 计算主窗口像素尺寸：以「可见内容宽高比」为准，而不是固定 110×165dp。
     *
     * 旧实现把窗口固定成 CHIBI_BASE_WIDTH/HEIGHT × displayScale：
     * - 宽扁型骨骼（横躺/带大翅膀/武器）在窗口内只占很小一块，
     *   视觉上"怎么放大都很小"；窗口透明部分却全额占用触摸面积，
     *   且随 displayScale 一起放大。
     * - 高瘦型骨骼则被宽度挤压。
     *
     * 现在窗口高度仍以 [CHIBI_BASE_HEIGHT] × displayScale 为基准，
     * 宽度按内容宽高比推导；短边有下限保证可点，长边不超过屏幕。
     */
    private fun computeWindowSize(displayScale: Float): Pair<Int, Int> {
        val density = resources.displayMetrics.density
        val dm = resources.displayMetrics
        val baseWidthPx = (CHIBI_BASE_WIDTH.value * density).toInt()
        val baseHeightPx = (CHIBI_BASE_HEIGHT.value * density).toInt()
        val minSidePx = (MIN_TOUCH_SIDE_DP * density).toInt()
        return OverlayGeometry.computeContentWindowSize(
            contentAspect = contentAspectState.value,
            baseWidth = baseWidthPx,
            baseHeight = baseHeightPx,
            displayScale = displayScale,
            minSide = minSidePx,
            maxWidth = (dm.widthPixels * MAX_WINDOW_SCREEN_RATIO).toInt(),
            maxHeight = (dm.heightPixels * MAX_WINDOW_SCREEN_RATIO).toInt()
        )
    }

    @Suppress("DEPRECATION")
    private suspend fun showOverlay() {
        Log.d(TAG, "showOverlay: 开始显示悬浮窗（双窗口架构）")

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            // 【重启偏移修复】主窗口初始尺寸必须与持久化的 sdScale 一致。
            // 旧实现固定按 baseSize（sdScale=1.0）建窗：用户上次放大后保存的位置是
            // 大窗口左上角，先按小窗恢复、再由 updateMainWindowSize 以"小窗中心"为基准
            // 缩放，最终小人中心整体向左上偏移 (新尺寸-旧尺寸)/2，表现为放大后
            // 重启/重开悬浮窗时位置异常偏移。
            val savedScale = playerSettings.secretarySdScale.first()
                .coerceIn(SECRETARY_SD_SCALE_MIN, SECRETARY_SD_SCALE_MAX)
            // 【穿透真值先行】首次建窗就要按持久化开关决定 flags，
            // 否则服务重启后第一帧到 LaunchedEffect 生效之间会漏一段时间（穿透过早/过晚）。
            touchPassthrough = runCatching {
                playerSettings.secretaryOverlayTouchPassthrough.first()
            }.getOrDefault(false)
            // 尺寸按「可见内容宽高比」推导（contentAspect 未知时退化为基础尺寸）
            val (overlayWidthPx, overlayHeightPx) = computeWindowSize(savedScale)

            // 主窗口 LayoutParams：
            // - FLAG_NOT_FOCUSABLE：不抢占输入焦点
            // - FLAG_LAYOUT_IN_SCREEN：允许窗口覆盖状态栏区域
            // - FLAG_NOT_TOUCH_MODAL：窗口外事件穿透到下层 App
            // - FLAG_LAYOUT_NO_LIMITS：允许窗口位置超出屏幕边界（y 可为负），
            //   使小人可拖动到屏幕上方靠近状态栏区域。
            //   安全性保障：SpineSdView 的 OnTouchListener 使用 event.rawX/rawY
            //   （屏幕绝对坐标）计算拖动偏移，不依赖 view 自身位置，因此窗口超出
            //   屏幕边界不会导致触摸坐标异常或拖动卡死。
            //   穿透位由 [windowFlagsFor] 统一决定（读持久化的用户开关），
            //   避免"服务重启后要等 LaunchedEffect 才生效"的空窗期。
            val params = WindowManager.LayoutParams(
                overlayWidthPx,
                overlayHeightPx,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                windowFlagsFor(touchPassthrough),
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            // 【跨应用穿透的前提】窗口 alpha 必须一并按穿透开关落地：
            // Android 12+ 只放行 alpha ≤ 0.8 的窗口把触摸交给下层应用，
            // 满 alpha + FLAG_NOT_TOUCHABLE 的事件会被 InputDispatcher 直接丢弃
            // （只在本应用内生效，正是用户报的"桌面/其他应用穿不过去"）。
            params.alpha = windowAlphaFor(touchPassthrough, mainWindowObscuringLayers())
            val (posX, posY) = resolveWindowPosition(overlayWidthPx, overlayHeightPx)
            params.x = posX
            params.y = posY
            overlayLayoutParams = params

            lifecycleOwner = FloatingWindowLifecycleOwner()
            lifecycleOwner?.attachToDecor()

            composeView = ComposeView(this).apply {
                // 【核心修复】默认策略是 DisposeOnDetachedFromWindowOrReleasedFromPool：
                // [removeMainWindowTemporarily] 里的一次 removeView 就会把整棵组合销毁，
                // setContent 内的 LaunchedEffect(hasChibiContent) 随之消失，内容恢复后
                // 再没有任何路径去重新 addView —— 窗口从此永久缺席。
                // 这正是「悬浮窗只在应用内生效，回桌面/切其他应用就没了」的根因之一。
                // 改为随 LifecycleOwner（Service 持有）销毁，窗口增删不再影响组合。
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                )
                setViewTreeLifecycleOwner(lifecycleOwner!!)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner!!)
                setViewTreeViewModelStoreOwner(lifecycleOwner!!)

                setContent {
                    BlyyTheme {
                        // WithLifecycle — 悬浮窗生命周期恒为 RESUMED（FloatingWindowLifecycleOwner），
                        // 行为与裸 collectAsState 等价，统一收集口径
                        val secretaryState by secretaryManager.state.collectAsStateWithLifecycle()

                        // 拖动状态（Compose State，让 LaunchedEffect 感知变化）
                        var isDragging by remember { mutableStateOf(false) }

                        // 当前对话内容（dialogueEnabled=false 时传 null 隐藏气泡）
                        val dialogue = if (secretaryState.dialogueEnabled) secretaryState.currentDialogue else null

                        // 监听 sdScale 变化：动态调整主窗口尺寸，保持小人中心位置不变
                        LaunchedEffect(secretaryState.sdScale) {
                            updateMainWindowSize(secretaryState.sdScale)
                        }

                        // 监听可见内容宽高比变化：Spine 首次解码完成 / 切换皮肤后
                        // 内容形状可能改变，窗口尺寸要跟着重算（旧实现窗口尺寸与
                        // 模型无关，宽扁骨骼会留下大片透明触摸区）
                        val reportedContentAspect by contentAspectState
                        LaunchedEffect(reportedContentAspect) {
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
                            secretaryState.sdResourceId.isNotEmpty() ||
                            // 只有「游戏资源导入」的 SD 时同样要显示
                            secretaryState.sdAvailable

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
                                hasSdBundle = secretaryState.sdAvailable,
                                // 触摸穿透：Composable 内部把它接到 touchEnabled，
                                // 与窗口级 FLAG_NOT_TOUCHABLE 双保险
                                touchPassthrough = secretaryState.overlayTouchPassthrough,
                                // 帧率档位：设置页切换后经 Manager → DataStore → State 实时下发
                                frameRate = secretaryState.sdFrameRate,
                                // 熄屏停帧：系统悬浮窗拿不到 onPause，只能由 Service 广播驱动
                                renderingEnabled = screenInteractiveState.value,
                                // 可见内容宽高比上报：Service 据此收紧窗口尺寸
                                onContentAspect = { aspect -> contentAspectState.value = aspect },
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
            // 建窗时同步穿透真值：避免"窗口已 addView 但 flags 尚未生效"的窗口期消费事件
            handler.passthrough = touchPassthrough
            composeView?.setOnTouchListener(handler)

            // 【先看内容再建窗】内容未就绪时不要 addView：旧实现先挂窗口、
            // 再因 hasChibiContent=false 立刻 removeView —— 既闪一下，
            // 又走了销毁组合的那条路径。
            if (hasRenderableContent()) {
                windowManager?.addView(composeView, params)
                mainWindowDetached = false
                publishOverlayWindowVisible(true)
                scheduleTouchAttributeRecheck()
                Log.d(
                    TAG,
                    "showOverlay: 主窗口添加成功 (尺寸=${overlayWidthPx}x${overlayHeightPx}px, " +
                        "pos=($posX,$posY)) passthrough=$touchPassthrough"
                )
            } else {
                // 内容到位后由 [startOverlayStateObservers] 的观察者挂载
                mainWindowDetached = true
                Log.d(TAG, "showOverlay: 内容未就绪，延后建窗（等 SecretaryManager 上报 sdAvailable）")
            }

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
        val displayScale = sdScale.coerceIn(SECRETARY_SD_SCALE_MIN, SECRETARY_SD_SCALE_MAX)
        // 窗口尺寸 = 可见内容形状 × 显示倍率（contentAspect 未知时退化为基础尺寸），
        // 不再固定 110×165dp——否则宽扁骨骼视觉过小、且留下大片透明触摸区
        val (newWidth, newHeight) = computeWindowSize(displayScale)

        if (lp.width == newWidth && lp.height == newHeight) {
            // 尺寸没变也要保证 flags 与当前穿透开关一致（防止某条路径漏重放）
            applyWindowFlags()
            return
        }

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

        // 尺寸变化时一并重放 flags + 窗口 alpha：updateViewLayout 以 lp 为准，
        // 若 lp 曾在别处被改回基础值，这里会立刻纠正
        applyWindowTouchAttributes(lp)
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
     * 动态切换主窗口触摸穿透模式（`overlayTouchPassthrough` 变化时调用）。
     *
     * - touchPassthrough=true：追加 [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE]，
     *   主窗口所有触摸事件直接穿透到下层 App，SD 小人变为纯展示模式
     *   （不可点击/拖动/缩放）。显示层、语音播放、动画运行不受影响。
     * - touchPassthrough=false：移除 FLAG_NOT_TOUCHABLE，恢复正常交互。
     *   形体外的空白区域触摸穿透由 [SpineSdGlSurfaceView] 的逐部件几何命中处理。
     *
     * 状态写入 [touchPassthrough] 后统一由 [applyWindowFlags] 落盘，
     * 保证后续任何一次 addView / updateViewLayout 都沿用同一真值。
     */
    private fun updateMainWindowTouchMode(touchPassthrough: Boolean) {
        if (this.touchPassthrough == touchPassthrough) {
            // 值没变也强制重放一次：窗口可能刚被移除/重建，flags 已回到默认值
            applyWindowFlags(forceUpdate = true)
        } else {
            this.touchPassthrough = touchPassthrough
            applyWindowFlags(forceUpdate = true)
        }
        // 视图层双保险：即使窗口级 FLAG_NOT_TOUCHABLE 因系统原因未生效，
        // 手势处理器也不会消费事件（修复"偶发失效"）
        gestureHandler?.passthrough = touchPassthrough
        Log.d(TAG, "updateMainWindowTouchMode: touchPassthrough=$touchPassthrough, flags=${overlayLayoutParams?.flags}")
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
        if (!mainWindowDetached) {
            // 已经在窗口上：顺手校正对外状态，避免状态被别的路径写脏
            publishOverlayWindowVisible(true)
            return
        }
        mainWindowDetached = false
        // 服务存活期间的移除→恢复沿用原位置；首次显示（onCreate 时无内容）走持久化解析
        if (lp.width <= 0 || lp.height <= 0) {
            rebuildLayoutParamsFromPersisted(lp)
        }
        var attempt = 0
        var lastError: Exception? = null
        while (attempt < ATTACH_RETRY_COUNT) {
            attempt++
            // 每次 re-add 前都重放 flags + 窗口 alpha：窗口被移除期间穿透开关可能改过，
            // 沿用旧 lp 会让穿透在 re-add 后失效（历史 bug；Android 12+ 还要求 alpha ≤ 0.8）
            applyWindowTouchAttributes(lp)
            try {
                windowManager?.addView(view, lp)
                publishOverlayWindowVisible(true)
                scheduleTouchAttributeRecheck()
                Log.d(
                    TAG,
                    "ensureMainWindowAttached: 主窗口重新挂载 attempt=$attempt " +
                        "pos=(${lp.x},${lp.y}) passthrough=$touchPassthrough"
                )
                return
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "ensureMainWindowAttached: addView 失败 attempt=$attempt", e)
                if (attempt < ATTACH_RETRY_COUNT) delay(200L * attempt)
            }
        }
        mainWindowDetached = true
        publishOverlayWindowVisible(false)
        Log.e(TAG, "ensureMainWindowAttached: 重试 $ATTACH_RETRY_COUNT 次仍失败", lastError)
    }

    /** 内容为空时移除主窗口（窗口/ComposeView/GL 资源全部释放，view 对象保留供恢复） */
    private fun removeMainWindowTemporarily() {
        val view = composeView ?: return
        if (mainWindowDetached) return
        mainWindowDetached = true
        publishOverlayWindowVisible(false)
        try {
            windowManager?.removeView(view)
            Log.d(TAG, "removeMainWindowTemporarily: 主窗口移除（无内容）")
        } catch (e: Exception) {
            Log.e(TAG, "removeMainWindowTemporarily: removeView failed", e)
        }
    }

    /** 按持久化 sdScale/位置重建 LayoutParams 尺寸与落点（尺寸合法后仍需 clamp） */
    private suspend fun rebuildLayoutParamsFromPersisted(lp: WindowManager.LayoutParams) {
        val savedScale = playerSettings.secretarySdScale.first()
            .coerceIn(SECRETARY_SD_SCALE_MIN, SECRETARY_SD_SCALE_MAX)
        val (width, height) = computeWindowSize(savedScale)
        lp.width = width
        lp.height = height
        applyWindowTouchAttributes(lp)
        val (posX, posY) = resolveWindowPosition(lp.width, lp.height)
        lp.x = posX
        lp.y = posY
    }

    // ───────────────────────────────────────────────────────────────────────────
    // 内容 / 穿透观察链（与 ComposeView 组合生命周期解耦）
    // ───────────────────────────────────────────────────────────────────────────

    /** 当前是否存在可渲染的秘书舰内容（与 setContent 内 hasChibiContent 判定一致） */
    private fun hasRenderableContent(): Boolean {
        val state = secretaryManager.state.value
        return state.figureUrl.isNotEmpty() ||
            state.webChibiUrl.isNotEmpty() ||
            state.sdResourceId.isNotEmpty() ||
            // 只有「游戏资源导入」的 SD 时同样要显示
            state.sdAvailable
    }

    /**
     * Service 级观察者：主窗口是否需要挂载、穿透开关是否变化，都由这里驱动。
     *
     * 【为什么必须放在 Service 层】旧实现把这两件事写在 ComposeView 的 setContent 里
     *（LaunchedEffect(hasChibiContent) / LaunchedEffect(overlayTouchPassthrough)）。
     * 组合一旦因 removeView 被销毁（默认 ViewCompositionStrategy），观察链就跟着消失：
     * 内容恢复后没人重新挂窗口，穿透开关也再没人响应 —— 用户看到的就是
     *「悬浮窗只在应用内生效，在桌面/其他应用里失效」。
     *
     * 观察者挂在 [serviceScope] 上，与视图增删完全无关，因此窗口被移除后依然存活，
     * 只要内容回来就能把窗口重新挂上（配合 ATTACH_RETRY_COUNT 的重试）。
     */
    private fun startOverlayStateObservers() {
        // 主窗口存在性
        serviceScope.launch {
            secretaryManager.state
                .map { state ->
                    state.figureUrl.isNotEmpty() ||
                        state.webChibiUrl.isNotEmpty() ||
                        state.sdResourceId.isNotEmpty() ||
                        state.sdAvailable
                }
                .distinctUntilChanged()
                .collect { present ->
                    Log.d(TAG, "observeOverlayState: hasRenderableContent=$present, detached=$mainWindowDetached")
                    if (present) {
                        ensureMainWindowAttached()
                    } else {
                        removeMainWindowTemporarily()
                    }
                }
        }
        // 触摸穿透（窗口级 FLAG_NOT_TOUCHABLE + 手势处理器双保险）
        serviceScope.launch {
            secretaryManager.state
                .map { state -> state.overlayTouchPassthrough }
                .distinctUntilChanged()
                .collect { passthrough ->
                    updateMainWindowTouchMode(passthrough)
                }
        }
        // 【看门狗】兜底自愈：WindowManager 可能在极端情况下自行移除窗口
        //（转屏、锁屏解锁、系统内存回收），或者 addView 与内容就绪撞上竞态；
        // 上面的观察者是 distinctUntilChanged，值不变不会重发信号，这里按周期对账。
        serviceScope.launch {
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                if (mainWindowDetached && hasRenderableContent()) {
                    Log.d(TAG, "watchdog: 窗口缺席但内容就绪，尝试重新挂载")
                    ensureMainWindowAttached()
                } else if (!mainWindowDetached) {
                    // 图层数会随内容切换（静态图 ↔ Spine 的 GLSurfaceView）而变化：
                    // 周期性对账 alpha，保证任何时候合并不透明度都在系统阈值以内
                    applyWindowFlags()
                }
            }
        }
    }

    /**
     * 发布「悬浮窗是否真的在屏幕上」。
     *
     * 只在值变化时才写回 SecretaryManager，避免每帧刷新 UI 状态。
     */
    private fun publishOverlayWindowVisible(visible: Boolean) {
        if (overlayWindowVisible == visible) return
        overlayWindowVisible = visible
        secretaryManager.updateOverlayRunning(visible)
        Log.d(TAG, "publishOverlayWindowVisible: overlayRunning=$visible")
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
     *
     * 【窗口 alpha】辅助窗口**始终**是 FLAG_NOT_TOUCHABLE 的纯展示层，
     * 因此它任何时刻都必须满足 Android 12+ 的穿透不透明度上限，
     * 否则气泡/拖动提示那一小块区域会静默吞掉对下层应用的点击
     *（穿透开关关闭时也一样——它本来就不接收触摸，却仍会遮挡）。
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
        // 纯展示窗口：始终按穿透合规的不透明度渲染（见 windowAlphaFor 注释）
        params.alpha = windowAlphaFor(passthrough = true, obscuringLayerCount = AUXILIARY_OBSCURING_LAYERS)

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
        // x clamp：辅宽已知时限制在屏幕内（未知时中心定位，等待测量回调纠正）
        auxLp.x = if (auxWidthPx > 0) {
            desiredX.coerceIn(0, (dm.widthPixels - auxWidthPx).coerceAtLeast(0))
        } else {
            desiredX
        }
        // y：优先贴主窗上方；顶部空间不足时改放主窗下方；都放不下才允许重叠（见纯函数注释）。
        // 【为什么必须尽量不重叠】Android 12+ 同一触点被多个窗口遮挡时按
        // 1-(1-a1)(1-a2) 合成不透明度，两个 0.79 叠加 = 0.956 > 0.8，
        // 该点触摸会被系统判定为"不可信"直接丢弃 —— 穿透就此失效。
        auxLp.y = OverlayGeometry.resolveAuxiliaryY(
            mainTop = mainLp.y,
            mainHeight = mainLp.height,
            auxHeight = auxHeightPx,
            screenHeight = dm.heightPixels
        )

        // 重叠告警：屏幕实在放不下时（小人几乎占满屏幕）只能压在一起，
        // 该区域合成 alpha 超限，穿透会被系统拦掉 —— 留日志便于定位反馈
        if (OverlayGeometry.auxiliaryOverlapsMain(
                mainTop = mainLp.y,
                mainHeight = mainLp.height,
                auxTop = auxLp.y,
                auxHeight = auxHeightPx
            )
        ) {
            Log.w(
                TAG,
                "updateAuxiliaryPosition: 气泡与主窗重叠（y=${auxLp.y}, mainY=${mainLp.y}, " +
                    "auxH=$auxHeightPx, mainH=${mainLp.height}），该区域穿透可能被系统拦下"
            )
        }

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
        // 转屏/分屏后重放 flags：部分 ROM 在 configuration change 后会按默认值
        // 重建窗口属性，若不重放，触摸穿透会"突然失效"（历史 bug）
        applyWindowFlags(forceUpdate = true)
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

    /**
     * 【保留悬浮窗】用户从最近任务划掉 App 时**不再**停止服务。
     *
     * 旧实现 stopSelf()：划掉任务 = 悬浮窗一起消失，配合"服务活着但窗口没挂上"
     * 的组合销毁 bug，用户看到的就是「悬浮窗只在应用内生效」。
     * 秘书舰属于屏幕常驻型功能（前台服务 + 常驻通知），划掉最近任务应当只关掉
     * Activity，悬浮窗继续陪伴；要关闭请用通知里的「关闭悬浮窗」按钮。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved: 保留悬浮窗（前台服务继续运行）")
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
            // 注销息屏/亮屏接收器
            screenStateReceiver?.let {
                runCatching { unregisterReceiver(it) }
            }
            screenStateReceiver = null
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
            // ContextCompat 统一处理 API 分支：<33 走无 flag 注册，>=33 传 RECEIVER_NOT_EXPORTED
            // （Android 14+ 对 targetSdk U+ 的应用强制要求 flag，缺了直接 SecurityException）
            ContextCompat.registerReceiver(
                this, closeActionReceiver,
                IntentFilter(ACTION_CLOSE_OVERLAY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        Log.d(TAG, "startForegroundServiceNotification: 前台服务启动成功")
    }
}
