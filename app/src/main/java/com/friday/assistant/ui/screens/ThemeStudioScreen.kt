package com.friday.assistant.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.friday.assistant.ui.screens.components.FridayBrandMark
import com.friday.assistant.ui.screens.components.FridayCard
import com.friday.assistant.ui.screens.components.FridaySectionHeader
import com.friday.assistant.ui.screens.components.StatusPill
import com.friday.assistant.ui.theme.FridayThemeConfig
import com.friday.assistant.ui.theme.LocalFridayColors
import com.friday.assistant.ui.theme.SurfaceStyle
import com.friday.assistant.ui.theme.ThemePreset
import com.friday.assistant.ui.theme.ThemeStore
import kotlin.math.roundToInt

@Composable
fun ThemeStudioScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val config by ThemeStore.configFlow.collectAsState()
    val colors = LocalFridayColors.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(scrollState)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.surface)
                    .border(1.dp, colors.cardBorder, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.textPrimary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Theme Studio",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.textPrimary
                )
                Text(
                    text = "Updates both the app and voice overlay live",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Live Preview Mock Card
        FridaySectionHeader(title = "Live Preview")
        FridayCard(
            title = "Friday Voice HUD",
            subtitle = "${config.preset.displayName} • ${config.surfaceStyle.displayName}",
            trailing = { StatusPill(label = "READY", active = true) }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surfaceElevated)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FridayBrandMark(size = 42.dp, isLive = true)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "\"What's on my schedule today?\"",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Instant offline response • Zero cloud delay",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.secondary
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {},
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Primary Action", color = MaterialTheme.colorScheme.onPrimary)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.primaryMuted)
                        .border(1.dp, colors.primary.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Secondary Chip",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        FridaySectionHeader(title = "Curated Palettes")

        val presets = listOf(
            ThemePreset.OBSIDIAN_AMBER,
            ThemePreset.CRIMSON_GRAPHITE,
            ThemePreset.CHAMPAGNE_BRONZE,
            ThemePreset.MONOCHROME_TITANIUM,
            ThemePreset.WARM_PAPER
        )

        presets.forEach { preset ->
            val previewPalette = ThemeStore.resolvePalette(
                FridayThemeConfig(preset = preset, surfaceStyle = config.surfaceStyle)
            )
            val isSelected = config.preset == preset
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable {
                        ThemeStore.updateConfig(context, config.copy(preset = preset))
                    },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) colors.surfaceElevated else colors.surface
                ),
                border = BorderStroke(
                    width = if (isSelected) 1.5.dp else 1.dp,
                    color = if (isSelected) colors.primary else colors.cardBorder
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Swatch badge
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(previewPalette.bg)
                            .border(1.dp, previewPalette.cardBorder, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(previewPalette.primary)
                            )
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(previewPalette.secondary)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = preset.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary
                        )
                        Text(
                            text = preset.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                    }
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(colors.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        FridaySectionHeader(title = "Surface Depth")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SurfaceStyle.entries.forEach { style ->
                val selected = config.surfaceStyle == style
                FilterChip(
                    selected = selected,
                    onClick = { ThemeStore.updateConfig(context, config.copy(surfaceStyle = style)) },
                    label = {
                        Text(
                            text = style.displayName,
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.primaryMuted,
                        selectedLabelColor = colors.primary,
                        containerColor = colors.surface,
                        labelColor = colors.textSecondary
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selected,
                        borderColor = colors.cardBorder,
                        selectedBorderColor = colors.primary
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        FridaySectionHeader(title = "Custom Studio Accent")

        FridayCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Palette,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Custom Accent Hue (${config.customHue.roundToInt()}°)",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary
                    )
                }
                if (config.preset == ThemePreset.CUSTOM) {
                    StatusPill(label = "CUSTOM", active = true)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Hue spectrum strip
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFFE53935),
                                Color(0xFFE86F2C),
                                Color(0xFFF59E0B),
                                Color(0xFFCA8A04),
                                Color(0xFFD946EF),
                                Color(0xFFE11D48),
                                Color(0xFFE53935)
                            )
                        )
                    )
            )

            Slider(
                value = config.customHue,
                onValueChange = { hue ->
                    ThemeStore.updateConfig(
                        context,
                        config.copy(preset = ThemePreset.CUSTOM, customHue = hue)
                    )
                },
                valueRange = 0f..360f,
                colors = SliderDefaults.colors(
                    thumbColor = colors.primary,
                    activeTrackColor = colors.primary,
                    inactiveTrackColor = colors.surfaceElevated
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Warm Light Canvas",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Use editorial warm-paper background in Custom mode",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )
                }
                Switch(
                    checked = config.customLightMode,
                    onCheckedChange = { light ->
                        ThemeStore.updateConfig(
                            context,
                            config.copy(preset = ThemePreset.CUSTOM, customLightMode = light)
                        )
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = colors.primary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
    }
}
