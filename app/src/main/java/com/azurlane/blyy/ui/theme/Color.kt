package com.azurlane.blyy.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object AppColors {

    // ==================== 品牌主色 — 碧蓝航线指挥中心青蓝 ====================
    val PrimaryLight: Color = Color(0xFF0096C7)
    val PrimaryContainerLight: Color = Color(0xFFCAF0F8)
    val OnPrimaryLight: Color = Color(0xFFFFFFFF)
    val OnPrimaryContainerLight: Color = Color(0xFF023E8A)

    val PrimaryDark: Color = Color(0xFF48CAE4)
    val PrimaryContainerDark: Color = Color(0xFF0077B6)
    /** WCAG AA — 深青底字，对比 PrimaryDark ≥ 4.5:1 */
    val OnPrimaryDark: Color = Color(0xFF002B44)
    val OnPrimaryContainerDark: Color = Color(0xFFCAF0F8)

    // ==================== 辅助色 — 金色高亮 ====================
    val SecondaryLight: Color = Color(0xFFE8A838)
    val SecondaryContainerLight: Color = Color(0xFFFFF3CD)
    val OnSecondaryLight: Color = Color(0xFF1A1200)
    val OnSecondaryContainerLight: Color = Color(0xFF5C4000)

    val TertiaryLight: Color = Color(0xFF5B8DEF)
    val TertiaryContainerLight: Color = Color(0xFFDBEAFE)
    val OnTertiaryLight: Color = Color(0xFFFFFFFF)
    val OnTertiaryContainerLight: Color = Color(0xFF1E3A8A)

    val SecondaryDark: Color = Color(0xFFFFD166)
    val SecondaryContainerDark: Color = Color(0xFF6B4F00)
    val OnSecondaryDark: Color = Color(0xFF1A1200)
    val OnSecondaryContainerDark: Color = Color(0xFFFFF3CD)

    val TertiaryDark: Color = Color(0xFF7EB6FF)
    val TertiaryContainerDark: Color = Color(0xFF1E3A5F)
    val OnTertiaryDark: Color = Color(0xFFECFEFF)
    val OnTertiaryContainerDark: Color = Color(0xFFDBEAFE)

    // ==================== 背景色 — 深海指挥室（Background 比 Surface 低一阶，增强层次） ====================
    val BackgroundLight: Color = Color(0xFFD4E8F5)
    val OnBackgroundLight: Color = Color(0xFF0A1628)
    val SurfaceLight: Color = Color(0xFFF5FAFF)
    val OnSurfaceLight: Color = Color(0xFF0A1628)

    val BackgroundGradientStartLight: Color = Color(0xFFD6EBF7)
    val BackgroundGradientMidLight: Color = Color(0xFFE8F4FC)
    val BackgroundGradientEndLight: Color = Color(0xFFC5DFF0)

    val BackgroundDark: Color = Color(0xFF0A1628)
    val OnBackgroundDark: Color = Color(0xFFE2EAF4)
    val SurfaceDark: Color = Color(0xFF0A1628)
    val OnSurfaceDark: Color = Color(0xFFE2EAF4)

    val BackgroundGradientStartDark: Color = Color(0xFF0A1628)
    val BackgroundGradientMidDark: Color = Color(0xFF0F1D32)
    val BackgroundGradientEndDark: Color = Color(0xFF152238)

    // ==================== 表面色 ====================
    val SurfaceVariantLight: Color = Color(0xFFB8D4E8)
    val OnSurfaceVariantLight: Color = Color(0xFF3D5A73)

    val SurfaceContainerLightestLight: Color = Color(0xFFF0F8FF)
    val SurfaceContainerLightLight: Color = Color(0xFFE0EFF8)
    val SurfaceContainerLight: Color = Color(0xFFD0E4F2)
    val SurfaceContainerHighLight: Color = Color(0xFFB8D4E8)
    val SurfaceContainerHighestLight: Color = Color(0xFF9BB8CC)

    val SurfaceVariantDark: Color = Color(0xFF1A3050)
    val OnSurfaceVariantDark: Color = Color(0xFF94A8BE)

    val SurfaceContainerLowestDark: Color = Color(0xFF060E18)
    val SurfaceContainerLowDark: Color = Color(0xFF0C1828)
    val SurfaceContainerDark: Color = Color(0xFF122038)
    val SurfaceContainerHighDark: Color = Color(0xFF1A3050)
    val SurfaceContainerHighestDark: Color = Color(0xFF243850)

    // ==================== 指挥面板色 ====================
    object Panel {
        val Dark: Color = Color(0xCC0F2038)
        val Light: Color = Color(0xCCFFFFFF)
        val BorderDark: Color = Color(0x6648CAE4)
        val BorderLight: Color = Color(0x660096C7)
    }

    // ==================== 金色强调 ====================
    object Accent {
        val Gold: Color = Color(0xFFFFD166)
        val GoldLight: Color = Color(0xFFFFE599)
        val GoldDark: Color = Color(0xFFE8A838)
        val Cyan: Color = Color(0xFF48CAE4)
        val CyanGlow: Color = Color(0x3348CAE4)
    }

    // ==================== 毛玻璃效果 ====================
    val GlassSurfaceLight: Color = Color(0xCCF0F8FF)
    val GlassBorderLight: Color = Color(0x500096C7)
    val GlassHighlightLight: Color = Color(0x15FFFFFF)

    val GlassSurfaceDark: Color = Color(0xCC0F2038)
    val GlassBorderDark: Color = Color(0x5048CAE4)
    val GlassHighlightDark: Color = Color(0x10FFFFFF)

    // ==================== 稀有度颜色 ====================
    object Rarity {
        val Legendary: Color = Color(0xFFFFD700)
        val Decisive: Color = Color(0xFFFF6B6B)
        val SuperRare: Color = Color(0xFF48CAE4)
        val Priority: Color = Color(0xFF60A5FA)
        val Elite: Color = Color(0xFF34D399)
        val Rare: Color = Color(0xFF94A3B8)
        val Common: Color = Color(0xFF64748B)

        val LegendaryGlow: Color = Color(0xFFFFD700).copy(alpha = 0.4f)
        val DecisiveGlow: Color = Color(0xFFFF6B6B).copy(alpha = 0.35f)
        val SuperRareGlow: Color = Color(0xFF48CAE4).copy(alpha = 0.35f)

        fun getRarityColor(rarity: String): Color = when (rarity) {
            "海上传奇" -> Legendary
            "决战方案" -> Decisive
            "超稀有" -> SuperRare
            "最高方案" -> Priority
            "精锐" -> Elite
            "稀有" -> Rare
            else -> Common
        }

        fun getRarityGradient(rarity: String): Brush = when (rarity) {
            "海上传奇" -> Brush.linearGradient(
                colors = listOf(Color(0xFFFFD700), Color(0xFFFFA500))
            )
            "决战方案" -> Brush.linearGradient(
                colors = listOf(Color(0xFFFF6B6B), Color(0xFFFF8E53))
            )
            "超稀有" -> Brush.linearGradient(
                colors = listOf(Color(0xFF48CAE4), Color(0xFF0096C7))
            )
            else -> Brush.linearGradient(
                colors = listOf(getRarityColor(rarity), getRarityColor(rarity))
            )
        }

        fun isHighRarity(rarity: String): Boolean =
            rarity in listOf("海上传奇", "决战方案", "超稀有", "最高方案")
    }

    // ==================== 渐变色板 ====================
    object Gradient {
        val Primary = Brush.linearGradient(
            colors = listOf(Color(0xFF0096C7), Color(0xFF48CAE4))
        )
        val Secondary = Brush.linearGradient(
            colors = listOf(Color(0xFFE8A838), Color(0xFFFFD166))
        )
        val Tertiary = Brush.linearGradient(
            colors = listOf(Color(0xFF5B8DEF), Color(0xFF7EB6FF))
        )

        fun BackgroundLight() = Brush.verticalGradient(
            colors = listOf(
                BackgroundGradientStartLight,
                BackgroundGradientMidLight,
                BackgroundGradientEndLight
            )
        )

        fun BackgroundDark() = Brush.verticalGradient(
            colors = listOf(
                BackgroundGradientStartDark,
                BackgroundGradientMidDark,
                BackgroundGradientEndDark
            )
        )

        val CardGlow = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.08f),
                Color.Transparent
            )
        )

        val HudAccent = Brush.horizontalGradient(
            colors = listOf(
                Color(0xFF48CAE4),
                Color(0xFFFFD166),
                Color(0xFF48CAE4)
            )
        )

        /** 面板边框渐变 — 青蓝到金色，营造 HUD 描边质感 */
        val PanelBorder = Brush.linearGradient(
            colors = listOf(
                Color(0xFF48CAE4).copy(alpha = 0.6f),
                Color(0xFFFFD166).copy(alpha = 0.25f),
                Color(0xFF48CAE4).copy(alpha = 0.4f)
            )
        )

        /** 卡片边框渐变 — 柔和的青蓝描边 */
        val CardBorder = Brush.linearGradient(
            colors = listOf(
                Color(0xFF48CAE4).copy(alpha = 0.4f),
                Color(0xFF48CAE4).copy(alpha = 0.15f)
            )
        )

        /** 金色强调渐变 — 用于高亮按钮/标题装饰 */
        val GoldAccent = Brush.linearGradient(
            colors = listOf(Color(0xFFFFE599), Color(0xFFE8A838), Color(0xFFFFD166))
        )

        /** 顶部高光 — 营造面板立体感 */
        val HighlightTop = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.12f),
                Color.Transparent
            )
        )

        /** 底部阴影 — 营造面板下沉感 */
        val ShadowBottom = Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                Color.Black.copy(alpha = 0.15f)
            )
        )

        /** 毛玻璃边框渐变 — 配合 GlassSurface 使用 */
        val GlassBorderLight = Brush.linearGradient(
            colors = listOf(
                Color(0xFF0096C7).copy(alpha = 0.3f),
                Color(0xFFFFD166).copy(alpha = 0.15f),
                Color(0xFF0096C7).copy(alpha = 0.2f)
            )
        )

        val GlassBorderDark = Brush.linearGradient(
            colors = listOf(
                Color(0xFF48CAE4).copy(alpha = 0.35f),
                Color(0xFFFFD166).copy(alpha = 0.2f),
                Color(0xFF48CAE4).copy(alpha = 0.25f)
            )
        )
    }

    // ==================== 功能色 ====================
    object SemanticLight {
        val Success: Color = Color(0xFF16A34A)
        val SuccessContainer: Color = Color(0xFFDCFCE7)
        val Warning: Color = Color(0xFFD97706)
        val WarningContainer: Color = Color(0xFFFEF3C7)
        val Error: Color = Color(0xFFDC2626)
        val ErrorContainer: Color = Color(0xFFFEE2E2)
        val Info: Color = Color(0xFF0096C7)
        val InfoContainer: Color = Color(0xFFCAF0F8)
    }

    object SemanticDark {
        val Success: Color = Color(0xFF4ADE80)
        val SuccessContainer: Color = Color(0xFF14532D)
        val Warning: Color = Color(0xFFFBBF24)
        val WarningContainer: Color = Color(0xFF451A03)
        val Error: Color = Color(0xFFF87171)
        val ErrorContainer: Color = Color(0xFF450A0A)
        val Info: Color = Color(0xFF48CAE4)
        val InfoContainer: Color = Color(0xFF023E8A)
    }

    object Favorite {
        val Gold: Color = Color(0xFFFFD700)
        val GoldLight: Color = Color(0xFFFFE57F)
        val GoldDark: Color = Color(0xFFFFC107)
        val Glow: Color = Color(0x33FFD700)
        val Pink: Color = Color(0xFFFF69B4)
        val PinkLight: Color = Color(0xFFFFB6C1)
        val PinkDark: Color = Color(0xFFFF1493)
        val Green: Color = Color(0xFF4CAF50)
    }

    object NeutralLight {
        val Gray50: Color = Color(0xFFF0F8FF)
        val Gray100: Color = Color(0xFFE0EFF8)
        val Gray200: Color = Color(0xFFB8D4E8)
        val Gray300: Color = Color(0xFF9BB8CC)
        val Gray400: Color = Color(0xFF7A9AB0)
        val Gray500: Color = Color(0xFF5A7A90)
        val Gray600: Color = Color(0xFF3D5A73)
        val Gray700: Color = Color(0xFF2A4058)
        val Gray800: Color = Color(0xFF1A3050)
        val Gray900: Color = Color(0xFF0A1628)
    }

    object NeutralDark {
        val Gray50: Color = Color(0xFF0A1628)
        val Gray100: Color = Color(0xFF122038)
        val Gray200: Color = Color(0xFF1A3050)
        val Gray300: Color = Color(0xFF243850)
        val Gray400: Color = Color(0xFF3D5A73)
        val Gray500: Color = Color(0xFF5A7A90)
        val Gray600: Color = Color(0xFF7A9AB0)
        val Gray700: Color = Color(0xFF94A8BE)
        val Gray800: Color = Color(0xFFB8C8D8)
        val Gray900: Color = Color(0xFFE2EAF4)
    }

    object Effect {
        val ShimmerStartLight: Color = Color(0xFFB8D4E8)
        val ShimmerEndLight: Color = Color(0xFFD0E4F2)
        val OverlayLight: Color = Color(0x1A000000)
        val DividerLight: Color = Color(0x1F0096C7)
        val GridLight: Color = Color(0x180096C7)
        val TopGlowLight: Color = Color(0x200096C7)

        val ShimmerStartDark: Color = Color(0xFF1A3050)
        val ShimmerEndDark: Color = Color(0xFF122038)
        val OverlayDark: Color = Color(0x52000000)
        val DividerDark: Color = Color(0x1F48CAE4)
        val GridDark: Color = Color(0x0D48CAE4)
        val TopGlowDark: Color = Color(0x2548CAE4)
    }

    object Text {
        val PrimaryLight: Color = Color(0xFF0A1628)
        val SecondaryLight: Color = Color(0xFF3D5A73)
        val TertiaryLight: Color = Color(0xFF7A9AB0)
        val DisabledLight: Color = Color(0xFFB8D4E8)

        val PrimaryDark: Color = Color(0xFFE2EAF4)
        val SecondaryDark: Color = Color(0xFF94A8BE)
        val TertiaryDark: Color = Color(0xFF5A7A90)
        val DisabledDark: Color = Color(0xFF3D5A73)
    }

    object Border {
        val LightLight: Color = Color(0xFFB8D4E8)
        val MediumLight: Color = Color(0xFF9BB8CC)
        val DarkLight: Color = Color(0xFF7A9AB0)

        val LightDark: Color = Color(0xFF1A3050)
        val MediumDark: Color = Color(0xFF243850)
        val DarkDark: Color = Color(0xFF3D5A73)
    }
}

