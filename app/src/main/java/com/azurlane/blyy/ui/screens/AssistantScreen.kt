package com.azurlane.blyy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AssignmentTurnedIn
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurlane.blyy.data.model.BuildRecordData
import com.azurlane.blyy.data.model.UserDetailData
import com.azurlane.blyy.domain.BuildFilterResult
import com.azurlane.blyy.domain.BuildFilterSelection
import com.azurlane.blyy.domain.BuildRarityFilter
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyChip
import com.azurlane.blyy.ui.components.BlyyErrorState
import com.azurlane.blyy.ui.components.BlyyLoadingState
import com.azurlane.blyy.ui.components.BlyyPanel
import com.azurlane.blyy.ui.components.BlyyPrimaryButton
import com.azurlane.blyy.ui.components.BlyyEntrance
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.screens.assistant.AssistantProfileHeader
import com.azurlane.blyy.ui.screens.assistant.AssistantProgressCard
import com.azurlane.blyy.ui.screens.assistant.AssistantReadinessCard
import com.azurlane.blyy.ui.screens.assistant.AssistantSectionBanner
import com.azurlane.blyy.ui.screens.assistant.AssistantStatsGrid
import com.azurlane.blyy.ui.screens.assistant.BuildFilterSheet
import com.azurlane.blyy.ui.screens.assistant.StatItem
import com.azurlane.blyy.ui.screens.assistant.formatAmount
import com.azurlane.blyy.ui.screens.assistant.orDash
import com.azurlane.blyy.ui.theme.AppColors
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.ui.theme.LocalUiStyle
import com.azurlane.blyy.ui.theme.isCommandCenter
import com.azurlane.blyy.viewmodel.AssistantIntent
import com.azurlane.blyy.viewmodel.AssistantViewModel
import com.azurlane.blyy.viewmodel.QueryMode

/**
 * 碧蓝航线小助手页面
 *
 * 提供两种查询模式（对应 main.py 的两个指令）：
 * - 查玩家 (az) ：查询指挥官信息，档案布局对齐设计稿（身份卡 + 数据格 + 进度追踪 + 战备速览）
 * - 查建造 (azb) ：查询建造记录，支持「建造类型 / 稀有度」属性筛选
 *
 * UID 和服务器从设置页面自动读取，无需在本页面手动输入。
 */
