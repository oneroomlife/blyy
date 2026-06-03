package com.azurlane.blyy.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.http.SslError
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ServiceWorkerController
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Adb
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.StayCurrentPortrait
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.azurlane.blyy.R
import com.azurlane.blyy.ui.theme.AppColors
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography

private const val LIVE2D_URL = "https://l2d.su/"
private const val INITIAL_PROGRESS = 6
private const val VISIBLE_PAGE_PROGRESS = 85
private const val COMPLETE_PROGRESS = 100

private enum class Live2DLoadPhase { Loading, Ready, Error }

/**
 * Live2D 全屏沉浸展示界面。
 *
 * 设计要点：
 * - WebView 全屏铺满，无边框无多余 UI，最大化 Live2D 模型可视区域
 * - 浮动控件（返回/旋转）自动半透明悬浮，不遮挡模型
 * - 支持横屏/竖屏切换，Activity 方向锁定 + configChanges 防重建
 * - WebView 生命周期严格绑定，避免 WebGL 资源泄漏
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Live2DScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    var loadPhase by remember { mutableStateOf(Live2DLoadPhase.Loading) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf("") }
    var reloadToken by remember { mutableIntStateOf(0) }
    var webViewGeneration by remember { mutableIntStateOf(0) }
    var recreateOnNextRetry by remember { mutableStateOf(false) }
    var isContentReady by remember { mutableStateOf(false) }

    // 横竖屏状态：null = 跟随系统，LANDSCAPE = 强制横屏，PORTRAIT = 强制竖屏
    var orientationMode by remember {
        mutableStateOf<OrientationMode?>(null)
    }

    // 进入页面时锁定为竖屏，离开时恢复
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // 响应方向模式切换
    LaunchedEffect(orientationMode) {
        activity?.requestedOrientation = when (orientationMode) {
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            null -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    fun beginLoad() {
        loadPhase = Live2DLoadPhase.Loading
        loadProgress = INITIAL_PROGRESS
        errorMessage = ""
        isContentReady = false
    }

    // 页面加载完成后延迟切换到 Ready，给 WebGL canvas 时间渲染首帧
    LaunchedEffect(loadProgress) {
        if (loadProgress >= COMPLETE_PROGRESS && loadPhase == Live2DLoadPhase.Loading) {
            delay(500L)
            if (loadPhase == Live2DLoadPhase.Loading) {
                isContentReady = true
                loadPhase = Live2DLoadPhase.Ready
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ── WebView 全屏铺满 ──
        key(webViewGeneration) {
            val webView = rememberLive2DWebView(
                onLoadStarted = { beginLoad() },
                onLoadProgress = { progress ->
                    val boundedProgress = progress.coerceIn(INITIAL_PROGRESS, COMPLETE_PROGRESS)
                    loadProgress = maxOf(loadProgress, boundedProgress)
                },
                onPageVisible = {
                    if (loadPhase == Live2DLoadPhase.Loading) {
                        loadProgress = maxOf(loadProgress, VISIBLE_PAGE_PROGRESS)
                    }
                },
                onLoadFinished = {
                    if (loadPhase != Live2DLoadPhase.Error) {
                        loadProgress = COMPLETE_PROGRESS
                    }
                },
                onLoadFailed = { message ->
                    loadProgress = 0
                    loadPhase = Live2DLoadPhase.Error
                    errorMessage = message
                },
                onRendererGone = { message ->
                    recreateOnNextRetry = true
                    loadProgress = 0
                    loadPhase = Live2DLoadPhase.Error
                    errorMessage = message
                }
            )

            BindLive2DWebViewLifecycle(webView)

            // 内容就绪后显示 WebView，避免黑闪
            LaunchedEffect(isContentReady) {
                if (isContentReady && webView.visibility != View.VISIBLE) {
                    webView.visibility = View.VISIBLE
                }
            }

            LaunchedEffect(webView, reloadToken) {
                beginLoad()
                // 重新加载时隐藏 WebView，防止旧内容闪现
                webView.visibility = View.INVISIBLE
                webView.stopLoading()
                webView.loadUrl(LIVE2D_URL)
            }

            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize()
            )
        }

        // ── 加载中覆盖层 ──
        AnimatedVisibility(
            visible = loadPhase == Live2DLoadPhase.Loading,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180))
        ) {
            LoadingOverlay(progress = loadProgress)
        }

        // ── 错误覆盖层 ──
        AnimatedVisibility(
            visible = loadPhase == Live2DLoadPhase.Error,
            enter = fadeIn(tween(240)),
            exit = fadeOut(tween(160))
        ) {
            ErrorOverlay(
                message = errorMessage,
                onRetry = {
                    beginLoad()
                    if (recreateOnNextRetry) {
                        recreateOnNextRetry = false
                        webViewGeneration++
                    } else {
                        reloadToken++
                    }
                }
            )
        }

        // ── 浮动控件（仅在加载完成/错误时显示） ──
        if (loadPhase != Live2DLoadPhase.Loading) {
            FloatingControls(
                orientationMode = orientationMode,
                onOrientationToggle = {
                    orientationMode = when (orientationMode) {
                        OrientationMode.LANDSCAPE -> OrientationMode.PORTRAIT
                        else -> OrientationMode.LANDSCAPE
                    }
                },
                onBack = onBack
            )
        }
    }
}

// ────────────────────────── 横竖屏模式 ──────────────────────────

private enum class OrientationMode { LANDSCAPE, PORTRAIT }

/**
 * 浮动控件：返回按钮 + 横竖屏切换
 * 半透明悬浮于 WebView 上方，不遮挡 Live2D 模型主体
 */
