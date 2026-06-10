package com.azurlane.blyy.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyChip
import com.azurlane.blyy.ui.components.BlyyPanel
import com.azurlane.blyy.ui.components.BlyyPrimaryButton
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
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
                            // 建造数量输入
                            androidx.compose.foundation.layout.Column {
                                Text(
                                    text = "条数",
                                    style = com.azurlane.blyy.ui.theme.AppTypography.LabelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                androidx.compose.foundation.text.BasicTextField(
                                    value = countInput,
                                    onValueChange = { countInput = it },
                                    modifier = Modifier
                                        .width(56.dp)
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                            RoundedCornerShape(AppSpacing.Corner.Sm)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    textStyle = AppTypography.BodyMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center
                                    ),
                                    singleLine = true,
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                                    )
                                )
                            }
                        }
                    }
                }

                // ── 加载指示器 ──
                if (state.isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.Lg),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // ── 错误提示 ──
                state.error?.let { error ->
                    BlyyPanel(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(AppSpacing.Md),
                            style = AppTypography.BodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // ── 查询结果展示（按当前模式显示，互不混淆） ──
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

/**
 * UID/服务器未配置提示卡片
 */
@Composable
private fun ConfigMissingHint(onNavigateToSettings: () -> Unit) {
    BlyyPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
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
private fun UserDetailResult(detail: com.azurlane.blyy.data.model.UserDetailData) {
    val u = detail.user_info
    val s = detail.statistics
    val p = detail.progress_tracking
    val c = detail.combat_overview

    BlyyPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AppSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
        ) {
            // 指挥官信息
            ResultRow(label = "指挥官", value = "${u.nickname} (Lv.${u.level})")
            ResultRow(label = "服务器", value = "${u.server} (UID:${u.uid})")
            ResultRow(label = "收集率", value = "${s.collection_rate} | 进度: ${s.mainline_progress}")
            ResultRow(label = "舰队", value = u.guild_name ?: "无")

            SectionDivider()

            // 资源信息
            ResultRow(label = "物资", value = "${s.coins_current}")
            ResultRow(label = "石油", value = "${s.oil_current}")
            ResultRow(label = "存粮", value = "${s.food_current}")
            ResultRow(label = "演习", value = "${c.exercise.today_remaining}/10")

            SectionDivider()

            // 进度信息
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
                ResultRow(
                    label = "待办副本",
                    value = todo.joinToString(", ") { it.daily_challenge_name }
                )
            } else {
                ResultRow(label = "今日副本", value = "已全部完成")
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
private fun BuildRecordResult(records: com.azurlane.blyy.data.model.BuildRecordData) {
    BlyyPanel(modifier = Modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier.padding(AppSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Xs)
        ) {
            // 头部信息
            item {
                Text(
                    text = "建造查询",
                    style = AppTypography.TitleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(AppSpacing.Xxs))
                Text(
                    text = "${records.nickname} (${records.uid}) | ${records.serverName}",
                    style = AppTypography.BodyMedium
                )
                Text(
                    text = "共${records.buildRecords.total_count}条，拉取${records.buildRecords.data.size}条",
                    style = AppTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(AppSpacing.Sm))
                SectionDivider()
                Spacer(modifier = Modifier.height(AppSpacing.Xs))
            }

            // 建造记录列表
            items(records.buildRecords.data) { record ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = record.roleName,
                        style = AppTypography.BodyMedium,
                        fontWeight = FontWeight.Medium
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
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = AppTypography.BodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 分隔线 */
@Composable
private fun SectionDivider() {
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
