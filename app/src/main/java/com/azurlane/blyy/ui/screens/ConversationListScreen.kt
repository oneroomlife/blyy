package com.azurlane.blyy.ui.screens

// JUUSTAGRAM 会话列表页（玻璃质感设计）
// 设计来源：UI_work/juustagram-messaging-ui/pages/conversation-list-v2.html
// 第五轮优化：删除筛选/管理UI、长按进入编辑模式、设置图标跳转、玻璃质感设计

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurlane.blyy.data.model.ChatSession
import com.azurlane.blyy.data.model.ApiConfig
import com.azurlane.blyy.data.model.PersonaConfig
import com.azurlane.blyy.ui.components.BlyyBottomSheet
import com.azurlane.blyy.ui.theme.JuusPalette
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.viewmodel.JiuxinViewModel

// ── JUUSTAGRAM 会话列表页色板（玻璃质感设计） ──
private object JuusListColors {
    // 背景渐变
    val BgGradientStart = Color(0xFFD6EFFF)
    val BgGradientEnd = Color(0xFFE8F4FE)

    val Primary = JuusPalette.Primary
    val PrimaryLight = JuusPalette.PrimaryLight
    val TextPrimary = JuusPalette.TextPrimary
    val TextSecondary = JuusPalette.TextSecondary
    val TextTertiary = JuusPalette.TextTertiary
    val Divider = JuusPalette.Divider

    // 导航栏渐变
    val NavGradientTop = Color(0xFF7DD3FC)
    val NavGradientBottom = Color(0xFF38BDF8)

    // 玻璃表面 — 提高不透明度补偿移除阴影后的深度感
    val GlassCard = Color(0xD9FFFFFF)          // 85% 白 — 通透且有实体感
    val GlassCardSelected = Color(0xF0F0F7FF)   // 94% 白微蓝 — 选中态
    val GlassHeader = Color(0xE6FFFFFF)         // 90% 白 — 明亮玻璃胶囊
    val GlassPill = Color(0x99FFFFFF)           // 60% 白
    val GlassEditBadge = Color(0x335BA4E6)
    val ChannelEmojiBg = Color(0x33BAE6FD)
    val ErrorRed = Color(0xFFE53935)

    // 玻璃边框 — 仅用极淡白色提供边缘定义，不与shadow叠加
    val GlassBorder = Color(0x33FFFFFF)         // 20% 白 — 极淡边框
    val GlassBorderSelected = Color(0x665BA4E6) // 40% 蓝 — 选中态
    // 玻璃高光 — 顶部内边缘模拟光反射
    val GlassHighlight = Color(0x55FFFFFF)      // 33% 白 — 顶部高光

    object Dark {
        val BgGradientStart = Color(0xFF0A1525)
        val BgGradientEnd = Color(0xFF102035)

        val Primary = JuusPalette.Dark.Primary
        val PrimaryLight = JuusPalette.Dark.PrimaryLight
        val TextPrimary = JuusPalette.Dark.TextPrimary
        val TextSecondary = JuusPalette.Dark.TextSecondary
        val TextTertiary = JuusPalette.Dark.TextTertiary
        val Divider = JuusPalette.Dark.Divider

        val GlassCard = Color(0xCC1E293B)
        val GlassCardSelected = Color(0xE6243B55)
        val GlassHeader = Color(0xE61E293B)
        val GlassPill = Color(0x991E293B)
        val GlassEditBadge = Color(0x335BA4E6)
        val ChannelEmojiBg = Color(0x33243559)
        val ErrorRed = Color(0xFFFF6B6B)

        val GlassBorder = Color(0x15FFFFFF)
        val GlassBorderSelected = Color(0x405BA4E6)
        val GlassHighlight = Color(0x22FFFFFF)
    }
}

/**
 * JUUSTAGRAM 会话列表页（玻璃质感设计）
 *
 * 布局：[左导航栏 56dp 玻璃渐变] [主内容区：玻璃标题栏 + 玻璃会话卡片列表]
 *
 * 交互逻辑：
 * - 正常模式：点击切换会话，长按进入编辑模式
 * - 编辑模式：长按拖动排序，点击删除按钮删除会话，完成按钮/返回键退出
 */
