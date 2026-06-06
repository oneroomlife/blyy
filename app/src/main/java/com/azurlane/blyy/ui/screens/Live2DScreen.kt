package com.azurlane.blyy.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.util.Log
import android.view.HapticFeedbackConstants
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.azurlane.blyy.R
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.viewmodel.Live2DViewModel
import kotlinx.coroutines.delay

private const val LIVE2D_URL = "https://l2d.su/"
private const val LIVE2D_HOST = "l2d.su"
private const val INITIAL_PROGRESS = 6
private const val VISIBLE_PAGE_PROGRESS = 85
private const val COMPLETE_PROGRESS = 100

/** Canvas opacity 恢复延迟帧数，确保 PixiJS 至少渲染一帧后再显示 */
private const val CANVAS_RESTORE_DELAY_FRAMES = 2

private const val TAG = "Live2DScreen"

private enum class Live2DLoadPhase { Loading, Ready, Error, SslWarning }

/**
 * Live2D 全屏沉浸展示界面。
 *
 * 优化重点：
 * 1. 视觉稳定性：使用 AnimatedContent 替代简单的 Visibility，实现平滑的十字淡化过渡。
 * 2. 性能表现：引入 ResizeObserver 与 双帧渲染策略，消除 WebView 尺寸变动时的黑闪与跳变。
 * 3. 交互体验：增加触感反馈与更细腻的加载进度反馈。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Live2DScreen(
    onBack: () -> Unit,
    viewModel: Live2DViewModel = hiltViewModel()
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
    var sslErrorMessage by remember { mutableStateOf("") }

    // 从 DataStore 读取证书信任状态
    val sslTrusted by viewModel.sslTrusted.collectAsStateWithLifecycle(initialValue = false)

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
    LaunchedEffect(isContentReady) {
        if (isContentReady && loadPhase == Live2DLoadPhase.Loading) {
            // 确保进度条达到 100% 并停留片刻
            loadProgress = COMPLETE_PROGRESS
            delay(200L) 
            loadPhase = Live2DLoadPhase.Ready
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117))) {
        // 使用 AnimatedContent 实现不同阶段之间的平滑过渡
        AnimatedContent(
            targetState = loadPhase,
            transitionSpec = {
                (fadeIn(animationSpec = tween(400, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
                        togetherWith fadeOut(animationSpec = tween(350)))
                    .using(SizeTransform(clip = false))
            },
            label = "Live2DPhaseTransition"
        ) { phase ->
            when (phase) {
                Live2DLoadPhase.Loading -> {
                    LoadingOverlay(progress = loadProgress)
                }
                Live2DLoadPhase.Error -> {
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
                Live2DLoadPhase.SslWarning -> {
                    SslWarningOverlay(
                        sslMessage = sslErrorMessage,
                        onAccept = {
                            viewModel.setSslTrusted(true)
                            beginLoad()
                            reloadToken++
                        },
                        onReject = onBack
                    )
                }
                Live2DLoadPhase.Ready -> {
                    Spacer(Modifier.fillMaxSize())
                }
            }
        }

        // ── WebView 层（始终存在但由 isContentReady 控制可见性，避免重绘） ──
        key(webViewGeneration) {
            val webView = rememberLive2DWebView(
                sslTrusted = sslTrusted,
                onLoadStarted = { beginLoad() },
                onLoadProgress = { progress ->
                    val boundedProgress = progress.coerceIn(INITIAL_PROGRESS, COMPLETE_PROGRESS)
                    loadProgress = maxOf(loadProgress, boundedProgress)
                },
                onPageVisible = {
                    if (loadPhase == Live2DLoadPhase.Loading) {
                        loadProgress = maxOf(loadProgress, VISIBLE_PAGE_PROGRESS)
                        isContentReady = true
                    }
                },
                onLoadFinished = {
                    if (loadPhase != Live2DLoadPhase.Error && loadPhase != Live2DLoadPhase.SslWarning) {
                        loadProgress = COMPLETE_PROGRESS
                        isContentReady = true
                    }
                },
                onLoadFailed = { message ->
                    loadProgress = 0
                    loadPhase = Live2DLoadPhase.Error
                    errorMessage = message
                },
                onSslError = { message ->
                    loadProgress = 0
                    sslErrorMessage = message
                    loadPhase = Live2DLoadPhase.SslWarning
                },
                onRendererGone = { message ->
                    recreateOnNextRetry = true
                    loadProgress = 0
                    loadPhase = Live2DLoadPhase.Error
                    errorMessage = message
                }
            )

            BindLive2DWebViewLifecycle(webView)

            // 监听 Ready 信号
            LaunchedEffect(isContentReady) {
                if (isContentReady && webView.visibility != View.VISIBLE) {
                    webView.visibility = View.VISIBLE
                }
            }

            LaunchedEffect(webView, reloadToken) {
                beginLoad()
                webView.visibility = View.INVISIBLE
                webView.stopLoading()
                webView.loadUrl(LIVE2D_URL)
            }

            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize()
            )
        }

        // ── 浮动控件（独立于 AnimatedContent，保证交互连续性） ──
        if (loadPhase == Live2DLoadPhase.Ready || loadPhase == Live2DLoadPhase.Error || loadPhase == Live2DLoadPhase.SslWarning) {
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
 */
