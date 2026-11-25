package com.friday.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val LocalFridayColors = staticCompositionLocalOf {
    ThemeStore.resolvePalette(FridayThemeConfig())
}

@Composable
fun FridayTheme(
    overrideConfig: FridayThemeConfig? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    remember(context) {
        ThemeStore.init(context)
        true
    }

    val storedConfig by ThemeStore.configFlow.collectAsState()
    val effectiveConfig = overrideConfig ?: storedConfig
    val palette = remember(effectiveConfig) { ThemeStore.resolvePalette(effectiveConfig) }

    val colorScheme = if (palette.isLight) {
        lightColorScheme(
            primary = palette.primary,
            secondary = palette.secondary,
            background = palette.bg,
            surface = palette.surface,
            surfaceVariant = palette.surfaceElevated,
            onPrimary = Color.White,
            onSecondary = Color.White,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.cardBorder,
            error = palette.errorAccent
        )
    } else {
        darkColorScheme(
            primary = palette.primary,
            secondary = palette.secondary,
            background = palette.bg,
            surface = palette.surface,
            surfaceVariant = palette.surfaceElevated,
            onPrimary = if (effectiveConfig.preset == ThemePreset.MONOCHROME_TITANIUM) Color.Black else Color.White,
            onSecondary = Color.Black,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.cardBorder,
            error = palette.errorAccent
        )
    }

    val typography = remember(palette) {
        Typography(
            displayLarge = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
                lineHeight = 36.sp,
                letterSpacing = (-0.6).sp,
                color = palette.textPrimary
            ),
            headlineMedium = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                letterSpacing = (-0.3).sp,
                color = palette.textPrimary
            ),
            titleMedium = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                letterSpacing = 0.sp,
                color = palette.textPrimary
            ),
            bodyLarge = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                letterSpacing = 0.1.sp,
                color = palette.textPrimary
            ),
            bodyMedium = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 13.5.sp,
                lineHeight = 19.sp,
                color = palette.textSecondary
            ),
            labelMedium = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.4.sp,
                color = palette.primary
            ),
            labelSmall = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                letterSpacing = 0.6.sp,
                color = palette.textTertiary
            )
        )
    }

    CompositionLocalProvider(LocalFridayColors provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content
        )
    }
}
