package com.azurlane.blyy.ui.screens.guess

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.theme.AppAnimation
import com.azurlane.blyy.ui.theme.AppElevation
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.BlyyShapes
import com.azurlane.blyy.ui.theme.DepthLayer
import com.azurlane.blyy.ui.theme.LocalUiStyle
import com.azurlane.blyy.ui.theme.blyyDepth
import com.azurlane.blyy.ui.theme.blyyDepthShadow
import com.azurlane.blyy.ui.theme.isCommandCenter

/**
 * 三种识舰娘玩法共用的页面骨架：顶栏、题面卡外框、题面元信息条。
 *
 * 收口原因：三个页面此前各自拼 `BlyyTopBar` + `IconButton` + 分数胶囊，
 * 结果同一个"总分"胶囊在指挥中心 UI 里是圆角胶囊、旁边的顶栏是切角面板，
 * 形状语言打架；历史按钮又没有底色，和胶囊并排时重心飘。这里统一成：
 * - 顶栏动作 = 40dp 同尺寸的「历史」图标按钮 + 总分胶囊，间距固定；
 * - 所有容器形状走 [BlyyShapes]（指挥中心切角 / 经典圆角自动切换）；
 * - 题面卡统一"标签 + 内容"的版式，看图/听音/识技能读起来像同一套 UI。
 */

/** 三个玩法统一的顶栏：返回 + 标题 + 副标题 + 历史记录 + 总分 */
@Composable
fun GuessTopBar(
    title: String,
    subtitle: String,
    totalScore: Int,
    onBack: () -> Unit,
    onHistory: () -> Unit
) {
    BlyyTopBar(
        title = title,
        subtitle = subtitle,
        onBackClick = onBack,
        actions = {
            GuessIconAction(
                icon = Icons.Rounded.History,
                contentDescription = "历史记录",
                onClick = onHistory
            )
            Spacer(Modifier.width(AppSpacing.Sm))
            GuessScoreChip(totalScore = totalScore)
        }
    )
}

/**
 * 顶栏图标按钮 — 与 [GuessScoreChip] 同高（40dp）、同形状语言。
 *
 * 旧实现直接用裸 `IconButton`：没有底色、正方形点击区，和右侧带底色的胶囊并排时
 * 一虚一实，看起来像"漏了一个按钮的容器"。
 */
@Composable
fun GuessIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val shape = if (isCommandCenter) BlyyShapes.PanelSmall else CircleShape
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) AppAnimation.Press.LightScale else 1f,
        animationSpec = AppAnimation.Press.light(),
        label = "guessIconActionScale"
    )
    val primary = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .size(40.dp)
            .scale(scale)
            .clip(shape)
            .background(primary.copy(alpha = 0.10f))
            .border(AppSpacing.Border.Thin, primary.copy(alpha = 0.22f), shape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = primary),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = primary,
            modifier = Modifier.size(AppSpacing.Icon.Md)
        )
    }
}

/**
 * 总分胶囊 — 顶栏右侧的"计分器"。
 *
 * 旧版是一个只有「★ 数字」的浅色圆角胶囊：没有"这是总分"的语义标签、没有描边，
 * 分数变大时宽度跳变，且在切角风格顶栏里显得格格不入。
 * 现在：`总分 / 数值` 两行结构（宽度稳定、语义明确）+ 切角/圆角自适应 + 数值滚动动画，
 * 分数增加时有一次轻微回弹，让"+50"这种奖励有存在感。
 */
