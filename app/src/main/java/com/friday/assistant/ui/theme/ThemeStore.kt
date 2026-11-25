package com.friday.assistant.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

enum class ThemePreset(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val isLight: Boolean = false
) {
    OBSIDIAN_AMBER("obsidian_amber", "Obsidian Amber", "Warm terracotta & espresso slate", false),
    CRIMSON_GRAPHITE("crimson_graphite", "Crimson Graphite", "Deep graphite & vermilion ember", false),
    CHAMPAGNE_BRONZE("champagne_bronze", "Champagne Bronze", "Charcoal brass & burnished gold", false),
    MONOCHROME_TITANIUM("monochrome_titanium", "Monochrome Titanium", "Brushed titanium & warm silver", false),
    WARM_PAPER("warm_paper", "Warm Paper", "Editorial alabaster & burnt sienna", true),
    CUSTOM("custom", "Custom Studio", "User-tuned accent & surface depth", false)
}

enum class SurfaceStyle(val id: String, val displayName: String) {
    MATTE("matte", "Deep Matte"),
    ELEVATED("elevated", "Elevated Warm"),
    GLASS("glass", "Translucent Glass")
}

data class FridayThemeConfig(
    val preset: ThemePreset = ThemePreset.OBSIDIAN_AMBER,
    val customHue: Float = 22f, // 0..360 (22 = Terracotta Amber)
    val customLightMode: Boolean = false,
    val surfaceStyle: SurfaceStyle = SurfaceStyle.ELEVATED
)

data class FridayColorPalette(
    val bg: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val cardBorder: Color,
    val primary: Color,
    val primaryMuted: Color,
    val secondary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val successAccent: Color,
    val errorAccent: Color,
    val glassOverlay: Color,
    val isLight: Boolean
)

object ThemeStore {
    private const val PREFS_NAME = "friday_theme_prefs"
    private const val KEY_PRESET = "theme_preset"
    private const val KEY_HUE = "theme_custom_hue"
    private const val KEY_LIGHT = "theme_custom_light"
    private const val KEY_SURFACE = "theme_surface_style"

