package com.azurlane.blyy.ui.screens.gameimport

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.components.SpineSdView
import com.azurlane.blyy.ui.components.ZoomableImage
import com.azurlane.blyy.ui.theme.AppColors
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.util.gameimport.GameAssetInfo
import com.azurlane.blyy.viewmodel.GameAssetViewerViewModel
import android.widget.Toast
import kotlinx.coroutines.launch

/**
 * 查看器退场时长（ms）。
 *
 * 只淡出不位移：Compose 内容与 GL 顶点 alpha 同步从 1 到 0，播完才 pop，pop 本身只剩一次
 * 极短淡出。取值略短于 [com.azurlane.blyy.ui.theme.AppAnimation.Duration.Fast]，
 * 让「返回」在观感上跟手，不像原来的 350ms 位移那样拖沓。
 */
private const val ViewerExitMillis = 160

/**
 * 进场淡入时长。
 *
 * 路由转场只淡入 Compose 内容；GL 图层不参与转场，会瞬间整块出现。
 * 用同一套 alpha 补一段淡入，两边观感才一致。
 */
private const val ViewerEnterMillis = 150

/**
 * 游戏资源查看器：Spine 小人 / 动态皮肤（GL 实时渲染）与静态立绘（可缩放图片）。
 *
 * 设计取舍：
 * - **不再有悬浮控制面板**：`SpineSdView` 内部是 `setZOrderOnTop(true)` 的
 *   GLSurfaceView，会盖住同窗口的 Compose 内容，悬浮按钮既难点也容易遮挡模型。
 * - **手势即控制**：单指拖动平移、双指缩放、双击复位；顶栏另有「适应屏幕」。
 * - 顶栏在 GL 视图之外（Column 布局），避免被 Surface 压住。
 */
