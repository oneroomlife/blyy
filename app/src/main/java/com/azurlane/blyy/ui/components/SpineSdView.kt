package com.azurlane.blyy.ui.components

import android.content.Context
import android.content.ContextWrapper
import android.graphics.PixelFormat
import android.graphics.RectF
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.badlogic.gdx.AbstractGraphics
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.android.DefaultAndroidFiles
import com.badlogic.gdx.backends.android.AndroidGL20
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.glutils.GLVersion
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.FloatArray
import com.badlogic.gdx.utils.GdxNativesLoader
import com.esotericsoftware.spine.Animation
import com.esotericsoftware.spine.AnimationState
import com.esotericsoftware.spine.AnimationStateData
import com.esotericsoftware.spine.Skeleton
import com.esotericsoftware.spine.SkeletonBinary
import com.esotericsoftware.spine.SkeletonRenderer
import com.esotericsoftware.spine.attachments.AtlasAttachmentLoader
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.min

private const val TAG = "SpineSdView"

/**
 * Spine SD 小人 Compose 渲染组件。
 *
 * 在 [AndroidView] 内嵌一个 [GLSurfaceView]，使用 OpenGL ES 2.0 上下文渲染 Spine 3.8.99
 * 骨骼动画。libgdx 的 Gdx.gl/Gdx.files 静态字段在 GL context 创建后被同步初始化，
 * 因为 AndroidGL20 内部直接调用 [android.opengl.GLES20]，需要 GLSurfaceView 已绑定 GLContext。
 *
 * 资源从外部存储加载（用户导入到 Download/BLYY/blhx_sd/），通过 [Gdx.files.absolute]
 * 读取绝对路径的 .skel/.atlas/.png 三件套。
 *
 * 默认循环播放 idle 动画（stand/normal 等），点击视图时随机播放一个 action 动画
 * （attack/dance/touch 等），播放完毕后自动回到 idle 循环，过渡平滑。
 * 长按视图 500ms 后触发 "tuozhuai"（拖拽）动画循环播放，可在拖动状态下移动小人位置；
 * 释放后自动回到 idle 循环。
 * 点击同时通过 [onTap] 回调通知外部（用于播放语音等）。
 *
 * @param dirPath 资源目录绝对路径（如 /sdcard/Download/BLYY/blhx_sd/boge）
 * @param assetName 三件套主名（如 boge 或 boge_g），.skel 文件名为 $assetName.skel
 * @param modifier Compose 修饰符
 * @param scaleMultiplier 缩放倍率（1.0 = 默认自适应到视口 95%，0.5 = 半尺寸，1.5 = 放大 50%）；
 *   通过 AndroidView update 实时生效，无需重建 GLSurfaceView
 * @param onTap 点击小人回调（在 UI 线程触发，用于播放语音等）
 */
@Composable
fun SpineSdView(
    dirPath: String,
    assetName: String,
    modifier: Modifier = Modifier,
    scaleMultiplier: Float = 1f,
    /** 触摸交互开关（false=触摸穿透，应用内场景使用；系统悬浮窗场景保持默认 true） */
    touchEnabled: Boolean = true,
    onTap: (() -> Unit)? = null,
    onDragStart: (() -> Unit)? = null,
    onDrag: ((dx: Float, dy: Float) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // 保存上次资源路径，用于检测变化触发热重载（替代旧的 key() 重建方案）
    // 旧方案：key(dirPath, assetName) 变化时销毁重建整个 GLSurfaceView（EGL context 重建 ~100ms）
    // 新方案：update 中检测变化，调用 reloadAsset 在 GL 线程热重载 Spine 资源（~20ms，无闪烁）
    var lastDirPath by remember { mutableStateOf(dirPath) }
    var lastAssetName by remember { mutableStateOf(assetName) }

    val viewRef = remember { mutableStateOf<SpineSdGlSurfaceView?>(null) }

    AndroidView(
        factory = { ctx ->
            SpineSdGlSurfaceView(
                ctx = ctx,
                dirPath = dirPath,
                assetName = assetName,
                scaleMultiplier = scaleMultiplier,
                touchEnabled = touchEnabled,
                onTap = onTap,
                onDragStart = onDragStart,
                onDrag = onDrag,
                onDragEnd = onDragEnd
            ).also { viewRef.value = it }
        },
        update = { view ->
            // 实时更新缩放倍率（无需重建 GLSurfaceView）
            view.scaleMultiplier = scaleMultiplier
            // 实时更新触摸开关（应用内触摸穿透模式切换）
            view.touchEnabled = touchEnabled
            // 检测 Spine 资源路径变化（切换皮肤）：热重载，不重建 GLSurfaceView
            if (dirPath != lastDirPath || assetName != lastAssetName) {
                lastDirPath = dirPath
                lastAssetName = assetName
                view.reloadAsset(dirPath, assetName)
            }
        },
        modifier = modifier
    )

    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) { viewRef.value?.onResume() }
            override fun onPause(owner: LifecycleOwner) { viewRef.value?.onPause() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewRef.value?.onPause()
        }
    }
}

/**
 * Spine 渲染容器。继承 [GLSurfaceView]，配置 GLES2 + RGBA8888 + 透明 Surface。
 *
 * 使用 [setZOrderOnTop] 把 GL Surface 提到窗口顶部，配合 alpha=0 的 clear color
 * 实现透明背景，避免被同窗口的 ComposeView 遮挡。
 *
 * 点击视图时通过 [queueEvent] 在 GL 线程触发随机 action 动画，同时在 UI 线程
 * 调用 [onTap] 回调通知外部播放语音等。
 */
