package com.azurlane.blyy.ui.screens.assistant

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.azurlane.blyy.data.model.DailyChallenge
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography

/**
 * 碧蓝航线助手 — 指挥官档案面板组
 *
 * 视觉对齐设计稿（图1）：
 * 1. 头像 + 昵称 / Lv. / UID / 所属大舰队 的身份卡
 * 2. 3×2 数据格（图鉴收集率、主线进度、宿舍互动、石油、物资、食量）
 * 3. 「进度追踪 / PROGRESS TRACKING」分组带
 * 4. 军事委托 / 军部研究室 进度卡
 * 5. 「战备速览 / COMBAT READINESS」分组带 + 演习与副本待办
 *
 * 所有配色/间距/字号均取自设计系统令牌，深浅主题自动适配，
 * 不做内联硬编码色值（项目「色板收敛」约束）。
 */

// ── 数据载体 ──

/** 数据格单元 */
@Immutable
data class StatItem(val label: String, val value: String)

/** 缺值统一占位 — 与设计稿的 "--" 一致 */
fun orDash(value: String?): String =
    value?.takeIf { it.isNotBlank() } ?: "--"

/** 数值千分位，避免 1234567 这类没法读的原始数字 */
fun formatAmount(value: Int): String = "%,d".format(value)

// ── 通用卡面 ──

/**
 * 档案卡面 — 浅蓝渐变 + 细描边，对应设计稿的白色/淡蓝卡片
 */
@Composable
private fun AssistantCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(AppSpacing.Corner.Lg)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        primary.copy(alpha = 0.12f),
                        primary.copy(alpha = 0.03f)
                    )
                )
            )
            .border(
                width = AppSpacing.Border.Thin,
                color = primary.copy(alpha = 0.28f),
                shape = shape
            ),
        content = content
    )
}

/** 细分隔线（横向） */
@Composable
private fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(AppSpacing.Border.Thin)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
    )
}

// ── 1. 身份卡 ──

/**
 * 指挥官身份卡 — 头像 + 昵称 + Lv. + UID + 所属大舰队
 *
 * @param server 服务器全名，作为 UID 行的尾部小标签，保留原有信息密度
 */
@Composable
fun AssistantProfileHeader(
    nickname: String,
    level: Int,
    uid: String,
    server: String,
    guildName: String?,
    modifier: Modifier = Modifier
) {
    AssistantCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProfileAvatar()

            Spacer(modifier = Modifier.width(AppSpacing.Md))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
            ) {
                // 昵称 + Lv. + 展开指示
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProfileLabel("昵称")
                    Text(
                        text = orDash(nickname),
                        style = AppTypography.TitleSmallBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    LevelTag(level)
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        modifier = Modifier.size(AppSpacing.Icon.Sm)
                    )
                }

                // UID + 服务器标签
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProfileLabel("UID")
                    Text(
                        text = orDash(uid),
                        style = AppTypography.BodyMediumMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    ServerTag(server)
                }

                // 所属大舰队
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProfileLabel("所属大舰队")
                    Text(
                        text = orDash(guildName),
                        style = AppTypography.BodyMediumMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileAvatar() {
    val primary = MaterialTheme.colorScheme.primary
    Box(modifier = Modifier.size(AppSpacing.Avatar.Xl)) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .clip(CircleShape)
                .background(primary.copy(alpha = 0.14f))
                .border(AppSpacing.Border.Normal, primary.copy(alpha = 0.38f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = null,
                tint = primary,
                modifier = Modifier.size(AppSpacing.Icon.Xl)
            )
        }
        // 右下角切换身份徽标 — 呼应设计稿的圆形小角标
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(AppSpacing.Icon.Md)
                .clip(CircleShape)
                .background(primary)
                .border(
                    width = AppSpacing.Border.Normal,
                    color = MaterialTheme.colorScheme.surface,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.SwapHoriz,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(AppSpacing.Icon.Xs)
            )
        }
    }
}

