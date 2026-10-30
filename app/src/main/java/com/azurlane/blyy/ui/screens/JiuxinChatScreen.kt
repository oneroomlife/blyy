package com.azurlane.blyy.ui.screens

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.azurlane.blyy.util.findActivityViewModelStoreOwner
import com.valentinilk.shimmer.shimmer
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.azurlane.blyy.R
import com.azurlane.blyy.data.model.ChatMessage
import com.azurlane.blyy.data.model.ChatMessageType
import com.azurlane.blyy.data.model.ChatSession
import com.azurlane.blyy.data.model.GroupPosition
import com.azurlane.blyy.data.model.PersonaConfig
import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.data.model.getGroupPosition
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyBottomSheet
import com.azurlane.blyy.ui.components.BlyyConfirmDialog
import com.azurlane.blyy.ui.components.BlyyFormDialog
import com.azurlane.blyy.ui.components.BlyyHaptic
import com.azurlane.blyy.ui.components.rememberBlyyHaptics
import com.azurlane.blyy.ui.components.BlyyDragHandle
import com.azurlane.blyy.ui.components.BlyyPanel
import com.azurlane.blyy.ui.components.BlyySectionPanel
import com.azurlane.blyy.ui.components.StableOutlinedTextField
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.JuusPalette
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.viewmodel.ConnectionTestState
import com.azurlane.blyy.viewmodel.JiuxinViewModel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.azurlane.blyy.ui.screens.chat.BackgroundPickerSheet
import com.azurlane.blyy.ui.screens.chat.ChatInputBar
import com.azurlane.blyy.ui.screens.chat.EmptyCallback
import com.azurlane.blyy.ui.screens.chat.EmptyChatState
import com.azurlane.blyy.ui.screens.chat.GroupMemberPanel
import com.azurlane.blyy.ui.screens.chat.HistoryPanel
import com.azurlane.blyy.ui.screens.chat.JuusChatTopBar
import com.azurlane.blyy.ui.screens.chat.JuusColors
import com.azurlane.blyy.ui.screens.chat.MessageActionSheet
import com.azurlane.blyy.ui.screens.chat.MessageBubble
import com.azurlane.blyy.ui.screens.chat.MessageEditSheet
import com.azurlane.blyy.ui.screens.chat.TypingIndicator
import com.azurlane.blyy.ui.screens.chat.UserConfigDialog

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun JiuxinChatScreen(
    onBack: () -> Unit,
    onNavigateToConfig: () -> Unit,
    onNavigateToShipConfig: () -> Unit,
    // 关键修复：绑定到 Activity 而非 NavBackStackEntry，确保跨页面共享同一 ViewModel 实例。
    // 修复"聊天记录退出后丢失"问题：避免每个屏幕持有独立 ViewModel 导致状态不同步。
    // NavBackStackEntry 作用域会导致：聊天页 ViewModel A 销毁保存 → 列表页 ViewModel B 用过期数据覆盖
    // 通过 findActivityViewModelStoreOwner() 解包 ContextWrapper，
    // 避免直接 `as ViewModelStoreOwner` 强转在部分设备/ROM 上抛出 ClassCastException 导致闪退。
    viewModel: JiuxinViewModel = hiltViewModel(
        viewModelStoreOwner = LocalContext.current.findActivityViewModelStoreOwner()
            ?: LocalViewModelStoreOwner.current
            ?: error("No ViewModelStoreOwner available in Context hierarchy")
    )
) {
    val chatState by viewModel.chatUiState.collectAsStateWithLifecycle()
    // 配置隔离：从当前会话快照读取舰娘配置，而非全局 StateFlow
    val currentSession by viewModel.currentSession.collectAsStateWithLifecycle()
    val jiuxinName = currentSession?.jiuxinName ?: ""
    val avatarUrl = currentSession?.avatarUrl ?: ""
    val voiceEnabled = currentSession?.voiceEnabled ?: true
    val voiceShipName = currentSession?.voiceShipName ?: ""
    val userName by viewModel.userName.collectAsStateWithLifecycle()
    val userAvatarUrl by viewModel.userAvatarUrl.collectAsStateWithLifecycle()
    val currentlyPlayingId by viewModel.currentlyPlayingId.collectAsStateWithLifecycle()
    // 当前舰娘的历史会话列表（已隔离）：HistoryPanel 中仅显示当前舰娘的对话
    // 注意：不再订阅 uniqueConversations — 该 StateFlow 仅用于会话列表界面（ConversationListScreen），
    // 在聊天界面订阅会造成不必要的重组且从未被读取
    val shipSessions by viewModel.currentShipSessions.collectAsStateWithLifecycle()
    val currentSessionId by viewModel.currentSessionId.collectAsStateWithLifecycle()
    // 群成员管理面板数据源：可用舰娘人格列表（仅群聊会话的面板使用）
    val personaConfigs by viewModel.personaConfigs.collectAsStateWithLifecycle()

    // 退出聊天界面时保存当前会话消息到 DataStore
    // 避免：用户在舰娘A聊天后返回 → 点击舰娘B → flatMapLatest collector 从 DataStore 读取舰娘A的旧消息
    // DisposableEffect 在 Composable 离开组合时触发，确保消息持久化
    DisposableEffect(currentSessionId) {
        onDispose {
            if (currentSessionId.isNotBlank()) {
                // updateTimestamp=false：退出聊天不应改变会话列表顺序
                viewModel.saveCurrentSessionMessages(
                    sessionId = currentSessionId,
                    updateTimestamp = false
                )
            }
        }
    }
    // API 配置状态：用于新建对话前的配置完整性验证（读取全局默认值）
    val apiUrl by viewModel.apiUrl.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    // 聊天背景图片 URL（空表示使用默认纯色背景）
    val chatBackgroundUrl by viewModel.chatBackgroundUrl.collectAsStateWithLifecycle()
    val isDark = LocalIsDark.current
    val haptic = rememberBlyyHaptics()

    var showHistoryPanel by remember { mutableStateOf(false) }
    var showMemberPanel by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }
    var showRenameDialog by remember { mutableStateOf<String?>(null) }
    var showUserConfigDialog by remember { mutableStateOf(false) }
    // 长按消息操作弹窗（messageId + isUserMessage）
    var messageActionTarget by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    // 编辑重发弹窗（messageId + 原始内容）
    var editResendTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    // API 配置缺失提示（新建对话时验证）
    var showConfigMissingDialog by remember { mutableStateOf(false) }
    // 聊天背景选择弹窗
    var showBackgroundPicker by remember { mutableStateOf(false) }

    // 纯色背景（取消角色立绘背景层，避免视觉干扰和性能开销）
    val chatBgFallback = if (isDark) JuusPalette.Dark.Bg else JuusPalette.Bg
    // 背景优先级：会话级 backgroundUrl > 全局 aiChatBackgroundUrl > 默认纯色
    val sessionBgUrl = currentSession?.backgroundUrl ?: ""
    val effectiveBackgroundUrl = sessionBgUrl.ifBlank { chatBackgroundUrl }

    Box(modifier = Modifier.fillMaxSize().background(chatBgFallback)) {
        // 自定义聊天背景图片层（半透明覆盖，保证消息气泡可读性）
        if (effectiveBackgroundUrl.isNotBlank()) {
            AsyncImage(
                model = effectiveBackgroundUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            // 半透明遮罩层：确保消息气泡在复杂背景下仍可读
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isDark) Color.Black.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.3f))
            )
        }
        // 聊天内容层
        Column(modifier = Modifier.fillMaxSize()) {
            val isGroupChat = currentSession?.isGroup == true
            val groupMemberCount = currentSession?.groupMembers?.size ?: 0
            JuusChatTopBar(
                title = if (isGroupChat) currentSession?.name?.ifBlank { "群聊" } ?: "群聊" else jiuxinName.ifBlank { "啾信" },
                subtitle = when {
                    isGroupChat -> "$groupMemberCount 位成员"
                    voiceEnabled && voiceShipName.isNotBlank() -> "语音: $voiceShipName"
                    else -> "啾信对话"
                },
                onBack = onBack,
                onHistoryClick = { showHistoryPanel = true },
                onBackgroundClick = { showBackgroundPicker = true },
                // 群聊暂不进入单舰娘配置页（群配置由会话列表管理）
                onSettingsClick = if (isGroupChat) ({ showMemberPanel = true }) else onNavigateToShipConfig,
                isDark = isDark
            )

            // 空状态：无会话时显示引导（全部删除后不自动创建新会话）
            if (currentSessionId.isBlank()) {
                EmptyChatState(
                    isDark = isDark,
                    onBack = onBack
                )
            } else {

            val listState = rememberLazyListState()
            val density = LocalDensity.current
            val coroutineScope = rememberCoroutineScope()

            // ── 智能自动滚动机制 ──
            // 仅当用户"在底部附近"时才自动滚动，避免用户查看历史消息时被强制拽回底部。
            // 判定"在底部附近"：最后可见 item 索引 >= 消息总数 - 2（容忍 2 条误差）
            // 额外引入 userScrolledUp 标记：用户主动向上滚动后，新消息到达不强制滚动，
            // 直到用户手动滚回底部（或发送消息）才恢复自动跟随。
            var shouldAutoScroll by remember { mutableStateOf(true) }

            // 进入界面/切换会话时强制跳转到底部（无动画，避免视觉跳动）
            // 解决从会话列表进入时（尤其是群聊）消息已加载但不会自动滚动到底部的问题。
            //
            // 直接从 ViewModel StateFlow 收集消息数量，而非用 snapshotFlow 读 Compose State：
            // 点击进入群聊时 switchToSession 已同步更新 _chatState，但 Compose State
            // (chatState) 可能尚未重组到最新值，snapshotFlow 会读到旧值（0 或上一会话的消息数），
            // 导致 filter { it > 0 } 不通过或滚动到错误位置。
            // 直接收集 viewModel.chatUiState 可立即获取最新值，不受重组时序影响。
            //
            // withFrameNanos 等待 LazyColumn 完成首次测量（两帧确保布局稳定）后再滚动。
            LaunchedEffect(currentSessionId) {
                val messagesCount = viewModel.chatUiState
                    .map { it.messages.size }
                    .filter { it > 0 }
                    .first()
                withFrameNanos { }
                withFrameNanos { }
                listState.scrollToItem(messagesCount - 1)
            }

            // 监听用户手动滚动：当用户向上滚动离开底部时，关闭自动滚动；
            // 当用户滚回底部附近时，重新开启自动滚动。
            LaunchedEffect(listState) {
                snapshotFlow {
                    val layoutInfo = listState.layoutInfo
                    val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                    val totalItems = layoutInfo.totalItemsCount
                    // 总项数包含头尾 Spacer 和 typing/error item，需要容忍更多误差
                    lastVisibleIndex >= 0 && totalItems > 0 && lastVisibleIndex < totalItems - 3
                }.collect { isScrolledAwayFromBottom ->
                    shouldAutoScroll = !isScrolledAwayFromBottom
                }
            }

            // 消息数量变化时自动滚动到底部（仅在 shouldAutoScroll 为 true 时）
            LaunchedEffect(chatState.messages.size) {
                if (chatState.messages.isNotEmpty() && shouldAutoScroll) {
                    listState.animateScrollToItem(chatState.messages.size - 1)
                }
            }

            // 加载状态变化时不触发滚动（避免 isLoading 翻转引起跳动）
            // 原实现将 isLoading 作为 key 会导致 API 开始/结束各滚动一次，产生跳动

            // 键盘弹出时自动滚动到底部（仅在 shouldAutoScroll 为 true 时）
            // 优化：使用 snapshotFlow + debounce 等待 IME 动画稳定后再滚动
            // 原实现用 LaunchedEffect(keyboardHeight > 0) 只在状态切换时触发一次，
            // 但此时 IME 动画刚开始，LazyColumn 的 weight(1f) 高度还在变化，
            // 滚动目标位置错误，导致最新消息仍被键盘遮挡。
            // debounce(280) 等待 IME 动画完成（约 250ms）+ 布局稳定后再滚动，
            // 使用 scrollToItem（无动画立即跳转）避免与 IME 动画叠加产生视觉跳动。
            val imeInsets = WindowInsets.ime
            LaunchedEffect(Unit) {
                snapshotFlow { imeInsets.getBottom(density) }
                    .filter { it > 0 }
                    .debounce(280)
                    .collect {
                        if (chatState.messages.isNotEmpty() && shouldAutoScroll) {
                            listState.scrollToItem(chatState.messages.size - 1)
                        }
                    }
            }

            // 提升到 LazyColumn 外层，避免每个 item 重复读取 LocalConfiguration
            val configuration = LocalConfiguration.current
            val messageMaxWidth = remember(configuration.screenWidthDp) {
                (configuration.screenWidthDp * 0.75f).dp
            }

            // 消息发送者标识函数（用于分组判断：USER=发送方，AI/VOICE/STICKER=接收方，SYSTEM=独立）
            // 群聊扩展：群聊会话中按 shipName 区分不同舰娘发送者，使不同舰娘的连续消息独立分组、各自显示头像
            val isGroupSession = currentSession?.isGroup == true
            val senderOf: (ChatMessage) -> String = remember(isGroupSession) {
                { msg ->
                    when (msg.type) {
                        ChatMessageType.USER.name -> "user"
                        ChatMessageType.SYSTEM.name -> "system_${msg.id}" // 每条系统消息独立成组
                        else -> if (isGroupSession) "ai_${msg.shipName}" else "ai" // 群聊按舰娘分组
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(0.dp) // 间距由 item 自身控制（组间12dp/组内4dp）
            ) {
                item(key = "top_spacer", contentType = "spacer") {
                    Spacer(modifier = Modifier.height(8.dp))
                }

                itemsIndexed(
                    items = chatState.messages,
                    key = { _, msg -> msg.id },
                    contentType = { _, msg -> msg.type }
                ) { index, message ->
                    val groupPos = getGroupPosition(chatState.messages, index, senderOf)
                    // 组间间距 12dp，组内间距 4dp
                    val topPadding = when (groupPos) {
                        GroupPosition.SINGLE, GroupPosition.FIRST -> 12.dp
                        else -> 4.dp
                    }
                    // animateItem：新消息淡入 + 历史消息增删/重排平滑过渡
                    Box(modifier = Modifier.animateItem()) {
                        MessageBubble(
                            message = message,
                            jiuxinName = jiuxinName,
                            jiuxinAvatarUrl = avatarUrl,
                            userName = userName,
                            userAvatarUrl = userAvatarUrl,
                            isDark = isDark,
                            isPlaying = currentlyPlayingId == message.id,
                            maxWidth = messageMaxWidth,
                            groupPosition = groupPos,
                            topPadding = topPadding,
                            isGroup = isGroupSession,
                            onVoiceClick = remember(message.id, message.voiceUrl) {
                                {
                                    if (message.voiceUrl.isNotBlank()) {
                                        viewModel.toggleVoicePlayback(message.id, message.voiceUrl)
                                    }
                                }
                            },
                            onStickerClick = EmptyCallback,
                            onMessageLongClick = remember(message.id, message.type) {
                                {
                                    haptic(BlyyHaptic.LongPress)
                                    val isUser = message.type == ChatMessageType.USER.name
                                    messageActionTarget = Pair(message.id, isUser)
                                }
                            },
                            onRetryClick = remember(message.id) {
                                { viewModel.retryMessage(message.id) }
                            }
                        )
                    }
                }

                // 打字动画指示器
                // 群聊并发模式：优先显示 typingMembers 列表中每位正在打字的成员
                // 私聊模式或 typingMembers 为空时：回退到基于 isLoading 的单一指示器
                if (chatState.typingMembers.isNotEmpty()) {
                    chatState.typingMembers.forEach { typingMember ->
                        item(key = "typing_${typingMember.name}_${typingMember.avatarUrl}", contentType = "typing") {
                            TypingIndicator(
                                jiuxinName = typingMember.name,
                                avatarUrl = typingMember.avatarUrl,
                                isDark = isDark
                            )
                        }
                    }
                } else if (chatState.isLoading) {
                    item(key = "typing_single", contentType = "typing") {
                        TypingIndicator(
                            jiuxinName = jiuxinName,
                            avatarUrl = avatarUrl,
                            isDark = isDark
                        )
                    }
                }

                if (chatState.error != null) {
                    item(key = "error_banner", contentType = "error") {
                        val errorBg = if (isDark) JuusColors.Dark.ErrorBg else JuusColors.ErrorBg
                        val errorText = if (isDark) JuusColors.Dark.ErrorText else JuusColors.ErrorText
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = AppSpacing.Md, vertical = 6.dp)
                                .clip(RoundedCornerShape(AppSpacing.Corner.Sm))
                                .background(errorBg)
                                .padding(horizontal = AppSpacing.Lg, vertical = 10.dp)
                        ) {
                            Text(
                                text = chatState.error!!,
                                style = AppTypography.BodySmall,
                                color = errorText,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                item(key = "bottom_spacer", contentType = "spacer") {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            ChatInputBar(
                inputText = chatState.inputText,
                onInputChange = viewModel::setInputText,
                onSend = {
                    // 用户主动发送消息：恢复自动滚动并立即滚到底部
                    shouldAutoScroll = true
                    viewModel.sendMessage(chatState.inputText)
                    if (chatState.messages.isNotEmpty()) {
                        coroutineScope.launch {
                            listState.animateScrollToItem(chatState.messages.size - 1)
                        }
                    }
                },
                enabled = !chatState.isLoading,
                isDark = isDark,
                onInputFocus = {
                    // 用户点击输入框：恢复自动滚动到底部
                    shouldAutoScroll = true
                    if (chatState.messages.isNotEmpty()) {
                        coroutineScope.launch {
                            listState.animateScrollToItem(chatState.messages.size - 1)
                        }
                    }
                },
                onPlusClick = { showUserConfigDialog = true }
            )
            } // end else (currentSessionId.isNotBlank())
        }
    }

    // ── 历史对话面板 ──
    // 仅显示当前舰娘的历史对话（已隔离），每个舰娘只能查看对应的历史对话
    if (showHistoryPanel) {
        HistoryPanel(
            sessions = shipSessions,
            currentSessionId = currentSessionId,
            onDismiss = { showHistoryPanel = false },
            onSwitchSession = { sessionId ->
                viewModel.switchToSession(sessionId)
                showHistoryPanel = false
            },
            onNewSession = {
                // 新建对话前验证 API 配置完整性
                // 注意：群聊会话使用会话快照中的 API 配置校验（会话级隔离），
                // 私聊会话使用全局 StateFlow 校验（作为新建会话默认值模板）
                // 这样即使全局 API 被清空，群聊仍可基于自身快照继续新建对话
                val isGroup = currentSession?.isGroup == true
                val hasValidApi = if (isGroup) {
                    currentSession?.apiUrl?.isNotBlank() == true &&
                        currentSession?.apiKey?.isNotBlank() == true
                } else {
                    apiUrl.isNotBlank() && apiKey.isNotBlank()
                }
                if (!hasValidApi) {
                    showHistoryPanel = false
                    showConfigMissingDialog = true
                } else {
                    viewModel.createNewSessionForCurrentShip()
                    showHistoryPanel = false
                }
            },
            onDeleteSession = { sessionId -> showDeleteConfirm = sessionId },
            onRenameSession = { sessionId -> showRenameDialog = sessionId }
        )
    }

    // ── 群成员管理面板 ──
    if (showMemberPanel) {
        val session = currentSession
        if (session != null && session.isGroup) {
            GroupMemberPanel(
                session = session,
                personaConfigs = personaConfigs,
                isDark = isDark,
                onDismiss = { showMemberPanel = false },
                onUpdateMembers = { memberIds ->
                    viewModel.updateGroupMembers(session.id, memberIds)
                },
                onRename = { newName ->
                    viewModel.renameGroupSession(session.id, newName)
                }
            )
        }
    }

    // ── 用户（指挥官）配置弹窗 ──
    if (showUserConfigDialog) {
        UserConfigDialog(
            currentName = userName,
            currentAvatarUrl = userAvatarUrl,
            onDismiss = { showUserConfigDialog = false },
            onSave = { name, avatar ->
                viewModel.saveUserName(name)
                viewModel.saveUserAvatarUrl(avatar)
                showUserConfigDialog = false
            },
            viewModel = viewModel
        )
    }

    // ── 删除确认弹窗 ──
    // 在当前舰娘的会话列表中查找
    showDeleteConfirm?.let { sessionId ->
        val session = shipSessions.find { it.id == sessionId }
        // 显示对话名称（name），回退到啾信名称（jiuxinName）
        val sessionName = session?.name
            ?.takeIf { it.isNotBlank() && !it.startsWith("对话-") }
            ?: session?.jiuxinName ?: "此对话"
        BlyyConfirmDialog(
            title = "删除对话",
            message = "确定要删除「$sessionName」吗？此操作不可撤销。",
            confirmText = "删除",
            dismissText = "取消",
            onConfirm = {
                viewModel.deleteSession(sessionId)
                showDeleteConfirm = null
            },
            onDismiss = { showDeleteConfirm = null },
            isDestructive = true
        )
    }

    // ── 重命名弹窗 ──
    // 在当前舰娘的会话列表中查找
    // 初始值使用对话名称（name），而非啾信名称（jiuxinName），确保名称职责隔离
    showRenameDialog?.let { sessionId ->
        val targetSession = shipSessions.find { it.id == sessionId }
        var renameText by remember(sessionId) { mutableStateOf(targetSession?.name?.takeIf { it.isNotBlank() && !it.startsWith("对话-") } ?: targetSession?.jiuxinName ?: "") }
        BlyyFormDialog(
            onDismiss = { showRenameDialog = null },
            title = "重命名对话",
            text = {
                StableOutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    textStyle = AppTypography.BodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = if (isDark) JuusColors.Dark.AiName else JuusColors.Primary),
                    shape = RoundedCornerShape(AppSpacing.Corner.Sm)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) {
                        viewModel.renameSession(sessionId, renameText)
                    }
                    showRenameDialog = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) { Text("取消") }
            }
        )
    }

    // ── 长按消息操作弹窗（编辑重发 / 删除） ──
    messageActionTarget?.let { (messageId, isUser) ->
        val targetMessage = chatState.messages.find { it.id == messageId }
        MessageActionSheet(
            isUser = isUser,
            isDark = isDark,
            onDismiss = { messageActionTarget = null },
            onEdit = {
                editResendTarget = Pair(messageId, targetMessage?.content ?: "")
                messageActionTarget = null
            },
            onDelete = {
                viewModel.deleteMessage(messageId)
                messageActionTarget = null
            }
        )
    }

    // ── 编辑重发弹窗 ──
    editResendTarget?.let { (messageId, originalContent) ->
        MessageEditSheet(
            originalContent = originalContent,
            isDark = isDark,
            onDismiss = { editResendTarget = null },
            onSend = { newContent ->
                if (newContent.isNotBlank()) {
                    viewModel.editAndResendMessage(messageId, newContent)
                }
                editResendTarget = null
            }
        )
    }

    // ── API 配置缺失提示弹窗（新建对话时验证） ──
    if (showConfigMissingDialog) {
        BlyyConfirmDialog(
            title = "需要先配置 API",
            message = "当前配置缺少 API URL 或 API 密钥，无法发送消息。\n是否前往配置页面完成设置？",
            confirmText = "去配置",
            onConfirm = {
                showConfigMissingDialog = false
                onNavigateToConfig()
            },
            onDismiss = { showConfigMissingDialog = false }
        )
    }

    // ── 聊天背景选择弹窗 ──
    if (showBackgroundPicker) {
        BackgroundPickerSheet(
            viewModel = viewModel,
            sessionId = currentSessionId,
            sessionBackgroundUrl = sessionBgUrl,
            globalBackgroundUrl = chatBackgroundUrl,
            isDark = isDark,
            onDismiss = { showBackgroundPicker = false }
        )
    }
}
