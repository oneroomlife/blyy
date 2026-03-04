package com.example.blyy.ui.screens

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
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
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
import com.example.blyy.data.local.PlayLaterItem
import com.example.blyy.data.model.VoiceLine
import com.example.blyy.ui.theme.*
import com.example.blyy.viewmodel.PlayerUiState
import com.example.blyy.viewmodel.PlayerViewModel
import com.example.blyy.viewmodel.PlayMode
import com.example.blyy.viewmodel.VoiceIntent
import com.example.blyy.viewmodel.VoiceViewModel
import com.example.blyy.viewmodel.VoiceViewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.intellij.lang.annotations.Language
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

private object VoiceScreenConfig {
    val AvatarSize = 140.dp
    val PlayerBarHeight = 90.dp
    val CollapsedBarWidth = 56.dp
    val CollapsedVisibleWidth = 56.dp
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
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    
    val groupedVoices by remember(voiceState.voices) {
        derivedStateOf { 
            voiceState.voices.groupBy { it.skinName } 
        }
    }
    
    val favorites by remember(playerState.favorites) {
        derivedStateOf { playerState.favorites }
    }
    
    fun downloadVoice(voice: VoiceLine) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val url = URL(voice.audioUrl)
                    val fileName = "${voiceState.shipName}_${voice.scene}.mp3"
                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    )
                    val file = File(downloadsDir, "BLYY/$fileName")
                    file.parentFile?.mkdirs()
                    
                    url.openStream().use { input ->
                        FileOutputStream(file).use { output ->
                            input.copyTo(output)
                        }
                    }
                    
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "已保存到 Download/BLYY/$fileName", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    fun shareVoice(voice: VoiceLine) {
        val shareText = "${voice.dialogue}\n\n音频链接: ${voice.audioUrl}"
        clipboardManager.setText(AnnotatedString(shareText))
        Toast.makeText(context, "链接已复制", Toast.LENGTH_SHORT).show()
    }
    
    fun toggleFavorite(voice: VoiceLine) {
        playerViewModel.toggleFavorite(voice.audioUrl)
        val isFav = voice.audioUrl !in playerState.favorites
        Toast.makeText(context, if (isFav) "已收藏并置顶" else "已取消收藏", Toast.LENGTH_SHORT).show()
    }
    
    fun addToPlayLater(voice: VoiceLine) {
        playerViewModel.addToPlayLater(
            PlayLaterItem(
                voiceUrl = voice.audioUrl,
                shipName = voiceState.shipName,
                scene = voice.scene,
                dialogue = voice.dialogue,
                avatarUrl = voiceState.avatarUrl
            )
        )
        Toast.makeText(context, "已加入稍后播放", Toast.LENGTH_SHORT).show()
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
                            val isFavorite = voice.audioUrl in favorites

                            VoiceItemRow(
                                voice = voice,
                                isCurrent = isCurrent,
                                isPlaying = isPlaying,
                                isFavorite = isFavorite,
                                onAddToPlayLater = { addToPlayLater(voice) },
                                onClick = { onVoiceIntent(VoiceIntent.PlayVoiceAtIndex(globalIndex, playerState.playMode)) },
                                onDownloadClick = { downloadVoice(voice) },
                                onFavoriteClick = { toggleFavorite(voice) },
                                onShareClick = { shareVoice(voice) }
                            )
                        }
                    }
                }
            }
            
            if (voiceState.currentSkinFigureUrl.isNotEmpty()) {
                val currentDialogue = if (voiceState.isPlaying && voiceState.currentVoiceIndex in voiceState.voices.indices) {
                    voiceState.voices[voiceState.currentVoiceIndex].dialogue
                } else null

                DraggableFigure(
                    figureUrl = voiceState.currentSkinFigureUrl,
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = if (isDark) AppColors.Gradient.BackgroundDark() else AppColors.Gradient.BackgroundLight()
                    )
            )
            
            if (avatarUrl.isNotEmpty()) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(radius = 100.dp)
                )
                
                val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
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


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoiceItemRow(
    voice: VoiceLine,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isFavorite: Boolean,
    onAddToPlayLater: () -> Unit,
    onClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onShareClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var showMenu by remember { mutableStateOf(false) }
    
    val containerColor = if (isCurrent)
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    else
        Color.Transparent

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Xs)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppSpacing.Corner.Lg))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showMenu = true
                    }
                ),
            color = containerColor,
            tonalElevation = if (isCurrent) AppSpacing.Elevation.Sm else AppSpacing.Elevation.None
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = voice.scene,
                            style = AppTypography.LabelLarge,
                            color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                        )
                        if (isFavorite) {
                            Spacer(modifier = Modifier.width(AppSpacing.Xs))
                            Icon(
                                imageVector = Icons.Rounded.PushPin,
                                contentDescription = "已收藏并置顶",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
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
        
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            shape = RoundedCornerShape(AppSpacing.Corner.Lg),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = AppSpacing.Elevation.Xl,
            modifier = Modifier.align(Alignment.Center)
        ) {
            DropdownMenuItem(
                text = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (isFavorite) Icons.Rounded.FavoriteBorder else Icons.Rounded.Favorite,
                            contentDescription = null,
                            modifier = Modifier.size(AppSpacing.Icon.Md),
                            tint = if (isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.Sm))
                        Text(if (isFavorite) "取消收藏" else "收藏并置顶", style = AppTypography.BodyMedium)
                    }
                },
                onClick = {
                    showMenu = false
                    onFavoriteClick()
                }
            )
            DropdownMenuItem(
                text = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.QueueMusic,
                            contentDescription = null,
                            modifier = Modifier.size(AppSpacing.Icon.Md),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.Sm))
                        Text("稍后播放", style = AppTypography.BodyMedium)
                    }
                },
                onClick = {
                    showMenu = false
                    onAddToPlayLater()
                }
            )
            DropdownMenuItem(
                text = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier.size(AppSpacing.Icon.Md),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.Sm))
                        Text("下载语音", style = AppTypography.BodyMedium)
                    }
                },
                onClick = {
                    showMenu = false
                    onDownloadClick()
                }
            )
            DropdownMenuItem(
                text = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Share,
                            contentDescription = null,
                            modifier = Modifier.size(AppSpacing.Icon.Md),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.Sm))
                        Text("分享链接", style = AppTypography.BodyMedium)
                    }
                },
                onClick = {
                    showMenu = false
                    onShareClick()
                }
            )
        }
    }
}


