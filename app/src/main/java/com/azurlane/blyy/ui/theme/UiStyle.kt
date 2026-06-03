package com.azurlane.blyy.ui.theme

import androidx.compose.runtime.compositionLocalOf

enum class UiStyle {
    /** 碧蓝航线指挥中心风格（新版 UI） */
    COMMAND_CENTER,
    /** 升级前的经典 Material 风格 */
    CLASSIC
}

val LocalUiStyle = compositionLocalOf { UiStyle.COMMAND_CENTER }

fun UiStyle.isCommandCenter(): Boolean = this == UiStyle.COMMAND_CENTER