private class SpineSdGlSurfaceView(
    ctx: Context,
    dirPath: String,
    assetName: String,
    scaleMultiplier: Float,
    touchEnabled: Boolean,
    private val onTap: (() -> Unit)?,
    private val onDragStart: (() -> Unit)?,
    private val onDrag: ((Float, Float) -> Unit)?,
    private val onDragEnd: (() -> Unit)?
) : GLSurfaceView(ctx) {

    /** 缩放倍率，可实时更新（由 AndroidView update 回调设置），每帧由 SpineRenderer 读取 */
    var scaleMultiplier: Float = scaleMultiplier

    /**
     * 触摸交互开关（由 AndroidView update 回调设置）。
     *
     * - true（默认）：正常处理点击/拖动/长按，骨骼 bounds 外透明区域事件穿透
     * - false：OnTouchListener 直接返回 false，所有触摸事件穿透到下层（应用内触摸穿透模式）
     *
     * 系统悬浮窗场景下触摸穿透由 WindowManager FLAG_NOT_TOUCHABLE 控制，
     * 此字段保持 true（GLSurfaceView 本身仍可交互，但窗口不接收触摸）。
     */
    var touchEnabled: Boolean = touchEnabled

    /**
     * GLSurfaceView 最新尺寸缓存（px），由 [onSizeChanged] 在 UI 线程更新，
     * 供 GL 线程的 SpineRenderer 通过 [viewSizeProvider] 读取。
     *
     * 用 @Volatile 保证 UI 线程写入对 GL 线程立即可见，避免读到部分更新的值。
     * 首次 layout 前为 0，SpineRenderer 会回退到 onSurfaceChanged 的缓存值。
     */
    @Volatile private var liveWidth: Int = 0
    @Volatile private var liveHeight: Int = 0

    // 长按检测 Handler：在 init 块中调度长按回调，onDetachedFromWindow 中清理
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    // SpineRenderer 通过 lambda 每帧读取最新的 scaleMultiplier 和 view 尺寸，
    // 无需重建 GLSurfaceView 即可实时缩放且骨骼始终居中（绕过 onSurfaceChanged 异步延迟）
    private val spineRenderer = SpineRenderer(
        context.applicationContext,
        dirPath,
        assetName,
        scaleProvider = { scaleMultiplier },
        viewSizeProvider = { liveWidth to liveHeight }
    )

    /**
     * View 尺寸变化时缓存最新尺寸（UI 线程），供 GL 线程 SpineRenderer 实时读取。
     *
     * 覆盖此方法而非直接读 width/height 是为了：
     * 1. 用 @Volatile 字段保证跨线程可见性（View.width/height 本身非 volatile）
     * 2. 在尺寸变化瞬间立即更新，不依赖 GL 线程的 onSurfaceChanged 异步回调
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        liveWidth = w
        liveHeight = h
    }

    /**
     * 热重载 Spine 资源（切换皮肤时由 AndroidView update 回调调用）。
     *
     * 在 GL 线程执行 [SpineRenderer.reloadAsset]，复用现有 GLSurfaceView / EGL context，
     * 仅重新加载 Spine 三件套资源，避免重建 GLSurfaceView 的开销（EGL context 重建 ~100ms）。
     */
    fun reloadAsset(newDirPath: String, newAssetName: String) {
        queueEvent { spineRenderer.reloadAsset(newDirPath, newAssetName) }
    }

    init {
        setEGLContextClientVersion(2)
        // RGBA8888 + 无 depth/stencil（2D 渲染用不到深度）
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        setRenderer(spineRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY

        // 触摸处理：区分点击、快速拖动与长按拖动。
        // GLSurfaceView 设置 OnTouchListener 后会消费整个触摸序列，
        // 父级 Compose 的 pointerInput(detectDragGestures) 收不到事件，
        // 因此拖动必须在 GLSurfaceView 内部识别并回调外部，否则 SD 小人无法拖动。
        //
        // 【关键 1】必须使用 event.rawX / rawY（屏幕绝对坐标）计算位移，不能用 event.x / y。
        // 原因：event.x/y 是相对于 GLSurfaceView 自身的坐标。拖动时 onDrag 回调会更新
        // localOffsetX/Y，导致 GLSurfaceView 跟随手指移动。下一帧 ACTION_MOVE 时，
        // event.x/y 是相对于【已移动后的 GLSurfaceView】的坐标，而 lastX/lastY 是上一帧
        // 相对【旧位置】的坐标，两者参考系不同，dx = event.x - lastX 会接近 0，
        // 导致小人"卡住"——手指移动但小人不动或移动很慢。
        // rawX/rawY 始终是屏幕绝对坐标，参考系不变，dx/dy 能正确反映手指实际移动量。
        //
        // 【关键 2】ACTION_DOWN 时必须调用 parent.requestDisallowInterceptTouchEvent(true)，
        // 否则父容器（如可滚动的 NavHost 页面、verticalScroll 列表）会在手指移动时
        // 调用 onInterceptTouchEvent 拦截整个触摸序列，导致 GLSurfaceView 收到
        // ACTION_CANCEL 而非 ACTION_MOVE，拖动功能完全失效。
        // ACTION_UP/CANCEL 时恢复父容器拦截能力，避免影响其他交互。
        //
        // 【tuozhuai 动画统一触发】无论快速拖动（touchSlop 内启动）还是长按拖动（500ms 超时），
        // 进入拖动状态时（onDragStart 触发、显示"拖动调整位置"提示）都同步调用
        // triggerDragAnimation()，确保拖动反馈动画与 UI 提示同步出现，
        // 避免"直接拖动功能未触发 tuozhuai"的问题。
        // 拖动结束（ACTION_UP/CANCEL）统一调用 returnToIdle() 回到 idle 循环。
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        var downRawX = 0f
        var downRawY = 0f
        var lastRawX = 0f
        var lastRawY = 0f
        var dragging = false
        var isLongPressTriggered = false

        // 统一的拖动入口：通知外部 + 触发 tuozhuai 动画
        // 无论快速拖动还是长按拖动，都通过此函数进入拖动状态，确保 UI 提示与动画同步。
        fun enterDragState(longPress: Boolean) {
            if (dragging) return
            dragging = true
            isLongPressTriggered = longPress
            // 触觉反馈
            performHapticFeedback(
                if (longPress) HapticFeedbackConstants.LONG_PRESS
                else HapticFeedbackConstants.VIRTUAL_KEY
            )
            // 通知外部进入拖动状态（UI 显示"拖动调整位置"标签等）
            onDragStart?.invoke()
            // 在 GL 线程触发 tuozhuai 动画（循环播放）
            // 与"拖动调整位置"提示同步出现，避免直接拖动时无动画反馈
            queueEvent { spineRenderer.triggerDragAnimation() }
        }

        // 统一的拖动退出：回到 idle 动画 + 通知外部
        fun exitDragState() {
            if (!dragging) return
            // 拖动结束：在 GL 线程回到 idle 动画
            queueEvent { spineRenderer.returnToIdle() }
            onDragEnd?.invoke()
        }

        setOnTouchListener { v, event ->
            // 触摸穿透模式（应用内场景）：直接返回 false，所有触摸事件穿透到下层 UI
            // 系统悬浮窗场景下 touchEnabled 保持 true，触摸穿透由 FLAG_NOT_TOUCHABLE 控制
            if (!touchEnabled) return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 触摸穿透：仅当触摸点落在骨骼实际边界框内时才消费事件，
                    // 透明区域返回 false 让事件穿透到下层 App（系统悬浮窗场景）或父容器（内嵌场景）。
                    //
                    // 【优化前】用 touchAreaRatio 限制为中心矩形比例，粗糙且仍含大量透明区域；
                    // 且 OnTouchListener 末尾固定返回 true 消费整个序列，透明区域拦截下层触摸。
                    //
                    // 【优化后】用 SpineRenderer 每帧更新的骨骼边界框（idle 动画 bounds × scale）
                    // 精确判断触摸点是否在角色实际范围内：
                    // - 在 bounds 内 → 消费事件，正常处理点击/拖动
                    // - 在 bounds 外 → 返回 false，事件穿透到下层 App
                    //
                    // 骨骼边界框居中在 view 中心（SpineRenderer 的 skel.x/skel.y 计算保证），
                    // 透明区域 = view 矩形 - 骨骼 bounds 矩形，这部分事件全部穿透。
                    //
                    // bounds 未就绪（首次渲染前）默认消费，避免首次点击失效；
                    // 此时 view 还没显示内容，用户也不会点击到。
                    val bounds = spineRenderer.boundsInViewport
                    if (bounds != null && width > 0 && height > 0) {
                        val touchX = event.x  // 相对 view 的坐标
                        val touchY = event.y
                        if (touchX < bounds.left || touchX > bounds.right ||
                            touchY < bounds.top || touchY > bounds.bottom
                        ) {
                            // 触摸点在骨骼边界框外（透明区域），不消费事件，让下层 App 处理
                            return@setOnTouchListener false
                        }
                    }
                    // 阻止父容器拦截后续 MOVE/UP 事件，确保拖动序列完整送达 GLSurfaceView
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    downRawX = event.rawX; downRawY = event.rawY
                    lastRawX = event.rawX; lastRawY = event.rawY
                    dragging = false
                    isLongPressTriggered = false
                    // 调度长按检测：500ms 后未移动则触发长按拖动 + tuozhuai 动画
                    longPressRunnable = Runnable {
                        if (!dragging) {
                            enterDragState(longPress = true)
                        }
                    }
                    longPressHandler.postDelayed(longPressRunnable!!, longPressTimeout)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && (abs(event.rawX - downRawX) > touchSlop ||
                            abs(event.rawY - downRawY) > touchSlop)) {
                        // 在长按超时前检测到移动：取消长按，进入快速拖动模式
                        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                        longPressRunnable = null
                        // 同步触发 tuozhuai 动画（与"拖动调整位置"提示同步）
                        enterDragState(longPress = false)
                    }
                    if (dragging) {
                        onDrag?.invoke(event.rawX - lastRawX, event.rawY - lastRawY)
                    }
                    lastRawX = event.rawX; lastRawY = event.rawY
                }
                MotionEvent.ACTION_UP -> {
                    // 清除长按计时器
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    longPressRunnable = null
                    if (!dragging) {
                        // 点击：触发随机 action 动画 + 回调外部 onTap（播放语音等）
                        queueEvent { spineRenderer.triggerRandomAction() }
                        onTap?.invoke()
                        performClick()
                    } else {
                        // 拖动结束：统一回到 idle 动画（无论快速拖动还是长按拖动）
                        exitDragState()
                    }
                    // 恢复父容器拦截能力，避免影响后续其他交互（如页面滚动）
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    dragging = false
                    isLongPressTriggered = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    longPressRunnable = null
                    // 拖动被中断：统一回到 idle 动画
                    exitDragState()
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    dragging = false
                    isLongPressTriggered = false
                }
            }
            true
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        // 视图销毁时清理 Handler 回调，避免内存泄漏
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        longPressRunnable = null
        super.onDetachedFromWindow()
    }
}