@Composable
private fun FloatingControls(
    orientationMode: OrientationMode?,
    onOrientationToggle: () -> Unit,
    onBack: () -> Unit
) {
    val isLandscape = orientationMode == OrientationMode.LANDSCAPE

    // 控件位置：横屏时在左侧竖排，竖屏时在左上角横排
    if (isLandscape) {
        Column(
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 12.dp, top = 8.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            FloatingIconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            FloatingIconButton(onClick = onOrientationToggle) {
                Icon(
                    Icons.Rounded.StayCurrentPortrait,
                    contentDescription = "竖屏",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    } else {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 12.dp, top = 8.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            FloatingIconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            FloatingIconButton(onClick = onOrientationToggle) {
                Icon(
                    Icons.Rounded.ScreenRotation,
                    contentDescription = "横屏",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun FloatingIconButton(
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = 0.7f),
        label = "FloatBtnScale"
    )

    Surface(
        onClick = onClick,
        modifier = Modifier.size(40.dp).scale(scale),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.12f),
        contentColor = Color.White
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
    }
}

// ────────────────────────── WebView 工厂与生命周期 ──────────────────────────

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun rememberLive2DWebView(
    onLoadStarted: () -> Unit,
    onLoadProgress: (Int) -> Unit,
    onPageVisible: () -> Unit,
    onLoadFinished: () -> Unit,
    onLoadFailed: (String) -> Unit,
    onRendererGone: (String) -> Unit
): WebView {
    val context = LocalContext.current
    val onLoadStartedState = rememberUpdatedState(onLoadStarted)
    val onLoadProgressState = rememberUpdatedState(onLoadProgress)
    val onPageVisibleState = rememberUpdatedState(onPageVisible)
    val onLoadFinishedState = rememberUpdatedState(onLoadFinished)
    val onLoadFailedState = rememberUpdatedState(onLoadFailed)
    val onRendererGoneState = rememberUpdatedState(onRendererGone)

    return remember(context) {
        configureLive2DServiceWorker()
        WebView.setWebContentsDebuggingEnabled(false)
        WebView(context).apply {
            configureForLive2D(
                onLoadStartedState = onLoadStartedState,
                onLoadProgressState = onLoadProgressState,
                onPageVisibleState = onPageVisibleState,
                onLoadFinishedState = onLoadFinishedState,
                onLoadFailedState = onLoadFailedState,
                onRendererGoneState = onRendererGoneState
            )
        }
    }
}

@Composable
private fun BindLive2DWebViewLifecycle(webView: WebView) {
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, webView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> runCatching {
                    webView.onResume()
                    webView.resumeTimers()
                }
                Lifecycle.Event.ON_PAUSE -> runCatching {
                    webView.onPause()
                    webView.pauseTimers()
                }
                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            runCatching {
                webView.onResume()
                webView.resumeTimers()
            }
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            webView.releaseLive2D()
        }
    }
}

// ────────────────────────── WebView 配置 ──────────────────────────

private fun WebView.configureForLive2D(
    onLoadStartedState: androidx.compose.runtime.State<() -> Unit>,
    onLoadProgressState: androidx.compose.runtime.State<(Int) -> Unit>,
    onPageVisibleState: androidx.compose.runtime.State<() -> Unit>,
    onLoadFinishedState: androidx.compose.runtime.State<() -> Unit>,
    onLoadFailedState: androidx.compose.runtime.State<(String) -> Unit>,
    onRendererGoneState: androidx.compose.runtime.State<(String) -> Unit>
) {
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )
    setLayerType(View.LAYER_TYPE_HARDWARE, null)
    // 使用不透明深色背景，避免 WebView 重绘时透明背景露出底层黑色产生"黑闪"
    setBackgroundColor(0xFF0D1117.toInt())
    // 初始不可见，等页面内容渲染完成后再设为 VISIBLE，彻底阻断黑闪源头
    visibility = View.INVISIBLE
    overScrollMode = View.OVER_SCROLL_NEVER
    isFocusable = true
    isFocusableInTouchMode = true
    // 禁止横向/纵向滚动，防止交互时页面偏移导致闪跳
    isHorizontalScrollBarEnabled = false
    isVerticalScrollBarEnabled = false
    scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
    }

    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(this@configureForLive2D, true)
    }

    settings.configureForLive2D()
    installResponsiveTouchGuard()
    installOrientationChangeGuard()

    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val scheme = request?.url?.scheme
            return scheme != "http" && scheme != "https"
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (url.isWebPageUrl()) {
                // 重置滚动位置到顶部，防止残留上一页的 scrollY
                view?.scrollTo(0, 0)
                onLoadStartedState.value()
            }
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            if (url.isWebPageUrl()) onPageVisibleState.value()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            if (url.isWebPageUrl()) {
                // 强制滚动到顶部，确保内容从正确位置开始显示
                view?.scrollTo(0, 0)
                onLoadFinishedState.value()
                // 注入网页优化脚本，确保 WebGL canvas 尺寸与屏幕匹配、防闪跳、响应式布局
                view?.evaluateJavascript(WEB_OPTIMIZATION_SCRIPT, null)
            }
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame == true) {
                onLoadFailedState.value(error.toFriendlyMessage())
            }
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 400) {
                onLoadFailedState.value(errorResponse.toFriendlyMessage())
            }
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            handler?.cancel()
            onLoadFailedState.value(error.toFriendlyMessage())
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            onRendererGoneState.value(detail.toFriendlyMessage())
            return true
        }
    }

    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            onLoadProgressState.value(newProgress)
        }
    }
}