@Composable
fun ConversationListScreen(
    onBack: () -> Unit,
    onNavigateToChat: () -> Unit,
    onNavigateToConfig: () -> Unit,
    viewModel: JiuxinViewModel = hiltViewModel()
) {
    val conversations by viewModel.uniqueConversations.collectAsStateWithLifecycle()
    val currentSessionId by viewModel.currentSessionId.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val apiConfigs by viewModel.apiConfigs.collectAsStateWithLifecycle()
    val personaConfigs by viewModel.personaConfigs.collectAsStateWithLifecycle()
    val apiUrl by viewModel.apiUrl.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val isDark = LocalIsDark.current
    val context = LocalContext.current

    var showPlusDropdown by remember { mutableStateOf(false) }
    var showNewChatSheet by remember { mutableStateOf(false) }
    var showDeleteSessionConfirm by remember { mutableStateOf<ChatSession?>(null) }
    var showConfigMissingDialog by remember { mutableStateOf(false) }
    // 编辑模式：长按会话项进入，可拖动排序+删除；完成按钮或返回键退出
    var isEditMode by remember { mutableStateOf(false) }

    // 返回键在编辑模式下退出编辑模式，而非导航返回
    BackHandler(enabled = isEditMode) {
        isEditMode = false
    }

    // 背景渐变
    val bgGradient = Brush.verticalGradient(
        colors = listOf(
            if (isDark) JuusListColors.Dark.BgGradientStart else JuusListColors.BgGradientStart,
            if (isDark) JuusListColors.Dark.BgGradientEnd else JuusListColors.BgGradientEnd
        )
    )
    val primaryColor = if (isDark) JuusListColors.Dark.Primary else JuusListColors.Primary

    Box(modifier = Modifier.fillMaxSize().background(bgGradient).imePadding()) {
        Row(modifier = Modifier.fillMaxSize()) {
            // ── 左导航栏（56dp，玻璃渐变） ──
            JuusLeftNavRail(
                isDark = isDark,
                onBack = onBack,
                onNavigateToConfig = onNavigateToConfig
            )

            // ── 主内容区 ──
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                JuusListHeader(
                    isDark = isDark,
                    isEditMode = isEditMode,
                    onPlusClick = { showPlusDropdown = true },
                    onMenuClick = onBack,
                    showPlusDropdown = showPlusDropdown,
                    onNewChatClick = {
                        showPlusDropdown = false
                        showNewChatSheet = true
                    },
                    onDismissDropdown = { showPlusDropdown = false },
                    onExitEditMode = { isEditMode = false }
                )

                // 会话列表
                if (conversations.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Rounded.ChatBubbleOutline,
                                contentDescription = null,
                                tint = primaryColor.copy(alpha = 0.4f),
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = "暂无对话",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isDark) JuusListColors.Dark.TextPrimary else JuusListColors.TextPrimary
                            )
                            Text(
                                text = "点击右上角加号新建对话",
                                fontSize = 13.sp,
                                color = if (isDark) JuusListColors.Dark.TextTertiary else JuusListColors.TextTertiary
                            )
                        }
                    }
                } else {
                    // ── 会话列表 ──
                    // 手势隔离方案（第五轮）：
                    // - 正常模式：点击切换会话，长按进入编辑模式
                    // - 编辑模式：长按拖动排序，右侧删除按钮，完成按钮退出
                    val listState = rememberLazyListState()
                    val orderedList = remember(conversations) { mutableStateListOf(*conversations.toTypedArray()) }
                    LaunchedEffect(conversations) {
                        if (orderedList.toList() != conversations) {
                            orderedList.clear()
                            orderedList.addAll(conversations)
                        }
                    }
                    // 拖动状态（仅编辑模式下使用）
                    var draggingIndex by remember { mutableStateOf<Int?>(null) }
                    var dragOffsetY by remember { mutableStateOf(0f) }
                    val itemHeightPx = with(LocalDensity.current) { 76.dp.toPx() }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp
                        )
                    ) {
                        items(orderedList.size, key = { orderedList[it].id }) { index ->
                            val session = orderedList[index]
                            val isDragging = draggingIndex == index
                            val isSelected = session.id == currentSessionId
                            val associatedPreset = presets.firstOrNull { it.id == session.presetId }
                            val effectiveAvatar = session.avatarUrl.ifBlank { associatedPreset?.avatarUrl ?: "" }
                            val effectiveName = session.jiuxinName.ifBlank { associatedPreset?.name ?: session.name }
                            val hasPreset = session.presetId.isNotBlank()
                            val lastPreview = formatSessionPreview(session.updatedAt)

                            Box(
                                modifier = Modifier
                                    .zIndex(if (isDragging) 1f else 0f)
                                    .graphicsLayer {
                                        if (isDragging) {
                                            translationY = dragOffsetY
                                            shadowElevation = 8f
                                            alpha = 0.95f
                                        }
                                    }
                            ) {
                                JuusConversationItem(
                                    displayName = effectiveName,
                                    avatarUrl = effectiveAvatar,
                                    preview = lastPreview,
                                    isSelected = isSelected,
                                    isDark = isDark,
                                    hasPreset = hasPreset,
                                    isEditMode = isEditMode,
                                    gestureModifier = if (isEditMode) {
                                        // 编辑模式：长按拖动排序
                                        Modifier.pointerInput(orderedList.size) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    draggingIndex = index
                                                    dragOffsetY = 0f
                                                },
                                                onDragEnd = {
                                                    draggingIndex?.let { _ ->
                                                        viewModel.reorderConversations(orderedList.map { it.id })
                                                    }
                                                    draggingIndex = null
                                                    dragOffsetY = 0f
                                                },
                                                onDragCancel = {
                                                    orderedList.clear()
                                                    orderedList.addAll(conversations)
                                                    draggingIndex = null
                                                    dragOffsetY = 0f
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    dragOffsetY += dragAmount.y
                                                    val fromIndex = draggingIndex
                                                    if (fromIndex != null) {
                                                        val targetOffset = (dragOffsetY / itemHeightPx).toInt()
                                                        val toIndex = (fromIndex + targetOffset).coerceIn(0, orderedList.lastIndex)
                                                        if (toIndex != fromIndex && toIndex in orderedList.indices) {
                                                            val item = orderedList.removeAt(fromIndex)
                                                            orderedList.add(toIndex, item)
                                                            draggingIndex = toIndex
                                                            dragOffsetY = 0f
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    } else {
                                        // 正常模式：点击切换会话，长按进入编辑模式
                                        Modifier.pointerInput(session.id) {
                                            detectTapGestures(
                                                onTap = {
                                                    viewModel.switchToSession(session.id)
                                                    onNavigateToChat()
                                                },
                                                onLongPress = {
                                                    isEditMode = true
                                                }
                                            )
                                        }
                                    },
                                    onDeleteClick = { showDeleteSessionConfirm = session }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 新建聊天配置界面 ──
    if (showNewChatSheet) {
        JuusNewChatSheet(
            apiConfigs = apiConfigs,
            personaConfigs = personaConfigs,
            isDark = isDark,
            onDismiss = { showNewChatSheet = false },
            onStart = { apiConfigId, personaConfigId ->
                val effectiveApiReady = if (apiConfigId != null) {
                    apiConfigs.firstOrNull { it.id == apiConfigId }?.let { it.apiUrl.isNotBlank() && it.apiKey.isNotBlank() } ?: false
                } else {
                    apiUrl.isNotBlank() && apiKey.isNotBlank()
                }
                if (!effectiveApiReady) {
                    showNewChatSheet = false
                    showConfigMissingDialog = true
                    return@JuusNewChatSheet
                }
                viewModel.startChatWithApiAndPersona(apiConfigId, personaConfigId)
                showNewChatSheet = false
                val apiName = apiConfigId?.let { id -> apiConfigs.firstOrNull { it.id == id }?.name } ?: "当前 API"
                val personaName = personaConfigId?.let { id -> personaConfigs.firstOrNull { it.id == id }?.name } ?: "当前人格"
                Toast.makeText(context, "已应用 $apiName · $personaName 并开始对话", Toast.LENGTH_SHORT).show()
                onNavigateToChat()
            }
        )
    }

    // ── 删除会话确认弹窗 ──
    showDeleteSessionConfirm?.let { session ->
        AlertDialog(
            onDismissRequest = { showDeleteSessionConfirm = null },
            title = { Text("删除聊天") },
            text = {
                val displayName = session.jiuxinName.ifBlank { session.name.ifBlank { "此聊天" } }
                Text("确定要删除「$displayName」吗？\n同一舰娘的所有历史对话都将被移除。此操作不可撤销。")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSessionsByShip(session.id)
                    showDeleteSessionConfirm = null
                    Toast.makeText(context, "已删除该舰娘的所有对话", Toast.LENGTH_SHORT).show()
                }) { Text("删除", color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSessionConfirm = null }) { Text("取消") }
            }
        )
    }

    // ── API 配置缺失提示弹窗 ──
    if (showConfigMissingDialog) {
        AlertDialog(
            onDismissRequest = { showConfigMissingDialog = false },
            title = { Text("需要先配置 API") },
            text = {
                Text("当前配置缺少 API URL 或 API 密钥，无法发送消息。\n是否前往配置页面完成设置？")
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfigMissingDialog = false
                    onNavigateToConfig()
                }) { Text("去配置", color = if (isDark) JuusListColors.Dark.Primary else JuusListColors.Primary) }
            },
            dismissButton = {
                TextButton(onClick = { showConfigMissingDialog = false }) { Text("取消") }
            }
        )
    }
}

// ── 左导航栏（56dp，玻璃渐变 #7DD3FC → #38BDF8 + 玻璃覆盖层） ──
@Composable
private fun JuusLeftNavRail(
    isDark: Boolean,
    onBack: () -> Unit,
    onNavigateToConfig: () -> Unit
) {
    val gradient = Brush.verticalGradient(
        colors = listOf(JuusListColors.NavGradientTop, JuusListColors.NavGradientBottom)
    )

    Column(
        modifier = Modifier
            .width(56.dp)
            .fillMaxHeight()
            .background(gradient),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 顶部：JUUS// 文字
        Text(
            text = "JUUS//",
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(top = 14.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        // 中间：聊天气泡图标（玻璃质感 — 无阴影，仅边框+背景）
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.85f))
                .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.ChatBubbleOutline,
                contentDescription = "消息",
                tint = JuusListColors.Primary,
                modifier = Modifier.size(24.dp)
            )
        }

        // 白色分隔线
        Box(
            modifier = Modifier
                .padding(vertical = 12.dp)
                .width(30.dp)
                .height(1.5.dp)
                .background(Color.White.copy(alpha = 0.5f))
        )

        // 底部：设置图标（玻璃质感 — 无阴影，与上方图标风格统一）
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.85f))
                .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                .clickable(onClick = onNavigateToConfig),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Settings,
                contentDescription = "设置",
                tint = JuusListColors.Primary,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ── 列表头部（玻璃标题栏 + 加号/菜单按钮，编辑模式下显示完成按钮） ──