/**
 * GLSurfaceView.Renderer 实现：负责初始化 libgdx 静态字段、加载 Spine 资源、每帧推进动画。
 *
 * libgdx 静态字段是 process-global 的，因此用 `if (Gdx.gl == null)` 守卫避免重复初始化。
 *
 * 动画分类策略：
 * - IDLE 动画（循环）：stand / stand2 / normal / sit / sleep — 静态待机动作
 * - ACTION 动画（一次性）：attack / dance / touch / skill / victory 等 — 互动触发动作
 * 点击时随机选一个 ACTION 播放一次，结束后自动回到随机 IDLE 循环。
 */
private class SpineRenderer(
    private val appContext: Context,
    dirPath: String,
    assetName: String,
    /** 缩放倍率提供者：每帧调用读取最新值，支持运行时动态调整（如用户在设置面板拖动滑块） */
    private val scaleProvider: () -> Float,
    /**
     * View 实时尺寸提供者：每帧调用读取 GLSurfaceView 的最新 width/height（px）。
     *
     * 【时序竞态修复】requiredSize 变化后 GLSurfaceView 在 UI 线程立即 measure/layout，
     * 但 GL 线程的 onSurfaceChanged 是异步触发的，期间 onDrawFrame 若仍用旧的
     * viewWidth/viewHeight 缓存值计算骨骼位置，会导致：
     *   - 新 userScale 已生效（update 回调立即设置）
     *   - 旧 viewWidth 未更新（onSurfaceChanged 未触发）
     *   - scale = min(旧viewW/boundsW, 旧viewH/boundsH) * 0.95 * 新userScale
     *   - 骨骼中心 = 旧viewW/2（相对旧 GLSurfaceView 中心）
     *   - 但 GLSurfaceView 实际尺寸已变大，骨骼偏左上角
     *
     * 通过 viewSizeProvider 在 onDrawFrame 中实时读取 UI 线程最新的 view 尺寸，
     * 绕过 onSurfaceChanged 的异步延迟，确保骨骼始终居中渲染。
     * 返回 (0, 0) 时回退到 onSurfaceChanged 的缓存值（首次渲染前 layout 未完成）。
     */
    private val viewSizeProvider: () -> Pair<Int, Int>
) : GLSurfaceView.Renderer {

    /** 当前 Spine 资源目录路径（@Volatile 支持 GL 线程热重载时读取最新值） */
    @Volatile private var currentDirPath: String = dirPath
    /** 当前 Spine 资源三件套主名（@Volatile 支持 GL 线程热重载时读取最新值） */
    @Volatile private var currentAssetName: String = assetName
    /** TextureAtlas 引用，热重载时需 dispose 释放旧 GL 纹理 */
    private var atlas: TextureAtlas? = null

    /**
     * 预定义的 idle 动画名集合（覆盖碧蓝航线 SD 小人常见命名）。
     *
     * 碧蓝航线 SD 资源的 idle 动画名可能因舰娘/资源版本不同而异，
     * 尽量覆盖所有可能的命名变体。若资源中不包含任何预定义 idle 名，
     * 则回退到第一个动画作为 idle（见采样逻辑）。
     */
    private val idleAnimationNames = setOf(
        "stand", "stand2", "normal", "sit", "sleep", "idle", "idle2",
        "daiji", "daiji2",  // 日语"待機"罗马音
        "wait", "wait2",
        "breath", "breathing"
    )

    /** 碧蓝航线 SD 小人的拖拽动画名（长按拖动时循环播放） */
    private val dragAnimationNames = setOf("tuozhuai", "drag", "move", "pick", "hold")

    private var batch: PolygonSpriteBatch? = null
    private var skeleton: Skeleton? = null
    private var animState: AnimationState? = null
    private var renderer: SkeletonRenderer? = null
    private var stubGraphics: StubGraphics? = null

    /** 所有动画名列表（加载后填充） */
    private var allAnimationNames: List<String> = emptyList()
    /** action 动画名列表（排除 idle 和 drag） */
    private var actionAnimationNames: List<String> = emptyList()
    /** 可用的 idle 动画名列表（与实际资源交集） */
    private var availableIdleNames: List<String> = emptyList()
    /** 可用的 drag 动画名列表（与实际资源交集） */
    private var availableDragNames: List<String> = emptyList()

    /** setup pose 的实际边界（scale=1 时），用于自适应缩放 */
    private var baseBoundsW = 0f
    private var baseBoundsH = 0f
    private var baseBoundsOffsetX = 0f
    private var baseBoundsOffsetY = 0f

    private var lastTimeNs = 0L
    private var viewWidth = 0
    private var viewHeight = 0
    private var loadFailed = false

    /** 当前是否正在播放 action 动画（防止点击重叠触发） */
    @Volatile private var isPlayingAction = false

    /** 当前是否正在播放 drag 动画（防止与 action 冲突） */
    @Volatile private var isDragging = false

    /**
     * 当前帧骨骼边界框在 view 坐标系中的位置（Android 坐标系，原点左上、Y 朝下）。
     *
     * 由 [onDrawFrame] 每帧更新，供 [SpineSdGlSurfaceView] 的 OnTouchListener 在
     * ACTION_DOWN 时判断触摸点是否落在角色实际范围内：
     * - 在 bounds 内 → 消费事件，正常处理点击/拖动
     * - 在 bounds 外 → 返回 false，事件穿透到下层 App（解决透明区域拦截触摸的问题）
     *
     * 【坐标系转换】SpineRenderer 用 libgdx 坐标系（原点左下、Y 朝上），
     * skel.x/skel.y 是根骨骼世界坐标。骨骼边界框居中在 view 中心：
     *   left = (vw - baseBoundsW * scale) / 2
     *   top = (vh - baseBoundsH * scale) / 2
     *   right = left + baseBoundsW * scale
     *   bottom = top + baseBoundsH * scale
     * （推导：baseBoundsOffsetX/Y 在居中计算中被消去，只剩 baseBoundsW/H × scale）
     *
     * 用 @Volatile 保证 GL 线程写入对 UI 线程立即可见；每帧创建新 RectF 实例，
     * 避免 RectF 内部字段跨线程写入的部分可见性问题。
     *
     * null 表示首次渲染前 bounds 未就绪，调用方应默认消费事件（避免首次点击失效）。
     */
    @Volatile private var _boundsInViewport: RectF? = null
    val boundsInViewport: RectF? get() = _boundsInViewport

    /**
     * 加载 Spine 三件套资源（.skel + .atlas + .png）。
     *
     * 从 [onSurfaceCreated] 和 [reloadAsset] 调用，在 GL 线程执行。
     * 首次加载时 [atlas]/[batch] 为 null，直接创建；
     * 热重载时先 dispose 旧资源（释放 GL 纹理和 GPU 缓冲区）再重新加载。
     *
     * 加载步骤：
     * 1. 释放旧资源（热重载场景）
     * 2. 读取 .skel + .atlas，创建 TextureAtlas / Skeleton / AnimationState
     * 3. 采样 idle 动画边界作为自适应缩放基准
     * 4. 分类动画（idle / action / drag）
     * 5. 创建 PolygonSpriteBatch，默认播放随机 idle 循环
     */
    private fun loadAsset() {
        // 释放旧资源（热重载场景）：atlas 持有 GL 纹理，batch 持有 GPU 顶点缓冲区
        atlas?.dispose()
        batch?.dispose()
        skeleton = null
        animState = null
        renderer = null
        batch = null
        atlas = null
        isPlayingAction = false
        isDragging = false

        try {
            val skelFile: FileHandle = Gdx.files.absolute("$currentDirPath/$currentAssetName.skel")
            // atlas 优先用 assetName.atlas（如 boge_g.atlas），
            // 不存在则回退到目录名同名 atlas（如 boge.atlas，改造/换皮皮肤复用默认皮肤 atlas）
            val dirName = currentDirPath.substringAfterLast('/')
            val primaryAtlas = Gdx.files.absolute("$currentDirPath/$currentAssetName.atlas")
            val atlasFile: FileHandle = if (primaryAtlas.exists()) primaryAtlas
                else Gdx.files.absolute("$currentDirPath/$dirName.atlas")

            val newAtlas = TextureAtlas(atlasFile)
            atlas = newAtlas
            val loader = AtlasAttachmentLoader(newAtlas)
            val skelReader = SkeletonBinary(loader).apply { scale = 1f }
            val skeletonData = skelReader.readSkeletonData(skelFile)

            skeleton = Skeleton(skeletonData).apply {
                setToSetupPose()
                updateWorldTransform()
            }

            // 计算 idle 动画边界：采样待机动画的关键帧作为缩放基准。
            //    仅用 idle 动画（stand/normal 等）的边界而非全局所有动画，
            //    因为 attack/dance 等动作动画肢体伸展范围远大于待机，
            //    用全局最大边界会导致 idle 时小人缩得太小。
            //    action 动画可能临时略微超出视口，这是自然表现，可接受。
            //
            //    【边界优化】使用每个动画的"代表性边界"（采样关键帧的中位数边界），
            //    而非所有关键帧的最大并集边界。避免个别极端关键帧（如待机中偶发的肢体伸展）
            //    导致边界过大、缩放过小的问题。
            val offset = Vector2()
            val size = Vector2()
            val temp = FloatArray()
            val sampler = Skeleton(skeletonData)  // 独立 sampler 实例避免污染主 skeleton

            // 先确定实际可用的 idle 动画名
            val idleAnimNames = idleAnimationNames.filter { name ->
                skeletonData.animations.any { it.name == name }
            }.ifEmpty { skeletonData.animations.take(1).map { it.name } }

            // 收集每个 idle 动画的代表性边界（采样关键帧后取中位数）
            data class AnimBounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)
            val animBoundsList = mutableListOf<AnimBounds>()

            for (anim in skeletonData.animations) {
                if (anim.name !in idleAnimNames) continue  // 仅采样 idle 动画
                val duration = anim.duration
                val sampleCount = if (duration > 0) 10 else 1
                // 收集该动画所有采样帧的边界
                // 注意：使用 kotlin.FloatArray 而非 com.badlogic.gdx.utils.FloatArray
                val frameBounds: MutableList<kotlin.FloatArray> = mutableListOf()  // [minX, minY, maxX, maxY]
                for (i in 0 until sampleCount) {
                    val t = if (duration > 0) duration * i / sampleCount else 0f
                    sampler.setToSetupPose()
                    anim.apply(sampler, 0f, t, false, null, 1f,
                        Animation.MixBlend.first, Animation.MixDirection.`in`)
                    sampler.updateWorldTransform()
                    sampler.getBounds(offset, size, temp)
                    if (size.x > 0 && size.y > 0) {
                        frameBounds.add(kotlin.floatArrayOf(
                            offset.x, offset.y,
                            offset.x + size.x, offset.y + size.y
                        ))
                    }
                }
                if (frameBounds.isNotEmpty()) {
                    // 取该动画所有帧的中位数边界（按面积排序取中位数帧）
                    // 中位数比最大值更鲁棒，避免极端关键帧导致边界过大
                    val sortedByArea = frameBounds.sortedBy { (it[2] - it[0]) * (it[3] - it[1]) }
                    val medianFrame = sortedByArea[sortedByArea.size / 2]
                    animBoundsList.add(AnimBounds(medianFrame[0], medianFrame[1], medianFrame[2], medianFrame[3]))
                }
            }

            // 取所有 idle 动画代表性边界的并集作为最终边界
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE
            var maxY = Float.MIN_VALUE
            for (b in animBoundsList) {
                if (b.minX < minX) minX = b.minX
                if (b.minY < minY) minY = b.minY
                if (b.maxX > maxX) maxX = b.maxX
                if (b.maxY > maxY) maxY = b.maxY
            }

            // 退化保护：若采样失败（空骨骼），用 setup pose 兜底
            if (minX == Float.MAX_VALUE || maxX == Float.MIN_VALUE) {
                skeleton!!.setToSetupPose()
                skeleton!!.updateWorldTransform()
                skeleton!!.getBounds(offset, size, temp)
                minX = offset.x; minY = offset.y
                maxX = offset.x + size.x; maxY = offset.y + size.y
            }

            baseBoundsOffsetX = minX
            baseBoundsOffsetY = minY
            baseBoundsW = (maxX - minX).coerceAtLeast(50f)
            baseBoundsH = (maxY - minY).coerceAtLeast(75f)
            Log.i(TAG, "Idle bounds (sampled from $idleAnimNames): " +
                "offset=($baseBoundsOffsetX, $baseBoundsOffsetY), " +
                "size=($baseBoundsW, $baseBoundsH)")

            // 分类动画
            allAnimationNames = skeletonData.animations.map { it.name }
            availableIdleNames = idleAnimationNames.filter { allAnimationNames.contains(it) }
                .ifEmpty { allAnimationNames.take(1) }
            availableDragNames = dragAnimationNames.filter { allAnimationNames.contains(it) }
            // action 动画排除 idle 和 drag，避免点击时误触发拖拽动画
            actionAnimationNames = allAnimationNames.filter {
                it !in idleAnimationNames && it !in dragAnimationNames
            }
            Log.i(TAG, "Loaded Spine asset=$currentAssetName, all=${allAnimationNames.size} " +
                "animations, idle=$availableIdleNames, drag=$availableDragNames, " +
                "action=$actionAnimationNames")

            // 创建 AnimationState，设置默认混合过渡（0.2s 平滑切换）
            val stateData = AnimationStateData(skeletonData).apply {
                defaultMix = 0.2f
            }
            animState = AnimationState(stateData).apply {
                // 默认播放随机 idle 动画（循环）
                val initialIdle = availableIdleNames.random()
                setAnimation(0, initialIdle, true)
                Log.i(TAG, "Started idle animation: $initialIdle")
            }

            renderer = SkeletonRenderer()
            batch = PolygonSpriteBatch()
            lastTimeNs = System.nanoTime()
        } catch (e: Exception) {
            loadFailed = true
            Log.e(TAG, "Failed to load Spine asset=$currentAssetName", e)
        }
    }

    /**
     * 热重载 Spine 资源（切换皮肤时调用，在 GL 线程执行）。
     *
     * 与 [onSurfaceCreated] + 重建 GLSurfaceView 的方案相比，热重载复用现有
     * GLSurfaceView / EGL context / GL 线程，仅重新加载 Spine 三件套资源：
     * - dispose 旧 TextureAtlas（释放 GL 纹理）和 PolygonSpriteBatch（释放 GPU 缓冲区）
     * - 用新路径重新加载 .skel + .atlas + .png
     * - 重新采样 idle 边界、分类动画、创建 AnimationState
     *
     * 优势：无需重建 EGL context（耗时 ~100ms），仅资源加载（~20ms），皮肤切换几乎无延迟。
     */
    fun reloadAsset(newDirPath: String, newAssetName: String) {
        if (newDirPath == currentDirPath && newAssetName == currentAssetName) return
        currentDirPath = newDirPath
        currentAssetName = newAssetName
        loadFailed = false
        loadAsset()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // 1. 初始化 libgdx 静态桥接（GLES20 在 GLSurfaceView 创建 GLContext 后才可用）
        if (Gdx.gl == null) {
            val gl20 = AndroidGL20()
            Gdx.gl = gl20
            Gdx.gl20 = gl20
        }
        if (Gdx.files == null) {
            val ctxWrapper = appContext as? ContextWrapper
            if (ctxWrapper != null) {
                Gdx.files = DefaultAndroidFiles(appContext.assets, ctxWrapper, false)
            } else {
                Log.w(TAG, "appContext is not a ContextWrapper, Gdx.files not initialized")
            }
        }

        // 2. 加载 libgdx native 库
        GdxNativesLoader.load()

        // 3. 初始化 Gdx.graphics stub
        if (Gdx.graphics == null) {
            val density = appContext.resources.displayMetrics.density
            stubGraphics = StubGraphics(density).also { Gdx.graphics = it }
            Log.i(TAG, "Injected StubGraphics into Gdx.graphics (density=$density)")
        } else {
            Log.i(TAG, "Gdx.graphics already set, reusing existing instance")
        }

        // 4. 初始化 Gdx.app stub
        if (Gdx.app == null) {
            Gdx.app = StubApplication()
            Log.i(TAG, "Injected StubApplication into Gdx.app")
        }

        // 5. 加载 Spine 三件套资源（抽取到 loadAsset，支持热重载复用）
        loadAsset()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        stubGraphics?.setSize(width, height)
        Gdx.gl.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (loadFailed) return
        val skel = skeleton ?: return
        val state = animState ?: return
        val rend = renderer ?: return
        val b = batch ?: return

        // 【时序竞态修复】实时读取 GLSurfaceView 的 width/height（UI 线程最新值），
        // 绕过 onSurfaceChanged 的异步延迟。requiredSize 变化后 GLSurfaceView layout
        // 立即更新 width/height，但 onSurfaceChanged 在 GL 线程异步触发，期间若用旧的
        // viewWidth/viewHeight 计算骨骼位置会导致放大时小人偏左上角。
        // 回退：首次渲染前 layout 未完成，viewSizeProvider 返回 (0,0)，用 onSurfaceChanged 缓存值。
        val (liveW, liveH) = viewSizeProvider()
        val vw = if (liveW > 0) liveW else viewWidth
        val vh = if (liveH > 0) liveH else viewHeight
        if (vw == 0 || vh == 0) return

        // view 尺寸变化时同步更新 viewport 和缓存（应对 onSurfaceChanged 未及时触发的场景）
        if (vw != viewWidth || vh != viewHeight) {
            viewWidth = vw
            viewHeight = vh
            stubGraphics?.setSize(vw, vh)
            Gdx.gl.glViewport(0, 0, vw, vh)
        }

        val now = System.nanoTime()
        val delta = if (lastTimeNs > 0) ((now - lastTimeNs) / 1_000_000_000f).coerceAtMost(0.1f) else 0.016f
        lastTimeNs = now
        stubGraphics?.updateDeltaTime(delta)
        stubGraphics?.nextFrame()

        // 推进动画并应用到骨骼
        state.update(delta)
        state.apply(skel)
        skel.updateWorldTransform()

        // 自适应缩放：基于 idle 动画边界缩放到视口 95%，再乘以用户设置的倍率（实时生效）
        val userScale = scaleProvider().coerceIn(0.3f, 2.0f)
        val scale = (min(vw / baseBoundsW, vh / baseBoundsH) * 0.95f) * userScale

        // 投影矩阵：左下(0,0) - 右上，Y 轴朝上
        b.projectionMatrix.setToOrtho2D(0f, 0f, vw.toFloat(), vh.toFloat())

        // 骨骼定位：边界框中心对齐到 view 中心
        // skel 的 (x, y) 是根骨骼世界坐标，getBounds 返回的 offset 是边界左下角相对于根骨骼的偏移
        // 要让边界水平居中：skel.x = viewW/2 - (offsetX + boundsW/2) * scale
        // 要让边界垂直居中：skel.y = viewH/2 - (offsetY + boundsH/2) * scale
        //
        // 配合外层 SecretaryChibiOverlay 的 offset 居中放置（renderOffset = (baseSize - renderSize)/2），
        // GLSurfaceView 中心 = 外层 Box 中心 = 小人中心，缩放时位置不移动。
        // 实时读取 vw/vh 确保缩放瞬间骨骼位置立即正确，不会因 onSurfaceChanged 延迟而偏移。
        skel.scaleX = scale
        skel.scaleY = scale
        skel.x = vw / 2f - (baseBoundsOffsetX + baseBoundsW / 2f) * scale
        skel.y = vh / 2f - (baseBoundsOffsetY + baseBoundsH / 2f) * scale

        // 更新骨骼边界框在 view 坐标系中的位置（Android 坐标系，原点左上、Y 朝下），
        // 供 OnTouchListener 判断触摸点是否落在角色实际范围内（触摸穿透透明区域）。
        //
        // 骨骼边界框居中在 view 中心（上方 skel.x/skel.y 计算保证），因此：
        //   left = (vw - baseBoundsW * scale) / 2
        //   top  = (vh - baseBoundsH * scale) / 2
        // baseBoundsOffsetX/Y 在居中计算中被消去，只剩 baseBoundsW/H × scale。
        //
        // 注意：baseBounds 是 idle 动画采样边界，action 动画时肢体可能略超出此范围，
        // 但触摸判断只在 ACTION_DOWN（idle 状态）执行，所以用 idle bounds 是精确的。
        val renderedBoundsW = baseBoundsW * scale
        val renderedBoundsH = baseBoundsH * scale
        val boundsLeft = (vw - renderedBoundsW) / 2f
        val boundsTop = (vh - renderedBoundsH) / 2f
        _boundsInViewport = RectF(
            boundsLeft,
            boundsTop,
            boundsLeft + renderedBoundsW,
            boundsTop + renderedBoundsH
        )

        // 清屏 + 绘制
        Gdx.gl.glClearColor(0f, 0f, 0f, 0f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        b.begin()
        rend.draw(b, skel)
        b.end()
    }

    /**
     * 随机触发一个 action 动画（GL 线程调用）。
     *
     * 如果当前正在播放 action 或 drag 则忽略（防止重叠）。
     * action 播放完毕后自动回到随机 idle 循环（通过 addAnimation 排队）。
     */
    fun triggerRandomAction() {
        if (isPlayingAction || isDragging) return
        val state = animState ?: return
        if (actionAnimationNames.isEmpty()) return

        val actionName = actionAnimationNames.random()
        val idleName = availableIdleNames.random()

        // 播放 action 一次（不循环），完成后自动回到 idle 循环
        state.setAnimation(0, actionName, false)
        state.addAnimation(0, idleName, true, 0f)
        isPlayingAction = true
        Log.i(TAG, "Trigger action: $actionName → return to idle: $idleName")

        // 监听 action 完成，重置标志位
        state.addListener(object : AnimationState.AnimationStateListener {
            override fun start(entry: AnimationState.TrackEntry?) {}
            override fun interrupt(entry: AnimationState.TrackEntry?) {}
            override fun end(entry: AnimationState.TrackEntry?) {}
            override fun dispose(entry: AnimationState.TrackEntry?) {}
            override fun event(entry: AnimationState.TrackEntry?, event: com.esotericsoftware.spine.Event?) {}

            override fun complete(entry: AnimationState.TrackEntry?) {
                // action 动画播放完成，回到 idle
                isPlayingAction = false
                state.removeListener(this)
            }
        })
    }

    /**
     * 触发拖拽动画（GL 线程调用）。
     *
     * 长按 SD 小人时调用，循环播放 "tuozhuai" / "drag" 等拖拽动画。
     * 如果 Spine 资源中不包含拖拽动画，则保持当前 idle 不变（视觉上小人仍可拖动，只是没有特殊动画），
     * 且不设置 isDragging 标志，避免 returnToIdle 误重置 idle 动画导致视觉闪烁。
     */
    fun triggerDragAnimation() {
        if (isDragging) return
        val state = animState ?: return
        if (availableDragNames.isEmpty()) {
            Log.i(TAG, "No drag animation available (tuozhuai/drag/move), keeping current animation")
            // 不设置 isDragging，避免 returnToIdle 误重置 idle 动画
            return
        }

        val dragName = availableDragNames.first()
        state.setAnimation(0, dragName, true)  // 循环播放拖拽动画
        isDragging = true
        isPlayingAction = false
        Log.i(TAG, "Trigger drag animation: $dragName (looping)")
    }

    /**
     * 回到 idle 动画（GL 线程调用）。
     *
     * 拖拽结束后调用，随机选一个 idle 动画循环播放。
     * 仅在 isDragging=true 时执行，避免非拖动场景误调用导致 idle 动画重启。
     */
    fun returnToIdle() {
        if (!isDragging) return  // 非拖动状态不处理，避免误重置 idle
        val state = animState ?: return
        if (availableIdleNames.isEmpty()) {
            isDragging = false
            return
        }

        val idleName = availableIdleNames.random()
        state.setAnimation(0, idleName, true)  // 循环播放 idle
        isDragging = false
        isPlayingAction = false
        Log.i(TAG, "Return to idle: $idleName")
    }
}

