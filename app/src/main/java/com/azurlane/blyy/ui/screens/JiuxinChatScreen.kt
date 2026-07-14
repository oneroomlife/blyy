package com.azurlane.blyy.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valentinilk.shimmer.shimmer
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.azurlane.blyy.R
import com.azurlane.blyy.data.model.ChatMessage
import com.azurlane.blyy.data.model.ChatMessageType
import com.azurlane.blyy.data.model.ChatSession
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyBottomSheet
import com.azurlane.blyy.ui.components.BlyyConfirmDialog
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.util.LocalAvatarResolver
import com.azurlane.blyy.viewmodel.JiuxinViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── JuusTalk 设计规范色彩系统 ──
private object JuusColors {
    val Primary = Color(0xFF20A0FF)        // JuusTalk 主色蓝
    val PrimaryLight = Color(0xFF85D0FF)   // 浅蓝强调
    val PrimaryBg = Color(0xFFEBF4FB)      // 聊天背景浅蓝
    val UserBubble = Color(0xFF1565C0)     // 用户气泡蓝（WCAG AA 5.7:1 on white text）
    val AiBubble = Color.White             // AI气泡白
    val AiBubbleBorder = Color(0xFFE5E9F2) // AI气泡边框
    val AiName = Color(0xFF20A0FF)         // AI名称蓝
    val VoiceBubble = Color(0xFFFFF5F8)    // 语音气泡粉底
    val VoiceBorder = Color(0xFFF9D5E5)    // 语音边框粉
    val VoiceAccent = Color(0xFFFF69B4)    // 语音强调粉
    val FooterBg = Color.White             // 底栏白
    val FooterBorder = Color(0xFFE5E9F2)   // 底栏边框
    val InputBg = Color(0xFFF5F7FA)        // 输入框背景
    val InputBorder = Color(0xFFD1D9E6)    // 输入框边框
    val InputFocusBorder = Color(0xFF20A0FF) // 输入框聚焦边框
    val SendActive = Color(0xFF20A0FF)     // 发送按钮激活
    val SendInactive = Color(0xFFE5E9F2)   // 发送按钮未激活
    val SendIconInactive = Color(0xFF7F8C9B) // 发送按钮未激活图标（深色，浅灰底上可读）
    val TextPrimary = Color(0xFF2C3E50)    // 主文字
    val TextSecondary = Color(0xFF5A6B7B)  // 辅助文字（WCAG AA 4.6:1 on 0xFFEBF4FB）
    val TextOnPrimary = Color.White        // 主色上文字
    val TextTime = Color(0xFF5A6B7B)       // 时间文字（WCAG AA 4.6:1 on 0xFFEBF4FB）
    val SystemText = Color(0xFFC8C7C8)     // 系统消息文字
    val AvatarBorder = Color(0xFFE5E9F2)   // 头像边框
    val TypingDot = Color(0xFF85D0FF)      // 打字动画点色
    val ErrorBg = Color(0xFFFFF0F0)        // 错误背景
    val ErrorText = Color(0xFFFF4949)      // 错误文字

    // 深色模式
    object Dark {
        val PrimaryBg = Color(0xFF0D1B2A)
        val UserBubble = Color(0xFF1565C0)
        val AiBubble = Color(0xFF243559)
        val AiBubbleBorder = Color(0xFF2A3F5F)
        val AiName = Color(0xFF85D0FF)
        val VoiceBubble = Color(0xFF2A1A28)
        val VoiceBorder = Color(0xFF4A2A44)
        val VoiceAccent = Color(0xFFFF69B4)
        val FooterBg = Color(0xFF0F1D32)
        val FooterBorder = Color(0xFF2A3F5F)
        val InputBg = Color(0xFF152238)
        val InputBorder = Color(0xFF2A3F5F)
        val InputFocusBorder = Color(0xFF85D0FF)
        val SendActive = Color(0xFF85D0FF)
        val SendInactive = Color(0xFF2A3F5F)
        val SendIconActive = Color(0xFF0D1B2A) // 发送按钮激活图标（深色，浅蓝底上可读）
        val TextPrimary = Color(0xFFE2EAF4)
        val TextSecondary = Color(0xFFA8BCD0)
        val TextOnPrimary = Color.White
        val TextTime = Color(0xFF8FA8BE)
        val SystemText = Color(0xFFA0B8CE)
        val AvatarBorder = Color(0xFF2A3F5F)
        val TypingDot = Color(0xFF85D0FF)
        val ErrorBg = Color(0xFF2A1A1A)
        val ErrorText = Color(0xFFFF6B6B)
    }
}

// ── 头像 URL 复合格式编解码 ──
// 格式: primary||fallback  例如: file:///data/.../ship_xxx.jpg||https://webstatic.cn/xxx.png
// 无 fallback 时为普通 URL，兼容旧数据
private const val AVATAR_DELIMITER = "||"