/** 升级前经典紫色 Material 配色 */
object ClassicColors {
    val PrimaryLight: Color = Color(0xFF7C3AED)
    val PrimaryContainerLight: Color = Color(0xFFEDE9FE)
    val OnPrimaryLight: Color = Color(0xFFFFFFFF)
    val OnPrimaryContainerLight: Color = Color(0xFF4C1D95)

    val PrimaryDark: Color = Color(0xFFA78BFA)
    val PrimaryContainerDark: Color = Color(0xFF5B21B6)
    val OnPrimaryDark: Color = Color(0xFF1E1B4B)
    val OnPrimaryContainerDark: Color = Color(0xFFF3E8FF)

    val SecondaryLight: Color = Color(0xFFDB2777)
    val SecondaryContainerLight: Color = Color(0xFFFCE7F3)
    val OnSecondaryLight: Color = Color(0xFFFFFFFF)
    val OnSecondaryContainerLight: Color = Color(0xFF831843)

    val TertiaryLight: Color = Color(0xFF0891B2)
    val TertiaryContainerLight: Color = Color(0xFFCFFAFE)
    val OnTertiaryLight: Color = Color(0xFFFFFFFF)
    val OnTertiaryContainerLight: Color = Color(0xFF164E63)

    val SecondaryDark: Color = Color(0xFFF472B6)
    val SecondaryContainerDark: Color = Color(0xFF9D174D)
    val OnSecondaryDark: Color = Color(0xFFFCE7F3)
    val OnSecondaryContainerDark: Color = Color(0xFFFCE7F3)

