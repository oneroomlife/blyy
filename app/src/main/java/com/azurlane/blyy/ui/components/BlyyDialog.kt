package com.azurlane.blyy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.azurlane.blyy.ui.theme.AppColors
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.BlyyShapes
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.ui.theme.LocalUiStyle
import com.azurlane.blyy.ui.theme.isCommandCenter

/**
 * 统一确认对话框 — 替代各 Screen 直接使用 AlertDialog 时的样式不一致问题。
 *
 * 设计规范：
 * - 指挥中心风格：切角容器 + 渐变描边
 * - 经典风格：标准 Material3 AlertDialog
 * - 使用 [AppTypography] 统一文字层级
 * - 按钮使用 [BlyyPrimaryButton]/[BlyySecondaryButton] 风格
 *
 * @param title 标题文字
 * @param message 正文内容
 * @param confirmText 确认按钮文字（默认"确认"）
 * @param dismissText 取消按钮文字（默认"取消"）
 * @param onConfirm 确认回调
 * @param onDismiss 取消/外部点击回调
 * @param isDestructive 是否为危险操作（确认按钮显示为错误色）
 */
@Composable
fun BlyyConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "确认",
    dismissText: String = "取消",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isDestructive: Boolean = false
) {
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val confirmColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = if (isCommandCenter) BlyyShapes.Dialog else RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = title,
                style = AppTypography.TitleLargeBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Text(
                text = message,
                style = AppTypography.BodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                lineHeight = AppTypography.BodyMedium.lineHeight
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmText,
                    style = AppTypography.LabelLarge,
                    color = confirmColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = dismissText,
                    style = AppTypography.LabelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

/**
 * 自定义内容对话框 — 当需要比 [BlyyConfirmDialog] 更复杂的内容时使用。
 *
 * @param onDismiss 外部点击/返回键回调
 * @param content 自定义内容
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlyyDialog(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.clip(
            if (isCommandCenter) BlyyShapes.Dialog else RoundedCornerShape(28.dp)
        )
    ) {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = AppSpacing.Border.Thin,
                    brush = if (isCommandCenter) AppColors.Gradient.PanelBorder
                    else Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                        )
                    ),
                    shape = if (isCommandCenter) BlyyShapes.Dialog else RoundedCornerShape(28.dp)
                )
        ) {
            content()
        }
    }
}

/**
 * 统一底部弹窗 — 替代各 Screen 直接使用 ModalBottomSheet 时的样式不一致问题。
 *
 * 设计规范：
 * - 指挥中心风格：切角顶部 + 渐变描边
 * - 经典风格：标准圆角顶部
 * - 统一拖拽手柄样式
 *
 * @param onDismissRequest 关闭回调
 * @param content 弹窗内容
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlyyBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val isCommandCenter = LocalUiStyle.current.isCommandCenter()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = if (isCommandCenter) BlyyShapes.BottomSheet else RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            // 统一拖拽手柄
            Box(
                modifier = Modifier
                    .padding(vertical = AppSpacing.Sm)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
            )
        }
    ) {
        content()
    }
}
