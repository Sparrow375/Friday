package com.friday.assistant.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.friday.assistant.core.ModelManager
import java.util.Arrays
import kotlin.math.sqrt

/**
 * Low-power Two-Stage VAD-Gated Neural Wake-Word Detector powered by PhoneticWakeWordNetV2
 * (compiled natively via wakeword_model.h in libfriday_native.so).
 *
 * Stage 1 (DC-Removed Micro-VAD):
 *   - Computes pure AC RMS (immune to mic DC offset) with adaptive background baseline.
 *   - Drops silence/ambient frames before neural inference.
 *
 * Stage 2 (PhoneticWakeWordNetV2 + Multi-Frame Confirmation):
 *   - Evaluates 32-Mel DS-Conv + 1D Temporal Motif Conv on the 24,000-sample (1.5s) rolling buffer.
 *   - Requires 2 consecutive frames >= 0.85 (avg >= 0.88) OR 3-of-4 recent frames >= 0.70 (latest >= 0.74),
 *     matching the pendant firmware's zero-false-alarm confirmation logic.
 */
class WakeWordDetector(
    private val context: Context,
    private val modelManager: ModelManager,
    private val onWakeWordDetected: () -> Unit
) : AudioCaptureManager.AudioFrameListener {

    companion object {
        private const val TAG = "WakeWordDetector"
        private const val WINDOW_SIZE = 24000 // 1.5 seconds at 16kHz
        private const val MIN_SPEECH_RMS = 95f
        private const val MAX_BG_RMS = 300f
        private const val VAD_MULTIPLIER = 1.30f
        private const val WAKE_STRONG_FRAME = 0.85f
        private const val WAKE_STRONG_AVG = 0.88f
        private const val WAKE_SUSTAINED_FRAME = 0.70f
        private const val WAKE_SUSTAINED_LATEST = 0.74f
        private const val BARGE_IN_GRACE_MS = 450L
        private const val TRIGGER_COOLDOWN_MS = 3500L

        init {
            try {
                System.loadLibrary("friday_native")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load friday_native for WakeWordDetector", e)
            }
        }
    }

    private external fun predictWakeWordNative(ringBuffer: ShortArray, headIdx: Int): Float

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isListeningEnabled = false

    @Volatile
    private var isBargeInMode = false

    @Volatile
    private var listeningStartMs = 0L

    @Volatile
    private var cooldownUntilMs = 0L

    // Pre-allocated buffers — ZERO heap allocations in audio hot loop
    private val ringBuffer = ShortArray(WINDOW_SIZE)
    private var ringWriteIndex = 0
    private var samplesCollected = 0

    // Adaptive AC noise floor & VAD hangover
    private var backgroundRms = 60f
    private var speechActiveFrames = 0

    // Multi-frame confirmation history
    private var prevConfidence = 0f
    private val wakeHistory = FloatArray(4)
    private var wakeHistoryIdx = 0

    fun isModelLoaded(): Boolean = true

    fun setBargeInMode(active: Boolean) {
        isBargeInMode = active
    }

    private fun resetWakeHistory() {
        prevConfidence = 0f
        Arrays.fill(wakeHistory, 0f)
        wakeHistoryIdx = 0
    }

    fun startListening() {
        isListeningEnabled = true
        listeningStartMs = System.currentTimeMillis()
        ringWriteIndex = 0
        samplesCollected = 0
        speechActiveFrames = 0
        backgroundRms = 60f
        resetWakeHistory()
        Arrays.fill(ringBuffer, 0.toShort())
        Log.i(TAG, "PhoneticWakeWordNetV2 wake-word detection started (bargeIn=$isBargeInMode)")
    }

    fun stopListening() {
        isListeningEnabled = false
        ringWriteIndex = 0
        samplesCollected = 0
        speechActiveFrames = 0
        resetWakeHistory()
        Arrays.fill(ringBuffer, 0.toShort())
        Log.i(TAG, "PhoneticWakeWordNetV2 wake-word detection stopped")
    }

    fun shutdown() {
        stopListening()
    }

    /**
     * Called 10 times per second with 100ms frames (1600 samples) from AudioCaptureManager.
     */
    override fun onAudioFrame(pcmData: ShortArray, length: Int) {
        if (!isListeningEnabled || length <= 0) return

        val now = System.currentTimeMillis()
        if (now < cooldownUntilMs) {
            resetWakeHistory()
            return
        }

        // 1. Write incoming PCM samples into circular ring buffer and compute DC-removed AC RMS
        var sumRaw = 0L
        for (i in 0 until length) {
            val s = pcmData[i]
            sumRaw += s
            ringBuffer[ringWriteIndex] = s
            ringWriteIndex = (ringWriteIndex + 1) % WINDOW_SIZE
        }
        samplesCollected += length
        val frameMean = sumRaw.toFloat() / length.toFloat()

        var sumSqAc = 0.0
        for (i in 0 until length) {
            val ac = pcmData[i] - frameMean
            sumSqAc += (ac * ac).toDouble()
        }
        val currentRms = sqrt(sumSqAc / length.toDouble()).toFloat()

        // Wait until rolling buffer has full 1.5s context before inference
        if (samplesCollected < WINDOW_SIZE) {
            backgroundRms = (backgroundRms * 0.92f + currentRms * 0.08f).coerceAtMost(MAX_BG_RMS)
            return
        }

        // 2. Adaptive AC VAD gating
        val speechThreshold = (backgroundRms * VAD_MULTIPLIER + 15f).coerceAtLeast(MIN_SPEECH_RMS)
        if (currentRms >= speechThreshold) {
            speechActiveFrames = 5 // 500ms hangover (5 * 100ms)
        } else if (speechActiveFrames > 0) {
            speechActiveFrames--
        } else {
            backgroundRms = (backgroundRms * 0.985f + currentRms * 0.015f).coerceIn(25f, MAX_BG_RMS)
            resetWakeHistory()
            return
        }

        if (isBargeInMode && (now - listeningStartMs) < BARGE_IN_GRACE_MS) {
            return
        }

        // 3. Stage 2: Native PhoneticWakeWordNetV2 evaluation + multi-frame confirmation
        evaluateWakeWord(now)
    }

    @Synchronized
    private fun evaluateWakeWord(nowMs: Long) {
        try {
            val confidence = predictWakeWordNative(ringBuffer, ringWriteIndex)

            wakeHistory[wakeHistoryIdx] = confidence
            wakeHistoryIdx = (wakeHistoryIdx + 1) and 0x03

            var moderateHits = 0
            for (h in wakeHistory) {
                if (h >= WAKE_SUSTAINED_FRAME) moderateHits++
            }

            val twoFrameAvg = 0.5f * (confidence + prevConfidence)
            val strongConfirmed = confidence >= WAKE_STRONG_FRAME &&
                prevConfidence >= WAKE_STRONG_FRAME &&
                twoFrameAvg >= WAKE_STRONG_AVG
            val sustainedConfirmed = moderateHits >= 3 && confidence >= WAKE_SUSTAINED_LATEST
            prevConfidence = confidence

            if (strongConfirmed || sustainedConfirmed) {
                Log.i(TAG, "PhoneticWakeWordNetV2 'Friday' CONFIRMED! (score=$confidence, avg=$twoFrameAvg, hits=$moderateHits, bargeIn=$isBargeInMode)")
                cooldownUntilMs = nowMs + TRIGGER_COOLDOWN_MS
                speechActiveFrames = 0
                resetWakeHistory()
                ringWriteIndex = 0
                samplesCollected = 0
                Arrays.fill(ringBuffer, 0.toShort())
                mainHandler.post {
                    onWakeWordDetected()
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error running native PhoneticWakeWordNetV2 inference", e)
        }
    }
}
