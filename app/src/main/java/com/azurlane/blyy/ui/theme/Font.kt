package com.azurlane.blyy.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * 字体族：若将来在 res/font/ 放入授权字体（如 blyy_display.ttf），
 * 可在此替换为 Font(R.font.blyy_display) 构建 FontFamily。
 * 当前使用系统等宽/无衬线组合模拟 HUD 科技感。
 */
object BlyyFontFamily {
    /** 标题/导航 — 略宽字距的无衬线 */
    val Display: FontFamily = FontFamily.SansSerif

    /** 数据/标签 — 等宽感 */
    val Mono: FontFamily = FontFamily.Monospace

    val DisplayWeight = FontWeight.SemiBold
    val BodyWeight = FontWeight.Normal
    val LabelWeight = FontWeight.Medium
}
