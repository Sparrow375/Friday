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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.friday.assistant.ui.theme.LocalFridayColors
import kotlin.math.sin

@Composable
fun AudioWaveformComposable(
    modifier: Modifier = Modifier,
    amplitude: Float = 0f
) {
    val palette = LocalFridayColors.current
    val phaseState = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        phaseState.animateTo(
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(1100, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        )
    }

    val phase = phaseState.value
    val primaryColor = palette.primary
    val secondaryColor = palette.secondary

    // Pre-allocate Path objects to eliminate per-frame GC allocation on the UI thread
    val paths = remember { List(3) { Path() } }
    val freqs = remember { floatArrayOf(0.016f, 0.026f, 0.009f) }
    val alphas = remember { floatArrayOf(0.92f, 0.62f, 0.34f) }
    val widths = remember { floatArrayOf(5f, 3f, 2f) }

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas
        val centerY = height / 2f
        val targetAmplitude = (amplitude * (height / 2.4f)).coerceAtLeast(4f)
        val phaseShifts = floatArrayOf(phase, -phase * 1.3f, phase * 0.7f)
        val colors = arrayOf(primaryColor, secondaryColor, primaryColor)

        for (idx in 0 until 3) {
            val path = paths[idx]
            path.reset()
            path.moveTo(0f, centerY)

            val freq = freqs[idx]
            val shift = phaseShifts[idx]
            var x = 0
            val maxW = width.toInt()
            while (x <= maxW) {
                val envelope = sin((x / width) * Math.PI).toFloat()
                val y = centerY + targetAmplitude * envelope * sin(x * freq + shift)
                path.lineTo(x.toFloat(), y)
                x += 4
            }

            drawPath(
                path = path,
                color = colors[idx].copy(alpha = alphas[idx]),
                style = Stroke(width = widths[idx], cap = StrokeCap.Round)
            )
        }
    }
}
