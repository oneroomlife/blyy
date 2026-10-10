package com.azurlane.blyy.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.ImageNotSupported
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyConfirmDialog
import com.azurlane.blyy.ui.components.BlyyHaptic
import com.azurlane.blyy.ui.components.rememberBlyyHaptics
import com.azurlane.blyy.ui.components.BlyyTopBar
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
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.DepthLayer
import com.azurlane.blyy.ui.theme.blyyDepth
import com.azurlane.blyy.viewmodel.CropRegion
import com.azurlane.blyy.viewmodel.GuessGameUiState
import com.azurlane.blyy.viewmodel.GuessResult
import com.azurlane.blyy.viewmodel.GuessShipViewModel
import com.azurlane.blyy.viewmodel.ImageDifficulty
import com.azurlane.blyy.viewmodel.PlayerViewModel

@UnstableApi
@Composable
fun GuessByImageScreen(
    viewModel: GuessShipViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onHistory: () -> Unit = {},
    playerViewModel: PlayerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.startImageGame()
    }

    val currentVoiceUrl = state.currentVoice?.audioUrl
    LaunchedEffect(currentVoiceUrl, state.lastResult) {
        if (state.lastResult == GuessResult.CORRECT && !currentVoiceUrl.isNullOrEmpty()) {
            playerViewModel.playSingleVoice(currentVoiceUrl)
        }
    }

    // 退出二次确认对话框状态：仅在结算弹窗中点击"退出"时触发
    var showExitConfirm by remember { mutableStateOf(false) }
    val haptic = rememberBlyyHaptics()

    // 作答结果触觉反馈：答对确认、答错重震、跳过轻点
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
                viewModel.confirmExitAndSave()
                viewModel.hideSettlement()
                onBack()
            },
            onDismiss = { showExitConfirm = false }
        )
    }

    ModernGuessImageContent(
        state = state,
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
        onReplayVoice = {
            val url = state.currentVoice?.audioUrl
            if (!url.isNullOrEmpty()) {
                playerViewModel.playSingleVoice(url)
            }
        },
        onRequestHint = viewModel::requestHint,
        onDifficultyChange = viewModel::setDifficulty,
        onShowAnswer = viewModel::showAnswer,
        onShowSettlement = viewModel::showSettlement
    )
}

