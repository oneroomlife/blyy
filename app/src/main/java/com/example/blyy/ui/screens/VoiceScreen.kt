package com.example.blyy.ui.screens

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import com.example.blyy.data.model.VoiceLine
import com.example.blyy.ui.theme.*
import com.example.blyy.viewmodel.PlayerUiState
import com.example.blyy.viewmodel.PlayerViewModel
import com.example.blyy.viewmodel.VoiceIntent
import com.example.blyy.viewmodel.VoiceViewModel
import com.example.blyy.viewmodel.VoiceViewState
import org.intellij.lang.annotations.Language
import java.util.Locale
import kotlin.math.roundToInt

private object VoiceScreenConfig {
    val AvatarSize = 140.dp
    val PlayerBarHeight = 100.dp
}

@UnstableApi
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun VoiceScreen(
    onBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    voiceViewModel: VoiceViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel = hiltViewModel(),
) {
    val voiceState by voiceViewModel.state.collectAsState()
    val playerState by playerViewModel.uiState.collectAsState()

    VoiceScreenContent(
        voiceState = voiceState,
        playerState = playerState,
        onVoiceIntent = voiceViewModel::onIntent,
        playerViewModel = playerViewModel,
        onBack = onBack,
        sharedTransitionScope = sharedTransitionScope,
        animatedContentScope = animatedContentScope
    )
}


@UnstableApi
@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreenContent(
    voiceState: VoiceViewState,
    playerState: PlayerUiState,
    onVoiceIntent: (VoiceIntent) -> Unit,
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope
) {
    val groupedVoices by remember(voiceState.voices) {
        derivedStateOf { voiceState.voices.groupBy { it.skinName } }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedBackground(avatarUrl = voiceState.avatarUrl)

        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                VoiceTopBar(
                    title = voiceState.shipName,
                    onBack = onBack,
                    scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
                )
            },
            bottomBar = {
                AnimatedVisibility(
                    visible = voiceState.voices.isNotEmpty() && playerState.currentMediaItem != null,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut()
                ) {
                    GlassPlayerControlBar(
                        playerState = playerState,
                        playerViewModel = playerViewModel
                    )
                }
            }
        ) { innerPadding ->
            
            if (voiceState.error != null) {
                ErrorStateView(
                    error = voiceState.error,
                    onRetry = { onVoiceIntent(VoiceIntent.LoadVoices(voiceState.shipName, voiceState.avatarUrl)) }
                )
            } else if (voiceState.isLoading && voiceState.voices.isEmpty()) {
                LoadingStateView()
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding()),
                    contentPadding = PaddingValues(bottom = VoiceScreenConfig.PlayerBarHeight + AppSpacing.Lg),
                ) {
                    item {
                        ShipHeader(
                            state = voiceState,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedContentScope = animatedContentScope
                        )
                    }

                    groupedVoices.forEach { (skinName, skinVoices) ->
                        stickyHeader {
                            SkinHeader(skinName)
                        }

                        itemsIndexed(skinVoices) { _, voice ->
                            val globalIndex = voiceState.voices.indexOf(voice)
                            val isCurrent = playerState.currentMediaItem?.mediaId == voice.audioUrl
                            val isPlaying = isCurrent && playerState.isPlaying

                            VoiceItemRow(
                                voice = voice,
                                isCurrent = isCurrent,
                                isPlaying = isPlaying,
                                onClick = { onVoiceIntent(VoiceIntent.PlayVoiceAtIndex(globalIndex)) }
                            )
                        }
                    }
                }
            }
            
            // 可拖动的立绘小人
            if (voiceState.figureUrl.isNotEmpty()) {
                val currentDialogue = if (voiceState.isPlaying && voiceState.currentVoiceIndex in voiceState.voices.indices) {
                    voiceState.voices[voiceState.currentVoiceIndex].dialogue
                } else null

                DraggableFigure(
                    figureUrl = voiceState.figureUrl,
                    dialogue = currentDialogue,
                    onRandomPlay = { onVoiceIntent(VoiceIntent.PlayRandomVoice(voiceState.currentVoiceIndex)) }
                )
            }
        }
    }
}


