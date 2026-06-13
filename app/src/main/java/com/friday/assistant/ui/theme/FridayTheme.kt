package com.friday.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Cybernetic / Sci-Fi Harmonies Color System
val NeonBlue = Color(0xFF00F2FE)
val NeonCyan = Color(0xFF4FACFE)
val ObsidianDark = Color(0xFF0A0B0E)
val GlassObsidian = Color(0xD90E1118) // Translucent base for glassmorphism
val SlateGray = Color(0xFF1B2230)
val CyberBorder = Color(0x3300F2FE) // Semi-transparent neon border
val SilverText = Color(0xFFC5C6C7)
val GlowGreen = Color(0xFF39FF14)
val AlertRed = Color(0xFFFF073A)

private val DarkColorScheme = darkColorScheme(
    primary = NeonBlue,
    secondary = NeonCyan,
    background = ObsidianDark,
    surface = GlassObsidian,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = SilverText,
    error = AlertRed
)

// Clean and modern typography system
private val CustomTypography = androidx.compose.material3.Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
        color = Color.White
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        color = Color.White
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
        color = Color.White
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
        color = SilverText
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
        color = NeonBlue
    )
)

@Composable
fun FridayTheme(
    darkTheme: Boolean = true, // Force dark theme for JARVIS/cyber aesthetic
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = CustomTypography,
        content = content
    )
}