/**
 * 全面优化 Live2D 网页渲染的 JavaScript 脚本。
 *
 * 解决的问题：
 * 1. 竖屏模式下缩放比例异常 — setInitialScale 导致 viewport 不匹配 DPR
 * 2. 缩放导致交互组件显示异常 — canvas 尺寸与视口不一致
 * 3. 交互时内容闪跳 — DOM 重排、WebGL 画布尺寸突变、滚动溢出
 *
 * 优化策略：
 * A. viewport 修正：确保 CSS 像素 = 设备独立像素（dp），禁止用户缩放
 * B. canvas 尺寸同步：使用 CSS 像素设置 canvas，让 PixiJS 内部 resolution 处理 DPR
 * C. 宽高比动态缩放：根据屏幕宽高比计算 Live2D 模型缩放因子，避免拉伸/压缩
 * D. CSS 响应式注入：全局 overflow 控制、will-change 提示、touch-action 优化
 * E. 防闪跳：requestAnimationFrame 批量 DOM 操作
 * F. PixiJS 集成：直接调用 app.renderer.resize() 重建渲染管线
 */
private const val WEB_OPTIMIZATION_SCRIPT = """
(function() {
    'use strict';

    // ── A. viewport 修正 ──
    // 确保 CSS 像素 = 设备独立像素（dp），让移动端网页按预期渲染
    var viewport = document.querySelector('meta[name=viewport]');
    if (!viewport) {
        viewport = document.createElement('meta');
        viewport.name = 'viewport';
        document.head.appendChild(viewport);
    }
    viewport.content = 'width=device-width,initial-scale=1,maximum-scale=1,minimum-scale=1,user-scalable=no,viewport-fit=cover';

    // ── B. CSS 响应式注入 ──
    var style = document.createElement('style');
    style.textContent = [
        'html, body {',
        '  width: 100% !important;',
        '  height: 100% !important;',
        '  margin: 0 !important;',
        '  padding: 0 !important;',
        '  overflow: hidden !important;',
        '  overscroll-behavior: none !important;',
        '  -webkit-overflow-scrolling: auto !important;',
        '  touch-action: manipulation !important;',
        // 强制内容从视口顶部开始，防止初始加载位置靠下
        '  position: fixed !important;',
        '  top: 0 !important;',
        '  left: 0 !important;',
        // 覆盖安全区域 padding，避免 env(safe-area-inset-top) 添加顶部间距
        '  padding-top: 0 !important;',
        '  padding-left: 0 !important;',
        '}',
        'canvas {',
        '  display: block !important;',
        '  width: 100vw !important;',
        '  height: 100vh !important;',
        '  will-change: transform, opacity !important;',
        // 防黑闪：opacity 变化时平滑过渡，避免突变
        '  transition: opacity 100ms ease-out !important;',
        '}',
        'button, [role="button"], a, input, select, textarea {',
        '  touch-action: manipulation !important;',
        '  -webkit-tap-highlight-color: transparent !important;',
        '}',
        '.modal, .dialog, .popup, [class*="modal"], [class*="dialog"], [class*="popup"] {',
        '  max-width: 100vw !important;',
        '  max-height: 100vh !important;',
        '  box-sizing: border-box !important;',
        '}',
        // 防黑闪：全局 transition 优化，减少 DOM 变更时的视觉闪烁
        '*, *::before, *::after {',
        '  -webkit-backface-visibility: hidden !important;',
        '  backface-visibility: hidden !important;',
        '}'
    ].join('\\n');
    document.head.appendChild(style);

    // ── C. canvas 尺寸同步核心函数 ──
    // 关键：使用 CSS 像素（window.innerWidth/Height）设置 canvas，
    // 而非物理像素（w * dpr）。PixiJS 内部通过 resolution 属性处理 DPR，
    // 如果我们手动乘以 DPR 会导致双倍缩放。
    //
    // 防黑闪策略：修改 canvas 尺寸时使用 opacity 过渡，
    // 先将 canvas 设为透明，修改尺寸并触发 PixiJS 重新渲染后恢复不透明。
    // 注意：WebGL canvas 默认 preserveDrawingBuffer=false，
    // drawImage 从 WebGL canvas 读取会得到空白图像，因此不能用离屏 canvas 双缓冲。
    function syncCanvasSize() {
        var w = window.innerWidth;
        var h = window.innerHeight;
        var canvases = document.querySelectorAll('canvas');
        canvases.forEach(function(c) {
            var targetW = Math.round(w);
            var targetH = Math.round(h);
            if (c.width !== targetW || c.height !== targetH) {
                // 防黑闪：修改尺寸前先设为透明，避免清空时的黑帧
                c.style.opacity = '0';
                // 重置尺寸（会清空 canvas 内容）
                c.width = targetW;
                c.height = targetH;
            }
        });
    }

    // ── D. 宽高比动态缩放 ──
    // 根据屏幕宽高比计算 Live2D 模型的最佳缩放因子，
    // 确保模型在竖屏/横屏下均保持正确比例，不拉伸不压缩
    function applyAspectRatioScaling() {
        var w = window.innerWidth;
        var h = window.innerHeight;
        var aspectRatio = w / h;

        // 参考宽高比：9:16（标准竖屏手机）
        var REF_PORTRAIT_RATIO = 9 / 16;
        // 参考宽高比：16:9（标准横屏）
        var REF_LANDSCAPE_RATIO = 16 / 9;

        // 查找 Live2D 模型实例并应用缩放
        try {
            if (typeof PIXI !== 'undefined') {
                // 遍历 PIXI stage 上的 Live2D 模型
                var apps = [];
                if (window.app && window.app.stage) apps.push(window.app);
                if (window.pixiApp && window.pixiApp.stage) apps.push(window.pixiApp);

                apps.forEach(function(app) {
                    var stage = app.stage;
                    for (var i = 0; i < stage.children.length; i++) {
                        var child = stage.children[i];
                        // 检测是否为 Live2D 模型（pixi-live2d-display 的 Live2DModel）
                        if (child.internalModel || child.constructor.name === 'Live2DModel') {
                            // 计算缩放因子：保持模型在视口内完整显示
                            var modelWidth = child.width / (child.scale.x || 1);
                            var modelHeight = child.height / (child.scale.y || 1);
                            if (modelWidth > 0 && modelHeight > 0) {
                                var scaleX = w / modelWidth;
                                var scaleY = h / modelHeight;
                                // 取较小值，确保模型完全可见
                                var scale = Math.min(scaleX, scaleY) * 0.85;
                                child.scale.set(scale);
                                // 居中定位
                                child.x = (w - child.width) / 2;
                                child.y = (h - child.height) / 2;
                            }
                        }
                    }
                });
            }
        } catch(e) {}
    }

    // ── E. PixiJS renderer resize 触发 ──
    function triggerPixiResize() {
        try {
            if (typeof PIXI !== 'undefined') {
                var w = window.innerWidth;
                var h = window.innerHeight;
                var apps = [];
                if (window.app && window.app.renderer) apps.push(window.app);
                if (window.pixiApp && window.pixiApp.renderer) apps.push(window.pixiApp);

                apps.forEach(function(app) {
                    app.renderer.resize(w, h);
                });
            }
        } catch(e) {}
    }

    // ── F. 防闪跳：批量 DOM 操作 ──
    var resizeRAF = null;
    function requestSync() {
        if (resizeRAF) return;
        resizeRAF = requestAnimationFrame(function() {
            resizeRAF = null;
            syncCanvasSize();
            triggerPixiResize();
            applyAspectRatioScaling();
            // PixiJS 渲染一帧后恢复 canvas 不透明，避免黑帧
            requestAnimationFrame(function() {
                var canvases = document.querySelectorAll('canvas');
                canvases.forEach(function(c) {
                    if (c.style.opacity === '0') {
                        c.style.opacity = '1';
                    }
                });
            });
        });
    }

    // ── G. resize 监听（防抖 + 多次修正） ──
    var resizeTimer = null;
    window.addEventListener('resize', function() {
        requestSync();
        clearTimeout(resizeTimer);
        resizeTimer = setTimeout(function() {
            requestSync();
            setTimeout(requestSync, 150);
        }, 100);
    }, { passive: true });

    // ── H. orientationchange 监听 ──
    window.addEventListener('orientationchange', function() {
        setTimeout(function() {
            requestSync();
            setTimeout(requestSync, 200);
            setTimeout(requestSync, 500);
        }, 50);
    });

    // ── I. 初始修正 ──
    // 强制滚动到顶部，确保内容从正确位置开始
    window.scrollTo(0, 0);
    document.documentElement.scrollTop = 0;
    document.body.scrollTop = 0;
    requestSync();
    setTimeout(function() {
        window.scrollTo(0, 0);
        requestSync();
    }, 100);
    setTimeout(function() {
        window.scrollTo(0, 0);
        requestSync();
    }, 300);
    setTimeout(requestSync, 800);

    // ── J. 禁止页面自动滚动（如 hash 导航） ──
    if ('scrollRestoration' in history) {
        history.scrollRestoration = 'manual';
    }

    // ── K. MutationObserver：监听动态添加的 canvas ──
    var observer = new MutationObserver(function(mutations) {
        var hasNewCanvas = false;
        mutations.forEach(function(m) {
            m.addedNodes.forEach(function(node) {
                if (node.nodeName === 'CANVAS' || (node.querySelector && node.querySelector('canvas'))) {
                    hasNewCanvas = true;
                }
            });
        });
        if (hasNewCanvas) {
            requestSync();
        }
    });
    observer.observe(document.documentElement, { childList: true, subtree: true });
})();
"""