@Composable
private fun AnimatedBackground(avatarUrl: String) {
    val isDark = isSystemInDarkTheme()
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = remember { RuntimeShader(FLUID_SHADER_VOICE) }
        val time by produceState(0f) {
            val startTime = System.nanoTime()
            while (true) {
                withInfiniteAnimationFrameMillis {
                    val elapsed = (System.nanoTime() - startTime) / 1_000_000_000f
                    value = elapsed
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            // 基础背景色
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = if (isDark) AppColors.Gradient.BackgroundDark() else AppColors.Gradient.BackgroundLight()
                    )
            )
            
            if (avatarUrl.isNotEmpty()) {
                // 更加柔和的模糊背景图
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(radius = 100.dp) // 增加模糊半径，更通透
                )
                
                val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
                // 多层叠加增加质感
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            // 增加纹理感
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = if (isDark) {
                                        listOf(
                                            Color.Black.copy(alpha = 0.2f),
                                            Color.Black.copy(alpha = 0.4f),
                                            surfaceContainer.copy(alpha = 0.7f)
                                        )
                                    } else {
                                        listOf(
                                            Color.White.copy(alpha = 0.1f),
                                            Color.White.copy(alpha = 0.3f),
                                            surfaceContainer.copy(alpha = 0.6f)
                                        )
                                    }
                                )
                            )
                        }
                )
            }
            
            // 动态流体效果 - 降低透明度使其更细腻
            Canvas(modifier = Modifier.fillMaxSize()) {
                shader.setFloatUniform("iResolution", size.width, size.height)
                shader.setFloatUniform("iTime", time)
                drawRect(brush = ShaderBrush(shader), alpha = 0.08f)
            }
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = if (isDark) AppColors.Gradient.BackgroundDark() else AppColors.Gradient.BackgroundLight()
                )
        )
    }
}


@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ShipHeader(
    state: VoiceViewState,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.Xxxl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = with(sharedTransitionScope) {
                Modifier
                    .sharedElement(
                        sharedContentState = rememberSharedContentState(key = "avatar-${state.shipName}"),
                        animatedVisibilityScope = animatedContentScope
                    )
                    .size(VoiceScreenConfig.AvatarSize)
                    .clip(CircleShape)
                    .border(
                        width = AppSpacing.Border.Normal,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            )
                        ),
                        shape = CircleShape
                    )
            },
            color = Color.Transparent,
            shadowElevation = AppSpacing.Elevation.Xl,
            shape = CircleShape
        ) {
            AsyncImage(
                model = state.avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.height(AppSpacing.Xl))
        Text(
            text = state.shipName,
            style = AppTypography.HeadlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(AppSpacing.Xs))
        Text(
            text = "Voice Collection",
            style = AppTypography.LabelMedium.copy(
                letterSpacing = AppTypography.LabelMedium.letterSpacing * 2
            ),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
        )
    }
}


@Composable
private fun SkinHeader(skinName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(AppSpacing.Xs)
                .height(AppSpacing.Lg)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        )
                    ),
                    shape = RoundedCornerShape(AppSpacing.Corner.Xs)
                )
        )
        Spacer(modifier = Modifier.width(AppSpacing.Md))
        Text(
            text = skinName,
            style = AppTypography.TitleMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}


@Composable
private fun VoiceItemRow(
    voice: VoiceLine,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (isCurrent)
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    else
        Color.Transparent

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Xs)
            .clip(RoundedCornerShape(AppSpacing.Corner.Lg)),
        color = containerColor,
        tonalElevation = if (isCurrent) AppSpacing.Elevation.Sm else AppSpacing.Elevation.None,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Lg),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.Audiotrack,
                contentDescription = null,
                tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(AppSpacing.Icon.Xxl)
                    .padding(top = AppSpacing.Xxs)
            )
            Spacer(modifier = Modifier.width(AppSpacing.Lg))
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = voice.scene,
                    style = AppTypography.LabelLarge,
                    color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(AppSpacing.Xs))
                Text(
                    text = voice.dialogue,
                    style = AppTypography.BodyMedium,
                    color = if (isCurrent) 
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.95f) 
                    else 
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    maxLines = if (isCurrent) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}


