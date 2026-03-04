package com.example.blyy.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.example.blyy.ui.theme.AppColors
import com.example.blyy.ui.theme.AppSpacing
import com.example.blyy.ui.theme.AppTypography
import com.example.blyy.viewmodel.AboutIntent
import com.example.blyy.viewmodel.AboutState
import com.example.blyy.viewmodel.AboutViewModel
import com.example.blyy.viewmodel.UpdateChannel
import com.example.blyy.viewmodel.UpdateStatus

private const val REPO_URL = "https://github.com/oneroomlife/blyy"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    viewModel: AboutViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val glassSurface = if (isDark) AppColors.GlassSurfaceDark else AppColors.GlassSurfaceLight

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        TopAppBar(
            title = { Text("关于") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回"
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(AppSpacing.Screen.Horizontal),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Gap.Section)
        ) {
            AppHeaderSection(currentVersion = viewModel.getVersionName())

            OpenSourceSection(
                context = context,
                glassSurface = glassSurface
            )

            DisclaimerSection(
                context = context,
                glassSurface = glassSurface
            )

            UpdateChannelSection(
                selectedChannel = state.selectedChannel,
                onChannelSelected = { viewModel.onIntent(AboutIntent.SelectChannel(it)) },
                glassSurface = glassSurface
            )

            CheckUpdateSection(
                state = state,
                onCheckUpdate = { viewModel.onIntent(AboutIntent.CheckUpdate) },
                context = context,
                glassSurface = glassSurface
            )

            Spacer(modifier = Modifier.height(AppSpacing.Xl))
        }
    }
}

@Composable
private fun AppHeaderSection(currentVersion: String) {
    val context = LocalContext.current
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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
    context: Context,
    glassSurface: Color
) {
    SectionCard(title = "开源代码", glassSurface = glassSurface) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)) {
            Text(
                text = REPO_URL,
                style = AppTypography.BodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
            ) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("仓库地址", REPO_URL)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(AppSpacing.Corner.Lg)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.Xs))
                    Text("复制地址")
                }

                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, REPO_URL.toUri())
                        context.startActivity(intent)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(AppSpacing.Corner.Lg)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.Xs))
                    Text("打开仓库")
                }
            }
        }
    }
}

@Composable
private fun DisclaimerSection(
    context: Context,
    glassSurface: Color
) {
    SectionCard(title = "版权声明", glassSurface = glassSurface) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)) {
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
            
            OutlinedButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, "https://game.bilibili.com/blhx/".toUri())
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AppSpacing.Corner.Lg)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(AppSpacing.Xs))
                Text("访问碧蓝航线官方网站")
            }
        }
    }
}

@Composable
private fun UpdateChannelSection(
    selectedChannel: UpdateChannel,
    onChannelSelected: (UpdateChannel) -> Unit,
    glassSurface: Color
) {
    SectionCard(title = "更新渠道", glassSurface = glassSurface) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
        ) {
            UpdateChannel.entries.forEach { channel ->
                FilterChip(
                    selected = selectedChannel == channel,
                    onClick = { onChannelSelected(channel) },
                    label = { Text(channel.displayName) },
                    leadingIcon = if (selectedChannel == channel) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else null,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
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
    context: Context,
    glassSurface: Color
) {
    SectionCard(title = "检查更新", glassSurface = glassSurface) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)) {
            Button(
                onClick = onCheckUpdate,
                enabled = !state.isCheckingUpdate,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AppSpacing.Corner.Lg)
            ) {
                if (state.isCheckingUpdate) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(AppSpacing.Sm))
                Text(if (state.isCheckingUpdate) "检查中..." else "检查更新")
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
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                ) {
                                    Text(
                                        text = status.changelog,
                                        style = AppTypography.BodySmall,
                                        modifier = Modifier.padding(AppSpacing.Sm),
                                        maxLines = 5
                                    )
                                }
                            }
                            if (state.downloadUrl.isNotEmpty()) {
                                Button(
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW, state.downloadUrl.toUri())
                                        context.startActivity(intent)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(AppSpacing.Corner.Lg)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(AppSpacing.Sm))
                                    Text("下载更新")
                                }
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isSuccess) {
            AppColors.Favorite.Green.copy(alpha = 0.15f)
        } else {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        },
        shape = RoundedCornerShape(AppSpacing.Corner.Md)
    ) {
        Text(
            text = message,
            style = AppTypography.BodyMedium,
            color = if (isSuccess) {
                AppColors.Favorite.Green
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
            modifier = Modifier.padding(AppSpacing.Md),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    glassSurface: Color,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = glassSurface.copy(alpha = 0.7f)
        ),
        shape = RoundedCornerShape(AppSpacing.Corner.Xl)
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
        ) {
            Text(
                text = title,
                style = AppTypography.TitleMedium,
                fontWeight = FontWeight.SemiBold
            )
            content()
        }
    }
}
