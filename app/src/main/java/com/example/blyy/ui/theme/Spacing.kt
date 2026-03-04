package com.example.blyy.ui.theme

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
    val Huge = 48.dp
    val Massive = 64.dp
    
    // ==================== 组件内间距 ====================
    object Padding {
        val CardInner = 10.dp
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
        val CornerSize = 16.dp
        val Elevation = 4.dp
        val ElevationPressed = 2.dp
    }
    
    // ==================== 图标尺寸 ====================
    object Icon {
        val Xs = 12.dp
        val Sm = 16.dp
        val Md = 20.dp
        val Lg = 24.dp
        val Xl = 28.dp
        val Xxl = 32.dp
        val Huge = 48.dp
        val Massive = 64.dp
    }
    
    // ==================== 圆角 ====================
    object Corner {
        val None = 0.dp
        val Xs = 4.dp
        val Sm = 8.dp
        val Md = 12.dp
        val Lg = 16.dp
        val Xl = 20.dp
        val Xxl = 24.dp
        val Full = 9999.dp
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
}