@Composable
fun GuessScoreChip(
    totalScore: Int,
    modifier: Modifier = Modifier
) {
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val shape = if (isCommandCenter) BlyyShapes.PanelSmall else RoundedCornerShape(AppSpacing.Corner.Full)
    val primary = MaterialTheme.colorScheme.primary
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer

    val animatedScore by animateIntAsState(
        targetValue = totalScore,
        animationSpec = tween(AppAnimation.Duration.Normal),
        label = "guessTotalScore"
    )
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(totalScore) {
        if (totalScore > 0) {
            pulse.snapTo(1.10f)
            pulse.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.55f,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    }

    Row(
        modifier = modifier
            .height(40.dp)
            .graphicsLayer {
                scaleX = pulse.value
                scaleY = pulse.value
            }
            .blyyDepthShadow(
                elevation = AppElevation.Level1,
                shape = shape,
                spotColor = primary.copy(alpha = 0.3f)
            )
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
            .border(AppSpacing.Border.Thin, primary.copy(alpha = 0.30f), shape)
            .padding(horizontal = AppSpacing.Md)
            .semantics { contentDescription = "总得分 $totalScore" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
    ) {
        Icon(
            imageVector = Icons.Rounded.Star,
            contentDescription = null,
            tint = primary,
            modifier = Modifier.size(AppSpacing.Icon.Sm)
        )
        Column {
            Text(
                text = "总分",
                style = AppTypography.LabelSmall,
                color = onPrimaryContainer.copy(alpha = 0.65f),
                maxLines = 1
            )
            Text(
                text = "$animatedScore",
                style = AppTypography.TitleSmall,
                fontWeight = FontWeight.Bold,
                color = primary,
                maxLines = 1
            )
        }
    }
}

/**
 * 题面元信息条 — 替代旧的「本题可得 N 分」单行说明。
 *
 * 一局里玩家真正需要的上下文有三个：现在第几题、这题值多少分、是不是困难档。
 * 旧实现只有中间一项，且是整条淡色横幅；现在拆成三个小标签，视觉更轻、信息更全。
 */
@Composable
fun GuessMetaBar(
    questionIndex: Int,
    questionScore: Int,
    isHard: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
    ) {
        GuessMetaTag(
            text = "第 $questionIndex 题",
            icon = null,
            container = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            content = MaterialTheme.colorScheme.onSurfaceVariant
        )
        GuessMetaTag(
            text = "$questionScore 分",
            icon = Icons.Rounded.Star,
            container = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
            content = MaterialTheme.colorScheme.onSecondaryContainer
        )
        if (isHard) {
            GuessMetaTag(
                text = "困难",
                icon = null,
                container = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                content = MaterialTheme.colorScheme.onErrorContainer
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun GuessMetaTag(
    text: String,
    icon: ImageVector?,
    container: Color,
    content: Color
) {
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val shape = if (isCommandCenter) BlyyShapes.PanelSmall else RoundedCornerShape(AppSpacing.Corner.Full)
    Surface(shape = shape, color = container) {
        Row(
            modifier = Modifier.padding(horizontal = AppSpacing.Sm, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xxs)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(12.dp)
                )
            }
            Text(
                text = text,
                style = AppTypography.LabelSmall,
                color = content,
                maxLines = 1
            )
        }
    }
}

/**
 * 题面卡外框 — 看图（立绘）/ 听音（语音）/ 识技能（技能图标）共用。
 *
 * 统一要素：左上角"这是什么题面"的图标 + 标签、右侧可选状态徽标、统一圆角/描边/投影。
 * 旧实现里三种卡片各画一套背景，切换玩法时观感断层。
 */
@Composable
fun GuessQuestionFrame(
    label: String,
    icon: ImageVector,
    badge: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val shape = if (isCommandCenter) BlyyShapes.PanelLarge else RoundedCornerShape(AppSpacing.Corner.Xxl)
    val primary = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .fillMaxWidth()
            .blyyDepth(DepthLayer.Instrument, shape, spotTint = primary)
            .clip(shape)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                    )
                )
            )
            .border(
                width = AppSpacing.Border.Thin,
                brush = Brush.linearGradient(
                    colors = listOf(
                        primary.copy(alpha = 0.22f),
                        primary.copy(alpha = 0.04f)
                    )
                ),
                shape = shape
            )
            .padding(AppSpacing.Lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = primary,
                modifier = Modifier.size(AppSpacing.Icon.Md2)
            )
            Text(
                text = label,
                style = AppTypography.LabelLarge,
                fontWeight = FontWeight.Bold,
                color = primary,
                maxLines = 1
            )
            if (!badge.isNullOrBlank()) {
                GuessFrameBadge(text = badge)
            }
            Spacer(Modifier.weight(1f))
        }
        content()
    }
}

@Composable
private fun GuessFrameBadge(text: String) {
    val shape = if (LocalUiStyle.current.isCommandCenter()) {
        BlyyShapes.PanelSmall
    } else {
        RoundedCornerShape(AppSpacing.Corner.Full)
    }
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = AppSpacing.Sm, vertical = 2.dp),
            style = AppTypography.LabelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            maxLines = 1
        )
    }
}