@UnstableApi
@Composable
private fun GlassPlayerControlBar(
    playerState: PlayerUiState,
    playerViewModel: PlayerViewModel
) {
    val isDark = isSystemInDarkTheme()
    val glassSurface = if (isDark) AppColors.GlassSurfaceDark else AppColors.GlassSurfaceLight
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm),
            shape = RoundedCornerShape(AppSpacing.Corner.Xxl),
            color = glassSurface.copy(alpha = 0.95f),
            shadowElevation = AppSpacing.Elevation.Xxl,
            tonalElevation = AppSpacing.Elevation.Lg
        ) {
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
                    ModernPlayerSlider(playerState, onSeek = { playerViewModel.seekTo(it) })
                    
                    Spacer(modifier = Modifier.height(AppSpacing.Md))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ModernControlButton(
                            icon = when (playerState.repeatMode) {
                                Player.REPEAT_MODE_ONE -> Icons.Rounded.RepeatOne
                                Player.REPEAT_MODE_ALL -> Icons.Rounded.Repeat
                                else -> Icons.Rounded.RepeatOn
                            },
                            isActive = playerState.repeatMode != Player.REPEAT_MODE_OFF,
                            onClick = { playerViewModel.toggleRepeatMode() }
                        )

                        ModernControlButton(
                            icon = Icons.Rounded.SkipPrevious,
                            size = ControlButtonSize.Medium,
                            onClick = { playerViewModel.skipToPrevious() }
                        )

                        ModernPlayButton(
                            isPlaying = playerState.isPlaying,
                            onClick = { playerViewModel.playOrPause() }
                        )

                        ModernControlButton(
                            icon = Icons.Rounded.SkipNext,
                            size = ControlButtonSize.Medium,
                            onClick = { playerViewModel.skipToNext() }
                        )

                        ModernControlButton(
                            icon = Icons.Rounded.Shuffle,
                            isActive = playerState.shuffleModeEnabled,
                            onClick = { playerViewModel.toggleShuffleMode() }
                        )
                    }
                }
            }
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
        color = if (isActive) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        } else {
            Color.Transparent
        },
        modifier = Modifier.size(buttonSize)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                },
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
    val infiniteTransition = rememberInfiniteTransition(label = "PlayButton")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GlowAlpha"
    )

    Box(
        modifier = Modifier.size(64.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )
        
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(56.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
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

    Column(
        modifier = Modifier.fillMaxWidth()
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
@Composable
private fun VoiceTopBar(title: String, onBack: () -> Unit, scrollBehavior: TopAppBarScrollBehavior) {
    val isDark = isSystemInDarkTheme()
    val glassSurface = if (isDark) AppColors.GlassSurfaceDark else AppColors.GlassSurfaceLight
    
    CenterAlignedTopAppBar(
        title = {
            Text(
                text = title, 
                style = AppTypography.TitleLarge
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Surface(
                    shape = CircleShape,
                    color = glassSurface,
                    tonalElevation = AppSpacing.Elevation.Sm
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack, 
                        contentDescription = "Back",
                        modifier = Modifier.padding(AppSpacing.Sm)
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = glassSurface
        ),
        scrollBehavior = scrollBehavior
    )
}


@Composable
private fun ErrorStateView(error: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Rounded.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(AppSpacing.Icon.Massive),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(AppSpacing.Lg))
        Text(
            text = error,
            style = AppTypography.BodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = AppSpacing.Xxl)
        )
        Spacer(Modifier.height(AppSpacing.Xxl))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer, 
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ),
            shape = RoundedCornerShape(AppSpacing.Corner.Lg)
        ) {
            Text("重试")
        }
    }
}


@Composable
private fun LoadingStateView() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}


private fun formatVoiceTime(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}


@Suppress("SpellCheckingInspection")
@Language("AGSL")
const val FLUID_SHADER_VOICE = """
    uniform float2 iResolution;
    uniform float iTime;

    half4 main(in float2 fragCoord) {
        float2 uv = fragCoord / iResolution.xy;
        float3 color = 0.5 + 0.5 * cos(iTime * 0.5 + uv.xyx + float3(0, 2, 4));
        return half4(color * 0.15 + 0.85, 0.1); 
    }
"""

