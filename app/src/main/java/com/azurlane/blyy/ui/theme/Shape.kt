package com.azurlane.blyy.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 碧蓝航线风格切角矩形 — 指挥中心 HUD 面板常用形态。
 *
 * 尺寸值统一来自 [AppSpacing.Corner]，保证全应用圆角语言一致。
 */
fun chamferedShape(size: androidx.compose.ui.unit.Dp = AppSpacing.Corner.Md): CornerBasedShape =
    CutCornerShape(size)

fun diagonalChamferedShape(size: androidx.compose.ui.unit.Dp = AppSpacing.Corner.Chamfer): CornerBasedShape =
    CutCornerShape(topStart = size, bottomEnd = size)

/**
 * 全应用 Shape 唯一来源。
 *
 * 尺寸 token 定义在 [AppSpacing.Corner]，此处仅负责将 token 转换为 [Shape] 对象。
 * 各组件应引用 [BlyyShapes] 的成员，而非自行 chamferedShape(xx.dp)。
 */
object BlyyShapes {
    /** 小型面板/按钮 — 8dp 切角 */
    val PanelSmall: Shape = chamferedShape(AppSpacing.Corner.Sm)
    /** 中型面板 — 12dp 切角 */
    val PanelMedium: Shape = chamferedShape(AppSpacing.Corner.Md)
    /** 大型面板/毛玻璃卡片 — 16dp 切角 */
    val PanelLarge: Shape = chamferedShape(AppSpacing.Corner.Lg)
    /** 底部导航栏 — 14dp 对角切角 */
    val NavBar: Shape = diagonalChamferedShape(14.dp)
    /** 舰船卡片 — 10dp 切角（与 AppSpacing.Corner.Chamfer 一致） */
    val Card: Shape = chamferedShape(AppSpacing.Corner.Chamfer)
    /** 按钮 — 8dp 切角（与 PanelSmall 一致，统一按钮语言） */
    val Button: Shape = chamferedShape(AppSpacing.Corner.Sm)
    /** 对话框 — 20dp 切角（比面板更大，突出层级） */
    val Dialog: Shape = chamferedShape(AppSpacing.Corner.Xl)
    /** 底部弹窗顶部圆角 — 20dp（仅顶部切角） */
    val BottomSheet: Shape = CutCornerShape(
        topStart = AppSpacing.Corner.Xl,
        topEnd = AppSpacing.Corner.Xl
    )
    /** 经典风格对话框 — 28dp 圆角 */
    val DialogClassic: Shape = androidx.compose.foundation.shape.RoundedCornerShape(AppSpacing.Corner.Dialog)
    /** 经典风格底部弹窗 — 24dp 顶部圆角 */
    val BottomSheetClassic: Shape = androidx.compose.foundation.shape.RoundedCornerShape(
        topStart = AppSpacing.Corner.Xxl,
        topEnd = AppSpacing.Corner.Xxl
    )
    /** 聊天气泡 — 接收方（左下角尖角） */
    val ChatBubbleReceived: Shape = androidx.compose.foundation.shape.RoundedCornerShape(
        topStart = AppSpacing.Corner.Md,
        topEnd = AppSpacing.Corner.Md,
        bottomStart = AppSpacing.Corner.Xs,
        bottomEnd = AppSpacing.Corner.Md
    )
    /** 聊天气泡 — 发送方（右下角尖角） */
    val ChatBubbleSent: Shape = androidx.compose.foundation.shape.RoundedCornerShape(
        topStart = AppSpacing.Corner.Md,
        topEnd = AppSpacing.Corner.Md,
        bottomStart = AppSpacing.Corner.Md,
        bottomEnd = AppSpacing.Corner.Xs
    )
}
