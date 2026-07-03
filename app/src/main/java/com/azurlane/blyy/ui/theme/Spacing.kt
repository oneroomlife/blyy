package com.azurlane.blyy.ui.theme

import androidx.compose.ui.unit.dp

object AppSpacing {
    
    // ==================== 基础单位 (8dp 网格系统) ====================
    val None = 0.dp
    val Xxs = 2.dp
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 20.dp
    val Xxl = 24.dp
    val Xxxl = 32.dp
    /** 区块级间距 — 设计系统标准 40dp */
    val Xxxxl = 40.dp
    val Huge = 48.dp
    val Massive = 64.dp

    /** 段落间距 — 正文块之间 */
    object Paragraph {
        val Tight = Sm
        val Normal = Md
        val Relaxed = Lg
        val Loose = Xxl
    }
    
    // ==================== 组件内间距 ====================
    object Padding {
        val CardInner = 16.dp
        val CardOuter = 6.dp
        val ButtonHorizontal = 24.dp
        val ButtonVertical = 12.dp
        val InputHorizontal = 16.dp
        val InputVertical = 14.dp
        val ListItemHorizontal = 16.dp
        val ListItemVertical = 12.dp
        val ChipHorizontal = 12.dp
        val ChipVertical = 6.dp
        val TagHorizontal = 6.dp
        val TagVertical = 2.dp
    }
    
    // ==================== 组件间距 ====================
    object Gap {
        val Xs = 4.dp
        val Sm = 8.dp
        val Md = 12.dp
        val Lg = 16.dp
        val Xl = 20.dp
        val CardGrid = 12.dp
        val ListItem = 8.dp
        val Section = 24.dp
    }
    
    // ==================== 屏幕边距 ====================
    object Screen {
        val Horizontal = 16.dp
        val Vertical = 16.dp
        val Top = 16.dp
        val Bottom = 16.dp
    }
    
    // ==================== 卡片尺寸 ====================
    object Card {
        val MinWidth = 120.dp
        val AspectRatio = 0.75f
        /** 卡片圆角尺寸 — 统一引用 [Corner.Chamfer]，避免多处定义冲突 */
        val CornerSize: androidx.compose.ui.unit.Dp get() = Corner.Chamfer
        val Elevation = 4.dp
        val ElevationPressed = 2.dp
    }
    
    // ==================== 图标尺寸 ====================
    object Icon {
        val Xs = 12.dp
        val Sm = 16.dp
        /** 18dp — 列表项辅助图标常用尺寸 */
        val Md2 = 18.dp
        val Md = 20.dp
        val Lg = 24.dp
        /** 22dp — 播放器/控制栏图标 */
        val Xl1 = 22.dp
        val Xl = 28.dp
        val Xxl = 32.dp
        val Huge = 48.dp
        val Massive = 64.dp
    }
    
    // ==================== 圆角 ====================
    object Corner {
        val None = 0.dp
        val Xxs = 2.dp
        val Xs = 4.dp
        /** 14dp — GuessImage/GuessVoice 卡片常用圆角 */
        val Xs2 = 14.dp
        val Sm = 8.dp
        val Md = 12.dp
        val Lg = 16.dp
        val Xl = 20.dp
        val Xxl = 24.dp
        val Full = 9999.dp
        val Chamfer = 10.dp
        val Panel = 12.dp
        /** 28dp — 经典风格对话框/大圆角容器 */
        val Dialog = 28.dp
    }

    // ==================== HUD 装饰 ====================
    object Hud {
        val CornerDecorSize = 24.dp
        val AccentLineHeight = 2.dp
        val GridStep = 32.dp
    }
    
    // ==================== 阴影 ====================
    object Elevation {
        val None = 0.dp
        val Sm = 2.dp
        val Md = 4.dp
        val Lg = 8.dp
        val Xl = 12.dp
        val Xxl = 16.dp
    }
    
    // ==================== 边框 ====================
    object Border {
        val Thin = 1.dp
        val Normal = 2.dp
        val Thick = 3.dp
    }
    
    // ==================== 高度 ====================
    object Height {
        val Button = 48.dp
        val Input = 56.dp
        val NavigationBar = 80.dp
        val TopBar = 56.dp
        val ListItem = 64.dp
        val Divider = 1.dp
    }

    // ==================== 头像尺寸（吸收各 Screen 硬编码） ====================
    object Avatar {
        val Xs = 24.dp   // 列表项小头像
        val Sm = 32.dp   // 顶栏头像
        val Md = 36.dp   // 聊天头像
        val Lg = 48.dp   // 设置项头像
        /** 44dp — 答题选项/控制按钮头像 */
        val Lg1 = 44.dp
        val Xl = 64.dp   // 卡片头像
        val Xxl = 80.dp  // 详情页头像
        val Huge = 120.dp // 关于页/大头像
    }

    // ==================== 立绘/图片展示尺寸 ====================
    object Figure {
        val Sm = 110.dp  // 折叠态立绘
        val Md = 140.dp  // 语音页立绘
        val Lg = 160.dp  // 画廊页立绘
        val Xl = 180.dp  // 翻牌动画
        val Xxl = 220.dp // 全屏立绘
        /** 70dp — 卡片底部渐变遮罩高度 */
        val Banner = 70.dp
    }

    // ==================== 顶栏滚动隐藏偏移 ====================
    object TopBar {
        /** 滚动隐藏时的 Y 轴偏移（px），用于 animateFloatAsState 目标值 */
        const val HideOffsetPx = -300f
        /** 浮动 TopBar + 搜索区预估高度 */
        val FloatingHeight = 132.dp
        /** 内容区为避开浮动顶栏的额外顶部 padding */
        val ContentTopPadding = FloatingHeight + Lg
        /** 内容区为避开底栏的额外底部 padding */
        val ContentBottomPadding = Height.NavigationBar + Sm
    }

    // ==================== 游戏专用尺寸（原 GameStyles 尺寸，统一归口） ====================
    object Game {
        object Card {
            val CornerSize: androidx.compose.ui.unit.Dp get() = AppSpacing.Corner.Md
            val Elevation = 8.dp
            val InnerPadding = 20.dp
            val OuterPadding = 16.dp
        }
        object Button {
            val Height = 52.dp
            val CornerSize: androidx.compose.ui.unit.Dp get() = AppSpacing.Corner.Sm
            val IconSize = 20.dp
        }
        object Input {
            val Height = 56.dp
            val CornerSize = 20.dp
        }
        object Score {
            val ChipCornerSize = 20.dp
            val BannerCornerSize = 12.dp
        }
    }
}
