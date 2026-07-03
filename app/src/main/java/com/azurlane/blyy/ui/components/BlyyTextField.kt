package com.azurlane.blyy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.azurlane.blyy.ui.theme.AppColors
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.BlyyShapes
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.ui.theme.LocalUiStyle
import com.azurlane.blyy.ui.theme.isCommandCenter

/**
 * 统一输入框组件 — 支持指挥中心/经典双风格。
 *
 * 指挥中心风格：切角容器 + 渐变描边 + 聚焦高亮
 * 经典风格：圆角容器 + 简洁边框
 *
 * 设计规范：
 * - 触摸高度 ≥ [AppSpacing.Height.Input]（56dp）
 * - 使用 [AppTypography.BodyLarge] 文字样式
 * - 支持 leadingIcon / trailingIcon / error 状态
 */
@Composable
fun BlyyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    onTrailingIconClick: (() -> Unit)? = null,
    isError: Boolean = false,
    errorMessage: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val isDark = LocalIsDark.current
    var isFocused by remember { mutableStateOf(false) }

    val borderColor = when {
        isError -> MaterialTheme.colorScheme.error
        isFocused -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    }

    val containerColor = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.4f)
    }

    Column(modifier = modifier) {
        label?.let {
            Text(
                text = it,
                style = AppTypography.LabelLarge,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = AppSpacing.Xs)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(AppSpacing.Height.Input)
                .clip(if (isCommandCenter) BlyyShapes.PanelSmall else BlyyShapes.Button)
                .background(containerColor)
                .border(
                    width = AppSpacing.Border.Thin,
                    brush = if (isCommandCenter && (isFocused || isError)) {
                        Brush.linearGradient(
                            colors = listOf(
                                borderColor.copy(alpha = 0.8f),
                                borderColor.copy(alpha = 0.3f)
                            )
                        )
                    } else {
                        SolidColor(borderColor)
                    },
                    shape = if (isCommandCenter) BlyyShapes.PanelSmall else BlyyShapes.Button
                )
                .padding(horizontal = AppSpacing.Padding.InputHorizontal),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                leadingIcon?.let {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(AppSpacing.Icon.Md)
                    )
                    Spacer(Modifier.width(AppSpacing.Sm))
                }

                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(
                            text = placeholder,
                            style = AppTypography.BodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled,
                        singleLine = singleLine,
                        textStyle = AppTypography.BodyLarge.copy(
                            color = if (enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        ),
                        keyboardOptions = keyboardOptions,
                        visualTransformation = visualTransformation,
                        cursorBrush = SolidColor(
                            if (isError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        )
                    )
                }

                if (trailingIcon != null) {
                    Spacer(Modifier.width(AppSpacing.Sm))
                    if (onTrailingIconClick != null) {
                        IconButton(
                            onClick = onTrailingIconClick,
                            modifier = Modifier.size(AppSpacing.Icon.Xxl)
                        ) {
                            Icon(
                                imageVector = trailingIcon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(AppSpacing.Icon.Md)
                            )
                        }
                    } else {
                        Icon(
                            imageVector = trailingIcon,
                            contentDescription = null,
                            tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(AppSpacing.Icon.Md)
                        )
                    }
                } else if (isError) {
                    Spacer(Modifier.width(AppSpacing.Sm))
                    Icon(
                        imageVector = Icons.Rounded.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(AppSpacing.Icon.Md)
                    )
                }
            }
        }

        errorMessage?.takeIf { isError }?.let {
            Text(
                text = it,
                style = AppTypography.BodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = AppSpacing.Xs, start = AppSpacing.Sm)
            )
        }
    }
}

/**
 * 带清除按钮的输入框便利封装。
 */
@Composable
fun BlyySearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索...",
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true
) {
    BlyyTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = if (value.isNotEmpty()) Icons.Rounded.Clear else null,
        onTrailingIconClick = if (value.isNotEmpty()) ({ onValueChange("") }) else null,
        enabled = enabled
    )
}
