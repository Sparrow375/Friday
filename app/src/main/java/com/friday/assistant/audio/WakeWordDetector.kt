package com.friday.assistant.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.friday.assistant.core.FridayApplication
import com.friday.assistant.core.ModelManager
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.sqrt

class WakeWordDetector(
    private val context: Context,
    private val modelManager: ModelManager,
    private val audioCaptureManager: AudioCaptureManager = FridayApplication.audioCaptureManager,
    private val onWakeWordDetected: () -> Unit
) : AudioCaptureManager.AudioFrameListener {

    companion object {
        private const val TAG = "WakeWordDetector"
        private const val PREFS_NAME = "friday_wakeword_prefs"
        const val KEY_WAKEWORD = "custom_wakeword"
        const val DEFAULT_WAKEWORD = "friday"

        private const val SAMPLE_RATE = 16000
        private const val BUFFER_SIZE = 24000 // 1.5 seconds at 16kHz
        private const val DEFAULT_CONFIDENCE_THRESHOLD = 0.70f
        private const val VAD_ENERGY_THRESHOLD = 65f // RMS gate to prevent running ONNX on silence
        private const val TRIGGER_COOLDOWN_MS = 1200L
    }

    private val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var isModelLoaded = false

    @Volatile
    private var isListeningEnabled = false

    // Rolling circular audio buffer for the 1.5-second classifier window
    private val rollingPcmBuffer = ShortArray(BUFFER_SIZE)
    private val floatAudioBuffer = FloatArray(BUFFER_SIZE)
    private var rollingWritePos = 0
    private var samplesCollectedTotal = 0

    private var lastTriggerTimeMs = 0L
    private var consecutiveSilenceFrames = 0

    init {
        loadModel()
    }

    @Synchronized
    private fun loadModel() {
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val modelPath = modelManager.getWakeWordModelPath()
            val modelFile = File(modelPath)
            if (modelFile.exists()) {
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(1) // Single-threaded inference is fastest & most energy-efficient for tiny 1D CNN
                }
                ortSession = ortEnv?.createSession(modelPath, opts)
                isModelLoaded = true
                Log.i(TAG, "ONNX Wake-Word Model loaded successfully from $modelPath")
            } else {
                Log.e(TAG, "Wake-Word ONNX model does not exist at: $modelPath")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load ONNX Wake-Word model", e)
            isModelLoaded = false
        }
    }

    fun isModelLoaded(): Boolean = isModelLoaded

    fun getWakeWord(): String {
        return sharedPrefs.getString(KEY_WAKEWORD, DEFAULT_WAKEWORD) ?: DEFAULT_WAKEWORD
    }

    fun setWakeWord(word: String) {
        sharedPrefs.edit().putString(KEY_WAKEWORD, word.trim().lowercase()).apply()
    }

    fun startListening() {
        if (isListeningEnabled) return
        Log.i(TAG, "Starting low-power ONNX wake-word listening")
        isListeningEnabled = true
        samplesCollectedTotal = 0
        rollingWritePos = 0
        consecutiveSilenceFrames = 0

        audioCaptureManager.registerListener(this)
        audioCaptureManager.startCapture()
    }

    fun stopListening() {
        if (!isListeningEnabled) return
        Log.i(TAG, "Stopping low-power ONNX wake-word listening")
        isListeningEnabled = false
        audioCaptureManager.unregisterListener(this)
        audioCaptureManager.stopCapture()
    }

    fun shutdown() {
        stopListening()
        try {
            ortSession?.close()
            ortSession = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ONNX wake-word session", e)
        }
    }

    override fun onAudioFrame(pcmData: ShortArray, length: Int) {
        if (!isListeningEnabled || !isModelLoaded || length <= 0) return

        // 1. Stage 0: Micro-VAD Energy Calculation (0.01ms CPU, pure math)
        val frameRms = calculateRMS(pcmData, length)
        
        // Push incoming samples into rolling circular buffer
        for (i in 0 until length) {
            rollingPcmBuffer[rollingWritePos] = pcmData[i]
            rollingWritePos = (rollingWritePos + 1) % BUFFER_SIZE
        }
        samplesCollectedTotal += length

        // Wait until at least 1.0s (16,000 samples) of audio is present in the buffer
        if (samplesCollectedTotal < 16000) return

        // Gate: If the current 100ms frame is silence, skip heavy tensor allocation & inference
        if (frameRms < VAD_ENERGY_THRESHOLD) {
            consecutiveSilenceFrames++
            return
        }
        consecutiveSilenceFrames = 0

        // Cooldown check
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastTriggerTimeMs < TRIGGER_COOLDOWN_MS) return

        // 2. Stage 1: Fast 1D-CNN ONNX Keyword Spotting
        try {
            val session = ortSession ?: return
            val env = ortEnv ?: return

            // Unroll circular buffer in chronological order into normalized FloatArray (-1.0 to 1.0)
            var readIdx = rollingWritePos
            for (i in 0 until BUFFER_SIZE) {
                floatAudioBuffer[i] = rollingPcmBuffer[readIdx].toFloat() / 32768.0f
                readIdx = (readIdx + 1) % BUFFER_SIZE
            }

            // Input tensor shape: [1, 1, 24000]
            val shape = longArrayOf(1, 1, BUFFER_SIZE.toLong())
            val buffer = FloatBuffer.wrap(floatAudioBuffer)
            val inputTensor = OnnxTensor.createTensor(env, buffer, shape)

            val inputName = session.inputNames.iterator().next()
            val results = session.run(mapOf(inputName to inputTensor))

            val outputValue = results[0].value
            val logits = when {
                outputValue is Array<*> && outputValue[0] is FloatArray -> outputValue[0] as FloatArray
                outputValue is FloatArray -> outputValue
                else -> null
            }

            results.close()
            inputTensor.close()

            if (logits != null && logits.size >= 2) {
                // Softmax probability for index 1 (positive "friday" class)
                val logit0 = logits[0].toDouble()
                val logit1 = logits[1].toDouble()
                val maxLogit = maxOf(logit0, logit1)
                val exp0 = exp(logit0 - maxLogit)
                val exp1 = exp(logit1 - maxLogit)
                val probPositive = (exp1 / (exp0 + exp1)).toFloat()

                if (probPositive >= DEFAULT_CONFIDENCE_THRESHOLD) {
                    Log.i(TAG, "Wake-word 'Friday' matched with confidence $probPositive (RMS: $frameRms)!")
                    lastTriggerTimeMs = nowMs
                    triggerWakeWord()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error running wake-word ONNX inference", e)
        }
    }

    private fun triggerWakeWord() {
        stopListening()
        mainHandler.post {
            onWakeWordDetected()
        }
    }

    private fun calculateRMS(pcmData: ShortArray, length: Int): Float {
        if (length == 0) return 0f
        var sum = 0.0
        for (i in 0 until length) {
            val sample = pcmData[i].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / length).toFloat()
    }
}
