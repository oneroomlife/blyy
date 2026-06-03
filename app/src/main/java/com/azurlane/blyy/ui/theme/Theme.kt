package com.azurlane.blyy.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
    surfaceContainer = AppColors.SurfaceContainerDark,
    surfaceContainerHigh = AppColors.SurfaceContainerHighDark,
    surfaceContainerHighest = AppColors.SurfaceContainerHighestDark
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
    surfaceContainer = AppColors.SurfaceContainerLight,
    surfaceContainerHigh = AppColors.SurfaceContainerHighLight,
    surfaceContainerHighest = AppColors.SurfaceContainerHighestLight
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
    surfaceContainerHighest = ClassicColors.SurfaceContainerHighestDark
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
    surfaceContainerHighest = ClassicColors.SurfaceContainerHighestLight
)

@Composable
fun BlyyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    uiStyle: UiStyle = UiStyle.COMMAND_CENTER,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        uiStyle == UiStyle.CLASSIC && darkTheme -> ClassicDarkScheme
        uiStyle == UiStyle.CLASSIC -> ClassicLightScheme
        darkTheme -> CommandCenterDarkScheme
        else -> CommandCenterLightScheme
    }

    val typography = if (uiStyle.isCommandCenter()) CommandCenterTypography else ClassicTypography

    CompositionLocalProvider(LocalUiStyle provides uiStyle) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content
        )
    }
}
