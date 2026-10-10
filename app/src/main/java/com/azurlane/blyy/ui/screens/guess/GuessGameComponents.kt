package com.azurlane.blyy.ui.screens.guess

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.azurlane.blyy.ui.components.BlyyPrimaryButton
import com.azurlane.blyy.ui.components.BlyySecondaryButton
import com.azurlane.blyy.ui.components.BlyyTextField
import com.azurlane.blyy.ui.theme.AppAnimation
import com.azurlane.blyy.ui.theme.AppColors
import com.azurlane.blyy.ui.theme.AppElevation
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.DepthLayer
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.ui.theme.blyyDepth
import com.azurlane.blyy.viewmodel.GameScore
import com.azurlane.blyy.viewmodel.HintItem

/**
 * 识舰娘玩法（看图/听音）共享组件库。
 *
 * 两个玩法界面的结构完全同构（难度选择 → 题目区 → 反馈卡 → 输入 → 操作行 → 结算），
 * 历史上各自维护一套实现且配色互相矛盾（听音用 secondary 黄系、看图用 primary 蓝系），
 * 本文件将可共享部分收口为单一实现：
 * - 主色语言统一为 colorScheme.primary（与 [BlyyPrimaryButton]/[BlyyTopBar] 一致）
 * - 投影统一使用 [AppColors.Depth] 有色柔和阴影，禁止默认黑阴影
 * - 动效统一走 [AppAnimation] 规范
 */
private val ResultCardShape = RoundedCornerShape(AppSpacing.Corner.Xl)

/** 顶栏总分徽章 */
@Composable
fun GuessScoreChip(totalScore: Int) {
    Surface(
        shape = RoundedCornerShape(AppSpacing.Corner.Xl),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppSpacing.Md, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Rounded.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(AppSpacing.Icon.Sm)
            )
            Text(
                "$totalScore",
                style = AppTypography.TitleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** "本题可得 N 分" 说明条 */
@Composable
fun GuessScoreBanner(score: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppSpacing.Corner.Md),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                Icons.Rounded.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(14.dp)
            )
            Text(
                "本题可得 $score 分",
                style = AppTypography.BodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/**
 * 游戏内错误/状态提示条 — VM 的 [com.azurlane.blyy.viewmodel.GuessGameUiState.errorMessage]
 * 历史上从未被渲染（同步失败/空输入提交均无反馈），本组件补齐该缺口。
 */
@Composable
fun GuessErrorBanner(message: String?) {
    AnimatedVisibility(
        visible = !message.isNullOrBlank(),
        enter = expandVertically(
            animationSpec = tween(AppAnimation.Duration.Normal, easing = AppAnimation.Easings.EmphasizedDecelerate)
        ) + fadeIn(animationSpec = tween(AppAnimation.Duration.Normal, easing = AppAnimation.Easings.EmphasizedDecelerate)),
        exit = shrinkVertically(
            animationSpec = tween(AppAnimation.Duration.Fast, easing = AppAnimation.Easings.EmphasizedAccelerate)
        ) + fadeOut(animationSpec = tween(AppAnimation.Duration.Fast, easing = AppAnimation.Easings.EmphasizedAccelerate))
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(AppSpacing.Corner.Md),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
            ) {
                Icon(
                    Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = message.orEmpty(),
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** 难度选项描述 */
data class GuessDifficultyOption<T>(
    val value: T,
    val title: String,
    val description: String,
    val icon: ImageVector
)

/**
 * 难度选择器 — 单容器分段控件（Segmented Control）。
 *
 * - 选中段使用与 [BlyyPrimaryButton] 一致的 primary 三段受光渐变 + 主色辉光投影
 * - 深色/浅色阴影均使用 [AppColors.Depth] 有色柔和投影
 * - 按压反馈遵循 [AppAnimation.Press] 规范（LightScale + light spring）
 * - 文字颜色用 animateColorAsState 平滑过渡，避免硬切
 */
@Composable
fun <T> GuessDifficultySelector(
    current: T,
    options: List<GuessDifficultyOption<T>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = LocalIsDark.current
    val shadowAmbient = if (isDark) AppColors.Depth.AmbientDark else AppColors.Depth.AmbientLight
    val shadowSpot = if (isDark) AppColors.Depth.SpotDark else AppColors.Depth.SpotLight
    val containerShape = RoundedCornerShape(AppSpacing.Corner.Lg)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = AppElevation.Level1,
                shape = containerShape,
                ambientColor = shadowAmbient,
                spotColor = shadowSpot
            )
            .clip(containerShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceContainerLowest,
                        MaterialTheme.colorScheme.surfaceContainerLow
                    )
                )
            )
            .border(
                width = AppSpacing.Border.Thin,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                shape = containerShape
            )
            .padding(AppSpacing.Xs),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
    ) {
        options.forEach { option ->
            GuessDifficultySegment(
                title = option.title,
                description = option.description,
                icon = option.icon,
                isSelected = current == option.value,
                onClick = { onSelect(option.value) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 难度选择器的单个分段：选中态 = primary 受光渐变实体按键 */
@Composable
private fun GuessDifficultySegment(
    title: String,
    description: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) AppAnimation.Press.LightScale else 1f,
        animationSpec = AppAnimation.Press.light(),
        label = "segmentScale"
    )

    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    // 选中段：与 BlyyPrimaryButton 一致的三段垂直受光渐变（顶亮中实底深）
    val segmentShape = RoundedCornerShape(AppSpacing.Corner.Lg - AppSpacing.Xs)
    val segmentBrush = if (isSelected) {
        Brush.verticalGradient(
            colors = listOf(
                lerp(primary, Color.White, 0.22f),
                primary,
                lerp(primary, Color.Black, 0.22f)
            )
        )
    } else {
        Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
    }

    // 文字颜色平滑过渡
    val titleColor by animateColorAsState(
        targetValue = if (isSelected) onPrimary else onSurfaceVariant,
        animationSpec = AppAnimation.Specs.fast(),
        label = "segmentTitle"
    )
    val descColor by animateColorAsState(
        targetValue = if (isSelected) onPrimary.copy(alpha = 0.85f) else onSurfaceVariant.copy(alpha = 0.7f),
        animationSpec = AppAnimation.Specs.fast(),
        label = "segmentDesc"
    )

    Row(
        modifier = modifier
            .scale(scale)
            .shadow(
                elevation = if (isSelected) AppElevation.Level1 else AppElevation.Level0,
                shape = segmentShape,
                ambientColor = if (LocalIsDark.current) AppColors.Depth.AmbientDark else AppColors.Depth.AmbientLight,
                // 直射阴影带主色 — 选中段的辉光投影
                spotColor = primary.copy(alpha = if (isSelected) 0.45f else 0f)
            )
            .clip(segmentShape)
            .background(segmentBrush)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = onPrimary.copy(alpha = if (isSelected) 0.2f else 0.08f)),
                onClick = onClick
            )
            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = titleColor,
            modifier = Modifier.size(AppSpacing.Icon.Md)
        )
        Spacer(Modifier.width(AppSpacing.Sm))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = AppTypography.LabelLarge,
                fontWeight = FontWeight.Bold,
                color = titleColor
            )
            Text(
                text = description,
                style = AppTypography.LabelSmall,
                color = descColor,
                maxLines = 1
            )
        }
    }
}

