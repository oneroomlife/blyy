package com.example.blyy.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object AppColors {
    
    // ==================== 品牌主色 ====================
    // 亮色主题 - 更柔和、更有高级感的紫色系
    val PrimaryLight: Color = Color(0xFF7C3AED)
    val PrimaryContainerLight: Color = Color(0xFFEDE9FE)
    val OnPrimaryLight: Color = Color(0xFFFFFFFF)
    val OnPrimaryContainerLight: Color = Color(0xFF4C1D95)
    
    // 暗色主题
    val PrimaryDark: Color = Color(0xFFA78BFA)
    val PrimaryContainerDark: Color = Color(0xFF5B21B6)
    val OnPrimaryDark: Color = Color(0xFF1E1B4B)
    val OnPrimaryContainerDark: Color = Color(0xFFF3E8FF)
    
    // ==================== 辅助色 ====================
    // 亮色主题 - 玫瑰粉和青蓝色
    val SecondaryLight: Color = Color(0xFFDB2777)
    val SecondaryContainerLight: Color = Color(0xFFFCE7F3)
    val OnSecondaryLight: Color = Color(0xFFFFFFFF)
    val OnSecondaryContainerLight: Color = Color(0xFF831843)
    
    val TertiaryLight: Color = Color(0xFF0891B2)
    val TertiaryContainerLight: Color = Color(0xFFCFFAFE)
    val OnTertiaryLight: Color = Color(0xFFFFFFFF)
    val OnTertiaryContainerLight: Color = Color(0xFF164E63)
    
    // 暗色主题
    val SecondaryDark: Color = Color(0xFFF472B6)
    val SecondaryContainerDark: Color = Color(0xFF9D174D)
    val OnSecondaryDark: Color = Color(0xFFFCE7F3)
    val OnSecondaryContainerDark: Color = Color(0xFFFCE7F3)
    
    val TertiaryDark: Color = Color(0xFF22D3EE)
    val TertiaryContainerDark: Color = Color(0xFF155E75)
    val OnTertiaryDark: Color = Color(0xFFECFEFF)
    val OnTertiaryContainerDark: Color = Color(0xFFCFFAFE)
    
    // ==================== 背景色 - 亮色主题 ====================
    val BackgroundLight: Color = Color(0xFFFAFAFA)
    val OnBackgroundLight: Color = Color(0xFF1A1A1A)
    val SurfaceLight: Color = Color(0xFFFAFAFA)
    val OnSurfaceLight: Color = Color(0xFF1A1A1A)
    
    // 浅色背景渐变 - 柔和的白色渐变
    val BackgroundGradientStartLight: Color = Color(0xFFFAFAFA)
    val BackgroundGradientMidLight: Color = Color(0xFFF5F5F5)
    val BackgroundGradientEndLight: Color = Color(0xFFEFEFEF)
    
    // ==================== 背景色 - 暗色主题 ====================
    val BackgroundDark: Color = Color(0xFF0F0F14)
    val OnBackgroundDark: Color = Color(0xFFE5E7EB)
    val SurfaceDark: Color = Color(0xFF0F0F14)
    val OnSurfaceDark: Color = Color(0xFFE5E7EB)
    
    // 深色背景渐变 - 更有深度
    val BackgroundGradientStartDark: Color = Color(0xFF0F0F14)
    val BackgroundGradientMidDark: Color = Color(0xFF16161D)
    val BackgroundGradientEndDark: Color = Color(0xFF1D1D26)
    
    // ==================== 表面色 - 亮色主题 ====================
    val SurfaceVariantLight: Color = Color(0xFFE5E7EB)
    val OnSurfaceVariantLight: Color = Color(0xFF4B5563)
    
    val SurfaceContainerLightestLight: Color = Color(0xFFFFFFFF)
    val SurfaceContainerLightLight: Color = Color(0xFFF9FAFB)
    val SurfaceContainerLight: Color = Color(0xFFF3F4F6)
    val SurfaceContainerHighLight: Color = Color(0xFFE5E7EB)
    val SurfaceContainerHighestLight: Color = Color(0xFFD1D5DB)
    
    // ==================== 表面色 - 暗色主题 ====================
    val SurfaceVariantDark: Color = Color(0xFF2D2D3A)
    val OnSurfaceVariantDark: Color = Color(0xFFCBD5E1)
    
    val SurfaceContainerLowestDark: Color = Color(0xFF0A0A0F)
    val SurfaceContainerLowDark: Color = Color(0xFF111118)
    val SurfaceContainerDark: Color = Color(0xFF18181F)
    val SurfaceContainerHighDark: Color = Color(0xFF1F1F28)
    val SurfaceContainerHighestDark: Color = Color(0xFF2D2D3A)
    
    // ==================== 毛玻璃效果 - 亮色主题 ====================
    val GlassSurfaceLight: Color = Color(0xCCFFFFFF)
    val GlassBorderLight: Color = Color(0x407C3AED)
    val GlassHighlightLight: Color = Color(0x10FFFFFF)
    
    // ==================== 毛玻璃效果 - 暗色主题 ====================
    val GlassSurfaceDark: Color = Color(0xCC1A1A24)
    val GlassBorderDark: Color = Color(0x40A78BFA)
    val GlassHighlightDark: Color = Color(0x10FFFFFF)
    
    // 兼容旧代码
    val GlassSurface: Color = GlassSurfaceDark
    val GlassBorder: Color = GlassBorderDark
    val GlassHighlight: Color = GlassHighlightDark
    
    // ==================== 稀有度颜色 ====================
    object Rarity {
        val Legendary: Color = Color(0xFFFFD700)
        val Decisive: Color = Color(0xFFFF6B6B)
        val SuperRare: Color = Color(0xFFA78BFA)
        val Priority: Color = Color(0xFF60A5FA)
        val Elite: Color = Color(0xFF34D399)
        val Rare: Color = Color(0xFF94A3B8)
        val Common: Color = Color(0xFF64748B)
        
        // 光晕效果
        val LegendaryGlow: Color = Color(0xFFFFD700).copy(alpha = 0.4f)
        val DecisiveGlow: Color = Color(0xFFFF6B6B).copy(alpha = 0.35f)
        val SuperRareGlow: Color = Color(0xFFA78BFA).copy(alpha = 0.3f)
        
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
                colors = listOf(Color(0xFFA78BFA), Color(0xFF8B5CF6))
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
            colors = listOf(Color(0xFF7C3AED), Color(0xFFA78BFA))
        )
        val Secondary = Brush.linearGradient(
            colors = listOf(Color(0xFFDB2777), Color(0xFFF472B6))
        )
        val Tertiary = Brush.linearGradient(
            colors = listOf(Color(0xFF0891B2), Color(0xFF22D3EE))
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
                Color.White.copy(alpha = 0.1f),
                Color.Transparent
            )
        )
    }
    
    // ==================== 功能色 - 亮色主题 ====================
    object SemanticLight {
        val Success: Color = Color(0xFF16A34A)
        val SuccessContainer: Color = Color(0xFFDCFCE7)
        val Warning: Color = Color(0xFFD97706)
        val WarningContainer: Color = Color(0xFFFEF3C7)
        val Error: Color = Color(0xFFDC2626)
        val ErrorContainer: Color = Color(0xFFFEE2E2)
        val Info: Color = Color(0xFF2563EB)
        val InfoContainer: Color = Color(0xFFDBEAFE)
    }
    
    // ==================== 功能色 - 暗色主题 ====================
    object SemanticDark {
        val Success: Color = Color(0xFF4ADE80)
        val SuccessContainer: Color = Color(0xFF14532D)
        val Warning: Color = Color(0xFFFBBF24)
        val WarningContainer: Color = Color(0xFF451A03)
        val Error: Color = Color(0xFFF87171)
        val ErrorContainer: Color = Color(0xFF450A0A)
        val Info: Color = Color(0xFF60A5FA)
        val InfoContainer: Color = Color(0xFF1E3A5F)
    }
    
    // ==================== 誓约状态色 ====================
    object Favorite {
        val Gold: Color = Color(0xFFFFD700)
        val GoldLight: Color = Color(0xFFFFE57F)
        val GoldDark: Color = Color(0xFFFFC107)
        val Glow: Color = Color(0x33FFD700)
    }
    
    // ==================== 中性灰度 - 亮色主题 ====================
    object NeutralLight {
        val Gray50: Color = Color(0xFFFAFAFA)
        val Gray100: Color = Color(0xFFF4F4F5)
        val Gray200: Color = Color(0xFFE4E4E7)
        val Gray300: Color = Color(0xFFD4D4D8)
        val Gray400: Color = Color(0xFFA1A1AA)
        val Gray500: Color = Color(0xFF71717A)
        val Gray600: Color = Color(0xFF52525B)
        val Gray700: Color = Color(0xFF3F3F46)
        val Gray800: Color = Color(0xFF27272A)
        val Gray900: Color = Color(0xFF18181B)
    }
    
    // ==================== 中性灰度 - 暗色主题 ====================
    object NeutralDark {
        val Gray50: Color = Color(0xFF18181B)
        val Gray100: Color = Color(0xFF27272A)
        val Gray200: Color = Color(0xFF3F3F46)
        val Gray300: Color = Color(0xFF52525B)
        val Gray400: Color = Color(0xFF71717A)
        val Gray500: Color = Color(0xFFA1A1AA)
        val Gray600: Color = Color(0xFFD4D4D8)
        val Gray700: Color = Color(0xFFE4E4E7)
        val Gray800: Color = Color(0xFFF4F4F5)
        val Gray900: Color = Color(0xFFFAFAFA)
    }
    
    // ==================== 特效色 ====================
    object Effect {
        // 亮色主题
        val ShimmerStartLight: Color = Color(0xFFE0E0E0)
        val ShimmerEndLight: Color = Color(0xFFF0F0F0)
        val OverlayLight: Color = Color(0x1A000000)
        val DividerLight: Color = Color(0x1F000000)
        
        // 暗色主题
        val ShimmerStartDark: Color = Color(0xFF3A3540)
        val ShimmerEndDark: Color = Color(0xFF2B2730)
        val OverlayDark: Color = Color(0x52000000)
        val DividerDark: Color = Color(0x1FFFFFFF)
        
        // 兼容旧代码
        val ShimmerStart: Color = ShimmerStartDark
        val ShimmerEnd: Color = ShimmerEndDark
        val OverlayLight_: Color = Color(0x52000000)
        val OverlayDark_: Color = Color(0x73000000)
        val Divider: Color = DividerDark
    }
    
    // ==================== 文本色 ====================
    object Text {
        // 亮色主题
        val PrimaryLight: Color = Color(0xFF1A1A1A)
        val SecondaryLight: Color = Color(0xFF4B5563)
        val TertiaryLight: Color = Color(0xFF9CA3AF)
        val DisabledLight: Color = Color(0xFFD1D5DB)
        
        // 暗色主题
        val PrimaryDark: Color = Color(0xFFF3F4F6)
        val SecondaryDark: Color = Color(0xFF9CA3AF)
        val TertiaryDark: Color = Color(0xFF6B7280)
        val DisabledDark: Color = Color(0xFF4B5563)
    }
    
    // ==================== 边框色 ====================
    object Border {
        // 亮色主题
        val LightLight: Color = Color(0xFFE5E7EB)
        val MediumLight: Color = Color(0xFFD1D5DB)
        val DarkLight: Color = Color(0xFF9CA3AF)
        
        // 暗色主题
        val LightDark: Color = Color(0xFF374151)
        val MediumDark: Color = Color(0xFF4B5563)
        val DarkDark: Color = Color(0xFF6B7280)
    }
}

// 兼容旧代码的别名
val Primary80 = AppColors.PrimaryDark
val Secondary80 = AppColors.SecondaryDark
val Tertiary80 = AppColors.TertiaryDark
val Primary40 = AppColors.PrimaryLight
val Secondary40 = AppColors.SecondaryLight
val Tertiary40 = AppColors.TertiaryLight
val LegendaryColor = AppColors.Rarity.Legendary
val SuperRareColor = AppColors.Rarity.SuperRare
val EliteColor = AppColors.Rarity.Elite
val RareColor = AppColors.Rarity.Rare
val CommonColor = AppColors.Rarity.Common
val BackgroundTop = AppColors.BackgroundGradientStartDark
val BackgroundMid = AppColors.BackgroundGradientMidDark
val BackgroundBottom = AppColors.BackgroundGradientEndDark