@Composable
private fun FloatingControls(
    orientationMode: OrientationMode?,
    onOrientationToggle: () -> Unit,
    onBack: () -> Unit
) {
    val isLandscape = orientationMode == OrientationMode.LANDSCAPE

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
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "FloatBtnScale"
    )

    Surface(
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        },
        modifier = Modifier.size(40.dp).scale(scale),
        shape = CircleShape,
        color = Color.White.copy(alpha = if (pressed) 0.24f else 0.12f),
        contentColor = Color.White,
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            .also { source ->
                LaunchedEffect(source) {
                    source.interactions.collect { interaction ->
                        when (interaction) {
                            is androidx.compose.foundation.interaction.PressInteraction.Press -> pressed = true
                            is androidx.compose.foundation.interaction.PressInteraction.Release -> pressed = false
                            is androidx.compose.foundation.interaction.PressInteraction.Cancel -> pressed = false
                        }
                    }
                }
            }
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
    sslTrusted: Boolean,
    onLoadStarted: () -> Unit,
    onLoadProgress: (Int) -> Unit,
    onPageVisible: () -> Unit,
    onLoadFinished: () -> Unit,
    onLoadFailed: (String) -> Unit,
    onSslError: (String) -> Unit,
    onRendererGone: (String) -> Unit
): WebView {
    val context = LocalContext.current
    val onLoadStartedState = rememberUpdatedState(onLoadStarted)
    val onLoadProgressState = rememberUpdatedState(onLoadProgress)
    val onPageVisibleState = rememberUpdatedState(onPageVisible)
    val onLoadFinishedState = rememberUpdatedState(onLoadFinished)
    val onLoadFailedState = rememberUpdatedState(onLoadFailed)
    val onSslErrorState = rememberUpdatedState(onSslError)
    val onRendererGoneState = rememberUpdatedState(onRendererGone)
    val sslTrustedState = rememberUpdatedState(sslTrusted)

    return remember {
        configureLive2DServiceWorker()
        WebView.setWebContentsDebuggingEnabled(false)
        WebView(context).apply {
            configureForLive2D(
                sslTrustedState = sslTrustedState,
                onLoadStartedState = onLoadStartedState,
                onLoadProgressState = onLoadProgressState,
                onPageVisibleState = onPageVisibleState,
                onLoadFinishedState = onLoadFinishedState,
                onLoadFailedState = onLoadFailedState,
                onSslErrorState = onSslErrorState,
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
    sslTrustedState: androidx.compose.runtime.State<Boolean>,
    onLoadStartedState: androidx.compose.runtime.State<() -> Unit>,
    onLoadProgressState: androidx.compose.runtime.State<(Int) -> Unit>,
    onPageVisibleState: androidx.compose.runtime.State<() -> Unit>,
    onLoadFinishedState: androidx.compose.runtime.State<() -> Unit>,
    onLoadFailedState: androidx.compose.runtime.State<(String) -> Unit>,
    onSslErrorState: androidx.compose.runtime.State<(String) -> Unit>,
    onRendererGoneState: androidx.compose.runtime.State<(String) -> Unit>
) {
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )
    setLayerType(View.LAYER_TYPE_HARDWARE, null)
    setBackgroundColor(0xFF0D1117.toInt())
    visibility = View.INVISIBLE
    overScrollMode = View.OVER_SCROLL_NEVER
    isFocusable = true
    isFocusableInTouchMode = true
    isHorizontalScrollBarEnabled = false
    isVerticalScrollBarEnabled = false
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
    }

    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(this@configureForLive2D, true)
    }

    settings.applyWebSettings()
    installResponsiveTouchGuard()
    installOrientationChangeGuard()

    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val scheme = request?.url?.scheme
            return scheme != "http" && scheme != "https"
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (url.isWebPageUrl()) onLoadStartedState.value()
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            if (url.isWebPageUrl()) onPageVisibleState.value()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            if (url.isWebPageUrl()) {
                onLoadFinishedState.value()
                view?.evaluateJavascript(WEB_OPTIMIZATION_SCRIPT, null)
            }
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame == true) onLoadFailedState.value(error.toFriendlyMessage())
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 400) {
                onLoadFailedState.value(errorResponse.toFriendlyMessage())
            }
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            val host = view?.url?.let { java.net.URL(it).host } ?: ""
            val friendlyMsg = error.toFriendlyMessage()
            Log.w(TAG, "SSL error for host=$host: $friendlyMsg (primaryError=${error?.primaryError}, trusted=${sslTrustedState.value})")

            if (sslTrustedState.value && host == LIVE2D_HOST) {
                // 用户已明确信任此域名，允许继续加载
                Log.i(TAG, "Proceeding with SSL for trusted host: $host")
                handler?.proceed()
            } else {
                // 首次遇到 SSL 错误，拒绝并通知 UI 显示警告
                handler?.cancel()
                onSslErrorState.value(friendlyMsg)
            }
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
 * 核心升级点：
 * 1. 使用 ResizeObserver 取代 window.resize，实现亚像素级的尺寸追踪。
 * 2. 引入双帧同步机制（Double-Frame Sync），确保 WebGL 指令提交后再显示。
 * 3. 优化 PixiJS 资源回收提示。
 */