private fun encodeAvatarUrl(primary: String, fallback: String?): String {
    if (fallback.isNullOrBlank() || fallback == primary) return primary
    return "$primary$AVATAR_DELIMITER$fallback"
}

private fun decodeAvatarUrl(url: String): Pair<String, String?> {
    if (url.isBlank()) return "" to null
    val idx = url.indexOf(AVATAR_DELIMITER)
    return if (idx >= 0) {
        url.substring(0, idx) to url.substring(idx + AVATAR_DELIMITER.length).takeIf { it.isNotBlank() }
    } else {
        url to null
    }
}

/**
 * 健壮头像加载组件：优先加载本地文件头像，加载失败时自动回退到网络 URL。
 *
 * - 支持 file://（本地文件）、file:/（asset）、content://（相册）、https://（网络）等所有 URI
 * - 支持 [encodeAvatarUrl] 编码的复合 URL，自动解析 primary/fallback
 * - 两个 URL 均失败时显示 [fallbackContent]
 *
 * @param url 头像 URL（可为复合格式 primary||fallback）
 * @param modifier 尺寸与裁剪修饰符
 * @param fallbackContent 两个 URL 均失败时显示的内容（通常为 Icon）
 */
@Composable
fun RobustAvatar(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallbackContent: @Composable () -> Unit
) {
    val (primary, fallback) = remember(url) { decodeAvatarUrl(url) }
    // 当前正在尝试加载的 URL，先尝试 primary，失败后切换到 fallback
    var currentTarget by remember(url) { mutableStateOf(primary) }
    // 两个 URL 均失败时为 true
    var allFailed by remember(url) { mutableStateOf(false) }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (currentTarget.isNotBlank() && !allFailed) {
            AsyncImage(
                model = currentTarget,
                contentDescription = null,
                modifier = modifier,
                contentScale = contentScale,
                onState = { state ->
                    if (state is AsyncImagePainter.State.Error) {
                        if (fallback != null && currentTarget != fallback) {
                            // primary 失败，切换到 fallback（网络 URL）
                            currentTarget = fallback
                        } else {
                            // 无 fallback 或 fallback 也失败
                            allFailed = true
                        }
                    }
                }
            )
        }
        if (currentTarget.isBlank() || allFailed) {
            fallbackContent()
        }
    }
}

