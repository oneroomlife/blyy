package com.azurlane.blyy.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun baseStyle(
    size: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: androidx.compose.ui.unit.TextUnit = 0.sp,
    family: FontFamily = FontFamily.Default
) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size,
    lineHeight = lineHeight,
    letterSpacing = letterSpacing
)

object AppTypography {
    
    // ==================== 显示字体 ====================
    val DisplayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
    )
    
    val DisplayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    )
    
    val DisplaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    )
    
    // ==================== 标题字体 ====================
    val HeadlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    )
    
    val HeadlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    )
    
    val HeadlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    )
    
    // ==================== 标题字体（导航栏等） ====================
    val TitleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    )
    
    val TitleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    )
    
    val TitleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    )
    
    // ==================== 正文主体字体 ====================
    val BodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    )
    
    val BodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    )
    
    val BodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    )
    
    // ==================== 标签字体 ====================
    val LabelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    )
    
    val LabelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
    
    val LabelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )

    // ==================== 说明文字 Caption ====================
    val CaptionLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    )

    val CaptionMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp
    )

    val CaptionSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.5.sp
    )
    
    // ==================== 自定义样式 ====================
    // 卡片名称样式
    val CardTitle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    )

    // 卡片标签样式
    val CardLabel = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.3.sp
    )

    // 空状态标题
    val EmptyTitle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.5.sp
    )

    // 空状态描述
    val EmptyDescription = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Light,
        fontSize = 14.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.25.sp
    )

    // 按钮文字
    val ButtonText = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    )

    // 导航栏标签
    val NavigationLabel = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )

    // ==================== 字重变体（避免调用方 .copy(fontWeight = ...)） ====================
    /** TitleMedium 的 Bold 变体 — 用于强调标题 */
    val TitleMediumBold = TitleMedium.copy(fontWeight = FontWeight.Bold)
    /** TitleLarge 的 Bold 变体 — 用于大标题强调（如加载失败提示） */
    val TitleLargeBold = TitleLarge.copy(fontWeight = FontWeight.Bold)
    /** TitleSmall 的 Medium 变体 — 用于设置项/卡片副标题 */
    val TitleSmallMedium = TitleSmall.copy(fontWeight = FontWeight.Medium)
    /** TitleSmall 的 Bold 变体 — 用于小标题强调（如答题分数/倒计时） */
    val TitleSmallBold = TitleSmall.copy(fontWeight = FontWeight.Bold)
    /** HeadlineSmall 的 Bold 变体 — 用于答题结果/大字强调 */
    val HeadlineSmallBold = HeadlineSmall.copy(fontWeight = FontWeight.Bold)
    /** DisplayMedium 的 Bold 变体 — 用于全屏大字强调（如翻牌动画） */
    val DisplayMediumBold = DisplayMedium.copy(fontWeight = FontWeight.Bold)
    /** LabelLarge 的 Bold 变体 — 用于计数徽章/强调标签 */
    val LabelLargeBold = LabelLarge.copy(fontWeight = FontWeight.Bold)
    /** LabelMedium 的 Bold 变体 — 用于强调小标签 */
    val LabelMediumBold = LabelMedium.copy(fontWeight = FontWeight.Bold)
    /** LabelSmall 的 Bold 变体 — 用于徽章/状态标签 */
    val LabelSmallBold = LabelSmall.copy(fontWeight = FontWeight.Bold)
    /** LabelSmall 的 Medium 变体 — 用于聊天气泡名称/时间戳 */
    val LabelSmallMedium = LabelSmall.copy(fontWeight = FontWeight.Medium)
    /** BodyMedium 的 Medium 变体 — 用于输入框/中等强调正文 */
    val BodyMediumMedium = BodyMedium.copy(fontWeight = FontWeight.Medium)
    /** BodySmall 的 Medium 变体 — 用于气泡文字/辅助说明 */
    val BodySmallMedium = BodySmall.copy(fontWeight = FontWeight.Medium)
}

// Material3 Typography — 经典风格
val ClassicTypography = Typography(
    displayLarge = AppTypography.DisplayLarge,
    displayMedium = AppTypography.DisplayMedium,
    displaySmall = AppTypography.DisplaySmall,
    headlineLarge = AppTypography.HeadlineLarge,
    headlineMedium = AppTypography.HeadlineMedium,
    headlineSmall = AppTypography.HeadlineSmall,
    titleLarge = AppTypography.TitleLarge,
    titleMedium = AppTypography.TitleMedium,
    titleSmall = AppTypography.TitleSmall,
    bodyLarge = AppTypography.BodyLarge,
    bodyMedium = AppTypography.BodyMedium,
    bodySmall = AppTypography.BodySmall,
    labelLarge = AppTypography.LabelLarge,
    labelMedium = AppTypography.LabelMedium,
    labelSmall = AppTypography.LabelSmall
)

// 指挥中心风格 — HUD 字距与字体族
val CommandCenterTypography = Typography(
    displayLarge = AppTypography.DisplayLarge.copy(
        fontFamily = BlyyFontFamily.Display,
        letterSpacing = 1.sp
    ),
    displayMedium = AppTypography.DisplayMedium.copy(
        fontFamily = BlyyFontFamily.Display,
        letterSpacing = 0.5.sp
    ),
    displaySmall = AppTypography.DisplaySmall.copy(
        fontFamily = BlyyFontFamily.Display,
        letterSpacing = 0.5.sp
    ),
    headlineLarge = AppTypography.HeadlineLarge.copy(
        fontFamily = BlyyFontFamily.Display,
        letterSpacing = 0.5.sp
    ),
    headlineMedium = AppTypography.HeadlineMedium.copy(
        fontFamily = BlyyFontFamily.Display,
        letterSpacing = 0.3.sp
    ),
    headlineSmall = AppTypography.HeadlineSmall.copy(
        fontFamily = BlyyFontFamily.Display,
        letterSpacing = 0.3.sp
    ),
    titleLarge = AppTypography.TitleLarge.copy(
        fontFamily = BlyyFontFamily.Display,
        fontWeight = BlyyFontFamily.DisplayWeight,
        letterSpacing = 0.8.sp
    ),
    titleMedium = AppTypography.TitleMedium.copy(
        fontFamily = BlyyFontFamily.Display,
        letterSpacing = 0.5.sp
    ),
    titleSmall = AppTypography.TitleSmall.copy(
        fontFamily = BlyyFontFamily.Mono,
        letterSpacing = 0.3.sp
    ),
    bodyLarge = AppTypography.BodyLarge,
    bodyMedium = AppTypography.BodyMedium,
    bodySmall = AppTypography.BodySmall,
    labelLarge = AppTypography.LabelLarge.copy(
        fontFamily = BlyyFontFamily.Mono,
        letterSpacing = 0.4.sp
    ),
    labelMedium = AppTypography.LabelMedium.copy(
        fontFamily = BlyyFontFamily.Mono,
        letterSpacing = 0.5.sp
    ),
    labelSmall = AppTypography.LabelSmall.copy(
        fontFamily = BlyyFontFamily.Mono,
        letterSpacing = 0.6.sp
    )
)

/** @deprecated 使用 ClassicTypography 或 CommandCenterTypography */
val Typography = ClassicTypography
