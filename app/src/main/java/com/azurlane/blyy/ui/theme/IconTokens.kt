package com.azurlane.blyy.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Material Symbols Rounded 统一尺寸规范。
 * 全应用 Icon 应使用 [AppSpacing.Icon] 或此对象的语义别名。
 */
object BlyyIcon {
    val Inline: Dp @Composable get() = AppSpacing.Icon.Sm      // 16dp — 行内
    val Standard: Dp @Composable get() = AppSpacing.Icon.Md    // 20dp — 按钮/列表
    val Emphasis: Dp @Composable get() = AppSpacing.Icon.Lg     // 24dp — TopBar
    val Hero: Dp @Composable get() = AppSpacing.Icon.Xxl        // 32dp — 空状态
    val Display: Dp @Composable get() = AppSpacing.Icon.Huge     // 48dp — 引导页

    /** 视觉描边权重 — 通过 tint alpha 区分，而非混用 Filled/Default */
    const val AlphaPrimary = 1f
    const val AlphaSecondary = 0.72f
    const val AlphaDisabled = 0.38f
    const val AlphaMuted = 0.55f
}