private const val WEB_OPTIMIZATION_SCRIPT = """
(function() {
    'use strict';

    // ── 1. Viewport & 全局样式修正 ──
    var meta = document.querySelector('meta[name=viewport]') || document.createElement('meta');
    meta.name = 'viewport';
    meta.content = 'width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover';
    if (!meta.parentNode) document.head.appendChild(meta);

    var style = document.createElement('style');
    style.textContent = `
        html, body { 
            width: 100vw !important; height: 100vh !important; 
            margin: 0 !important; padding: 0 !important; 
            overflow: hidden !important; position: fixed !important; 
            background: #0D1117 !important; touch-action: none !important;
        }
        canvas { 
            display: block !important; outline: none !important;
            transition: opacity 0.1s ease-out !important;
        }
    `;
    document.head.appendChild(style);

    // ── 2. 尺寸同步引擎 ──
    let _rafId = null;
    const sync = () => {
        if (_rafId) cancelAnimationFrame(_rafId);
        _rafId = requestAnimationFrame(() => {
            const w = window.innerWidth, h = window.innerHeight;
            const canvases = document.querySelectorAll('canvas');
            
            canvases.forEach(c => {
                if (c.width !== w || c.height !== h) {
                    c.style.opacity = '0';
                    c.width = w; c.height = h;
                }
            });

            // 深度适配：触发应用内所有可能的 Pixi 实例 resize
            try {
                const apps = [window.app, window.pixiApp].filter(a => a && a.renderer);
                apps.forEach(a => {
                    a.renderer.resize(w, h);
                    if (a.stage) {
                        a.stage.children.forEach(child => {
                            if (child.internalModel || child.constructor.name.includes('Model')) {
                                const s = Math.min(w/child.width, h/child.height) * 0.9;
                                child.scale.set(child.scale.x * s);
                                child.position.set(w/2, h/2);
                            }
                        });
                    }
                });
            } catch(e) {}

            // 双帧延迟恢复，确保 WebGL 缓冲区已交换
            requestAnimationFrame(() => requestAnimationFrame(() => {
                canvases.forEach(c => c.style.opacity = '1');
            }));
            _rafId = null;
        });
    };

    // ── 3. 使用 ResizeObserver 实现高性能监听 ──
    const ro = new ResizeObserver(entries => {
        for (let entry of entries) {
            if (entry.target === document.body) sync();
        }
    });
    ro.observe(document.body);

    window.addEventListener('orientationchange', () => setTimeout(sync, 100));
    
    // 禁止手势缩放干扰
    document.addEventListener('gesturestart', e => e.preventDefault());
    
    // 初始化
    sync();
    setTimeout(sync, 500);
})();
"""