@Composable
fun GameAssetViewerScreen(onBack: () -> Unit) {
    val viewModel: GameAssetViewerViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val asset = state.asset

    // 复位信号：递增即让渲染视图把平移/缩放归零（不持有 View 引用）
    var resetSignal by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    // 导出结果用 Toast 反馈（不打断全屏看图）
    LaunchedEffect(state.toast) {
        state.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.consumeToast()
        }
    }

    // ── 退场编排 ────────────────────────────────────────────────────────────────
    // 本页内容里有 setZOrderOnTop(true) 的 GLSurfaceView：它的 Surface 是 SurfaceFlinger 里的
    // 独立图层，既不吃 Compose 的 alpha/transform，也不跟随 NavHost 的转场动画移动，只能在
    // onDispose 里被动撤掉 —— 于是页面已经滑走、模型还留在屏幕上（「资源慢一步退出」）。
    //
    // 所以退场改由本页驱动：先把 Compose 内容（顶栏 / 图片）淡出，同时把同一个进度作为
    // 顶点 alpha 喂给 GL 内容，两条渲染路径一起归零后再 pop；pop 时路由侧只保留一次极短
    // 淡出（见 AppRoot 的 viewerExitTransition），不再做位移动画，彻底避免双重动画。
    val contentAlpha = remember { Animatable(0f) }
    val exitScope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }

    // 进场淡入：GL/content 两条渲染路径都从 0 起，避免模型在转场里「啪」地出现
    LaunchedEffect(Unit) {
        contentAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(ViewerEnterMillis, easing = FastOutLinearInEasing)
        )
    }

    val requestExit: () -> Unit = {
        if (!closing) {
            closing = true
            exitScope.launch {
                contentAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(ViewerExitMillis, easing = FastOutLinearInEasing)
                )
                onBack()
            }
        }
    }

    // 系统返回键与顶栏返回按钮走同一条退场路径（此前没有 BackHandler：返回键直接 pop，
    // 完全没有淡出，GL 图层只能等 onDispose 才消失）
    BackHandler { requestExit() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(AppColors.Live2DViewer.BgDark, Color(0xFF101A2C))))
    ) {
        BlyyTopBar(
            title = asset?.let { viewModel.displayName(it) } ?: "游戏资源",
            modifier = Modifier.graphicsLayer { alpha = contentAlpha.value },
            subtitle = asset?.let { subtitleOf(it) } ?: state.error,
            onBackClick = requestExit,
            actions = {
                // 图片型资源（静态立绘 / 过场图）：导出到 Downloads/BLYY
                if (asset?.isImage == true) {
                    IconButton(
                        onClick = { viewModel.export(asset) },
                        enabled = !state.exporting
                    ) {
                        if (state.exporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                Icons.Rounded.SaveAlt,
                                contentDescription = "导出到相册下载目录",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    }
                }
                if (asset?.isSpine == true) {
                    IconButton(onClick = { resetSignal++ }) {
                        Icon(
                            Icons.Rounded.FitScreen,
                            contentDescription = "适应屏幕",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
            }
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            when {
                state.loading -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)

                asset == null -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(AppSpacing.Xl)
                ) {
                    Icon(
                        Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(42.dp)
                    )
                    Spacer(Modifier.height(AppSpacing.Md))
                    Text(
                        state.error ?: "资源不可用",
                        style = AppTypography.BodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )
                }

                asset.isImage -> {
                    val preview = asset.previewFile
                    if (preview != null) {
                        ZoomableImage(
                            model = preview,
                            contentDescription = viewModel.displayName(asset),
                            modifier = Modifier
                                .fillMaxSize()
                                // 图片是窗口内绘制的，可以直接用图层 alpha 参与退场淡出；
                                // 注意不能把 alpha 加在 AndroidView 的祖先上（会破坏 Surface 合成）
                                .graphicsLayer { alpha = contentAlpha.value },
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text(
                            "立绘文件缺失",
                            style = AppTypography.BodyMedium,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }

                asset.isSpine -> {
                    val skeleton = state.skeleton
                    if (skeleton != null) {
                        SpineSdView(
                            dirPath = asset.dir.absolutePath,
                            assetName = skeleton,
                            modifier = Modifier.fillMaxSize(),
                            panZoomEnabled = true,
                            resetSignal = resetSignal,
                            // GL 内容不参与 Compose 图层：只能靠 Skeleton.color.a 淡出
                            contentAlpha = { contentAlpha.value }
                        )
                    } else {
                        Text(
                            "骨骼数据缺失",
                            style = AppTypography.BodyMedium,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }

                else -> Text(
                    "该资源请在 Live2D 皮肤库中查看",
                    style = AppTypography.BodyMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }
    }
}

/** 顶栏副标题：类型 + 版本 + 手势提示（替代原来的底部控制面板） */
private fun subtitleOf(asset: GameAssetInfo): String = buildString {
    append(asset.kind.label)
    asset.versionLabel?.let { append(" · ").append(it) }
    when {
        asset.isSpine -> {
            append(" · ").append(asset.pageCount).append(" 页图集 · 拖动/双指缩放/双击复位")
        }
        asset.isLoadingBg -> {
            if (asset.imageWidth > 0) {
                append(" · ").append(asset.imageWidth).append("×").append(asset.imageHeight)
            }
            append(" · 可导出到下载目录")
        }
        asset.isPainting -> {
            if (asset.imageWidth > 0) {
                append(" · ").append(asset.imageWidth).append("×").append(asset.imageHeight)
            }
            // 立绘不是「一张图」：游戏里是碎片图集 + 网格 + 图层布局拼出来的。
            // 导入时已按同样方式还原，这里把还原方式如实写在副标题里。
            when (asset.paintingMode) {
                "layout" -> {
                    val layers = asset.paintingLayers
                    if (layers > 0) append(" · ").append(layers).append(" 层合成")
                }
                "mesh" -> append(" · 网格还原")
            }
            // 素材不全时如实说明：这类资源在游戏内也是「按需下载」的，
            // 用户需要先在游戏里打开一次，再回来重新导入。
            asset.paintingWarning?.let { append(" · ⚠ ").append(it) }
        }
    }
    asset.paintingWarning?.takeIf { asset.isLoadingBg }?.let { append(" · ⚠ ").append(it) }
}