@Composable
fun JiuxinChatScreen(
    onBack: () -> Unit,
    onNavigateToConfig: () -> Unit,
    viewModel: JiuxinViewModel = hiltViewModel()
) {
    val chatState by viewModel.chatUiState.collectAsStateWithLifecycle()
    val jiuxinName by viewModel.jiuxinName.collectAsStateWithLifecycle()
    val avatarUrl by viewModel.avatarUrl.collectAsStateWithLifecycle()
    val userName by viewModel.userName.collectAsStateWithLifecycle()
    val userAvatarUrl by viewModel.userAvatarUrl.collectAsStateWithLifecycle()
    val voiceEnabled by viewModel.voiceEnabled.collectAsStateWithLifecycle()
    val voiceShipName by viewModel.voiceShipName.collectAsStateWithLifecycle()
    val currentlyPlayingId by viewModel.currentlyPlayingId.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val currentSessionId by viewModel.currentSessionId.collectAsStateWithLifecycle()
    val isDark = LocalIsDark.current

    var showHistoryPanel by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }
    var showRenameDialog by remember { mutableStateOf<String?>(null) }
    var showUserConfigDialog by remember { mutableStateOf(false) }

    AdaptiveScreenBackground {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            JuusChatTopBar(
                title = jiuxinName.ifBlank { "啾信" },
                subtitle = if (voiceEnabled && voiceShipName.isNotBlank()) "语音: $voiceShipName" else "啾信对话",
                onBack = onBack,
                onHistoryClick = { showHistoryPanel = true },
                onSettingsClick = onNavigateToConfig,
                isDark = isDark
            )

            val listState = rememberLazyListState()
            val density = LocalDensity.current
            val coroutineScope = rememberCoroutineScope()

            // 消息数量变化时自动滚动到底部
            LaunchedEffect(chatState.messages.size, chatState.isLoading) {
                if (chatState.messages.isNotEmpty()) {
                    listState.animateScrollToItem(chatState.messages.size - 1)
                }
            }

            // 键盘弹出时自动滚动到底部
            val imeInsets = WindowInsets.ime
            LaunchedEffect(imeInsets.getBottom(density)) {
                val keyboardHeight = imeInsets.getBottom(density)
                if (keyboardHeight > 0 && chatState.messages.isNotEmpty()) {
                    listState.animateScrollToItem(chatState.messages.size - 1)
                }
            }

            // 聊天消息区域 — JuusTalk 风格浅蓝背景
            val chatBg = if (isDark) JuusColors.Dark.PrimaryBg else JuusColors.PrimaryBg
            // 提升到 LazyColumn 外层，避免每个 item 重复读取 LocalConfiguration
            val messageMaxWidth = (LocalConfiguration.current.screenWidthDp * 0.75f).dp
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(chatBg)
                    .padding(horizontal = AppSpacing.Md),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
            ) {
                item { Spacer(modifier = Modifier.height(8.dp)) }

                items(chatState.messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        jiuxinName = jiuxinName,
                        jiuxinAvatarUrl = avatarUrl,
                        userName = userName,
                        userAvatarUrl = userAvatarUrl,
                        isDark = isDark,
                        isPlaying = currentlyPlayingId == message.id,
                        maxWidth = messageMaxWidth,
                        onVoiceClick = remember(message.id, message.voiceUrl) {
                            {
                                if (message.voiceUrl.isNotBlank()) {
                                    viewModel.toggleVoicePlayback(message.id, message.voiceUrl)
                                }
                            }
                        },
                        onStickerClick = { /* 以后可以做点击查看大图或保存表情包 */ },
                        onUserAvatarLongClick = { showUserConfigDialog = true }
                    )
                }

                // JuusTalk 风格打字动画指示器
                if (chatState.isLoading) {
                    item {
                        TypingIndicator(
                            jiuxinName = jiuxinName,
                            avatarUrl = avatarUrl,
                            isDark = isDark
                        )
                    }
                }

                if (chatState.error != null) {
                    item {
                        val errorBg = if (isDark) JuusColors.Dark.ErrorBg else JuusColors.ErrorBg
                        val errorText = if (isDark) JuusColors.Dark.ErrorText else JuusColors.ErrorText
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(AppSpacing.Corner.Sm))
                                .background(errorBg)
                                .padding(horizontal = 16.dp, vertical = 10.dp)
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

                item { Spacer(modifier = Modifier.height(8.dp)) }
            }

            ChatInputBar(
                inputText = chatState.inputText,
                onInputChange = viewModel::setInputText,
                onSend = { viewModel.sendMessage(chatState.inputText) },
                enabled = !chatState.isLoading,
                isDark = isDark,
                onInputFocus = {
                    if (chatState.messages.isNotEmpty()) {
                        coroutineScope.launch {
                            listState.animateScrollToItem(chatState.messages.size - 1)
                        }
                    }
                }
            )
        }
    }

    // ── 历史对话面板 ──
    if (showHistoryPanel) {
        HistoryPanel(
            sessions = sessions,
            currentSessionId = currentSessionId,
            onDismiss = { showHistoryPanel = false },
            onSwitchSession = { sessionId ->
                viewModel.switchToSession(sessionId)
                showHistoryPanel = false
            },
            onNewSession = {
                viewModel.createNewSession()
                showHistoryPanel = false
            },
            onDeleteSession = { sessionId -> showDeleteConfirm = sessionId },
            onRenameSession = { sessionId -> showRenameDialog = sessionId }
        )
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
    showDeleteConfirm?.let { sessionId ->
        val sessionName = sessions.find { it.id == sessionId }?.name ?: "此对话"
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
    showRenameDialog?.let { sessionId ->
        var renameText by remember { mutableStateOf(sessions.find { it.id == sessionId }?.name ?: "") }
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("重命名对话") },
            text = {
                OutlinedTextField(
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
}

@Composable
private fun UserConfigDialog(
    currentName: String,
    currentAvatarUrl: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    viewModel: JiuxinViewModel
) {
    val isDark = LocalIsDark.current
    var name by remember { mutableStateOf(currentName) }
    var avatarUrl by remember { mutableStateOf(currentAvatarUrl) }
    var showAvatarPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("指挥官信息设置", style = AppTypography.TitleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // 头像选择
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RobustAvatar(
                        url = avatarUrl,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background((if (isDark) JuusColors.Dark.AiName else JuusColors.Primary).copy(alpha = 0.1f))
                            .border(1.dp, (if (isDark) JuusColors.Dark.AiName else JuusColors.Primary).copy(alpha = 0.3f), CircleShape)
                            .clickable { showAvatarPicker = true },
                        fallbackContent = {
                            Icon(Icons.Rounded.Person, null, modifier = Modifier.size(32.dp), tint = (if (isDark) JuusColors.Dark.AiName else JuusColors.Primary).copy(alpha = 0.5f))
                        }
                    )
                    Text("点击更换头像", style = AppTypography.BodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("显示名称") },
                    singleLine = true,
                    textStyle = AppTypography.BodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = if (isDark) JuusColors.Dark.AiName else JuusColors.Primary),
                    shape = RoundedCornerShape(AppSpacing.Corner.Sm)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, avatarUrl) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )

    if (showAvatarPicker) {
        AvatarPickerSheet(
            viewModel = viewModel,
            currentAvatarUrl = avatarUrl,
            onDismiss = { showAvatarPicker = false },
            onAvatarSelected = {
                avatarUrl = it
                showAvatarPicker = false
            }
        )
    }
}

@Composable
private fun JuusChatTopBar(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    onHistoryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    isDark: Boolean
) {
    val bgColor = if (isDark) JuusColors.Dark.FooterBg else JuusColors.FooterBg
    val contentColor = if (isDark) JuusColors.Dark.TextPrimary else JuusColors.TextPrimary
    val subColor = if (isDark) JuusColors.Dark.TextSecondary else JuusColors.TextSecondary
    val borderColor = if (isDark) JuusColors.Dark.FooterBorder else JuusColors.FooterBorder
    val accentColor = if (isDark) JuusColors.Dark.AiName else JuusColors.Primary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .drawBehind {
                val strokeWidth = 1.dp.toPx()
                drawLine(
                    color = borderColor,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = strokeWidth
                )
            }
            .padding(horizontal = AppSpacing.Xs, vertical = AppSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回",
                tint = accentColor
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp)
        ) {
            Text(
                text = title,
                style = AppTypography.TitleMediumBold.copy(fontSize = 18.sp),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = AppTypography.LabelSmall.copy(fontSize = 11.sp),
                    color = subColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onHistoryClick) {
                Icon(
                    Icons.Rounded.ChatBubbleOutline,
                    contentDescription = "历史对话",
                    tint = accentColor,
                    modifier = Modifier.size(22.dp)
                )
            }
            IconButton(onClick = onSettingsClick) {
                Icon(
                    Icons.Rounded.Settings,
                    contentDescription = "设置",
                    tint = subColor.copy(alpha = 0.7f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

// ── JuusTalk 风格打字动画指示器 ──
@Composable
private fun TypingIndicator(
    jiuxinName: String,
    avatarUrl: String,
    isDark: Boolean
) {
    val dotColor = if (isDark) JuusColors.Dark.TypingDot else JuusColors.TypingDot
    val bubbleBg = if (isDark) JuusColors.Dark.AiBubble else JuusColors.AiBubble
    val bubbleBorder = if (isDark) JuusColors.Dark.AiBubbleBorder else JuusColors.AiBubbleBorder
    val nameColor = if (isDark) JuusColors.Dark.AiName else JuusColors.AiName
    val avatarBorder = if (isDark) JuusColors.Dark.AvatarBorder else JuusColors.AvatarBorder

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        // AI头像
        RobustAvatar(
            url = avatarUrl,
            modifier = Modifier.size(36.dp).clip(CircleShape)
                .background(bubbleBg)
                .border(1.dp, avatarBorder, CircleShape),
            fallbackContent = {
                Icon(Icons.Rounded.SmartToy, contentDescription = null, modifier = Modifier.size(18.dp), tint = nameColor)
            }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = jiuxinName,
                style = AppTypography.LabelSmallMedium.copy(color = nameColor),
                modifier = Modifier.padding(start = 2.dp, bottom = 3.dp)
            )
            // 打字气泡
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(topStart = AppSpacing.Corner.Md, topEnd = AppSpacing.Corner.Md, bottomStart = AppSpacing.Corner.Xs, bottomEnd = AppSpacing.Corner.Md))
                    .background(bubbleBg)
                    .border(1.dp, bubbleBorder, RoundedCornerShape(topStart = AppSpacing.Corner.Md, topEnd = AppSpacing.Corner.Md, bottomStart = AppSpacing.Corner.Xs, bottomEnd = AppSpacing.Corner.Md))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                TypingDots(dotColor = dotColor)
            }
        }
    }
}

// ── 三个跳动圆点动画 ──
@Composable
private fun TypingDots(dotColor: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val offsetY by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -6f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 400, delayMillis = index * 150, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot_$index"
            )
            Box(
                modifier = Modifier
                    .offset(y = offsetY.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryPanel(
    sessions: List<ChatSession>,
    currentSessionId: String,
    onDismiss: () -> Unit,
    onSwitchSession: (String) -> Unit,
    onNewSession: () -> Unit,
    onDeleteSession: (String) -> Unit,
    onRenameSession: (String) -> Unit
) {
    val isDark = LocalIsDark.current
    BlyyBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().height(520.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "历史对话", style = AppTypography.TitleMediumBold)
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                    IconButton(onClick = onNewSession) {
                        Icon(Icons.Rounded.Add, contentDescription = "新建对话", tint = if (isDark) JuusColors.Dark.AiName else JuusColors.Primary)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Edit, contentDescription = "关闭")
                    }
                }
            }

            if (sessions.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // 暗色模式下提高图标可见度：alpha 0.3 → 0.5（暗色）/ 0.35（浅色）
                        Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDark) 0.5f else 0.35f))
                        Text("暂无对话记录", style = AppTypography.BodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDark) 0.7f else 0.6f))
                        Text("点击 + 开始新对话", style = AppTypography.BodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDark) 0.6f else 0.45f))
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(sessions, key = { it.id }) { session ->
                        val isCurrent = session.id == currentSessionId
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(AppSpacing.Corner.Sm))
                                .background(if (isCurrent) (if (isDark) JuusColors.Dark.AiName else JuusColors.Primary).copy(alpha = 0.08f) else Color.Transparent)
                                .clickable { onSwitchSession(session.id) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = session.name,
                                    style = AppTypography.TitleSmall,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isCurrent) (if (isDark) JuusColors.Dark.AiName else JuusColors.Primary) else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = formatSessionTime(session.updatedAt),
                                    style = AppTypography.LabelSmall,
                                    // 暗色模式下提高时间戳可读性：0.5 → 0.7
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDark) 0.7f else 0.55f)
                                )
                            }
                            IconButton(onClick = { onRenameSession(session.id) }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Rounded.Edit, contentDescription = "重命名", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDark) 0.7f else 0.55f))
                            }
                            IconButton(onClick = { onDeleteSession(session.id) }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Rounded.Delete, contentDescription = "删除", modifier = Modifier.size(20.dp), tint = (if (isDark) JuusColors.Dark.ErrorText else JuusColors.ErrorText).copy(alpha = 0.6f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    jiuxinName: String,
    jiuxinAvatarUrl: String,
    userName: String,
    userAvatarUrl: String,
    isDark: Boolean,
    isPlaying: Boolean,
    maxWidth: Dp,
    onVoiceClick: () -> Unit,
    onStickerClick: () -> Unit,
    onUserAvatarLongClick: () -> Unit
) {
    val context = LocalContext.current

    when (message.type) {
        ChatMessageType.USER.name -> {
            // ── 用户消息：JuusTalk 风格蓝色气泡 + 右侧三角 ──
            val bubbleColor = if (isDark) JuusColors.Dark.UserBubble else JuusColors.UserBubble
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.widthIn(max = maxWidth), horizontalAlignment = Alignment.End) {
                    Text(
                        text = userName,
                        style = AppTypography.LabelSmallMedium.copy(color = bubbleColor),
                        modifier = Modifier.padding(end = 2.dp, bottom = 3.dp)
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(topStart = AppSpacing.Corner.Md, topEnd = AppSpacing.Corner.Md, bottomStart = AppSpacing.Corner.Md, bottomEnd = AppSpacing.Corner.Xs))
                            .background(bubbleColor)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = message.content,
                            style = AppTypography.BodyMedium.copy(
                                color = JuusColors.TextOnPrimary,
                                lineHeight = 20.sp
                            )
                        )
                    }
                    // 时间戳
                    Text(
                        text = formatTime(message.timestamp),
                        style = AppTypography.LabelSmall.copy(fontSize = 11.sp),
                        color = if (isDark) JuusColors.Dark.TextTime else JuusColors.TextTime,
                        modifier = Modifier.padding(end = 4.dp, top = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                // 用户头像
                RobustAvatar(
                    url = userAvatarUrl,
                    modifier = Modifier.size(36.dp).clip(CircleShape)
                        .background(bubbleColor.copy(alpha = 0.15f))
                        .combinedClickable(
                            onClick = {},
                            onLongClick = onUserAvatarLongClick
                        ),
                    fallbackContent = {
                        Icon(Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(20.dp), tint = bubbleColor)
                    }
                )
            }
        }

        ChatMessageType.AI.name -> {
            // ── AI消息：JuusTalk 风格白色气泡 + 左侧三角 ──
            val bubbleBg = if (isDark) JuusColors.Dark.AiBubble else JuusColors.AiBubble
            val bubbleBorder = if (isDark) JuusColors.Dark.AiBubbleBorder else JuusColors.AiBubbleBorder
            val nameColor = if (isDark) JuusColors.Dark.AiName else JuusColors.AiName
            val textColor = if (isDark) JuusColors.Dark.TextPrimary else JuusColors.TextPrimary
            val avatarBorder = if (isDark) JuusColors.Dark.AvatarBorder else JuusColors.AvatarBorder

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.Top
            ) {
                RobustAvatar(
                    url = jiuxinAvatarUrl,
                    modifier = Modifier.size(36.dp).clip(CircleShape)
                        .background(bubbleBg)
                        .border(1.dp, avatarBorder, CircleShape),
                    fallbackContent = {
                        Icon(Icons.Rounded.SmartToy, contentDescription = null, modifier = Modifier.size(18.dp), tint = nameColor)
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.widthIn(max = maxWidth)) {
                    Text(
                        text = jiuxinName,
                        style = AppTypography.LabelSmallMedium.copy(color = nameColor),
                        modifier = Modifier.padding(start = 2.dp, bottom = 3.dp)
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(topStart = AppSpacing.Corner.Md, topEnd = AppSpacing.Corner.Md, bottomStart = AppSpacing.Corner.Xs, bottomEnd = AppSpacing.Corner.Md))
                            .background(bubbleBg)
                            .border(1.dp, bubbleBorder, RoundedCornerShape(topStart = AppSpacing.Corner.Md, topEnd = AppSpacing.Corner.Md, bottomStart = AppSpacing.Corner.Xs, bottomEnd = AppSpacing.Corner.Md))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = message.content,
                            style = AppTypography.BodyMedium.copy(color = textColor, lineHeight = 20.sp)
                        )
                    }
                    Text(
                        text = formatTime(message.timestamp),
                        style = AppTypography.LabelSmall.copy(fontSize = 11.sp),
                        color = if (isDark) JuusColors.Dark.TextTime else JuusColors.TextTime,
                        modifier = Modifier.padding(start = 2.dp, top = 2.dp)
                    )
                }
            }
        }

        ChatMessageType.VOICE.name -> {
            // ── 语音消息：JuusTalk 风格粉色气泡 ──
            val bubbleBg = if (isDark) JuusColors.Dark.VoiceBubble else JuusColors.VoiceBubble
            val bubbleBorder = if (isDark) JuusColors.Dark.VoiceBorder else JuusColors.VoiceBorder
            val voiceAccent = if (isDark) JuusColors.Dark.VoiceAccent else JuusColors.VoiceAccent
            val textColor = if (isDark) JuusColors.Dark.TextPrimary else JuusColors.TextPrimary
            val avatarBorder = if (isDark) JuusColors.Dark.AvatarBorder else JuusColors.AvatarBorder

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.Top
            ) {
                RobustAvatar(
                    url = message.avatarUrl,
                    modifier = Modifier.size(36.dp).clip(CircleShape)
                        .background(bubbleBg)
                        .border(1.dp, bubbleBorder, CircleShape),
                    fallbackContent = {
                        Icon(Icons.Rounded.SmartToy, contentDescription = null, modifier = Modifier.size(18.dp), tint = voiceAccent)
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.widthIn(max = maxWidth)) {
                    Text(
                        text = message.shipName,
                        style = AppTypography.LabelSmallMedium.copy(color = voiceAccent),
                        modifier = Modifier.padding(start = 2.dp, bottom = 3.dp)
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(topStart = AppSpacing.Corner.Md, topEnd = AppSpacing.Corner.Md, bottomStart = AppSpacing.Corner.Xs, bottomEnd = AppSpacing.Corner.Md))
                            .background(bubbleBg)
                            .border(1.dp, bubbleBorder, RoundedCornerShape(topStart = AppSpacing.Corner.Md, topEnd = AppSpacing.Corner.Md, bottomStart = AppSpacing.Corner.Xs, bottomEnd = AppSpacing.Corner.Md))
                            .clickable(onClick = onVoiceClick)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                if (isPlaying) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = voiceAccent
                            )
                            Text(
                                text = message.dialogue.ifBlank { "语音消息" },
                                style = AppTypography.BodyMedium.copy(color = textColor, lineHeight = 20.sp)
                            )
                        }
                    }
                    Text(
                        text = formatTime(message.timestamp),
                        style = AppTypography.LabelSmall.copy(fontSize = 11.sp),
                        color = if (isDark) JuusColors.Dark.TextTime else JuusColors.TextTime,
                        modifier = Modifier.padding(start = 2.dp, top = 2.dp)
                    )
                }
            }
        }

        ChatMessageType.STICKER.name -> {
            // ── 表情包消息 ──
            val bubbleBg = if (isDark) JuusColors.Dark.AiBubble else JuusColors.AiBubble
            val nameColor = if (isDark) JuusColors.Dark.AiName else JuusColors.AiName
            val avatarBorder = if (isDark) JuusColors.Dark.AvatarBorder else JuusColors.AvatarBorder

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.Top
            ) {
                RobustAvatar(
                    url = message.avatarUrl,
                    modifier = Modifier.size(36.dp).clip(CircleShape)
                        .background(bubbleBg)
                        .border(1.dp, avatarBorder, CircleShape),
                    fallbackContent = {
                        Icon(Icons.Rounded.SmartToy, contentDescription = null, modifier = Modifier.size(18.dp), tint = nameColor)
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = message.shipName,
                        style = AppTypography.LabelSmallMedium.copy(color = nameColor),
                        modifier = Modifier.padding(start = 2.dp, bottom = 3.dp)
                    )
                    Box(
                        modifier = Modifier
                            .widthIn(max = 160.dp)
                            .heightIn(min = 60.dp, max = 180.dp)
                            .clip(RoundedCornerShape(AppSpacing.Corner.Sm))
                            .background(if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.03f))
                            .clickable(onClick = onStickerClick)
                    ) {
                        var isLoading by remember { mutableStateOf(true) }
                        var isError by remember { mutableStateOf(false) }

                        AsyncImage(
                            model = remember(message.stickerUrl) {
                                ImageRequest.Builder(context)
                                    .data(message.stickerUrl)
                                    .crossfade(true)
                                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                                    .addHeader("Referer", "https://wiki.biligame.com/")
                                    .memoryCachePolicy(CachePolicy.ENABLED)
                                    .diskCachePolicy(CachePolicy.ENABLED)
                                    .build()
                            },
                            contentDescription = "表情包",
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (isLoading) Modifier.shimmer() else Modifier),
                            contentScale = ContentScale.FillWidth,
                            onState = { state ->
                                isLoading = state is AsyncImagePainter.State.Loading
                                isError = state is AsyncImagePainter.State.Error
                            }
                        )

                        if (isError) {
                            // 加载失败时显示表情包名称文字，而非空白或错误图标
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = message.content.ifBlank { "[表情包]" },
                                    style = AppTypography.BodySmall.copy(
                                        color = nameColor.copy(alpha = 0.7f),
                                        textAlign = TextAlign.Center
                                    )
                                )
                            }
                        }
                    }
                    Text(
                        text = formatTime(message.timestamp),
                        style = AppTypography.LabelSmall.copy(fontSize = 11.sp),
                        color = if (isDark) JuusColors.Dark.TextTime else JuusColors.TextTime,
                        modifier = Modifier.padding(start = 2.dp, top = 2.dp)
                    )
                }
            }
        }

        ChatMessageType.SYSTEM.name -> {
            // ── 系统消息：居中灰色文字 ──
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = message.content,
                    style = AppTypography.LabelSmall.copy(
                        color = if (isDark) JuusColors.Dark.SystemText else JuusColors.SystemText,
                        fontSize = 12.sp
                    ),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    isDark: Boolean,
    onInputFocus: () -> Unit = {}
) {
    val focusRequester = remember { FocusRequester() }
    val footerBg = if (isDark) JuusColors.Dark.FooterBg else JuusColors.FooterBg
    val footerBorder = if (isDark) JuusColors.Dark.FooterBorder else JuusColors.FooterBorder
    val inputBg = if (isDark) JuusColors.Dark.InputBg else JuusColors.InputBg
    val inputBorder = if (isDark) JuusColors.Dark.InputBorder else JuusColors.InputBorder
    val inputFocusBorder = if (isDark) JuusColors.Dark.InputFocusBorder else JuusColors.InputFocusBorder
    val sendActive = if (isDark) JuusColors.Dark.SendActive else JuusColors.SendActive
    val sendInactive = if (isDark) JuusColors.Dark.SendInactive else JuusColors.SendInactive
    val textColor = if (isDark) JuusColors.Dark.TextPrimary else JuusColors.TextPrimary
    val hintColor = if (isDark) JuusColors.Dark.TextSecondary else JuusColors.TextSecondary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(footerBg)
            .border(width = 1.dp, color = footerBorder, shape = RoundedCornerShape(AppSpacing.Corner.None))
            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f)
                .focusRequester(focusRequester)
                .onFocusEvent { state ->
                    if (state.isFocused) onInputFocus()
                },
            placeholder = {
                Text("输入消息...", style = AppTypography.BodyMedium.copy(color = hintColor))
            },
            textStyle = AppTypography.BodyMedium.copy(color = textColor),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = inputFocusBorder,
                unfocusedBorderColor = inputBorder,
                focusedContainerColor = inputBg,
                unfocusedContainerColor = inputBg
            ),
            shape = RoundedCornerShape(AppSpacing.Corner.Xl)
        )
        IconButton(
            onClick = onSend,
            enabled = enabled && inputText.isNotBlank(),
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (inputText.isNotBlank() && enabled) sendActive else sendInactive
                )
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.Send,
                contentDescription = "发送",
                // 暗色模式：激活态背景为浅蓝(0xFF85D0FF)，需用深色图标；未激活态背景为深蓝，用浅色图标
                // 浅色模式：激活态背景为蓝(0xFF20A0FF)，用白色图标；未激活态背景为浅灰，用深色图标
                tint = if (inputText.isNotBlank() && enabled) {
                    if (isDark) JuusColors.Dark.SendIconActive else Color.White
                } else {
                    if (isDark) Color.White else JuusColors.SendIconInactive
                },
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AvatarPickerSheet(viewModel: JiuxinViewModel, currentAvatarUrl: String, onDismiss: () -> Unit, onAvatarSelected: (String) -> Unit) {
    val filteredShips by viewModel.filteredShipList.collectAsStateWithLifecycle()
    val searchQuery by viewModel.shipSearchQuery.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isDark = LocalIsDark.current
    val scope = rememberCoroutineScope()
    val imagePickerLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            scope.launch {
                // 复制到内部存储，避免 content:// URI 重启后丢失权限
                val filePath = viewModel.copyAvatarToInternalStorage(it)
                if (filePath != null) {
                    onAvatarSelected(filePath)
                }
            }
        }
    }
    var avatarTab by remember { mutableStateOf(0) }

    BlyyBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().height(520.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text = "选择头像", style = AppTypography.TitleMediumBold)
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "关闭") }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Lg), horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                com.azurlane.blyy.ui.components.BlyyChip(label = "舰娘头像", selected = avatarTab == 0, onClick = { avatarTab = 0 })
                com.azurlane.blyy.ui.components.BlyyChip(label = "本地上传", selected = avatarTab == 1, onClick = { avatarTab = 1 })
            }
            Spacer(modifier = Modifier.height(AppSpacing.Sm))
            if (avatarTab == 0) {
                OutlinedTextField(value = searchQuery, onValueChange = viewModel::setShipSearchQuery, modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Lg), placeholder = { Text("搜索舰娘...") }, singleLine = true, textStyle = AppTypography.BodyMedium, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = if (isDark) JuusColors.Dark.AiName else JuusColors.Primary), shape = RoundedCornerShape(AppSpacing.Corner.Sm))
                Spacer(modifier = Modifier.height(AppSpacing.Sm))
                LazyVerticalGrid(columns = GridCells.Fixed(5), modifier = Modifier.fillMaxSize().padding(horizontal = AppSpacing.Lg), horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm), verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                    items(filteredShips, key = { it.name }) { ship ->
                        // 优先使用本地高清头像，匹配不到回退网络 URL
                        val effectiveAvatar = remember(ship.name, ship.avatarUrl) {
                            LocalAvatarResolver.resolveOrDefault(context, ship.name, ship.avatarUrl)
                        }
                        val isSelected = effectiveAvatar == currentAvatarUrl
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(modifier = Modifier.size(52.dp).clip(CircleShape).border(if (isSelected) 2.dp else 1.dp, if (isSelected) (if (isDark) JuusColors.Dark.AiName else JuusColors.Primary) else Color.LightGray.copy(alpha = 0.3f), CircleShape).clickable {
                                // 选中舰娘头像时，复制 asset 到内部存储，确保 file:// 路径可靠加载
                                scope.launch {
                                    val reliablePath = viewModel.resolveAndCopyShipAvatar(ship.name, ship.avatarUrl)
                                    onAvatarSelected(reliablePath)
                                }
                            }, contentAlignment = Alignment.Center) { RobustAvatar(url = effectiveAvatar, modifier = Modifier.size(52.dp).clip(CircleShape), fallbackContent = { Icon(Icons.Rounded.Person, null, modifier = Modifier.size(20.dp), tint = (if (isDark) JuusColors.Dark.AiName else JuusColors.Primary).copy(alpha = 0.5f)) }) }
                            Text(text = ship.name, style = AppTypography.LabelSmall.copy(fontSize = 10.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (isSelected) (if (isDark) JuusColors.Dark.AiName else JuusColors.Primary) else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp))
                        }
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = AppSpacing.Lg), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    if (currentAvatarUrl.isNotBlank()) {
                        RobustAvatar(url = currentAvatarUrl, modifier = Modifier.size(120.dp).clip(CircleShape).border(2.dp, (if (isDark) JuusColors.Dark.AiName else JuusColors.Primary).copy(alpha = 0.3f), CircleShape), fallbackContent = { Icon(Icons.Rounded.Person, null, modifier = Modifier.size(48.dp), tint = (if (isDark) JuusColors.Dark.AiName else JuusColors.Primary).copy(alpha = 0.5f)) })
                        Spacer(modifier = Modifier.height(AppSpacing.Md)); Text("当前头像", style = AppTypography.BodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(modifier = Modifier.height(AppSpacing.Lg))
                    }
                    TextButton(onClick = { imagePickerLauncher.launch(arrayOf("image/*")) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                            Icon(Icons.Rounded.AddPhotoAlternate, null)
                            Text("从相册选择图片")
                        }
                    }
                    Spacer(modifier = Modifier.height(AppSpacing.Sm))
                    Text("支持 JPG、PNG 格式，建议使用正方形图片", style = AppTypography.BodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    if (currentAvatarUrl.isNotBlank()) { Spacer(modifier = Modifier.height(AppSpacing.Lg)); Text("清除头像", style = AppTypography.BodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.clickable { onAvatarSelected("") }) }
                }
            }
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatSessionTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000}分钟前"
        diff < 86_400_000 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        diff < 604_800_000 -> "${diff / 86_400_000}天前"
        else -> SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(timestamp))
    }
}
