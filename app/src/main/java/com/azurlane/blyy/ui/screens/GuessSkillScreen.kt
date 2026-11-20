package com.azurlane.blyy.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyConfirmDialog
import com.azurlane.blyy.ui.components.BlyyHaptic
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.components.rememberBlyyHaptics
import com.azurlane.blyy.ui.screens.guess.GuessActionButton
import com.azurlane.blyy.ui.screens.guess.GuessCheckingBanner
import com.azurlane.blyy.ui.screens.guess.GuessCorrectCard
import com.azurlane.blyy.ui.screens.guess.GuessDifficultyOption
import com.azurlane.blyy.ui.screens.guess.GuessDifficultySelector
import com.azurlane.blyy.ui.screens.guess.GuessErrorBanner
import com.azurlane.blyy.ui.screens.guess.GuessHintButton
import com.azurlane.blyy.ui.screens.guess.GuessHintsSection
import com.azurlane.blyy.ui.screens.guess.GuessInputField
import com.azurlane.blyy.ui.screens.guess.GuessMetaBar
import com.azurlane.blyy.ui.screens.guess.GuessTopBar
import com.azurlane.blyy.ui.screens.guess.GuessSettlementDialog
import com.azurlane.blyy.ui.screens.guess.GuessSkillAnswerCard
import com.azurlane.blyy.ui.screens.guess.GuessSkillQuestionCard
import com.azurlane.blyy.ui.screens.guess.GuessSkillReveal
import com.azurlane.blyy.ui.screens.guess.GuessWrongCard
import com.azurlane.blyy.ui.theme.AppAnimation
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.viewmodel.GuessGameUiState
import com.azurlane.blyy.viewmodel.GuessResult
import com.azurlane.blyy.viewmodel.GuessShipViewModel
import com.azurlane.blyy.viewmodel.SkillDifficulty

/**
 * 技能识舰娘：题面是一枚**技能图标**，玩家要答出持有该技能的舰娘。
 *
 * 与看图/听音共用 [GuessShipViewModel] 与 [GuessGameComponents] 的整套流程
 * （难度 → 题面 → 提示 → 判分 → 结算 → 落库），差异只有：
 * - 题面是网络图标，有独立的加载/失败/重试三态（[GuessSkillIconCard]）；
 * - 简单模式额外给出技能名与描述，困难模式**只给图标**；
 * - 判分支持"共享技能"：答出另一艘同样持有该技能的舰娘也算对（VM 内校验）。
 */
@Composable
fun GuessBySkillScreen(
    viewModel: GuessShipViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onHistory: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.startSkillGame()
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

    ModernGuessSkillContent(
        state = state,
        onBack = { viewModel.showSettlement() },
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
            viewModel.goToNextQuestion()
        },
        onRequestHint = viewModel::requestHint,
        onShowAnswer = viewModel::showAnswer,
        onShowSettlement = viewModel::showSettlement,
        onDifficultyChange = viewModel::setSkillDifficulty
    )
}

@Composable
private fun ModernGuessSkillContent(
    state: GuessGameUiState,
    onBack: () -> Unit,
    onHistory: () -> Unit,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onNext: () -> Unit,
    onRequestHint: () -> Unit,
    onShowAnswer: () -> Unit,
    onShowSettlement: () -> Unit,
    onDifficultyChange: (SkillDifficulty) -> Unit
) {
    val scrollState = rememberScrollState()
    val isEasy = state.skillDifficulty == SkillDifficulty.EASY
    // 答对/已揭示答案后，主操作切换为"下一题"
    val answered = state.lastResult == GuessResult.CORRECT || state.showAnswer
    // 共享技能校验中：提交按钮锁住，避免同一题被重复判定
    val canSubmit = !answered && !state.isChecking

    AdaptiveScreenBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            GuessTopBar(
                title = "技能识舰娘",
                subtitle = "看技能图标，猜出舰娘",
                totalScore = state.score.totalScore,
                onBack = onBack,
                onHistory = onHistory
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
                    current = state.skillDifficulty,
                    options = listOf(
                        GuessDifficultyOption(
                            value = SkillDifficulty.EASY,
                            title = "简单模式",
                            description = "显示技能名与描述",
                            icon = Icons.Rounded.Lightbulb
                        ),
                        GuessDifficultyOption(
                            value = SkillDifficulty.HARD,
                            title = "困难模式",
                            description = "只显示技能图标",
                            icon = Icons.Rounded.VisibilityOff
                        )
                    ),
                    onSelect = onDifficultyChange
                )

                GuessMetaBar(
                    questionIndex = state.currentQuestionNumber,
                    questionScore = state.currentQuestionScore,
                    isHard = !isEasy
                )

                GuessSkillQuestionCard(
                    skills = state.currentSkills,
                    isHard = !isEasy,
                    isLoading = state.currentSkills.isEmpty() && state.errorMessage == null,
                    // 已有题面时不再展示上一次的失败原因（避免"卡片里写着失败但图标在"）
                    errorMessage = if (state.currentSkills.isEmpty()) state.errorMessage else null
                )

                // 提示系统 — 与看图/听音一致：EASY 可用，HARD 关闭
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

                GuessCheckingBanner(visible = state.isChecking)

                AnimatedVisibility(
                    visible = state.lastResult == GuessResult.CORRECT,
                    enter = fadeIn() + scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)),
                    exit = fadeOut() + scaleOut()
                ) {
                    GuessCorrectCard(
                        score = state.currentQuestionScore,
                        rewardImageUrl = state.rewardImageUrl,
                        note = state.sharedSkillOwner?.let { "「$it」同样持有这个技能，按答对计算" }
                    )
                }

                // 困难模式作答时看不到技能名，答对后补一次揭示（学习闭环）
                if (!isEasy && state.lastResult == GuessResult.CORRECT) {
                    GuessSkillReveal(state.currentSkills)
                }

                AnimatedVisibility(
                    visible = state.showAnswer && state.lastResult == GuessResult.SKIPPED,
                    enter = fadeIn(animationSpec = tween(AppAnimation.Duration.Smooth)) +
                        slideInHorizontally(animationSpec = tween(AppAnimation.Duration.Smooth)),
                    exit = fadeOut(animationSpec = tween(AppAnimation.Duration.Fast)) +
                        slideOutHorizontally(animationSpec = tween(AppAnimation.Duration.Fast))
                ) {
                    GuessSkillAnswerCard(
                        shipName = state.currentShip?.name.orEmpty(),
                        skills = state.currentSkills,
                        shipAvatarUrl = state.currentShip?.avatarUrl,
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
                    enabled = canSubmit,
                    placeholder = "输入持有该技能的舰娘…"
                )

                if (!answered) {
                    GuessActionButton(
                        text = "显示答案",
                        icon = Icons.Rounded.Visibility,
                        onClick = onShowAnswer,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isChecking
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
                        enabled = canSubmit
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