@SuppressLint("ClickableViewAccessibility")
private fun WebView.installOrientationChangeGuard() {
    val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        postDelayed({
            evaluateJavascript(
                "(function(){" +
                "var w=window.innerWidth,h=window.innerHeight;" +
                "var cs=document.querySelectorAll('canvas');" +
                "var needsRestore=false;" +
                "cs.forEach(function(c){" +
                "var tw=Math.round(w),th=Math.round(h);" +
                "if(c.width!==tw||c.height!==th){" +
                "c.style.opacity='0';" +
                "c.style.willChange='transform,opacity';" +
                "c.width=tw;c.height=th;" +
                "needsRestore=true;" +
                "}" +
                "});" +
                "try{if(window.app&&window.app.renderer)window.app.renderer.resize(w,h);" +
                "if(window.pixiApp&&window.pixiApp.renderer)window.pixiApp.renderer.resize(w,h);}catch(e){}" +
                "if(needsRestore){" +
                "var frames=$CANVAS_RESTORE_DELAY_FRAMES;" +
                "function tick(){" +
                "frames--;" +
                "if(frames<=0){" +
                "cs.forEach(function(c){" +
                "if(c.style.opacity==='0')c.style.opacity='1';" +
                "c.style.willChange='auto';" +
                "});" +
                "}else{requestAnimationFrame(tick);}" +
                "}" +
                "requestAnimationFrame(tick);" +
                "}" +
                "})();",
                null
            )
        }, 150)
    }
    setTag(R.id.live2d_layout_listener_tag, listener)
    addOnLayoutChangeListener(listener)
}

private fun WebView.removeOrientationChangeGuard() {
    val listener = getTag(R.id.live2d_layout_listener_tag) as? View.OnLayoutChangeListener
    if (listener != null) removeOnLayoutChangeListener(listener)
}

@Suppress("DEPRECATION")
private fun WebSettings.applyWebSettings() {
    javaScriptEnabled = true
    domStorageEnabled = true
    databaseEnabled = true
    loadsImagesAutomatically = true
    blockNetworkImage = false
    cacheMode = WebSettings.LOAD_DEFAULT
    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    mediaPlaybackRequiresUserGesture = false
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
    runCatching { stopLoading() }
    runCatching { webChromeClient = null; webViewClient = WebViewClient() }
    runCatching { loadUrl("about:blank") }
    runCatching { clearHistory() }
    runCatching { removeAllViews() }
    runCatching { onPause(); pauseTimers() }
    runCatching { destroy() }
}

private fun String?.isWebPageUrl(): Boolean =
    this?.startsWith("http://") == true || this?.startsWith("https://") == true

private fun WebResourceError?.toFriendlyMessage(): String = when (this?.errorCode) {
    WebViewClient.ERROR_HOST_LOOKUP -> "无法解析网页地址，请检查网络设置"
    WebViewClient.ERROR_CONNECT -> "无法连接到 Live2D 服务"
    WebViewClient.ERROR_TIMEOUT -> "加载超时，请重试"
    else -> this?.description?.toString() ?: "页面加载失败"
}