/**
 * 监听 Activity 配置变更（横竖屏切换），强制 WebView 重新布局并通知网页更新 canvas。
 *
 * 由于 AndroidManifest 中设置了 configChanges，Activity 不会重建，
 * 但 WebView 的内部渲染表面可能未正确更新尺寸。
 * 此监听器在方向变更时：
 * 1. 强制 WebView requestLayout 重新计算布局
 * 2. 注入 JS 触发 canvas 尺寸修正 + PixiJS renderer resize
 */
@SuppressLint("ClickableViewAccessibility")
private fun WebView.installOrientationChangeGuard() {
    val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        // 延迟 150ms 后再修正 canvas 尺寸，等待 WebView 内部布局稳定
        // 避免在布局过渡期间修改 canvas 产生黑帧
        postDelayed({
            evaluateJavascript(
                "(function(){" +
                "var w=window.innerWidth,h=window.innerHeight;" +
                "var cs=document.querySelectorAll('canvas');" +
                "cs.forEach(function(c){" +
                "var tw=Math.round(w),th=Math.round(h);" +
                "if(c.width!==tw||c.height!==th){" +
                // 防黑闪：先设为透明
                "c.style.opacity='0';" +
                "c.width=tw;c.height=th;" +
                "}" +
                "});" +
                "try{if(window.app&&window.app.renderer)window.app.renderer.resize(w,h);" +
                "if(window.pixiApp&&window.pixiApp.renderer)window.pixiApp.renderer.resize(w,h);}catch(e){}" +
                // PixiJS 渲染一帧后恢复不透明
                "requestAnimationFrame(function(){" +
                "cs.forEach(function(c){c.style.opacity='1';});" +
                "});" +
                "})();",
                null
            )
        }, 150)
    }
    setTag(R.id.live2d_layout_listener_tag, listener)
    addOnLayoutChangeListener(listener)
}

