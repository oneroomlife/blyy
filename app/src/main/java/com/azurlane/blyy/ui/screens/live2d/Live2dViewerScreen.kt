package com.azurlane.blyy.ui.screens.live2d

import android.annotation.SuppressLint
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.BatteryManager
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CenterFocusWeak
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Shadow

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

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
    /** 陪伴模式定时触发（group = 渲染端随机选中的非待机动作组，动作已在渲染端播放） */
    data class Companion(val group: String) : BridgeMessage()
    /** 用户触摸画布（任意手势按下）：陪伴沉浸模式下恢复界面显示 */
    data object Touch : BridgeMessage()
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
        "companion" -> BridgeMessage.Companion(obj["group"]?.jsonPrimitive?.content ?: "")
        "touch" -> BridgeMessage.Touch
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
    // 注意：必须用块体而非表达式体（= onMessage(json)），否则 lint 无法识别
    // @JavascriptInterface 注解并误报 JavascriptInterface 错误
    @JavascriptInterface
    fun postMessage(json: String) {
        onMessage(json)
    }
}

/**
 * Live2D 模型全屏查看器。
 *
 * WebView 加载内置渲染页（虚拟域名 live2d.local，全部资源本地），
 * Compose 层负责主题背景、控制条、动作/表情弹层与加载/错误态。
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
// suppress JavascriptInterface：postMessage 已标注 @JavascriptInterface（见 Live2dJsBridge），
// lint 经 remember 内联泛型解析 bridge 实参类型时得到类型变量 T 而误报（加显式声明类型亦无效）
@SuppressLint("JavascriptInterface")
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
    var showCompanionSheet by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableIntStateOf(0) }

    // 底部控制条可见性 + 交互计数（任意控件交互重置自动收起计时）
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var controlsTick by remember { mutableIntStateOf(0) }

    // 横屏查看模式：强制横屏，退出查看器时恢复系统方向
    var landscapeMode by rememberSaveable { mutableStateOf(false) }

    // 陪伴模式首次开启时自动弹一次设置层（间隔/时钟/电量），后续手动进
    var companionHintShown by rememberSaveable { mutableStateOf(false) }

    // 陪伴沉浸模式：开启即收齐全部界面；触摸画布恢复；10s 无操作再收齐
    var uiRevealed by rememberSaveable { mutableStateOf(true) }
    var companionUiTick by remember { mutableIntStateOf(0) }
    // 进入沉浸时的一次性操作提示（2.6s 后自动淡出）
    var showImmersiveHint by remember { mutableStateOf(false) }

    // AndroidView factory 与控制回调之间共享 WebView 引用
    val webViewHolder = remember { java.util.concurrent.atomic.AtomicReference<WebView?>(null) }

    fun callViewer(js: String) {
        webViewHolder.get()?.evaluateJavascript("window.__viewer && window.__viewer.$js", null)
    }

    // 供拦截器在后台线程读取当前模型信息（rememberUpdatedState 保证拿到最新值）
    val currentInfo by rememberUpdatedState(state.info)

    // 显式声明类型：经 remember 内联泛型返回时 lint 会把实参类型解析成类型变量 T，
    // 导致 JavascriptInterface 检查看不到 @JavascriptInterface 注解而误报
    val bridge: Live2dJsBridge = remember {
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
                is BridgeMessage.Companion -> viewModel.playInteractionVoice(msg.group)
                is BridgeMessage.Touch -> if (viewModel.state.value.companionEnabled) {
                    // 陪伴沉浸模式：任意触摸画布 → 恢复界面并重置 10s 收起计时
                    uiRevealed = true
                    controlsVisible = true
                    companionUiTick++
                }
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
    // （陪伴模式下禁用本计时：由下面 10s 沉浸计时统一收齐全部界面）
    LaunchedEffect(controlsVisible, controlsTick, state.companionEnabled) {
        if (controlsVisible && !state.companionEnabled) {
            delay(5000)
            controlsVisible = false
        }
    }

    // 陪伴沉浸计时：界面恢复后 10 秒无操作（画布触摸/控件交互均重置）→ 再次收齐
    LaunchedEffect(state.companionEnabled, uiRevealed, companionUiTick, controlsTick) {
        if (state.companionEnabled && uiRevealed) {
            delay(10_000)
            uiRevealed = false
        }
    }

    // 沉浸提示一次性自动淡出
    LaunchedEffect(showImmersiveHint) {
        if (showImmersiveHint) {
            delay(2600)
            showImmersiveHint = false
        }
    }

    // 陪伴模式配置下发渲染端：开关/间隔变化、模型就绪、重试重建 WebView 后都要重推
    // （渲染端 ticker 驱动计时，退后台自动暂停，无需宿主额外门控）
    LaunchedEffect(
        state.companionEnabled,
        state.companionIntervalSec,
        phase,
        reloadToken
    ) {
        callViewer("setCompanion(${state.companionEnabled}, ${state.companionIntervalSec * 1000})")
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

        // ── 顶部信息栏（渐变衬底保证可读性；陪伴沉浸模式下随界面一起收起） ──
        // 暗色下立绘常顶到上半屏，两段直落渐变对浅色发饰遮蔽不足 → 三段更实更平滑
        val topScrim = if (isDark) {
            Brush.verticalGradient(
                listOf(Color(0xE60B1220), Color(0x8C0B1220), Color.Transparent)
            )
        } else {
            Brush.verticalGradient(listOf(Color(0xB3FFFFFF), Color.Transparent))
        }
        AnimatedVisibility(
            visible = !state.companionEnabled || uiRevealed,
            modifier = Modifier.fillMaxWidth(),
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(300))
        ) {
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
                        color = (if (isDark) Color.White else Color(0xFF1B2735))
                            .copy(alpha = if (isDark) 0.78f else 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        }

        // ── 语音台词气泡（顶部左侧：模型水平居中且头部偏上，角落最不易遮挡立绘；点按即收起） ──
        val voiceBubble = state.voiceBubble
        // 退出动画期间保留最后一条台词内容，避免气泡瞬移消失
        val shownBubble = remember { mutableStateOf<Live2dViewerViewModel.VoiceBubble?>(null) }
        SideEffect { if (voiceBubble != null) shownBubble.value = voiceBubble }
        AnimatedVisibility(
            visible = voiceBubble != null,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 72.dp, start = AppSpacing.Md),
            enter = fadeIn(tween(180)) +
                scaleIn(initialScale = 0.85f, animationSpec = tween(180)) +
                slideInVertically(initialOffsetY = { -it / 4 }, animationSpec = tween(180)),
            exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.92f, animationSpec = tween(140))
        ) {
            shownBubble.value?.let { bubble ->
                VoiceBubbleCard(
                    dialogue = bubble.dialogue,
                    scene = bubble.scene,
                    onDismiss = { viewModel.dismissVoiceBubble() }
                )
            }
        }

        // ── 底部控制条（可收起：手动收起 / 5 秒无操作自动收起，仅剩迷你浮标） ──
        // 暗色下背景近乎纯黑：浮层更实、边框更亮才能与背景拉开层次
        val overlayAlpha = if (isDark) 0.97f else 0.94f
        val overlayBorderAlpha = if (isDark) 0.55f else 0.4f
        val info = state.info
        val controlsGate = phase == ViewerPhase.READY && info != null
        // 陪伴沉浸：界面未恢复时控制条一并收齐（收齐期间只剩 HUD 与台词气泡）
        val immersiveHidden = state.companionEnabled && !uiRevealed
        AnimatedVisibility(
            visible = controlsGate && controlsVisible && !immersiveHidden,
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
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = overlayBorderAlpha),
                        shape = RoundedCornerShape(28.dp)
                    ),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = overlayAlpha),
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
                    // 台词气泡开关（图标式，随开关切换 实心/描边 气泡图标）
                    ViewerControlPill(
                        icon = if (state.bubbleEnabled) Icons.Rounded.ChatBubble else Icons.Rounded.ChatBubbleOutline,
                        label = null,
                        contentDescription = if (state.bubbleEnabled) "隐藏台词气泡" else "显示台词气泡",
                        onClick = {
                            haptic(BlyyHaptic.Tick)
                            controlsTick++
                            viewModel.setBubbleEnabled(!state.bubbleEnabled)
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
                    // 陪伴模式开关：循环待机 + 每到间隔随机一个动作并配语音
                    ViewerControlPill(
                        icon = if (state.companionEnabled) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        label = "陪伴",
                        active = state.companionEnabled,
                        onClick = {
                            haptic(BlyyHaptic.Tick)
                            controlsTick++
                            val turningOn = !state.companionEnabled
                            viewModel.setCompanionEnabled(turningOn)
                            if (turningOn) {
                                // 进入沉浸：收齐界面并给一次性提示
                                uiRevealed = false
                                showImmersiveHint = true
                                if (!companionHintShown) {
                                    companionHintShown = true
                                    showCompanionSheet = true
                                }
                            } else {
                                // 退出沉浸：恢复常规界面状态
                                uiRevealed = true
                                controlsVisible = true
                            }
                        }
                    )
                    // 陪伴设置入口：仅陪伴中显示，减少常驻按钮
                    if (state.companionEnabled) {
                        ViewerControlPill(
                            icon = Icons.Rounded.Tune,
                            label = "陪伴设置",
                            onClick = {
                                haptic(BlyyHaptic.Tick)
                                controlsTick++
                                showCompanionSheet = true
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
                            // 陪伴模式下"收起"即进入沉浸：连迷你浮标一起收齐
                            if (state.companionEnabled) {
                                uiRevealed = false
                                showImmersiveHint = true
                            }
                        }
                    )
                }
            }
        }

        // ── 收起后的迷你浮标（右下角半透明，点击恢复控制条；沉浸收齐期间隐藏） ──
        AnimatedVisibility(
            visible = controlsGate && !controlsVisible && !immersiveHidden,
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
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isDark) 0.62f else 0.45f),
                tonalElevation = AppElevation.Level1,
                border = androidx.compose.foundation.BorderStroke(
                    width = AppSpacing.Border.Thin,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.5f else 0.35f)
                )
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowUp,
                        contentDescription = "展开控制条",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.85f else 0.75f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        // ── 陪伴模式右下角信息角标（时钟日期 + 电量，半透明不抢戏） ──
        if (controlsGate && state.companionEnabled &&
            (state.companionShowClock || state.companionShowBattery)
        ) {
            CompanionHud(
                showClock = state.companionShowClock,
                showBattery = state.companionShowBattery,
                // 控制条展开时抬高避让（条体+内边距约占 28..~92dp），收起后只让开迷你浮标
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = if (controlsVisible && !immersiveHidden) 100.dp else 72.dp)
            )
        }

        // ── 进入沉浸时的一次性提示（点屏幕可唤回界面） ──
        AnimatedVisibility(
            visible = showImmersiveHint,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp),
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(450))
        ) {
            Text(
                "点击屏幕可恢复界面",
                style = AppTypography.LabelMedium,
                color = Color.White.copy(alpha = 0.92f),
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0x990B1220))
                    .border(
                        width = AppSpacing.Border.Thin,
                        color = Color.White.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(999.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
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
    // ── 陪伴模式设置弹层 ──
    if (showCompanionSheet) {
        CompanionSettingsSheet(
            intervalSec = state.companionIntervalSec,
            showClock = state.companionShowClock,
            showBattery = state.companionShowBattery,
            // 传全量三参落盘：避免只传单项时 VM 读到未回流的旧状态互相覆盖
            onIntervalChange = {
                viewModel.updateCompanionConfig(
                    intervalSec = it,
                    showClock = state.companionShowClock,
                    showBattery = state.companionShowBattery
                )
            },
            onClockChange = {
                viewModel.updateCompanionConfig(
                    intervalSec = state.companionIntervalSec,
                    showClock = it,
                    showBattery = state.companionShowBattery
                )
            },
            onBatteryChange = {
                viewModel.updateCompanionConfig(
                    intervalSec = state.companionIntervalSec,
                    showClock = state.companionShowClock,
                    showBattery = it
                )
            },
            onDismiss = { showCompanionSheet = false }
        )
    }
}

/** 底部控制条的胶囊按钮（label = null 时渲染为纯图标模式；active 高亮陪伴等开启态） */
@Composable
private fun ViewerControlPill(
    icon: ImageVector,
    label: String?,
    showPulse: Boolean = false,
    active: Boolean = false,
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
            .background(
                if (active) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = if (label != null) 10.dp else 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) MaterialTheme.colorScheme.onTertiaryContainer
            else MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(18.dp)
                .alpha(if (showPulse) pulseAlpha else 1f)
        )
        if (label != null) {
            Text(
                text = label,
                style = AppTypography.LabelMedium,
                color = if (active) MaterialTheme.colorScheme.onTertiaryContainer
                else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** 台词气泡卡片：圆角主体 + 左下尖角尾巴（一体成型外形，描边同样贴合尾巴轮廓） */
@Composable
private fun VoiceBubbleCard(
    dialogue: String,
    scene: String,
    onDismiss: () -> Unit
) {
    val isDark = LocalIsDark.current
    val density = LocalDensity.current
    val shape = remember(density) {
        with(density) {
            SpeechBubbleShape(
                cornerRadius = 18.dp.toPx(),
                tailWidth = 16.dp.toPx(),
                tailHeight = 9.dp.toPx(),
                tailOffset = 22.dp.toPx()
            )
        }
    }
    Surface(
        modifier = Modifier
            .widthIn(max = 264.dp)
            .clickable(onClick = onDismiss),
        shape = shape,
        // 暗色下背景近黑：容器更实 + 更亮描边 + 轻抬升，保证台词可读与浮层层次
        color = MaterialTheme.colorScheme.surfaceContainerHigh
            .copy(alpha = if (isDark) 0.97f else 0.94f),
        tonalElevation = if (isDark) AppElevation.Level1 else 0.dp,
        border = BorderStroke(
            width = AppSpacing.Border.Thin,
            color = MaterialTheme.colorScheme.outlineVariant
                .copy(alpha = if (isDark) 0.55f else 0.4f)
        )
    ) {
        // 底部内边距额外加上尾巴高度，避免文字压到尾巴区域
        Column(
            modifier = Modifier.padding(
                start = 14.dp, end = 14.dp,
                top = 10.dp, bottom = (10.dp + 9.dp)
            )
        ) {
            if (scene.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = scene,
                        style = AppTypography.LabelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(3.dp))
            }
            Text(
                text = dialogue,
                style = AppTypography.BodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 台词气泡外形：圆角矩形主体 + 左下角指向模型的尖角尾巴。
 * 尾巴与主体经 Union 合并为单一路径，Surface 的填充与描边都沿合并后的轮廓绘制。
 */
private class SpeechBubbleShape(
    private val cornerRadius: Float,
    private val tailWidth: Float,
    private val tailHeight: Float,
    private val tailOffset: Float
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val bodyHeight = size.height - tailHeight
        val body = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(0f, 0f, size.width, bodyHeight),
                    cornerRadius = CornerRadius(cornerRadius)
                )
            )
        }
        // 尾巴顶边上移 0.5px 与主体略微重叠，避免接缝；尖角收在左下方指向模型
        val overlap = bodyHeight - 0.5f
        val tail = Path().apply {
            moveTo(tailOffset, overlap)
            lineTo(tailOffset + tailWidth, overlap)
            lineTo(tailOffset + 1.5f, size.height)
            close()
        }
        body.op(body, tail, PathOperation.Union)
        return Outline.Generic(body)
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

// ---------- 陪伴模式 ----------

/** 陪伴模式触发间隔档位（秒 → 标签） */
private val COMPANION_INTERVALS = listOf(
    30 to "30秒",
    60 to "1分钟",
    180 to "3分钟",
    300 to "5分钟",
    600 to "10分钟"
)

/** 读取设备电量（粘性广播，非注册式监听）：返回 百分比 to 是否充电中，失败 null */
private fun readBatteryStatus(context: android.content.Context): Pair<Int, Boolean>? =
    runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        if (level >= 0 && scale > 0) {
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            (level * 100 / scale).coerceIn(0, 100) to charging
        } else null
    }.getOrNull()

/**
 * 陪伴模式右下角锁屏风时钟角标：
 * 大号细体 12 小时制时间 + 基线小号 AM/PM → 通栏细白分隔线 →
 * 左侧电量（图标+百分比，充电显闪电、低电变红）右侧大写英文日期
 * （WEEKDAY / MON / D / YYYY）。无卡片底，白字+柔投影直接压在立绘上，
 * 与系统锁屏时钟同款气质。时间/日期每秒刷新，电量粘性广播 20 秒重读。
 */
@Composable
private fun CompanionHud(
    showClock: Boolean,
    showBattery: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var battery by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            nowMs = System.currentTimeMillis()
            if (tick % 20 == 0) battery = readBatteryStatus(context)
            tick++
            delay(1000)
        }
    }
    val timeFmt = remember { SimpleDateFormat("h:mm", Locale.US) }
    val ampmFmt = remember { SimpleDateFormat("a", Locale.US) }
    val dateFmt = remember { SimpleDateFormat("EEEE / MMM / d / yyyy", Locale.US) }
    val calendar = remember { Calendar.getInstance() }
    calendar.timeInMillis = nowMs

    val textShadow = Shadow(
        color = Color.Black.copy(alpha = 0.55f),
        offset = Offset(0f, 2f),
        blurRadius = 12f
    )
    val (pct, charging) = battery ?: (100 to false)
    val lowBattery = pct <= 20
    val infoColor = when {
        lowBattery -> MaterialTheme.colorScheme.error
        else -> Color.White
    }

    Column(modifier.width(252.dp)) {
        if (showClock) {
            // 大号细体时间 + 基线对齐的小号 AM/PM
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = timeFmt.format(calendar.time),
                    style = TextStyle(
                        fontSize = 54.sp,
                        lineHeight = 54.sp,
                        fontWeight = FontWeight.Light,
                        fontFeatureSettings = "tnum",
                        color = Color.White,
                        shadow = textShadow
                    )
                )
                Text(
                    text = ampmFmt.format(calendar.time).uppercase(Locale.US),
                    style = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Light,
                        color = Color.White,
                        shadow = textShadow
                    ),
                    modifier = Modifier.padding(start = 5.dp, bottom = 9.dp)
                )
            }
            // 通栏细白分隔线
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(1.dp))
            )
            // 电量（左）· 日期（右）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (showBattery) Arrangement.SpaceBetween else Arrangement.End
            ) {
                if (showBattery) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = if (charging) Icons.Rounded.Bolt else Icons.Rounded.BatteryStd,
                            contentDescription = if (charging) "充电中" else "电量",
                            tint = infoColor,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "$pct%",
                            style = TextStyle(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                fontFeatureSettings = "tnum",
                                color = infoColor,
                                shadow = textShadow
                            )
                        )
                    }
                }
                if (showClock) {
                    Text(
                        text = dateFmt.format(calendar.time).uppercase(Locale.US),
                        style = TextStyle(
                            fontSize = 10.sp,
                            letterSpacing = 0.8.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            shadow = textShadow
                        ),
                        maxLines = 1
                    )
                }
            }
        } else if (showBattery) {
            // 仅电量：左对齐单行
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = if (charging) Icons.Rounded.Bolt else Icons.Rounded.BatteryStd,
                    contentDescription = if (charging) "充电中" else "电量",
                    tint = infoColor,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "$pct%",
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        fontFeatureSettings = "tnum",
                        color = infoColor,
                        shadow = textShadow
                    )
                )
            }
        }
    }
}

