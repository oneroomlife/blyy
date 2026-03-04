package com.example.blyy.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.example.blyy.ui.theme.AppColors
import com.example.blyy.ui.theme.AppSpacing
import com.example.blyy.ui.theme.AppTypography
import androidx.media3.common.util.UnstableApi
import com.example.blyy.viewmodel.GuessGameUiState
import com.example.blyy.viewmodel.GuessResult
import com.example.blyy.viewmodel.GuessShipViewModel
import com.example.blyy.viewmodel.PlayerViewModel
import com.example.blyy.viewmodel.GameScore

@UnstableApi
@Composable
fun GuessByVoiceScreen(
    viewModel: GuessShipViewModel = hiltViewModel(),
    onBack: () -> Unit,
    playerViewModel: PlayerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.startVoiceGame()
    }

    val questionVoiceUrl = state.currentVoice?.audioUrl
    LaunchedEffect(questionVoiceUrl) {
        if (!questionVoiceUrl.isNullOrEmpty()) {
            playerViewModel.playSingleVoice(questionVoiceUrl)
        }
    }

    if (state.showSettlement) {
        ModernVoiceSettlementDialog(
            score = state.score,
            onDismiss = { viewModel.hideSettlement() },
            onExit = {
                viewModel.hideSettlement()
                onBack()
            },
            onContinue = { viewModel.hideSettlement() }
        )
    }

    ModernGuessVoiceContent(
        state = state,
        onBack = {
            viewModel.showSettlement()
        },
        onInputChange = viewModel::onInputChanged,
        onSubmit = { viewModel.checkAnswer() },
        onNext = {
            if (state.lastResult == GuessResult.SKIPPED) {
                viewModel.skipToNextQuestion()
            } else {
                viewModel.clearResult()
                viewModel.loadNextQuestion()
            }
        },
        onReplay = {
            viewModel.playRandomVoiceForCurrentShip { url ->
                playerViewModel.playSingleVoice(url)
            }
        },
        onRequestHint = viewModel::requestHint,
        onShowAnswer = viewModel::showAnswer,
        onShowSettlement = viewModel::showSettlement
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModernGuessVoiceContent(
    state: GuessGameUiState,
    onBack: () -> Unit,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onNext: () -> Unit,
    onReplay: () -> Unit,
    onRequestHint: () -> Unit,
    onShowAnswer: () -> Unit,
    onShowSettlement: () -> Unit
) {
    val scrollState = rememberScrollState()
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    val gradientColors = if (isDark) {
        listOf(
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
            MaterialTheme.colorScheme.surface
        )
    } else {
        listOf(
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f),
            MaterialTheme.colorScheme.surface
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = Brush.verticalGradient(colors = gradientColors))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f),
                                            Color.Transparent
                                        )
                                    ),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                "听音识舰娘",
                                style = AppTypography.TitleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "聆听语音，猜出舰娘",
                                style = AppTypography.LabelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                CircleShape
                            )
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                actions = {
                    VoiceScoreChip(totalScore = state.score.totalScore)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppSpacing.Lg)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Md),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                VoicePlayerCard(
                    isPlaying = state.isLoadingHint,
                    onReplay = onReplay,
                    hasVoice = state.currentVoice != null
                )

                VoiceScoreBanner(score = state.currentQuestionScore)

                if (state.hints.isNotEmpty()) {
                    HintsSection(hints = state.hints.map { it.label to it.value })
                }

                if (state.lastResult != GuessResult.CORRECT && !state.showAnswer) {
                    HintButton(
                        isLoading = state.isLoadingHint,
                        hintCount = state.hints.size,
                        noMoreHints = state.noMoreHints,
                        onRequestHint = onRequestHint
                    )
                }

                AnimatedVisibility(
                    visible = state.lastResult == GuessResult.CORRECT,
                    enter = fadeIn() + scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)),
                    exit = fadeOut() + scaleOut()
                ) {
                    VoiceCorrectCard(
                        score = state.currentQuestionScore,
                        rewardImageUrl = state.rewardImageUrl
                    )
                }

                AnimatedVisibility(
                    visible = state.showAnswer && state.lastResult == GuessResult.SKIPPED,
                    enter = fadeIn() + slideInHorizontally(),
                    exit = fadeOut() + slideOutHorizontally()
                ) {
                    VoiceAnswerCard(
                        shipName = state.currentShip?.name ?: "",
                        rewardImageUrl = state.rewardImageUrl
                    )
                }

                AnimatedVisibility(
                    visible = state.lastResult == GuessResult.WRONG,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut()
                ) {
                    VoiceWrongCard()
                }

                VoiceInputField(
                    value = state.inputText,
                    onValueChange = onInputChange,
                    onSubmit = onSubmit,
                    enabled = state.lastResult != GuessResult.CORRECT && !state.showAnswer
                )

                if (!state.showAnswer && state.lastResult != GuessResult.CORRECT) {
                    ModernVoiceOutlinedButton(
                        text = "显示答案",
                        icon = Icons.Rounded.Visibility,
                        onClick = onShowAnswer,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ModernVoiceOutlinedButton(
                        text = "下一题",
                        icon = Icons.Rounded.SkipNext,
                        onClick = onNext,
                        modifier = Modifier.weight(1f)
                    )
                    ModernVoicePrimaryButton(
                        text = "提交答案",
                        icon = Icons.Rounded.Check,
                        onClick = onSubmit,
                        enabled = state.lastResult != GuessResult.CORRECT && !state.showAnswer,
                        modifier = Modifier.weight(1f)
                    )
                }

                ModernVoiceOutlinedButton(
                    text = "结算退出",
                    icon = null,
                    onClick = onShowSettlement,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(AppSpacing.Lg))
            }
        }
    }
}

