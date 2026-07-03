package com.azurlane.blyy.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 统一光影 / 阴影层级 — Neo-Minimalism 三档 + 扩展档。
 * 优先使用 [tonalElevation]；仅在需要浮起感的组件上使用 [shadowElevation]。
 */
object AppElevation {
    /** 贴地 — 背景、分隔 */
    val Level0: Dp = 0.dp
    /** 微浮 — 列表项、Chip */
    val Level1: Dp = 1.dp
    /** 标准卡片 */
    val Level2: Dp = 3.dp
    /** 强调卡片 / FAB */
    val Level3: Dp = 6.dp
    /** 对话框 / BottomSheet */
    val Level4: Dp = 12.dp
    /** 全屏 Modal */
    val Level5: Dp = 16.dp

    /** 按压态阴影回落 */
    val PressedDelta: Dp = 2.dp

    /** 毛玻璃面板推荐 tonal */
    val GlassTonal: Dp = 2.dp
}
