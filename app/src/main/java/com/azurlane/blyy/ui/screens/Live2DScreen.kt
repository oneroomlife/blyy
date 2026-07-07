package com.azurlane.blyy.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
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
import android.widget.Toast
import androidx.annotation.RequiresApi
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Adb
import androidx.compose.material.icons.rounded.ContentCopy
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
import com.azurlane.blyy.viewmodel.Live2DLoadPhase
import com.azurlane.blyy.viewmodel.Live2DViewModel
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest

private const val LIVE2D_URL = "https://l2d.su/cn/"
private const val LIVE2D_HOST = "l2d.su"
/** l2d.su 站点主题色（浅色），用于 WebView 背景以消除深色闪屏 */
private const val SITE_THEME_COLOR = 0xFFF4F8FF.toInt()

/**
 * 完整模拟 Chrome Mobile 发起的合规网络请求头。
 *
 * 核心防线：`X-Requested-With` 覆盖为空字符串，彻底阻止 Android WebView 自动泄漏 App 包名
 * （com.azurlane.blyy），这是 Cloudflare/DDoS-GUARD 等 WAF 判定 bot 抓取的第一大元凶。
 *
 * - `Sec-Fetch-Site: none`：表示用户直接输入 URL 的初始导航（与真实 Chrome 行为一致）
 * - `Sec-Fetch-Dest/Mode/User`：Fetch Metadata 头，真实 Chrome 自动发送
 * - `Upgrade-Insecure-Requests`：真实 Chrome 导航必带
 * - `Accept`：标准 Chrome 导航 Accept 头
 */
private val MAIN_REQUEST_HEADERS = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
    "Sec-Fetch-Dest" to "document",
    "Sec-Fetch-Mode" to "navigate",
    "Sec-Fetch-Site" to "none",
    "Sec-Fetch-User" to "?1",
    "Upgrade-Insecure-Requests" to "1",
    "X-Requested-With" to "" // 【核心防线】：覆盖系统默认注入的 com.azurlane.blyy
)

/**
 * 构建完美合规的标准移动端 Chrome User-Agent。
 *
 * 不采用粗暴 replace（MIUI/HyperOS/ColorOS/EMUI 等 ROM 魔改标记无法通过 replace 彻底清除），
 * 而是提取当前系统 Android 版本号，合成标准无瑕疵的 Chrome Mobile UA。
 * 彻底抹除：`; wv`、`Version/4.0`、`XiaoMi/MiuiBrowser/`、`OppoBrowser/` 等厂商定制标记。
 */
private fun buildCleanBrowserUserAgent(): String {
    val androidVersion = Build.VERSION.RELEASE ?: "13"
    return "Mozilla/5.0 (Linux; Android $androidVersion; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
}

/** 403/503 自动重试延迟（毫秒）。给 Cloudflare JS Challenge/Turnstile 充分执行并种 cf_clearance Cookie 的时间。 */
private const val FORBIDDEN_RETRY_DELAY_MS = 3000L

private const val INITIAL_PROGRESS = 6
private const val VISIBLE_PAGE_PROGRESS = 85
private const val COMPLETE_PROGRESS = 100

/** 加载超时时间（毫秒） */
private const val LOAD_TIMEOUT_MS = 45_000L

/** 加载卡住检测阈值：进度在指定时间内无变化则判定为卡住 */
private const val STUCK_CHECK_INTERVAL_MS = 5_000L
private const val STUCK_THRESHOLD_MS = 15_000L

/** ZSTD 一次性解压最大字节数（256MB），防止 declaredSize 异常导致 OOM */
private const val MAX_DECOMPRESSED_SIZE = 256L * 1024 * 1024

private const val TAG = "Live2DScreen"

/**
 * 【Fix J 2026-07-06】ZSTD 解压失败重试专用的 OkHttp 客户端。
 *
 * HttpURLConnection 对某些 chunked transfer-encoding 响应处理异常，导致大 .moc3 文件
 * ZSTD 解压报 "Data corruption detected"（错误码 20，bitstream 结构性损坏）。
 * OkHttp 使用完全独立的 HTTP/1.1 实现，chunked 解码更健壮，可绕过 HttpURLConnection 的 bug。
 *
 * 重试使用 OkHttp 而非 HttpURLConnection，显著提升大 .moc3 文件加载成功率。
 */
private val zstdRetryClient: OkHttpClient by lazy {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .readTimeout(java.time.Duration.ofSeconds(60))
            .retryOnConnectionFailure(true)
            .build()
    } else {
        TODO("VERSION.SDK_INT < O")
    }
}

