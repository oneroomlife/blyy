package com.azurlane.blyy.ui.theme

import androidx.compose.runtime.compositionLocalOf

enum class UiStyle {
    /** 碧蓝航线指挥中心风格（新版 UI） */
    COMMAND_CENTER,
    /** 升级前的经典 Material 风格 */
    CLASSIC
}

val LocalUiStyle = compositionLocalOf { UiStyle.COMMAND_CENTER }

/** 统一的深浅色判断，由 BlyyTheme 的 darkTheme 参数驱动，替代 isSystemInDarkTheme() */
val LocalIsDark = compositionLocalOf { true }

/** 手表/小屏判断，由 BlyyTheme 注入，替代每次调用 isWatchScreen() */
val LocalIsWatch = compositionLocalOf { false }

fun UiStyle.isCommandCenter(): Boolean = this == UiStyle.COMMAND_CENTER
