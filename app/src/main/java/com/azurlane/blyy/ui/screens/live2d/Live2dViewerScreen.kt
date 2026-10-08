package com.azurlane.blyy.ui.screens.live2d

import android.content.pm.ActivityInfo
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.CenterFocusWeak
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurlane.blyy.ui.components.BlyyHaptic
import com.azurlane.blyy.ui.components.rememberBlyyHaptics
import com.azurlane.blyy.ui.theme.AppColors
import com.azurlane.blyy.ui.theme.AppElevation
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.viewmodel.Live2dViewerViewModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 渲染桥 → UI 的页面阶段 */
private enum class ViewerPhase { LOADING, READY, ERROR }

/** 查看器背景风格（循环切换） */
enum class L2dBgMode(val label: String) {
    DEEP_SEA("深海"),
    DAWN("晨曦"),
    NIGHT("夜幕");

    fun next(): L2dBgMode = entries[(ordinal + 1) % entries.size]
}

/** 碧蓝航线动作组 → 中文标签（缺省回退原名） */
private val MOTION_GROUP_LABELS: Map<String, String> = mapOf(
    "idle" to "待机",
    "login" to "登录",
    "complete" to "完成",
    "effect" to "特效",
    "home" to "主界面",
    "mail" to "邮件",
    "main_1" to "触摸·一",
    "main_2" to "触摸·二",
    "main_3" to "触摸·三",
    "mission" to "任务",
    "mission_complete" to "任务完成",
    "touch_body" to "抚摸身体",
    "touch_head" to "摸头",
    "touch_special" to "特殊触摸",
    "wedding" to "誓约",
    "tap" to "点击"
)

private fun motionGroupLabel(group: String): String = MOTION_GROUP_LABELS[group] ?: group

// ---------- 渲染桥消息 ----------

private sealed class BridgeMessage {
    data object Ready : BridgeMessage()
    data object Loaded : BridgeMessage()
    data class Error(val message: String) : BridgeMessage()
    data class MotionStart(val group: String) : BridgeMessage()
    data object MotionFinish : BridgeMessage()
    data class Thumb(val data: String) : BridgeMessage()
    data class Log(val message: String) : BridgeMessage()
    /** 用户点击模型互动（group = 渲染端随机选中的触摸系动作组） */
    data class Tap(val group: String) : BridgeMessage()
}

private val bridgeJson = Json { ignoreUnknownKeys = true }

private fun parseBridgeMessage(json: String): BridgeMessage? = runCatching {
    val obj = bridgeJson.parseToJsonElement(json).jsonObject
    when (obj["t"]?.jsonPrimitive?.content) {
        "ready" -> BridgeMessage.Ready
        "loaded" -> BridgeMessage.Loaded
        "error" -> BridgeMessage.Error(obj["message"]?.jsonPrimitive?.content ?: "未知错误")
        "motionStart" -> BridgeMessage.MotionStart(obj["group"]?.jsonPrimitive?.content ?: "")
        "motionFinish" -> BridgeMessage.MotionFinish
        "tap" -> BridgeMessage.Tap(obj["group"]?.jsonPrimitive?.content ?: "")
        "thumb" -> BridgeMessage.Thumb(obj["data"]?.jsonPrimitive?.content ?: "")
        "log" -> BridgeMessage.Log(obj["message"]?.jsonPrimitive?.content ?: "")
        else -> null
    }
}.getOrNull()

/**
 * JS 桥：渲染端通过 AndroidBridge.postMessage(JSON) 上报事件。
 * 回调运行在 WebView 的 JavaBridge 线程，接收方自行保证线程安全。
 */
private class Live2dJsBridge(val onMessage: (String) -> Unit) {
    @JavascriptInterface
    fun postMessage(json: String) = onMessage(json)
}

