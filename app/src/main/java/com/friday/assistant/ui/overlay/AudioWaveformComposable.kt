package com.friday.assistant.ui.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import com.friday.assistant.ui.theme.NeonBlue
import com.friday.assistant.ui.theme.NeonCyan
import kotlin.math.sin

@Composable
fun AudioWaveformComposable(
    modifier: Modifier = Modifier,
    amplitude: Float = 0f // Normalized from 0f (silent) to 1f (max volume)
) {
    // Phase offset for animating the waves when silent or speaking
    val phaseState = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        phaseState.animateTo(
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        )
    }

    val phase = phaseState.value
    val baseColor = NeonBlue
    val altColor = NeonCyan

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        
        // Base amplitude: even when silent, show a tiny breathing line (looks alive)
        val targetAmplitude = (amplitude * (height / 2.5f)).coerceAtLeast(4f)

        // Draw 3 layered sine waves with different speeds, frequencies, and transparencies
        val waveParams = listOf(
            WaveParam(frequency = 0.015f, phaseShift = phase, alpha = 0.9f, strokeWidth = 5f, color = baseColor),
            WaveParam(frequency = 0.025f, phaseShift = -phase * 1.3f, alpha = 0.6f, strokeWidth = 3f, color = altColor),
            WaveParam(frequency = 0.008f, phaseShift = phase * 0.7f, alpha = 0.3f, strokeWidth = 2f, color = baseColor)
        )

        for (param in waveParams) {
            val path = Path()
            path.moveTo(0f, centerY)

            for (x in 0..width.toInt() step 4) {
                // Apply a gaussian-like envelope so waves taper out beautifully at the edges
                val envelope = sin((x / width) * Math.PI).toFloat()
                val y = centerY + targetAmplitude * envelope * sin(x * param.frequency + param.phaseShift)
                path.lineTo(x.toFloat(), y)
            }
            
            drawPath(
                path = path,
                color = param.color.copy(alpha = param.alpha),
                style = Stroke(width = param.strokeWidth)
            )
        }
    }
}

private data class WaveParam(
    val frequency: Float,
    val phaseShift: Float,
    val alpha: Float,
    val strokeWidth: Float,
    val color: androidx.compose.ui.graphics.Color
)
