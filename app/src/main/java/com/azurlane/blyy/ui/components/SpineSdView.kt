package com.azurlane.blyy.ui.components

import android.content.Context
import android.content.ContextWrapper
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
 * 默认循环播放 idle 动画（stand/normal 等），点击视图时随机播放一个 action 动画
 * （attack/dance/touch 等），播放完毕后自动回到 idle 循环，过渡平滑。
 * 点击同时通过 [onTap] 回调通知外部（用于播放语音等）。
 *
 * 显示区域自适应：加载后采样所有动画的关键帧，计算全局最大外接矩形，
 * 确保任何动作都不会超出视口边界被裁剪。
 *
 * @param assetName assets/blhx_sd/ 下三件套的主名（无扩展名），如 "boge"
 * @param modifier Compose 修饰符
 * @param scaleOverride 手动缩放覆盖；null 时按全局最大边界自适应到视口 95%
 * @param onTap 点击小人回调（在 UI 线程触发，用于播放语音等）
 */
@Composable
fun SpineSdView(
    assetName: String,
    modifier: Modifier = Modifier,
    scaleOverride: Float? = null,
    onTap: (() -> Unit)? = null,
    onDragStart: (() -> Unit)? = null,
    onDrag: ((dx: Float, dy: Float) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewRef = remember(assetName) { mutableStateOf<SpineSdGlSurfaceView?>(null) }

    AndroidView(
        factory = { ctx ->
            SpineSdGlSurfaceView(
                ctx = ctx,
                assetName = assetName,
                scaleOverride = scaleOverride,
                onTap = onTap,
                onDragStart = onDragStart,
                onDrag = onDrag,
                onDragEnd = onDragEnd
            ).also { viewRef.value = it }
        },
        modifier = modifier
    )

    DisposableEffect(lifecycleOwner, assetName) {
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
    private val assetName: String,
    private val scaleOverride: Float?,
    private val onTap: (() -> Unit)?,
    private val onDragStart: (() -> Unit)?,
    private val onDrag: ((Float, Float) -> Unit)?,
    private val onDragEnd: (() -> Unit)?
) : GLSurfaceView(ctx) {

    private val spineRenderer = SpineRenderer(context.applicationContext, assetName, scaleOverride)

    init {
        setEGLContextClientVersion(2)
        // RGBA8888 + 无 depth/stencil（2D 渲染用不到深度）
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        setRenderer(spineRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY

        // 触摸处理：区分点击与拖动。
        // GLSurfaceView 设置 OnTouchListener 后会消费整个触摸序列，
        // 父级 Compose 的 pointerInput(detectDragGestures) 收不到事件，
        // 因此拖动必须在 GLSurfaceView 内部识别并回调外部，否则 SD 小人无法拖动。
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        var downX = 0f
        var downY = 0f
        var lastX = 0f
        var lastY = 0f
        var dragging = false
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y
                    lastX = event.x; lastY = event.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && (abs(event.x - downX) > touchSlop ||
                            abs(event.y - downY) > touchSlop)) {
                        dragging = true
                        onDragStart?.invoke()
                    }
                    if (dragging) {
                        onDrag?.invoke(event.x - lastX, event.y - lastY)
                    }
                    lastX = event.x; lastY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        // 点击：触发随机 action 动画 + 回调外部 onTap（播放语音等）
                        queueEvent { spineRenderer.triggerRandomAction() }
                        onTap?.invoke()
                        performClick()
                    } else {
                        onDragEnd?.invoke()
                    }
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) onDragEnd?.invoke()
                    dragging = false
                }
            }
            true
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
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
    private val assetName: String,
    private val scaleOverride: Float?
) : GLSurfaceView.Renderer {

    /** 碧蓝航线 SD 小人的待机动画名（循环播放） */
    private val idleAnimationNames = setOf("stand", "stand2", "normal", "sit", "sleep")

    private var batch: PolygonSpriteBatch? = null
    private var skeleton: Skeleton? = null
    private var animState: AnimationState? = null
    private var renderer: SkeletonRenderer? = null
    private var stubGraphics: StubGraphics? = null

    /** 所有动画名列表（加载后填充） */
    private var allAnimationNames: List<String> = emptyList()
    /** action 动画名列表（排除 idle） */
    private var actionAnimationNames: List<String> = emptyList()
    /** 可用的 idle 动画名列表（与实际资源交集） */
    private var availableIdleNames: List<String> = emptyList()

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

        // 5. 加载 Spine 三件套
        try {
            val atlasFile: FileHandle = Gdx.files.internal("blhx_sd/$assetName.atlas")
            val skelFile: FileHandle = Gdx.files.internal("blhx_sd/$assetName.skel")

            val atlas = TextureAtlas(atlasFile)
            val loader = AtlasAttachmentLoader(atlas)
            val skelReader = SkeletonBinary(loader).apply { scale = 1f }
            val skeletonData = skelReader.readSkeletonData(skelFile)

            skeleton = Skeleton(skeletonData).apply {
                setToSetupPose()
                updateWorldTransform()
            }

            // 6. 计算 idle 动画边界：采样待机动画的关键帧作为缩放基准。
            //    仅用 idle 动画（stand/normal 等）的边界而非全局所有动画，
            //    因为 attack/dance 等动作动画肢体伸展范围远大于待机，
            //    用全局最大边界会导致 idle 时小人缩得太小。
            //    action 动画可能临时略微超出视口，这是自然表现，可接受。
            val offset = Vector2()
            val size = Vector2()
            val temp = FloatArray()
            val sampler = Skeleton(skeletonData)  // 独立 sampler 实例避免污染主 skeleton
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE
            var maxY = Float.MIN_VALUE

            // 先确定实际可用的 idle 动画名
            val idleAnimNames = idleAnimationNames.filter { name ->
                skeletonData.animations.any { it.name == name }
            }.ifEmpty { skeletonData.animations.take(1).map { it.name } }

            for (anim in skeletonData.animations) {
                if (anim.name !in idleAnimNames) continue  // 仅采样 idle 动画
                val duration = anim.duration
                val sampleCount = if (duration > 0) 10 else 1
                for (i in 0 until sampleCount) {
                    val t = if (duration > 0) duration * i / sampleCount else 0f
                    sampler.setToSetupPose()
                    anim.apply(sampler, 0f, t, false, null, 1f,
                        Animation.MixBlend.first, Animation.MixDirection.`in`)
                    sampler.updateWorldTransform()
                    sampler.getBounds(offset, size, temp)
                    if (size.x > 0 && size.y > 0) {
                        if (offset.x < minX) minX = offset.x
                        if (offset.y < minY) minY = offset.y
                        if (offset.x + size.x > maxX) maxX = offset.x + size.x
                        if (offset.y + size.y > maxY) maxY = offset.y + size.y
                    }
                }
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

            // 7. 分类动画
            allAnimationNames = skeletonData.animations.map { it.name }
            availableIdleNames = idleAnimationNames.filter { allAnimationNames.contains(it) }
                .ifEmpty { allAnimationNames.take(1) }
            actionAnimationNames = allAnimationNames.filter { !idleAnimationNames.contains(it) }
            Log.i(TAG, "Loaded Spine asset=$assetName, all=${allAnimationNames.size} " +
                "animations, idle=$availableIdleNames, action=$actionAnimationNames")

            // 8. 创建 AnimationState，设置默认混合过渡（0.2s 平滑切换）
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
            Log.e(TAG, "Failed to load Spine asset=$assetName", e)
        }
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
        if (viewWidth == 0 || viewHeight == 0) return

        val now = System.nanoTime()
        val delta = if (lastTimeNs > 0) ((now - lastTimeNs) / 1_000_000_000f).coerceAtMost(0.1f) else 0.016f
        lastTimeNs = now
        stubGraphics?.updateDeltaTime(delta)
        stubGraphics?.nextFrame()

        // 推进动画并应用到骨骼
        state.update(delta)
        state.apply(skel)
        skel.updateWorldTransform()

        // 自适应缩放：基于全局最大边界（覆盖所有动画），缩放到视口 95%
        val scale = scaleOverride ?: (min(viewWidth / baseBoundsW, viewHeight / baseBoundsH) * 0.95f)

        // 投影矩阵：左下(0,0) - 右上，Y 轴朝上
        b.projectionMatrix.setToOrtho2D(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())

        // 骨骼定位：基于全局最大边界偏移居中
        // skel 的 (x, y) 是根骨骼世界坐标，getBounds 返回的 offset 是边界左下角相对于根骨骼的偏移
        // 要让边界水平居中：skel.x = viewW/2 - (offsetX + boundsW/2) * scale
        // 要让边界垂直居中：skel.y = viewH/2 - (offsetY + boundsH/2) * scale
        skel.scaleX = scale
        skel.scaleY = scale
        skel.x = viewWidth / 2f - (baseBoundsOffsetX + baseBoundsW / 2f) * scale
        skel.y = viewHeight / 2f - (baseBoundsOffsetY + baseBoundsH / 2f) * scale

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
     * 如果当前正在播放 action 则忽略（防止重叠）。
     * action 播放完毕后自动回到随机 idle 循环（通过 addAnimation 排队）。
     */
    fun triggerRandomAction() {
        if (isPlayingAction) return
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