/**
 * libgdx [com.badlogic.gdx.Graphics] 的最小 stub 实现。
 *
 * 本项目用裸 [GLSurfaceView] 渲染 Spine，没有走 [com.badlogic.gdx.backends.android.AndroidApplication]
 * 生命周期，因此 libgdx 不会自动注入 [Gdx.graphics]。而 libgdx 的 GLTexture 在加载纹理时会调
 * `Gdx.graphics.supportsExtension("GL_EXT_texture_filter_anisotropic")` 判断是否支持各向异性过滤，
 * 若 Gdx.graphics 为 null 则抛 NPE 闪退。
 *
 * 这里只提供 libgdx 内部（TextureAtlas / Texture / PolygonSpriteBatch）会用到的最小实现：
 * - [supportsExtension] 返回 false（禁用各向异性过滤，对 SD 小人渲染无影响）
 * - [getGL20] / [setGL20] 代理到 [Gdx.gl]
 * - [getWidth] / [getHeight] / [getBackBufferWidth] / [getBackBufferHeight] 由 [setSize] 更新
 * - 其余方法返回合理默认值（0 / false / null / 空数组），不被 libgdx 内部调用
 *
 * 如果未来 libgdx 内部调用更多 Graphics 方法导致崩溃，在此补全对应实现即可。
 */