/**
 * Live2D 模型全屏查看器。
 *
 * WebView 加载内置渲染页（虚拟域名 live2d.local，全部资源本地），
 * Compose 层负责主题背景、控制条、动作/表情弹层与加载/错误态。
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun Live2dViewerScreen(
    onBack: () -> Unit
) {
    val viewModel: Live2dViewerViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val displayName by viewModel.displayName.collectAsStateWithLifecycle()
    val isDark = LocalIsDark.current
    val haptic = rememberBlyyHaptics()
    // 渲染桥状态
    var phase by remember { mutableStateOf(ViewerPhase.LOADING) }
    var bridgeError by remember { mutableStateOf<String?>(null) }
    var activeMotion by remember { mutableStateOf<String?>(null) }
    var motionIndexByGroup by remember { mutableStateOf(mapOf<String, Int>()) }
    var bgMode by rememberSaveable { mutableStateOf(L2dBgMode.DEEP_SEA) }
    var showMotionSheet by remember { mutableStateOf(false) }
    var showExpressionSheet by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableIntStateOf(0) }

    // 底部控制条可见性 + 交互计数（任意控件交互重置自动收起计时）
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var controlsTick by remember { mutableIntStateOf(0) }

    // 横屏查看模式：强制横屏，退出查看器时恢复系统方向
    var landscapeMode by rememberSaveable { mutableStateOf(false) }

    // AndroidView factory 与控制回调之间共享 WebView 引用
    val webViewHolder = remember { java.util.concurrent.atomic.AtomicReference<WebView?>(null) }

    fun callViewer(js: String) {
        webViewHolder.get()?.evaluateJavascript("window.__viewer && window.__viewer.$js", null)
    }

    // 供拦截器在后台线程读取当前模型信息（rememberUpdatedState 保证拿到最新值）
    val currentInfo by rememberUpdatedState(state.info)

    val bridge = remember {
        Live2dJsBridge { json ->
            when (val msg = parseBridgeMessage(json)) {
                is BridgeMessage.Loaded -> phase = ViewerPhase.READY
                is BridgeMessage.Error -> {
                    bridgeError = msg.message
                    phase = ViewerPhase.ERROR
                }
                is BridgeMessage.MotionStart -> activeMotion = msg.group
                is BridgeMessage.MotionFinish -> activeMotion = null
                is BridgeMessage.Tap -> viewModel.playInteractionVoice(msg.group)
                is BridgeMessage.Thumb -> {
                    runCatching {
                        val b64 = msg.data.substringAfter("base64,")
                        viewModel.saveThumb(Base64.decode(b64, Base64.DEFAULT))
                    }.onFailure { Log.w("Live2dViewer", "缩略图解码失败", it) }
                }
                is BridgeMessage.Log -> Log.d("Live2dViewer", "JS: ${msg.message}")
                else -> Unit
            }
        }
    }

    // 屏幕常亮：查看模型时不熄屏；离开查看器时恢复系统方向设置
    val activity = LocalActivity.current
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // 生命周期联动：退后台暂停渲染节拍器与 WebView，回前台恢复
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    webViewHolder.get()?.evaluateJavascript(
                        "window.__viewer && window.__viewer.setPaused(true)", null
                    )
                    webViewHolder.get()?.onPause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    webViewHolder.get()?.onResume()
                    webViewHolder.get()?.evaluateJavascript(
                        "window.__viewer && window.__viewer.setPaused(false)", null
                    )
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 库层错误（模型不存在等）同步进错误态
    LaunchedEffect(state.error) {
        if (state.error != null) phase = ViewerPhase.ERROR
    }

    // 控制条 5 秒无操作自动收起为迷你浮标，保持观看沉浸性；controlsTick 变化即重置计时
    LaunchedEffect(controlsVisible, controlsTick) {
        if (controlsVisible) {
            kotlinx.coroutines.delay(5000)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brushForBg(bgMode))
    ) {
        // ── 渲染画布（reloadToken 变化时整体重建以实现重试） ──
        key(reloadToken) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    // 手势导航设备上排除系统边缘返回手势，避免平移模型时误触返回
                    .systemGestureExclusion(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.useWideViewPort = false
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setLayerType(View.LAYER_TYPE_HARDWARE, null)
                        webViewClient = viewModel.createWebViewClient(ctx) { currentInfo }
                        addJavascriptInterface(bridge, "AndroidBridge")
                        loadUrl(
                            "https://live2d.local/viewer.html?model=${android.net.Uri.encode(viewModel.modelId)}"
                        )
                    }.also { webViewHolder.set(it) }
                },
                onRelease = { wv ->
                    webViewHolder.compareAndSet(wv, null)
                    wv.stopLoading()
                    wv.removeJavascriptInterface("AndroidBridge")
                    wv.destroy()
                }
            )
        }

        // ── 顶部信息栏（渐变衬底保证可读性） ──
        val topScrim = if (isDark) {
            Brush.verticalGradient(listOf(Color(0xCC0B1220), Color.Transparent))
        } else {
            Brush.verticalGradient(listOf(Color(0xB3FFFFFF), Color.Transparent))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(topScrim)
                .statusBarsPadding()
                .padding(horizontal = AppSpacing.Sm, vertical = AppSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                haptic(BlyyHaptic.Tick)
                onBack()
            }) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBackIosNew,
                    contentDescription = "返回",
                    tint = if (isDark) Color.White.copy(alpha = 0.9f) else Color(0xFF1B2735)
                )
            }
            Spacer(Modifier.width(AppSpacing.Xs))
            Column(Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = AppTypography.LabelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isDark) Color.White else Color(0xFF1B2735),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                state.info?.let { info ->
                    Text(
                        text = buildString {
                            append("Cubism ")
                            append(info.version ?: "3/4")
                            append(" · ")
                            append(info.totalMotions)
                            append(" 个动作")
                            if (info.hasExpressions) {
                                append(" · ")
                                append(info.expressions.size)
                                append(" 个表情")
                            }
                        },
                        style = AppTypography.CaptionSmall,
                        color = (if (isDark) Color.White else Color(0xFF1B2735)).copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // ── 底部控制条（可收起：手动收起 / 5 秒无操作自动收起，仅剩迷你浮标） ──
        val info = state.info
        val controlsGate = phase == ViewerPhase.READY && info != null
        AnimatedVisibility(
            visible = controlsGate && controlsVisible,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp),
            enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut()
        ) {
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(28.dp))
                    .border(
                        width = AppSpacing.Border.Thin,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(28.dp)
                    ),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
                tonalElevation = AppElevation.Level2
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 互动语音开关（图标式，随开关切换 音量开/静音 图标）
                    ViewerControlPill(
                        icon = if (state.voiceEnabled) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff,
                        label = null,
                        contentDescription = if (state.voiceEnabled) "关闭互动语音" else "开启互动语音",
                        onClick = {
                            haptic(BlyyHaptic.Tick)
                            controlsTick++
                            viewModel.setVoiceEnabled(!state.voiceEnabled)
                        }
                    )
                    ViewerControlPill(
                        icon = Icons.Rounded.Animation,
                        label = "动作",
                        showPulse = activeMotion != null,
                        onClick = {
                            haptic(BlyyHaptic.Tick)
                            controlsTick++
                            showMotionSheet = true
                        }
                    )
                    if (info?.hasExpressions == true) {
                        ViewerControlPill(
                            icon = Icons.Rounded.Mood,
                            label = "表情",
                            onClick = {
                                haptic(BlyyHaptic.Tick)
                                controlsTick++
                                showExpressionSheet = true
                            }
                        )
                    }
                    ViewerControlPill(
                        icon = Icons.Rounded.Palette,
                        label = bgMode.label,
                        onClick = {
                            haptic(BlyyHaptic.Tick)
                            controlsTick++
                            bgMode = bgMode.next()
                        }
                    )
                    ViewerControlPill(
                        icon = Icons.Rounded.CenterFocusWeak,
                        label = "复位",
                        onClick = {
                            haptic(BlyyHaptic.Tick)
                            controlsTick++
                            callViewer("resetView()")
                        }
                    )
                    ViewerControlPill(
                        icon = Icons.Rounded.ScreenRotation,
                        label = if (landscapeMode) "竖屏" else "横屏",
                        onClick = {
                            haptic(BlyyHaptic.Tick)
                            controlsTick++
                            landscapeMode = !landscapeMode
                            activity?.requestedOrientation = if (landscapeMode) {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            }
                        }
                    )
                    ViewerControlPill(
                        icon = Icons.Rounded.KeyboardArrowDown,
                        label = "收起",
                        onClick = {
                            haptic(BlyyHaptic.Tick)
                            controlsVisible = false
                        }
                    )
                }
            }
        }

        // ── 收起后的迷你浮标（右下角半透明，点击恢复控制条） ──
        AnimatedVisibility(
            visible = controlsGate && !controlsVisible,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 24.dp, end = 20.dp),
            enter = fadeIn() + scaleIn(initialScale = 0.6f),
            exit = fadeOut() + scaleOut(targetScale = 0.6f)
        ) {
            Surface(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(AppSpacing.Corner.Full))
                    .clickable {
                        haptic(BlyyHaptic.Tick)
                        controlsVisible = true
                        controlsTick++
                    },
                shape = RoundedCornerShape(AppSpacing.Corner.Full),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f),
                tonalElevation = AppElevation.Level1,
                border = androidx.compose.foundation.BorderStroke(
                    width = AppSpacing.Border.Thin,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                )
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowUp,
                        contentDescription = "展开控制条",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        // ── 加载态 ──
        if (phase == ViewerPhase.LOADING && state.error == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.tertiary,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(Modifier.height(AppSpacing.Md))
                Text(
                    "正在加载模型…",
                    style = AppTypography.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── 错误态 ──
        if (phase == ViewerPhase.ERROR) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                    .padding(AppSpacing.Lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(52.dp)
                )
                Spacer(Modifier.height(AppSpacing.Md))
                Text(
                    "模型加载失败",
                    style = AppTypography.TitleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(AppSpacing.Xs))
                Text(
                    text = bridgeError ?: state.error ?: "渲染过程出现未知错误",
                    style = AppTypography.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = AppSpacing.Lg)
                )
                Spacer(Modifier.height(AppSpacing.Lg))
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)) {
                    TextButton(onClick = {
                        haptic(BlyyHaptic.Tick)
                        bridgeError = null
                        phase = ViewerPhase.LOADING
                        reloadToken++
                    }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(AppSpacing.Xs))
                        Text("重试")
                    }
                    TextButton(onClick = onBack) { Text("返回") }
                }
            }
        }
    }

    // ── 动作弹层 ──
    val sheetInfo = state.info
    if (showMotionSheet && sheetInfo != null) {
        ModalBottomSheet(onDismissRequest = { showMotionSheet = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AppSpacing.Screen.Horizontal)
                    .padding(bottom = AppSpacing.Xl)
            ) {
                Text(
                    "播放动作",
                    style = AppTypography.TitleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = AppSpacing.Sm)
                )
                Text(
                    "点击动作立即播放，播完自动回到待机；也可以直接点击模型触发随机互动",
                    style = AppTypography.CaptionSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = AppSpacing.Md)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
                ) {
                    sheetInfo.motionGroups.forEach { (group, motions) ->
                        val isActive = activeMotion == group
                        AssistChip(
                            onClick = {
                                haptic(BlyyHaptic.Tick)
                                val idx = if (motions.size > 1) {
                                    ((motionIndexByGroup[group] ?: -1) + 1) % motions.size
                                } else 0
                                motionIndexByGroup = motionIndexByGroup + (group to idx)
                                callViewer("playMotion('$group', $idx)")
                                // 手动播放动作同样联动对应场景语音（待机等无映射组静默跳过）
                                viewModel.playInteractionVoice(group)
                                showMotionSheet = false
                            },
                            label = {
                                Text(
                                    text = motionGroupLabel(group) + if (motions.size > 1) " ×${motions.size}" else "",
                                    style = AppTypography.LabelMedium
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (isActive) MaterialTheme.colorScheme.tertiaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                                labelColor = if (isActive) MaterialTheme.colorScheme.onTertiaryContainer
                                else MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }
            }
        }
    }

    // ── 表情弹层 ──
    if (showExpressionSheet && sheetInfo != null && sheetInfo.hasExpressions) {
        ModalBottomSheet(onDismissRequest = { showExpressionSheet = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AppSpacing.Screen.Horizontal)
                    .padding(bottom = AppSpacing.Xl)
            ) {
                Text(
                    "切换表情",
                    style = AppTypography.TitleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = AppSpacing.Md)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
                ) {
                    sheetInfo.expressions.forEachIndexed { index, expr ->
                        AssistChip(
                            onClick = {
                                haptic(BlyyHaptic.Tick)
                                callViewer("playExpression($index)")
                                showExpressionSheet = false
                            },
                            label = { Text(expr.name, style = AppTypography.LabelMedium) }
                        )
                    }
                }
            }
        }
    }
}

/** 底部控制条的胶囊按钮（label = null 时渲染为纯图标模式） */
@Composable
private fun ViewerControlPill(
    icon: ImageVector,
    label: String?,
    showPulse: Boolean = false,
    contentDescription: String? = null,
    onClick: () -> Unit
) {
    val pulse = rememberInfiniteTransition(label = "motionPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "pulseAlpha"
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = if (label != null) 10.dp else 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(18.dp)
                .alpha(if (showPulse) pulseAlpha else 1f)
        )
        if (label != null) {
            Text(
                text = label,
                style = AppTypography.LabelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** 背景渐变（深海/晨曦/夜幕），画布透明以让主题渐变透出 */
private fun brushForBg(mode: L2dBgMode): Brush = when (mode) {
    L2dBgMode.DEEP_SEA -> Brush.verticalGradient(
        listOf(
            AppColors.Live2DViewer.BgDark,
            Color(0xFF16233C),
            Color(0xFF0C1420)
        )
    )
    L2dBgMode.DAWN -> Brush.verticalGradient(
        listOf(
            AppColors.Live2DViewer.BgLight,
            Color(0xFFFFFFFF),
            Color(0xFFE1EDF6)
        )
    )
    L2dBgMode.NIGHT -> Brush.verticalGradient(
        listOf(Color(0xFF0B0E14), Color(0xFF14181F), Color(0xFF0B0E14))
    )
}
