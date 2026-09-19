package com.azurlane.blyy.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyPanel
import com.azurlane.blyy.ui.components.BlyyPrimaryButton
import com.azurlane.blyy.ui.components.BlyySecondaryButton
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.components.WatermarkPickerRow
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.util.SaveResult
import com.azurlane.blyy.util.WatermarkComposer
import com.azurlane.blyy.util.WatermarkParams
import com.azurlane.blyy.viewmodel.WatermarkEditorViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 水印编辑页 — 拍照/图库图片进入后的预览与导出页。
 *
 * 交互模型（以水印为基准）：
 * - 预览区容器宽高比 = 相框宽高比（与合成输出画布一致），相框始终完整覆盖
 * - **单指拖动**照片调整其在相框内的位置（归一化偏移，[-1,1] 即边缘对齐）
 * - **双指捏合**缩放照片（[WatermarkParams.SCALE_MIN]~[WatermarkParams.SCALE_MAX]）
 * - 底部水印选择器切换/移除水印
 * - 顶栏：重新选图（Photo Picker）/ 重新拍照（返回相机页）
 *
 * 所见即所得：预览与最终合成共用 [WatermarkComposer.computePlacement]
 * 的同参数计算，导出效果与屏幕预览一致。
 */
@Composable
fun WatermarkEditorScreen(
    imageUri: Uri?,
    initialWatermarkId: String?,
    onBack: () -> Unit,
    onRetake: () -> Unit,
    viewModel: WatermarkEditorViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // 重新选图（Photo Picker，免存储权限、全版本兼容；取消则静默）
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.replaceSource(it) } }

    // Android 9 及以下：保存到系统图库需要写权限，请求授权后自动重试保存
    val writePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.save()
        } else {
            Toast.makeText(context, "未授予存储权限，无法保存到系统图库", Toast.LENGTH_SHORT).show()
        }
    }

    // 页面进入初始化（VM 内部幂等，配置变更/导航重放不会重置编辑状态）
    LaunchedEffect(imageUri, initialWatermarkId) {
        viewModel.initialize(imageUri, initialWatermarkId)
    }

    // 保存结果反馈
    LaunchedEffect(state.saveResult) {
        when (val result = state.saveResult) {
            is SaveResult.Success -> {
                snackbarHostState.showSnackbar(
                    message = "已保存到系统图库",
                    actionLabel = "完成"
                )
                viewModel.consumeSaveResult()
            }
            is SaveResult.NeedPermission -> {
                viewModel.consumeSaveResult()
                writePermissionLauncher.launch(result.permission)
            }
            is SaveResult.Error -> {
                snackbarHostState.showSnackbar(message = result.message)
                viewModel.consumeSaveResult()
            }
            null -> Unit
        }
    }

    AdaptiveScreenBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                BlyyTopBar(
                    title = "水印编辑",
                    subtitle = "单指拖动照片 · 双指缩放",
                    onBackClick = onBack,
                    actions = {
                        IconButton(onClick = {
                            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) {
                            Icon(
                                Icons.Rounded.Image, "重新选择图片",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = onRetake) {
                            Icon(
                                Icons.Rounded.PhotoCamera, "重新拍照",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                )

                // ── 预览区 ──
                EditorPreviewArea(
                    state = state,
                    viewModel = viewModel,
                    modifier = Modifier.weight(1f)
                )

                // ── 底部面板：水印选择 + 保存 ──
                EditorBottomPanel(
                    state = state,
                    onPickImage = {
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onSelectWatermark = viewModel::selectWatermark,
                    onSave = viewModel::save
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

// ==================== 预览区 ====================

@Composable
private fun EditorPreviewArea(
    state: WatermarkEditorViewModel.EditorUiState,
    viewModel: WatermarkEditorViewModel,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md)
    ) {
        when {
            state.isLoading || (state.sourceUri == null && !state.sourceError) -> Box(
                Modifier.fillMaxSize(), contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }

            state.sourceError || !state.hasSource -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Rounded.BrokenImage, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(Modifier.height(AppSpacing.Md))
                Text(
                    "图片读取失败或已失效",
                    style = AppTypography.BodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> WatermarkPreview(
                state = state,
                viewModel = viewModel,
                maxW = maxWidth,
                maxH = maxHeight,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/**
 * 照片 + 相框实时预览。
 * 以水印为基准：容器宽高比 = 相框宽高比（与合成输出画布一致），照片在容器内摆放，
 * 与合成共用 [WatermarkComposer.computePlacement] 布局算法。
 */
@Composable
private fun WatermarkPreview(
    state: WatermarkEditorViewModel.EditorUiState,
    viewModel: WatermarkEditorViewModel,
    maxW: androidx.compose.ui.unit.Dp,
    maxH: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val entry = state.selectedEntry

    // 单一数据源：预览直接渲染 ViewModel 持有的已解码位图（与合成同一对象），
    // 不再经 Coil 二次解码 —— 排除"预览/保存解码差异"导致的效果不一致
    val sourceBitmap = state.sourceBitmap
    val sourceImage = sourceBitmap?.let { remember(it) { it.asImageBitmap() } }

    val imgW = state.sourceWidth.toFloat()
    val imgH = state.sourceHeight.toFloat()
    val hasFrame = entry != null && imgW > 0f && imgH > 0f

    // 容器宽高比：有相框+照片时 = 合成输出画布比例（与合成共用 outputCanvasSize，
    // 含 roundToInt 取整后比例严格一致 → 不同水印下预览与保存照片位置零偏移）；
    // 仅相框无照片时 = 相框 PNG 精确比例；无相框时 = 原图宽高比
    val containerAspect = when {
        entry != null && entry.width > 0 && entry.height > 0 &&
            imgW > 0f && imgH > 0f -> {
            val (ow, oh) = WatermarkComposer.outputCanvasSize(
                entry.width, entry.height, imgW.toInt(), imgH.toInt()
            )
            ow.toFloat() / oh.coerceAtLeast(1)
        }
        entry != null && entry.width > 0 && entry.height > 0 ->
            entry.width.toFloat() / entry.height
        imgW > 0f && imgH > 0f -> imgW / imgH
        else -> 1f
    }
    val maxWpx = with(density) { maxW.toPx() }
    val maxHpx = with(density) { maxH.toPx() }
    val dispWpx: Float
    val dispHpx: Float
    if (maxWpx / maxHpx > containerAspect) {
        dispHpx = maxHpx
        dispWpx = maxHpx * containerAspect
    } else {
        dispWpx = maxWpx
        dispHpx = maxWpx / containerAspect
    }

    // 照片在容器内的摆放（与合成时容器 = 输出画布完全同参，所见即所得）
    val placement = remember(entry, state.params, dispWpx, dispHpx, imgW, imgH) {
        if (hasFrame) {
            WatermarkComposer.computePlacement(dispWpx, dispHpx, imgW, imgH, state.params)
        } else {
            null // 无水印：照片直接铺满预览区
        }
    }

    // 手势回调读取最新状态，避免 pointerInput 重启打断进行中的手势
    val gestureInput by rememberUpdatedState(
        Triple(hasFrame, state.params, dispWpx to dispHpx)
    )
    val imgSizeInput by rememberUpdatedState(imgW to imgH)
    val onGesture by rememberUpdatedState(viewModel::applyGestureTransform)

    Box(
        modifier = modifier
            .size(with(density) { dispWpx.toDp() }, with(density) { dispHpx.toDp() })
            .clip(RoundedCornerShape(AppSpacing.Corner.Md))
            .background(Color.Black)
            .border(
                AppSpacing.Border.Thin,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                RoundedCornerShape(AppSpacing.Corner.Md)
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    // 手势开始：从最新状态播种本地累积器。手势期间每个事件都基于
                    // 本地值推进，不依赖重组回写 —— 修复"高刷屏一帧内多个事件读到
                    // 过期 scale，缩放增量相互覆盖 / 偏移重复累加"。
                    val (frame, params0, dims) = gestureInput
                    if (!frame) return@awaitEachGesture
                    val (cw, ch) = dims
                    val (iw, ih) = imgSizeInput

                    var scale = params0.scale
                    var ox = params0.offsetX
                    var oy = params0.offsetY
                    var prevAnchor: Offset? = null
                    var prevSpan = 0f
                    var prevCount = -1

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }

                        // 锚点 = 按下指针的质心（单指即手指位置，双指为捏合中点）；
                        // 跨度 = 指针间最大距离（双指捏合间距，单指为 0）
                        var ax = 0f
                        var ay = 0f
                        pressed.forEach { ax += it.position.x; ay += it.position.y }
                        val anchor = if (pressed.isEmpty()) null
                        else Offset(ax / pressed.size, ay / pressed.size)
                        var span = 0f
                        if (pressed.size >= 2) {
                            for (i in pressed.indices) {
                                for (j in i + 1 until pressed.size) {
                                    val d = (pressed[i].position - pressed[j].position).getDistance()
                                    if (d > span) span = d
                                }
                            }
                        }

                        val prevA = prevAnchor
                        val countChanged = pressed.size != prevCount
                        // 指针数量变化（1↔2 指切换）或首事件：只记录锚点/跨度、跳过本次
                        // 变换，避免切换瞬间质心跳变导致照片跳动
                        if (prevA != null && anchor != null && !countChanged) {
                            // 双指跨度变化 → 缩放；跨度不变/单指 → 纯拖动
                            val zoomChange = if (prevSpan > 0f && span > 0f) span / prevSpan else 1f
                            val newScale = (scale * zoomChange).coerceIn(
                                WatermarkParams.SCALE_MIN, WatermarkParams.SCALE_MAX
                            )
                            // 有效缩放倍率 z：被 min/max 夹住时 ≈1（缩放意图未实现）
                            val z = if (scale > 0f) newScale / scale else 1f
                            // 夹住检测用 epsilon：浮点误差下"想缩放但被 clamp"（例如
                            // scale=7.999999 时新值被夹到 8.0）也能被识别 → 跳过整个
                            // 变换，杜绝"产生坐标偏移却不放大"
                            val clamped = zoomChange != 1f && abs(z - 1f) < 1e-4f
                            if (!clamped) {
                                val p = WatermarkComposer.computePlacement(
                                    cw, ch, iw, ih, WatermarkParams(scale, ox, oy)
                                )
                                // 锚点锚定变换：照片上位于"上一锚点"的内容移动到当前
                                // 锚点并按 z 缩放 —— 捏合焦点内容不漂移，单指拖动
                                // （z=1）即照片跟随手指
                                val left2 = anchor.x + z * (p.left - prevA.x)
                                val top2 = anchor.y + z * (p.top - prevA.y)
                                val w2 = p.width * z
                                val h2 = p.height * z
                                scale = newScale
                                // 物理位置 clamp（拖动手感最大化）：直接把照片中心
                                // 限制在"照片与画布至少相切/交集"的边界内 —— 按
                                // scale 放宽归一化偏移不够（scale 略>1 时照片只比
                                // 画布大一点，(画布−照片)/2 极小，拖动几乎无感）。
                                // 约束后转回归一化偏移，±1 = 边缘对齐，超出表示
                                // 照片边缘进入画布内部（露出背景）
                                val cxClamped = WatermarkComposer.clampPhotoCenter(
                                    left2 + w2 / 2f, cw, w2
                                )
                                val cyClamped = WatermarkComposer.clampPhotoCenter(
                                    top2 + h2 / 2f, ch, h2
                                )
                                val dX = cw - w2
                                val dY = ch - h2
                                if (abs(dX) > 0.5f) {
                                    ox = ((cxClamped - cw / 2f) / (dX / 2f))
                                        .coerceIn(
                                            -WatermarkComposer.OFFSET_SANITY_LIMIT,
                                            WatermarkComposer.OFFSET_SANITY_LIMIT
                                        )
                                }
                                if (abs(dY) > 0.5f) {
                                    oy = ((cyClamped - ch / 2f) / (dY / 2f))
                                        .coerceIn(
                                            -WatermarkComposer.OFFSET_SANITY_LIMIT,
                                            WatermarkComposer.OFFSET_SANITY_LIMIT
                                        )
                                }
                                onGesture(scale, ox, oy)
                            }
                        }
                        prevAnchor = anchor
                        prevSpan = span
                        prevCount = pressed.size

                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                        if (pressed.isEmpty()) break
                    }
                }
            }
    ) {
        // 照片：Canvas 直接绘制到摆放矩形（round 取整 + 拉伸到矩形），
        // 与合成端 scaleHighQuality + drawBitmap 完全同语义 —— 预览所见即保存所得。
        // 渲染的是与合成同一对象的已解码位图；超出容器的部分由外层 clip 裁剪
        if (sourceImage != null) {
            if (placement != null) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawImage(
                        image = sourceImage,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(sourceImage.width, sourceImage.height),
                        dstOffset = IntOffset(
                            placement.left.roundToInt(),
                            placement.top.roundToInt()
                        ),
                        dstSize = IntSize(
                            placement.width.roundToInt(),
                            placement.height.roundToInt()
                        ),
                        filterQuality = FilterQuality.High
                    )
                }
            } else {
                // 无水印：照片铺满预览区（合成侧导出原图）
                Image(
                    bitmap = sourceImage,
                    contentDescription = "待编辑照片",
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 三分构图辅助线只服务编辑，不进入导出画布。它让拖动照片时有明确的
        // 对齐参照，尤其适合票根、拍立得等边缘装饰较重的相框。
        if (entry != null) {
            Canvas(modifier = Modifier.fillMaxSize()) { drawRuleOfThirds() }
        }

        // 相框：覆盖整个容器（与合成同参：不透明度/混合模式处理已随旧参数面板移除）
        if (entry != null) {
            // 按预览尺寸解码（inSampleSize 降采样，不加载全分辨率位图）
            val previewLongSide = kotlin.math.ceil(maxOf(dispWpx, dispHpx)).toInt()
            var wmBitmap by remember(entry) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(entry, previewLongSide) {
                wmBitmap = WatermarkComposer.loadWatermark(context, entry, previewLongSide)
            }
            wmBitmap?.let { bmp ->
                val image = remember(bmp) { bmp.asImageBitmap() }
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawImage(
                        image = image,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(image.width, image.height),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                        filterQuality = FilterQuality.High
                    )
                }
            }
        }
    }
}

/** 三分构图辅助线（仅编辑预览，不参与导出） */
private fun DrawScope.drawRuleOfThirds() {
    val guide = Color.White.copy(alpha = 0.18f)
    val stroke = 1.dp.toPx()
    drawLine(guide, Offset(size.width / 3f, 0f), Offset(size.width / 3f, size.height), stroke)
    drawLine(guide, Offset(size.width * 2f / 3f, 0f), Offset(size.width * 2f / 3f, size.height), stroke)
    drawLine(guide, Offset(0f, size.height / 3f), Offset(size.width, size.height / 3f), stroke)
    drawLine(guide, Offset(0f, size.height * 2f / 3f), Offset(size.width, size.height * 2f / 3f), stroke)
}

// ==================== 底部面板 ====================

@Composable
private fun EditorBottomPanel(
    state: WatermarkEditorViewModel.EditorUiState,
    onPickImage: () -> Unit,
    onSelectWatermark: (String?) -> Unit,
    onSave: () -> Unit
) {
    BlyyPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AppSpacing.Md)
                .navigationBarsPadding()
        ) {
            // ── 水印选择器 ──
            WatermarkPickerRow(
                watermarks = state.watermarks,
                selectedId = state.selectedWatermarkId,
                onSelect = onSelectWatermark,
                thumbSize = 60.dp
            )

            // ── 保存 ──
            Spacer(Modifier.height(AppSpacing.Xs))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Lg),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
            ) {
                BlyySecondaryButton(
                    text = "换图片",
                    onClick = onPickImage,
                    enabled = !state.isSaving,
                    modifier = Modifier.weight(1f)
                )
                BlyyPrimaryButton(
                    text = if (state.isSaving) "保存中…" else "保存到相册",
                    icon = Icons.Rounded.SaveAlt,
                    onClick = onSave,
                    enabled = !state.isSaving && state.hasSource && !state.isLoading,
                    modifier = Modifier.weight(2f)
                )
            }
        }
    }
}
