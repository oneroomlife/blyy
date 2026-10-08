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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.azurlane.blyy.util.MediaDownloader
import org.intellij.lang.annotations.Language
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
    val voiceState by voiceViewModel.state.collectAsStateWithLifecycle()
    val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val playbackError by voiceViewModel.playbackError.collectAsStateWithLifecycle()

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

    // 啾信功能仅使用网络加载获取的头像
    val effectiveAvatarUrl = voiceState.avatarUrl

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

    // B6 修复：整表稳定 key 在组合层一次性计算（LazyListScope 的 forEach 不是组合上下文，
    // 不能在其中调用 remember）。key 取内容身份（场景|当前语言音频|台词）——
    // 收藏置顶排序变化时 key 不失效，行状态与动画得以保留；组内重复内容追加序号保证唯一。
    val stableKeysPerSkin by remember(voiceState.voices) {
        derivedStateOf {
            voiceState.voices.groupBy { it.skinName }.mapValues { (_, list) ->
                val seen = mutableMapOf<String, Int>()
                list.map { v ->
                    val base = "${v.scene}|${v.audioUrlCn.ifBlank { v.audioUrlJp }}|${v.dialogue}"
                    val n = seen.merge(base, 1, Int::plus)
                    if (n == 1) base else "$base#$n"
                }
            }
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
            val fileName = "${voiceState.shipName}_${voice.scene}_${voiceState.voiceLanguage.shortName}.mp3"
            when (val result = MediaDownloader.download(context, voice.getActiveAudioUrl(voiceState.voiceLanguage), fileName)) {
                is MediaDownloader.Result.Success ->
                    Toast.makeText(context, "已保存到 Download/${result.relativePath}/${result.displayName}", Toast.LENGTH_LONG).show()
                is MediaDownloader.Result.Failure ->
                    Toast.makeText(context, "下载失败: ${result.message}", Toast.LENGTH_SHORT).show()
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
        // 修复 P0：原代码先调用 toggle 再读 playerState.favorites，StateFlow 是否同步更新
        // 会导致 isFav 时序颠倒、Toast 提示与实际状态相反。改为先捕获旧状态再翻转。
        val wasFavorited = activeUrl in playerState.favorites
        playerViewModel.toggleFavorite(activeUrl)
        val isFav = !wasFavorited
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
                avatarUrl = effectiveAvatarUrl
            )
        )
        Toast.makeText(context, "已加入稍后播放", Toast.LENGTH_SHORT).show()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedBackground(avatarUrl = effectiveAvatarUrl)

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
                            effectiveAvatarUrl = effectiveAvatarUrl,
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
                        val skinKeys = stableKeysPerSkin[skinName].orEmpty()
                        stickyHeader {
                            SkinHeader(skinName)
                        }

                        itemsIndexed(
                            skinVoices,
                            key = { i, _ -> skinKeys[i] },
                            contentType = { _, _ -> "VoiceItemRow" }
                        ) { _, voice ->
                            val globalIndex = voiceIndexMap[voice] ?: -1
                            val isCurrent = playerState.currentMediaItem?.mediaId == voice.audioUrlCn ||
                            playerState.currentMediaItem?.mediaId == voice.audioUrlJp
                            val isPlaying = isCurrent && playerState.isPlaying
                            val isFavorite = voice.audioUrlCn in favorites || voice.audioUrlJp in favorites

                            // 行级回调按 voice 记忆：播放进度每秒 tick 会引发本屏重组，
                            // 未记忆时 5 个新 lambda 会导致所有可见行跟着重组（与 HomeScreen 行处理一致）
                            val onAddToPlayLater = remember(voice) { { addToPlayLater(voice) } }
                            val onDownloadClick = remember(voice) { { downloadVoice(voice) } }
                            val onFavoriteClick = remember(voice) { { toggleFavorite(voice) } }
                            val onShareClick = remember(voice) { { shareVoice(voice) } }
                            val onRowClick = remember(voice, globalIndex, playerState.playMode) {
                                { onVoiceIntent(VoiceIntent.PlayVoiceAtIndex(globalIndex, playerState.playMode)) }
                            }

                            VoiceItemRow(
                                voice = voice,
                                isCurrent = isCurrent,
                                isPlaying = isPlaying,
                                isFavorite = isFavorite,
                                onAddToPlayLater = onAddToPlayLater,
                                onClick = onRowClick,
                                onDownloadClick = onDownloadClick,
                                onFavoriteClick = onFavoriteClick,
                                onShareClick = onShareClick
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
    val haptic = rememberBlyyHaptics()
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
                    haptic(BlyyHaptic.LongPress)
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
        // 生命周期绑定：后台/非可见时暂停 produceState，避免无效 CPU/GPU 开销
        val lifecycleOwner = LocalLifecycleOwner.current
        // Throttle to ~30fps to reduce CPU/GPU load while keeping fluid motion
        val time by produceState(0f, lifecycleOwner) {
            val startTime = System.nanoTime()
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val elapsed = (System.nanoTime() - startTime) / 1_000_000_000f
                    this@produceState.value = elapsed
                    delay(33)
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
                // 渐变 Brush 提升为 remember：AGSL 着色器 Canvas 以 30fps 驱动重绘，
                // drawBehind 内每帧新建 Brush + 4 个 Color copy 会造成持续 GC 压力
                val overlayBrush = remember(isDark, surfaceContainer) {
                    Brush.verticalGradient(
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
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            drawRect(brush = overlayBrush)
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
    effectiveAvatarUrl: String,
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
                model = effectiveAvatarUrl,
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
    val haptic = rememberBlyyHaptics()
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
                            haptic(BlyyHaptic.LongPress)
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
                            haptic(BlyyHaptic.LongPress)
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceTopBar(title: String, onBack: () -> Unit, scrollBehavior: TopAppBarScrollBehavior) {
    BlyyTopBar(
        title = title,
        subtitle = "语音档案",
        onBackClick = onBack
    )
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
    val hapticFeedback = rememberBlyyHaptics()
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
                BlyySpeechBubble(text = dialogue ?: "", isDark = isDark)
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
                            hapticFeedback(BlyyHaptic.Tick)
                            onRandomPlay()
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { 
                            isDragging = true
                            hapticFeedback(BlyyHaptic.LongPress)
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
