package com.friday.assistant.audio

import android.content.Context
import android.util.Log
import com.friday.assistant.core.native.WhisperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

class WakeWordDetector(
    private val context: Context,
    private val whisperEngine: WhisperEngine,
    private val onWakeWordDetected: () -> Unit
) : AudioCaptureManager.AudioFrameListener {

    companion object {
        private const val TAG = "WakeWordDetector"
        private const val PREFS_NAME = "friday_wakeword_prefs"
        const val KEY_WAKEWORD = "custom_wakeword"
        const val DEFAULT_WAKEWORD = "friday"
        
        private const val MIN_SPEECH_DURATION_MS = 300L
        private const val MAX_SPEECH_DURATION_MS = 2500L
        private const val SILENCE_TIMEOUT_MS = 500L
    }

    private val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.Default)

    // VAD Variables
    private var backgroundRms = 100f
    private val rmsAlpha = 0.98f // Running average smoothing factor
    private var isSpeaking = false
    private var speechStartTimestamp = 0L
    private var lastSpeechTimestamp = 0L

    // Audio buffer to store captured speech
    private val speechBuffer = mutableListOf<Short>()
    // Ring buffer to capture the pre-speech buffer (approx 400ms)
    private val preSpeechRingBuffer = ShortArray(16000 * 4 / 10) // 400ms of 16kHz
    private var ringBufferIndex = 0

    fun getWakeWord(): String {
        return sharedPrefs.getString(KEY_WAKEWORD, DEFAULT_WAKEWORD) ?: DEFAULT_WAKEWORD
    }

    fun setWakeWord(word: String) {
        sharedPrefs.edit().putString(KEY_WAKEWORD, word.trim().lowercase(Locale.ROOT)).apply()
    }

    override fun onAudioFrame(pcmData: ShortArray) {
        val rms = calculateRMS(pcmData)
        
        // Update background noise level (if it's quiet, track it)
        if (rms < backgroundRms * 1.5f) {
            backgroundRms = backgroundRms * rmsAlpha + rms * (1f - rmsAlpha)
        }

        // Voice Activity Detection Threshold
        val vadThreshold = backgroundRms + 150f // Noise-adaptive threshold offset
        val now = System.currentTimeMillis()

        if (rms > vadThreshold) {
            // Speech detected
            if (!isSpeaking) {
                isSpeaking = true
                speechStartTimestamp = now
                speechBuffer.clear()
                // Append pre-speech buffer for context
                appendPreSpeechBuffer()
                Log.d(TAG, "Speech start detected (RMS: $rms, Background: $backgroundRms)")
            }
            lastSpeechTimestamp = now
        }

        if (isSpeaking) {
            // Append current frame to speech buffer
            pcmData.forEach { speechBuffer.add(it) }

            val speechDuration = now - speechStartTimestamp
            val silenceDuration = now - lastSpeechTimestamp

            // Stop speech accumulation on silence or max duration limit
            if (silenceDuration > SILENCE_TIMEOUT_MS || speechDuration > MAX_SPEECH_DURATION_MS) {
                isSpeaking = false
                val finalSpeechDuration = lastSpeechTimestamp - speechStartTimestamp
                
                if (finalSpeechDuration > MIN_SPEECH_DURATION_MS && speechBuffer.isNotEmpty()) {
                    val capturedAudio = speechBuffer.toShortArray()
                    scope.launch {
                        processCapturedSpeech(capturedAudio)
                    }
                }
                speechBuffer.clear()
            }
        } else {
            // Save current frame into ring buffer for pre-speech capture
            writeToRingBuffer(pcmData)
        }
    }

    private fun calculateRMS(pcmData: ShortArray): Float {
        var sum = 0.0
        for (sample in pcmData) {
            sum += sample.toDouble() * sample.toDouble()
        }
        return sqrt(sum / pcmData.size).toFloat()
    }

    private fun writeToRingBuffer(pcmData: ShortArray) {
        for (sample in pcmData) {
            preSpeechRingBuffer[ringBufferIndex] = sample
            ringBufferIndex = (ringBufferIndex + 1) % preSpeechRingBuffer.size
        }
    }

    private fun appendPreSpeechBuffer() {
        // Read out the ring buffer chronologically
        val size = preSpeechRingBuffer.size
        for (i in 0 until size) {
            val idx = (ringBufferIndex + i) % size
            speechBuffer.add(preSpeechRingBuffer[idx])
        }
    }

    private suspend fun processCapturedSpeech(pcmData: ShortArray) {
        if (!whisperEngine.isModelLoaded()) return

        // Convert ShortArray to normalized FloatArray
        val floatData = FloatArray(pcmData.size)
        for (i in pcmData.indices) {
            floatData[i] = pcmData[i].toFloat() / 32768.0f
        }

        // Transcribe speech segment
        val transcript = whisperEngine.transcribe(floatData).trim().lowercase(Locale.ROOT)
        if (transcript.isNotEmpty()) {
            val target = getWakeWord()
            Log.d(TAG, "VAD Segment Transcript: '$transcript' (Target: '$target')")
            
            // Check if wake word is mentioned
            if (transcript.contains(target) || isPhoneticMatch(transcript, target)) {
                Log.i(TAG, "Wake word matched! Triggering notification.")
                onWakeWordDetected()
            }
        }
    }

    /**
     * Simple phonetic check for speech variations (e.g. "friday", "fri day", "frida")
     */
    private fun isPhoneticMatch(text: String, target: String): Boolean {
        if (target == "friday") {
            return text.contains("fri day") || text.contains("frida") || text.contains("fly day") || text.contains("pry day")
        }
        return false
    }
}
