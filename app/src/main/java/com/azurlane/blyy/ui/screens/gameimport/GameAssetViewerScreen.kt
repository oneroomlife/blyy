package com.azurlane.blyy.ui.screens.gameimport

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(AppColors.Live2DViewer.BgDark, Color(0xFF101A2C))))
    ) {
        BlyyTopBar(
            title = asset?.let { viewModel.displayName(it) } ?: "游戏资源",
            subtitle = asset?.let { subtitleOf(it) } ?: state.error,
            onBackClick = onBack,
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
                            modifier = Modifier.fillMaxSize(),
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
                            resetSignal = resetSignal
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
