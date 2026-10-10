package com.azurlane.blyy.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.RuntimeShader
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import com.azurlane.blyy.data.local.PlayLaterItem
import com.azurlane.blyy.data.model.VoiceLanguage
import com.azurlane.blyy.data.model.VoiceLine
import com.azurlane.blyy.ui.components.BlyyBottomSheet
import com.azurlane.blyy.ui.components.BlyyErrorState
import com.azurlane.blyy.ui.components.BlyyLoadingState
import com.azurlane.blyy.ui.components.BlyyPanel
import com.azurlane.blyy.ui.components.BlyySpeechBubble
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.theme.LocalUiStyle
import com.azurlane.blyy.ui.theme.isCommandCenter
import com.azurlane.blyy.ui.theme.*
import com.azurlane.blyy.viewmodel.PlayerUiState
import com.azurlane.blyy.viewmodel.PlayerViewModel
import com.azurlane.blyy.viewmodel.PlayMode
import com.azurlane.blyy.viewmodel.VoiceIntent
import com.azurlane.blyy.viewmodel.VoiceViewModel
import com.azurlane.blyy.viewmodel.VoiceViewState
import com.azurlane.blyy.ui.components.rememberBlyyHaptics
import com.azurlane.blyy.ui.components.BlyyHaptic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.intellij.lang.annotations.Language
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayLaterBottomSheet(
    items: List<PlayLaterItem>,
    currentlyPlayingUrl: String?,
    isPlayingFromQueue: Boolean,
    onDismiss: () -> Unit,
    onPlayItem: (PlayLaterItem) -> Unit,
    onStartQueue: () -> Unit,
    onRemoveItem: (PlayLaterItem) -> Unit,
    onClearAll: () -> Unit
) {
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val accentColor = MaterialTheme.colorScheme.primary

    BlyyBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = AppSpacing.Xxxl)
        ) {
            // 标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Lg),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.QueueMusic,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "稍后播放",
                        style = AppTypography.TitleLargeBold
                    )
                    if (items.isNotEmpty()) {
                        Surface(
                            shape = if (isCommandCenter) BlyyShapes.Button else RoundedCornerShape(AppSpacing.Corner.Xxl),
                            color = accentColor.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = "${items.size}",
                                style = AppTypography.LabelMediumBold,
                                color = accentColor,
                                modifier = Modifier.padding(horizontal = AppSpacing.Sm, vertical = AppSpacing.Xxs)
                            )
                        }
                    }
                }
                if (items.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
                    ) {
                        FilledTonalButton(
                            onClick = onStartQueue,
                            contentPadding = PaddingValues(horizontal = AppSpacing.Md, vertical = AppSpacing.Xs)
                        ) {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(AppSpacing.Xs))
                            Text("播放全部", style = AppTypography.LabelLarge)
                        }
                        TextButton(
                            onClick = onClearAll,
                            contentPadding = PaddingValues(horizontal = AppSpacing.Md, vertical = AppSpacing.Xs)
                        ) {
                            Text("清空", color = MaterialTheme.colorScheme.error, style = AppTypography.LabelLarge)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.Sm))

            if (items.isEmpty()) {
                // 空状态 — 带图标和引导
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.QueueMusic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(AppSpacing.Md))
                    Text(
                        text = "列表空空如也",
                        style = AppTypography.TitleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.Xs))
                    Text(
                        text = "长按语音条目可添加到稍后播放",
                        style = AppTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = AppSpacing.Lg, vertical = AppSpacing.Xs)
                ) {
                    itemsIndexed(items, key = { _, item -> item.voiceUrl }) { index, item ->
                        val isCurrentlyPlaying = item.voiceUrl == currentlyPlayingUrl && isPlayingFromQueue
                        val isNextUp = !isCurrentlyPlaying && index == 0 && isPlayingFromQueue
                        val hasPlayed = item.hasPlayed

                        PlayLaterQueueItem(
                            index = index,
                            item = item,
                            isCurrentlyPlaying = isCurrentlyPlaying,
                            isNextUp = isNextUp,
                            hasPlayed = hasPlayed,
                            onPlayItem = onPlayItem,
                            onRemoveItem = onRemoveItem
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayLaterQueueItem(
    index: Int,
    item: PlayLaterItem,
    isCurrentlyPlaying: Boolean,
    isNextUp: Boolean,
    hasPlayed: Boolean,
    onPlayItem: (PlayLaterItem) -> Unit,
    onRemoveItem: (PlayLaterItem) -> Unit
) {
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val accentColor = MaterialTheme.colorScheme.primary

    val itemShape = if (isCommandCenter) BlyyShapes.PanelSmall else RoundedCornerShape(AppSpacing.Corner.Lg)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.Xxs),
        shape = itemShape,
        color = when {
            isCurrentlyPlaying -> accentColor.copy(alpha = 0.1f)
            isNextUp -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
            hasPlayed -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.2f)
            else -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f)
        },
        border = if (isCurrentlyPlaying) BorderStroke(
            AppSpacing.Border.Thin,
            accentColor.copy(alpha = 0.3f)
        ) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm)
                .alpha(if (hasPlayed && !isCurrentlyPlaying) 0.55f else 1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 序号/播放状态指示
            Box(
                modifier = Modifier
                    .size(28.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isCurrentlyPlaying -> {
                        // 正在播放 — 脉冲动画
                        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                        val pulseAlpha by infiniteTransition.animateFloat(
                            initialValue = 0.4f,
                            targetValue = 1f,
                            animationSpec = AppAnimation.Repeating.breathing(duration = 800),
                            label = "pulseAlpha"
                        )
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(
                                    accentColor.copy(alpha = pulseAlpha * 0.15f),
                                    CircleShape
                                )
                        )
                        Icon(
                            Icons.Rounded.GraphicEq,
                            contentDescription = "正在播放",
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    isNextUp -> {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
                        ) {
                            Icon(
                                Icons.Rounded.SkipNext,
                                contentDescription = "下一个",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier
                                    .size(28.dp)
                                    .padding(5.dp)
                            )
                        }
                    }
                    else -> {
                        // 序号或播放按钮
                        if (hasPlayed) {
                            Text(
                                text = "${index + 1}",
                                style = AppTypography.LabelSmall,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center
                            )
                        } else {
                            IconButton(
                                onClick = { onPlayItem(item) },
                                modifier = Modifier.minimumInteractiveComponentSize().size(28.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.PlayCircle,
                                    contentDescription = "播放",
                                    tint = accentColor.copy(alpha = 0.7f),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(AppSpacing.Sm))

            // 头像
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .then(
                        if (isCurrentlyPlaying) {
                            Modifier.border(
                                width = AppSpacing.Border.Thin,
                                color = accentColor.copy(alpha = 0.5f),
                                shape = CircleShape
                            )
                        } else Modifier
                    )
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                if (item.avatarUrl.isNotBlank()) {
                    AsyncImage(
                        model = item.avatarUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Rounded.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(AppSpacing.Md))

            // 文字信息
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
                ) {
                    Text(
                        text = item.shipName,
                        style = AppTypography.TitleSmall,
                        fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (isCurrentlyPlaying) accentColor else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        text = "·",
                        style = AppTypography.TitleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Text(
                        text = item.scene,
                        style = AppTypography.TitleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (hasPlayed && !isCurrentlyPlaying) {
                        Surface(
                            shape = if (isCommandCenter) BlyyShapes.Button else RoundedCornerShape(AppSpacing.Corner.Xs),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = "已播放",
                                style = AppTypography.LabelSmall,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Text(
                    text = item.dialogue,
                    style = AppTypography.BodySmall,
                    color = if (hasPlayed && !isCurrentlyPlaying)
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 移除按钮 — 低调设计
            IconButton(
                onClick = { onRemoveItem(item) },
                modifier = Modifier.minimumInteractiveComponentSize().size(28.dp)
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "移除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
