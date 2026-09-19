package com.azurlane.blyy.ui.screens

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurlane.blyy.ui.components.BlyyPrimaryButton
import com.azurlane.blyy.ui.components.WatermarkAssetImage
import com.azurlane.blyy.ui.components.WatermarkPickerRow
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.util.WatermarkAssets
import com.azurlane.blyy.viewmodel.WatermarkCameraViewModel
import java.io.File

/**
 * 水印相机页 — 拍摄入口 + 水印预选。
 *
 * 拍照委托给**系统默认相机**（ACTION_IMAGE_CAPTURE + FileProvider）：
 * - 无需 CAMERA 运行时权限、无需 CameraX 依赖，用户使用熟悉的原厂相机取景
 * - 拍摄结果写入应用 cache 临时目录，返回后携带预选水印进入编辑页精修
 * - 设备无相机 / 无相机应用时引导走图库流程，功能不残废
 *
 * 页面状态机：
 * 1. 可用性检测完成前 loading（避免引导页误闪）
 * 2. 设备无相机 → 引导改用图库
 * 3. 正常 → 相框预览 + 快门（拉起系统相机）/ 图库 / 水印切换
 */
@Composable
fun WatermarkCameraScreen(
    onBack: () -> Unit,
    onEditImage: (imageUri: Uri, watermarkId: String?) -> Unit,
    viewModel: WatermarkCameraViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current

    // ---------- 系统图库选择（Photo Picker，全版本兼容、免存储权限） ----------
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        // 用户取消选择 → 静默返回
        uri?.let { onEditImage(it, state.selectedWatermarkId) }
    }

    // ---------- 系统相机拍照 ----------
    // pending 记录本次拍摄的目标文件与预选水印，返回时据此路由（避免闭包读到旧 state）
    var pendingFile by remember { mutableStateOf<File?>(null) }
    var pendingWatermarkId by remember { mutableStateOf<String?>(null) }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val file = pendingFile
        val watermarkId = pendingWatermarkId
        pendingFile = null
        pendingWatermarkId = null
        when {
            // 用户在系统相机里取消 → 静默清理临时文件，不打扰
            !success -> {
                file?.let { runCatching { if (it.exists()) it.delete() } }
                viewModel.onCaptureSuccess() // 仅复位拍摄中状态
            }
            // 成功但文件为空（部分 ROM 异常）→ 提示重试
            file == null || !file.exists() || file.length() <= 0L ->
                viewModel.onCaptureError(file, "拍照失败，请重试")
            else -> {
                viewModel.onCaptureSuccess()
                onEditImage(Uri.fromFile(file), watermarkId)
            }
        }
    }

    /** 拉起系统默认相机；无相机应用可处理时降级提示 */
    fun launchSystemCamera() {
        val file = viewModel.createCaptureFile()
        pendingFile = file
        pendingWatermarkId = state.selectedWatermarkId
        viewModel.setCapturing(true)
        try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            takePicture.launch(uri)
        } catch (e: android.content.ActivityNotFoundException) {
            pendingFile = null
            pendingWatermarkId = null
            viewModel.onCaptureError(file, "未找到系统相机应用，可从图库选择照片")
        } catch (e: Exception) {
            pendingFile = null
            pendingWatermarkId = null
            viewModel.onCaptureError(file, "无法启动相机：${e.message ?: "未知错误"}")
        }
    }

    // ---------- 一次性消息提示 ----------
    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when {
            // 可用性检测完成前统一 loading，避免引导页误闪
            !state.cameraChecked -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }

            !state.cameraAvailable -> CameraUnavailableGuide(
                onPickFromGallery = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onBack = onBack
            )
            else -> CameraContent(
                state = state,
                haptic = haptic,
                onBack = onBack,
                onShutter = ::launchSystemCamera,
                onSelectWatermark = { viewModel.selectWatermark(it) },
                onPickFromGallery = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            )
        }
    }
}

