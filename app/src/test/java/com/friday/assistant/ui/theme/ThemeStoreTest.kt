package com.friday.assistant.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeStoreTest {

    @Test
    fun `obsidian amber preset is dark and has distinct terracotta accent`() {
        val palette = ThemeStore.resolvePalette(FridayThemeConfig(preset = ThemePreset.OBSIDIAN_AMBER))
        assertFalse(palette.isLight)
        assertNotEquals(palette.bg, palette.surface)
        assertNotEquals(palette.primary, palette.secondary)
    }

    @Test
    fun `warm paper preset resolves to light editorial palette`() {
        val palette = ThemeStore.resolvePalette(FridayThemeConfig(preset = ThemePreset.WARM_PAPER))
        assertTrue(palette.isLight)
    }

    @Test
    fun `custom hue updates primary color deterministically`() {
        val warmAmber = ThemeStore.resolvePalette(
            FridayThemeConfig(preset = ThemePreset.CUSTOM, customHue = 22f, customLightMode = false)
        )
        val rubyRed = ThemeStore.resolvePalette(
            FridayThemeConfig(preset = ThemePreset.CUSTOM, customHue = 350f, customLightMode = false)
        )
        assertNotEquals(warmAmber.primary, rubyRed.primary)
    }
}