private class StubGraphics(private val density: Float) : AbstractGraphics() {
    @Volatile private var viewW: Int = 1
    @Volatile private var viewH: Int = 1

    private var frameId: Long = 0
    private var deltaTime: Float = 0.016f
    private val glVersion = GLVersion(
        com.badlogic.gdx.Application.ApplicationType.Android,
        "OpenGL ES 2.0", "", ""
    )

    fun setSize(w: Int, h: Int) {
        viewW = if (w > 0) w else 1
        viewH = if (h > 0) h else 1
    }

    fun updateDeltaTime(dt: Float) { deltaTime = dt }
    fun nextFrame() { frameId++ }

    // --- libgdx 内部关键方法 ---
    override fun supportsExtension(extension: String?): Boolean = false
    override fun getGL20(): GL20? = Gdx.gl
    override fun setGL20(gl20: GL20?) { Gdx.gl = gl20; Gdx.gl20 = gl20 }
    override fun getWidth(): Int = viewW
    override fun getHeight(): Int = viewH
    override fun getBackBufferWidth(): Int = viewW
    override fun getBackBufferHeight(): Int = viewH
    override fun getSafeInsetLeft(): Int = 0
    override fun getSafeInsetTop(): Int = 0
    override fun getSafeInsetBottom(): Int = 0
    override fun getSafeInsetRight(): Int = 0
    override fun getFrameId(): Long = frameId
    override fun getDeltaTime(): Float = deltaTime
    override fun getFramesPerSecond(): Int = 60
    override fun getType(): com.badlogic.gdx.Graphics.GraphicsType =
        com.badlogic.gdx.Graphics.GraphicsType.AndroidGL
    override fun getGLVersion(): GLVersion = glVersion
    override fun getPpiX(): Float = 160f * density
    override fun getPpiY(): Float = 160f * density
    override fun getPpcX(): Float = 160f * density / 2.54f
    override fun getPpcY(): Float = 160f * density / 2.54f