@UnstableApi
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun GlassPlayerControlBar(
    playerState: PlayerUiState,
    playerViewModel: PlayerViewModel
) {
    val isDark = isSystemInDarkTheme()
    val glassSurface = if (isDark) AppColors.GlassSurfaceDark else AppColors.GlassSurfaceLight
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    
    var isCollapsed by remember { mutableStateOf(false) }
    var collapseToRight by remember { mutableStateOf(true) }
    var totalDragOffset by remember { mutableFloatStateOf(0f) }
    var showPlayLaterSheet by remember { mutableStateOf(false) }
    
    val transition = updateTransition(targetState = isCollapsed to collapseToRight, label = "CollapseTransition")
    
    val expandedAlpha by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 200, easing = FastOutSlowInEasing) },
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
        transitionSpec = { tween(durationMillis = 250, easing = FastOutSlowInEasing) },
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
                        onPreviousClick = { playerViewModel.skipToPrevious() },
                        onNextClick = { playerViewModel.skipToNext() },
                        onModeClick = { playerViewModel.cyclePlayMode() },
                        onListClick = { showPlayLaterSheet = true }
                    )
                }
            }

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
        }
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
private fun CollapsedPlayerBar(
    isPlaying: Boolean,
    playMode: PlayMode,
    collapseToRight: Boolean,
    onPlayPauseClick: () -> Unit,
    onModeClick: () -> Unit,
    onExpandClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val infiniteTransition = rememberInfiniteTransition(label = "GlowPulse")
    
    val glowScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GlowScale"
    )
    
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GlowAlpha"
    )
    
    val iconRotation by infiniteTransition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "IconRotation"
    )

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
                                listOf(
                                    Color(0xFF2A2A4A),
                                    Color(0xFF1A1A2E)
                                )
                            } else {
                                listOf(
                                    Color(0xFFF8F8FF),
                                    Color(0xFFE8E8F0)
                                )
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
private fun ExpandedPlayerBar(
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
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "ExpandedBarAlpha"
    )
    
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayLaterBottomSheet(
    items: List<PlayLaterItem>,
    currentlyPlayingUrl: String?,
    isPlayingFromQueue: Boolean,
    onDismiss: () -> Unit,
    onPlayItem: (PlayLaterItem) -> Unit,
    onStartQueue: () -> Unit,
    onRemoveItem: (PlayLaterItem) -> Unit,
    onClearAll: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Lg),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "稍后播放队列 (${items.size})",
                    style = AppTypography.TitleLarge
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
                ) {
                    if (items.isNotEmpty()) {
                        Button(onClick = onStartQueue) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = "播放全部")
                            Spacer(modifier = Modifier.width(AppSpacing.Xs))
                            Text("播放全部")
                        }
                        TextButton(onClick = onClearAll) {
                            Text("清空列表", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            
            if (items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("列表空空如也", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(AppSpacing.Lg)
                ) {
                    itemsIndexed(items) { index, item ->
                        val isCurrentlyPlaying = item.voiceUrl == currentlyPlayingUrl && isPlayingFromQueue
                        val isNextUp = !isCurrentlyPlaying && index == 0 && isPlayingFromQueue
                        val hasPlayed = item.hasPlayed
                        
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = AppSpacing.Xs),
                            shape = RoundedCornerShape(AppSpacing.Corner.Lg),
                            color = when {
                                isCurrentlyPlaying -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                isNextUp -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                                hasPlayed -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.3f)
                                else -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(AppSpacing.Lg)
                                    .alpha(if (hasPlayed && !isCurrentlyPlaying) 0.6f else 1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isCurrentlyPlaying) {
                                    Icon(
                                        Icons.Rounded.GraphicEq,
                                        contentDescription = "正在播放",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                } else if (isNextUp) {
                                    Icon(
                                        Icons.Rounded.SkipNext,
                                        contentDescription = "下一个",
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                } else {
                                    IconButton(
                                        onClick = { onPlayItem(item) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            if (hasPlayed) Icons.Rounded.Replay else Icons.Rounded.PlayCircle,
                                            contentDescription = if (hasPlayed) "重新播放" else "播放",
                                            tint = if (hasPlayed) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(AppSpacing.Md))
                                AsyncImage(
                                    model = item.avatarUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.width(AppSpacing.Md))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "${item.shipName} · ${item.scene}",
                                            style = AppTypography.TitleSmall,
                                            maxLines = 1
                                        )
                                        if (hasPlayed && !isCurrentlyPlaying) {
                                            Spacer(modifier = Modifier.width(AppSpacing.Xs))
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                            ) {
                                                Text(
                                                    text = "已播放",
                                                    style = AppTypography.LabelSmall,
                                                    color = MaterialTheme.colorScheme.outline,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = item.dialogue,
                                        style = AppTypography.BodySmall,
                                        color = if (hasPlayed && !isCurrentlyPlaying) 
                                            MaterialTheme.colorScheme.outline 
                                        else 
                                            MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { onRemoveItem(item) }) {
                                    Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = "移除", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
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
        animationSpec = tween(300, easing = FastOutSlowInEasing),
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
@Composable
private fun VoiceTopBar(title: String, onBack: () -> Unit, scrollBehavior: TopAppBarScrollBehavior) {
    val isDark = isSystemInDarkTheme()
    val glassSurface = if (isDark) AppColors.GlassSurfaceDark else AppColors.GlassSurfaceLight
    
    CenterAlignedTopAppBar(
        title = { Text(text = title, style = AppTypography.TitleLarge) },
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
    
    val scale by animateFloatAsState(
        targetValue = when {
            isDragging -> 1.25f
            isTapped -> 1.15f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        finishedListener = { isTapped = false },
        label = "FigureScale"
    )
    
    val alpha by animateFloatAsState(
        targetValue = if (isDragging) 0.7f else 1f,
        animationSpec = tween(250),
        label = "FigureAlpha"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        val bubbleMaxHeight = 200.dp
        val bubbleWidth = 220.dp
        val bubbleWidthPx = with(density) { bubbleWidth.toPx() }
        val textLength = dialogue?.length ?: 0
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
            Box(contentAlignment = Alignment.BottomCenter) {
                SpeechBubble(text = dialogue ?: "", isDark = isDark)
            }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(figureWidth, figureHeight)
                .scale(scale)
                .graphicsLayer { this.alpha = alpha }
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
                    detectDragGestures(
                        onDragStart = { 
                            isDragging = true
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false }
                    ) { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount.x).coerceIn(0f, screenWidth - figureWidthPx)
                        offsetY = (offsetY + dragAmount.y).coerceIn(0f, screenHeight - figureHeightPx)
                    }
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
                    fontSize = if (text.length > 80) 11.sp else 13.sp,
                    lineHeight = if (text.length > 80) 15.sp else 18.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = textColor,
                textAlign = TextAlign.Center
            )
        }
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
