package com.azurlane.blyy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.data.model.ShipDetail
import com.azurlane.blyy.data.model.SkillRow
import com.azurlane.blyy.data.model.StatRow
import com.azurlane.blyy.ui.components.BlyyScreenScaffold
import com.azurlane.blyy.ui.components.BlyyTabRow
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.viewmodel.ShipDetailUiState
import com.azurlane.blyy.viewmodel.ShipDetailViewModel

/**
 * 舰娘详情页（站内档案）。
 *
 * 解决的老问题：以前点「Wiki」直接开外部浏览器，用户出去就回不来。
 * 现在站内先给出**三块**信息：
 * 1. 基本档案（本地 Room：阵营/舰种/稀有度/生日 + 头像，秒开、离线可用）；
 * 2. 数值与技能、装备（wiki 抓取 + `ship_cache` 缓存，可手动刷新）；
 * 3. 角色档案（性格/萌点等，来自既有 `fetchCharacterInfo` 链路）。
 *
 * 数据拿不到时不装作有：明确给"数据暂缺 → 去 Wiki"的出口。
 */
@Composable
fun ShipDetailScreen(
    shipName: String,
    archiveType: String,
    onBack: () -> Unit,
    onOpenVoice: (String, String) -> Unit,
    onOpenGallery: (String, String) -> Unit,
    onOpenWiki: (String) -> Unit,
    viewModel: ShipDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tabIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(shipName, archiveType) {
        viewModel.load(shipName, archiveType)
    }

    BlyyScreenScaffold(
        topBar = {
            BlyyTopBar(
                title = shipName,
                subtitle = "舰娘档案",
                onBackClick = onBack,
                actions = {
                    IconButton(onClick = { viewModel.refresh() }, enabled = !state.isRefreshing) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "刷新",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { onOpenWiki(shipName) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = "在 Wiki 打开",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 首次加载与手动刷新都要有可见反馈：详情页的四块数据来自不同链路，
            // 没有任何指示时页面会先渲染一排"暂未解析到…"，像是坏了
            if (state.isRefreshing || state.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = AppSpacing.Screen.Horizontal,
                    vertical = AppSpacing.Sm
                ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
            ) {
                item(key = "hero") {
                    HeroCard(state = state, onOpenVoice = onOpenVoice, onOpenGallery = onOpenGallery)
                }
                item(key = "tabs") {
                    val tabs = listOf("属性", "技能", "装备", "档案")
                    BlyyTabRow(
                        tabs = tabs,
                        selectedIndex = tabIndex,
                        onTabSelected = { tabIndex = it }
                    )
                }

                if (state.isLoading) {
                    item(key = "loading_hint") { EmptyHint("正在读取档案…") }
                } else {
                    when (tabIndex) {
                        0 -> statsItems(state.detail)
                        1 -> skillItems(state.detail)
                        2 -> equipmentItems(state.detail)
                        else -> profileItems(state, onOpenWiki = onOpenWiki)
                    }
                }

                item(key = "bottom_spacer") {
                    Spacer(modifier = Modifier.height(AppSpacing.Xxl))
                }
            }
        }
    }
}

// ─────────────────────────────── 头部 ───────────────────────────────

@Composable
private fun HeroCard(
    state: ShipDetailUiState,
    onOpenVoice: (String, String) -> Unit,
    onOpenGallery: (String, String) -> Unit
) {
    val ship = state.ship
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppSpacing.Corner.Md),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(AppSpacing.Md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(ship)
                Spacer(modifier = Modifier.width(AppSpacing.Md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.shipName,
                        style = AppTypography.TitleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.Xxs))
                    val chips = buildList {
                        ship?.faction?.takeIf { it.isNotBlank() }?.let { add(it) }
                        ship?.type?.takeIf { it.isNotBlank() }?.let { add(it) }
                        ship?.rarity?.takeIf { it.isNotBlank() }?.let { add(it) }
                        ship?.birthday?.takeIf { it.isNotBlank() }?.let { add("生日 $it") }
                    }
                    if (chips.isEmpty()) {
                        Text(
                            text = "基础资料待联网补全",
                            style = AppTypography.LabelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xxs)) {
                            chips.take(3).forEach { InfoChip(it) }
                        }
                        if (chips.size > 3) {
                            Spacer(modifier = Modifier.height(AppSpacing.Xxs))
                            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xxs)) {
                                chips.drop(3).forEach { InfoChip(it) }
                            }
                        }
                    }
                    val sub = state.detail.subtitle
                    if (sub.isNotBlank()) {
                        Spacer(modifier = Modifier.height(AppSpacing.Xxs))
                        Text(
                            text = sub,
                            style = AppTypography.LabelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.Md))
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                FilledTonalButton(onClick = { onOpenVoice(state.shipName, ship?.avatarUrl.orEmpty()) }) {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(AppSpacing.Xxs))
                    Text("语音")
                }
                FilledTonalButton(onClick = { onOpenGallery(state.shipName, ship?.avatarUrl.orEmpty()) }) {
                    Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(AppSpacing.Xxs))
                    Text("立绘")
                }
            }
        }
    }
}