    // --- 其余方法返回合理默认值，libgdx 内部不会调用 ---
    override fun isGL30Available(): Boolean = false
    override fun isGL31Available(): Boolean = false
    override fun isGL32Available(): Boolean = false
    override fun getGL30() = null as com.badlogic.gdx.graphics.GL30?
    override fun getGL31() = null as com.badlogic.gdx.graphics.GL31?
    override fun getGL32() = null as com.badlogic.gdx.graphics.GL32?
    override fun setGL30(gl: com.badlogic.gdx.graphics.GL30?) {}
    override fun setGL31(gl: com.badlogic.gdx.graphics.GL31?) {}
    override fun setGL32(gl: com.badlogic.gdx.graphics.GL32?) {}
    override fun supportsDisplayModeChange(): Boolean = false
    override fun getPrimaryMonitor() = null as com.badlogic.gdx.Graphics.Monitor?
    override fun getMonitor() = null as com.badlogic.gdx.Graphics.Monitor?
    override fun getMonitors(): Array<com.badlogic.gdx.Graphics.Monitor> = emptyArray()
    override fun getDisplayModes(): Array<com.badlogic.gdx.Graphics.DisplayMode> = emptyArray()
    override fun getDisplayModes(monitor: com.badlogic.gdx.Graphics.Monitor?): Array<com.badlogic.gdx.Graphics.DisplayMode> = emptyArray()
    override fun getDisplayMode() = null as com.badlogic.gdx.Graphics.DisplayMode?
    override fun getDisplayMode(monitor: com.badlogic.gdx.Graphics.Monitor?) = null as com.badlogic.gdx.Graphics.DisplayMode?
    override fun setFullscreenMode(mode: com.badlogic.gdx.Graphics.DisplayMode?): Boolean = false
    override fun setWindowedMode(width: Int, height: Int): Boolean = false
    override fun setTitle(title: String?) {}
    override fun setUndecorated(undecorated: Boolean) {}
    override fun setResizable(resizable: Boolean) {}
    override fun setVSync(vsync: Boolean) {}
    override fun setForegroundFPS(fps: Int) {}
    override fun getBufferFormat() = com.badlogic.gdx.Graphics.BufferFormat(8, 8, 8, 8, 0, 0, 0, false)
    override fun setContinuousRendering(isContinuous: Boolean) {}
    override fun isContinuousRendering(): Boolean = true
    override fun requestRendering() {}
    override fun isFullscreen(): Boolean = false
    override fun newCursor(pixmap: com.badlogic.gdx.graphics.Pixmap?, xHotspot: Int, yHotspot: Int) = null as com.badlogic.gdx.graphics.Cursor?
    override fun setCursor(cursor: com.badlogic.gdx.graphics.Cursor?) {}
    override fun setSystemCursor(systemCursor: com.badlogic.gdx.graphics.Cursor.SystemCursor?) {}
}

