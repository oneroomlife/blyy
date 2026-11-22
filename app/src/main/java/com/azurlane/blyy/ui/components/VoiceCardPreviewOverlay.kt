package com.azurlane.blyy.ui.components

import android.graphics.Color as AndroidColor
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import java.io.File

/**
 * 台词语音卡预览（页内浮层，不是 Dialog）。
 *
 * 为什么用浮层而不是 `Dialog`：
 * - 卡片本体是 WebView。放在独立窗口里，这个 ROM 上出现过「弹窗出现 1 秒就被系统关掉、
 *   且 `onDismissRequest` 被反复回调」的现象（窗口生命周期和宿主重组打架）；
 * - 页内浮层没有第二个 Window，WebView 直接从属 Activity，页面内音频正常播放，
 *   `BackHandler` 又能精确接管返回键，行为比 Dialog 可预测得多。
 *
 * 同时这也更符合产品预期：卡片是「当前这句台词的一张实物」，在页面之上"拿起来看"，
 * 而不是跳进另一个界面。
 */
@Composable
fun VoiceCardPreviewOverlay(
    htmlFile: File,
    hasAudio: Boolean,
    busy: Boolean = false,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit
) {
    BackHandler(enabled = true, onBack = { if (!busy) onDismiss() })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF03070D).copy(alpha = 0.80f))
            // 浮层本身吃掉点击：点空白处收起；下方列表不再响应
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { if (!busy) onDismiss() }
            )
            .systemBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Md)
                // 面板内部点击不再冒泡到遮罩，避免"想拖进度条却把卡片关了"
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { },
            shape = RoundedCornerShape(AppSpacing.Corner.Xxl),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            shadowElevation = AppSpacing.Elevation.Xl
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                PreviewHeader(hasAudio = hasAudio, onDismiss = { if (!busy) onDismiss() })

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.Md)
                        .clip(RoundedCornerShape(AppSpacing.Corner.Lg))
                        .background(Color(0xFF050B16))
                ) {
                    if (remember { isWebViewAvailable() }) {
                        // key：换了另一句台词的卡片时整体重建 WebView。
                        // AndroidView 的 factory 只跑一次，不换 key 会一直显示上一张卡（旧实现的 bug）
                        key(htmlFile.absolutePath) {
                            HtmlCardWebView(htmlFile = htmlFile)
                        }
                    } else {
                        // 裁剪过的 ROM / 手动停用 WebView 时不能直接崩：卡片文件本身是好的，
                        // 保存和分享两条按钮仍然可用
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "系统 WebView 不可用，无法预览\n卡片仍可保存或分享",
                                style = AppTypography.BodyMedium,
                                color = Color(0xFF93A9C4),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(AppSpacing.Lg)
                            )
                        }
                    }
                }

                PreviewActions(busy = busy, onSave = onSave, onShare = onShare)
            }
        }
    }
}

@Composable
private fun PreviewHeader(hasAudio: Boolean, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.Lg, end = AppSpacing.Sm, top = AppSpacing.Sm, bottom = AppSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.GraphicEq,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(AppSpacing.Icon.Md)
        )
        Spacer(modifier = Modifier.width(AppSpacing.Sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "台词语音卡",
                style = AppTypography.TitleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (hasAudio) "语音取自原站 · 点播放试听（需联网）" else "该台词暂无语音 · 卡片信息仍可分享",
                style = AppTypography.LabelSmall,
                color = if (hasAudio) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "关闭预览",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PreviewActions(busy: Boolean, onSave: () -> Unit, onShare: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = onSave,
            enabled = !busy,
            shape = RoundedCornerShape(AppSpacing.Corner.Md),
            modifier = Modifier
                .weight(1f)
                .height(46.dp)
        ) {
            if (busy) {
                // 在途态给一个 16dp 的转圈，避免用户以为"点了没反应"再点一次
                CircularProgressIndicator(
                    modifier = Modifier.size(AppSpacing.Icon.Sm),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.SaveAlt,
                    contentDescription = null,
                    modifier = Modifier.size(AppSpacing.Icon.Sm)
                )
            }
            Spacer(modifier = Modifier.width(AppSpacing.Xs))
            Text(text = if (busy) "处理中…" else "保存", style = AppTypography.LabelLarge)
        }
        Button(
            onClick = onShare,
            enabled = !busy,
            shape = RoundedCornerShape(AppSpacing.Corner.Md),
            modifier = Modifier
                .weight(1f)
                .height(46.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Icon(
                imageVector = Icons.Rounded.Share,
                contentDescription = null,
                modifier = Modifier.size(AppSpacing.Icon.Sm)
            )
            Spacer(modifier = Modifier.width(AppSpacing.Xs))
            Text(
                text = "分享卡片",
                style = AppTypography.LabelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * 系统里有没有可用的 WebView 提供方。
 *
 * Android 8+ 提供查询 API；更老版本没有这个接口，直接按"有"处理。查询本身也可能抛
 * SecurityException，兜底成 true——探测只用来决定要不要降级，不该反过来挡住预览。
 */
private fun isWebViewAvailable(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
    return runCatching { WebView.getCurrentWebViewPackage() != null }.getOrDefault(true)
}

/**
 * 承载卡片的 WebView。
 *
 * 关键设置：
 * - `javaScriptEnabled`：播放按钮/进度条逻辑全靠页面内联脚本；
 * - `allowFileAccess`：targetSdk 30+ 默认关闭，不显式打开就加载不了 `file://` 卡片；
 * - `mediaPlaybackRequiresUserGesture = false`：让"点一下按钮"的播放不受自动播放策略二次拦截。
 *
 * `file://` 页面 + 远程 `https` 音频属于跨源，浏览器会拦 XHR 但不拦 `<audio src>` 直接播放，
 * 所以"语音走原站链接"这条路在 WebView 里是通的（真机已验证）。
 */
@Composable
private fun HtmlCardWebView(htmlFile: File) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var webRef by remember { mutableStateOf<WebView?>(null) }

    // 页面切后台时必须停掉卡片里的音频：预览浮层没有自己的 Window，
    // WebView 的 media 元素不会跟着 Activity 暂停，用户切到微信还能听到半句台词
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> webRef?.let { web ->
                    runCatching { web.evaluateJavascript("(function(){try{document.getElementById('audio').pause();}catch(e){}})()", null) }
                    web.onPause()
                }
                Lifecycle.Event.ON_START -> webRef?.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            webRef = null
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.parseColor("#050B16"))
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    allowContentAccess = false
                    loadsImagesAutomatically = true
                    mediaPlaybackRequiresUserGesture = false
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    // file:// 页面不许反过来读别的 file:// 资源（卡片用不到，收紧攻击面）
                    allowFileAccessFromFileURLs = false
                    allowUniversalAccessFromFileURLs = false
                }
                webChromeClient = WebChromeClient()
                loadUrl("file://" + htmlFile.absolutePath)
                webRef = this
            }
        },
        onRelease = { web ->
            if (webRef === web) webRef = null
            // 先摘出视图树再 destroy：部分 ROM 上"边挂着边 destroy"会留下一个黑帧甚至崩 native
            runCatching {
                (web.parent as? ViewGroup)?.removeView(web)
                web.stopLoading()
                web.loadUrl("about:blank")
                web.destroy()
            }
        }
    )
}
