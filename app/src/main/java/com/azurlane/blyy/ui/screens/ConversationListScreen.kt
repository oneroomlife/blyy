package com.azurlane.blyy.ui.screens

// JUUSTAGRAM 会话列表页（玻璃质感设计）
// 设计来源：UI_work/juustagram-messaging-ui/pages/conversation-list-v2.html
// 第五轮优化：删除筛选/管理UI、长按进入编辑模式、设置图标跳转、玻璃质感设计

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
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.azurlane.blyy.util.findActivityViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurlane.blyy.data.model.ChatSession
import com.azurlane.blyy.data.model.ApiConfig
import com.azurlane.blyy.data.model.PersonaConfig
import com.azurlane.blyy.ui.components.BlyyBottomSheet
import com.azurlane.blyy.ui.theme.AppAnimation
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.JuusPalette
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.viewmodel.JiuxinViewModel

// ── JUUSTAGRAM 会话列表页 ──
// 色板统一归口到 ui/theme/Color.kt 的 JuusPalette.ListPage（含 Dark 子对象）

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
    viewModel: JiuxinViewModel = hiltViewModel(
        // 优先使用 Activity 作为 ViewModelStoreOwner，确保跨页面共享同一 ViewModel 实例。
        // 通过 findActivityViewModelStoreOwner() 解包 ContextWrapper，
        // 避免直接 `as ViewModelStoreOwner` 强转在部分设备/ROM 上抛出 ClassCastException 导致闪退。
        viewModelStoreOwner = LocalContext.current.findActivityViewModelStoreOwner()
            ?: LocalViewModelStoreOwner.current
            ?: error("No ViewModelStoreOwner available in Context hierarchy")
    )
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
    var showNewGroupSheet by remember { mutableStateOf(false) }
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
            if (isDark) JuusPalette.ListPage.Dark.BgGradientStart else JuusPalette.ListPage.BgGradientStart,
            if (isDark) JuusPalette.ListPage.Dark.BgGradientEnd else JuusPalette.ListPage.BgGradientEnd
        )
    )
    val primaryColor = if (isDark) JuusPalette.ListPage.Dark.Primary else JuusPalette.ListPage.Primary

    Box(modifier = Modifier.fillMaxSize().background(bgGradient)) {
        Row(modifier = Modifier.fillMaxSize()) {
            // ── 左导航栏（56dp，玻璃渐变） ──
            // 注意：导航栏不应用 imePadding — 它没有输入字段，键盘弹出时应保持全高，
            // 避免高度变化导致内部 weight spacer 重新分配引起图标位移抽搐。
            // imePadding 仅作用于主内容区（下方 Column）。
            JuusLeftNavRail(
                onBack = onBack,
                onNavigateToConfig = onNavigateToConfig
            )

            // ── 主内容区 ──
            // imePadding 仅在此处应用：键盘弹出时只有主内容区收缩，导航栏保持全高
            Column(modifier = Modifier.weight(1f).fillMaxHeight().imePadding()) {
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
                    onNewGroupClick = {
                        showPlusDropdown = false
                        showNewGroupSheet = true
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
                                style = AppTypography.TitleMedium,
                                color = if (isDark) JuusPalette.ListPage.Dark.TextPrimary else JuusPalette.ListPage.TextPrimary
                            )
                            Text(
                                text = "点击右上角加号新建对话",
                                style = AppTypography.BodyMedium,
                                color = if (isDark) JuusPalette.ListPage.Dark.TextTertiary else JuusPalette.ListPage.TextTertiary
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
                        items(orderedList.size, key = { orderedList[it].id }, contentType = { "conversation" }) { index ->
                            val session = orderedList[index]
                            val isDragging = draggingIndex == index
                            val isSelected = session.id == currentSessionId
                            val associatedPreset = presets.firstOrNull { it.id == session.presetId }
                            val effectiveAvatar = session.avatarUrl.ifBlank { associatedPreset?.avatarUrl ?: "" }
                            val effectiveName = session.jiuxinName.ifBlank { associatedPreset?.name ?: session.name }
                            val hasPreset = session.presetId.isNotBlank()
                            val isGroup = session.isGroup
                            // 派生数据缓存：groupMemberAvatars 在 session 数据未变时不应重新分配 List
                            val groupMemberAvatars = remember(session.id, session.groupMembers) {
                                if (isGroup) session.groupMembers.map { it.avatarUrl } else emptyList()
                            }
                            val groupMemberCount = remember(session.id, session.groupMembers) {
                                if (isGroup) session.groupMembers.size else 0
                            }
                            // lastPreview 缓存：避免 System.currentTimeMillis() 每次返回不同值破坏 item 跳过
                            val lastPreview = remember(session.id, session.updatedAt, isGroup, groupMemberCount) {
                                if (isGroup) {
                                    "$groupMemberCount 位成员 · ${formatSessionPreview(session.updatedAt)}"
                                } else {
                                    formatSessionPreview(session.updatedAt)
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .animateItem()
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
                                    isGroup = isGroup,
                                    groupMemberAvatars = groupMemberAvatars,
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
                                    onDeleteClick = remember(session.id) {
                                        { showDeleteSessionConfirm = session }
                                    }
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

    // ── 新建群聊界面 ──
    if (showNewGroupSheet) {
        JuusNewGroupSheet(
            personaConfigs = personaConfigs,
            isDark = isDark,
            onDismiss = { showNewGroupSheet = false },
            onCreate = { groupName, memberIds ->
                // API 配置校验：群聊使用全局 API 配置
                if (apiUrl.isBlank() || apiKey.isBlank()) {
                    showNewGroupSheet = false
                    showConfigMissingDialog = true
                    return@JuusNewGroupSheet
                }
                val newId = viewModel.createGroupSession(groupName, memberIds)
                showNewGroupSheet = false
                if (newId.isNotBlank()) {
                    Toast.makeText(context, "已创建群聊「${groupName.ifBlank { "群聊" }}」", Toast.LENGTH_SHORT).show()
                    onNavigateToChat()
                } else {
                    Toast.makeText(context, "创建失败：请至少选择 2 位成员", Toast.LENGTH_SHORT).show()
                }
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
                }) { Text("去配置", color = if (isDark) JuusPalette.ListPage.Dark.Primary else JuusPalette.ListPage.Primary) }
            },
            dismissButton = {
                TextButton(onClick = { showConfigMissingDialog = false }) { Text("取消") }
            }
        )
    }
}

// ── 左导航栏（56dp，玻璃渐变 #7DD3FC → #38BDF8 + 玻璃覆盖层） ──
// 布局稳定性设计：使用 Box + Alignment 替代 Column + weight(1f)，
// 顶部文字锚定 TopCenter，底部图标组锚定 BottomCenter。
// 消除 weight spacer 对父容器高度的依赖，任何高度变化都不会导致图标位移。
@Composable
private fun JuusLeftNavRail(
    onBack: () -> Unit,
    onNavigateToConfig: () -> Unit
) {
    val gradient = Brush.verticalGradient(
        colors = listOf(JuusPalette.ListPage.NavGradientTop, JuusPalette.ListPage.NavGradientBottom)
    )

    Box(
        modifier = Modifier
            .width(56.dp)
            .fillMaxHeight()
            .background(gradient),
        contentAlignment = Alignment.TopCenter
    ) {
        // 顶部：JUUS// 文字 — 锚定到顶部，不随高度变化移动
        Text(
            text = "JUUS//",
            style = AppTypography.CaptionSmall.copy(fontWeight = FontWeight.ExtraBold),
            color = Color.White,
            modifier = Modifier.padding(top = 14.dp)
        )

        // 底部图标组 — 锚定到 BottomCenter，不使用 weight spacer
        // 即使父容器高度变化（虽然 imePadding 已隔离），图标位置也仅随底边变化，
        // 不会出现 weight 重新分配导致的"抽搐"位移
        Column(
            modifier = Modifier.align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 聊天气泡图标（玻璃质感 — 无阴影，仅边框+背景）
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
                    tint = JuusPalette.ListPage.Primary,
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

            // 设置图标（玻璃质感 — 无阴影，与上方图标风格统一）
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
                    tint = JuusPalette.ListPage.Primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
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
    onNewGroupClick: () -> Unit = {},
    onDismissDropdown: () -> Unit = {},
    onExitEditMode: () -> Unit = {}
) {
    val titleColor = if (isDark) JuusPalette.ListPage.Dark.TextPrimary else JuusPalette.ListPage.TextPrimary
    val subtitleColor = if (isDark) JuusPalette.ListPage.Dark.TextSecondary else JuusPalette.ListPage.TextSecondary
    val primaryColor = if (isDark) JuusPalette.ListPage.Dark.Primary else JuusPalette.ListPage.Primary
    val dropdownSurface = if (isDark) JuusPalette.ListPage.Dark.DropdownSurface else JuusPalette.ListPage.DropdownSurface
    val glassHeaderColor = if (isDark) JuusPalette.ListPage.Dark.GlassHeader else JuusPalette.ListPage.GlassHeader
    val glassBorderColor = if (isDark) JuusPalette.ListPage.Dark.GlassBorder else JuusPalette.ListPage.GlassBorder

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
                style = AppTypography.TitleMediumBold,
                color = if (isEditMode) primaryColor else titleColor,
                letterSpacing = 1.sp
            )
            if (!isEditMode) {
                Text(
                    text = "消息",
                    style = AppTypography.CaptionMedium,
                    color = subtitleColor.copy(alpha = 0.6f)
                )
            } else {
                Text(
                    text = "拖动排序 · 点击删除",
                    style = AppTypography.CaptionMedium,
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
                    style = AppTypography.LabelLargeSemiBold,
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
                                        style = AppTypography.BodyMedium,
                                        color = if (isDark) JuusPalette.ListPage.Dark.TextPrimary else JuusPalette.ListPage.TextPrimary
                                    )
                                }
                            },
                            onClick = onNewChatClick
                        )
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.Person,
                                        contentDescription = null,
                                        tint = primaryColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "新建群聊",
                                        style = AppTypography.BodyMedium,
                                        color = if (isDark) JuusPalette.ListPage.Dark.TextPrimary else JuusPalette.ListPage.TextPrimary
                                    )
                                }
                            },
                            onClick = onNewGroupClick
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
    isGroup: Boolean = false,
    groupMemberAvatars: List<String> = emptyList(),
    isEditMode: Boolean = false,
    gestureModifier: Modifier = Modifier,
    onDeleteClick: () -> Unit = {}
) {
    val nameColor = if (isDark) JuusPalette.ListPage.Dark.TextPrimary else JuusPalette.ListPage.TextPrimary
    val previewColor = if (isDark) JuusPalette.ListPage.Dark.TextSecondary else JuusPalette.ListPage.TextSecondary
    val primaryColor = if (isDark) JuusPalette.ListPage.Dark.Primary else JuusPalette.ListPage.Primary
    // 选中态颜色平滑过渡 — 统一 AppAnimation token，避免选中跳变
    val cardBg by animateColorAsState(
        targetValue = when {
            isSelected && isDark -> JuusPalette.ListPage.Dark.GlassCardSelected
            isSelected -> JuusPalette.ListPage.GlassCardSelected
            isDark -> JuusPalette.ListPage.Dark.GlassCard
            else -> JuusPalette.ListPage.GlassCard
        },
        animationSpec = AppAnimation.Specs.normal(),
        label = "cardBg"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isSelected && isDark -> JuusPalette.ListPage.Dark.GlassBorderSelected
            isSelected -> JuusPalette.ListPage.GlassBorderSelected
            isDark -> JuusPalette.ListPage.Dark.GlassBorder
            else -> JuusPalette.ListPage.GlassBorder
        },
        animationSpec = AppAnimation.Specs.normal(),
        label = "borderColor"
    )
    val borderWidth = if (isSelected) 1.dp else 0.5.dp
    val errorColor = if (isDark) JuusPalette.ListPage.Dark.ErrorRed else JuusPalette.ListPage.ErrorRed

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
                if (isGroup) {
                    // 群聊头像：2x2 宫格组合成员头像（最多 4 个），无成员时回退群图标
                    val avatarsToShow = groupMemberAvatars.take(4)
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(JuusPalette.ListPage.ChannelEmojiBg),
                        contentAlignment = Alignment.Center
                    ) {
                        if (avatarsToShow.isNotEmpty()) {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(3.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                val rows = if (avatarsToShow.size <= 2) 1 else 2
                                for (row in 0 until rows) {
                                    Row(
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        val itemsInRow = if (rows == 1) avatarsToShow.size
                                        else if (row == 0) minOf(2, avatarsToShow.size)
                                        else avatarsToShow.size - 2
                                        for (col in 0 until itemsInRow) {
                                            val idx = row * 2 + col
                                            if (idx < avatarsToShow.size) {
                                                RobustAvatar(
                                                    url = avatarsToShow[idx],
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .fillMaxHeight()
                                                        .clip(RoundedCornerShape(6.dp)),
                                                    fallbackContent = {
                                                        Icon(
                                                            Icons.Rounded.Person,
                                                            contentDescription = null,
                                                            tint = primaryColor.copy(alpha = 0.5f),
                                                            modifier = Modifier.size(12.dp)
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            Icon(
                                Icons.Rounded.Person,
                                contentDescription = null,
                                tint = primaryColor.copy(alpha = 0.5f),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(JuusPalette.ListPage.ChannelEmojiBg),
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
                    style = AppTypography.TitleMediumBold,
                    color = nameColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = preview,
                    style = AppTypography.BodyMedium,
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
                    style = AppTypography.CaptionMedium,
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
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
private fun JuusNewGroupSheet(
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
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
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )

            // ── 群聊名称输入 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(fieldBg)
                    .border(1.dp, fieldBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 4.dp)
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
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
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