/** 提示列表 — 每条提示一个 tertiary 主题面板 */
@Composable
fun GuessHintsSection(hints: List<HintItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
        hints.forEachIndexed { index, hint ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AppSpacing.Corner.Lg),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
                border = BorderStroke(
                    width = AppSpacing.Border.Thin,
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                )
            ) {
                Column(modifier = Modifier.padding(AppSpacing.Md + 2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
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
                    Spacer(Modifier.height(AppSpacing.Sm))
                    Text(
                        hint.label,
                        style = AppTypography.LabelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        hint.value,
                        style = AppTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
    }
}

/** "获取提示" 按钮 — EASY 模式可用 */
@Composable
fun GuessHintButton(
    isLoading: Boolean,
    hintCount: Int,
    noMoreHints: Boolean,
    onRequestHint: () -> Unit,
    modifier: Modifier = Modifier
) {
    BlyySecondaryButton(
        text = when {
            isLoading -> "获取提示中…"
            noMoreHints -> "已无更多提示"
            hintCount == 0 -> "获取提示"
            else -> "再获取提示"
        },
        icon = Icons.Rounded.Lightbulb,
        onClick = onRequestHint,
        enabled = !isLoading && !noMoreHints,
        modifier = modifier
            .fillMaxWidth()
            .height(AppSpacing.Game.Button.Height)
    )
}

/** 游戏操作按钮 — primary 决定主/次样式（答对后主次互换引导下一动作） */
@Composable
fun GuessActionButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false
) {
    val buttonModifier = modifier.height(AppSpacing.Game.Button.Height)
    if (primary) {
        BlyyPrimaryButton(
            text = text,
            icon = icon,
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier
        )
    } else {
        BlyySecondaryButton(
            text = text,
            icon = icon,
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier
        )
    }
}

/** 答案输入框 — 共享 [BlyyTextField]，键盘 Done 直接提交 */
@Composable
fun GuessInputField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    BlyyTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = "输入舰娘名字...",
        trailingIcon = if (value.isNotEmpty() && enabled) Icons.Rounded.Clear else null,
        onTrailingIconClick = if (value.isNotEmpty() && enabled) ({ onValueChange("") }) else null,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Text),
        keyboardActions = KeyboardActions(onDone = { onSubmit() })
    )
}