/**
 * Live2D 全屏沉浸展示界面。
 *
 * 优化重点：
 * 1. 视觉稳定性：使用 AnimatedContent 替代简单的 Visibility，实现平滑的十字淡化过渡。
 * 2. 性能表现：引入 ResizeObserver 与 双帧渲染策略，消除 WebView 尺寸变动时的黑闪与跳变。
 * 3. 交互体验：增加触感反馈与更细腻的加载进度反馈。
 * 4. 可靠性：加载超时检测、卡住检测、WebGL 状态监控、JavaScript 错误捕获。
 * 5. 可诊断性：完整的错误信息复制功能，包含设备信息、加载阶段、控制台日志等。
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
        viewModel.updateLoadState {
            it.copy(
                phase = Live2DLoadPhase.Loading,
                progress = INITIAL_PROGRESS,
                errorMessage = "",
                sslErrorMessage = "",
                sslErrorCode = -1,
                loadStartTimeMs = System.currentTimeMillis(),
                pageStartedTimeMs = 0L,
                pageVisibleTimeMs = 0L,
                pageFinishedTimeMs = 0L,
                errorTimeMs = 0L,
                consoleErrors = emptyList(),
                webGLStatus = "unknown",
                requestLogs = emptyList()
            )
        }
    }

    // 页面加载完成后延迟切换到 Ready，给 WebGL canvas 时间渲染首帧
    LaunchedEffect(isContentReady) {
        if (isContentReady && loadPhase == Live2DLoadPhase.Loading) {
            // 确保进度条达到 100% 并停留片刻
            loadProgress = COMPLETE_PROGRESS
            delay(200L)
            loadPhase = Live2DLoadPhase.Ready
            viewModel.updateLoadState { it.copy(phase = Live2DLoadPhase.Ready, progress = COMPLETE_PROGRESS) }
        }
    }

    // ── 加载超时检测 ──
    LaunchedEffect(reloadToken, webViewGeneration) {
        val startTime = System.currentTimeMillis()
        var lastProgress = INITIAL_PROGRESS
        var lastProgressTime = startTime

        while (loadPhase == Live2DLoadPhase.Loading) {
            delay(STUCK_CHECK_INTERVAL_MS)
            val elapsed = System.currentTimeMillis() - startTime

            // 总超时检测
            if (elapsed > LOAD_TIMEOUT_MS) {
                Log.w(TAG, "Load timeout after ${elapsed}ms, progress=$loadProgress")
                loadPhase = Live2DLoadPhase.Error
                errorMessage = "加载超时（${elapsed / 1000}秒），请检查网络后重试"
                viewModel.updateLoadState {
                    it.copy(
                        phase = Live2DLoadPhase.Error,
                        errorMessage = errorMessage,
                        errorTimeMs = System.currentTimeMillis()
                    )
                }
                break
            }

            // 卡住检测：进度长时间无变化
            if (loadProgress != lastProgress) {
                lastProgress = loadProgress
                lastProgressTime = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - lastProgressTime > STUCK_THRESHOLD_MS) {
                Log.w(TAG, "Load stuck at ${loadProgress}% for ${STUCK_THRESHOLD_MS}ms")
                loadPhase = Live2DLoadPhase.Error
                errorMessage = "加载停滞在 ${loadProgress}%，请重试"
                viewModel.updateLoadState {
                    it.copy(
                        phase = Live2DLoadPhase.Error,
                        errorMessage = errorMessage,
                        errorTimeMs = System.currentTimeMillis()
                    )
                }
                break
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(SITE_THEME_COLOR))) {
        // ── WebView 底层（始终全屏 VISIBLE、alpha=1，绝不修改 visibility/alpha，确保 GPU 管道无阻塞） ──
        key(webViewGeneration) {
            val webView = rememberLive2DWebView(
                sslTrusted = sslTrusted,
                onLoadStarted = {
                    beginLoad()
                    viewModel.updateLoadState { it.copy(pageStartedTimeMs = System.currentTimeMillis()) }
                },
                onLoadProgress = { progress ->
                    val boundedProgress = progress.coerceIn(INITIAL_PROGRESS, COMPLETE_PROGRESS)
                    loadProgress = maxOf(loadProgress, boundedProgress)
                    viewModel.updateLoadState { it.copy(progress = loadProgress) }
                },
                onPageVisible = {
                    if (loadPhase == Live2DLoadPhase.Loading) {
                        loadProgress = maxOf(loadProgress, VISIBLE_PAGE_PROGRESS)
                        isContentReady = true
                        viewModel.updateLoadState {
                            it.copy(pageVisibleTimeMs = System.currentTimeMillis(), progress = loadProgress)
                        }
                    }
                },
                onLoadFinished = {
                    if (loadPhase != Live2DLoadPhase.Error && loadPhase != Live2DLoadPhase.SslWarning) {
                        loadProgress = COMPLETE_PROGRESS
                        isContentReady = true
                        viewModel.updateLoadState {
                            it.copy(pageFinishedTimeMs = System.currentTimeMillis(), progress = COMPLETE_PROGRESS)
                        }
                    }
                },
                onLoadFailed = { message ->
                    loadProgress = 0
                    loadPhase = Live2DLoadPhase.Error
                    errorMessage = message
                    viewModel.updateLoadState {
                        it.copy(
                            phase = Live2DLoadPhase.Error,
                            errorMessage = message,
                            errorTimeMs = System.currentTimeMillis(),
                            progress = 0
                        )
                    }
                },
                onSslError = { message, errorCode ->
                    loadProgress = 0
                    sslErrorMessage = message
                    loadPhase = Live2DLoadPhase.SslWarning
                    viewModel.updateLoadState {
                        it.copy(
                            phase = Live2DLoadPhase.SslWarning,
                            sslErrorMessage = message,
                            sslErrorCode = errorCode,
                            errorTimeMs = System.currentTimeMillis(),
                            progress = 0
                        )
                    }
                },
                onRendererGone = { message ->
                    recreateOnNextRetry = true
                    loadProgress = 0
                    loadPhase = Live2DLoadPhase.Error
                    errorMessage = message
                    viewModel.updateLoadState {
                        it.copy(
                            phase = Live2DLoadPhase.Error,
                            errorMessage = message,
                            errorTimeMs = System.currentTimeMillis(),
                            progress = 0
                        )
                    }
                },
                onConsoleError = { message ->
                    viewModel.addConsoleError(message)
                },
                onWebGLStatus = { status ->
                    viewModel.updateLoadState { it.copy(webGLStatus = status) }
                },
                onRequestLog = { phase, msg, details ->
                    viewModel.addRequestLog(phase, msg, details)
                }
            )

            BindLive2DWebViewLifecycle(webView)

            LaunchedEffect(webView, reloadToken) {
                beginLoad()
                webView.stopLoading()
                // 重置 403 自动重试标记，每次用户发起的加载都享有一次自动重试机会
                webView.setTag(R.id.live2d_forbidden_retry_tag, false)

                // 【设备分辨率适配】：获取当前设备物理分辨率与 DPI，用于尺寸验证与日志诊断
                val dm = context.resources.displayMetrics
                val screenW = dm.widthPixels
                val screenH = dm.heightPixels
                val density = dm.density
                val dpi = dm.densityDpi
                viewModel.addRequestLog(
                    "device-resolution",
                    "设备分辨率: ${screenW}x${screenH}, density=${density}, dpi=${dpi}",
                    mapOf("width" to screenW.toString(), "height" to screenH.toString(), "dpi" to dpi.toString())
                )

                // 【终极核心修复】：绝对不在 WebView 尺寸为 0x0 时加载网页！
                // 等待 Android 视图树完成 Layout，确认物理宽高大于 0 后再触发 loadUrl，
                // 否则 PixiJS/Live2D 会以 0x0 创建 WebGL 画布且无法恢复 → 永久黑/白屏。
                val ua = (webView.settings.userAgentString ?: "").take(120)
                if (webView.width > 0 && webView.height > 0) {
                    viewModel.addRequestLog("load-start", "尺寸已就绪 (${webView.width}x${webView.height})，发起加载", mapOf("ua" to ua, "screen" to "${screenW}x${screenH}"))
                    webView.loadUrl(LIVE2D_URL, MAIN_REQUEST_HEADERS)
                    viewModel.addRequestLog("load-url", "WebView.loadUrl 已发起", mapOf("url" to LIVE2D_URL))
                } else {
                    viewModel.addRequestLog("load-wait", "等待视图 Layout 计算尺寸... (设备: ${screenW}x${screenH})", emptyMap())
                    val layoutListener = object : View.OnLayoutChangeListener {
                        override fun onLayoutChange(
                            v: View?, left: Int, top: Int, right: Int, bottom: Int,
                            oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                        ) {
                            val w = right - left
                            val h = bottom - top
                            if (w > 0 && h > 0) {
                                webView.removeOnLayoutChangeListener(this)
                                viewModel.addRequestLog("load-start", "布局成功 (${w}x${h})，正式发起加载", mapOf("ua" to ua, "screen" to "${screenW}x${screenH}"))
                                Log.i(TAG, "WebView 尺寸分配成功: ${w}x${h} (设备: ${screenW}x${screenH} dpi=${dpi})，正式加载页面")
                                webView.loadUrl(LIVE2D_URL, MAIN_REQUEST_HEADERS)
                                viewModel.addRequestLog("load-url", "WebView.loadUrl 已发起", mapOf("url" to LIVE2D_URL))
                            }
                        }
                    }
                    webView.addOnLayoutChangeListener(layoutListener)
                }
            }

            // 原生 WebView 始终保持全屏 VISIBLE 与 alpha=1，绝不修改 visibility/alpha，
            // 以确保 GPU 加速管道无阻塞、Chromium 不暂停 WebGL 光栅化。
            // 加载前遮罩由顶层 AnimatedContent 的 LoadingOverlay 负责。
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize()
            )
        }

        // ── 顶层遮罩层（Loading/Error/SslWarning 盖在 WebView 上方，Ready 时淡出露出底层 WebView） ──
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
                        },
                        onCopyError = {
                            val report = viewModel.generateErrorReport(context)
                            copyToClipboard(context, "Live2D错误报告", report)
                            Toast.makeText(context, "错误信息已复制", Toast.LENGTH_SHORT).show()
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
                        onReject = onBack,
                        onCopyError = {
                            val report = viewModel.generateErrorReport(context)
                            copyToClipboard(context, "Live2D_SSL错误报告", report)
                            Toast.makeText(context, "错误信息已复制", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
                Live2DLoadPhase.Ready -> {
                    Spacer(Modifier.fillMaxSize())
                }
            }
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

// ────────────────────────── 剪贴板工具 ──────────────────────────

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
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
    onSslError: (String, Int) -> Unit,
    onRendererGone: (String) -> Unit,
    onConsoleError: (String) -> Unit,
    onWebGLStatus: (String) -> Unit,
    onRequestLog: (String, String, Map<String, String>) -> Unit
): WebView {
    val context = LocalContext.current
    val onLoadStartedState = rememberUpdatedState(onLoadStarted)
    val onLoadProgressState = rememberUpdatedState(onLoadProgress)
    val onPageVisibleState = rememberUpdatedState(onPageVisible)
    val onLoadFinishedState = rememberUpdatedState(onLoadFinished)
    val onLoadFailedState = rememberUpdatedState(onLoadFailed)
    val onSslErrorState = rememberUpdatedState(onSslError)
    val onRendererGoneState = rememberUpdatedState(onRendererGone)
    val onConsoleErrorState = rememberUpdatedState(onConsoleError)
    val onWebGLStatusState = rememberUpdatedState(onWebGLStatus)
    val sslTrustedState = rememberUpdatedState(sslTrusted)
    val onRequestLogState = rememberUpdatedState(onRequestLog)

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
                onRendererGoneState = onRendererGoneState,
                onConsoleErrorState = onConsoleErrorState,
                onWebGLStatusState = onWebGLStatusState,
                onRequestLogState = onRequestLogState
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
                    // 持久化 Cookie（含 Cloudflare cf_clearance），下次加载可跳过 challenge
                    CookieManager.getInstance().flush()
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
    onSslErrorState: androidx.compose.runtime.State<(String, Int) -> Unit>,
    onRendererGoneState: androidx.compose.runtime.State<(String) -> Unit>,
    onConsoleErrorState: androidx.compose.runtime.State<(String) -> Unit>,
    onWebGLStatusState: androidx.compose.runtime.State<(String) -> Unit>,
    onRequestLogState: androidx.compose.runtime.State<(String, String, Map<String, String>) -> Unit>
) {
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )
    setLayerType(View.LAYER_TYPE_HARDWARE, null)
    // 【核心修复】：必须设为 VISIBLE！
    // 在 Compose AndroidView 机制下，初始设为 INVISIBLE 会导致布局引擎第一次 layout 时
    // 分配 0x0 尺寸，PixiJS/Live2D 据此创建 0x0 WebGL 画布且无法恢复 → 永久白屏。
    // 视觉上的加载前隐藏改由 Compose Modifier.alpha() 控制。
    visibility = View.VISIBLE
    // 【核心修复】：深色背景，避免 0x0 期间露出刺眼纯白底色
    setBackgroundColor(android.graphics.Color.parseColor("#1E1E1E"))
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

    // 403/503 自动重试期间，Cloudflare 拦截页(403 响应体)仍会被 WebView 加载并触发
    // onPageCommitVisible/onPageFinished。此标记用于在重试期间抑制这些回调，
    // 避免拦截页被误判为"加载完成"提前进入 Ready，再被重试的 beginLoad 打回 Loading，
    // 导致状态机与卡住检测 watchdog 不同步（曾表现为"stuck at 100%"）。
    var pendingRetry = false

    webViewClient = object : WebViewClient() {

        /**
         * 【终极杀手锏】：主动接管所有网络请求（主帧 + 子资源），绝对不给内核回退接管的机会。
         *
         * 基于对 l2d.su 真实流量抓包分析，确认了 4 类报错的最终根因：
         *
         * ① 陷阱 1（原生内核路径）：Android Chromium 自动注入 X-Requested-With: com.azurlane.blyy → WAF 403
         * ② 陷阱 2（HttpURLConnection 路径）：Java TLS 指纹被 WAF 判定异常 → live2dcubismcore.min.js 仍 403
         * ③ 泄漏 1：403 子资源 → return null → 内核接管 → 默认 Accept-Encoding: gzip, deflate, br, zstd
         *    → static.l2d.su 返回 zstd 压缩二进制 (魔术字节 0x28 0xB5 0x2F 0xFD = ASCII "(/X ")
         *    → JS JSON.parse 报 "Unexpected token '(', \"(\/X...\" is not valid JSON"
         * ④ 泄漏 2：catch 异常 → return null → 同上
         *
         * 真实流量证据（crawl4ai 抓包）：
         *   static.l2d.su/azurlane/live2d/Z23/Z23.model3.json
         *     [Accept-Encoding: gzip, deflate, br, zstd] → enc=zstd  magic=0x28b52ffd (即 ASCII "(/X ")
         *     [Accept-Encoding: gzip]                     → enc=none  原始 JSON 文本（以 "{" 开头）✓
         *     [Accept-Encoding: identity]                 → enc=none  原始 JSON 文本 ✓
         *
         * 五重防线：
         * 1. 强制注入合法 Referer + 纯净 UA，破解防盗链 403
         * 2. CDN 自动熔断备份：live2dcubismcore.min.js 403 时切换 jsDelivr 公共 CDN
         * 3. 魔术字节探测：GZIP(0x1F 0x8B) → 解压；ZSTD(0x28 0xB5 0x2F 0xFD) → 用 identity 重试
         * 4. 子资源 403 不再返回 null，构造合成 WebResourceResponse 避免内核接管
         * 5. 异常 catch 不再返回 null，构造合成 503 响应避免内核接管
         */
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            val urlStr = request?.url?.toString() ?: return null
            val scheme = request.url?.scheme ?: return null
            if (scheme != "http" && scheme != "https") return null

            // 【Fix L 2026-07-06：拦截无用分析/追踪请求】
            // k.clarity.ms（Microsoft Clarity 分析）请求在日志中频繁出现：
            //   ① 连接超时 15s（k.clarity.ms/172.175.38.6 不可达）→ 浪费网络资源与用户时间
            //   ② 即便连上也会被 CORS 策略拦截（无 Access-Control-Allow-Origin 头）→ 503 合成响应
            //   ③ 这些请求与 Live2D 功能完全无关，纯粹是 l2d.su 站点注入的分析脚本
            // 直接返回空 204 响应，让 JS 认为请求成功并静默结束，避免 15s 超时 + CORS 噪音
            val requestHost = request.url?.host ?: ""
            if (requestHost.endsWith("clarity.ms") ||
                requestHost.endsWith("google-analytics.com") ||
                requestHost.endsWith("googletagmanager.com") ||
                requestHost.endsWith("doubleclick.net") ||
                requestHost.endsWith("facebook.net") ||
                requestHost.endsWith("hotjar.com")) {
                return WebResourceResponse(
                    "text/plain",
                    "utf-8",
                    204,
                    "No Content (Analytics Blocked)",
                    mapOf(
                        "Content-Type" to "text/plain; charset=utf-8",
                        "Access-Control-Allow-Origin" to "*"
                    ),
                    java.io.ByteArrayInputStream(ByteArray(0))
                )
            }

            return try {
                var targetUrlStr = urlStr
                var url = java.net.URL(targetUrlStr)
                var connection = (url.openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = request.method
                    // 【Fix P 2026-07-06】连接超时从 15s 降到 8s。
                    // 日志证据：kaxin.webp/qiye.webp/aersasi_3.cdi3.json 等资源连接超时 15s
                    // → 用户等待时间长 + 触发大写 URL fallback → 404。
                    // 8s 足够建立 TCP 连接（正常 <1s），不可达时快速失败。
                    connectTimeout = 8000
                    // 放宽到 60s：通过 Clash 代理下载大 .moc3 文件（最大 4.5MB 解压后）需要更多时间
                    readTimeout = 60000
                    instanceFollowRedirects = true

                    // 1. 复制原请求头，严厉查杀 X-Requested-With、Accept-Encoding、Referer 与条件请求头
                    // 【Fix C 2026-07-03】剥离条件请求头 (If-Modified-Since/If-None-Match/If-Range/If-Unmodified-Since)
                    // 防止服务器返回 304 Not Modified。304 在 300..399 范围内 → WebResourceResponse 构造异常
                    // → catch 合成 503 → MiSans 字体/kofi Widget/live2dcubismcore 加载失败。
                    // 更恶毒的是 304 会触发 Cubism Core CDN 熔断 → 加载 jsDelivr NPM 的 4.2.2 旧版本
                    // (l2d.su 使用 5.1.0)，版本不匹配可能导致 moc3 解析失败。
                    // shouldInterceptRequest 绕过了 Chromium 缓存，304 时无内容可返回，必须从源头杜绝。
                    request.requestHeaders?.forEach { (key, value) ->
                        val keyLower = key.lowercase()
                        if (keyLower != "x-requested-with" &&
                            keyLower != "accept-encoding" &&
                            keyLower != "referer" &&
                            !keyLower.startsWith("if-")) {
                            setRequestProperty(key, value)
                        }
                    }

                    // 2. 【防盗链防 403 核心】：死锁标准 Referer 和 Chrome User-Agent
                    //    Referer 对 l2d.su 与 static.l2d.su 同样有效（同父域）
                    setRequestProperty("Referer", LIVE2D_URL)
                    setRequestProperty("User-Agent", buildCleanBrowserUserAgent())

                    // 3. 【防 zstd/br 乱码核心】：强行声明仅接收 gzip 压缩！从源头拒绝 zstd (/X 乱码)
                    //    实测：static.l2d.su 收到 gzip 时返回无压缩纯文本 JSON ✓
                    setRequestProperty("Accept-Encoding", "gzip")

                    // 【防连接池损坏 2026-07-03】static.l2d.su 并发请求时 keep-alive 连接复用
                    // 会导致前一个响应的残留字节污染当前 ZSTD 流 → "Data corruption detected" (code 20)
                    // 典型症状：大文件(1.8MB)成功，小文件(243KB)失败 —— 非文件大小问题而是连接复用竞态
                    // 强制 Connection: close 让每个请求独立连接，彻底杜绝 keep-alive 字节污染
                    if (url.host.contains("static.l2d.su")) {
                        setRequestProperty("Connection", "close")
                    }

                    if (getRequestProperty("Accept-Language") == null) {
                        setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    }

                    // 4. 携带 CookieManager 中的会话信息（特别是 cf_clearance）
                    val cookies = CookieManager.getInstance().getCookie(urlStr)
                    if (!cookies.isNullOrEmpty()) {
                        setRequestProperty("Cookie", cookies)
                    }
                }

                var responseCode = connection.responseCode

                // 【Fix A 2026-07-03：手动跟随 3xx 重定向】
                // HttpURLConnection.instanceFollowRedirects=true 仅能跟随同协议同主机重定向，
                // 无法跟随跨协议(HTTP↔HTTPS)/跨主机重定向。WebResourceResponse 构造函数
                // 严禁 300..399 状态码 → 抛 IllegalArgumentException → catch 后合成 503
                // → live2dcubismcore.min.js / MiSans 字体等子资源加载失败。
                // 必须手动读取 Location 头并用完整安全头重新发起请求。
                //
                // 【Fix D 2026-07-03：精确化重定向码】
                // 仅跟随真正的重定向状态码：301/302/303/307/308。
                // 排除：304 (Not Modified, 非重定向，无 Location 头)、305 (Use Proxy, 已废弃)、306 (Unused)。
                // 304 已由 Fix C (剥离条件请求头) 从源头杜绝，此处作为安全网防止漏网。
                val redirectCodes = setOf(301, 302, 303, 307, 308)
                var redirectCount = 0
                while (responseCode in redirectCodes && redirectCount < 5) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrEmpty()) {
                        Log.w(TAG, "【重定向失败】Location 头为空: $targetUrlStr (code=$responseCode)")
                        break
                    }
                    // 处理相对路径重定向（如 Location: /lib/...）
                    val newUrlStr = if (location.startsWith("http")) {
                        location
                    } else {
                        java.net.URL(url, location).toString()
                    }
                    Log.d(TAG, "【跟随重定向 #$redirectCount】: $targetUrlStr → $newUrlStr (code=$responseCode)")
                    onRequestLogState.value(
                        "redirect-follow",
                        "跟随 3xx 重定向 #$redirectCount",
                        mapOf(
                            "fromUrl" to targetUrlStr,
                            "toUrl" to newUrlStr,
                            "statusCode" to responseCode.toString()
                        )
                    )
                    targetUrlStr = newUrlStr
                    url = java.net.URL(targetUrlStr)
                    connection = (url.openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = request.method
                        connectTimeout = 8000 // 【Fix P】与初始请求一致
                        readTimeout = 60000
                        instanceFollowRedirects = false // 已手动处理，禁用内核自动行为

                        // 复制原请求头（与首次连接相同的过滤策略，含条件头剥离）
                        request.requestHeaders?.forEach { (key, value) ->
                            val keyLower = key.lowercase()
                            if (keyLower != "x-requested-with" &&
                                keyLower != "accept-encoding" &&
                                keyLower != "referer" &&
                                !keyLower.startsWith("if-")) {
                                setRequestProperty(key, value)
                            }
                        }
                        // 同源 Referer + 标准 Chrome UA + 强制 gzip（同首次请求安全策略）
                        setRequestProperty("Referer", LIVE2D_URL)
                        setRequestProperty("User-Agent", buildCleanBrowserUserAgent())
                        setRequestProperty("Accept-Encoding", "gzip")
                        if (url.host.contains("static.l2d.su")) {
                            setRequestProperty("Connection", "close")
                        }
                        if (getRequestProperty("Accept-Language") == null) {
                            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                        }
                        val redirectCookies = CookieManager.getInstance().getCookie(targetUrlStr)
                        if (!redirectCookies.isNullOrEmpty()) {
                            setRequestProperty("Cookie", redirectCookies)
                        }
                    }
                    responseCode = connection.responseCode
                    redirectCount++
                }

                // 【Fix E 2026-07-03：304 安全网】
                // 虽然 Fix C 已从源头剥离条件请求头，仍需兜底防止服务器配置异常或缓存层注入条件头。
                // 304 Not Modified 不是错误也不是重定向，表示"使用缓存版本"。
                // shouldInterceptRequest 绕过了 Chromium 缓存，304 时无内容可返回。
                // 返回 null 让 Chromium 内核用自己的网络栈+缓存重新请求，安全获取内容。
                // ⚠️ 仅对 l2d.su 主站安全（非 ZSTD 压缩）；static.l2d.su 不应出现 304（条件头已剥离）。
                // 旧代码将 304 误判为重定向 → Location 为空 → break → 触发 Cubism Core CDN 熔断
                // → 加载 jsDelivr NPM 4.2.2 旧版本（l2d.su 使用 5.1.0），版本不匹配风险。
                if (responseCode == 304) {
                    Log.w(TAG, "【304 安全网】交还 Chromium 内核用缓存处理: $targetUrlStr")
                    onRequestLogState.value(
                        "not-modified",
                        "304 Not Modified → 交还内核缓存",
                        mapOf("url" to targetUrlStr, "statusCode" to "304")
                    )
                    connection.disconnect()
                    return null
                }

                // 【防线 2：Cubism 核心库 403/异常重定向 自动熔断切换全球 CDN】
                // 主站 WAF 对 live2dcubismcore.min.js 校验 TLS 指纹（JA3/JA4），
                // Java HttpURLConnection 被判定异常 → 403 → 切换 jsDelivr
                // 注意：真正的重定向(301/302/303/307/308)已由上方手动循环处理；
                // 此处仅兜底 403 与 Location 头为空的异常重定向（循环 break 后仍为重定向码）
                if (targetUrlStr.contains("live2dcubismcore") &&
                    (responseCode == 403 || responseCode in redirectCodes)) {
                    Log.w(TAG, "主站 Cubism Core 遭遇 $responseCode，触发公共 CDN 熔断备份: $targetUrlStr")
                    onRequestLogState.value(
                        "cdn-fallback",
                        "Cubism Core $responseCode → jsDelivr CDN 熔断",
                        mapOf("originalUrl" to targetUrlStr, "fallbackUrl" to "jsdelivr-npm")
                    )
                    targetUrlStr = "https://cdn.jsdelivr.net/npm/live2dcubismcore@1.0.2/live2dcubismcore.min.js"
                    url = java.net.URL(targetUrlStr)
                    connection = (url.openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 15000
                        readTimeout = 15000
                        setRequestProperty("User-Agent", buildCleanBrowserUserAgent())
                        setRequestProperty("Accept-Encoding", "gzip")
                        setRequestProperty("Referer", "https://cdn.jsdelivr.net/")
                    }
                    responseCode = connection.responseCode
                }

                // 【防线 4：子资源 403 不再返回 null，避免内核接管！】
                // 仅主帧 403 返回 null 触发 onReceivedHttpError → 自动重试流程；
                // 子资源 403 必须自行构造 WebResourceResponse 返回，禁止内核接管
                // （否则内核发出 zstd → static.l2d.su 返回 zstd 二进制 → JSON.parse 崩溃）
                if (responseCode == 403 && request.isForMainFrame) {
                    Log.e(TAG, "【主帧 403 交回原生触发重试】: $urlStr")
                    onRequestLogState.value(
                        "intercept-403-main",
                        "主帧 403，交回原生处理以触发重试",
                        mapOf("url" to urlStr, "statusCode" to "403", "mainFrame" to "true")
                    )
                    return null
                }

                // 同步服务器 Cookie（如 cf_clearance）
                connection.headerFields?.get("Set-Cookie")?.forEach { cookieStr ->
                    CookieManager.getInstance().setCookie(targetUrlStr, cookieStr)
                }

                // 解析 Content-Type 与字符编码
                val contentTypeRaw = connection.contentType ?: "application/octet-stream"
                var mimeType = contentTypeRaw
                var encoding = "utf-8"
                if (contentTypeRaw.contains(";")) {
                    val parts = contentTypeRaw.split(";")
                    mimeType = parts[0].trim()
                    parts.drop(1).forEach { part ->
                        if (part.trim().startsWith("charset=", ignoreCase = true)) {
                            encoding = part.trim().substringAfter("charset=").trim()
                        }
                    }
                }

                // 收集响应头：严厉剔除 Content-Encoding、Content-Length 与 Transfer-Encoding！
                // Chromium 认为返回的流是已解压的原始字节，若保留这些头会导致二次解压或长度不匹配
                val responseHeaders = mutableMapOf<String, String>()
                connection.headerFields?.forEach { (key, values) ->
                    if (key != null && values.isNotEmpty()) {
                        if (!key.equals("Content-Encoding", ignoreCase = true) &&
                            !key.equals("Content-Length", ignoreCase = true) &&
                            !key.equals("Transfer-Encoding", ignoreCase = true)) {
                            responseHeaders[key] = values.joinToString("; ")
                        }
                    }
                }

                // 读取原始字节流（兼容 400 及以上错误状态流的读取）
                val rawStream = if (responseCode >= 400) {
                    connection.errorStream ?: java.io.ByteArrayInputStream(ByteArray(0))
                } else {
                    connection.inputStream ?: java.io.ByteArrayInputStream(ByteArray(0))
                }

                // 将流完整读入内存以探测魔术字节，确保 100% 准确解压
                val rawBytes = rawStream.readBytes()
                var finalBytes = rawBytes

                // 【防线 3a：GZIP 魔术字节探测】：0x1F 0x8B → GZIPInputStream 解压
                if (rawBytes.size >= 2 &&
                    rawBytes[0] == 0x1F.toByte() &&
                    rawBytes[1] == 0x8B.toByte()) {
                    finalBytes = java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(rawBytes)).readBytes()
                }

                // 【防线 3b：ZSTD 魔术字节探测 + 本地解压】
                // ⚠️ 真实抓包证据颠覆旧认知：static.l2d.su 服务器配置严重错误
                // 对 .model3.json / .motion3.json / .moc3 等 Live2D 资源**预先压缩成 ZSTD 格式存储**，
                // 并完全无视客户端 Accept-Encoding 头——即使发送 gzip/identity/br，
                // 服务器照样返回 ZSTD 流（魔术字节 0x28 0xB5 0x2F 0xFD = ASCII "(/X "）。
                //
                // 服务器还撒谎：Content-Encoding 头仅在 Accept-Encoding 含 zstd 时才诚实标 zstd，
                // 其余情况 CE=null（声称无压缩），让客户端误以为是明文 → 直接交给 JS → JSON.parse 崩溃
                // "Unexpected token '(', \"(\\/X...\" is not valid JSON"
                //
                // 实测矩阵（iter_raw 原始字节，5 种 Accept-Encoding × 3 端点 = 15 组）：
                //   https://static.l2d.su/.../lafei_4.model3.json
                //     [AE: gzip]      → CE=null   body=28 b5 2f fd ... (ZSTD 流)
                //     [AE: identity]  → CE=null   body=28 b5 2f fd ... (ZSTD 流)
                //     [AE: br]        → CE=null   body=28 b5 2f fd ... (ZSTD 流)
                //   https://static.l2d.su/.../idle.motion3.json
                //     [AE: gzip]      → CE=null   body=28 b5 2f fd ... (ZSTD 流)
                //   https://static.l2d.su/.../lafei_4.moc3
                //     [AE: gzip]      → CE=null   body=28 b5 2f fd ... (ZSTD 流)
                //
                // 唯一解决方案：用 zstd-jni 库在客户端本地解压 ZSTD 流
                //
                // 【2026-07-03 修正：双解压路径 + 兜底交还内核】
                // 实测某些 .moc3 文件 ZSTD 解压报 "Data corruption detected"（错误码 20，
                // 非 22 checksum_wrong），是 bitstream 结构性损坏。最可能根因：
                // ① Clash 代理 127.0.0.1:7897 缓存损坏响应
                // ② HttpURLConnection 对 chunked+zstd 组合处理异常导致字节缺失
                // 修复策略：
                //   主路径：Zstd.decompress(dst, src) 一次性解压（绕过 ZstdInputStream 64KB 缓冲）
                //   备路径：ZstdInputStream 流式解压（fallback）
                //   兜底：两次解压均失败时返回 null，交还 Chromium 内核处理
                //        （Chromium 123+ 原生支持 Content-Encoding: zstd 自动解压）
                if (finalBytes.size >= 4 &&
                    finalBytes[0] == 0x28.toByte() &&
                    finalBytes[1] == 0xB5.toByte() &&
                    finalBytes[2] == 0x2F.toByte() &&
                    finalBytes[3] == 0xFD.toByte()) {
                    val compressedSize = finalBytes.size
                    // 【P0 诊断日志】记录 HTTP 响应完整性指标，定位传输层损坏
                    val contentLengthHeader = connection.getHeaderField("Content-Length")
                    val contentEncodingHeader = connection.getHeaderField("Content-Encoding")
                    val transferEncodingHeader = connection.getHeaderField("Transfer-Encoding")
                    val headHex = finalBytes.take(16).joinToString(" ") { "%02X".format(it) }
                    val tailHex = finalBytes.takeLast(16).joinToString(" ") { "%02X".format(it) }
                    Log.w(TAG, "【检测到 ZSTD 压缩！本地解压】: $targetUrlStr (压缩后 size=$compressedSize, " +
                        "Content-Length=$contentLengthHeader, matchCL=${contentLengthHeader?.toLongOrNull() == compressedSize.toLong()}, " +
                        "Content-Encoding=$contentEncodingHeader, Transfer-Encoding=$transferEncodingHeader)")
                    Log.d(TAG, "  ZSTD诊断 head=$headHex")
                    Log.d(TAG, "  ZSTD诊断 tail=$tailHex")
                    onRequestLogState.value(
                        "zstd-decompress",
                        "ZSTD 流 → 本地解压",
                        mapOf(
                            "url" to targetUrlStr,
                            "magic" to "28b52ffd",
                            "compressedSize" to compressedSize.toString(),
                            "contentLength" to (contentLengthHeader ?: "null"),
                            "contentEncoding" to (contentEncodingHeader ?: "null"),
                            "transferEncoding" to (transferEncodingHeader ?: "null")
                        )
                    )

                    var decompressedBytes: ByteArray? = null
                    var lastErrorMsg: String? = null

                    // 【主路径：Zstd.decompress(dst, src) 一次性解压】
                    // 直接调用 native ZSTD_decompress，绕过 ZstdInputStream 64KB 缓冲流式逻辑。
                    // 原生支持多帧拼接（RFC 8478 §3.1），错误信息更直接（返回 native 错误码）
                    //
                    // 【2026-07-03 改进：渐进式缓冲区】
                    // 当 ZSTD 帧头未设置 Frame_Content_Size（declaredSize=0）时，
                    // 旧代码直接跳过主路径走流式 fallback → ZstdInputStream 可能因 64KB 缓冲区
                    // 与 ZSTD 块边界不对齐而报 "Data corruption detected"。
                    // 新策略：declaredSize 未知时尝试 6x → 12x → 20x 压缩大小的渐进式缓冲区
                    try {
                        val declaredSize = com.github.luben.zstd.Zstd.decompressedSize(finalBytes)
                        val isDeclaredSizeValid = !com.github.luben.zstd.Zstd.isError(declaredSize) &&
                            declaredSize > 0 && declaredSize <= MAX_DECOMPRESSED_SIZE
                        Log.d(TAG, "  ZSTD诊断 declaredSize=$declaredSize, valid=$isDeclaredSizeValid, thread=${Thread.currentThread().id}")

                        // 计算候选缓冲区大小列表
                        // ZSTD 二进制数据最大压缩比约 3.5x，6x 留余量，12x/20x 兜底极端情况
                        val bufferSizes = if (isDeclaredSizeValid) {
                            listOf(declaredSize.toInt())
                        } else {
                            listOf(compressedSize * 6, compressedSize * 12, compressedSize * 20)
                                .filter { it > 0 && it.toLong() <= MAX_DECOMPRESSED_SIZE }
                        }

                        for (estimatedSize in bufferSizes) {
                            try {
                                val dst = ByteArray(estimatedSize)
                                val result = com.github.luben.zstd.Zstd.decompress(dst, finalBytes)
                                if (!com.github.luben.zstd.Zstd.isError(result) && result > 0) {
                                    // trim 到实际解压大小，避免尾部零字节干扰 Cubism SDK 的 moc3 解析器
                                    decompressedBytes = if (result < dst.size) dst.copyOf(result.toInt()) else dst
                                    Log.i(TAG, "【ZSTD 一次性解压成功】: $targetUrlStr (压缩=$compressedSize → 解压=${result}, buf=$estimatedSize)")
                                    break
                                } else if (com.github.luben.zstd.Zstd.isError(result)) {
                                    lastErrorMsg = "Zstd.decompress error: ${com.github.luben.zstd.Zstd.getErrorName(result)} (code=$result, buf=$estimatedSize)"
                                    Log.d(TAG, "  ZSTD一次性解压 buf=$estimatedSize 失败: $lastErrorMsg")
                                }
                            } catch (e: OutOfMemoryError) {
                                lastErrorMsg = "OOM buf=$estimatedSize"
                                break // 缓冲区太大，停止尝试
                            } catch (e: com.github.luben.zstd.ZstdException) {
                                // 【Fix I 2026-07-06】"Destination buffer is too small" 等异常须在循环内捕获，
                                // 继续尝试更大的缓冲区。旧代码异常逃逸到外层 catch → 12x/20x 缓冲区从未尝试
                                // → 不必要的流式 fallback（虽然流式能成功，但一次性解压性能更好）
                                lastErrorMsg = "ZstdException buf=$estimatedSize: ${e.message}"
                                // 【Fix O 2026-07-06】"Data corruption detected" 是字节损坏，增大缓冲区无用。
                                // 立即 break 跳过 12x/20x 无效尝试，避免分配 79MB/133MB 巨型缓冲区。
                                // 日志证据：beilaosenlin_2.moc3 (6.6MB) 6x=39MB → 12x=79MB → 20x=133MB 全部失败
                                if (e.message?.contains("Data corruption detected") == true) {
                                    Log.d(TAG, "  ZSTD一次性解压 buf=$estimatedSize 字节损坏，跳过更大缓冲区直接重试: ${e.message}")
                                    break
                                }
                                Log.d(TAG, "  ZSTD一次性解压 buf=$estimatedSize 异常，尝试下一个缓冲区: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        lastErrorMsg = "一次性解压异常: ${e.message}"
                        Log.w(TAG, "  ZSTD一次性解压异常: $targetUrlStr - ${e.message}", e)
                    }

                    // 【备路径：ZstdInputStream 流式解压】
                    // 当主路径失败（如 declaredSize 未知且所有渐进缓冲区均失败）时尝试
                    // 【Fix M 2026-07-06】.moc3 文件因 HttpURLConnection chunked 编码 bug 导致字节损坏时，
                    // 一次性解压和流式解压都会报 "Data corruption detected"（同一份损坏字节）。
                    // 跳过流式解压直接进入 OkHttp 重试，节省 ~100ms 无效计算。
                    // 仅对 static.l2d.su 的 .moc3 文件且错误为 "Data corruption detected" 时跳过。
                    val isStaticL2dMoc3 = url.host.contains("static.l2d.su") &&
                        targetUrlStr.lowercase().endsWith(".moc3")
                    val isDataCorruption = lastErrorMsg?.contains("Data corruption detected") == true
                    val skipStreaming = isStaticL2dMoc3 && isDataCorruption
                    if (decompressedBytes == null && !skipStreaming) {
                        try {
                            val zstdIs = com.github.luben.zstd.ZstdInputStream(
                                java.io.ByteArrayInputStream(finalBytes)
                            )
                            decompressedBytes = zstdIs.readBytes()
                            zstdIs.close()
                            Log.i(TAG, "【ZSTD 流式解压成功】: $targetUrlStr (压缩=$compressedSize → 解压=${decompressedBytes!!.size})")
                        } catch (decompressEx: Exception) {
                            lastErrorMsg = "流式解压失败: ${decompressEx.message}"
                            Log.e(TAG, "【ZSTD 流式解压失败】: $targetUrlStr - ${decompressEx.message}", decompressEx)
                        }
                    } else if (skipStreaming) {
                        Log.d(TAG, "  【Fix M】跳过流式解压（.moc3 字节损坏，直接 OkHttp 重试）: $targetUrlStr")
                    }

                    if (decompressedBytes != null) {
                        finalBytes = decompressedBytes
                        onRequestLogState.value(
                            "zstd-decompress-success",
                            "ZSTD 解压成功 (压缩=$compressedSize → 解压=${finalBytes.size})",
                            mapOf(
                                "url" to targetUrlStr,
                                "compressedSize" to compressedSize.toString(),
                                "decompressedSize" to finalBytes.size.toString()
                            )
                        )
                    } else {
                        // 【Fix F+G 2026-07-03：ZSTD 解压失败重试 + 原始 ZSTD 流返回】
                        // 旧代码 return null → Chromium 内核重新请求 → 服务器因 X-Requested-With/Referer
                        // 判定异常返回 404（用户报错 HTTP 404: jinshi_2.moc3 / jinluhao_2）。
                        // 对 JSON 资源：Chromium 拿到 ZSTD 二进制直传 JS → JSON.parse 崩溃
                        // "Unexpected token '', \"�dSg{�I\"... is not valid JSON"。
                        //
                        // 新策略两层兜底：
                        //   ① Fix G：重试整个 HTTP 请求（新连接），最多 2 次，解决传输层瞬态损坏
                        //      （chunked 编码字节缺失/Clash 代理缓存损坏/keep-alive 残留）
                        //   ② Fix F：重试仍失败时，返回原始 ZSTD 字节流 + Content-Encoding: zstd 头
                        //      Chromium 123+ (SystemWebViewGoogle6432+) 原生支持 zstd 解压，
                        //      让内核 native 解压作为最终保险。严禁 return null（必致 404）
                        Log.e(TAG, "【ZSTD 解压首次失败，启动 HTTP 重试】: $targetUrlStr - $lastErrorMsg")
                        onRequestLogState.value(
                            "zstd-decompress-retry",
                            "ZSTD 解压失败，启动重试: ${lastErrorMsg ?: "unknown"}",
                            mapOf(
                                "url" to targetUrlStr,
                                "compressedSize" to compressedSize.toString(),
                                "error" to (lastErrorMsg ?: "")
                            )
                        )
                        connection.disconnect()

                        var retryResolved = false
                        for (retryIdx in 1..2) {
                            try {
                                // 【Fix M 2026-07-06】首次重试时，若错误为 "Data corruption detected"
                                // （HttpURLConnection chunked bug，非瞬态网络抖动），跳过 300ms 延迟
                                if (retryIdx == 1 && isDataCorruption) {
                                    Log.d(TAG, "  ZSTD 重试 #$retryIdx 重新请求 (OkHttp, 无延迟): $targetUrlStr")
                                } else {
                                    Thread.sleep(300L) // 短暂等待，规避瞬态网络抖动
                                    Log.d(TAG, "  ZSTD 重试 #$retryIdx 重新请求 (OkHttp): $targetUrlStr")
                                }
                                // 【Fix J 2026-07-06】使用 OkHttp 替代 HttpURLConnection
                                // HttpURLConnection 对某些 chunked transfer-encoding 响应处理异常，
                                // 导致 ZSTD "Data corruption detected"。OkHttp chunked 解码更健壮。
                                val retryReq = OkRequest.Builder()
                                    .url(targetUrlStr)
                                    .get()
                                    .header("Referer", LIVE2D_URL)
                                    .header("User-Agent", buildCleanBrowserUserAgent())
                                    .header("Accept-Encoding", "gzip")
                                    .header("Connection", "close")
                                    .apply {
                                        val rCookies = CookieManager.getInstance().getCookie(targetUrlStr)
                                        if (!rCookies.isNullOrEmpty()) {
                                            header("Cookie", rCookies)
                                        }
                                    }
                                    .build()
                                val retryResp = zstdRetryClient.newCall(retryReq).execute()
                                val retryCode = retryResp.code
                                if (retryCode != 200) {
                                    retryResp.close()
                                    Log.w(TAG, "  ZSTD 重试 #$retryIdx HTTP $retryCode，跳过")
                                    continue
                                }
                                val retryRaw = retryResp.body?.bytes() ?: ByteArray(0)
                                retryResp.close()

                                // 探测重试响应的压缩格式并解压
                                if (retryRaw.size >= 4 &&
                                    retryRaw[0] == 0x28.toByte() &&
                                    retryRaw[1] == 0xB5.toByte() &&
                                    retryRaw[2] == 0x2F.toByte() &&
                                    retryRaw[3] == 0xFD.toByte()) {
                                    // 仍是 ZSTD，尝试解压
                                    var retryDecompressed: ByteArray? = null
                                    try {
                                        val rDeclared = com.github.luben.zstd.Zstd.decompressedSize(retryRaw)
                                        val rValid = !com.github.luben.zstd.Zstd.isError(rDeclared) &&
                                            rDeclared > 0 && rDeclared <= MAX_DECOMPRESSED_SIZE
                                        val rBufSizes = if (rValid) {
                                            listOf(rDeclared.toInt())
                                        } else {
                                            listOf(retryRaw.size * 6, retryRaw.size * 12, retryRaw.size * 20)
                                                .filter { it > 0 && it.toLong() <= MAX_DECOMPRESSED_SIZE }
                                        }
                                        for (bufSize in rBufSizes) {
                                            try {
                                                val dst = ByteArray(bufSize)
                                                val result = com.github.luben.zstd.Zstd.decompress(dst, retryRaw)
                                                if (!com.github.luben.zstd.Zstd.isError(result) && result > 0) {
                                                    retryDecompressed = if (result < dst.size) dst.copyOf(result.toInt()) else dst
                                                    break
                                                }
                                            } catch (oom: OutOfMemoryError) { break }
                                            catch (e: com.github.luben.zstd.ZstdException) {
                                                // 【Fix O 2026-07-06】"Data corruption detected" 立即 break，避免无效的大缓冲区分配
                                                if (e.message?.contains("Data corruption detected") == true) break
                                                /* buf too small → try next */
                                            }
                                        }
                                    } catch (e: Exception) { /* 忽略，走流式 */ }
                                    if (retryDecompressed == null) {
                                        try {
                                            val zstdIs = com.github.luben.zstd.ZstdInputStream(
                                                java.io.ByteArrayInputStream(retryRaw)
                                            )
                                            retryDecompressed = zstdIs.readBytes()
                                            zstdIs.close()
                                        } catch (e: Exception) { /* 忽略 */ }
                                    }
                                    if (retryDecompressed != null) {
                                        finalBytes = retryDecompressed
                                        retryResolved = true
                                        Log.i(TAG, "【ZSTD 重试 #$retryIdx 解压成功】: $targetUrlStr (解压=${retryDecompressed.size})")
                                        onRequestLogState.value(
                                            "zstd-retry-success",
                                            "ZSTD 重试 #$retryIdx 解压成功",
                                            mapOf(
                                                "url" to targetUrlStr,
                                                "retry" to retryIdx.toString(),
                                                "decompressedSize" to retryDecompressed.size.toString()
                                            )
                                        )
                                        break
                                    }
                                } else if (retryRaw.size >= 2 &&
                                           retryRaw[0] == 0x1F.toByte() &&
                                           retryRaw[1] == 0x8B.toByte()) {
                                    finalBytes = java.util.zip.GZIPInputStream(
                                        java.io.ByteArrayInputStream(retryRaw)
                                    ).readBytes()
                                    retryResolved = true
                                    Log.i(TAG, "【重试 #$retryIdx 获取 GZIP 数据，解压成功】: $targetUrlStr")
                                    break
                                } else {
                                    // 非压缩明文（服务器可能配置修复或 CDN 缓存更新）
                                    finalBytes = retryRaw
                                    retryResolved = true
                                    Log.i(TAG, "【重试 #$retryIdx 获取明文数据】: $targetUrlStr")
                                    break
                                }
                            } catch (retryEx: Exception) {
                                Log.w(TAG, "  ZSTD 重试 #$retryIdx 异常: ${retryEx.message}")
                            }
                        }

                        if (!retryResolved) {
                            // 【Fix F+H 2026-07-06：返回原始 ZSTD 流 + Content-Encoding: zstd 头 + CORS 头】
                            // Chromium 123+ (SystemWebViewGoogle6432+) 原生支持 zstd 解压。
                            // 严禁 return null（必致 Chromium 重新请求 → X-Requested-With → 404）。
                            // 【Fix H】必须包含原始响应头中的 CORS 头（Access-Control-Allow-Origin: *），
                            // 否则 JS fetch() 会因 CORS 策略拒绝响应 → Live2D SDK 拿不到数据 → 模型加载失败。
                            // 旧代码只返回 Content-Encoding+Cache-Control → CORS 拦截 → 模型加载失败。
                            Log.e(TAG, "【ZSTD 解压+重试全部失败！返回原始 ZSTD 流 + Content-Encoding: zstd + CORS 头】: $targetUrlStr")
                            onRequestLogState.value(
                                "zstd-decompress-failed",
                                "ZSTD 全部失败，返回原始 ZSTD 流交 Chromium native 解压",
                                mapOf(
                                    "url" to targetUrlStr,
                                    "compressedSize" to compressedSize.toString(),
                                    "fallback" to "raw-zstd-with-ce-header"
                                )
                            )
                            val zstdHeaders = responseHeaders.toMutableMap()
                            zstdHeaders["Content-Encoding"] = "zstd"
                            return WebResourceResponse(
                                mimeType,
                                encoding,
                                200,
                                "OK",
                                zstdHeaders,
                                java.io.ByteArrayInputStream(finalBytes)
                            )
                        }
                    }
                }

                // 【防线 5：异常 catch 不再返回 null】
                // 构造合成 WebResourceResponse 避免内核接管（内核接管会发出 zstd → JSON.parse 崩溃）
                // 子资源失败让 JS 自行处理（Promise reject），主帧失败由 onReceivedError 处理
                // finalBytes 已读入内存，安全 disconnect 避免复用损坏的 keep-alive 连接
                connection.disconnect()
                WebResourceResponse(
                    mimeType,
                    encoding,
                    responseCode,
                    connection.responseMessage ?: "OK",
                    responseHeaders,
                    java.io.ByteArrayInputStream(finalBytes)
                )
            } catch (e: Exception) {
                Log.w(TAG, "网络请求处理异常 ($urlStr): ${e.message}")
                onRequestLogState.value(
                    "intercept-error",
                    "拦截器异常 → 构造合成 503 响应避免内核接管: ${e.message}",
                    mapOf("url" to urlStr, "error" to (e.message ?: ""))
                )
                // ⚠️ 绝对不返回 null！返回合成 503 响应阻止内核接管。
                // 返回 null 会让内核接管请求 → 默认 Accept-Encoding: gzip, deflate, br, zstd
                // → static.l2d.su 返回 zstd → JS JSON.parse 崩溃
                // 【Fix N 2026-07-06】必须包含 Access-Control-Allow-Origin: * 头！
                // 旧代码只返回 Content-Type 头 → JS fetch() 因 CORS 策略拒绝响应
                // → Live2D SDK 拿不到错误信息 → 尝试大写 URL fallback → 404
                // 日志证据：1.txt 行 642 aersasi_3.cdi3.json CORS 错误
                WebResourceResponse(
                    "text/plain",
                    "utf-8",
                    503,
                    "Service Unavailable (Intercepted)",
                    mapOf(
                        "Content-Type" to "text/plain; charset=utf-8",
                        "Access-Control-Allow-Origin" to "*"
                    ),
                    java.io.ByteArrayInputStream(ByteArray(0))
                )
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val urlString = request?.url?.toString() ?: return false
            val scheme = request.url?.scheme ?: return false
            // 非 http(s) 协议（mailto: / tel: / intent: 等）：交给系统处理
            if (scheme != "http" && scheme != "https") return true

            // l2d.su 同域主帧跳转/重定向：主动拦截并注入完整安全请求头
            // 防止系统 WebView 接管后丢失自定义头并重新注入 X-Requested-With 包名
            if (request.isForMainFrame && urlString.contains(LIVE2D_HOST)) {
                val customHeaders = MAIN_REQUEST_HEADERS.toMutableMap().apply {
                    put("Sec-Fetch-Site", "same-origin")
                    put("Referer", LIVE2D_URL)
                }
                view?.loadUrl(urlString, customHeaders)
                return true
            }

            // 外部链接：在 WebView 内正常加载（不拦截）
            return false
        }

        /**
         * 触发 403/503 自动重试。两条路径共用此方法：
         * 1. onReceivedHttpError 收到 403/503 状态码
         * 2. onPageFinished 内容检测发现 Cloudflare 拦截页（回调顺序竞态兜底）
         *
         * @return true 表示已调度重试；false 表示已达重试上限（已重试过一次）
         */
        fun scheduleRetryIfAvailable(view: WebView?, reason: String): Boolean {
            val retried = (view?.getTag(R.id.live2d_forbidden_retry_tag) as? Boolean) == true
            if (retried) return false
            view?.setTag(R.id.live2d_forbidden_retry_tag, true)
            // 抑制当前拦截页的完成回调，仅等待重试页面加载完成
            pendingRetry = true
            onRequestLogState.value(
                "retry",
                "$reason，${FORBIDDEN_RETRY_DELAY_MS}ms 后自动重试（等待 WAF JS 验证完成）",
                mapOf("delayMs" to FORBIDDEN_RETRY_DELAY_MS.toString())
            )
            Log.w(TAG, "$reason, auto-retrying once for $LIVE2D_HOST")
            view?.postDelayed({
                // 重试前持久化保存 Cloudflare JS Challenge 期间获取的 cf_clearance Cookie
                CookieManager.getInstance().flush()
                onRequestLogState.value("retry", "正在重试加载 $LIVE2D_URL", mapOf("attempt" to "1"))
                view.loadUrl(LIVE2D_URL, MAIN_REQUEST_HEADERS)
            }, FORBIDDEN_RETRY_DELAY_MS)
            return true
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (url.isWebPageUrl()) {
                // 新导航开始：清除待重试标记，使本次加载的完成回调正常生效
                pendingRetry = false
                onRequestLogState.value("page-started", "页面开始加载", mapOf("url" to (url ?: "")))
                onLoadStartedState.value()
            }
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            // 重试期间忽略 Cloudflare 拦截页的首帧可见信号
            if (url.isWebPageUrl() && !pendingRetry) {
                onRequestLogState.value("page-visible", "页面首帧可见", mapOf("url" to (url ?: "")))
                onPageVisibleState.value()
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            if (!url.isWebPageUrl() || pendingRetry) return

            // ── Cloudflare 拦截页内容检测 ──
            // Android WebView 回调顺序不保证：onPageFinished 可能先于 onReceivedHttpError 触发，
            // 导致 403 拦截页被误判为"加载成功"。此处通过 JS 检测页面内容兜底。
            view?.evaluateJavascript(CLOUDFLARE_BLOCK_DETECT_SCRIPT) { result ->
                // 回调可能晚于 onReceivedHttpError 触发，需再次检查 pendingRetry
                if (pendingRetry) {
                    onRequestLogState.value(
                        "page-finished-skip",
                        "onPageFinished 被重试机制抑制",
                        mapOf("url" to (url ?: ""))
                    )
                    return@evaluateJavascript
                }

                val isBlockPage = result?.contains("cloudflare_block") ?: false
                if (isBlockPage) {
                    onRequestLogState.value(
                        "cf-block",
                        "onPageFinished 检测到 Cloudflare 拦截页",
                        mapOf("url" to (url ?: ""), "title" to (result ?: ""))
                    )
                    // 内容检测命中，按 403 处理（即使 onReceivedHttpError 未触发或尚未触发）
                    if (!scheduleRetryIfAvailable(view, "Cloudflare 拦截页内容检测")) {
                        // 已重试过仍为拦截页 → 加载失败
                        onLoadFailedState.value("Cloudflare 持续拦截，请切换网络后重试")
                    }
                } else {
                    // 真实页面加载完成
                    onRequestLogState.value("page-finished", "页面加载完成", mapOf("url" to (url ?: "")))
                    onLoadFinishedState.value()

                    // 【前置注入 Cubism 4 Core 运行库保障脚本】
                    // 确保 Live2D 引擎初始化前全局变量 window.Live2DCubismCore 绝对就绪
                    // 主站 l2d.su/lib/live2dcubismcore.min.js 偶发 3xx 重定向已由 shouldInterceptRequest
                    // 手动跟随修复；此处作为 WAF 403 等极端场景的兜底，从 jsDelivr NPM 与官方 CDN 链式加载
                    // 【2026-07-03 修正】旧 URL https://cdn.jsdelivr.net/gh/dengzii/Live2D-v3@master/...
                    // 返回 404 且 MIME 为 text/plain → 浏览器拒绝执行；改用 NPM 包 live2dcubismcore@1.0.2
                    // 与 Live2D 官方 CDN 作为双保险
                    val ensureCubismCoreScript = """
(function() {
    if (!window.Live2DCubismCore) {
        console.warn('[Live2D] window.Live2DCubismCore 未就绪，启动公共 CDN 备份链式加载');
        var urls = [
            'https://cdn.jsdelivr.net/npm/live2dcubismcore@1.0.2/live2dcubismcore.min.js',
            'https://cubism.live2d.com/sdk-web/cubismcore/live2dcubismcore.min.js'
        ];
        function tryNext(i) {
            if (i >= urls.length) {
                console.error('[Live2D] 所有 CDN 备份均加载失败，Live2D 引擎将无法初始化');
                return;
            }
            var s = document.createElement('script');
            s.src = urls[i];
            s.async = false;
            s.onload = function() {
                if (window.Live2DCubismCore) {
                    console.log('[Live2D] CDN 备份加载成功: ' + urls[i]);
                } else {
                    console.warn('[Live2D] CDN 加载完成但 Live2DCubismCore 仍未定义: ' + urls[i] + '，尝试下一个');
                    tryNext(i + 1);
                }
            };
            s.onerror = function() {
                console.warn('[Live2D] CDN 加载失败: ' + urls[i] + '，尝试下一个');
                tryNext(i + 1);
            };
            document.head.appendChild(s);
        }
        tryNext(0);
    }
})();
""".trimIndent()
                    view?.evaluateJavascript(ensureCubismCoreScript, null)

                    // 注入 WebGL 检测和错误监控脚本
                    view?.evaluateJavascript(WEBGL_DETECT_AND_MONITOR_SCRIPT, null)
                    view?.evaluateJavascript(WEB_OPTIMIZATION_SCRIPT, null)
                }
            }
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame == true) {
                val errMsg = error.toFriendlyMessage()
                onRequestLogState.value(
                    "net-error",
                    "主帧网络错误：$errMsg",
                    mapOf(
                        "errorCode" to (error?.errorCode?.toString() ?: "-1"),
                        "description" to (error?.description?.toString() ?: "")
                    )
                )
                onLoadFailedState.value(errMsg)
            }
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            val code = errorResponse?.statusCode ?: 0
            val reqUrl = request?.url?.toString() ?: ""
            val isMainFrame = request?.isForMainFrame == true
            // 记录所有 HTTP 错误（含子资源），便于排查资源加载失败
            onRequestLogState.value(
                "http-error",
                "HTTP $code ${if (isMainFrame) "(主帧)" else "(子资源)"}",
                mapOf(
                    "url" to reqUrl,
                    "statusCode" to code.toString(),
                    "mainFrame" to isMainFrame.toString()
                )
            )
            // 【关键监控】：子资源（如 /assets/xxx.js 或 /api/xxx）返回 403/5xx，
            // 不会触发主帧错误但会导致 SPA 停止渲染模型，直接标红打出便于诊断
            if (!isMainFrame && code >= 400) {
                Log.e(TAG, "【子资源加载失败】HTTP $code | URL: $reqUrl")
            }
            if (isMainFrame && code >= 400) {
                // 若重试已由 onPageFinished 的内容检测调度，忽略此错误避免重复处理
                if (pendingRetry) {
                    onRequestLogState.value(
                        "http-error-suppressed",
                        "HTTP $code 已被重试机制处理",
                        mapOf("statusCode" to code.toString())
                    )
                    return
                }
                // Cloudflare/WAF 对 in-app WebView 可能偶发 403/503；
                // 首次命中时自动重试一次（首次响应可能已下发 cf_clearance cookie，重试即可通过）。
                if (code == 403 || code == 503) {
                    if (!scheduleRetryIfAvailable(view, "主帧 HTTP $code")) {
                        onLoadFailedState.value(errorResponse.toFriendlyMessage())
                    }
                    return
                }
                onLoadFailedState.value(errorResponse.toFriendlyMessage())
            }
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            val host = view?.url?.let { java.net.URL(it).host } ?: ""
            val friendlyMsg = error.toFriendlyMessage()
            val primaryError = error?.primaryError ?: -1
            Log.w(TAG, "SSL error for host=$host: $friendlyMsg (primaryError=$primaryError, trusted=${sslTrustedState.value})")

            // 记录 SSL 证书详细信息用于诊断
            val certInfo = error?.certificate?.let { cert ->
                "DN=${cert.issuedBy?.dName}, O=${cert.issuedBy?.oName}, U=${cert.issuedBy?.uName}, " +
                "Valid=${cert.validNotBeforeDate}~${cert.validNotAfterDate}"
            } ?: "null"
            onRequestLogState.value(
                "ssl-error",
                "SSL 错误：$friendlyMsg",
                mapOf(
                    "host" to host,
                    "primaryError" to primaryError.toString(),
                    "trusted" to sslTrustedState.value.toString(),
                    "cert" to certInfo
                )
            )
            Log.w(TAG, "SSL certificate info: $certInfo")

            if (sslTrustedState.value && host == LIVE2D_HOST) {
                // 用户已明确信任此域名，允许继续加载
                Log.i(TAG, "Proceeding with SSL for trusted host: $host")
                handler?.proceed()
            } else {
                // 首次遇到 SSL 错误，拒绝并通知 UI 显示警告
                handler?.cancel()
                onSslErrorState.value(friendlyMsg, primaryError)
            }
        }

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            val msg = detail.toFriendlyMessage()
            onRequestLogState.value(
                "render-gone",
                "渲染进程异常：$msg",
                mapOf("didCrash" to (detail?.didCrash()?.toString() ?: "unknown"))
            )
            onRendererGoneState.value(msg)
            return true
        }
    }

    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            onLoadProgressState.value(newProgress)
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            val level = consoleMessage.messageLevel()
            val msg = "[${level}] ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
            Log.d(TAG, "Console: $msg")

            // 捕获错误和警告级别的控制台消息
            when (level) {
                ConsoleMessage.MessageLevel.ERROR,
                ConsoleMessage.MessageLevel.WARNING -> {
                    onConsoleErrorState.value(msg)
                }
                else -> Unit
            }
            return true
        }

        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
            Log.w(TAG, "JS Alert from $url: $message")
            result?.confirm()
            return true
        }
    }
}