    val TertiaryDark: Color = Color(0xFF22D3EE)
    val TertiaryContainerDark: Color = Color(0xFF155E75)
    val OnTertiaryDark: Color = Color(0xFFECFEFF)
    val OnTertiaryContainerDark: Color = Color(0xFFCFFAFE)

    val BackgroundLight: Color = Color(0xFFFAFAFA)
    val OnBackgroundLight: Color = Color(0xFF1A1A1A)
    val SurfaceLight: Color = Color(0xFFFAFAFA)
    val OnSurfaceLight: Color = Color(0xFF1A1A1A)

    val BackgroundDark: Color = Color(0xFF0F0F14)
    val OnBackgroundDark: Color = Color(0xFFE5E7EB)
    val SurfaceDark: Color = Color(0xFF0F0F14)
    val OnSurfaceDark: Color = Color(0xFFE5E7EB)

    val SurfaceVariantLight: Color = Color(0xFFE5E7EB)
    val OnSurfaceVariantLight: Color = Color(0xFF4B5563)
    val SurfaceContainerLight: Color = Color(0xFFF3F4F6)
    val SurfaceContainerHighLight: Color = Color(0xFFE5E7EB)
    val SurfaceContainerHighestLight: Color = Color(0xFFD1D5DB)

    val SurfaceVariantDark: Color = Color(0xFF2D2D3A)
    val OnSurfaceVariantDark: Color = Color(0xFFCBD5E1)
    val SurfaceContainerDark: Color = Color(0xFF18181F)
    val SurfaceContainerHighDark: Color = Color(0xFF1F1F28)
    val SurfaceContainerHighestDark: Color = Color(0xFF2D2D3A)

