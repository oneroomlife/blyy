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
import com.azurlane.blyy.data.local.PlayLaterItem
import com.azurlane.blyy.data.model.VoiceLanguage
import com.azurlane.blyy.data.model.VoiceLine
import com.azurlane.blyy.ui.components.BlyyBottomSheet
import com.azurlane.blyy.ui.components.BlyyErrorState
import com.azurlane.blyy.ui.components.BlyyLoadingState
import com.azurlane.blyy.ui.components.BlyyPanel
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
    val playbackError by voiceViewModel.playbackError.collectAsState()

    VoiceScreenContent(
        voiceState = voiceState,
        playerState = playerState,
        onVoiceIntent = voiceViewModel::onIntent,
        playerViewModel = playerViewModel,
        onBack = onBack,
        sharedTransitionScope = sharedTransitionScope,
        animatedContentScope = animatedContentScope,
        playbackError = playbackError,
        onClearPlaybackError = { voiceViewModel.clearPlaybackError() }
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
    animatedContentScope: AnimatedContentScope,
    playbackError: String?,
    onClearPlaybackError: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(playbackError) {
        playbackError?.let {
            snackbarHostState.showSnackbar(it)
            onClearPlaybackError()
        }
    }
    
    val groupedVoices by remember(voiceState.voices) {
        derivedStateOf {
            voiceState.voices.groupBy { it.skinName }
        }
    }

    // Precompute voice -> global index map to avoid O(n) indexOf per item (was O(n²) total)
    val voiceIndexMap by remember(voiceState.voices) {
        derivedStateOf {
            voiceState.voices.withIndex().associate { (index, voice) -> voice to index }
        }
    }
    
    val favorites = playerState.favorites
    
    fun downloadVoice(voice: VoiceLine) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val url = URL(voice.getActiveAudioUrl(voiceState.voiceLanguage))
                    val fileName = "${voiceState.shipName}_${voice.scene}_${voiceState.voiceLanguage.shortName}.mp3"
                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    )
                    val file = File(downloadsDir, "BLYY/$fileName")
                    file.parentFile?.mkdirs()
                    
                    val referer = when {
                        url.host.contains("gamekee") -> "https://www.gamekee.com/"
                        url.host.contains("biligame") || url.host.contains("hdslb") -> "https://wiki.biligame.com/"
                        else -> "https://www.google.com/"
                    }
                    (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20000
                        readTimeout = 20000
                        setRequestProperty("Referer", referer)
                    }.inputStream.use { input ->
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
        val activeUrl = voice.getActiveAudioUrl(voiceState.voiceLanguage)
        playerViewModel.toggleFavorite(activeUrl)
        val isFav = activeUrl !in playerState.favorites
        Toast.makeText(context, if (isFav) "已收藏并置顶" else "已取消收藏", Toast.LENGTH_SHORT).show()
    }
    
    fun addToPlayLater(voice: VoiceLine) {
        val activeUrl = voice.getActiveAudioUrl(voiceState.voiceLanguage)
        playerViewModel.addToPlayLater(
            PlayLaterItem(
                voiceUrl = activeUrl,
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
                        playerViewModel = playerViewModel,
                        onVoiceIntent = onVoiceIntent
                    )
                }
            }
        ) { innerPadding ->
            
            if (voiceState.error != null && voiceState.voices.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BlyyErrorState(
                        message = voiceState.error,
                        onRetry = {
                            if (voiceState.studentLink.isNotEmpty()) {
                                onVoiceIntent(
                                    VoiceIntent.LoadStudentVoices(
                                        voiceState.shipName,
                                        voiceState.avatarUrl,
                                        voiceState.studentLink
                                    )
                                )
                            } else {
                                onVoiceIntent(VoiceIntent.LoadVoices(voiceState.shipName, voiceState.avatarUrl))
                            }
                        }
                    )
                }
            } else if (voiceState.isLoading && voiceState.voices.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
                    ) {
                        BlyyLoadingState()
                        // 重试状态提示
                        if (voiceState.isRetrying) {
                            Text(
                                text = "正在重试加载... (${voiceState.retryCount}/3)",
                                style = AppTypography.BodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (voiceState.isCacheHit) {
                            Text(
                                text = "从缓存加载",
                                style = AppTypography.LabelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
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

                    item {
                        val hasDualAudio = voiceState.voices.any { it.hasDualAudio }
                        AnimatedVisibility(
                            visible = hasDualAudio,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            VoiceLanguageSwitch(
                                currentLanguage = voiceState.voiceLanguage,
                                onLanguageChange = { lang ->
                                    onVoiceIntent(VoiceIntent.SetVoiceLanguage(lang))
                                }
                            )
                        }
                    }

                    groupedVoices.forEach { (skinName, skinVoices) ->
                        stickyHeader {
                            SkinHeader(skinName)
                        }

                        itemsIndexed(skinVoices, key = { index, v -> "${index}_${v.audioUrlCn.ifBlank { v.audioUrlJp }}" }) { _, voice ->
                            val globalIndex = voiceIndexMap[voice] ?: -1
                            val isCurrent = playerState.currentMediaItem?.mediaId == voice.audioUrlCn || 
                            playerState.currentMediaItem?.mediaId == voice.audioUrlJp
                            val isPlaying = isCurrent && playerState.isPlaying
                            val isFavorite = voice.audioUrlCn in favorites || voice.audioUrlJp in favorites

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
private fun VoiceLanguageSwitch(
    currentLanguage: VoiceLanguage,
    onLanguageChange: (VoiceLanguage) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val isDark = LocalIsDark.current

    val cnScale by animateFloatAsState(
        targetValue = if (currentLanguage == VoiceLanguage.CN) 1.05f else 1f,
        animationSpec = AppAnimation.Springs.Snappy,
        label = "cnScale"
    )
    val jpScale by animateFloatAsState(
        targetValue = if (currentLanguage == VoiceLanguage.JP) 1.05f else 1f,
        animationSpec = AppAnimation.Springs.Snappy,
        label = "jpScale"
    )

    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val glassBorder = if (isDark) AppColors.GlassBorderDark else AppColors.GlassBorderLight

    val buttonHeight = 32.dp
    val buttonShape = RoundedCornerShape(AppSpacing.Corner.Md)

    @Composable
    fun LanguageButton(
        label: String,
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        isSelected: Boolean,
        scale: Float,
        onClick: () -> Unit
    ) {
        Box(
            modifier = Modifier
                .height(buttonHeight)
                .widthIn(min = 78.dp)
                .scale(scale)
                .clip(buttonShape)
                .then(
                    if (isSelected) {
                        Modifier
                            .shadow(
                                elevation = AppSpacing.Elevation.Sm,
                                shape = buttonShape,
                                ambientColor = primary.copy(alpha = 0.18f),
                                spotColor = primary.copy(alpha = 0.25f)
                            )
                            .background(
                                brush = Brush.linearGradient(
                                    colors = listOf(primary, primary.copy(alpha = 0.82f))
                                )
                            )
                    } else {
                        Modifier
                            .background(surfaceVariant.copy(alpha = 0.45f))
                            .border(
                                width = AppSpacing.Border.Thin,
                                color = glassBorder.copy(alpha = 0.22f),
                                shape = buttonShape
                            )
                    }
                )
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = AppSpacing.Sm, vertical = 5.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSelected) Color.White else onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(AppSpacing.Icon.Sm)
                )
                Spacer(modifier = Modifier.width(AppSpacing.Xs))
                Text(
                    text = label,
                    style = AppTypography.LabelMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) Color.White else onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Screen.Horizontal, vertical = AppSpacing.Sm),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LanguageButton(
                label = "中配",
                icon = Icons.Rounded.Translate,
                isSelected = currentLanguage == VoiceLanguage.CN,
                scale = cnScale,
                onClick = { onLanguageChange(VoiceLanguage.CN) }
            )

            LanguageButton(
                label = "日配",
                icon = Icons.Rounded.GTranslate,
                isSelected = currentLanguage == VoiceLanguage.JP,
                scale = jpScale,
                onClick = { onLanguageChange(VoiceLanguage.JP) }
            )
        }
    }
}


@Composable
private fun AnimatedBackground(avatarUrl: String) {
    val isDark = LocalIsDark.current
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = remember { RuntimeShader(FLUID_SHADER_VOICE) }
        // Throttle to ~30fps to reduce CPU/GPU load while keeping fluid motion
        val time by produceState(0f) {
            val startTime = System.nanoTime()
            while (true) {
                val elapsed = (System.nanoTime() - startTime) / 1_000_000_000f
                value = elapsed
                delay(33)
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
    val headerContent: @Composable () -> Unit = {
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

    if (LocalUiStyle.current.isCommandCenter()) {
        BlyyPanel(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Xs),
            chamfer = 8.dp
        ) {
            headerContent()
        }
    } else {
        headerContent()
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

    val rowContent: @Composable () -> Unit = {
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
            Column(modifier = Modifier.weight(1f)) {
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Xs)
    ) {
        if (LocalUiStyle.current.isCommandCenter()) {
            BlyyPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showMenu = true
                        }
                    ),
                accentColor = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                chamfer = 8.dp,
                content = rowContent
            )
        } else {
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
                rowContent()
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
    playerViewModel: PlayerViewModel,
    onVoiceIntent: (VoiceIntent) -> Unit
) {
    val isDark = LocalIsDark.current
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
                        onPreviousClick = { onVoiceIntent(VoiceIntent.SkipPrevious) },
                        onNextClick = { onVoiceIntent(VoiceIntent.SkipNext) },
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
    val isDark = LocalIsDark.current
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
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val accentColor = MaterialTheme.colorScheme.primary

    BlyyBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
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
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
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
                            animationSpec = infiniteRepeatable(
                                animation = tween(800, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
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
                                modifier = Modifier.size(28.dp)
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
                modifier = Modifier.size(28.dp)
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
    BlyyTopBar(
        title = title,
        subtitle = "语音档案",
        onBackClick = onBack
    )
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
    val isDark = LocalIsDark.current
    
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
                        style = AppTypography.LabelSmallBold,
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
                style = AppTypography.BodySmallMedium.copy(
                    fontSize = if (text.length > 80) 11.sp else 13.sp,
                    lineHeight = if (text.length > 80) 15.sp else 18.sp
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
