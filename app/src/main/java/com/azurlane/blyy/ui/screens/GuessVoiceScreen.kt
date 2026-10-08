package com.azurlane.blyy.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyConfirmDialog
import com.azurlane.blyy.ui.components.BlyyHaptic
import com.azurlane.blyy.ui.components.BlyyPanel
import com.azurlane.blyy.ui.components.BlyyPrimaryButton
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.components.rememberBlyyHaptics
import com.azurlane.blyy.ui.screens.guess.GuessActionButton
import com.azurlane.blyy.ui.screens.guess.GuessAnswerCard
import com.azurlane.blyy.ui.screens.guess.GuessCorrectCard
import com.azurlane.blyy.ui.screens.guess.GuessDifficultyOption
import com.azurlane.blyy.ui.screens.guess.GuessDifficultySelector
import com.azurlane.blyy.ui.screens.guess.GuessErrorBanner
import com.azurlane.blyy.ui.screens.guess.GuessHintButton
import com.azurlane.blyy.ui.screens.guess.GuessHintsSection
import com.azurlane.blyy.ui.screens.guess.GuessInputField
import com.azurlane.blyy.ui.screens.guess.GuessScoreBanner
import com.azurlane.blyy.ui.screens.guess.GuessScoreChip
import com.azurlane.blyy.ui.screens.guess.GuessSettlementDialog
import com.azurlane.blyy.ui.screens.guess.GuessWrongCard
import com.azurlane.blyy.ui.theme.AppAnimation
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.viewmodel.GuessGameUiState
import com.azurlane.blyy.viewmodel.GuessResult
import com.azurlane.blyy.viewmodel.GuessShipViewModel
import com.azurlane.blyy.viewmodel.PlayerViewModel
import com.azurlane.blyy.viewmodel.VoiceDifficulty

@UnstableApi
@Composable
fun GuessByVoiceScreen(
    viewModel: GuessShipViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onHistory: () -> Unit = {},
    playerViewModel: PlayerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()

    var lastDialogueId by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        viewModel.startVoiceGame()
    }

    val questionVoiceUrl = state.currentVoice?.audioUrl
    val currentDialogueId = state.currentDialogueId

    // 新题目自动播放一次（旧版在此处还会用 Toast 弹台词，现改为播放卡片内常驻展示）
    LaunchedEffect(questionVoiceUrl, currentDialogueId) {
        if (!questionVoiceUrl.isNullOrEmpty() && currentDialogueId != lastDialogueId) {
            playerViewModel.playSingleVoice(questionVoiceUrl)
            lastDialogueId = currentDialogueId
        }
    }

    // 退出二次确认对话框状态：仅在结算弹窗中点击"退出"时触发
    var showExitConfirm by remember { mutableStateOf(false) }
    val haptic = rememberBlyyHaptics()

    // 作答结果触觉反馈：答对确认、答错重震
    LaunchedEffect(state.lastResult) {
        when (state.lastResult) {
            GuessResult.CORRECT -> haptic(BlyyHaptic.Confirm)
            GuessResult.WRONG -> haptic(BlyyHaptic.Heavy)
            else -> Unit
        }
    }

    if (state.showSettlement) {
        GuessSettlementDialog(
            score = state.score,
            onDismiss = { viewModel.hideSettlement() },
            onExit = {
                // 不直接退出，先弹出二次确认对话框，防止误操作导致历史记录提前生成
                showExitConfirm = true
            },
            onContinue = { viewModel.hideSettlement() }
        )
    }

    // 退出二次确认对话框：用户确认后才保存历史记录并退出
    if (showExitConfirm) {
        BlyyConfirmDialog(
            title = "确认退出",
            message = "退出后本次作答结果将保存到历史记录，且无法继续作答。确认退出吗？",
            confirmText = "确认退出",
            dismissText = "继续作答",
            onConfirm = {
                showExitConfirm = false
                // 确认退出：保存历史记录（首次 insert / 继续后 update）后退出
                viewModel.confirmExitAndSave()
                viewModel.hideSettlement()
                onBack()
            },
            onDismiss = { showExitConfirm = false }
        )
    }

    ModernGuessVoiceContent(
        state = state,
        isVoicePlaying = playerState.isPlaying,
        onBack = {
            viewModel.showSettlement()
        },
        onHistory = onHistory,
        onInputChange = viewModel::onInputChanged,
        onSubmit = {
            // 空输入提交只会设置 errorMessage（不产生作答结果），不应给确认触觉
            if (state.inputText.isNotBlank()) {
                haptic(BlyyHaptic.Confirm)
            }
            viewModel.checkAnswer()
        },
        onNext = {
            haptic(BlyyHaptic.Tick)
            // 统一调用 ViewModel 的 goToNextQuestion，由 VM 内部读取最新状态计数
            viewModel.goToNextQuestion()
        },
        onReplay = {
            // 每次点击随机播放该舰娘的另一条语音（唯一语音时回退重播当前条）。
            // 选中后 VM 更新 currentVoice/currentDialogueId，由上方监听 dialogueId 的
            // LaunchedEffect 统一触发播放——若在此回调里直接 playSingleVoice 会与
            // 自动播放叠加成双重播放。EASY 模式台词卡随新语音同步切换。
            viewModel.playRandomVoiceForCurrentShip { _, _ -> }
        },
        onRequestHint = viewModel::requestHint,
        onShowAnswer = viewModel::showAnswer,
        onShowSettlement = viewModel::showSettlement,
        onDifficultyChange = viewModel::setVoiceDifficulty
    )
}