@Composable
private fun VoiceScoreChip(totalScore: Int) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(Icons.Rounded.Star, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
            Text(
                "$totalScore",
                style = AppTypography.TitleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun VoicePlayerCard(
    isPlaying: Boolean,
    onReplay: () -> Unit,
    hasVoice: Boolean
) {
    val scale by animateFloatAsState(
        targetValue = if (isPlaying) 1.02f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "scale"
    )

    val pulseAlpha by animateFloatAsState(
        targetValue = if (isPlaying) 0.6f else 0.3f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "pulseAlpha"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.secondary.copy(alpha = pulseAlpha),
                                    Color.Transparent
                                )
                            ),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Headphones,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Column {
                    Text(
                        "播放语音,点击“下一题”加载",
                        style = AppTypography.TitleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "根据语音猜出舰娘名字",
                        style = AppTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                }
            }

            Button(
                onClick = onReplay,
                enabled = hasVoice,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 4.dp,
                    pressedElevation = 2.dp
                )
            ) {
                if (isPlaying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondary
                    )
                } else {
                    Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isPlaying) "播放中..." else "播放语音",
                    style = AppTypography.LabelLarge
                )
            }
        }
    }
}

@Composable
private fun VoiceScoreBanner(score: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Rounded.Star, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(14.dp))
            Text("本题可得 $score 分", style = AppTypography.BodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

@Composable
private fun HintsSection(hints: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        hints.forEachIndexed { index, (label, value) ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Lightbulb,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Text(
                            "提示 #${index + 1}",
                            style = AppTypography.LabelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        label,
                        style = AppTypography.LabelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        value,
                        style = AppTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun HintButton(
    isLoading: Boolean,
    hintCount: Int,
    noMoreHints: Boolean,
    onRequestHint: () -> Unit
) {
    OutlinedButton(
        onClick = onRequestHint,
        enabled = !isLoading && !noMoreHints,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.tertiary
        )
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.tertiary
            )
        } else {
            Icon(Icons.Rounded.Lightbulb, null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                noMoreHints -> "已无更多提示"
                hintCount == 0 -> "获取提示"
                else -> "再获取提示"
            },
            style = AppTypography.LabelLarge
        )
    }
}

@Composable
private fun VoiceCorrectCard(score: Int, rewardImageUrl: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
            shadowElevation = 6.dp
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    AppColors.Favorite.Gold.copy(alpha = 0.3f),
                                    Color.Transparent
                                )
                            ),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.EmojiEvents,
                        contentDescription = null,
                        tint = AppColors.Favorite.Gold,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Column {
                    Text(
                        "回答正确！",
                        style = AppTypography.TitleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "+$score 分",
                        style = AppTypography.HeadlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        if (rewardImageUrl != null) {
            AsyncImage(
                model = rewardImageUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(20.dp))
                    .shadow(12.dp, RoundedCornerShape(20.dp)),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun VoiceAnswerCard(shipName: String, rewardImageUrl: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f),
                                    Color.Transparent
                                )
                            ),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Lightbulb,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column {
                    Text(
                        "答案：$shipName",
                        style = AppTypography.TitleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "本题不得分",
                        style = AppTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }
        if (rewardImageUrl != null) {
            AsyncImage(
                model = rewardImageUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(20.dp))
                    .shadow(12.dp, RoundedCornerShape(20.dp)),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun VoiceWrongCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "×",
                    style = AppTypography.TitleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Text(
                "好像不太对，再想想？",
                style = AppTypography.BodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun VoiceInputField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    enabled: Boolean
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .shadow(6.dp, RoundedCornerShape(20.dp)),
        placeholder = {
            Text(
                "输入舰娘名字...",
                style = AppTypography.BodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        },
        singleLine = true,
        shape = RoundedCornerShape(20.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.secondary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            cursorColor = MaterialTheme.colorScheme.secondary
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Text),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        enabled = enabled,
        textStyle = AppTypography.BodyMedium.copy(fontWeight = FontWeight.Medium)
    )
}

@Composable
private fun ModernVoicePrimaryButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.98f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "buttonScale"
    )

    Button(
        onClick = onClick,
        modifier = modifier
            .height(52.dp)
            .scale(scale),
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 4.dp,
            pressedElevation = 2.dp
        )
    ) {
        icon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = AppTypography.LabelLarge)
    }
}

