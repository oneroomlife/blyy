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

@UnstableApi
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun GlassPlayerControlBar(
    playerState: PlayerUiState,
    playerViewModel: PlayerViewModel,
    onVoiceIntent: (VoiceIntent) -> Unit
) {
    val isDark = LocalIsDark.current
    val glassSurface = if (isDark) AppColors.GlassSurfaceDark else AppColors.GlassSurfaceLight
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = remember(configuration, density) {
        with(density) { configuration.screenWidthDp.dp.toPx() }
    }
    
    var isCollapsed by remember { mutableStateOf(false) }
    var collapseToRight by remember { mutableStateOf(true) }
    var totalDragOffset by remember { mutableFloatStateOf(0f) }
    var showPlayLaterSheet by remember { mutableStateOf(false) }
    
    val transition = updateTransition(targetState = isCollapsed to collapseToRight, label = "CollapseTransition")
    
    val expandedAlpha by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 200, easing = AppAnimation.Easings.Standard) },
        label = "ExpandedAlpha"
    ) { (collapsed, _) ->
        if (collapsed) 0f else 1f
    }
    
    val expandedScale by transition.animateFloat(
        transitionSpec = { spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium) },
        label = "ExpandedScale"
    ) { (collapsed, _) ->
        if (collapsed) 0.85f else 1f
    }
    
    val collapsedAlpha by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 250, easing = AppAnimation.Easings.Standard) },
        label = "CollapsedAlpha"
    ) { (collapsed, _) ->
        if (collapsed) 1f else 0f
    }
    
    val collapsedScale by transition.animateFloat(
        transitionSpec = { spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium) },
        label = "CollapsedScale"
    ) { (collapsed, _) ->
        if (collapsed) 1f else 0.7f
    }
    
    val collapsedOffsetX by transition.animateDp(
        transitionSpec = { spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium) },
        label = "CollapsedOffsetX"
    ) { (collapsed, toRight) ->
        if (collapsed) {
            if (toRight) 0.dp else 0.dp
        } else {
            if (toRight) 32.dp else (-32).dp
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (!isCollapsed) {
                        Modifier.pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { totalDragOffset = 0f },
                                onDragEnd = {
                                    val threshold = screenWidthPx * 0.12f
                                    if (!isCollapsed && kotlin.math.abs(totalDragOffset) > threshold) {
                                        isCollapsed = true
                                        collapseToRight = totalDragOffset > 0
                                    }
                                    totalDragOffset = 0f
                                }
                            ) { change, dragAmount ->
                                change.consume()
                                totalDragOffset += dragAmount.x
                            }
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            if (!isCollapsed) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .alpha(expandedAlpha)
                        .graphicsLayer {
                            scaleX = expandedScale
                            scaleY = expandedScale
                        }
                ) {
                    ExpandedPlayerBar(
                        playerState = playerState,
                        glassSurface = glassSurface,
                        onSeek = { playerViewModel.seekTo(it) },
                        onPlayPauseClick = { playerViewModel.playOrPause() },
                        onPreviousClick = { onVoiceIntent(VoiceIntent.SkipPrevious) },
                        onNextClick = { onVoiceIntent(VoiceIntent.SkipNext) },
                        onModeClick = { playerViewModel.cyclePlayMode() },
                        onListClick = { showPlayLaterSheet = true }
                    )
                }
            }

            // 折叠气泡：展开态下完全不可见（collapsedAlpha=0），不参与组合，
            // 避免其内部 3 组无限辉光动画在不可见时也持续重组/重绘（语音页常驻开销）
            if (collapsedAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .align(if (collapseToRight) Alignment.CenterEnd else Alignment.CenterStart)
                        .alpha(collapsedAlpha)
                        .offset(x = collapsedOffsetX)
                        .graphicsLayer {
                            scaleX = collapsedScale
                            scaleY = collapsedScale
                        }
                ) {
                    CollapsedPlayerBar(
                        isPlaying = playerState.isPlaying,
                        playMode = playerState.playMode,
                        collapseToRight = collapseToRight,
                        onPlayPauseClick = { playerViewModel.playOrPause() },
                        onModeClick = { playerViewModel.cyclePlayMode() },
                        onExpandClick = { isCollapsed = false }
                    )
                }
            }        }
    }

    if (showPlayLaterSheet) {
        PlayLaterBottomSheet(
            items = playerState.playLaterList,
            currentlyPlayingUrl = playerState.currentlyPlayingQueueItemUrl,
            isPlayingFromQueue = playerState.isPlayingFromQueue,
            onDismiss = { showPlayLaterSheet = false },
            onPlayItem = { 
                playerViewModel.playFromPlayLater(it)
                showPlayLaterSheet = false
            },
            onStartQueue = { 
                playerViewModel.startPlayQueue()
                showPlayLaterSheet = false
            },
            onRemoveItem = { playerViewModel.removeFromPlayLater(it.voiceUrl) },
            onClearAll = { playerViewModel.clearPlayLaterList() }
        )
    }
}