@Suppress("UNCHECKED_CAST")
private fun WebView.removeOrientationChangeGuard() {
    val listener = getTag(R.id.live2d_layout_listener_tag) as? View.OnLayoutChangeListener
    if (listener != null) {
        removeOnLayoutChangeListener(listener)
    }
}

@Suppress("DEPRECATION")
private fun WebSettings.configureForLive2D() {
    javaScriptEnabled = true
    domStorageEnabled = true
    databaseEnabled = true
    loadsImagesAutomatically = true
    blockNetworkImage = false
    cacheMode = WebSettings.LOAD_DEFAULT
    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    mediaPlaybackRequiresUserGesture = false
    // 不使用 useWideViewPort / loadWithOverviewMode，
    // 它们会创建虚拟视口导致 WebGL canvas 尺寸与实际屏幕不匹配，
    // 横竖屏切换时出现四等分渲染异常
    textZoom = 100
    defaultTextEncodingName = "utf-8"
    allowFileAccess = false
    allowContentAccess = false
    javaScriptCanOpenWindowsAutomatically = false
    setSupportMultipleWindows(false)
    setSupportZoom(false)
    builtInZoomControls = false
    displayZoomControls = false

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        offscreenPreRaster = true
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        safeBrowsingEnabled = true
    }
}

