package com.azurlane.blyy.ui.screens.guess

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.azurlane.blyy.data.model.SkillRow
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography

/**
 * 「技能识舰娘」玩法的专属组件。
 *
 * 题面从"一枚技能图标"升级为"该舰娘的**全部技能图标**"：
 * 单看一枚图标时，"专属弹幕 / 阵营通用技能"这类图标几乎只能靠猜；整组图标
 * （通常是专属技能 + 1–2 个被动）才构得成这艘船的"技能指纹"。
 *
 * 与看图/听音的共享组件分开的原因：这里的题面是**一组会加载失败的网络图标**，
 * 必须显式处理 loading / error / 重试三态，否则网络抖动时玩家会看到空白卡却不知道能不能作答。
 */

private val SkillIconShape = RoundedCornerShape(AppSpacing.Corner.Lg)

/**
 * 技能图标题面卡。
 *
 * @param skills 当前舰娘的全部可用技能（1–5 个）；空列表 = 题目尚未就绪（加载中/失败）
 * @param isHard 困难模式：只显示图标，不显示技能名与描述
 * @param isLoading 正在抓取题目（wiki 技能表还没回来）
 * @param errorMessage 出题失败原因
 */
@Composable
fun GuessSkillQuestionCard(
    skills: List<SkillRow>,
    isHard: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    modifier: Modifier = Modifier
) {
    val ready = skills.isNotEmpty()
    GuessQuestionFrame(
        label = "技能图标",
        icon = Icons.Rounded.AutoAwesome,
        badge = when {
            ready && isHard -> "仅图标"
            ready -> "${skills.size} 个技能"
            else -> null
        },
        modifier = modifier
    ) {
        when {
            ready -> {
                SkillIconRow(
                    skills = skills,
                    showNames = !isHard,
                    iconSize = iconSizeFor(skills.size)
                )

                if (!isHard) {
                    SkillDescriptionBlock(skills = skills)
                } else {
                    Text(
                        text = "困难模式：只给图标，答出这 ${skills.size} 个技能属于哪位舰娘",
                        style = AppTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            errorMessage != null -> {
                Icon(
                    imageVector = Icons.Rounded.BrokenImage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(44.dp)
                )
                Text(
                    text = errorMessage,
                    style = AppTypography.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "点击下方「下一题」重新出题",
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            else -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(34.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "正在抓取技能图标…",
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 图标尺寸随数量收缩：1 个放大展示，3 个以上排成一行也放得下 */
private fun iconSizeFor(count: Int): Dp = when {
    count <= 1 -> 132.dp
    count == 2 -> 112.dp
    count == 3 -> 88.dp
    else -> 72.dp
}

/**
 * 技能图标行 — 一题一次展示该舰娘的全部技能。
 *
 * 用固定列宽（= 图标尺寸）而不是 `weight`：图标之间的间距才是视觉主角，
 * 数量变化时每枚图标仍是同一尺寸，不会被拉伸成大小不一。
 */
@Composable
internal fun SkillIconRow(
    skills: List<SkillRow>,
    showNames: Boolean,
    iconSize: Dp
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Top
    ) {
        skills.forEach { skill ->
            Column(
                modifier = Modifier.width(iconSize),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // 主色柔光垫底：技能图标多为彩色徽章，直接贴灰底会显脏
                    Box(
                        modifier = Modifier
                            .size(iconSize + 24.dp)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                        Color.Transparent
                                    )
                                ),
                                shape = CircleShape
                            )
                    )
                    Surface(
                        modifier = Modifier.size(iconSize),
                        shape = SkillIconShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        border = androidx.compose.foundation.BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        )
                    ) {
                        SkillIconImage(
                            iconUrl = skill.iconUrl,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(AppSpacing.Sm)
                        )
                    }
                }
                if (showNames) {
                    Text(
                        text = skill.name,
                        style = AppTypography.LabelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** 技能说明合并块（简单模式）：一个容器里逐条列出，避免每枚图标下面各挂一大段文字 */
@Composable
private fun SkillDescriptionBlock(skills: List<SkillRow>) {
    val described = skills.filter { it.description.isNotBlank() }
    if (described.isEmpty()) return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppSpacing.Corner.Md),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
        ) {
            described.forEachIndexed { index, skill ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
                    ) {
                        Text(
                            text = "${index + 1}. ${skill.name}",
                            style = AppTypography.LabelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (skill.badge.isNotBlank()) {
                            Text(
                                text = skill.badge,
                                style = AppTypography.LabelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    Text(
                        text = skill.description,
                        style = AppTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 技能图标（remote）。
 *
 * 失败态给"重试"入口：图标就是题面，图挂掉时若只能点"下一题"，
 * 玩家会白丢一题（跳过计入 skipped），所以这里允许原地重试。
 */
@Composable
fun SkillIconImage(
    iconUrl: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit
) {
    val context = LocalContext.current
    var state by remember(iconUrl) {
        mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty)
    }
    var retry by remember(iconUrl) { mutableIntStateOf(0) }

    val request = remember(iconUrl, retry) {
        ImageRequest.Builder(context)
            .data(iconUrl)
            // 失败结果不进 Coil 缓存，但用独立的 cacheKey 保证"重试"一定重新发起请求
            .memoryCacheKey("skill_icon_${iconUrl.hashCode()}_$retry")
            .diskCacheKey("skill_icon_${iconUrl.hashCode()}")
            .crossfade(true)
            .build()
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AsyncImage(
            model = request,
            contentDescription = "技能图标",
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale,
            onState = { state = it }
        )

        when (state) {
            is AsyncImagePainter.State.Loading, AsyncImagePainter.State.Empty -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(AppSpacing.Icon.Lg),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                )
            }

            is AsyncImagePainter.State.Error -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BrokenImage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                        modifier = Modifier.size(AppSpacing.Icon.Lg)
                    )
                    TextButton(onClick = { retry++ }) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(AppSpacing.Icon.Sm)
                        )
                        Spacer(Modifier.width(AppSpacing.Xxs))
                        Text("重试", style = AppTypography.LabelSmall)
                    }
                }
            }

            else -> Unit
        }
    }
}

/**
 * 识技能答案揭示卡：整组技能图标 + 名称/描述 + 答案舰娘。
 *
 * 与看图/听音的 [GuessAnswerCard] 分开：那边只需要"答案：xxx"，这边要让玩家
 * 看清**这组图标到底对应哪些技能**（尤其困难模式，作答时完全看不到技能名）。
 */
@Composable
fun GuessSkillAnswerCard(
    shipName: String,
    skills: List<SkillRow>,
    shipAvatarUrl: String? = null,
    rewardImageUrl: String? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(AppSpacing.Corner.Xl),
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
        ) {
            Column(
                modifier = Modifier.padding(AppSpacing.Lg),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
            ) {
                Row(
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
                            imageVector = Icons.Rounded.Lightbulb,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(AppSpacing.Icon.Lg)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "答案：$shipName",
                            style = AppTypography.TitleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "本题不得分",
                            style = AppTypography.BodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                        )
                    }
                    if (!shipAvatarUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = shipAvatarUrl,
                            contentDescription = shipName,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    }
                }

                if (skills.isNotEmpty()) {
                    // 图标不挂名字：下面紧跟的明细列表已给出完整名称，
                    // 72dp 列宽塞长技能名只会断成"独角兽的应 / 援"这种两行碎片
                    SkillIconRow(
                        skills = skills,
                        showNames = false,
                        iconSize = if (skills.size <= 1) 96.dp else 80.dp
                    )
                    SkillDetailList(skills = skills)
                }
            }
        }

        GuessRewardImage(rewardImageUrl)
    }
}

/** 技能明细（名称 + 标记 + 描述），答案卡与困难模式揭示共用 */
@Composable
private fun SkillDetailList(skills: List<SkillRow>) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
        skills.forEach { skill ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
                ) {
                    Text(
                        text = skill.name,
                        style = AppTypography.LabelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (skill.badge.isNotBlank()) {
                        Text(
                            text = skill.badge,
                            style = AppTypography.LabelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                if (skill.description.isNotBlank()) {
                    Text(
                        text = skill.description,
                        style = AppTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.9f),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 困难模式答对后的技能揭示。
 *
 * 困难模式作答时看不到技能名，答对后必须补上"这组图标分别是哪几个技能"，
 * 否则玩家只知道答对了船，不知道图标与技能的对应关系，学习闭环断掉。
 */
@Composable
fun GuessSkillReveal(skills: List<SkillRow>) {
    AnimatedVisibility(
        visible = skills.isNotEmpty(),
        enter = fadeIn(tween(180)) + scaleInSafe(),
        exit = fadeOut(tween(140))
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(AppSpacing.Corner.Md),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Column(
                modifier = Modifier.padding(AppSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
            ) {
                Text(
                    text = "这道题的技能",
                    style = AppTypography.LabelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                skills.forEach { skill ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
                    ) {
                        Surface(
                            modifier = Modifier.size(32.dp),
                            shape = SkillIconShape,
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            SkillIconImage(
                                iconUrl = skill.iconUrl,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(2.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = skill.name,
                                style = AppTypography.TitleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (skill.description.isNotBlank()) {
                                Text(
                                    text = skill.description,
                                    style = AppTypography.BodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 校验中提示（共享技能校验要走一次缓存/网络查询） */
@Composable
fun GuessCheckingBanner(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(120))
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(AppSpacing.Corner.Md),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(AppSpacing.Icon.Sm),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "正在核对该舰娘的技能表…",
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 小工具：需要"轻微弹入"但没有弹簧参数的手写场景 */
private fun scaleInSafe() = androidx.compose.animation.scaleIn(
    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
)