/**
 * libgdx [com.badlogic.gdx.Application] 的最小 stub 实现。
 *
 * 本项目用裸 [GLSurfaceView] 渲染 Spine，没有走 [com.badlogic.gdx.backends.android.AndroidApplication]
 * 生命周期，因此 libgdx 不会自动注入 [Gdx.app]。而 libgdx 的
 * [com.badlogic.gdx.graphics.glutils.ShaderProgram.addManagedShader] 用 `Gdx.app` 作为
 * [com.badlogic.gdx.utils.ObjectMap] 的 key 来区分不同 Application 实例的 shader 资源，
 * `Gdx.app` 为 null 时 `ObjectMap.get(null)` 抛 `IllegalArgumentException: key cannot be null`，
 * 导致 `PolygonSpriteBatch` 创建默认 Shader 时闪退。
 *
 * 这里只提供 libgdx 内部会用到的最小实现：
 * - [getGraphics] / [getFiles] / [getGL20] 代理到 [Gdx] 已注入的字段
 * - [log] / [error] / [debug] 转发到 [android.util.Log]
 * - [getType] 返回 [com.badlogic.gdx.Application.ApplicationType.Android]
 * - [getJavaHeap] / [getNativeHeap] 返回 [Runtime] 内存使用
 * - [postRunnable] 直接同步执行（GL 线程已就绪，无需排队）
 * - 其余方法返回合理默认值，libgdx 内部不会调用
 */