@Composable
private fun ModernGuessVoiceContent(
    state: GuessGameUiState,
    isVoicePlaying: Boolean,
    onBack: () -> Unit,
    onHistory: () -> Unit,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onNext: () -> Unit,
    onReplay: () -> Unit,
    onRequestHint: () -> Unit,
    onShowAnswer: () -> Unit,
    onShowSettlement: () -> Unit,
    onDifficultyChange: (VoiceDifficulty) -> Unit
) {
    val scrollState = rememberScrollState()
    val isEasy = state.voiceDifficulty == VoiceDifficulty.EASY
    // 答对/已揭示答案后，主操作切换为"下一题"（主次按钮互换引导下一步）
    val answered = state.lastResult == GuessResult.CORRECT || state.showAnswer

    AdaptiveScreenBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            BlyyTopBar(
                title = "听音识舰娘",
                subtitle = "聆听语音，猜出舰娘",
                onBackClick = onBack,
                actions = {
                    IconButton(onClick = onHistory) {
                        Icon(
                            Icons.Rounded.History,
                            contentDescription = "历史记录",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    GuessScoreChip(totalScore = state.score.totalScore)
                }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppSpacing.Lg)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Md),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                GuessDifficultySelector(
                    current = state.voiceDifficulty,
                    options = listOf(
                        GuessDifficultyOption(
                            value = VoiceDifficulty.EASY,
                            title = "简单模式",
                            description = "显示台词和提示",
                            icon = Icons.Rounded.RecordVoiceOver
                        ),
                        GuessDifficultyOption(
                            value = VoiceDifficulty.HARD,
                            title = "困难模式",
                            description = "不显示台词和提示",
                            icon = Icons.Rounded.VisibilityOff
                        )
                    ),
                    onSelect = onDifficultyChange
                )

                VoicePlayerCard(
                    isPlaying = isVoicePlaying,
                    onReplay = onReplay,
                    hasVoice = state.currentVoice != null,
                    dialogue = if (isEasy) state.currentVoice?.dialogue else null
                )

                GuessScoreBanner(score = state.currentQuestionScore)

                if (isEasy && state.hints.isNotEmpty()) {
                    GuessHintsSection(hints = state.hints)
                }

                if (isEasy && state.lastResult != GuessResult.CORRECT && !state.showAnswer) {
                    GuessHintButton(
                        isLoading = state.isLoadingHint,
                        hintCount = state.hints.size,
                        noMoreHints = state.noMoreHints,
                        onRequestHint = onRequestHint
                    )
                }

                GuessErrorBanner(message = state.errorMessage)

                AnimatedVisibility(
                    visible = state.lastResult == GuessResult.CORRECT,
                    enter = fadeIn() + scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)),
                    exit = fadeOut() + scaleOut()
                ) {
                    GuessCorrectCard(
                        score = state.currentQuestionScore,
                        rewardImageUrl = state.rewardImageUrl
                    )
                }

                AnimatedVisibility(
                    visible = state.showAnswer && state.lastResult == GuessResult.SKIPPED,
                    enter = fadeIn() + slideInHorizontally(),
                    exit = fadeOut() + slideOutHorizontally()
                ) {
                    GuessAnswerCard(
                        shipName = state.currentShip?.name ?: "",
                        rewardImageUrl = state.rewardImageUrl
                    )
                }

                AnimatedVisibility(
                    visible = state.lastResult == GuessResult.WRONG,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut()
                ) {
                    GuessWrongCard()
                }

                GuessInputField(
                    value = state.inputText,
                    onValueChange = onInputChange,
                    onSubmit = onSubmit,
                    enabled = !answered
                )

                if (!answered) {
                    GuessActionButton(
                        text = "显示答案",
                        icon = Icons.Rounded.Visibility,
                        onClick = onShowAnswer,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
                ) {
                    GuessActionButton(
                        text = "下一题",
                        icon = Icons.Rounded.SkipNext,
                        onClick = onNext,
                        modifier = Modifier.weight(1f),
                        primary = answered
                    )
                    GuessActionButton(
                        text = "提交答案",
                        icon = Icons.Rounded.Check,
                        onClick = onSubmit,
                        modifier = Modifier.weight(1f),
                        primary = !answered,
                        enabled = !answered
                    )
                }

                GuessActionButton(
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

/**
 * 播放卡片 — BlyyPanel 容器 + BlyyPrimaryButton 播放键 + 台词面板
 *
 * 要点：
 * - [isPlaying] 绑定 PlayerViewModel 的真实播放状态（旧版误绑 isLoadingHint，
 *   导致"播放中…"跟随提示加载而非播放）
 * - 播放键语义为"换一条语音"：每次点击随机播放该舰娘的另一条语音，
 *   EASY 模式台词在卡内随新语音同步切换
 * - 播放中耳机图标以呼吸光晕反馈，静止时不跑动画
 */
@Composable
private fun VoicePlayerCard(
    isPlaying: Boolean,
    onReplay: () -> Unit,
    hasVoice: Boolean,
    dialogue: String?
) {
    val scale by animateFloatAsState(
        targetValue = if (isPlaying) 1.02f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "scale"
    )

    // 播放中：呼吸光晕；静止：固定微光（不启动无限动画，避免空耗帧）
    val breathingAlpha = if (isPlaying) {
        val infiniteTransition = rememberInfiniteTransition(label = "voicePulse")
        val pulse by infiniteTransition.animateFloat(
            initialValue = 0.25f,
            targetValue = 0.55f,
            animationSpec = infiniteRepeatable(tween(1500, easing = AppAnimation.Easings.EaseInOutSine)),
            label = "pulseAlpha"
        )
        pulse
    } else {
        0.25f
    }

    BlyyPanel(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale),
        accentColor = MaterialTheme.colorScheme.primary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Lg)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = breathingAlpha),
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
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Column {
                    Text(
                        if (hasVoice) "聆听这段语音" else "正在准备题目",
                        style = AppTypography.TitleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (hasVoice) "每次点击随机播放该舰娘的不同语音" else "语音加载完成后即可播放",
                        style = AppTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }

            BlyyPrimaryButton(
                text = when {
                    isPlaying -> "播放中…"
                    hasVoice -> "换一条语音"
                    else -> "等待题目加载"
                },
                icon = if (isPlaying) Icons.Rounded.MusicNote else Icons.Rounded.PlayArrow,
                onClick = onReplay,
                enabled = hasVoice,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(AppSpacing.Game.Button.Height)
            )

            if (!dialogue.isNullOrBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(AppSpacing.Corner.Md),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AppSpacing.Md),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
                        ) {
                            Icon(
                                Icons.Rounded.RecordVoiceOver,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                "台词",
                                style = AppTypography.LabelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            dialogue,
                            style = AppTypography.BodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }
    }
}
