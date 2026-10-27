package com.azurlane.blyy.ui.screens.gameimport

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurlane.blyy.ui.components.BlyyButton
import com.azurlane.blyy.ui.components.BlyyButtonVariant
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.components.SpineSdController
import com.azurlane.blyy.ui.components.SpineSdView
import com.azurlane.blyy.ui.components.ZoomableImage
import com.azurlane.blyy.ui.theme.AppColors
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.util.gameimport.GameAssetInfo
import com.azurlane.blyy.viewmodel.GameAssetViewerViewModel
import java.util.Locale

/**
 * 游戏资源查看器：Spine 小人 / 动态皮肤（GL 实时渲染）与静态立绘（可缩放图片）。
 *
 * 布局刻意**不做悬浮覆盖**：`SpineSdView` 内部是 `setZOrderOnTop(true)` 的
 * GLSurfaceView，会把同窗口的 Compose 内容压在下面。因此顶栏与操作面板都放在
 * GL 视图之外的 Column 里，避免「按钮被小人挡住 / 点不到」。
 *
 * 交互：
 * - Spine：点击模型播放随机动作；底部可切换骨骼、拖动缩放、点动作名直接播放
 * - 立绘：双指缩放 / 双击还原
 */
@Composable
fun GameAssetViewerScreen(onBack: () -> Unit) {
    val viewModel: GameAssetViewerViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val controller = remember { SpineSdController() }
    val asset = state.asset

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
                if (asset?.isSpine == true) {
                    IconButton(onClick = { controller.random() }) {
                        Icon(
                            Icons.Rounded.Animation,
                            contentDescription = "随机动作",
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

                asset.isPainting -> {
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
                    val skeleton = state.skeleton ?: asset.primarySkeleton
                    if (skeleton != null) {
                        SpineSdView(
                            dirPath = asset.dir.absolutePath,
                            assetName = skeleton,
                            modifier = Modifier.fillMaxSize(),
                            scaleMultiplier = state.scale,
                            onLoaded = viewModel::onAnimationsLoaded,
                            controller = controller
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

        if (asset?.isSpine == true && state.skeleton != null) {
            SpineControls(
                state = state,
                onScaleChange = viewModel::setScale,
                onSkeletonSelected = viewModel::selectSkeleton,
                onAnimationSelected = { controller.play(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm)
            )
        }
    }
}

private fun subtitleOf(asset: GameAssetInfo): String = buildString {
    append(asset.kind.label)
    asset.versionLabel?.let { append(" · ").append(it) }
    when {
        asset.isSpine -> {
            append(" · ").append(asset.skeletons.size).append(" 套骨骼")
            if (asset.pageCount > 0) append(" · ").append(asset.pageCount).append(" 页图集")
        }
        asset.isPainting -> {
            if (asset.imageWidth > 0) {
                append(" · ").append(asset.imageWidth).append("×").append(asset.imageHeight)
            }
        }
    }
}

@Composable
private fun SpineControls(
    state: GameAssetViewerViewModel.State,
    onScaleChange: (Float) -> Unit,
    onSkeletonSelected: (String) -> Unit,
    onAnimationSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(AppSpacing.Corner.Lg),
        color = Color(0xCC0F1826),
        border = BorderStroke(AppSpacing.Border.Thin, Color.White.copy(alpha = 0.16f))
    ) {
        Column(
            Modifier.padding(AppSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
        ) {
            val asset = state.asset
            if (asset != null && asset.skeletons.size > 1) {
                Text(
                    "骨骼",
                    style = AppTypography.LabelSmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                    items(asset.skeletons) { name ->
                        FilterChip(
                            selected = name == state.skeleton,
                            onClick = { onSkeletonSelected(name) },
                            label = { Text(name, style = AppTypography.LabelSmall) }
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(AppSpacing.Sm))
                // 缩放条独占剩余宽度：右侧再塞按钮会把「随机动作」挤出屏幕
                Slider(
                    value = state.scale,
                    onValueChange = onScaleChange,
                    valueRange = 0.4f..2f,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = AppSpacing.Sm)
                )
                Text(
                    "${String.format(Locale.US, "%.2f", state.scale)}×",
                    style = AppTypography.CaptionSmall,
                    color = Color.White.copy(alpha = 0.75f)
                )
            }

            if (state.animations.isNotEmpty()) {
                Text(
                    "动作 ${state.animations.size} 个（点击播放，点模型也可随机播放）",
                    style = AppTypography.LabelSmall,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                    items(state.animations) { name ->
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(AppSpacing.Corner.Full))
                                .clickable { onAnimationSelected(name) },
                            shape = RoundedCornerShape(AppSpacing.Corner.Full),
                            color = Color.White.copy(alpha = 0.08f),
                            border = BorderStroke(
                                AppSpacing.Border.Thin,
                                Color.White.copy(alpha = 0.14f)
                            )
                        ) {
                            Text(
                                name,
                                style = AppTypography.LabelSmall,
                                color = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}