private fun configureLive2DServiceWorker() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        ServiceWorkerController.getInstance().serviceWorkerWebSettings.apply {
            allowContentAccess = false
            allowFileAccess = false
            blockNetworkLoads = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
private fun WebView.installResponsiveTouchGuard() {
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                view.parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                view.parent?.requestDisallowInterceptTouchEvent(false)
        }
        false
    }
}

private fun WebView.releaseLive2D() {
    runCatching { setOnTouchListener(null) }
    runCatching { removeOrientationChangeGuard() }
    runCatching { removeCallbacks(null) }
    runCatching { stopLoading() }
    runCatching { webChromeClient = null; webViewClient = WebViewClient() }
    runCatching { loadUrl("about:blank") }
    runCatching { clearHistory() }
    runCatching { removeAllViews() }
    runCatching { onPause(); pauseTimers() }
    runCatching { destroy() }
}

// ────────────────────────── URL / 错误工具 ──────────────────────────

private fun String?.isWebPageUrl(): Boolean =
    this?.startsWith("http://") == true || this?.startsWith("https://") == true

private fun WebResourceError?.toFriendlyMessage(): String = when (this?.errorCode) {
    WebViewClient.ERROR_HOST_LOOKUP -> "无法解析网页地址，请检查网络或 DNS 设置"
    WebViewClient.ERROR_CONNECT -> "无法连接到 Live2D 服务，请稍后重试"
    WebViewClient.ERROR_TIMEOUT -> "加载超时，请切换网络后重试"
    WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> "安全连接握手失败，请确认系统时间与网络环境"
    WebViewClient.ERROR_TOO_MANY_REQUESTS -> "请求过于频繁，请稍后再试"
    else -> this?.description?.toString() ?: "页面加载失败，请检查网络连接后重试"
}