/** 陪伴模式设置弹层：触发间隔档位 + 时钟/电量显示开关 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CompanionSettingsSheet(
    intervalSec: Int,
    showClock: Boolean,
    showBattery: Boolean,
    onIntervalChange: (Int) -> Unit,
    onClockChange: (Boolean) -> Unit,
    onBatteryChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.Screen.Horizontal)
                .padding(bottom = AppSpacing.Xl)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(AppSpacing.Xs))
                Text(
                    "陪伴模式",
                    style = AppTypography.TitleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(AppSpacing.Xs))
            Text(
                "待机动作自动循环，每到设定间隔随机播放一个其他动作并配上对应语音",
                style = AppTypography.CaptionSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = AppSpacing.Md)
            )
            Text(
                "动作触发间隔",
                style = AppTypography.LabelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = AppSpacing.Sm)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
                modifier = Modifier.padding(bottom = AppSpacing.Md)
            ) {
                COMPANION_INTERVALS.forEach { (sec, label) ->
                    val selected = sec == intervalSec
                    AssistChip(
                        onClick = { onIntervalChange(sec) },
                        label = { Text(label, style = AppTypography.LabelMedium) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.tertiaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                            labelColor = if (selected) MaterialTheme.colorScheme.onTertiaryContainer
                            else MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }
            CompanionSettingRow(
                icon = { Icon(Icons.Rounded.Schedule, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) },
                title = "显示时间与日期",
                subtitle = "右下角常驻时钟角标",
                checked = showClock,
                onCheckedChange = onClockChange
            )
            CompanionSettingRow(
                icon = { Icon(Icons.Rounded.BatteryStd, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) },
                title = "显示设备电量",
                subtitle = "电量偏低或充电时变色提示",
                checked = showBattery,
                onCheckedChange = onBatteryChange
            )
        }
    }
}

@Composable
private fun CompanionSettingRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(AppSpacing.Md))
        Column(Modifier.weight(1f)) {
            Text(title, style = AppTypography.BodyMedium)
            Text(
                subtitle,
                style = AppTypography.CaptionSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
