package com.azurlane.blyy.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * M3 语义色扩展 — Success / Warning / Info（非 ColorScheme 内置字段）。
 * 由 [BlyyTheme] 注入，双 UI 风格共用同一语义定义。
 */
data class SemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color
)

val LocalSemanticColors = compositionLocalOf {
    SemanticColors(
        success = AppColors.SemanticLight.Success,
        onSuccess = Color.White,
        successContainer = AppColors.SemanticLight.SuccessContainer,
        onSuccessContainer = AppColors.SemanticLight.Success,
        warning = AppColors.SemanticLight.Warning,
        onWarning = Color.White,
        warningContainer = AppColors.SemanticLight.WarningContainer,
        onWarningContainer = AppColors.SemanticLight.Warning,
        info = AppColors.SemanticLight.Info,
        onInfo = Color.White,
        infoContainer = AppColors.SemanticLight.InfoContainer,
        onInfoContainer = AppColors.SemanticLight.Info
    )
}

fun semanticColors(darkTheme: Boolean): SemanticColors = if (darkTheme) {
    SemanticColors(
        success = AppColors.SemanticDark.Success,
        onSuccess = AppColors.OnBackgroundDark,
        successContainer = AppColors.SemanticDark.SuccessContainer,
        onSuccessContainer = AppColors.SemanticDark.Success,
        warning = AppColors.SemanticDark.Warning,
        onWarning = AppColors.OnBackgroundDark,
        warningContainer = AppColors.SemanticDark.WarningContainer,
        onWarningContainer = AppColors.SemanticDark.Warning,
        info = AppColors.SemanticDark.Info,
        onInfo = AppColors.OnBackgroundDark,
        infoContainer = AppColors.SemanticDark.InfoContainer,
        onInfoContainer = AppColors.SemanticDark.Info
    )
} else {
    SemanticColors(
        success = AppColors.SemanticLight.Success,
        onSuccess = Color.White,
        successContainer = AppColors.SemanticLight.SuccessContainer,
        onSuccessContainer = AppColors.SemanticLight.Success,
        warning = AppColors.SemanticLight.Warning,
        onWarning = Color.White,
        warningContainer = AppColors.SemanticLight.WarningContainer,
        onWarningContainer = AppColors.SemanticLight.Warning,
        info = AppColors.SemanticLight.Info,
        onInfo = Color.White,
        infoContainer = AppColors.SemanticLight.InfoContainer,
        onInfoContainer = AppColors.SemanticLight.Info
    )
}
