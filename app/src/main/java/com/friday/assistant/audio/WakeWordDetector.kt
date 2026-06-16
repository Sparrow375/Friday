package com.friday.assistant.audio

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.friday.assistant.core.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.sqrt

class WakeWordDetector(
    private val context: Context,
    private val modelManager: ModelManager,
    private val onWakeWordDetected: () -> Unit
) : AudioCaptureManager.AudioFrameListener {

    companion object {
        private const val TAG = "WakeWordDetector"
        private const val PREFS_NAME = "friday_wakeword_prefs"
        const val KEY_WAKEWORD = "custom_wakeword"
        const val DEFAULT_WAKEWORD = "friday"
        
        private const val MIN_SPEECH_DURATION_MS = 200L
        private const val MAX_SPEECH_DURATION_MS = 2500L
        private const val SILENCE_TIMEOUT_MS = 500L
    }

    private val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.Default)

    // ONNX Runtime variables
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

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

    init {
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val modelPath = modelManager.getWakeWordModelPath()
            if (File(modelPath).exists()) {
                ortSession = ortEnv?.createSession(modelPath, OrtSession.SessionOptions())
                Log.i(TAG, "ONNX Wake-Word model loaded successfully from: $modelPath")
            } else {
                Log.e(TAG, "Wake-word model does not exist at: $modelPath")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize ONNX Runtime or load wake-word model", e)
        }
    }

    fun isModelLoaded(): Boolean = ortSession != null

    fun getWakeWord(): String {
        return sharedPrefs.getString(KEY_WAKEWORD, DEFAULT_WAKEWORD) ?: DEFAULT_WAKEWORD
    }

    fun setWakeWord(word: String) {
        sharedPrefs.edit().putString(KEY_WAKEWORD, word.trim().lowercase()).apply()
    }

    override fun onAudioFrame(pcmData: ShortArray) {
        val rms = calculateRMS(pcmData)
        
        // Update background noise level (if it's quiet, track it)
        if (rms < backgroundRms * 1.5f) {
            backgroundRms = backgroundRms * rmsAlpha + rms * (1f - rmsAlpha)
        }

        // Voice Activity Detection Threshold
        val vadThreshold = backgroundRms + 80f // Noise-adaptive threshold offset
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
        val session = ortSession ?: return
        val env = ortEnv ?: return

        try {
            val floatAudio = preprocessAudioForOnnx(pcmData)
            
            // Input shape: [1, 1, 24000]
            val shape = longArrayOf(1, 1, floatAudio.size.toLong())
            val buffer = FloatBuffer.wrap(floatAudio)
            val inputTensor = OnnxTensor.createTensor(env, buffer, shape)
            
            val inputName = session.inputNames.iterator().next()
            val results = session.run(mapOf(inputName to inputTensor))
            
            val outputValue = results[0].value
            val probabilities = when {
                outputValue is Array<*> && outputValue[0] is FloatArray -> outputValue[0] as FloatArray
                outputValue is FloatArray -> outputValue
                else -> null
            }
            
            results.close()
            inputTensor.close()
            
            if (probabilities != null && probabilities.size >= 2) {
                // Apply Softmax to get confidence for class 1 (positive wake word)
                val expNeg = Math.exp(probabilities[0].toDouble())
                val expPos = Math.exp(probabilities[1].toDouble())
                val confidence = (expPos / (expNeg + expPos)).toFloat()
                
                Log.d(TAG, "ONNX Wake Word logits: [${probabilities[0]}, ${probabilities[1]}], confidence: $confidence")
                
                // Trigger wake word if confidence >= 0.85 (highly confident prediction)
                if (confidence >= 0.85f) {
                    Log.i(TAG, "Custom ONNX wake word detected! (Confidence: $confidence)")
                    onWakeWordDetected()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error running wake-word ONNX session", e)
        }
    }

    private fun preprocessAudioForOnnx(pcmData: ShortArray): FloatArray {
        val targetLen = 24000
        val floatData = FloatArray(targetLen)
        
        if (pcmData.size > targetLen) {
            // Center crop
            val start = (pcmData.size - targetLen) / 2
            for (i in 0 until targetLen) {
                floatData[i] = pcmData[start + i].toFloat() / 32768.0f
            }
        } else {
            // Center pad
            val padLen = targetLen - pcmData.size
            val left = padLen / 2
            val startIdx = left
            for (i in pcmData.indices) {
                floatData[startIdx + i] = pcmData[i].toFloat() / 32768.0f
            }
        }
        return floatData
    }
}
