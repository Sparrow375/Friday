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

// Sleek, Premium & Simple Color System (Revamped from cyber-neon)
val NeonBlue = Color(0xFF6366F1)       // Premium Indigo
val NeonCyan = Color(0xFF0EA5E9)       // Premium Sky Blue
val ObsidianDark = Color(0xFF090A0F)   // Deep Charcoal/Obsidian background
val GlassObsidian = Color(0xF2171923)  // Dark premium card/sheet container
val SlateGray = Color(0xFF1F2232)      // Modern Slate Gray card background
val CyberBorder = Color(0x1F6366F1)    // Soft indigo outline border
val SilverText = Color(0xFF94A3B8)     // Cool muted gray subtext
val GlowGreen = Color(0xFF10B981)      // Elegant Emerald Green for success/active states
val AlertRed = Color(0xFFEF4444)       // Clean Coral Red for alerts/error states

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