/**
 * Cloudflare 拦截页检测脚本。
 *
 * 解决 Android WebView 回调顺序竞态：onPageFinished 可能先于 onReceivedHttpError 触发，
 * 导致 403 拦截页被误判为"加载成功"。本脚本在 onPageFinished 时检测页面内容是否为
 * Cloudflare 挑战页/拦截页，若命中则触发重试而非进入 Ready 状态。
 *
 * 检测特征（覆盖 Cloudflare 主要拦截形态）：
 * - "Just a moment..." —— 托管挑战页（JS Challenge / Turnstile）
 * - "Attention Required!" —— 防火墙拦截页（403 Block）
 * - cf-browser-verification / cf-challenge-running / cf-mitigated —— DOM 标识
 * - "Cloudflare" + "Ray ID" —— 拦截页底部签名
 */
private const val CLOUDFLARE_BLOCK_DETECT_SCRIPT = """
(function() {
    try {
        var title = (document.title || '').toLowerCase();
        var bodyHtml = document.body ? document.body.innerHTML.substring(0, 3000) : '';
        var bodyLower = bodyHtml.toLowerCase();
        if (title.indexOf('just a moment') !== -1 ||
            title.indexOf('attention required') !== -1 ||
            bodyLower.indexOf('cf-browser-verification') !== -1 ||
            bodyLower.indexOf('cf-challenge-running') !== -1 ||
            bodyLower.indexOf('cf-mitigated') !== -1 ||
            (bodyLower.indexOf('cloudflare') !== -1 && bodyLower.indexOf('ray id') !== -1) ||
            bodyLower.indexOf('enable javascript and cookies to continue') !== -1) {
            return 'cloudflare_block';
        }
        return 'ok';
    } catch(e) {
        return 'error:' + e.message;
    }
})()
"""

