package com.azurlane.blyy.ui.screens.config

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import androidx.compose.material3.AlertDialog
import com.azurlane.blyy.data.model.ApiConfig
import com.azurlane.blyy.data.model.JiuxinPreset
import com.azurlane.blyy.data.model.PersonaConfig
import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyBottomSheet
import com.azurlane.blyy.ui.components.BlyyPanel
import com.azurlane.blyy.ui.components.BlyyPrimaryButton
import com.azurlane.blyy.ui.components.BlyyEntrance
import com.azurlane.blyy.ui.components.RobustAvatar
import com.azurlane.blyy.ui.screens.chat.AvatarPickerSheet
import com.azurlane.blyy.ui.components.BlyySectionPanel
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.components.StableOutlinedTextField
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.ChatColors
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.util.findActivityViewModelStoreOwner
import com.azurlane.blyy.viewmodel.ConnectionTestState
import com.azurlane.blyy.viewmodel.JiuxinViewModel
import com.azurlane.blyy.viewmodel.ModelListState
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlinx.coroutines.launch

/**
 * 预设卡片：显示预设名称、模型、舰娘信息，提供应用/编辑/删除操作
 */
@Composable
internal fun PresetCard(
    preset: JiuxinPreset,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.Corner.Md))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(AppSpacing.Corner.Md))
            .padding(AppSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
    ) {
        // 头像
        RobustAvatar(
            url = preset.avatarUrl,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
            fallbackContent = {
                Icon(
                    Icons.Rounded.Bookmark,
                    null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.tertiary
                )
            }
        )
        // 信息
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = preset.name.ifBlank { "未命名预设" },
                style = AppTypography.TitleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    if (preset.jiuxinName.isNotBlank()) append(preset.jiuxinName)
                    if (preset.model.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(preset.model)
                    }
                    if (preset.voiceShipName.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append("语音: ${preset.voiceShipName}")
                    }
                    if (isEmpty()) append("点击应用以加载配置")
                },
                style = AppTypography.BodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 操作按钮
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onApply, modifier = Modifier.minimumInteractiveComponentSize().size(32.dp)) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = "应用",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.minimumInteractiveComponentSize().size(32.dp)) {
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = "编辑",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.minimumInteractiveComponentSize().size(32.dp)) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * API 配置卡片：显示配置名、URL、Model，提供应用/编辑/删除操作
 * 与 [PresetCard] 区别：仅包含 API 连接信息，不绑定舰娘人格
 */
@Composable
internal fun ApiConfigCard(
    config: ApiConfig,
    isCurrentActive: Boolean,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.Corner.Md))
            .background(
                if (isCurrentActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
            )
            .border(
                if (isCurrentActive) 1.5.dp else 1.dp,
                if (isCurrentActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                RoundedCornerShape(AppSpacing.Corner.Md)
            )
            .padding(AppSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
    ) {
        // 配置图标
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Key,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        // 信息
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = config.name.ifBlank { "未命名 API 配置" },
                    style = AppTypography.TitleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isCurrentActive) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(AppSpacing.Corner.Xs))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = AppSpacing.Xxs)
                    ) {
                        Text(
                            "当前",
                            style = AppTypography.CardLabel,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Text(
                text = buildString {
                    if (config.model.isNotBlank()) append(config.model)
                    if (config.apiUrl.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        // 截断长 URL 提升可读性
                        val url = config.apiUrl
                        append(if (url.length > 40) url.take(40) + "…" else url)
                    }
                    if (isEmpty()) append("点击应用以加载配置")
                },
                style = AppTypography.BodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 操作按钮
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onApply, modifier = Modifier.minimumInteractiveComponentSize().size(32.dp)) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = "应用",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.minimumInteractiveComponentSize().size(32.dp)) {
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = "编辑",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.minimumInteractiveComponentSize().size(32.dp)) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * 舰娘人格配置卡片：显示头像、名称、摘要，提供应用/编辑/删除操作
 * 与 [PresetCard] 区别：仅包含舰娘人格信息，不绑定 API 配置
 */
@Composable
internal fun PersonaConfigCard(
    config: PersonaConfig,
    isCurrentActive: Boolean,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.Corner.Md))
            .background(
                if (isCurrentActive) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
            )
            .border(
                if (isCurrentActive) 1.5.dp else 1.dp,
                if (isCurrentActive) MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                RoundedCornerShape(AppSpacing.Corner.Md)
            )
            .padding(AppSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
    ) {
        // 头像
        RobustAvatar(
            url = config.avatarUrl,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)),
            fallbackContent = {
                Icon(
                    Icons.Rounded.Psychology,
                    null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
        )
        // 信息
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = config.name.ifBlank { "未命名舰娘" },
                    style = AppTypography.TitleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isCurrentActive) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(AppSpacing.Corner.Xs))
                            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = AppSpacing.Xxs)
                    ) {
                        Text(
                            "当前",
                            style = AppTypography.CardLabel,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
            Text(
                text = buildString {
                    if (config.jiuxinName.isNotBlank()) append(config.jiuxinName)
                    if (config.voiceShipName.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append("语音: ${config.voiceShipName}")
                    }
                    if (config.systemPrompt.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        // 仅显示提示词前 30 字作为摘要
                        val promptPreview = config.systemPrompt.take(30)
                        append(if (config.systemPrompt.length > 30) "$promptPreview…" else promptPreview)
                    }
                    if (isEmpty()) append("点击应用以加载配置")
                },
                style = AppTypography.BodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 操作按钮
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onApply, modifier = Modifier.minimumInteractiveComponentSize().size(32.dp)) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = "应用",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.minimumInteractiveComponentSize().size(32.dp)) {
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = "编辑",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.minimumInteractiveComponentSize().size(32.dp)) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