@Composable
private fun DraggableFigure(
    figureUrl: String,
    dialogue: String? = null,
    onRandomPlay: () -> Unit
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val hapticFeedback = LocalHapticFeedback.current
    val isDark = isSystemInDarkTheme()
    
    val screenWidth = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeight = with(density) { configuration.screenHeightDp.dp.toPx() }
    
    val figureWidth = 110.dp
    val figureHeight = 165.dp
    val figureWidthPx = with(density) { figureWidth.toPx() }
    val figureHeightPx = with(density) { figureHeight.toPx() }

    var offsetX by remember { mutableFloatStateOf(screenWidth - figureWidthPx - 30f) }
    var offsetY by remember { mutableFloatStateOf(screenHeight * 0.45f) }
    
    var isDragging by remember { mutableStateOf(false) }
    var isTapped by remember { mutableStateOf(false) }
    
    // 点击时的缩放动效
    val scale by animateFloatAsState(
        targetValue = when {
            isDragging -> 1.25f
            isTapped -> 1.15f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        finishedListener = { isTapped = false },
        label = "FigureScale"
    )
    
    val alpha by animateFloatAsState(
        targetValue = if (isDragging) 0.7f else 1f,
        animationSpec = tween(250),
        label = "FigureAlpha"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // --- 1. 动态台词对话框 ---
        val bubbleMaxHeight = 200.dp
        val bubbleWidth = 220.dp
        val bubbleWidthPx = with(density) { bubbleWidth.toPx() }
        val textLength = dialogue?.length ?: 0
        // 进一步缩短间距：基础值设为 -6dp（重叠感），且随长度增长极其缓慢
        val dynamicGap = ((-6) + (textLength / 60) * 2).dp.coerceAtMost(4.dp)

        AnimatedVisibility(
            visible = dialogue != null && !isDragging,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom) + scaleIn(transformOrigin = TransformOrigin(0.5f, 1f)),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom) + scaleOut(transformOrigin = TransformOrigin(0.5f, 1f)),
            modifier = Modifier
                .offset { 
                    IntOffset(
                        (offsetX + figureWidthPx / 2 - bubbleWidthPx / 2).roundToInt(), 
                        (offsetY - bubbleMaxHeight.toPx() - dynamicGap.toPx()).roundToInt() 
                    ) 
                }
                .size(bubbleWidth, bubbleMaxHeight)
        ) {
            // 通过 Alignment.BottomCenter 确保文本增加时向上生长，位置协调
            Box(contentAlignment = Alignment.BottomCenter) {
                SpeechBubble(text = dialogue ?: "", isDark = isDark)
            }
        }

        // --- 2. 立绘本体 (精准拦截手势) ---
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(figureWidth, figureHeight)
                .scale(scale)
                .graphicsLayer { this.alpha = alpha }
                // 关键修复：仅在立绘本体区域监听手势，不拦截全屏背景，确保列表和按钮正常工作
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            isTapped = true
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onRandomPlay()
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { 
                            isDragging = true
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            offsetX = (offsetX + dragAmount.x).coerceIn(0f, screenWidth - figureWidthPx)
                            offsetY = (offsetY + dragAmount.y).coerceIn(0f, screenHeight - figureHeightPx)
                        }
                    )
                }
        ) {
            AsyncImage(
                model = figureUrl,
                contentDescription = "舰娘立绘",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
            
            if (isDragging) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = AppSpacing.Xs),
                    shape = RoundedCornerShape(AppSpacing.Corner.Sm),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
                ) {
                    Text(
                        text = "配置中",
                        style = AppTypography.LabelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = AppSpacing.Sm, vertical = AppSpacing.Xxs)
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeechBubble(text: String, isDark: Boolean) {
    val bubbleColor = if (isDark) Color(0xFF2C2C2E).copy(alpha = 0.9f) else Color.White.copy(alpha = 0.95f)
    val textColor = if (isDark) Color.White else Color.Black
    
    // 根据文本长度动态调整内边距和圆角
    val hPadding = if (text.length > 40) 16.dp else 12.dp
    val vPadding = if (text.length > 40) 10.dp else 8.dp
    val cornerRadius = if (text.length > 60) 16.dp else 12.dp

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(cornerRadius),
            color = bubbleColor,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = hPadding, vertical = vPadding),
                style = AppTypography.BodySmall.copy(
                    // 针对极长文本自动缩小字号
                    fontSize = if (text.length > 80) 11.sp else 13.sp,
                    lineHeight = if (text.length > 80) 15.sp else 18.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = textColor,
                textAlign = TextAlign.Center
            )
        }
        // 三角形指示器
        Canvas(modifier = Modifier.size(12.dp, 6.dp)) {
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width / 2, size.height)
                close()
            }
            drawPath(path, color = bubbleColor)
        }
    }
}