    val GlassSurfaceDark: Color = Color(0xCC1A1A24)
    val GlassBorderDark: Color = Color(0x40A78BFA)
    val GlassSurfaceLight: Color = Color(0xCCFFFFFF)
    val GlassBorderLight: Color = Color(0x407C3AED)
}

/**
 * 排行榜奖牌色 — 前三名专用，统一管理避免各 Screen 硬编码。
 */
object MedalColors {
    val Gold: Color = Color(0xFFFFD700)
    val GoldDark: Color = Color(0xFFB87333)
    val Silver: Color = Color(0xFFC0C0C0)
    val SilverDark: Color = Color(0xFF9A9A9A)
    val Bronze: Color = Color(0xFFCD7F32)
    val BronzeDark: Color = Color(0xFF8B5A2B)

    fun medalColor(rank: Int, isDark: Boolean): Color = when (rank) {
        1 -> if (isDark) GoldDark else Gold
        2 -> if (isDark) SilverDark else Silver
        3 -> if (isDark) BronzeDark else Bronze
        else -> Color.Transparent
    }
}

/**
 * 啾信聊天色 — 统一管理聊天气泡颜色，替代各 Screen 硬编码的 JuusColors。
 * 颜色基于碧蓝航线青蓝主色派生，保持与整体主题一致。
 */
object ChatColors {
    // 自己的消息气泡（青蓝系）
    val BubbleSelfLight: Color = Color(0xFF0096C7)
    val BubbleSelfDark: Color = Color(0xFF0077B6)
    val OnBubbleSelfLight: Color = Color(0xFFFFFFFF)
    val OnBubbleSelfDark: Color = Color(0xFFCAF0F8)

    // 对方的消息气泡（浅色容器）
    val BubbleOtherLight: Color = Color(0xFFE0EFF8)
    val BubbleOtherDark: Color = Color(0xFF1A3050)
    val OnBubbleOtherLight: Color = Color(0xFF0A1628)
    val OnBubbleOtherDark: Color = Color(0xFFE2EAF4)

    // 时间戳/已读等辅助文字
    val TimestampLight: Color = Color(0xFF7A9AB0)
    val TimestampDark: Color = Color(0xFF5A7A90)
}
