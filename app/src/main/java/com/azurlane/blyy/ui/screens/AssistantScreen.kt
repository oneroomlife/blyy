package com.azurlane.blyy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TrendingUp
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurlane.blyy.data.model.BuildRecordData
import com.azurlane.blyy.data.model.UserDetailData
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyChip
import com.azurlane.blyy.ui.components.BlyyErrorState
import com.azurlane.blyy.ui.components.BlyyLoadingState
import com.azurlane.blyy.ui.components.BlyyPanel
import com.azurlane.blyy.ui.components.BlyyPrimaryButton
import com.azurlane.blyy.ui.components.BlyySectionPanel
import com.azurlane.blyy.ui.components.BlyyTopBar
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
 * - 查玩家 (az) ：查询指挥官信息（仅显示 main.py L107-116 定义的字段）
 * - 查建造 (azb) ：查询建造记录（仅展示建造相关数据）
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
                if (defaultUid.isBlank() || defaultServer.isBlank()) {
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
                                if (defaultUid.isNotBlank() && defaultServer.isNotBlank()) {
                                    viewModel.onIntent(AssistantIntent.QueryUserDetail(defaultUid, defaultServer))
                                }
                            },
                            enabled = !state.isLoading && defaultUid.isNotBlank() && defaultServer.isNotBlank(),
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
                                    if (defaultUid.isNotBlank() && defaultServer.isNotBlank()) {
                                        val count = countInput.toIntOrNull() ?: 10
                                        viewModel.onIntent(AssistantIntent.QueryBuildRecord(defaultUid, defaultServer, count))
                                    }
                                },
                                enabled = !state.isLoading && defaultUid.isNotBlank() && defaultServer.isNotBlank(),
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
                                    if (defaultUid.isNotBlank() && defaultServer.isNotBlank()) {
                                        viewModel.onIntent(AssistantIntent.QueryUserDetail(defaultUid, defaultServer))
                                    }
                                }
                                QueryMode.BUILD_RECORD -> {
                                    if (defaultUid.isNotBlank() && defaultServer.isNotBlank()) {
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
                            BuildRecordResult(records = records)
                        }
                    }
                }
            }
        }
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
 * 玩家详情查询结果
 *
 * 严格对应 main.py L107-116 的输出格式，仅显示以下信息：
 * - 指挥官名称和等级
 * - 服务器和 UID
 * - 收集率和进度
 * - 舰队
 * - 物资、石油
 * - 存粮、演习
 * - 委托进度
 * - 科研进度
 */
@Composable
private fun UserDetailResult(detail: UserDetailData) {
    val u = detail.user_info
    val s = detail.statistics
    val p = detail.progress_tracking
    val c = detail.combat_overview

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Gap.Md)
    ) {
        // ── 指挥官信息 ──
        BlyySectionPanel(
            title = "指挥官信息",
            icon = Icons.Rounded.Person,
            accentColor = MaterialTheme.colorScheme.primary
        ) {
            Column(
                modifier = Modifier.padding(AppSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
            ) {
                ResultRow(label = "指挥官", value = "${u.nickname} (Lv.${u.level})")
                ResultRow(label = "服务器", value = "${u.server} (UID:${u.uid})")
                ResultRow(label = "收集率", value = "${s.collection_rate} | 进度: ${s.mainline_progress}")
                ResultRow(label = "舰队", value = u.guild_name ?: "无")
            }
        }

        // ── 资源信息 ──
        BlyySectionPanel(
            title = "资源信息",
            icon = Icons.Rounded.Inventory2,
            accentColor = AppColors.Accent.Gold
        ) {
            Column(
                modifier = Modifier.padding(AppSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
            ) {
                IconBadgeRow(icon = Icons.Rounded.Inventory2, label = "物资", value = "${s.coins_current}", badgeColor = AppColors.Accent.Gold)
                IconBadgeRow(icon = Icons.Rounded.Person, label = "石油", value = "${s.oil_current}", badgeColor = AppColors.Accent.Cyan)
                ResultRow(label = "存粮", value = "${s.food_current}")
                ResultRow(label = "演习", value = "${c.exercise.today_remaining}/10")
            }
        }

        // ── 进度信息 ──
        BlyySectionPanel(
            title = "进度信息",
            icon = Icons.Rounded.TrendingUp,
            accentColor = MaterialTheme.colorScheme.tertiary
        ) {
            Column(
                modifier = Modifier.padding(AppSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
            ) {
                ResultRow(
                    label = "委托",
                    value = "进行${p.commissions.in_progress} | 完成${p.commissions.completed} | 空闲${p.commissions.idle}"
                )
                ResultRow(
                    label = "科研",
                    value = "进行${p.research.in_progress} | 完成${p.research.completed} | 空闲${p.research.idle}"
                )

                // 待办副本
                val todo = c.daily_challenges.filter { it.daily_challenge_remaining_attempts > 0 }
                if (todo.isNotEmpty()) {
                    GradientDivider()
                    ResultRow(
                        label = "待办副本",
                        value = todo.joinToString(", ") { it.daily_challenge_name }
                    )
                } else {
                    GradientDivider()
                    ResultRow(label = "今日副本", value = "已全部完成")
                }
            }
        }
    }
}

/**
 * 建造记录查询结果
 *
 * 仅展示建造相关数据：玩家标识 + 建造记录列表
 */
@Composable
private fun BuildRecordResult(records: BuildRecordData) {
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

            // 分隔线
            item {
                GradientDivider()
                Spacer(modifier = Modifier.height(AppSpacing.Xs))
            }

            // 建造记录列表 — 交替行背景
            itemsIndexed(records.buildRecords.data, key = { index, record -> "${record.roleName}-${record.taskName}-$index" }) { index, record ->
                val isEvenRow = index % 2 == 0
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
                    Text(
                        text = record.roleName,
                        style = AppTypography.BodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = record.taskName,
                        style = AppTypography.LabelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
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
                text = "${serverName} | UID: ${uid}",
                style = AppTypography.BodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${totalCount}",
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
        text = "已拉取 ${fetchedCount} 条记录",
        style = AppTypography.BodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 结果行：标签 + 值 */
@Composable
private fun ResultRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = AppTypography.LabelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 0.3.sp
        )
        Text(
            text = value,
            style = AppTypography.BodyMedium.copy(lineHeight = 20.sp),
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 带图标徽章的结果行 */
@Composable
private fun IconBadgeRow(
    icon: ImageVector,
    label: String,
    value: String,
    badgeColor: Color
) {
    val isDark = LocalIsDark.current
    // 暗色模式下将颜色向白色混合（提亮），确保在深色面板背景上对比度 ≥ 4.5:1（WCAG AA）
    // 浅色模式下将颜色向黑色混合（加深），确保在浅色背景上可读
    val valueColor = if (isDark) {
        androidx.compose.ui.graphics.lerp(badgeColor, Color.White, 0.25f)
    } else {
        androidx.compose.ui.graphics.lerp(badgeColor, Color.Black, 0.15f)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        badgeColor.copy(alpha = if (isDark) 0.25f else 0.15f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = valueColor
                )
            }
            Spacer(modifier = Modifier.width(AppSpacing.Sm))
            Text(
                text = label,
                style = AppTypography.LabelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = value,
            style = AppTypography.BodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
    }
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