@Composable
private fun ModernGuessImageContent(
    state: GuessGameUiState,
    onBack: () -> Unit,
    onHistory: () -> Unit,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onNext: () -> Unit,
    onReplayVoice: () -> Unit,
    onRequestHint: () -> Unit,
    onDifficultyChange: (ImageDifficulty) -> Unit,
    onShowAnswer: () -> Unit,
    onShowSettlement: () -> Unit
) {
    val scrollState = rememberScrollState()
    val isEasy = state.difficulty == ImageDifficulty.EASY
    // 答对/已揭示答案后，主操作切换为"下一题"（主次按钮互换引导下一步）
    val answered = state.lastResult == GuessResult.CORRECT || state.showAnswer

    AdaptiveScreenBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            BlyyTopBar(
                title = "看图识舰娘",
                subtitle = "观察图片，猜出舰娘",
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
                    current = state.difficulty,
                    options = listOf(
                        GuessDifficultyOption(
                            value = ImageDifficulty.EASY,
                            title = "简单模式",
                            description = "显示完整立绘",
                            icon = Icons.Rounded.Fullscreen
                        ),
                        GuessDifficultyOption(
                            value = ImageDifficulty.HARD,
                            title = "困难模式",
                            description = "只显示部分立绘",
                            icon = Icons.Rounded.Crop
                        )
                    ),
                    onSelect = onDifficultyChange
                )

                if (state.difficulty == ImageDifficulty.HARD) {
                    GuessHintBanner(
                        text = "困难模式：只显示部分立绘",
                        icon = Icons.Rounded.Crop
                    )
                }

                GuessScoreBanner(score = state.currentQuestionScore)

                ImageCard(
                    imageUrl = state.currentImageUrl,
                    cropRegion = state.cropRegion,
                    difficulty = state.difficulty,
                    isLoading = state.isLoadingHint,
                    // 题目生成失败（重试耗尽/同步中）时传错误信息，卡片内区分"加载中"与"失败"（B7 修复）
                    errorMessage = state.errorMessage,
                    showFullImage = state.showAnswer && state.difficulty == ImageDifficulty.HARD
                )

                // 提示系统 — 与听音玩法对齐（VM 早已支持，旧版界面无入口）
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
                        rewardImageUrl = null,
                        onReplayVoice = onReplayVoice
                    )
                }

                AnimatedVisibility(
                    visible = state.showAnswer && state.lastResult == GuessResult.SKIPPED,
                    enter = fadeIn(animationSpec = tween(300)) + slideInHorizontally(animationSpec = tween(300)),
                    exit = fadeOut(animationSpec = tween(200)) + slideOutHorizontally(animationSpec = tween(200))
                ) {
                    GuessAnswerCard(shipName = state.currentShip?.name ?: "")
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

@Composable
private fun GuessHintBanner(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppSpacing.Corner.Md),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier.padding(AppSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
            Text(text, style = AppTypography.BodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

@Composable
private fun ImageCard(
    imageUrl: String?,
    cropRegion: CropRegion?,
    difficulty: ImageDifficulty,
    isLoading: Boolean,
    errorMessage: String? = null,
    showFullImage: Boolean = false
) {
    val scale by animateFloatAsState(
        targetValue = if (isLoading) 0.95f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "scale"
    )

    // 修复 P0：原 targetValue 两分支均为 1f（死代码，动画无效）。
    // 改为揭示全图时轻微放大（1.05f），强化"揭示"的视觉反馈。
    val imageScale by animateFloatAsState(
        targetValue = if (showFullImage && cropRegion != null) 1.05f else 1f,
        animationSpec = tween(durationMillis = 300),
        label = "imageScale"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .scale(scale)
            // L2 仪表层（题面卡）— 四层深度模型统一阴影，
            // 仅直射光允许染主色（原实现环境光也染了主色，且 16.dp 绕过令牌）
            .blyyDepth(
                DepthLayer.Instrument,
                RoundedCornerShape(AppSpacing.Corner.Xxl),
                spotTint = MaterialTheme.colorScheme.primary
            )
            .clip(RoundedCornerShape(AppSpacing.Corner.Xxl))
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                    )
                ),
                shape = RoundedCornerShape(AppSpacing.Corner.Xxl)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl != null) {
            if (cropRegion != null && difficulty == ImageDifficulty.HARD && !showFullImage) {
                CroppedImage(
                    imageUrl = imageUrl,
                    cropRegion = cropRegion,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .scale(imageScale),
                    contentScale = ContentScale.Fit
                )
            }
        } else if (errorMessage != null) {
            // 题目生成失败（如重试耗尽/数据同步中）— 展示真实原因，引导点击「下一题」（B7 修复）
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Md),
                modifier = Modifier.padding(AppSpacing.Lg)
            ) {
                Icon(
                    imageVector = Icons.Rounded.ImageNotSupported,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(40.dp)
                )
                Text(
                    errorMessage,
                    style = AppTypography.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Text(
                    "点击下方「下一题」重新出题",
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "正在加载题目…",
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CroppedImage(
    imageUrl: String,
    cropRegion: CropRegion,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val cropWidth = cropRegion.endX - cropRegion.startX
                    val cropHeight = cropRegion.endY - cropRegion.startY
                    // 修复 P0：cropWidth/cropHeight 为 0 时除零导致 scaleX/Y 为 Infinity/NaN，
                    // 渲染异常或崩溃。退化区域用 1f 占位（不缩放、不裁剪）。
                    val sx = if (cropWidth > 0f) 1f / cropWidth else 1f
                    val sy = if (cropHeight > 0f) 1f / cropHeight else 1f
                    scaleX = sx
                    scaleY = sy
                    translationX = if (cropWidth > 0f) -cropRegion.startX * size.width / cropWidth else 0f
                    translationY = if (cropHeight > 0f) -cropRegion.startY * size.height / cropHeight else 0f
                },
            contentScale = ContentScale.Fit
        )
    }
}