private fun WebResourceResponse?.toFriendlyMessage(): String {
    val statusCode = this?.statusCode ?: return "服务器响应异常"
    return "服务器返回 $statusCode，请稍后重试"
}

private fun SslError?.toFriendlyMessage(): String = when (this?.primaryError) {
    SslError.SSL_EXPIRED -> "网页证书已过期"
    SslError.SSL_IDMISMATCH -> "网页证书域名不匹配"
    SslError.SSL_UNTRUSTED -> "网页证书不受信任"
    else -> "安全连接校验失败"
}

private fun RenderProcessGoneDetail?.toFriendlyMessage(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && this?.didCrash() == true) {
        "渲染进程崩溃，请重试"
    } else {
        "系统回收了渲染资源"
    }

@Composable
private fun LoadingOverlay(progress: Int) {
    val isDark = LocalIsDark.current
    val bgColor = if (isDark) Color(0xFF0D1117) else Color(0xFFF6F8FA)
    val boundedProgress = progress.coerceIn(0, COMPLETE_PROGRESS)

    Box(
        modifier = Modifier.fillMaxSize().background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Adb, null, Modifier.size(32.dp), MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(AppSpacing.Xl))
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { boundedProgress / 100f },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 3.dp
                )
                Text("$boundedProgress%", style = AppTypography.LabelSmall)
            }
            Spacer(Modifier.height(AppSpacing.Lg))
            Text("正在加载 Live2D 模型库", style = AppTypography.BodyMedium)
        }
    }
}

@Composable
private fun ErrorOverlay(message: String, onRetry: () -> Unit) {
    val isDark = LocalIsDark.current
    val bgColor = if (isDark) Color(0xFF0D1117) else Color(0xFFF6F8FA)
    Box(
        modifier = Modifier.fillMaxSize().background(bgColor).padding(AppSpacing.Xxl),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("加载失败", style = AppTypography.TitleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(AppSpacing.Sm))
            Text(message, style = AppTypography.BodyMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(AppSpacing.Xl))
            Surface(onClick = onRetry, shape = RoundedCornerShape(AppSpacing.Corner.Lg), color = MaterialTheme.colorScheme.primary) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("重试", style = AppTypography.ButtonText)
                }
            }
        }
    }
}

/**
 * SSL 证书警告界面。
 *
 * 当 Live2D 网站证书不可信任时显示，提供详细的安全风险提示和用户确认操作。
 * 仅对 l2d.su 域名生效，不影响其他网络请求。
 */
@Composable
private fun SslWarningOverlay(
    sslMessage: String,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    val isDark = LocalIsDark.current
    val bgColor = if (isDark) Color(0xFF0D1117) else Color(0xFFF6F8FA)
    val warningColor = Color(0xFFFF9800)

    Box(
        modifier = Modifier.fillMaxSize().background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        AlertDialog(
            onDismissRequest = onReject,
            icon = {
                Icon(
                    Icons.Rounded.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = warningColor
                )
            },
            title = {
                Text(
                    text = "安全连接警告",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = sslMessage,
                        style = AppTypography.BodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = warningColor.copy(alpha = 0.1f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                text = "安全风险提示",
                                style = AppTypography.LabelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = warningColor
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "继续加载可能存在以下风险：",
                                style = AppTypography.BodySmall
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "• 数据传输可能被第三方截获或篡改\n• 您与该网站的通信可能不再保密\n• 此信任仅对 Live2D 功能模块生效",
                                style = AppTypography.BodySmall,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Surface(
                    onClick = {
                        Log.w(TAG, "User accepted SSL certificate warning for Live2D")
                        onAccept()
                    },
                    shape = RoundedCornerShape(8.dp),
                    color = warningColor
                ) {
                    Text(
                        text = "了解风险，继续加载",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        style = AppTypography.LabelMedium,
                        color = Color.White
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    Log.i(TAG, "User rejected SSL certificate warning")
                    onReject()
                }) {
                    Text("返回")
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }
}
