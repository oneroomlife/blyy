package com.azurlane.blyy.ui.screens.chat

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
import com.azurlane.blyy.ui.components.BlyyFormDialog
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
import com.azurlane.blyy.ui.components.RobustAvatar

@Composable
internal fun UserConfigDialog(
    currentName: String,
    currentAvatarUrl: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    viewModel: JiuxinViewModel
) {
    val isDark = LocalIsDark.current
    var name by remember(currentName) { mutableStateOf(currentName) }
    var avatarUrl by remember(currentAvatarUrl) { mutableStateOf(currentAvatarUrl) }
    var showAvatarPicker by remember { mutableStateOf(false) }

    BlyyFormDialog(
        onDismiss = onDismiss,
        title = "指挥官信息设置",
        titleStyle = AppTypography.TitleMedium,
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

                StableOutlinedTextField(
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

/**
 * 空状态：无会话时显示引导
 *
 * 全部会话删除后不再自动创建新会话，显示空状态引导用户返回列表新建对话
 */
@Composable
internal fun EmptyChatState(
    isDark: Boolean,
    onBack: () -> Unit
) {
    val primaryColor = if (isDark) JuusPalette.Dark.Primary else JuusPalette.Primary
    val hintColor = if (isDark) JuusPalette.Dark.TextTertiary else JuusPalette.TextTertiary
    val titleColor = if (isDark) JuusPalette.Dark.TextPrimary else JuusPalette.TextPrimary

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.ChatBubbleOutline,
                contentDescription = null,
                tint = primaryColor.copy(alpha = 0.4f),
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = "暂无对话",
                style = AppTypography.TitleMedium,
                color = titleColor
            )
            Text(
                text = "返回列表新建对话",
                style = AppTypography.BodyMedium,
                color = hintColor
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(AppSpacing.Corner.Xl))
                    .background(primaryColor)
                    .clickable { onBack() }
                    .padding(horizontal = AppSpacing.Xxl, vertical = AppSpacing.Sm),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "返回列表",
                    style = AppTypography.BodyMediumMedium,
                    color = Color.White
                )
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryPanel(
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
                        Icon(Icons.Rounded.Close, contentDescription = "关闭")
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
                    items(sessions, key = { it.id }, contentType = { "session_item" }) { session ->
                        val isCurrent = session.id == currentSessionId
                        // 显示对话名称（name），为空或为默认格式时回退到啾信名称（jiuxinName）
                        // 名称职责隔离：对话名称可被用户重命名修改，啾信名称来自配置不可被重命名修改
                        val displayName = session.name
                            .takeIf { it.isNotBlank() && !it.startsWith("对话-") }
                            ?: session.jiuxinName.ifBlank { "未命名对话" }
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(AppSpacing.Corner.Sm))
                                .background(if (isCurrent) (if (isDark) JuusColors.Dark.AiName else JuusColors.Primary).copy(alpha = 0.08f) else Color.Transparent)
                                .clickable { onSwitchSession(session.id) }
                                .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = displayName,
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

/**
 * 群成员管理面板
 *
 * 展示当前群聊成员列表，支持：
 * - 查看群成员头像/名称
 * - 添加/移除成员（从可用舰娘人格中选择，至少保留 2 位）
 * - 重命名群聊
 */
@Composable
internal fun GroupMemberPanel(
    session: ChatSession,
    personaConfigs: List<PersonaConfig>,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onUpdateMembers: (List<String>) -> Unit,
    onRename: (String) -> Unit
) {
    val primaryColor = if (isDark) JuusColors.Dark.AiName else JuusColors.Primary
    // 当前群成员的 personaId 集合（用于标记已加入状态）
    var selectedPersonaIds by remember(session.groupMembers) {
        mutableStateOf(session.groupMembers.mapNotNull { it.personaId.ifBlank { null } }.toSet())
    }
    var editingName by remember(session.name) { mutableStateOf(session.name) }

    BlyyBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().height(560.dp)) {
            // ── 标题栏 ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "群聊信息", style = AppTypography.TitleMediumBold)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭")
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // ── 群名称编辑 ──
                item {
                    Column(modifier = Modifier.padding(horizontal = AppSpacing.Lg)) {
                        Text(
                            text = "群聊名称",
                            style = AppTypography.LabelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = AppSpacing.Xs)
                                .clip(RoundedCornerShape(AppSpacing.Corner.Sm))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isDark) 0.4f else 0.5f))
                                .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.foundation.text.BasicTextField(
                                value = editingName,
                                onValueChange = { if (it.length <= 24) editingName = it },
                                singleLine = true,
                                textStyle = AppTypography.BodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(primaryColor),
                                modifier = Modifier.weight(1f).padding(vertical = 10.dp)
                            )
                            if (editingName.trim() != session.name && editingName.trim().isNotBlank()) {
                                TextButton(onClick = { onRename(editingName.trim()) }) {
                                    Text("保存", style = AppTypography.LabelLarge, color = primaryColor)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // ── 成员选择标题 ──
                item {
                    Text(
                        text = "群成员（${selectedPersonaIds.size} 位，至少 2 位）",
                        style = AppTypography.LabelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Xs)
                    )
                }

                // ── 成员选择列表 ──
                items(personaConfigs, key = { it.id }, contentType = { "persona_item" }) { persona ->
                    val isMember = persona.id in selectedPersonaIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AppSpacing.Corner.Sm))
                            .background(if (isMember) primaryColor.copy(alpha = 0.08f) else Color.Transparent)
                            .clickable {
                                val newSet = if (isMember) selectedPersonaIds - persona.id
                                else selectedPersonaIds + persona.id
                                // 至少保留 2 位成员
                                if (newSet.size >= 2 || !isMember) {
                                    selectedPersonaIds = newSet
                                    onUpdateMembers(newSet.toList())
                                }
                            }
                            .padding(horizontal = AppSpacing.Lg, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        RobustAvatar(
                            url = persona.avatarUrl,
                            modifier = Modifier.size(40.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            fallbackContent = {
                                Icon(
                                    Icons.Rounded.Person,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = primaryColor.copy(alpha = 0.5f)
                                )
                            }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = persona.name.ifBlank { "未命名舰娘" },
                                style = AppTypography.TitleSmall,
                                fontWeight = if (isMember) FontWeight.Bold else FontWeight.Medium,
                                color = if (isMember) primaryColor else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (persona.jiuxinName.isNotBlank()) {
                                Text(
                                    text = persona.jiuxinName,
                                    style = AppTypography.LabelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                        if (isMember) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = "已加入",
                                tint = primaryColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── JUUSTAGRAM 消息分组圆角计算 ──