@Composable
fun AssistantScreen(
    onBack: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    viewModel: AssistantViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val defaultUid by viewModel.defaultUid.collectAsStateWithLifecycle()
    val defaultServer by viewModel.defaultServer.collectAsStateWithLifecycle()

    // 建造数量输入
    var countInput by remember { mutableStateOf("10") }
    // 建造记录「属性筛选」弹窗
    var showFilterSheet by remember { mutableStateOf(false) }

    val canQuery = defaultUid.isNotBlank() && defaultServer.isNotBlank()

    AdaptiveScreenBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            BlyyTopBar(
                title = "碧蓝航线助手",
                subtitle = "指挥官数据查询",
                onBackClick = onBack
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(AppSpacing.Screen.Horizontal),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Gap.Md)
            ) {
                Spacer(modifier = Modifier.height(AppSpacing.Sm))

                // ── UID/服务器未配置提示 ──
                if (!canQuery) {
                    ConfigMissingHint(onNavigateToSettings = onNavigateToSettings)
                }

                // ── 模式选择 Chips ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
                ) {
                    BlyyChip(
                        label = "查玩家",
                        selected = state.queryMode == QueryMode.USER_DETAIL,
                        onClick = { viewModel.onIntent(AssistantIntent.SwitchMode(QueryMode.USER_DETAIL)) }
                    )
                    BlyyChip(
                        label = "查建造",
                        selected = state.queryMode == QueryMode.BUILD_RECORD,
                        onClick = { viewModel.onIntent(AssistantIntent.SwitchMode(QueryMode.BUILD_RECORD)) }
                    )
                }

                // ── 查询按钮区域 ──
                when (state.queryMode) {
                    QueryMode.USER_DETAIL -> {
                        BlyyPrimaryButton(
                            text = if (state.isLoading) "查询中..." else "查询玩家信息",
                            onClick = {
                                if (canQuery) {
                                    viewModel.onIntent(AssistantIntent.QueryUserDetail(defaultUid, defaultServer))
                                }
                            },
                            enabled = !state.isLoading && canQuery,
                            icon = Icons.Rounded.Search,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    QueryMode.BUILD_RECORD -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
                        ) {
                            BlyyPrimaryButton(
                                text = if (state.isLoading) "查询中..." else "查询建造记录",
                                onClick = {
                                    if (canQuery) {
                                        val count = countInput.toIntOrNull() ?: 10
                                        viewModel.onIntent(AssistantIntent.QueryBuildRecord(defaultUid, defaultServer, count))
                                    }
                                },
                                enabled = !state.isLoading && canQuery,
                                icon = Icons.Rounded.Search,
                                modifier = Modifier.weight(1f)
                            )
                            // 建造数量输入 — 增强版样式
                            StyledCountInput(
                                value = countInput,
                                onValueChange = { countInput = it }
                            )
                        }
                    }
                }

                // ── 建造记录属性筛选入口 ──
                if (state.queryMode == QueryMode.BUILD_RECORD && state.buildRecords != null) {
                    BuildFilterBar(
                        selection = state.buildSelection,
                        matched = state.buildFilter.rows.size,
                        total = state.buildFilter.total,
                        onClick = { showFilterSheet = true }
                    )
                }

                // ── 加载指示器 ──
                if (state.isLoading) {
                    BlyyLoadingState(
                        message = if (state.queryMode == QueryMode.USER_DETAIL) "正在查询指挥官信息..." else "正在查询建造记录...",
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // ── 错误提示 ──
                state.error?.let { error ->
                    BlyyErrorState(
                        message = error,
                        modifier = Modifier.fillMaxWidth(),
                        onRetry = {
                            when (state.queryMode) {
                                QueryMode.USER_DETAIL -> {
                                    if (canQuery) {
                                        viewModel.onIntent(AssistantIntent.QueryUserDetail(defaultUid, defaultServer))
                                    }
                                }
                                QueryMode.BUILD_RECORD -> {
                                    if (canQuery) {
                                        val count = countInput.toIntOrNull() ?: 10
                                        viewModel.onIntent(AssistantIntent.QueryBuildRecord(defaultUid, defaultServer, count))
                                    }
                                }
                            }
                        }
                    )
                }

                // ── 查询结果展示（按当前模式显示，互不混淆） ──
                // P0 修复：用 weight(1f) 限定结果区高度，避免 LazyColumn 在无限高度父容器中
                // 抛 IllegalArgumentException: Vertically scrolling component was measured with
                // an infinity maximum height constraints
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (state.queryMode) {
                        QueryMode.USER_DETAIL -> state.userDetail?.let { detail ->
                            UserDetailResult(detail = detail)
                        }
                        QueryMode.BUILD_RECORD -> state.buildRecords?.let { records ->
                            BuildRecordResult(
                                records = records,
                                filter = state.buildFilter,
                                hasRarityIndex = state.hasRarityIndex,
                                rarityFilterActive = state.rarityFilter != BuildRarityFilter.ALL,
                                onOpenFilter = { showFilterSheet = true }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showFilterSheet) {
        BuildFilterSheet(
            selection = state.buildSelection,
            hasRarityIndex = state.hasRarityIndex,
            unknownRarity = state.buildFilter.unknownRarity,
            onDismiss = { showFilterSheet = false },
            onReset = { viewModel.onIntent(AssistantIntent.ResetBuildFilter) },
            onConfirm = { selection ->
                viewModel.onIntent(AssistantIntent.ApplyBuildFilter(selection.pool, selection.rarity))
            }
        )
    }
}

/**
 * 属性筛选入口条 — 对齐设计稿图2的入口语义
 *
 * 展示当前筛选摘要与命中条数，点整条胶囊唤起筛选弹窗。
 */
@Composable
private fun BuildFilterBar(
    selection: BuildFilterSelection,
    matched: Int,
    total: Int,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(AppSpacing.Corner.Full)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(primary.copy(alpha = if (selection.isActive) 0.14f else 0.05f))
            .border(
                width = AppSpacing.Border.Thin,
                color = primary.copy(alpha = if (selection.isActive) 0.75f else 0.35f),
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Padding.ChipVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
    ) {
        androidx.compose.material3.Icon(
            imageVector = Icons.Rounded.FilterAlt,
            contentDescription = null,
            modifier = Modifier.size(AppSpacing.Icon.Sm),
            tint = primary
        )
        Text(
            text = "属性筛选",
            style = AppTypography.LabelLargeBold,
            color = primary
        )
        Text(
            text = selection.summary(),
            style = AppTypography.LabelMedium,
            color = if (selection.isActive) primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$matched/$total",
            style = AppTypography.LabelSmallMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * UID/服务器未配置提示 — 使用 BlyyErrorState 风格
 */
@Composable
private fun ConfigMissingHint(onNavigateToSettings: () -> Unit) {
    BlyyPanel(
        modifier = Modifier.fillMaxWidth(),
        accentColor = MaterialTheme.colorScheme.error
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 错误图标徽章
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                    )
                }
                Spacer(modifier = Modifier.width(AppSpacing.Md))
                Column {
                    Text(
                        text = "未配置查询参数",
                        style = AppTypography.TitleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "请先在设置中填入 UID 和服务器",
                        style = AppTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            BlyyPrimaryButton(
                text = "去设置",
                onClick = onNavigateToSettings
            )
        }
    }
}

/**
 * 玩家详情查询结果 —— 严格对齐设计稿（图1）
 *
 * 自上而下：
 * 1. 指挥官身份卡（头像 / 昵称 / Lv. / UID / 所属大舰队）
 * 2. 6 个数据格（图鉴收集率、主线进度、宿舍互动、石油、物资、食量）
 * 3. 「进度追踪 / PROGRESS TRACKING」分组带
 * 4. 军事委托 / 军部研究室 两张进度卡
 * 5. 「战备速览 / COMBAT READINESS」分组带 + 演习/副本待办
 *
 * 接口未返回的字段（宿舍互动）按设计稿显示 "--"，不做臆造。
 */
@Composable
private fun UserDetailResult(detail: UserDetailData) {
    val u = detail.user_info
    val s = detail.statistics
    val p = detail.progress_tracking
    val c = detail.combat_overview

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = AppSpacing.Gap.Section),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Gap.Md)
    ) {
        BlyyEntrance(index = 0) {
            AssistantProfileHeader(
                nickname = u.nickname,
                level = u.level,
                uid = u.uid,
                server = u.server,
                guildName = u.guild_name
            )
        }

        BlyyEntrance(index = 1) {
            AssistantStatsGrid(
                stats = listOf(
                    StatItem(label = "图鉴收集率", value = orDash(s.collection_rate)),
                    StatItem(label = "主线进度", value = orDash(s.mainline_progress)),
                    // 接口未提供宿舍互动数据，按设计稿占位
                    StatItem(label = "宿舍互动", value = "--"),
                    StatItem(label = "石油", value = formatAmount(s.oil_current)),
                    StatItem(label = "物资", value = formatAmount(s.coins_current)),
                    StatItem(label = "食量", value = formatAmount(s.food_current))
                )
            )
        }

        BlyyEntrance(index = 2) {
            AssistantSectionBanner(title = "进度追踪", subtitle = "PROGRESS TRACKING")
        }

        BlyyEntrance(index = 3) {
            AssistantProgressCard(
                icon = Icons.Rounded.AssignmentTurnedIn,
                title = "军事委托",
                inProgress = p.commissions.in_progress,
                completed = p.commissions.completed,
                idle = p.commissions.idle
            )
        }

        BlyyEntrance(index = 4) {
            AssistantProgressCard(
                icon = Icons.Rounded.Science,
                title = "军部研究室",
                inProgress = p.research.in_progress,
                completed = p.research.completed,
                idle = p.research.idle
            )
        }

        BlyyEntrance(index = 5) {
            AssistantSectionBanner(title = "战备速览", subtitle = "COMBAT READINESS")
        }

        BlyyEntrance(index = 6) {
            AssistantReadinessCard(
                exerciseRemaining = c.exercise.today_remaining,
                // 游戏每日演习次数上限为 10 次
                exerciseTotal = 10,
                challenges = c.daily_challenges
            )
        }
    }
}

/**
 * 建造记录查询结果
 *
 * 仅展示建造相关数据：玩家标识 + 属性筛选入口 + 过滤后的建造记录列表
 *
 * @param filter 已按当前筛选组合过滤的结果（含未知稀有度计数）
 * @param hasRarityIndex 本地图鉴是否已收录舰船
 * @param rarityFilterActive 当前是否启用了稀有度筛选
 */
@Composable
private fun BuildRecordResult(
    records: BuildRecordData,
    filter: BuildFilterResult,
    hasRarityIndex: Boolean,
    rarityFilterActive: Boolean,
    onOpenFilter: () -> Unit
) {
    BlyyPanel(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(AppSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
        ) {
            // 头部信息 — 卡片风格
            item {
                PlayerInfoCard(
                    nickname = records.nickname,
                    uid = records.uid,
                    serverName = records.serverName,
                    totalCount = records.buildRecords.total_count,
                    fetchedCount = records.buildRecords.data.size
                )
            }

            // 图鉴缺失时的稀有度筛选说明
            if (rarityFilterActive && !hasRarityIndex) {
                item {
                    Text(
                        text = "本地图鉴尚未收录舰船，稀有度筛选暂无命中，结果已按建造类型展示",
                        style = AppTypography.CaptionSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = AppSpacing.Xxs)
                    )
                }
            }

            // 分隔线
            item {
                GradientDivider()
                Spacer(modifier = Modifier.height(AppSpacing.Xs))
            }

            if (filter.rows.isEmpty()) {
                item {
                    Text(
                        text = if (filter.isNarrowed) {
                            "当前筛选条件下没有建造记录，可调整属性筛选后重试"
                        } else {
                            "暂无建造记录"
                        },
                        style = AppTypography.BodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = AppSpacing.Xl),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // 建造记录列表 — 交替行背景，稀有度着色
            itemsIndexed(
                items = filter.rows,
                key = { _, row -> "${row.sourceIndex}-${row.roleName}-${row.taskName}" }
            ) { index, row ->
                BuildRecordRowItem(
                    roleName = row.roleName,
                    taskName = row.taskName,
                    rarity = row.rarity,
                    isEvenRow = index % 2 == 0
                )
            }

            // 尾部：筛选说明 + 再次打开筛选
            item {
                Spacer(modifier = Modifier.height(AppSpacing.Sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "共 ${filter.rows.size} 条 / 已拉取 ${filter.total} 条，点「属性筛选」调整条件",
                        style = AppTypography.CaptionSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(AppSpacing.Corner.Xs))
                            .clickable(onClick = onOpenFilter)
                            .padding(horizontal = AppSpacing.Sm, vertical = AppSpacing.Xxs),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * 单条建造记录 — 舰名按稀有度着色，右侧标注建造池
 */
@Composable
private fun BuildRecordRowItem(
    roleName: String,
    taskName: String,
    rarity: String?,
    isEvenRow: Boolean
) {
    val nameColor = if (rarity != null) {
        AppColors.Rarity.getRarityColor(rarity)
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.Corner.Xs))
            .background(
                if (isEvenRow) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                else Color.Transparent
            )
            .padding(horizontal = AppSpacing.Sm, vertical = AppSpacing.Xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
        ) {
            Text(
                text = roleName,
                style = AppTypography.BodyMedium,
                fontWeight = FontWeight.Medium,
                color = nameColor,
                maxLines = 1
            )
            if (rarity != null) {
                Text(
                    text = rarity,
                    style = AppTypography.CaptionSmall,
                    color = nameColor.copy(alpha = 0.75f),
                    maxLines = 1
                )
            }
        }
        Text(
            text = taskName,
            style = AppTypography.LabelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/**
 * 玩家信息卡片 — 建造记录头部
 */
@Composable
private fun PlayerInfoCard(
    nickname: String,
    uid: String,
    serverName: String,
    totalCount: Int,
    fetchedCount: Int
) {
    val accentColor = MaterialTheme.colorScheme.primary
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.Corner.Sm))
            .background(
                if (isCommandCenter) {
                    Brush.linearGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.12f),
                            accentColor.copy(alpha = 0.03f)
                        )
                    )
                } else {
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.05f)
                        )
                    )
                }
            )
            .border(
                width = AppSpacing.Border.Thin,
                color = accentColor.copy(alpha = 0.2f),
                shape = RoundedCornerShape(AppSpacing.Corner.Sm)
            )
            .padding(AppSpacing.Md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 头像占位
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    accentColor.copy(alpha = 0.2f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Rounded.Build,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = accentColor
            )
        }

        Spacer(modifier = Modifier.width(AppSpacing.Md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = nickname,
                style = AppTypography.TitleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(AppSpacing.Xxs))
            Text(
                text = "$serverName | UID: $uid",
                style = AppTypography.BodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "$totalCount",
                style = AppTypography.TitleMedium,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
            Text(
                text = "总记录",
                style = AppTypography.LabelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Spacer(modifier = Modifier.height(AppSpacing.Xs))

    Text(
        text = "已拉取 $fetchedCount 条记录",
        style = AppTypography.BodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 渐变分隔线 */
@Composable
private fun GradientDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        Color.Transparent
                    )
                )
            )
    )
}

/**
 * 增强版数量输入框 — 类似 Settings 风格
 */
@Composable
private fun StyledCountInput(
    value: String,
    onValueChange: (String) -> Unit
) {
    val isDark = LocalIsDark.current
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val accentColor = MaterialTheme.colorScheme.primary

    Column {
        Text(
            text = "条数",
            style = AppTypography.LabelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(AppSpacing.Xxs))

        if (isCommandCenter) {
            // 指挥中心风格：渐变边框输入框
            val isFocused = value.isNotEmpty()
            val borderBrush = if (isFocused) {
                Brush.linearGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.8f),
                        AppColors.Accent.Gold.copy(alpha = 0.5f),
                        accentColor.copy(alpha = 0.6f)
                    )
                )
            } else {
                Brush.linearGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.2f),
                        accentColor.copy(alpha = 0.1f)
                    )
                )
            }

            Box(
                modifier = Modifier
                    .width(64.dp)
                    .clip(RoundedCornerShape(AppSpacing.Corner.Sm))
                    .background(
                        if (isDark) AppColors.Panel.Dark else AppColors.Panel.Light.copy(alpha = 0.9f)
                    )
                    .border(
                        width = if (isFocused) AppSpacing.Border.Normal else AppSpacing.Border.Thin,
                        brush = borderBrush,
                        shape = RoundedCornerShape(AppSpacing.Corner.Sm)
                    )
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.width(64.dp),
                    singleLine = true,
                    textStyle = AppTypography.BodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = accentColor
                    ),
                    shape = RoundedCornerShape(AppSpacing.Corner.Sm)
                )
            }
        } else {
            // 经典风格
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.width(64.dp),
                singleLine = true,
                textStyle = AppTypography.BodyMedium.copy(
                    textAlign = TextAlign.Center
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(AppSpacing.Corner.Sm)
            )
        }
    }
}
