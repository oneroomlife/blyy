package com.azurlane.blyy.ui.screens

/*
 * 啾信新建会话/群聊弹层 — 自 ConversationListScreen.kt 机械拆分而来，纯移动零逻辑改动。
 * JuusNewChatSheet / JuusNewGroupSheet 与其私有助手（SectionHeader / ApiConfigRow /
 * PersonaConfigRow / JuusEmptyState）随迁，助手保持 private。
 */
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.azurlane.blyy.util.findActivityViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurlane.blyy.data.model.ChatSession
import com.azurlane.blyy.data.model.ApiConfig
import com.azurlane.blyy.data.model.PersonaConfig
import com.azurlane.blyy.ui.components.BlyyBottomSheet
import com.azurlane.blyy.ui.components.BlyyConfirmDialog
import com.azurlane.blyy.ui.components.BlyyHaptic
import com.azurlane.blyy.ui.components.RobustAvatar
import com.azurlane.blyy.ui.components.rememberBlyyHaptics
import com.azurlane.blyy.ui.theme.AppAnimation
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.JuusPalette
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.viewmodel.JiuxinViewModel

/**
 * 新建聊天界面
 *
 * 单一流程：选择 API 配置 → 选择舰娘人格 → 开始对话。
 * 两项均可不选（回退到当前全局配置），但至少需要 API 可用才能发送消息。
 */
