package com.azurlane.blyy.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.azurlane.blyy.R
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyPrimaryButton
import com.azurlane.blyy.ui.components.BlyySecondaryButton
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.util.AppIconType
import com.azurlane.blyy.viewmodel.SettingsViewModel
import com.azurlane.blyy.viewmodel.SettingsViewModel.IconSwitchState

/**
 * 自定义 app 快捷方式设置页
 *
 * 采用 ShortcutManagerCompat 方案：从相册选择图片 → 生成自适应图标 → 创建桌面快捷方式。
 *
 * UI 结构：
 * 1. 顶栏（返回按钮 + 标题）
 * 2. 大图标预览区（居中展示当前图标，带阴影和渐变背景）
 * 3. 不支持提示 / 需授权提示（条件显示）
 * 4. 操作区（从相册选择 + 权限提示）
 *
 * 圆角一致性：所有图标预览容器与图片本身使用相同的 [iconShape]，
 * 消除"图片方角 + 容器圆角"的视觉差异。
 */
@Composable
fun IconSettingsScreen(
    onBack: () -> Unit,
    onNavigateToCropper: (Uri) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val currentType by viewModel.appIconType.collectAsStateWithLifecycle()
    val customPath by viewModel.appCustomIconPath.collectAsStateWithLifecycle()
    val iconTimestamp by viewModel.appIconSelectedAt.collectAsStateWithLifecycle()
    val switching by viewModel.iconSwitching.collectAsStateWithLifecycle()
    val isSupported by viewModel.isPinShortcutSupported.collectAsStateWithLifecycle()

    val context = LocalContext.current

    // 系统相册选择器 — 选图后直接跳转裁剪页
    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            onNavigateToCropper(uri)
        }
    }

    val isProcessing = switching is IconSwitchState.Switching
    val isCustom = currentType == AppIconType.CUSTOM.id && customPath.isNotEmpty()

    // 监听操作状态，显示 Toast
    LaunchedEffect(switching) {
        when (val state = switching) {
            is IconSwitchState.Success -> {
                Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
                viewModel.clearIconSwitchingState()
            }
            is IconSwitchState.Error -> {
                Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
                viewModel.clearIconSwitchingState()
            }
            else -> {}
        }
    }

    AdaptiveScreenBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            BlyyTopBar(
                title = "自定义 app 快捷方式",
                subtitle = "从相册选择图片创建桌面快捷方式",
                onBackClick = onBack
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppSpacing.Screen.Horizontal)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Gap.Lg)
            ) {
                Spacer(modifier = Modifier.height(AppSpacing.Sm))

                // ── 大图标预览区 ──
                IconPreviewCard(
                    isCustom = isCustom,
                    customIconPath = customPath,
                    iconTimestamp = iconTimestamp,
                    isProcessing = isProcessing
                )

                // ── 不支持提示 ──
                if (!isSupported) {
                    UnsupportedHintCard()
                }

                // ── 需要去系统设置授权 ──
                if (switching is IconSwitchState.NeedSettings) {
                    NeedSettingsCard(
                        onOpenSettings = {
                            viewModel.openShortcutSettings()
                            viewModel.clearIconSwitchingState()
                        }
                    )
                }

                // ── 操作区 ──
                ActionSection(
                    isCustom = isCustom,
                    isProcessing = isProcessing,
                    isSupported = isSupported,
                    onPickFromGallery = {
                        pickImageLauncher.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    }
                )

                Spacer(modifier = Modifier.height(AppSpacing.Xxl))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 组件
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 图标预览形状 — 方形圆角，与系统应用图标风格一致
 *
 * 使用 22% 圆角（接近 Android 系统默认 adaptive icon 的 squircle 形状）。
 * 配合安全区域裁剪（[SAFE_ZONE_RATIO]），预览显示的内容范围
 * 与启动器裁剪后的实际可见范围一致。
 */
private val iconShape = RoundedCornerShape(22.dp)

/**
 * 自适应图标安全区域比例
 *
 * Android 官方规范：108dp 总尺寸中，中心 66dp×66dp 为安全区域，
 * OEM 形状蒙版绝不会裁剪该区域；外围 18dp 会被启动器裁剪。
 *
 * 安全区域占比 = 66 / 108 ≈ 0.6111
 *
 * 预览若显示全幅图片，看到的内容比实际多 38.9%，造成「预览与实际不一致」。
 * 预览只显示中心安全区域，内容范围与启动器裁剪后一致。
 * 全幅 Bitmap 仍传给 [IconCompat.createWithAdaptiveBitmap]，
 * 由启动器自行裁剪外围。
 */
private const val SAFE_ZONE_RATIO = 66f / 108f

/**
 * 大图标预览卡片
 *
 * 痛点改进：
 * - 使用阴影模拟真实桌面图标的立体感
 * - 渐变背景增强视觉层次
 * - 加载状态覆盖在图标上，不占额外空间
 * - 图标和容器使用相同的 [iconShape]，消除圆角差异
 * - 使用时间戳作为缓存键，解决更换图标后预览不刷新的问题
 */
@Composable
private fun IconPreviewCard(
    isCustom: Boolean,
    customIconPath: String,
    iconTimestamp: Long,
    isProcessing: Boolean
) {
    val accentColor = MaterialTheme.colorScheme.primary
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
    ) {
        // 图标预览
        val previewSize = 140.dp
        Box(
            modifier = Modifier
                .size(previewSize + 8.dp)  // 外层留阴影空间
                .clip(iconShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.12f),
                            MaterialTheme.colorScheme.surface
                        ),
                        radius = previewSize.toPx()
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            // 阴影层
            Box(
                modifier = Modifier
                    .size(previewSize)
                    .shadow(
                        elevation = AppSpacing.Elevation.Lg,
                        shape = iconShape,
                        clip = false
                    )
                    .clip(iconShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                // 图标内容
                //
                // 【预览与实际一致性原理】
                // 存储的自定义图标是全幅 432×432 Bitmap，传给 createWithAdaptiveBitmap 后，
                // 启动器只显示中心安全区域（66/108 ≈ 61.1%），外围被裁剪。
                //
                // 预览用 graphicsLayer 将图片放大 1/SAFE_ZONE_RATIO ≈ 1.636 倍，
                // clip 裁掉超出部分 — 只显示中心安全区域，与启动器裁剪效果一致。
                // AsyncImage 占满整个容器（fillMaxSize），无空隙。
                //
                // 关键：自定义图标文件路径固定为 custom_icon.png，用户更换图标后文件内容变了
                // 但路径不变。若不设置缓存键，Coil 会命中内存/磁盘缓存，预览不刷新。
                // 用 iconTimestamp 作为 memoryCacheKey/diskCacheKey，每次更换图标时间戳变化，
                // 强制 Coil 重新解码文件。
                if (isCustom && customIconPath.isNotEmpty()) {
                    val imageRequest = remember(customIconPath, iconTimestamp) {
                        ImageRequest.Builder(context)
                            .data(Uri.parse("file://$customIconPath"))
                            .memoryCacheKey("custom_icon_$iconTimestamp")
                            .diskCacheKey("custom_icon_$iconTimestamp")
                            .build()
                    }
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = "自定义快捷方式图标预览",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = 1f / SAFE_ZONE_RATIO
                                scaleY = 1f / SAFE_ZONE_RATIO
                            }
                            .clip(iconShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    // 默认图标 — 使用 Coil 解码 mipmap（支持 adaptive icon XML）
                    // ic_launcher 本身是自适应图标，前景层已在安全区域内，
                    // 用 fillMaxSize + Crop 显示完整图标
                    AsyncImage(
                        model = R.mipmap.ic_launcher,
                        contentDescription = "默认图标",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(iconShape),
                        contentScale = ContentScale.Crop
                    )
                }

                // 加载遮罩
                androidx.compose.animation.AnimatedVisibility(
                    visible = isProcessing,
                    enter = fadeIn() + scaleIn(initialScale = 0.8f),
                    exit = fadeOut() + scaleOut(targetScale = 0.8f),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(iconShape)
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }

        // 图标类型标签
        Text(
            text = if (isCustom) "自定义快捷方式" else "默认图标",
            style = AppTypography.TitleMedium,
            fontWeight = FontWeight.SemiBold
        )

        // 状态徽章
        val badgeColor = if (isCustom) accentColor else MaterialTheme.colorScheme.tertiary
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(badgeColor.copy(alpha = 0.12f))
                .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
        ) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = badgeColor
            )
            Text(
                text = if (isCustom) "已创建桌面快捷方式" else "尚未创建快捷方式",
                style = AppTypography.LabelSmall,
                color = badgeColor,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * 不支持提示卡片
 */
@Composable
private fun UnsupportedHintCard() {
    val warningColor = MaterialTheme.colorScheme.tertiary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.Corner.Md))
            .background(warningColor.copy(alpha = 0.08f))
            .border(
                width = AppSpacing.Border.Thin,
                color = warningColor.copy(alpha = 0.3f),
                shape = RoundedCornerShape(AppSpacing.Corner.Md)
            )
            .padding(AppSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
    ) {
        Icon(
            imageVector = Icons.Rounded.Warning,
            contentDescription = null,
            tint = warningColor,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = "当前设备/启动器不支持固定快捷方式，自定义快捷方式功能不可用",
            style = AppTypography.BodySmall,
            color = warningColor
        )
    }
}

/**
 * 需授权提示卡片 — 当创建快捷方式失败（启动器限制/权限不足）时显示
 *
 * 提供「去设置」按钮引导用户到系统应用详情页开启「创建桌面快捷方式」权限。
 */
@Composable
private fun NeedSettingsCard(onOpenSettings: () -> Unit) {
    val accentColor = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.Corner.Md))
            .background(accentColor.copy(alpha = 0.08f))
            .border(
                width = AppSpacing.Border.Thin,
                color = accentColor.copy(alpha = 0.25f),
                shape = RoundedCornerShape(AppSpacing.Corner.Md)
            )
            .padding(AppSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
        ) {
            Icon(
                imageVector = Icons.Rounded.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = accentColor
            )
            Text(
                text = "需要授权创建快捷方式",
                style = AppTypography.LabelLarge,
                fontWeight = FontWeight.SemiBold,
                color = accentColor
            )
        }
        Text(
            text = "当前启动器拒绝了创建快捷方式的请求。部分国产 ROM（MIUI/ColorOS/OriginOS 等）" +
                "需要手动授权「创建桌面快捷方式」权限。\n请点击下方按钮前往系统设置开启后重试。",
            style = AppTypography.BodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        BlyySecondaryButton(
            text = "前往系统设置",
            onClick = onOpenSettings,
            icon = Icons.Rounded.OpenInNew,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 操作区
 *
 * 包含主操作按钮（从相册选择/更换图标）和权限提示文字。
 * 移除了"移除自定义快捷方式"功能 — Android 不允许应用直接删除 pinned shortcut，
 * 该功能在多数启动器上只会留下灰色图标，反而造成困扰。
 * 用户如需移除，可直接在桌面长按拖拽删除。
 */
@Composable
private fun ActionSection(
    isCustom: Boolean,
    isProcessing: Boolean,
    isSupported: Boolean,
    onPickFromGallery: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
    ) {
        // 主操作按钮
        BlyyPrimaryButton(
            text = if (isCustom) "更换快捷方式图标" else "从相册选择图片",
            onClick = onPickFromGallery,
            enabled = !isProcessing && isSupported,
            icon = Icons.Rounded.AddPhotoAlternate,
            modifier = Modifier.fillMaxWidth()
        )

        // 功能提示 — 创建失败时引导用户检查权限
        Text(
            text = "如创建失败请检查应用权限",
            style = AppTypography.BodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ── 扩展工具 ──

/** 将 Dp 转为 Px（用于 Brush.radialGradient radius 参数） */
private fun androidx.compose.ui.unit.Dp.toPx(): Float {
    return this.value * android.content.res.Resources.getSystem().displayMetrics.density
}
