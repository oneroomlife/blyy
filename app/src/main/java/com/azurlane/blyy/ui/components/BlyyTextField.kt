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
import androidx.compose.ui.text.input.TextFieldValue
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

/**
 * 优化光标体验的 OutlinedTextField 封装
 *
 * 核心原理：维护本地 [TextFieldValue] 状态，用户输入时立即更新本地状态（光标不等待 DataStore 往返），
 * 仅在外部值与本地值不一致时（如程序化清空、切换会话）才同步外部值。
 *
 * 解决问题：直接绑定 StateFlow String 值时，用户每次输入都要经 DataStore 异步往返，
 * 导致光标位置丢失/跳变。
 *
 * 光标跳末尾修复：原实现 `if (localValue.text != value)` 在用户输入后、StateFlow 异步回流前
 * 会为 true（localValue 是新值，value 是旧值），导致光标被重置到 TextRange(value.length) 末尾。
 * 现引入 [lastExternalValue] 记录上一次外部值，只有外部值真正变化时（说明是程序化修改/切换会话）
 * 才同步 localValue，避免用户输入过程中被 recompose 干扰。
 */
@Composable
fun StableOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    textStyle: androidx.compose.ui.text.TextStyle = androidx.compose.material3.LocalTextStyle.current,
    colors: androidx.compose.material3.TextFieldColors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(),
    shape: androidx.compose.ui.graphics.Shape = androidx.compose.material3.OutlinedTextFieldDefaults.shape
) {
    // 本地 TextFieldValue 状态：用户输入时立即更新，不等待外部 StateFlow 往返
    var localValue by remember { mutableStateOf(TextFieldValue(value)) }
    // 记录上一次的外部值：用于判断外部值是否真正变化（而非用户输入导致的 recompose）
    var lastExternalValue by remember { mutableStateOf(value) }

    // 仅在外部值真正变化时（如程序化清空、切换会话等场景）才同步本地状态
    // 关键：不使用 `if (localValue.text != value)`，因为用户输入后 StateFlow 异步回流前
    // value 还是旧值，localValue.text 是新值，会导致光标被重置到末尾
    if (value != lastExternalValue) {
        lastExternalValue = value
        // 外部值变化时，仅在本地文本与外部值不一致时才更新（避免覆盖用户正在输入的相同文本）
        if (localValue.text != value) {
            localValue = TextFieldValue(
                text = value,
                selection = androidx.compose.ui.text.TextRange(value.length)
            )
        }
    }

    androidx.compose.material3.OutlinedTextField(
        value = localValue,
        onValueChange = { tfv ->
            localValue = tfv
            lastExternalValue = tfv.text  // 同步标记，避免 StateFlow 回流时重复同步
            if (tfv.text != value) {
                onValueChange(tfv.text)
            }
        },
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        textStyle = textStyle,
        colors = colors,
        shape = shape
    )
}