private class StubApplication : com.badlogic.gdx.Application {
    private val lifecycleListeners = java.util.Collections.synchronizedList(mutableListOf<com.badlogic.gdx.LifecycleListener>())

    override fun getGraphics(): com.badlogic.gdx.Graphics = Gdx.graphics
        ?: throw IllegalStateException("Gdx.graphics not initialized")
    override fun getAudio(): com.badlogic.gdx.Audio = throw UnsupportedOperationException("Audio not supported in Spine SD view")
    override fun getInput(): com.badlogic.gdx.Input = throw UnsupportedOperationException("Input not supported in Spine SD view")
    override fun getFiles(): com.badlogic.gdx.Files = Gdx.files
        ?: throw IllegalStateException("Gdx.files not initialized")
    override fun getNet(): com.badlogic.gdx.Net = throw UnsupportedOperationException("Net not supported in Spine SD view")
    override fun getApplicationListener(): com.badlogic.gdx.ApplicationListener? = null

    override fun log(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
    override fun log(tag: String?, message: String?, throwable: Throwable?) { Log.i(tag ?: TAG, message ?: "", throwable) }
    override fun error(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
    override fun error(tag: String?, message: String?, throwable: Throwable?) { Log.e(tag ?: TAG, message ?: "", throwable) }
    override fun debug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
    override fun debug(tag: String?, message: String?, throwable: Throwable?) { Log.d(tag ?: TAG, message ?: "", throwable) }

    override fun setLogLevel(level: Int) {}
    override fun getLogLevel(): Int = com.badlogic.gdx.Application.LOG_INFO
    override fun setApplicationLogger(logger: com.badlogic.gdx.ApplicationLogger?) {}
    override fun getApplicationLogger(): com.badlogic.gdx.ApplicationLogger? = null

    override fun getType(): com.badlogic.gdx.Application.ApplicationType =
        com.badlogic.gdx.Application.ApplicationType.Android
    override fun getVersion(): Int = 0

    override fun getJavaHeap(): Long = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
    override fun getNativeHeap(): Long = getJavaHeap()

    override fun getPreferences(name: String?): com.badlogic.gdx.Preferences = throw UnsupportedOperationException("Preferences not supported in Spine SD view")
    override fun getClipboard(): com.badlogic.gdx.utils.Clipboard = throw UnsupportedOperationException("Clipboard not supported in Spine SD view")

    override fun postRunnable(runnable: java.lang.Runnable?) {
        // GL 线程已就绪，直接同步执行
        runnable?.run()
    }

    override fun exit() {}

    override fun addLifecycleListener(listener: com.badlogic.gdx.LifecycleListener?) {
        listener?.let { lifecycleListeners.add(it) }
    }

    override fun removeLifecycleListener(listener: com.badlogic.gdx.LifecycleListener?) {
        listener?.let { lifecycleListeners.remove(it) }
    }
}
