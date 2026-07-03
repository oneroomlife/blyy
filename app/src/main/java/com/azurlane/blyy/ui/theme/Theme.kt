package com.azurlane.blyy.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val CommandCenterDarkScheme = darkColorScheme(
    primary = AppColors.PrimaryDark,
    onPrimary = AppColors.OnPrimaryDark,
    primaryContainer = AppColors.PrimaryContainerDark,
    onPrimaryContainer = AppColors.OnPrimaryContainerDark,
    secondary = AppColors.SecondaryDark,
    onSecondary = AppColors.OnSecondaryDark,
    secondaryContainer = AppColors.SecondaryContainerDark,
    onSecondaryContainer = AppColors.OnSecondaryContainerDark,
    tertiary = AppColors.TertiaryDark,
    onTertiary = AppColors.OnTertiaryDark,
    tertiaryContainer = AppColors.TertiaryContainerDark,
    onTertiaryContainer = AppColors.OnTertiaryContainerDark,
    background = AppColors.BackgroundDark,
    onBackground = AppColors.OnBackgroundDark,
    surface = AppColors.SurfaceDark,
    onSurface = AppColors.OnSurfaceDark,
    surfaceVariant = AppColors.SurfaceVariantDark,
    onSurfaceVariant = AppColors.OnSurfaceVariantDark,
    surfaceContainerLowest = AppColors.SurfaceContainerLowestDark,
    surfaceContainerLow = AppColors.SurfaceContainerLowDark,
    surfaceContainer = AppColors.SurfaceContainerDark,
    surfaceContainerHigh = AppColors.SurfaceContainerHighDark,
    surfaceContainerHighest = AppColors.SurfaceContainerHighestDark,
    error = AppColors.SemanticDark.Error,
    onError = Color(0xFF1A0000),
    errorContainer = AppColors.SemanticDark.ErrorContainer,
    onErrorContainer = AppColors.SemanticDark.Error,
    outline = AppColors.Border.MediumDark,
    outlineVariant = AppColors.Border.LightDark
)

private val CommandCenterLightScheme = lightColorScheme(
    primary = AppColors.PrimaryLight,
    onPrimary = AppColors.OnPrimaryLight,
    primaryContainer = AppColors.PrimaryContainerLight,
    onPrimaryContainer = AppColors.OnPrimaryContainerLight,
    secondary = AppColors.SecondaryLight,
    onSecondary = AppColors.OnSecondaryLight,
    secondaryContainer = AppColors.SecondaryContainerLight,
    onSecondaryContainer = AppColors.OnSecondaryContainerLight,
    tertiary = AppColors.TertiaryLight,
    onTertiary = AppColors.OnTertiaryLight,
    tertiaryContainer = AppColors.TertiaryContainerLight,
    onTertiaryContainer = AppColors.OnTertiaryContainerLight,
    background = AppColors.BackgroundLight,
    onBackground = AppColors.OnBackgroundLight,
    surface = AppColors.SurfaceLight,
    onSurface = AppColors.OnSurfaceLight,
    surfaceVariant = AppColors.SurfaceVariantLight,
    onSurfaceVariant = AppColors.OnSurfaceVariantLight,
    surfaceContainerLowest = AppColors.SurfaceContainerLightestLight,
    surfaceContainerLow = AppColors.SurfaceContainerLightLight,
    surfaceContainer = AppColors.SurfaceContainerLight,
    surfaceContainerHigh = AppColors.SurfaceContainerHighLight,
    surfaceContainerHighest = AppColors.SurfaceContainerHighestLight,
    error = AppColors.SemanticLight.Error,
    onError = Color.White,
    errorContainer = AppColors.SemanticLight.ErrorContainer,
    onErrorContainer = AppColors.SemanticLight.Error,
    outline = AppColors.Border.MediumLight,
    outlineVariant = AppColors.Border.LightLight
)

private val ClassicDarkScheme = darkColorScheme(
    primary = ClassicColors.PrimaryDark,
    onPrimary = ClassicColors.OnPrimaryDark,
    primaryContainer = ClassicColors.PrimaryContainerDark,
    onPrimaryContainer = ClassicColors.OnPrimaryContainerDark,
    secondary = ClassicColors.SecondaryDark,
    onSecondary = ClassicColors.OnSecondaryDark,
    secondaryContainer = ClassicColors.SecondaryContainerDark,
    onSecondaryContainer = ClassicColors.OnSecondaryContainerDark,
    tertiary = ClassicColors.TertiaryDark,
    onTertiary = ClassicColors.OnTertiaryDark,
    tertiaryContainer = ClassicColors.TertiaryContainerDark,
    onTertiaryContainer = ClassicColors.OnTertiaryContainerDark,
    background = ClassicColors.BackgroundDark,
    onBackground = ClassicColors.OnBackgroundDark,
    surface = ClassicColors.SurfaceDark,
    onSurface = ClassicColors.OnSurfaceDark,
    surfaceVariant = ClassicColors.SurfaceVariantDark,
    onSurfaceVariant = ClassicColors.OnSurfaceVariantDark,
    surfaceContainer = ClassicColors.SurfaceContainerDark,
    surfaceContainerHigh = ClassicColors.SurfaceContainerHighDark,
    surfaceContainerHighest = ClassicColors.SurfaceContainerHighestDark,
    error = AppColors.SemanticDark.Error,
    onError = Color(0xFF1A0000),
    errorContainer = AppColors.SemanticDark.ErrorContainer,
    onErrorContainer = AppColors.SemanticDark.Error,
    outline = AppColors.Border.MediumDark,
    outlineVariant = AppColors.Border.LightDark
)