@Composable
private fun JuusListHeader(
    isDark: Boolean,
    isEditMode: Boolean,
    onPlusClick: () -> Unit,
    onMenuClick: () -> Unit,
    showPlusDropdown: Boolean = false,
    onNewChatClick: () -> Unit = {},
    onDismissDropdown: () -> Unit = {},
    onExitEditMode: () -> Unit = {}
) {
    val titleColor = if (isDark) JuusListColors.Dark.TextPrimary else JuusListColors.TextPrimary
    val subtitleColor = if (isDark) JuusListColors.Dark.TextSecondary else JuusListColors.TextSecondary
    val primaryColor = if (isDark) JuusListColors.Dark.Primary else JuusListColors.Primary
    val dropdownSurface = if (isDark) Color(0xFF1E293B) else Color.White
    val glassHeaderColor = if (isDark) JuusListColors.Dark.GlassHeader else JuusListColors.GlassHeader
    val glassBorderColor = if (isDark) JuusListColors.Dark.GlassBorder else JuusListColors.GlassBorder

    // 玻璃标题栏 — 无阴影，仅边框+背景，避免阴影透过半透明表面产生双线
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(glassHeaderColor)
            .border(1.dp, glassBorderColor, RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // 左侧：标题
        Column {
            Text(
                text = if (isEditMode) "编辑模式" else "JUUSTAGRAM",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (isEditMode) primaryColor else titleColor,
                letterSpacing = 1.sp
            )
            if (!isEditMode) {
                Text(
                    text = "消息",
                    fontSize = 11.sp,
                    color = subtitleColor.copy(alpha = 0.6f)
                )
            } else {
                Text(
                    text = "拖动排序 · 点击删除",
                    fontSize = 11.sp,
                    color = primaryColor.copy(alpha = 0.7f)
                )
            }
        }

        // 右侧：操作按钮
        if (isEditMode) {
            // 编辑模式：完成按钮 — 无border，仅背景色
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(primaryColor.copy(alpha = 0.2f))
                    .clickable(onClick = onExitEditMode)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = "完成",
                    tint = primaryColor,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "完成",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = primaryColor
                )
            }
        } else {
            // 正常模式：加号 + 菜单
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box {
                    IconButton(onClick = onPlusClick, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = "新建会话",
                            tint = primaryColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showPlusDropdown,
                        onDismissRequest = onDismissDropdown,
                        modifier = Modifier
                            .background(dropdownSurface, RoundedCornerShape(12.dp))
                            .width(160.dp)
                    ) {
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.Add,
                                        contentDescription = null,
                                        tint = primaryColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "新建聊天",
                                        fontSize = 14.sp,
                                        color = if (isDark) JuusListColors.Dark.TextPrimary else JuusListColors.TextPrimary
                                    )
                                }
                            },
                            onClick = onNewChatClick
                        )
                    }
                }
                IconButton(onClick = onMenuClick, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Rounded.Menu,
                        contentDescription = "菜单",
                        tint = subtitleColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

// ── 会话项（玻璃卡片 + 蓝色边框选中态 + 预设徽章） ──
// 手势方案（第五轮）：
// - 正常模式：gestureModifier 绑定 detectTapGestures，点击切换会话，长按进入编辑模式
// - 编辑模式：gestureModifier 绑定 detectDragGesturesAfterLongPress，长按拖动排序，
//   右侧显示删除图标按钮，点击删除触发确认对话框
@Composable
private fun JuusConversationItem(
    displayName: String,
    avatarUrl: String,
    preview: String,
    isSelected: Boolean,
    isDark: Boolean,
    hasPreset: Boolean = false,
    isEditMode: Boolean = false,
    gestureModifier: Modifier = Modifier,
    onDeleteClick: () -> Unit = {}
) {
    val nameColor = if (isDark) JuusListColors.Dark.TextPrimary else JuusListColors.TextPrimary
    val previewColor = if (isDark) JuusListColors.Dark.TextSecondary else JuusListColors.TextSecondary
    val primaryColor = if (isDark) JuusListColors.Dark.Primary else JuusListColors.Primary
    val cardBg = if (isSelected) {
        if (isDark) JuusListColors.Dark.GlassCardSelected else JuusListColors.GlassCardSelected
    } else {
        if (isDark) JuusListColors.Dark.GlassCard else JuusListColors.GlassCard
    }
    val borderColor = if (isSelected) {
        if (isDark) JuusListColors.Dark.GlassBorderSelected else JuusListColors.GlassBorderSelected
    } else {
        if (isDark) JuusListColors.Dark.GlassBorder else JuusListColors.GlassBorder
    }
    val borderWidth = if (isSelected) 1.dp else 0.5.dp
    val errorColor = if (isDark) JuusListColors.Dark.ErrorRed else JuusListColors.ErrorRed

    // 玻璃卡片 — 无shadow，仅clip+background+border，避免阴影透过半透明表面产生双线伪影
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cardBg)
            .border(borderWidth, borderColor, RoundedCornerShape(16.dp))
            .then(gestureModifier)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 头像 48dp + 预设关联徽章
            Box(contentAlignment = Alignment.BottomEnd) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(JuusListColors.ChannelEmojiBg),
                    contentAlignment = Alignment.Center
                ) {
                    RobustAvatar(
                        url = avatarUrl,
                        modifier = Modifier.size(48.dp).clip(CircleShape),
                        fallbackContent = {
                            Icon(
                                Icons.Rounded.Person,
                                contentDescription = null,
                                tint = primaryColor.copy(alpha = 0.5f),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    )
                }
                // 预设关联小徽章 — 用纯白边框避免半透明边框模糊
                if (hasPreset) {
                    Box(
                        modifier = Modifier
                            .offset(x = (-2).dp, y = (-2).dp)
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(primaryColor)
                            .border(1.5.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Bookmark,
                            contentDescription = "已关联预设",
                            tint = Color.White,
                            modifier = Modifier.size(8.dp)
                        )
                    }
                }
            }

            // 右侧内容：名称 + 预览
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName.ifBlank { "啾信对话" },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = nameColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = preview,
                    fontSize = 13.sp,
                    color = previewColor.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // 编辑模式下显示删除按钮 — 无border，仅背景色，避免嵌套半透明浑浊
            if (isEditMode) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(errorColor.copy(alpha = 0.15f))
                        .clickable(onClick = onDeleteClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "删除会话",
                        tint = errorColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

private fun formatSessionPreview(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000}分钟前"
        diff < 86_400_000 -> "今天"
        diff < 604_800_000 -> "${diff / 86_400_000}天前"
        else -> "更早"
    }
}