@Composable
private fun Avatar(ship: Ship?) {
    val avatarUrl = ship?.avatarUrl.orEmpty()
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (avatarUrl.isNotBlank()) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = ship?.name?.take(1).orEmpty().ifBlank { "舰" },
                style = AppTypography.TitleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun InfoChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(AppSpacing.Corner.Sm))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .padding(horizontal = AppSpacing.Xs, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = AppTypography.LabelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

// ─────────────────────────────── 各 Tab 内容 ───────────────────────────────

private fun androidx.compose.foundation.lazy.LazyListScope.statsItems(detail: ShipDetail) {
    if (detail.stats.isEmpty()) {
        item(key = "stats_empty") { EmptyHint("暂未解析到属性数据，可点右上角刷新或前往 Wiki") }
        return
    }
    items(detail.stats, key = { "stat_${it.label}" }) { row -> StatRowItem(row) }
}

@Composable
private fun StatRowItem(row: StatRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.Corner.Sm))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = row.label,
            style = AppTypography.BodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = row.value,
            style = AppTypography.NumericHud,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.skillItems(detail: ShipDetail) {
    if (detail.skills.isEmpty()) {
        item(key = "skills_empty") { EmptyHint("暂未解析到技能数据，可点右上角刷新或前往 Wiki") }
        return
    }
    items(detail.skills, key = { "skill_${it.name}" }) { skill -> SkillItem(skill) }
}

@Composable
private fun SkillItem(skill: SkillRow) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppSpacing.Corner.Md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(AppSpacing.Md)) {
            Text(
                text = skill.name,
                style = AppTypography.TitleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            if (skill.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(AppSpacing.Xxs))
                Text(
                    text = skill.description,
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.equipmentItems(detail: ShipDetail) {
    if (detail.equipment.isEmpty()) {
        item(key = "equip_empty") { EmptyHint("暂未解析到装备数据，可点右上角刷新或前往 Wiki") }
        return
    }
    items(detail.equipment, key = { "equip_$it" }) { name ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppSpacing.Corner.Sm))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "·", style = AppTypography.BodyMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(AppSpacing.Xs))
            Text(
                text = name,
                style = AppTypography.BodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    if (detail.acquire.isNotBlank()) {
        item(key = "acquire") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AppSpacing.Corner.Md),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Column(modifier = Modifier.padding(AppSpacing.Md)) {
                    Text(
                        text = "获取方式",
                        style = AppTypography.LabelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = detail.acquire,
                        style = AppTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.profileItems(
    state: ShipDetailUiState,
    onOpenWiki: (String) -> Unit
) {
    val profile = state.profile
    val rows = buildList {
        if (profile.identity.isNotBlank()) add("身份" to profile.identity)
        if (profile.personality.isNotBlank()) add("性格" to profile.personality)
        if (profile.keywords.isNotBlank()) add("关键词" to profile.keywords)
        if (profile.possessions.isNotBlank()) add("持有物" to profile.possessions)
        if (profile.hairColor.isNotBlank()) add("发色" to profile.hairColor)
        if (profile.eyeColor.isNotBlank()) add("瞳色" to profile.eyeColor)
        if (profile.moePoints.isNotBlank()) add("萌点" to profile.moePoints)
        if (state.detail.cv.isNotBlank()) add("声优" to state.detail.cv)
        if (state.detail.illustrator.isNotBlank()) add("画师" to state.detail.illustrator)
    }
    if (rows.isEmpty()) {
        item(key = "profile_empty") {
            EmptyHint("角色档案暂缺，可前往 Wiki 查看完整设定")
        }
    } else {
        items(rows, key = { "profile_${it.first}" }) { (label, value) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AppSpacing.Corner.Sm))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = label,
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(64.dp)
                )
                Text(
                    text = value,
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
    item(key = "profile_wiki") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            FilledTonalButton(onClick = { onOpenWiki(state.shipName) }) {
                Icon(
                    Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(AppSpacing.Xxs))
                Text("在 Wiki 查看完整资料")
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.Corner.Md))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(AppSpacing.Lg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = AppTypography.BodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