@Composable
private fun ModernVoiceOutlinedButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.secondary
        )
    ) {
        icon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = AppTypography.LabelLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModernVoiceSettlementDialog(
    score: GameScore,
    onDismiss: () -> Unit,
    onExit: () -> Unit,
    onContinue: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        icon = {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                AppColors.Favorite.Gold.copy(alpha = 0.3f),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.EmojiEvents,
                    contentDescription = null,
                    tint = AppColors.Favorite.Gold,
                    modifier = Modifier.size(36.dp)
                )
            }
        },
        title = {
            Text(
                "游戏结算",
                style = AppTypography.HeadlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "总得分",
                            style = AppTypography.LabelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${score.totalScore}",
                            style = AppTypography.DisplayMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        if (score.totalPossibleScore > 0) {
                            Text(
                                "满分 ${score.totalPossibleScore} 分",
                                style = AppTypography.BodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VoiceStatItemContent("答对", "${score.correctAnswers}/${score.totalQuestions}", "${(score.accuracy * 100).toInt()}%")
                    VoiceStatItemContent("跳过", "${score.skippedQuestions}", "-")
                    VoiceStatItemContent("提示", "${score.hintsUsedTotal}", "-")
                }

                if (score.totalQuestions > 0) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("平均得分", style = AppTypography.LabelMedium)
                            Text(
                                String.format("%.1f", score.averageScore),
                                style = AppTypography.TitleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onContinue,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text("继续游戏", style = AppTypography.LabelLarge)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onExit,
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("退出", style = AppTypography.LabelLarge)
            }
        }
    )
}

@Composable
private fun VoiceStatItemContent(label: String, value: String, subValue: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = AppTypography.LabelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = AppTypography.TitleMedium, fontWeight = FontWeight.Bold)
            Text(subValue, style = AppTypography.LabelSmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

private fun Color.luminance(): Float {
    val r = this.red
    val g = this.green
    val b = this.blue
    return 0.299f * r + 0.587f * g + 0.114f * b
}