@Composable
internal fun CollapsedPlayerBar(
    isPlaying: Boolean,
    playMode: PlayMode,
    collapseToRight: Boolean,
    onPlayPauseClick: () -> Unit,
    onModeClick: () -> Unit,
    onExpandClick: () -> Unit
) {
    val isDark = LocalIsDark.current
    // 无限动画 gating：辉光/摇摆仅在播放中运行；暂停时挂空态（静态值），
    // 避免折叠气泡在语音页常驻期间持续跑 3 组无限动画（重组+重绘+每帧分配）
    val glowScale: Float
    val glowAlpha: Float
    val iconRotation: Float
    if (isPlaying) {
        val infiniteTransition = rememberInfiniteTransition(label = "GlowPulse")

        glowScale = infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.3f,
            animationSpec = AppAnimation.Repeating.glow(duration = 2000),
            label = "GlowScale"
        ).value

        glowAlpha = infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 0.6f,
            animationSpec = AppAnimation.Repeating.glow(duration = 2000),
            label = "GlowAlpha"
        ).value

        iconRotation = infiniteTransition.animateFloat(
            initialValue = -5f,
            targetValue = 5f,
            animationSpec = AppAnimation.Repeating.float(duration = 1500),
            label = "IconRotation"
        ).value
    } else {
        glowScale = 1f
        glowAlpha = 0.3f
        iconRotation = 0f
    }

    Box(
        modifier = Modifier.size(64.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(56.dp * glowScale)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha),
                            MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha * 0.5f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )
        
        Surface(
            modifier = Modifier
                .size(52.dp)
                .clickable(onClick = onExpandClick),
            shape = CircleShape,
            color = Color.Transparent,
            shadowElevation = 0.dp
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.linearGradient(
                            colors = if (isDark) {
                                AppColors.Player.CollapsedGradientDark
                            } else {
                                AppColors.Player.CollapsedGradientLight
                            }
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(1.dp)
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
                                )
                            ),
                            shape = CircleShape
                        )
                )
                
                Icon(
                    imageVector = if (collapseToRight) Icons.Rounded.ChevronLeft else Icons.Rounded.ChevronRight,
                    contentDescription = if (collapseToRight) "向左展开" else "向右展开",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(28.dp)
                        .graphicsLayer {
                            rotationZ = iconRotation
                        }
                )
            }
        }
        
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.1f),
                            Color.Transparent
                        ),
                        start = androidx.compose.ui.geometry.Offset(0f, 0f),
                        end = androidx.compose.ui.geometry.Offset(0f, Float.POSITIVE_INFINITY)
                    )
                )
        )
    }
}

@Composable
internal fun ExpandedPlayerBar(
    playerState: PlayerUiState,
    glassSurface: Color,
    onSeek: (Long) -> Unit,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onModeClick: () -> Unit,
    onListClick: () -> Unit
) {
    val hasContent = playerState.currentMediaItem != null
    val animatedAlpha by animateFloatAsState(
        targetValue = if (hasContent) 1f else 0f,
        animationSpec = tween(300, easing = AppAnimation.Easings.Standard),
        label = "ExpandedBarAlpha"
    )

    val playerContent: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
                            Color.Transparent
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier.padding(horizontal = AppSpacing.Xl, vertical = AppSpacing.Lg),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (hasContent) {
                    ModernPlayerSlider(playerState, onSeek = onSeek)
                    Spacer(modifier = Modifier.height(AppSpacing.Md))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlayModeButton(playMode = playerState.playMode, onClick = onModeClick)
                    ModernControlButton(icon = Icons.Rounded.SkipPrevious, size = ControlButtonSize.Medium, onClick = onPreviousClick)
                    ModernPlayButton(isPlaying = playerState.isPlaying, onClick = onPlayPauseClick)
                    ModernControlButton(icon = Icons.Rounded.SkipNext, size = ControlButtonSize.Medium, onClick = onNextClick)
                    ModernControlButton(
                        icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                        isActive = playerState.playLaterList.isNotEmpty(),
                        size = ControlButtonSize.Medium,
                        onClick = onListClick
                    )
                }
            }
        }
    }

    if (LocalUiStyle.current.isCommandCenter()) {
        BlyyPanel(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm)
                .alpha(animatedAlpha),
            chamfer = 14.dp,
            content = playerContent
        )
    } else {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm)
                .alpha(animatedAlpha),
            shape = RoundedCornerShape(AppSpacing.Corner.Xxl),
            color = glassSurface.copy(alpha = 0.95f),
            shadowElevation = AppSpacing.Elevation.Xxl,
            tonalElevation = AppSpacing.Elevation.Lg
        ) {
            playerContent()
        }
    }
}

