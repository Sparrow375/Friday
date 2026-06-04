package com.friday.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

class AudioVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private var amplitude = 0f
    private var targetAmplitude = 0f
    private var phase = 0f
    private val speed = 0.15f
    
    // Wave parameters: frequency, amplitude scale, stroke width, color
    private val waveConfigs = listOf(
        WaveConfig(1.0f, 1.0f, 6f, Color.parseColor("#4A00E0"), Color.parseColor("#8E2DE2"), 255), // Main purple/blue
        WaveConfig(1.5f, 0.6f, 4f, Color.parseColor("#00C9FF"), Color.parseColor("#92FE9D"), 180), // Cyan/green accent
        WaveConfig(0.8f, 0.4f, 3f, Color.parseColor("#FF416C"), Color.parseColor("#FF4B2B"), 120)  // Pink/orange accent
    )

    private class WaveConfig(
        val freqMultiplier: Float,
        val ampMultiplier: Float,
        val strokeWidth: Float,
        val startColor: Int,
        val endColor: Int,
        val alpha: Int
    )

    init {
        // Transparent background
        setBackgroundColor(Color.TRANSPARENT)
    }

    /**
     * Updates the amplitude based on audio level input (RMS dB values, typically 0 - 60).
     */
    fun setAmplitude(dbLevel: Float) {
        // Normalize dB level to a 0.0 to 1.0 range for the animation
        val normalized = ((dbLevel - 2.0f) / 40.0f).coerceIn(0.0f, 1.0f)
        targetAmplitude = normalized * (height * 0.4f)
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val centerY = height / 2f
        val w = width.toFloat()

        // Smoothly interpolate amplitude to avoid jerky movements
        amplitude += (targetAmplitude - amplitude) * 0.15f
        phase += speed

        // Draw multiple waves
        for (config in waveConfigs) {
            val path = Path()
            
            wavePaint.strokeWidth = config.strokeWidth
            wavePaint.alpha = config.alpha
            
            // Set up a sleek linear gradient for each wave
            val gradient = LinearGradient(
                0f, 0f, w, 0f,
                config.startColor, config.endColor,
                Shader.TileMode.CLAMP
            )
            wavePaint.shader = gradient

            path.moveTo(0f, centerY)
            
            // Draw points across the width
            for (x in 0..width step 4) {
                val progress = x.toFloat() / w
                
                // Pinch the wave at the start and end edges (bell curve envelope)
                val envelope = sin(progress * Math.PI).toFloat()
                
                // Calculate sine wave value
                val angle = (progress * Math.PI * 2 * config.freqMultiplier) + phase
                val y = centerY + sin(angle).toFloat() * (amplitude * config.ampMultiplier) * envelope
                
                if (x == 0) {
                    path.moveTo(x.toFloat(), y)
                } else {
                    path.lineTo(x.toFloat(), y)
                }
            }
            canvas.drawPath(path, wavePaint)
        }

        // Keep animating even when quiet (idle micro-pulse)
        if (targetAmplitude < 1.0f) {
            targetAmplitude = 5f + sin(phase * 0.5f).toFloat() * 2f
        }

        postInvalidateOnAnimation()
    }
}