@Composable
internal fun JuusNewChatSheet(
    apiConfigs: List<ApiConfig>,
    personaConfigs: List<PersonaConfig>,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onStart: (apiConfigId: String?, personaConfigId: String?) -> Unit
) {
    val titleColor = if (isDark) JuusPalette.ListPage.Dark.TextPrimary else JuusPalette.ListPage.TextPrimary
    val hintColor = if (isDark) JuusPalette.ListPage.Dark.TextSecondary else JuusPalette.ListPage.TextSecondary
    val primaryColor = if (isDark) JuusPalette.ListPage.Dark.Primary else JuusPalette.ListPage.Primary
    val itemDividerColor = if (isDark) JuusPalette.Dark.Divider else JuusPalette.Divider
    val cardBg = if (isDark) JuusPalette.ListPage.Dark.SheetCard else JuusPalette.ListPage.SheetCard
    val sectionBg = if (isDark) JuusPalette.ListPage.Dark.SheetSection else JuusPalette.ListPage.SheetSection
    val selectedBg = if (isDark) JuusPalette.ListPage.Dark.SheetSelectedBg else JuusPalette.ListPage.SheetSelectedBg

    var selectedApiConfigId by remember { mutableStateOf<String?>(null) }
    var selectedPersonaConfigId by remember { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()

    BlyyBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().background(cardBg)) {
            // ── 标题栏 ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = null,
                        tint = primaryColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "新建聊天",
                        style = AppTypography.TitleMediumBold,
                        color = titleColor
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "关闭",
                        tint = hintColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // ── 提示文字 ──
            Text(
                text = "选择 API 配置和舰娘人格后开始对话，未选择则使用当前全局配置",
                style = AppTypography.CaptionMedium,
                color = hintColor.copy(alpha = 0.8f),
                modifier = Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Xxs)
            )

            // ── 内容区（可滚动） ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(scrollState)
            ) {
                // ── API 配置选择 ──
                JuusNewChatSectionHeader(
                    title = "API 配置",
                    count = apiConfigs.size,
                    trailing = if (selectedApiConfigId != null) "已选择" else "可选",
                    primaryColor = primaryColor,
                    hintColor = hintColor,
                    sectionBg = sectionBg
                )
                if (apiConfigs.isNotEmpty()) {
                    apiConfigs.forEach { config ->
                        JuusApiConfigRow(
                            config = config,
                            isSelected = selectedApiConfigId == config.id,
                            titleColor = titleColor,
                            hintColor = hintColor,
                            primaryColor = primaryColor,
                            selectedBg = selectedBg,
                            itemDividerColor = itemDividerColor,
                            onClick = {
                                selectedApiConfigId = if (selectedApiConfigId == config.id) null else config.id
                            }
                        )
                    }
                } else {
                    JuusEmptyState(
                        icon = Icons.Rounded.Key,
                        title = "暂无 API 配置",
                        subtitle = "未选择时使用当前全局 API 配置",
                        hintColor = hintColor,
                        compact = true
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // ── 舰娘人格选择 ──
                JuusNewChatSectionHeader(
                    title = "舰娘人格",
                    count = personaConfigs.size,
                    trailing = if (selectedPersonaConfigId != null) "已选择" else "可选",
                    primaryColor = primaryColor,
                    hintColor = hintColor,
                    sectionBg = sectionBg
                )
                if (personaConfigs.isNotEmpty()) {
                    personaConfigs.forEach { config ->
                        JuusPersonaConfigRow(
                            config = config,
                            isSelected = selectedPersonaConfigId == config.id,
                            titleColor = titleColor,
                            hintColor = hintColor,
                            primaryColor = primaryColor,
                            selectedBg = selectedBg,
                            itemDividerColor = itemDividerColor,
                            onClick = {
                                selectedPersonaConfigId = if (selectedPersonaConfigId == config.id) null else config.id
                            }
                        )
                    }
                } else {
                    JuusEmptyState(
                        icon = Icons.Rounded.Psychology,
                        title = "暂无舰娘人格",
                        subtitle = "未选择时使用当前全局舰娘人格",
                        hintColor = hintColor,
                        compact = true
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ── 底部固定：选择摘要 + 开始按钮 ──
            val apiSelected = apiConfigs.firstOrNull { it.id == selectedApiConfigId }
            val personaSelected = personaConfigs.firstOrNull { it.id == selectedPersonaConfigId }
            val comboSummary = buildString {
                append("API: ")
                append(apiSelected?.name ?: "当前")
                append("  ·  舰娘: ")
                append(personaSelected?.name ?: "当前")
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(sectionBg)
                    .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = comboSummary,
                    style = AppTypography.CaptionMedium,
                    color = hintColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Xs)
                    .clip(RoundedCornerShape(AppSpacing.Corner.Xs2))
                    .background(primaryColor)
                    .clickable {
                        onStart(selectedApiConfigId, selectedPersonaConfigId)
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "开始对话",
                    style = AppTypography.LabelLargeSemiBold,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/**
 * 新建聊天页 - 分区标题
 */
@Composable
private fun JuusNewChatSectionHeader(
    title: String,
    count: Int,
    trailing: String,
    primaryColor: Color,
    hintColor: Color,
    sectionBg: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(sectionBg)
            .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(3.dp, 12.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(primaryColor)
        )
        Text(
            text = title,
            style = AppTypography.LabelMediumSemiBold,
            color = primaryColor
        )
        Text(
            text = "($count)",
            style = AppTypography.CaptionMedium,
            color = hintColor
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = trailing,
            style = AppTypography.CaptionSmall,
            color = hintColor.copy(alpha = 0.7f)
        )
    }
}

/**
 * 新建聊天页 - API 配置行（可选）
 */
@Composable
private fun JuusApiConfigRow(
    config: ApiConfig,
    isSelected: Boolean,
    titleColor: Color,
    hintColor: Color,
    primaryColor: Color,
    selectedBg: Color,
    itemDividerColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) selectedBg else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = AppSpacing.Lg, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (isSelected) primaryColor.copy(alpha = 0.2f) else JuusPalette.ListPage.ChannelEmojiBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Key,
                contentDescription = null,
                tint = if (isSelected) primaryColor else primaryColor.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = config.name.ifBlank { "未命名 API 配置" },
                style = AppTypography.TitleSmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                color = if (isSelected) primaryColor else titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    if (config.model.isNotBlank()) append(config.model)
                    if (config.apiUrl.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        val url = config.apiUrl
                        append(if (url.length > 35) url.take(35) + "…" else url)
                    }
                    if (isEmpty()) append("无 URL")
                },
                style = AppTypography.CaptionMedium,
                color = hintColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isSelected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = "已选择",
                tint = primaryColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Lg)
            .height(1.dp)
            .background(itemDividerColor)
    )
}

/**
 * 新建聊天页 - 舰娘人格行（可选）
 */
@Composable
private fun JuusPersonaConfigRow(
    config: PersonaConfig,
    isSelected: Boolean,
    titleColor: Color,
    hintColor: Color,
    primaryColor: Color,
    selectedBg: Color,
    itemDividerColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) selectedBg else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = AppSpacing.Lg, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (isSelected) primaryColor.copy(alpha = 0.15f) else JuusPalette.ListPage.ChannelEmojiBg),
            contentAlignment = Alignment.Center
        ) {
            RobustAvatar(
                url = config.avatarUrl,
                modifier = Modifier.size(40.dp).clip(CircleShape),
                fallbackContent = {
                    Icon(
                        Icons.Rounded.Psychology,
                        contentDescription = null,
                        tint = primaryColor.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = config.name.ifBlank { "未命名舰娘" },
                style = AppTypography.TitleSmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                color = if (isSelected) primaryColor else titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    if (config.jiuxinName.isNotBlank()) append(config.jiuxinName)
                    if (config.voiceShipName.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append("语音: ${config.voiceShipName}")
                    }
                    if (isEmpty()) append("点击选择")
                },
                style = AppTypography.CaptionMedium,
                color = hintColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isSelected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = "已选择",
                tint = primaryColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Lg)
            .height(1.dp)
            .background(itemDividerColor)
    )
}