    private val _configFlow = MutableStateFlow(FridayThemeConfig())
    val configFlow: StateFlow<FridayThemeConfig> = _configFlow.asStateFlow()

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val presetId = prefs.getString(KEY_PRESET, ThemePreset.OBSIDIAN_AMBER.id)
        val preset = ThemePreset.entries.firstOrNull { it.id == presetId } ?: ThemePreset.OBSIDIAN_AMBER
        val hue = prefs.getFloat(KEY_HUE, 22f).coerceIn(0f, 360f)
        val customLight = prefs.getBoolean(KEY_LIGHT, false)
        val surfaceId = prefs.getString(KEY_SURFACE, SurfaceStyle.ELEVATED.id)
        val surfaceStyle = SurfaceStyle.entries.firstOrNull { it.id == surfaceId } ?: SurfaceStyle.ELEVATED
        _configFlow.value = FridayThemeConfig(
            preset = preset,
            customHue = hue,
            customLightMode = customLight,
            surfaceStyle = surfaceStyle
        )
        initialized = true
    }

    fun updateConfig(context: Context, config: FridayThemeConfig) {
        _configFlow.value = config
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PRESET, config.preset.id)
            .putFloat(KEY_HUE, config.customHue.coerceIn(0f, 360f))
            .putBoolean(KEY_LIGHT, config.customLightMode)
            .putString(KEY_SURFACE, config.surfaceStyle.id)
            .apply()
    }

    fun resolvePalette(config: FridayThemeConfig): FridayColorPalette {
        val base = when (config.preset) {
            ThemePreset.OBSIDIAN_AMBER -> FridayColorPalette(
                bg = Color(0xFF0C0B0A),
                surface = Color(0xFF161412),
                surfaceElevated = Color(0xFF201D1A),
                cardBorder = Color(0xFF2E2924),
                primary = Color(0xFFE86F2C),
                primaryMuted = Color(0x33E86F2C),
                secondary = Color(0xFFF59E0B),
                textPrimary = Color(0xFFF4EFEA),
                textSecondary = Color(0xFFA89F96),
                textTertiary = Color(0xFF70685F),
                successAccent = Color(0xFFF59E0B),
                errorAccent = Color(0xFFE14D43),
                glassOverlay = Color(0xEB161412),
                isLight = false
            )
            ThemePreset.CRIMSON_GRAPHITE -> FridayColorPalette(
                bg = Color(0xFF0B0A0B),
                surface = Color(0xFF151214),
                surfaceElevated = Color(0xFF211B1E),
                cardBorder = Color(0xFF33262A),
                primary = Color(0xFFE11D48),
                primaryMuted = Color(0x33E11D48),
                secondary = Color(0xFFFB7185),
                textPrimary = Color(0xFFF6F1F2),
                textSecondary = Color(0xFFB0A3A6),
                textTertiary = Color(0xFF746669),
                successAccent = Color(0xFFF59E0B),
                errorAccent = Color(0xFFEF4444),
                glassOverlay = Color(0xEB151214),
                isLight = false
            )
            ThemePreset.CHAMPAGNE_BRONZE -> FridayColorPalette(
                bg = Color(0xFF0C0B09),
                surface = Color(0xFF161410),
                surfaceElevated = Color(0xFF221E17),
                cardBorder = Color(0xFF352E22),
                primary = Color(0xFFD99B26),
                primaryMuted = Color(0x33D99B26),
                secondary = Color(0xFFF2C14E),
                textPrimary = Color(0xFFF6F2EA),
                textSecondary = Color(0xFFB3A997),
                textTertiary = Color(0xFF756D5E),
                successAccent = Color(0xFFF2C14E),
                errorAccent = Color(0xFFE14D43),
                glassOverlay = Color(0xEB161410),
                isLight = false
            )
            ThemePreset.MONOCHROME_TITANIUM -> FridayColorPalette(
                bg = Color(0xFF0A0A0B),
                surface = Color(0xFF141416),
                surfaceElevated = Color(0xFF1F1F22),
                cardBorder = Color(0xFF2E2E33),
                primary = Color(0xFFE4E4E7),
                primaryMuted = Color(0x28E4E4E7),
                secondary = Color(0xFFF59E0B),
                textPrimary = Color(0xFFFAFAFA),
                textSecondary = Color(0xFFA1A1AA),
                textTertiary = Color(0xFF6B6B73),
                successAccent = Color(0xFFF59E0B),
                errorAccent = Color(0xFFEF4444),
                glassOverlay = Color(0xEB141416),
                isLight = false
            )
            ThemePreset.WARM_PAPER -> FridayColorPalette(
                bg = Color(0xFFF6F2EC),
                surface = Color(0xFFFFFFFF),
                surfaceElevated = Color(0xFFEDE6DC),
                cardBorder = Color(0xFFDFD6C9),
                primary = Color(0xFFD95D24),
                primaryMuted = Color(0x22D95D24),
                secondary = Color(0xFFB45309),
                textPrimary = Color(0xFF1C1917),
                textSecondary = Color(0xFF625A52),
                textTertiary = Color(0xFF8C8277),
                successAccent = Color(0xFFD97706),
                errorAccent = Color(0xFFDC2626),
                glassOverlay = Color(0xF5FFFFFF),
                isLight = true
            )
            ThemePreset.CUSTOM -> {
                val hue = ((config.customHue % 360f) + 360f) % 360f
                val secondaryHue = (hue + 24f) % 360f
                if (config.customLightMode) {
                    val primary = hslToColor(hue, 0.78f, 0.45f)
                    val secondary = hslToColor(secondaryHue, 0.76f, 0.42f)
                    FridayColorPalette(
                        bg = Color(0xFFF6F2EC),
                        surface = Color(0xFFFFFFFF),
                        surfaceElevated = Color(0xFFECE6DE),
                        cardBorder = Color(0xFFDCD4C8),
                        primary = primary,
                        primaryMuted = primary.copy(alpha = 0.14f),
                        secondary = secondary,
                        textPrimary = Color(0xFF1B1917),
                        textSecondary = Color(0xFF605850),
                        textTertiary = Color(0xFF8D8378),
                        successAccent = secondary,
                        errorAccent = Color(0xFFDC2626),
                        glassOverlay = Color(0xF5FFFFFF),
                        isLight = true
                    )
                } else {
                    val primary = hslToColor(hue, 0.82f, 0.56f)
                    val secondary = hslToColor(secondaryHue, 0.88f, 0.54f)
                    FridayColorPalette(
                        bg = Color(0xFF0C0B0A),
                        surface = Color(0xFF161412),
                        surfaceElevated = Color(0xFF211E1B),
                        cardBorder = Color(0xFF302B26),
                        primary = primary,
                        primaryMuted = primary.copy(alpha = 0.20f),
                        secondary = secondary,
                        textPrimary = Color(0xFFF4EFEA),
                        textSecondary = Color(0xFFA89F96),
                        textTertiary = Color(0xFF70685F),
                        successAccent = secondary,
                        errorAccent = Color(0xFFE14D43),
                        glassOverlay = Color(0xEB161412),
                        isLight = false
                    )
                }
            }
        }

        return when (config.surfaceStyle) {
            SurfaceStyle.MATTE -> base.copy(
                surface = if (base.isLight) Color(0xFFFAF7F2) else base.bg,
                surfaceElevated = if (base.isLight) Color(0xFFF0EAE1) else base.surface,
                cardBorder = base.cardBorder.copy(alpha = 0.55f)
            )
            SurfaceStyle.ELEVATED -> base
            SurfaceStyle.GLASS -> base.copy(
                surface = base.surface.copy(alpha = 0.84f),
                surfaceElevated = base.surfaceElevated.copy(alpha = 0.90f),
                glassOverlay = base.glassOverlay.copy(alpha = 0.86f),
                cardBorder = base.primary.copy(alpha = 0.28f)
            )
        }
    }

    internal fun hslToColor(hueDeg: Float, saturation: Float, lightness: Float): Color {
        val h = ((hueDeg % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val l = lightness.coerceIn(0f, 1f)
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color(r1 + m, g1 + m, b1 + m, 1f)
    }
}