/**
 * WebGL 检测与加载状态监控脚本。
 * 在页面加载完成后注入，检测 WebGL 支持状态并捕获加载过程中的错误。
 * 注意：l2d.su 是 Vite SPA，不暴露 window.app/pixiApp 全局，故不再做框架相关轮询。
 */
private const val WEBGL_DETECT_AND_MONITOR_SCRIPT = """
(function() {
    'use strict';

    // ── WebGL 支持检测 ──
    function detectWebGL() {
        try {
            var canvas = document.createElement('canvas');
            var gl = canvas.getContext('webgl2') || canvas.getContext('webgl') || canvas.getContext('experimental-webgl');
            if (gl) {
                var renderer = gl.getParameter(gl.RENDERER);
                var vendor = gl.getParameter(gl.VENDOR);
                var version = gl.getParameter(gl.VERSION);
                console.log('[Live2D] WebGL OK: renderer=' + renderer + ', vendor=' + vendor + ', version=' + version);
                return 'supported: ' + renderer;
            }
            console.error('[Live2D] WebGL not available');
            return 'not_supported';
        } catch(e) {
            console.error('[Live2D] WebGL detection failed: ' + e.message);
            return 'error: ' + e.message;
        }
    }

    detectWebGL();

    // ── 全局错误捕获 ──
    window.addEventListener('error', function(e) {
        console.error('[Live2D] Uncaught error: ' + e.message + ' at ' + e.filename + ':' + e.lineno + ':' + e.colno);
    });

    window.addEventListener('unhandledrejection', function(e) {
        var reason = e.reason ? (e.reason.stack || e.reason.message || e.reason) : 'unknown';
        console.error('[Live2D] Unhandled promise rejection: ' + reason);
    });

    // ── WebGL 上下文丢失监控 ──
    document.addEventListener('webglcontextlost', function(e) {
        console.error('[Live2D] WebGL context lost!');
    }, true);

    document.addEventListener('webglcontextrestored', function(e) {
        console.log('[Live2D] WebGL context restored');
    }, true);

    // ── 资源加载错误监控 ──
    document.addEventListener('error', function(e) {
        var target = e.target;
        if (target && (target.tagName === 'IMG' || target.tagName === 'SCRIPT' || target.tagName === 'LINK')) {
            console.error('[Live2D] Resource load failed: ' + target.tagName + ' src=' + (target.src || target.href));
        }
    }, true);
})();
"""