/**
 * 新建聊天页 - 空状态提示
 */
@Composable
private fun JuusEmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    hintColor: Color,
    compact: Boolean = false
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Lg, vertical = if (compact) 10.dp else AppSpacing.Lg),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                icon,
                contentDescription = null,
                tint = hintColor.copy(alpha = 0.4f),
                modifier = Modifier.size(if (compact) 24.dp else 32.dp)
            )
            Text(
                text = title,
                style = AppTypography.BodyMedium,
                color = hintColor
            )
            Text(
                text = subtitle,
                style = AppTypography.CaptionMedium,
                color = hintColor.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * 新建群聊界面
 *
 * 流程：输入群聊名称 → 多选舰娘人格（至少 2 位）→ 创建群聊。
 * 与新建聊天页保持一致的玻璃质感设计语言和交互模式。
 *
 * 成员选择为多选模式（复选），区别于单聊的单选。
 */
@Composable
internal fun JuusNewGroupSheet(
    personaConfigs: List<PersonaConfig>,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onCreate: (groupName: String, memberIds: List<String>) -> Unit
) {
    val titleColor = if (isDark) JuusPalette.ListPage.Dark.TextPrimary else JuusPalette.ListPage.TextPrimary
    val hintColor = if (isDark) JuusPalette.ListPage.Dark.TextSecondary else JuusPalette.ListPage.TextSecondary
    val primaryColor = if (isDark) JuusPalette.ListPage.Dark.Primary else JuusPalette.ListPage.Primary
    val itemDividerColor = if (isDark) JuusPalette.Dark.Divider else JuusPalette.Divider
    val cardBg = if (isDark) JuusPalette.ListPage.Dark.SheetCard else JuusPalette.ListPage.SheetCard
    val sectionBg = if (isDark) JuusPalette.ListPage.Dark.SheetSection else JuusPalette.ListPage.SheetSection
    val selectedBg = if (isDark) JuusPalette.ListPage.Dark.SheetSelectedBg else JuusPalette.ListPage.SheetSelectedBg
    val fieldBg = if (isDark) JuusPalette.ListPage.Dark.SheetFieldBg else JuusPalette.ListPage.SheetFieldBg
    val fieldBorder = if (isDark) JuusPalette.ListPage.Dark.SheetFieldBorder else JuusPalette.ListPage.SheetFieldBorder

    var groupName by remember { mutableStateOf("") }
    var selectedMemberIds by remember { mutableStateOf(setOf<String>()) }
    val scrollState = rememberScrollState()

    BlyyBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().background(cardBg)) {
            // ── 标题栏 ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Rounded.Person,
                        contentDescription = null,
                        tint = primaryColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "新建群聊",
                        style = AppTypography.TitleMediumBold,
                        color = titleColor
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "关闭",
                        tint = hintColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // ── 提示文字 ──
            Text(
                text = "设置群聊名称并选择至少 2 位舰娘成员",
                style = AppTypography.CaptionMedium,
                color = hintColor.copy(alpha = 0.8f),
                modifier = Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Xxs)
            )

            // ── 群聊名称输入 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm)
                    .clip(RoundedCornerShape(AppSpacing.Corner.Md))
                    .background(fieldBg)
                    .border(1.dp, fieldBorder, RoundedCornerShape(AppSpacing.Corner.Md))
                    .padding(horizontal = 14.dp, vertical = AppSpacing.Xs)
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = groupName,
                    onValueChange = { if (it.length <= 24) groupName = it },
                    singleLine = true,
                    textStyle = AppTypography.BodyMedium.copy(color = titleColor),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(primaryColor),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (groupName.isEmpty()) {
                                Text(
                                    text = "群聊名称（选填，默认自动生成）",
                                    style = AppTypography.BodyMedium,
                                    color = hintColor.copy(alpha = 0.6f)
                                )
                            }
                            innerTextField()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ── 成员选择区（可滚动） ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(scrollState)
            ) {
                JuusNewChatSectionHeader(
                    title = "选择成员",
                    count = selectedMemberIds.size,
                    trailing = "共 ${personaConfigs.size} 位 · 至少 2 位",
                    primaryColor = primaryColor,
                    hintColor = hintColor,
                    sectionBg = sectionBg
                )
                if (personaConfigs.isNotEmpty()) {
                    personaConfigs.forEach { config ->
                        val isSelected = config.id in selectedMemberIds
                        JuusPersonaConfigRow(
                            config = config,
                            isSelected = isSelected,
                            titleColor = titleColor,
                            hintColor = hintColor,
                            primaryColor = primaryColor,
                            selectedBg = selectedBg,
                            itemDividerColor = itemDividerColor,
                            onClick = {
                                selectedMemberIds = if (isSelected) {
                                    selectedMemberIds - config.id
                                } else {
                                    selectedMemberIds + config.id
                                }
                            }
                        )
                    }
                } else {
                    JuusEmptyState(
                        icon = Icons.Rounded.Psychology,
                        title = "暂无舰娘人格",
                        subtitle = "请先在啾信配置中创建舰娘人格",
                        hintColor = hintColor,
                        compact = true
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ── 底部固定：已选摘要 + 创建按钮 ──
            val selectedNames = personaConfigs.filter { it.id in selectedMemberIds }
                .joinToString("、") { it.name.ifBlank { "未命名" } }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(sectionBg)
                    .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = if (selectedNames.isBlank()) "尚未选择成员" else "已选 ${selectedMemberIds.size} 位: $selectedNames",
                    style = AppTypography.CaptionMedium,
                    color = hintColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val canCreate = selectedMemberIds.size >= 2
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Xs)
                    .clip(RoundedCornerShape(AppSpacing.Corner.Xs2))
                    .background(if (canCreate) primaryColor else primaryColor.copy(alpha = 0.35f))
                    .clickable(enabled = canCreate) {
                        onCreate(groupName.trim(), selectedMemberIds.toList())
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (canCreate) "创建群聊" else "请至少选择 2 位成员",
                    style = AppTypography.LabelLargeSemiBold,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