// ==================== 相机主界面 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraContent(
    state: WatermarkCameraViewModel.CameraUiState,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onBack: () -> Unit,
    onShutter: () -> Unit,
    onSelectWatermark: (String?) -> Unit,
    onPickFromGallery: () -> Unit
) {
    var showWatermarkSheet by remember { mutableStateOf(false) }

    val selectedEntry = remember(state.watermarks, state.selectedWatermarkId) {
        WatermarkAssets.find(state.watermarks, state.selectedWatermarkId)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ── 中部：选中相框预览 + 使用说明 ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AppSpacing.Xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (selectedEntry != null) {
                // 相框预览：按原始宽高比等比展示（与编辑页"以水印为基准"模型一致）
                WatermarkAssetImage(
                    assetPath = selectedEntry.assetPath,
                    contentDescription = selectedEntry.displayName,
                    maxSidePx = 1024,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(
                            if (selectedEntry.width > 0 && selectedEntry.height > 0)
                                selectedEntry.width.toFloat() / selectedEntry.height
                            else 1f
                        )
                )
                Spacer(Modifier.height(AppSpacing.Lg))
                Text(
                    "拍摄后将套用「${selectedEntry.displayName}」相框\n可在编辑页调整照片位置与大小",
                    style = AppTypography.BodyMedium,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center
                )
            } else {
                Icon(
                    Icons.Rounded.PhotoCamera,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(88.dp)
                )
                Spacer(Modifier.height(AppSpacing.Xl))
                Text(
                    "点击下方快门使用系统相机拍摄\n拍摄后可为照片添加相框水印",
                    style = AppTypography.BodyMedium,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center
                )
            }
        }

        // ── 顶部控制 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FloatingControl(icon = Icons.AutoMirrored.Filled.ArrowBack, contentDesc = "返回", onClick = onBack)
        }

        // ── 底部操作栏：图库 / 快门 / 水印 ──
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = AppSpacing.Xxl),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图库入口
            FloatingControl(
                icon = Icons.Rounded.Image,
                contentDesc = "从图库选择",
                size = 52.dp,
                onClick = onPickFromGallery
            )

            // 快门 — 拉起系统相机
            ShutterButton(
                enabled = !state.isCapturing,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onShutter()
                }
            )

            // 水印入口 — 有选中水印时直接展示其缩略图
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showWatermarkSheet = true
                    },
                contentAlignment = Alignment.Center
            ) {
                if (selectedEntry != null) {
                    WatermarkAssetImage(
                        assetPath = selectedEntry.preferredThumbPath,
                        contentDescription = "选择水印",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Rounded.AutoAwesome,
                        contentDescription = "选择水印",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }

    // ── 水印选择底部弹层 ──
    if (showWatermarkSheet) {
        ModalBottomSheet(
            onDismissRequest = { showWatermarkSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AppSpacing.Sm)
            ) {
                Text(
                    "选择水印",
                    style = AppTypography.TitleMediumBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = AppSpacing.Lg)
                )
                Spacer(Modifier.height(AppSpacing.Sm))
                Text(
                    "拍摄后可在编辑页调整位置、大小与透明度",
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = AppSpacing.Lg)
                )
                Spacer(Modifier.height(AppSpacing.Md))
                WatermarkPickerRow(
                    watermarks = state.watermarks,
                    selectedId = state.selectedWatermarkId,
                    onSelect = onSelectWatermark,
                    thumbSize = 68.dp
                )
                Spacer(Modifier.height(AppSpacing.Xxl))
            }
        }
    }
}

// ==================== 子组件 ====================

/** 相机页悬浮圆形控件（半透明黑底 + 白图标） */
@Composable
private fun FloatingControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDesc: String,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 44.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDesc,
            tint = Color.White,
            modifier = Modifier.size(size / 2.1f)
        )
    }
}

/** 快门按钮 — 外环描边 + 内实心圆，按压缩放反馈 */
@Composable
private fun ShutterButton(
    enabled: Boolean,
    onClick: () -> Unit
) {
    val pressScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (enabled) 1f else 0.85f,
        label = "shutterScale"
    )
    Box(
        modifier = Modifier
            .size(78.dp)
            .scale(pressScale)
            .clip(CircleShape)
            .border(4.dp, Color.White.copy(alpha = 0.9f), CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(if (enabled) Color.White else Color.White.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
    )
}

// ==================== 引导页 ====================

/** 无相机设备引导 */
@Composable
private fun CameraUnavailableGuide(
    onPickFromGallery: () -> Unit,
    onBack: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        IconButtonCircle(onClick = onBack, modifier = Modifier.align(Alignment.TopStart))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AppSpacing.Xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp)
            )
            Spacer(Modifier.height(AppSpacing.Xl))
            Text(
                "设备无可用相机",
                style = AppTypography.TitleLargeBold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(AppSpacing.Sm))
            Text(
                "仍可以从系统图库选择照片添加水印",
                style = AppTypography.BodyMedium,
                color = Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(AppSpacing.Xxl))
            BlyyPrimaryButton(
                text = "从图库选择",
                onClick = onPickFromGallery,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 引导页左上角返回按钮 */
@Composable
private fun IconButtonCircle(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .statusBarsPadding()
            .padding(AppSpacing.Md)
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            tint = Color.White,
            modifier = Modifier.size(22.dp)
        )
    }
}
