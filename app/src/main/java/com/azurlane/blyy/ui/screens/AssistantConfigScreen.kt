package com.azurlane.blyy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyySectionPanel
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.theme.AppColors
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.ui.theme.LocalUiStyle
import com.azurlane.blyy.ui.theme.isCommandCenter
import com.azurlane.blyy.ui.theme.isWatchScreen
import com.azurlane.blyy.viewmodel.SettingsViewModel

@Composable
fun AssistantConfigScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val defaultUid by viewModel.assistantDefaultUid.collectAsStateWithLifecycle()
    val defaultServer by viewModel.assistantDefaultServer.collectAsStateWithLifecycle()

    AdaptiveScreenBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            BlyyTopBar(
                title = "助手配置",
                subtitle = "碧蓝航线助手查询参数",
                onBackClick = onBack
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(AppSpacing.Screen.Horizontal)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Gap.Lg)
            ) {
                Spacer(modifier = Modifier.height(AppSpacing.Sm))

                BlyySectionPanel(
                    title = "查询参数",
                    icon = Icons.Rounded.PersonSearch,
                    accentColor = MaterialTheme.colorScheme.primary
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(if (isWatchScreen()) AppSpacing.Md else AppSpacing.Lg),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)
                    ) {
                        Column {
                            Text(
                                text = "UID",
                                style = AppTypography.TitleSmall,
                                fontWeight = FontWeight.Medium,
                                color = if (LocalUiStyle.current.isCommandCenter()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(AppSpacing.Sm))
                            EnhancedOutlinedTextField(
                                value = defaultUid,
                                onValueChange = viewModel::setAssistantDefaultUid,
                                placeholder = "输入游戏内 UID",
                                isCommandCenter = LocalUiStyle.current.isCommandCenter(),
                                isDark = LocalIsDark.current,
                                keyboardType = KeyboardType.Number
                            )
                        }

                        Column {
                            Text(
                                text = "服务器",
                                style = AppTypography.TitleSmall,
                                fontWeight = FontWeight.Medium,
                                color = if (LocalUiStyle.current.isCommandCenter()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(AppSpacing.Sm))
                            EnhancedOutlinedTextField(
                                value = defaultServer,
                                onValueChange = viewModel::setAssistantDefaultServer,
                                placeholder = "服务器名",
                                isCommandCenter = LocalUiStyle.current.isCommandCenter(),
                                isDark = LocalIsDark.current,
                                keyboardType = KeyboardType.Text
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.Xl))
            }
        }
    }
}

@Composable
private fun EnhancedOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isCommandCenter: Boolean,
    isDark: Boolean,
    keyboardType: KeyboardType
) {
    val accentColor = MaterialTheme.colorScheme.primary

    if (isCommandCenter) {
        val borderColorFocused = Brush.linearGradient(
            colors = listOf(
                accentColor.copy(alpha = 0.8f),
                AppColors.Accent.Gold.copy(alpha = 0.5f),
                accentColor.copy(alpha = 0.6f)
            )
        )
        val borderColorUnfocused = Brush.linearGradient(
            colors = listOf(
                accentColor.copy(alpha = 0.2f),
                accentColor.copy(alpha = 0.1f)
            )
        )
        val isFocused = value.isNotEmpty()
        val borderBrush = if (isFocused) borderColorFocused else borderColorUnfocused

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppSpacing.Corner.Sm))
                .background(if (isDark) AppColors.Panel.Dark else AppColors.Panel.Light.copy(alpha = 0.9f))
                .border(
                    width = if (isFocused) AppSpacing.Border.Normal else AppSpacing.Border.Thin,
                    brush = borderBrush,
                    shape = RoundedCornerShape(AppSpacing.Corner.Sm)
                )
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(placeholder, style = AppTypography.BodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                textStyle = AppTypography.BodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, cursorColor = accentColor),
                shape = RoundedCornerShape(AppSpacing.Corner.Sm)
            )
        }
    } else {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(placeholder, style = AppTypography.BodySmall) },
            textStyle = AppTypography.BodyMedium,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(AppSpacing.Corner.Sm)
        )
    }
}