/**
 * Live2D 网页渲染的轻量、非侵入式优化脚本。
 *
 * 设计原则：l2d.su 是自带 WebGL 画布与 resize 管理的 Vite SPA，
 * 任何对 canvas.width/height、框架内部状态（window.app / pixiApp）的直接覆写
 * 都会清空 WebGL 绘制缓冲区、破坏模型变换（曾导致 scale 被反复相乘而指数放大）。
 * 因此本脚本只做不干扰 SPA 自身渲染逻辑的安全增强。
 */
private const val WEB_OPTIMIZATION_SCRIPT = """
(function() {
    'use strict';

    // 1. 补全 viewport-fit=cover 以适配刘海屏（站点已自带 viewport，仅补全此项）
    var meta = document.querySelector('meta[name=viewport]');
    if (meta && meta.content.indexOf('viewport-fit=cover') === -1) {
        meta.content = meta.content + ',viewport-fit=cover';
    }

    // 2. 禁用浏览器手势缩放，避免双指操作干扰 Live2D 交互
    document.addEventListener('gesturestart', function(e) { e.preventDefault(); }, { passive: false });

    // 3. 【设备分辨率适配】：读取 devicePixelRatio 并输出诊断日志，
    // 确保 PixiJS 在高 DPI 设备上以物理像素分辨率渲染（PixiJS 自动读取此值设置 canvas resolution）
    var dpr = window.devicePixelRatio || 1;
    try { console.log('[Live2D] devicePixelRatio=' + dpr + ', inner=' + window.innerWidth + 'x' + window.innerHeight + ', screen=' + window.screen.width + 'x' + window.screen.height); } catch(e) {}

    // 4. 【核心唤醒】：页面加载完成后连续派发 window.resize 事件，
    // 通知 PixiJS / Live2D 引擎重新校准 Canvas 画布尺寸与显存分配，
    // 应对首次 layout 时可能因尺寸未稳定导致的画布休眠/错位。
    // 不直接修改 canvas.width/height（会清空 WebGL 绘制缓冲区），仅派发合成事件让 SPA 自身处理。
    setTimeout(function() {
        try { window.dispatchEvent(new Event('resize')); } catch(e) {}
    }, 100);
    setTimeout(function() {
        try { window.dispatchEvent(new Event('resize')); } catch(e) {}
    }, 600);
})();
"""