private fun WebResourceResponse?.toFriendlyMessage(): String {
    val statusCode = this?.statusCode ?: return "服务器响应异常，请稍后重试"
    val reason = this.reasonPhrase?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
    return "服务器返回 $statusCode$reason，请稍后重试"
}

private fun SslError?.toFriendlyMessage(): String = when (this?.primaryError) {
    SslError.SSL_DATE_INVALID -> "网页证书日期无效，请检查系统时间"
    SslError.SSL_EXPIRED -> "网页证书已过期，已停止加载以保护连接安全"
    SslError.SSL_IDMISMATCH -> "网页证书域名不匹配，已停止加载"
    SslError.SSL_NOTYETVALID -> "网页证书尚未生效，请检查系统时间"
    SslError.SSL_UNTRUSTED -> "网页证书不受信任，已停止加载"
    else -> "安全连接校验失败，已停止加载"
}

private fun RenderProcessGoneDetail?.toFriendlyMessage(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && this?.didCrash() == true) {
        "Live2D 渲染进程异常退出，请重试加载"
    } else {
        "系统已回收网页渲染资源，请重试加载"
    }

// ────────────────────────── 加载 / 错误覆盖层 ──────────────────────────

@Composable
private fun LoadingOverlay(progress: Int) {
    val isDark = isSystemInDarkTheme()
    val bgColor = if (isDark) Color(0xFF0D1117) else Color(0xFFF6F8FA)
    val boundedProgress = progress.coerceIn(0, COMPLETE_PROGRESS)

    Box(
        modifier = Modifier.fillMaxSize().background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Adb,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.Xl))

            Box(
                modifier = Modifier.size(56.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = { boundedProgress / 100f },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
                Text(
                    text = "$boundedProgress%",
                    style = AppTypography.LabelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.Lg))

            Text(
                text = "正在加载Live2D模型库",
                style = AppTypography.BodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(AppSpacing.Xs))

            Text(
                text = "请稍候...",
                style = AppTypography.BodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun ErrorOverlay(
    message: String,
    onRetry: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val bgColor = if (isDark) Color(0xFF0D1117) else Color(0xFFF6F8FA)
    val displayMessage = message.ifBlank { "页面加载失败，请检查网络连接后重试" }

    Box(
        modifier = Modifier.fillMaxSize().background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(AppSpacing.Xxl)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "!",
                    style = AppTypography.EmptyTitle.copy(fontSize = AppTypography.EmptyTitle.fontSize * 1.3f),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.Xl))

            Text(
                text = "加载失败",
                style = AppTypography.TitleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(AppSpacing.Sm))

            Text(
                text = displayMessage,
                style = AppTypography.BodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(AppSpacing.Xl))

            Surface(
                onClick = onRetry,
                modifier = Modifier.clip(RoundedCornerShape(AppSpacing.Corner.Lg)),
                shape = RoundedCornerShape(AppSpacing.Corner.Lg),
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = AppSpacing.Elevation.Sm
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = AppSpacing.Padding.ButtonHorizontal,
                        vertical = AppSpacing.Padding.ButtonVertical
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.Sm))
                    Text(
                        text = "重试",
                        style = AppTypography.ButtonText,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}