/**
 * 新建聊天界面
 *
 * 单一流程：选择 API 配置 → 选择舰娘人格 → 开始对话。
 * 两项均可不选（回退到当前全局配置），但至少需要 API 可用才能发送消息。
 */
@Composable
private fun JuusNewChatSheet(
    apiConfigs: List<ApiConfig>,
    personaConfigs: List<PersonaConfig>,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onStart: (apiConfigId: String?, personaConfigId: String?) -> Unit
) {
    val titleColor = if (isDark) JuusListColors.Dark.TextPrimary else JuusListColors.TextPrimary
    val hintColor = if (isDark) JuusListColors.Dark.TextSecondary else JuusListColors.TextSecondary
    val primaryColor = if (isDark) JuusListColors.Dark.Primary else JuusListColors.Primary
    val itemDividerColor = if (isDark) JuusPalette.Dark.Divider else JuusPalette.Divider
    val cardBg = if (isDark) Color(0xFF1E293B) else Color.White
    val sectionBg = if (isDark) Color(0xFF161922) else Color(0xFFF8FAFC)
    val selectedBg = if (isDark) Color(0xFF1E2A44) else Color(0xFFE6F2FF)

    var selectedApiConfigId by remember { mutableStateOf<String?>(null) }
    var selectedPersonaConfigId by remember { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()

    BlyyBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().background(cardBg)) {
            // ── 标题栏 ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
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
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
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
                fontSize = 11.sp,
                color = hintColor.copy(alpha = 0.8f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
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
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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
                    fontSize = 11.sp,
                    color = hintColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(primaryColor)
                    .clickable {
                        onStart(selectedApiConfigId, selectedPersonaConfigId)
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "开始对话",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
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
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = primaryColor
        )
        Text(
            text = "($count)",
            fontSize = 11.sp,
            color = hintColor
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = trailing,
            fontSize = 10.sp,
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (isSelected) primaryColor.copy(alpha = 0.2f) else JuusListColors.ChannelEmojiBg),
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
                fontSize = 14.sp,
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
                fontSize = 11.sp,
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
            .padding(horizontal = 16.dp)
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (isSelected) primaryColor.copy(alpha = 0.15f) else JuusListColors.ChannelEmojiBg),
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
                fontSize = 14.sp,
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
                fontSize = 11.sp,
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
            .padding(horizontal = 16.dp)
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
            .padding(horizontal = 16.dp, vertical = if (compact) 10.dp else 16.dp),
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
                fontSize = 13.sp,
                color = hintColor
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = hintColor.copy(alpha = 0.7f)
            )
        }
    }
}
