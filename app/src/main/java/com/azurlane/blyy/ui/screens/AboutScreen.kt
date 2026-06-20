package com.azurlane.blyy.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyChip
import com.azurlane.blyy.ui.components.BlyyPanel
import com.azurlane.blyy.ui.components.BlyyPrimaryButton
import com.azurlane.blyy.ui.components.BlyySecondaryButton
import com.azurlane.blyy.ui.components.BlyySectionPanel
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.theme.AppColors
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.viewmodel.AboutIntent
import com.azurlane.blyy.viewmodel.AboutState
import com.azurlane.blyy.viewmodel.AboutViewModel
import com.azurlane.blyy.viewmodel.UpdateChannel
import com.azurlane.blyy.viewmodel.UpdateStatus

private const val REPO_URL = "https://github.com/oneroomlife/blyy"

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    viewModel: AboutViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    AdaptiveScreenBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            BlyyTopBar(
                title = "关于",
                subtitle = "碧蓝语音 BLYY",
                onBackClick = onBack
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(AppSpacing.Screen.Horizontal),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Gap.Section)
            ) {
                AppHeaderSection(currentVersion = viewModel.getVersionName())

                OpenSourceSection(context = context)

                DisclaimerSection(context = context)

                UpdateChannelSection(
                    selectedChannel = state.selectedChannel,
                    onChannelSelected = { viewModel.onIntent(AboutIntent.SelectChannel(it)) }
                )

                CheckUpdateSection(
                    state = state,
                    onCheckUpdate = { viewModel.onIntent(AboutIntent.CheckUpdate) },
                    context = context
                )

                Spacer(modifier = Modifier.height(AppSpacing.Xl))
            }
        }
    }
}

@Composable
private fun AppHeaderSection(currentVersion: String) {
    val context = LocalContext.current

    val infiniteTransition = rememberInfiniteTransition(label = "appIconGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center
        ) {
            // Outer glowing animated background — Accent radial gradients
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                AppColors.Accent.Cyan.copy(alpha = glowAlpha * 0.5f),
                                AppColors.Accent.Gold.copy(alpha = glowAlpha * 0.3f),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )

            // Inner icon container with gradient
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = "android.resource://${context.packageName}/mipmap/cf",
                    contentDescription = "应用图标",
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Fit
                )
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.Md))

        Text(
            text = "碧蓝语音",
            style = AppTypography.TitleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(AppSpacing.Xs))

        Text(
            text = "版本 $currentVersion",
            style = AppTypography.BodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OpenSourceSection(
    context: Context
) {
    BlyySectionPanel(
        title = "开源代码",
        icon = Icons.Rounded.Code,
        accentColor = AppColors.Accent.Cyan
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
        ) {
            Text(
                text = REPO_URL,
                style = AppTypography.BodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
            ) {
                BlyySecondaryButton(
                    text = "复制地址",
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("仓库地址", REPO_URL)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    },
                    icon = Icons.Rounded.ContentCopy,
                    modifier = Modifier.weight(1f)
                )

                BlyyPrimaryButton(
                    text = "打开仓库",
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, REPO_URL.toUri())
                        context.startActivity(intent)
                    },
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun DisclaimerSection(
    context: Context
) {
    BlyySectionPanel(
        title = "版权声明",
        icon = Icons.Rounded.Gavel,
        accentColor = AppColors.Accent.Gold
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
        ) {
            Text(
                text = "本应用中涉及的所有角色台词、立绘、语音等内容，其著作权及相关权利均归碧蓝航线游戏公司（Manjuu）所有。",
                style = AppTypography.BodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "本应用所使用的游戏资源链接均来源于碧蓝航线Wiki（https://wiki.biligame.com/blhx），仅供学习交流使用，不得用于任何商业用途。",
                style = AppTypography.BodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "如有任何侵权问题，请联系开发者处理。",
                style = AppTypography.BodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            BlyySecondaryButton(
                text = "访问碧蓝航线官方网站",
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, "https://game.bilibili.com/blhx/".toUri())
                    context.startActivity(intent)
                },
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun UpdateChannelSection(
    selectedChannel: UpdateChannel,
    onChannelSelected: (UpdateChannel) -> Unit
) {
    BlyySectionPanel(
        title = "更新渠道",
        icon = Icons.Rounded.Update,
        accentColor = AppColors.Accent.Cyan
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Lg),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
        ) {
            UpdateChannel.entries.forEach { channel ->
                BlyyChip(
                    label = channel.displayName,
                    selected = selectedChannel == channel,
                    onClick = { onChannelSelected(channel) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun CheckUpdateSection(
    state: AboutState,
    onCheckUpdate: () -> Unit,
    context: Context
) {
    BlyySectionPanel(
        title = "检查更新",
        icon = Icons.Rounded.Download,
        accentColor = AppColors.Accent.Gold
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
        ) {
            if (state.isCheckingUpdate) {
                BlyyPanel(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AppSpacing.Md),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.Sm))
                        Text(
                            text = "检查中...",
                            style = AppTypography.BodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                BlyyPrimaryButton(
                    text = "检查更新",
                    onClick = onCheckUpdate,
                    icon = Icons.Rounded.Refresh,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            AnimatedVisibility(
                visible = state.updateStatus !is UpdateStatus.Idle && state.updateStatus !is UpdateStatus.Checking,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                when (val status = state.updateStatus) {
                    is UpdateStatus.UpToDate -> {
                        StatusCard(
                            message = "当前已是最新版本",
                            isSuccess = true
                        )
                    }
                    is UpdateStatus.UpdateAvailable -> {
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                            StatusCard(
                                message = "发现新版本: ${status.version}",
                                isSuccess = false
                            )
                            if (status.changelog.isNotEmpty()) {
                                BlyyPanel(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = status.changelog,
                                        style = AppTypography.BodySmall,
                                        modifier = Modifier.padding(AppSpacing.Sm),
                                        maxLines = 5
                                    )
                                }
                            }
                            if (state.downloadUrl.isNotEmpty()) {
                                BlyyPrimaryButton(
                                    text = "下载更新",
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW, state.downloadUrl.toUri())
                                        context.startActivity(intent)
                                    },
                                    icon = Icons.Rounded.Download,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    is UpdateStatus.Error -> {
                        StatusCard(
                            message = status.message,
                            isSuccess = false
                        )
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    message: String,
    isSuccess: Boolean
) {
    val accentColor = if (isSuccess) AppColors.Favorite.Green else MaterialTheme.colorScheme.primary
    BlyyPanel(
        modifier = Modifier.fillMaxWidth(),
        accentColor = accentColor
    ) {
        Text(
            text = message,
            style = AppTypography.BodyMedium,
            color = if (isSuccess) AppColors.Favorite.Green else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(AppSpacing.Md),
            textAlign = TextAlign.Center
        )
    }
}