/** 字段名 — 设计稿里的小号蓝字标签 */
@Composable
private fun ProfileLabel(text: String) {
    Text(
        text = text,
        style = AppTypography.LabelMedium,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
        modifier = Modifier.padding(end = AppSpacing.Sm)
    )
}

/** 等级胶囊 */
@Composable
private fun LevelTag(level: Int) {
    val primary = MaterialTheme.colorScheme.primary
    val text = if (level > 0) "Lv.$level" else "Lv.--"
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(AppSpacing.Corner.Full))
            .background(primary.copy(alpha = 0.14f))
            .border(AppSpacing.Border.Thin, primary.copy(alpha = 0.45f), RoundedCornerShape(AppSpacing.Corner.Full))
            .padding(horizontal = AppSpacing.Sm, vertical = AppSpacing.Xxs)
    ) {
        Text(
            text = text,
            style = AppTypography.LabelSmallMedium,
            color = primary
        )
    }
}

/** 服务器标签 */
@Composable
private fun ServerTag(server: String) {
    val primary = MaterialTheme.colorScheme.primary
    if (server.isBlank()) return
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(AppSpacing.Corner.Xs))
            .background(primary.copy(alpha = 0.10f))
            .padding(horizontal = AppSpacing.Corner.Sm1, vertical = AppSpacing.Xxs)
    ) {
        Text(
            text = server,
            style = AppTypography.CaptionSmall,
            color = primary.copy(alpha = 0.9f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ── 2. 数据格 ──

/**
 * 数据格 — 设计稿的上排 3 格 + 下排 3 格
 *
 * @param stats 按行优先顺序排列，长度不足时末行留白
 * @param columns 每行列数
 */
@Composable
fun AssistantStatsGrid(
    stats: List<StatItem>,
    modifier: Modifier = Modifier,
    columns: Int = 3
) {
    if (stats.isEmpty()) return
    val rows = stats.chunked(columns)
    AssistantCard(modifier = modifier) {
        rows.forEachIndexed { rowIndex, rowStats ->
            if (rowIndex > 0) Hairline()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
            ) {
                rowStats.forEachIndexed { colIndex, stat ->
                    if (colIndex > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(AppSpacing.Border.Thin)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                        )
                    }
                    StatCell(
                        stat = stat,
                        modifier = Modifier.weight(1f)
                    )
                }
                // 末行不足时补白，保持等宽
                repeat(columns - rowStats.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StatCell(stat: StatItem, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = AppSpacing.Md, horizontal = AppSpacing.Sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
    ) {
        Text(
            text = stat.label,
            style = AppTypography.LabelSmall,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.82f),
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        Text(
            text = stat.value,
            style = AppTypography.TitleSmallBold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ── 3. 分组带 ──

/**
 * 分组带 — 「进度追踪 / PROGRESS TRACKING」这种中英双行标题
 *
 * 两侧斜纹装饰用 Canvas 绘制，颜色走主题令牌，不引入位图资源。
 */
@Composable
fun AssistantSectionBanner(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = AppSpacing.Sm),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
        ) {
            StripeDecoration(modifier = Modifier.weight(1f))
            Text(
                text = title,
                style = AppTypography.TitleLargeBold,
                color = primary,
                letterSpacing = 2.sp
            )
            StripeDecoration(modifier = Modifier.weight(1f))
        }
        Text(
            text = subtitle,
            style = AppTypography.CaptionSmall,
            color = primary.copy(alpha = 0.45f),
            letterSpacing = 4.sp,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/** 斜纹装饰 —— 设计稿标题两侧的排线 */
@Composable
private fun StripeDecoration(modifier: Modifier = Modifier) {
    val stripeColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
    Canvas(modifier = modifier.height(AppSpacing.Lg)) {
        val step = 6.dp.toPx()
        val strokeWidth = 1.2.dp.toPx()
        var x = -size.height
        while (x < size.width + size.height) {
            drawLine(
                color = stripeColor,
                start = Offset(x, size.height),
                end = Offset(x + size.height, 0f),
                strokeWidth = strokeWidth
            )
            x += step
        }
    }
}

// ── 4. 进度卡 ──

/**
 * 进度卡 — 军事委托 / 军部研究室
 *
 * 左侧图标 + 名称标签，右侧进度数字；全零时展示设计稿的「暂无进度数据」。
 */
@Composable
fun AssistantProgressCard(
    icon: ImageVector,
    title: String,
    inProgress: Int,
    completed: Int,
    idle: Int,
    modifier: Modifier = Modifier
) {
    val isEmpty = inProgress == 0 && completed == 0 && idle == 0
    AssistantCard(modifier = modifier) {
        Row(
            modifier = Modifier.padding(AppSpacing.Lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.width(AppSpacing.Avatar.Xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(AppSpacing.Icon.Xxl)
                )
                CategoryTag(title)
            }

            Box(
                modifier = Modifier
                    .padding(horizontal = AppSpacing.Lg)
                    .width(AppSpacing.Border.Thin)
                    .height(AppSpacing.Icon.Huge)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            )

            if (isEmpty) {
                Text(
                    text = "暂无进度数据",
                    style = AppTypography.TitleSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ProgressMetric(value = inProgress, label = "进行中")
                    ProgressMetric(value = completed, label = "已完成")
                    ProgressMetric(value = idle, label = "空闲")
                }
            }
        }
    }
}

@Composable
private fun ProgressMetric(value: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "$value",
            style = AppTypography.TitleLargeBold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = AppTypography.CaptionSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 分类标签 —— 设计稿图标下方的描边小牌子 */
@Composable
private fun CategoryTag(text: String) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(AppSpacing.Corner.Xs))
            .background(primary.copy(alpha = 0.16f))
            .border(AppSpacing.Border.Thin, primary.copy(alpha = 0.5f), RoundedCornerShape(AppSpacing.Corner.Xs))
            .padding(horizontal = AppSpacing.Sm, vertical = AppSpacing.Xxs)
    ) {
        Text(
            text = text,
            style = AppTypography.LabelSmallMedium,
            color = primary,
            maxLines = 1
        )
    }
}

// ── 5. 战备速览 ──

/**
 * 战备速览 — 演习剩余 + 今日副本待办
 */
@Composable
fun AssistantReadinessCard(
    exerciseRemaining: Int,
    exerciseTotal: Int,
    challenges: List<DailyChallenge>,
    modifier: Modifier = Modifier
) {
    val pending = challenges.filter { it.daily_challenge_remaining_attempts > 0 }
    AssistantCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AppSpacing.Md)
        ) {
            ReadinessCell(
                label = "演习场次",
                value = "$exerciseRemaining/$exerciseTotal",
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(AppSpacing.Border.Thin)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            )
            ReadinessCell(
                label = "待办副本",
                value = "${pending.size} 项",
                modifier = Modifier.weight(1f)
            )
        }

        Hairline()

        if (pending.isEmpty()) {
            Text(
                text = "今日副本已全部完成",
                style = AppTypography.BodySmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.Md)
            )
        } else {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.Md),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
            ) {
                pending.forEach { challenge ->
                    ChallengeChip(
                        name = challenge.daily_challenge_name,
                        remaining = challenge.daily_challenge_remaining_attempts
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadinessCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = AppSpacing.Sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
    ) {
        Text(
            text = label,
            style = AppTypography.LabelSmall,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)
        )
        Text(
            text = value,
            style = AppTypography.TitleSmallBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun ChallengeChip(name: String, remaining: Int) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppSpacing.Corner.Full))
            .background(primary.copy(alpha = 0.12f))
            .padding(horizontal = AppSpacing.Sm, vertical = AppSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
    ) {
        Text(
            text = name,
            style = AppTypography.CaptionMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "$remaining",
            style = AppTypography.CaptionSmall,
            fontWeight = FontWeight.SemiBold,
            color = primary
        )
    }
}