/** 答对卡片 — 奖杯 + 得分；可选奖励立绘（听音）与语音回放（看图） */
@Composable
fun GuessCorrectCard(
    score: Int,
    rewardImageUrl: String?,
    onReplayVoice: (() -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                // L2 仪表层 — 统一四层深度模型（原实现亮暗两态都用了亮色阴影，暗色下发灰）
                .blyyDepth(DepthLayer.Instrument, ResultCardShape),
            shape = ResultCardShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        ) {
            Column(
                modifier = Modifier.padding(AppSpacing.Xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Lg)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
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
                onReplayVoice?.let { replay ->
                    BlyySecondaryButton(
                        text = "播放语音",
                        icon = Icons.Rounded.Refresh,
                        onClick = replay
                    )
                }
            }
        }
        GuessRewardImage(rewardImageUrl)
    }
}

/** 答案揭示卡片 — 显示答案/跳过时；听音模式附奖励立绘 */
@Composable
fun GuessAnswerCard(
    shipName: String,
    rewardImageUrl: String? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = ResultCardShape,
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
        ) {
            Row(
                modifier = Modifier.padding(AppSpacing.Lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
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
        GuessRewardImage(rewardImageUrl)
    }
}

/** 答错卡片 */
@Composable
fun GuessWrongCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppSpacing.Corner.Lg),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
    ) {
        Row(
            modifier = Modifier.padding(AppSpacing.Lg),
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
private fun GuessRewardImage(rewardImageUrl: String?) {
    if (rewardImageUrl == null) return
    AsyncImage(
        model = rewardImageUrl,
        contentDescription = null,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            // L3 瞭望层 — 原 Level4 走的是默认纯黑阴影，改用 Depth 双色（暗色下发脏）
            .blyyDepth(DepthLayer.Lookout, ResultCardShape)
            .clip(ResultCardShape),
        contentScale = ContentScale.Fit
    )
}

/** 结算弹窗 — 两玩法共享（历史上是两份仅配色不同的复制） */
@Composable
fun GuessSettlementDialog(
    score: GameScore,
    onDismiss: () -> Unit,
    onExit: () -> Unit,
    onContinue: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(AppSpacing.Corner.Dialog),
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
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Lg)
            ) {
                Surface(
                    shape = RoundedCornerShape(AppSpacing.Corner.Xl),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Column(
                        modifier = Modifier.padding(AppSpacing.Xxl),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "总得分",
                            style = AppTypography.LabelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(AppSpacing.Xs))
                        Text(
                            "${score.totalScore}",
                            style = AppTypography.DisplayMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (score.totalPossibleScore > 0) {
                            Text(
                                "满分 ${score.totalPossibleScore} 分",
                                style = AppTypography.BodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
                ) {
                    GuessStatItem(
                        label = "答对",
                        value = "${score.correctAnswers}/${score.totalQuestions}",
                        subValue = "${(score.accuracy * 100).toInt()}%",
                        modifier = Modifier.weight(1f)
                    )
                    GuessStatItem(
                        label = "跳过",
                        value = "${score.skippedQuestions}",
                        subValue = "-",
                        modifier = Modifier.weight(1f)
                    )
                    GuessStatItem(
                        label = "提示",
                        value = "${score.hintsUsedTotal}",
                        subValue = "-",
                        modifier = Modifier.weight(1f)
                    )
                }

                if (score.totalQuestions > 0) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(AppSpacing.Corner.Md),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    ) {
                        Row(
                            modifier = Modifier.padding(AppSpacing.Md),
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
            BlyyPrimaryButton(
                text = "继续游戏",
                onClick = onContinue
            )
        },
        dismissButton = {
            BlyySecondaryButton(
                text = "退出",
                onClick = onExit
            )
        }
    )
}

/** 结算统计项 */
@Composable
private fun GuessStatItem(
    label: String,
    value: String,
    subValue: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(AppSpacing.Corner.Xs2),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Md),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                label,
                style = AppTypography.LabelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(AppSpacing.Xs))
            Text(value, style = AppTypography.TitleMedium, fontWeight = FontWeight.Bold)
            Text(
                subValue,
                style = AppTypography.LabelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
