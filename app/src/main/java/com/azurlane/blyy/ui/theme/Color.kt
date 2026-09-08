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

        /** 三色金属渐变 — 用于高级标题文字，模拟青金双色金属光泽 */
        val MetallicText = Brush.linearGradient(
            colors = listOf(
                Color(0xFF48CAE4),
                Color(0xFFFFFFFF),
                Color(0xFFFFD166)
            )
        )

        /** 暗色三色金属渐变 — 暗色模式下增强对比 */
        val MetallicTextDark = Brush.linearGradient(
            colors = listOf(
                Color(0xFF90E0EF),
                Color(0xFFFFE599),
                Color(0xFF48CAE4)
            )
        )

        /** 按钮流光 — 横向扫过的白色高光带 */
        val ButtonShimmer = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                Color.White.copy(alpha = 0.0f),
                Color.White.copy(alpha = 0.35f),
                Color.White.copy(alpha = 0.0f),
                Color.Transparent
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
        val Green: Color = Color(0xFF2E7D32)
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
    val Silver: Color = Color(0xFFC0C0C0)
    val Bronze: Color = Color(0xFFCD7F32)

    // 前三名奖牌渐变对（浅→深），用于排行榜名次卡片
    val GoldGradient = listOf(Gold, Color(0xFFFFA500))
    val SilverGradient = listOf(Silver, Color(0xFFA0A0A0))
    val BronzeGradient = listOf(Bronze, Color(0xFFB87333))
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

    // 带尾尖语音气泡（VoiceScreen 台词气泡 / 秘书台桌宠气泡共用）
    val SpeechBubbleLight: Color = Color.White.copy(alpha = 0.95f)
    val SpeechBubbleDark: Color = Color(0xFF2C2C2E).copy(alpha = 0.9f)

    // 连接成功状态（啾信 API 连接测试通过提示）
    val SuccessLight: Color = Color(0xFF2E7D32)
    val SuccessDark: Color = Color(0xFF7FE09B)
}

/**
 * JUUSTAGRAM 消息界面设计规范色板
 * 来源：UI_work/juustagram-messaging-ui/docs/design-spec.md
 * 主色 #5BA4E6，与 ChatColors 的青蓝主题区分
 */
object JuusPalette {
    // 主色调
    val Primary = Color(0xFF5BA4E6)
    val PrimaryLight = Color(0xFFD6EBFF)
    val PrimaryLighter = Color(0xFFE8F4FD)
    val PrimaryDark = Color(0xFF3B8FD9)

    // 背景色
    val Bg = Color(0xFFF0F4F8)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceDim = Color(0xFFF7F9FB)

    // 文字色
    val TextPrimary = Color(0xFF1A1A2E)
    val TextSecondary = Color(0xFF555566)
    val TextTertiary = Color(0xFF8899AA)
    val TextOnPrimary = Color(0xFFFFFFFF)

    // 功能色
    val Badge = Color(0xFFFF3B30)
    val TagWaiting = Color(0xFFFF9500)
    val TagWaitingBg = Color(0x1FFF9500) // rgba(255,149,0,0.12)

    // 语音消息气泡（粉色系，与普通聊天气泡区分）
    val VoiceBubble = Color(0xFFFFF5F8)
    val VoiceBorder = Color(0xFFF9D5E5)
    val VoiceAccent = Color(0xFFFF69B4)

    // 错误消息
    val ErrorBg = Color(0xFFFFF0F0)
    val ErrorText = Color(0xFFFF4949)

    // 弹窗表面（聊天气泡操作/编辑弹窗）
    val DialogSurface = Color(0xFFFFFFFF)
    val DialogDivider = Color(0x0D000000)      // 5% 黑
    val DialogWarningBg = Color(0x0D5BA4E6)    // 5% 主色

    // 聊天气泡
    val BubbleIncoming = Color(0xFFFFFFFF)
    val BubbleOutgoing = Color(0xFF5BA4E6)
    val BubbleIncomingText = Color(0xFF1A1A2E)
    val BubbleOutgoingText = Color(0xFFFFFFFF)

    // 边框与分割线
    val Border = Color(0xFFE4E8EC)
    val BorderLight = Color(0xFFEFF2F5)
    val Divider = Color(0xFFEDEFF2)
    val ItemBorder = Color(0xFFF4F6F8)

    // 导航栏渐变
    val NavGradientTop = Color(0xFFE0F2FE)
    val NavGradientBottom = Color(0xFFBAE6FD)

    // 毛玻璃半透明
    val Glass95 = Color(0xF2FFFFFF) // rgba(255,255,255,0.95)
    val Glass85 = Color(0xD9FFFFFF) // rgba(255,255,255,0.85)
    val Glass35 = Color(0x59FFFFFF) // rgba(255,255,255,0.35)

    // 筛选弹窗
    val FilterOverlay = Color(0x66000000) // rgba(0,0,0,0.4)
    val FilterPillUnselectedBg = Color(0xFFF4F6F8)
    val FilterCancelBg = Color(0xFFF4F6F8)

    /**
     * 会话列表页专用色板（玻璃质感设计）
     * 归口管理，替代 ConversationListScreen 内散落的硬编码颜色
     */
    object ListPage {
        // 基础色别名 — 沿用 JuusPalette 主色与文字色，供列表页统一引用
        val Primary = JuusPalette.Primary
        val PrimaryLight = JuusPalette.PrimaryLight
        val TextPrimary = JuusPalette.TextPrimary
        val TextSecondary = JuusPalette.TextSecondary
        val TextTertiary = JuusPalette.TextTertiary
        val Divider = JuusPalette.Divider

