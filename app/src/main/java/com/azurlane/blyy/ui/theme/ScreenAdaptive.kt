package com.azurlane.blyy.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 屏幕自适应工具 — 为手表端（480×480 及以下）提供缩放后的尺寸值。
 *
 * 判断逻辑：取屏幕宽高中较小值，≤ 360dp 视为手表/小屏设备。
 * 大多数 480×480 像素手表在 hdpi/xhdpi 密度下，最小边约 240~320dp，可命中此阈值。
 */
@Composable
fun isWatchScreen(): Boolean {
    val config = LocalConfiguration.current
    return minOf(config.screenWidthDp, config.screenHeightDp) <= 360
}

/**
 * 根据屏幕尺寸返回自适应的 Dp 值
 */
@Composable
fun adaptiveDp(watch: Dp, normal: Dp): Dp =
    if (isWatchScreen()) watch else normal

/**
 * 根据屏幕尺寸返回自适应的 Sp 值
 */
@Composable
fun adaptiveSp(watch: TextUnit, normal: TextUnit): TextUnit =
    if (isWatchScreen()) watch else normal

/**
 * 根据屏幕尺寸返回自适应的 Int 值
 */
@Composable
fun adaptiveInt(watch: Int, normal: Int): Int =
    if (isWatchScreen()) watch else normal

/**
 * 自适应间距系统 — 手表端缩减至约 65%
 */
object WatchSpacing {
    val None = 0.dp
    val Xxs = 1.dp
    val Xs = 3.dp
    val Sm = 5.dp
    val Md = 8.dp
    val Lg = 10.dp
    val Xl = 13.dp
    val Xxl = 16.dp
    val Xxxl = 21.dp
    val Huge = 32.dp
    val Massive = 42.dp

    object Padding {
        val CardInner = 7.dp
        val CardOuter = 4.dp
        val ButtonHorizontal = 16.dp
        val ButtonVertical = 8.dp
        val InputHorizontal = 10.dp
        val InputVertical = 9.dp
        val ListItemHorizontal = 10.dp
        val ListItemVertical = 8.dp
        val ChipHorizontal = 8.dp
        val ChipVertical = 4.dp
        val TagHorizontal = 4.dp
        val TagVertical = 1.dp
    }

    object Gap {
        val Xs = 3.dp
        val Sm = 5.dp
        val Md = 8.dp
        val Lg = 10.dp
        val Xl = 13.dp
        val CardGrid = 8.dp
        val ListItem = 5.dp
        val Section = 16.dp
    }

    object Screen {
        val Horizontal = 10.dp
        val Vertical = 10.dp
        val Top = 10.dp
        val Bottom = 10.dp
    }

    object Card {
        val MinWidth = 90.dp
        val AspectRatio = 0.8f
        val CornerSize = 10.dp
        val Elevation = 3.dp
        val ElevationPressed = 1.dp
    }

    object Icon {
        val Xs = 10.dp
        val Sm = 13.dp
        val Md = 16.dp
        val Lg = 20.dp
        val Xl = 22.dp
        val Xxl = 26.dp
        val Huge = 36.dp
        val Massive = 48.dp
    }

    object Corner {
        val None = 0.dp
        val Xs = 3.dp
        val Sm = 5.dp
        val Md = 8.dp
        val Lg = 10.dp
        val Xl = 13.dp
        val Xxl = 16.dp
        val Full = 9999.dp
        val Chamfer = 7.dp
        val Panel = 8.dp
    }

    object Height {
        val Button = 40.dp
        val Input = 44.dp
        val NavigationBar = 56.dp
        val TopBar = 44.dp
        val ListItem = 48.dp
        val Divider = 1.dp
    }

    object Elevation {
        val None = 0.dp
        val Sm = 1.dp
        val Md = 2.dp
        val Lg = 4.dp
        val Xl = 8.dp
        val Xxl = 10.dp
    }

    object Border {
        val Thin = 1.dp
        val Normal = 1.dp
        val Thick = 2.dp
    }
}

/**
 * 自适应字体系统 — 手表端缩减至约 80%
 */
object WatchTypography {
    val DisplayLarge = 46.sp
    val DisplayMedium = 36.sp
    val DisplaySmall = 29.sp

    val HeadlineLarge = 26.sp
    val HeadlineMedium = 22.sp
    val HeadlineSmall = 19.sp

    val TitleLarge = 18.sp
    val TitleMedium = 13.sp
    val TitleSmall = 12.sp

    val BodyLarge = 13.sp
    val BodyMedium = 12.sp
    val BodySmall = 10.sp

    val LabelLarge = 12.sp
    val LabelMedium = 10.sp
    val LabelSmall = 9.sp

    val CardTitle = 12.sp
    val CardLabel = 8.sp
    val EmptyTitle = 19.sp
    val EmptyDescription = 11.sp
    val ButtonText = 13.sp
    val NavigationLabel = 10.sp
}