@SuppressLint("ClickableViewAccessibility")
private fun WebView.installOrientationChangeGuard() {
    val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        // 不直接修改 canvas 的 width/height（会清空 WebGL 绘制缓冲区），
        // 也不触碰框架内部状态。仅在布局稳定后派发 resize 事件，
        // 让 SPA 自身的 resize 处理逻辑接管画布尺寸与模型重排。
        postDelayed({
            evaluateJavascript(
                "(function(){" +
                "try{window.dispatchEvent(new Event('resize'));}catch(e){}" +
                "try{window.dispatchEvent(new Event('orientationchange'));}catch(e){}" +
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
    // 合成纯净标准 Chrome Mobile UA，彻底抹除 ; wv / Version/4.0 / MIUI 等厂商魔改标记
    userAgentString = buildCleanBrowserUserAgent()

    // 【设备分辨率适配】：启用宽视口与概览模式，确保 WebView 获取真实物理分辨率
    // useWideViewPort=true 让 WebView 支持 viewport meta 标签，SPA 可正确读取 window.innerWidth
    // loadWithOverviewMode=true 使页面以缩放至屏幕宽度的方式加载，适配不同 DPI 设备
    useWideViewPort = true
    loadWithOverviewMode = true

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
    return when (statusCode) {
        403 -> "访问被拒绝（403）。站点防火墙可能拦截了请求，请切换网络后重试"
        404 -> "资源不存在（404）"
        in 500..599 -> "服务器异常（$statusCode），请稍后重试"
        else -> "服务器返回 $statusCode，请稍后重试"
    }
}

private fun SslError?.toFriendlyMessage(): String = when (this?.primaryError) {
    SslError.SSL_EXPIRED -> "网页证书已过期"
    SslError.SSL_IDMISMATCH -> "网页证书域名不匹配"
    SslError.SSL_UNTRUSTED -> "网页证书不受信任"
    SslError.SSL_DATE_INVALID -> "证书日期无效，请检查设备系统时间是否正确"
    SslError.SSL_INVALID -> "证书验证失败"
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
private fun ErrorOverlay(
    message: String,
    onRetry: () -> Unit,
    onCopyError: () -> Unit
) {
    val isDark = LocalIsDark.current
    val bgColor = if (isDark) Color(0xFF0D1117) else Color(0xFFF6F8FA)
    Box(
        modifier = Modifier.fillMaxSize().background(bgColor).padding(AppSpacing.Xxl),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("加载失败", style = AppTypography.TitleLargeBold)
            Spacer(Modifier.height(AppSpacing.Sm))
            Text(message, style = AppTypography.BodyMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(AppSpacing.Xl))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(onClick = onRetry, shape = RoundedCornerShape(AppSpacing.Corner.Lg), color = MaterialTheme.colorScheme.primary) {
                    Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("重试", style = AppTypography.ButtonText)
                    }
                }
                Surface(
                    onClick = onCopyError,
                    shape = RoundedCornerShape(AppSpacing.Corner.Lg),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.ContentCopy, null, Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("复制错误信息", style = AppTypography.ButtonText, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
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
    onReject: () -> Unit,
    onCopyError: () -> Unit
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
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
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
                    // 复制错误信息按钮
                    Surface(
                        onClick = onCopyError,
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Rounded.ContentCopy, null, Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "复制错误详情",
                                style = AppTypography.LabelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
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