private val ClassicLightScheme = lightColorScheme(
    primary = ClassicColors.PrimaryLight,
    onPrimary = ClassicColors.OnPrimaryLight,
    primaryContainer = ClassicColors.PrimaryContainerLight,
    onPrimaryContainer = ClassicColors.OnPrimaryContainerLight,
    secondary = ClassicColors.SecondaryLight,
    onSecondary = ClassicColors.OnSecondaryLight,
    secondaryContainer = ClassicColors.SecondaryContainerLight,
    onSecondaryContainer = ClassicColors.OnSecondaryContainerLight,
    tertiary = ClassicColors.TertiaryLight,
    onTertiary = ClassicColors.OnTertiaryLight,
    tertiaryContainer = ClassicColors.TertiaryContainerLight,
    onTertiaryContainer = ClassicColors.OnTertiaryContainerLight,
    background = ClassicColors.BackgroundLight,
    onBackground = ClassicColors.OnBackgroundLight,
    surface = ClassicColors.SurfaceLight,
    onSurface = ClassicColors.OnSurfaceLight,
    surfaceVariant = ClassicColors.SurfaceVariantLight,
    onSurfaceVariant = ClassicColors.OnSurfaceVariantLight,
    surfaceContainer = ClassicColors.SurfaceContainerLight,
    surfaceContainerHigh = ClassicColors.SurfaceContainerHighLight,
    surfaceContainerHighest = ClassicColors.SurfaceContainerHighestLight,
    error = AppColors.SemanticLight.Error,
    onError = Color.White,
    errorContainer = AppColors.SemanticLight.ErrorContainer,
    onErrorContainer = AppColors.SemanticLight.Error,
    outline = AppColors.Border.MediumLight,
    outlineVariant = AppColors.Border.LightLight
)

/** 品牌静态配色 — 双风格各自独立 */
private fun brandScheme(uiStyle: UiStyle, darkTheme: Boolean): ColorScheme = when {
    uiStyle == UiStyle.CLASSIC && darkTheme -> ClassicDarkScheme
    uiStyle == UiStyle.CLASSIC -> ClassicLightScheme
    darkTheme -> CommandCenterDarkScheme
    else -> CommandCenterLightScheme
}

/**
 * Material You 动态色 + 品牌主色锁定。
 * 动态色负责 surface/background 阶梯；primary/secondary 保留品牌识别。
 */
@Composable
private fun resolveColorScheme(
    uiStyle: UiStyle,
    darkTheme: Boolean,
    dynamicColor: Boolean
): ColorScheme {
    val brand = brandScheme(uiStyle, darkTheme)
    if (!dynamicColor || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return brand
    }
    val context = LocalContext.current
    val dynamic = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    return dynamic.copy(
        primary = brand.primary,
        onPrimary = brand.onPrimary,
        primaryContainer = brand.primaryContainer,
        onPrimaryContainer = brand.onPrimaryContainer,
        secondary = brand.secondary,
        onSecondary = brand.onSecondary,
        secondaryContainer = brand.secondaryContainer,
        onSecondaryContainer = brand.onSecondaryContainer,
        tertiary = brand.tertiary,
        onTertiary = brand.onTertiary,
        tertiaryContainer = brand.tertiaryContainer,
        onTertiaryContainer = brand.onTertiaryContainer,
        error = brand.error,
        onError = brand.onError,
        errorContainer = brand.errorContainer,
        onErrorContainer = brand.onErrorContainer
    )
}

@Composable
fun BlyyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    uiStyle: UiStyle = UiStyle.COMMAND_CENTER,
    dynamicColor: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    content: @Composable () -> Unit
) {
    val colorScheme = resolveColorScheme(uiStyle, darkTheme, dynamicColor)
    val typography = if (uiStyle.isCommandCenter()) CommandCenterTypography else ClassicTypography
    val isWatch = isWatchScreen()
    val semantics = semanticColors(darkTheme)

    CompositionLocalProvider(
        LocalUiStyle provides uiStyle,
        LocalIsDark provides darkTheme,
        LocalIsWatch provides isWatch,
        LocalSemanticColors provides semantics
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content
        )
    }
}