@Composable
private fun PlayModeButton(
    playMode: PlayMode,
    onClick: () -> Unit
) {
    val (icon, description, isActive) = when (playMode) {
        PlayMode.PLAY_ONCE -> Triple(Icons.Rounded.PlayArrow, "单次播放", false)
        PlayMode.REPEAT_ONE -> Triple(Icons.Rounded.RepeatOne, "单个循环", true)
        PlayMode.REPEAT_ALL -> Triple(Icons.Rounded.Repeat, "顺序循环", true)
        PlayMode.SHUFFLE -> Triple(Icons.Rounded.Shuffle, "随机播放", true)
    }
    
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent,
        modifier = Modifier.size(40.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(AppSpacing.Icon.Lg)
            )
        }
    }
}

private enum class ControlButtonSize {
    Small, Medium, Large
}

@Composable
private fun ModernControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isActive: Boolean = false,
    size: ControlButtonSize = ControlButtonSize.Small,
    onClick: () -> Unit
) {
    val buttonSize = when (size) {
        ControlButtonSize.Small -> 40.dp
        ControlButtonSize.Medium -> 48.dp
        ControlButtonSize.Large -> 56.dp
    }
    
    val iconSize = when (size) {
        ControlButtonSize.Small -> AppSpacing.Icon.Lg
        ControlButtonSize.Medium -> AppSpacing.Icon.Xl
        ControlButtonSize.Large -> AppSpacing.Icon.Xxl
    }

    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent,
        modifier = Modifier.size(buttonSize)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

@Composable
private fun ModernPlayButton(
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    // 辉光无限动画 gating：仅在播放中运行，暂停时静态无辉光
    // （此前播放条可见期间该动画恒跑，未播放也在消耗 CPU/GPU）
    val glowAlpha: Float
    if (isPlaying) {
        val infiniteTransition = rememberInfiniteTransition(label = "PlayButton")
        glowAlpha = infiniteTransition.animateFloat(
            initialValue = 0.2f,
            targetValue = 0.4f,
            animationSpec = AppAnimation.Repeating.glow(duration = 2000),
            label = "GlowAlpha"
        ).value
    } else {
        glowAlpha = 0f
    }

    Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha), Color.Transparent)
                    ),
                    shape = CircleShape
                )
        )
        
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(56.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(AppSpacing.Icon.Xxl)
            )
        }
    }
}


@UnstableApi
@Composable
private fun ModernPlayerSlider(
    playerState: PlayerUiState,
    onSeek: (positionMs: Long) -> Unit
) {
    val duration = playerState.duration.coerceAtLeast(1L)
    val currentPos = playerState.currentPosition.coerceAtLeast(0L)

    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    val sliderValue = if (isDragging) dragValue else playerState.progress
    val hasContent = playerState.currentMediaItem != null && duration > 0L
    
    val sliderAlpha by animateFloatAsState(
        targetValue = if (hasContent) 1f else 0f,
        animationSpec = tween(300, easing = AppAnimation.Easings.Standard),
        label = "SliderAlpha"
    )
    
    val sliderHeight by animateDpAsState(
        targetValue = if (hasContent) 48.dp else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "SliderHeight"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(sliderHeight)
            .alpha(sliderAlpha)
    ) {
        Slider(
            value = sliderValue.coerceIn(0f, 1f),
            onValueChange = {
                isDragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                onSeek((dragValue * duration).toLong())
                isDragging = false
            },
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )
        )
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Xs),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatVoiceTime(if (isDragging) (dragValue * duration).toLong() else currentPos),
                style = AppTypography.LabelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Text(
                text = formatVoiceTime(duration),
                style = AppTypography.LabelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
internal fun formatVoiceTime(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}