        // 背景渐变
        val BgGradientStart = Color(0xFFD6EFFF)
        val BgGradientEnd = Color(0xFFE8F4FE)

        // 导航栏渐变
        val NavGradientTop = Color(0xFF7DD3FC)
        val NavGradientBottom = Color(0xFF38BDF8)

        // 玻璃表面 — 提高不透明度补偿移除阴影后的深度感
        val GlassCard = Color(0xD9FFFFFF)          // 85% 白 — 通透且有实体感
        val GlassCardSelected = Color(0xF0F0F7FF)   // 94% 白微蓝 — 选中态
        val GlassHeader = Color(0xE6FFFFFF)         // 90% 白 — 明亮玻璃胶囊
        val GlassPill = Color(0x99FFFFFF)           // 60% 白
        val GlassEditBadge = Color(0x335BA4E6)
        val ChannelEmojiBg = Color(0x33BAE6FD)
        val ErrorRed = Color(0xFFE53935)
        val DropdownSurface = Color(0xFFFFFFFF)

        // 玻璃边框 — 仅用极淡白色提供边缘定义，不与shadow叠加
        val GlassBorder = Color(0x33FFFFFF)         // 20% 白 — 极淡边框
        val GlassBorderSelected = Color(0x665BA4E6) // 40% 蓝 — 选中态
        val GlassHighlight = Color(0x55FFFFFF)      // 33% 白 — 顶部高光

        // 新建聊天 Sheet 表面
        val SheetCard = Color(0xFFFFFFFF)
        val SheetSection = Color(0xFFF8FAFC)
        val SheetSelectedBg = Color(0xFFE6F2FF)
        val SheetFieldBg = Color(0xFFF1F5F9)
        val SheetFieldBorder = Color(0xFFE2E8F0)

        object Dark {
            val Primary = JuusPalette.Dark.Primary
            val PrimaryLight = JuusPalette.Dark.PrimaryLight
            val TextPrimary = JuusPalette.Dark.TextPrimary
            val TextSecondary = JuusPalette.Dark.TextSecondary
            val TextTertiary = JuusPalette.Dark.TextTertiary
            val Divider = JuusPalette.Dark.Divider

            val BgGradientStart = Color(0xFF0A1525)
            val BgGradientEnd = Color(0xFF102035)

            val NavGradientTop = Color(0xFF0F2038)
            val NavGradientBottom = Color(0xFF1A3050)

            val GlassCard = Color(0xCC1E293B)
            val GlassCardSelected = Color(0xE6243B55)
            val GlassHeader = Color(0xE61E293B)
            val GlassPill = Color(0x991E293B)
            val GlassEditBadge = Color(0x335BA4E6)
            val ChannelEmojiBg = Color(0x33243559)
            val ErrorRed = Color(0xFFFF6B6B)
            val DropdownSurface = Color(0xFF1E293B)

            val GlassBorder = Color(0x15FFFFFF)
            val GlassBorderSelected = Color(0x405BA4E6)
            val GlassHighlight = Color(0x22FFFFFF)

            val SheetCard = Color(0xFF1E293B)
            val SheetSection = Color(0xFF161922)
            val SheetSelectedBg = Color(0xFF1E2A44)
            val SheetFieldBg = Color(0xFF0F172A)
            val SheetFieldBorder = Color(0xFF334155)
        }
    }

    // 暗色模式适配
    object Dark {
        val Bg = Color(0xFF0D1B2A)
        val Surface = Color(0xFF1A2335)
        val SurfaceDim = Color(0xFF152033)
        val Primary = Color(0xFF5BA4E6)
        val PrimaryLight = Color(0xFF2A4A6B)
        val PrimaryLighter = Color(0xFF1F3556)
        val PrimaryDark = Color(0xFF3B8FD9)
        val Badge = Color(0xFFFF453A)
        val TextPrimary = Color(0xFFE2EAF4)
        val TextSecondary = Color(0xFFA8BCD0)
        val TextTertiary = Color(0xFF7A9AB0)
        val TextOnPrimary = Color(0xFFFFFFFF)
        val BubbleIncoming = Color(0xFF243559)
        val BubbleOutgoing = Color(0xFF3B8FD9)
        val BubbleIncomingText = Color(0xFFE2EAF4)
        val BubbleOutgoingText = Color(0xFFFFFFFF)
        val Border = Color(0xFF2A3F5F)
        val BorderLight = Color(0xFF1F2F45)
        val Divider = Color(0xFF2A3F5F)
        val ItemBorder = Color(0xFF1F2F45)
        val Glass95 = Color(0xF20F2038)
        val Glass85 = Color(0xD90F2038)
        val Glass35 = Color(0x590F2038)
        val NavGradientTop = Color(0xFF0F2038)
        val NavGradientBottom = Color(0xFF1A3050)
        val FilterPillUnselectedBg = Color(0xFF1F2F45)
        val FilterCancelBg = Color(0xFF1F2F45)
        val TagWaiting = Color(0xFFFF9500)
        val TagWaitingBg = Color(0x4DFF9500)

        // 语音消息气泡（粉色系）
        val VoiceBubble = Color(0xFF2A1A28)
        val VoiceBorder = Color(0xFF4A2A44)
        val VoiceAccent = Color(0xFFFF69B4)

        // 错误消息
        val ErrorBg = Color(0xFF2A1A1A)
        val ErrorText = Color(0xFFFF6B6B)

        // 弹窗表面
        val DialogSurface = Color(0xFF1E293B)
        val DialogDivider = Color(0x1AFFFFFF)   // 10% 白
        val DialogWarningBg = Color(0x1A5BA4E6) // 10% 主色
    }
}
