package com.friday.assistant.audio

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.friday.assistant.core.ModelManager
import java.util.Locale

class WakeWordDetector(
    private val context: Context,
    private val modelManager: ModelManager, // Kept for signature compatibility
    private val onWakeWordDetected: () -> Unit
) {

    companion object {
        private const val TAG = "WakeWordDetector"
        private const val PREFS_NAME = "friday_wakeword_prefs"
        const val KEY_WAKEWORD = "custom_wakeword"
        const val DEFAULT_WAKEWORD = "friday"
    }

    private val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    @Volatile
    private var isListeningEnabled = false
    private var maxRmsSeen = -100f

    // Pre-allocated DP array for Levenshtein distance — avoids per-call allocation in hot path
    private val dpArray = IntArray(32)
    private var cachedVariants: Set<String> = buildVariantSet(getWakeWord())
    private var consecutiveSilentCycles = 0

    private fun getAdaptiveRestartDelay(): Long {
        return when {
            consecutiveSilentCycles >= 30 -> 2000L
            consecutiveSilentCycles >= 10 -> 1000L
            consecutiveSilentCycles >= 3  -> 500L
            else -> 100L
        }
    }

    fun getWakeWord(): String {
        return sharedPrefs.getString(KEY_WAKEWORD, DEFAULT_WAKEWORD) ?: DEFAULT_WAKEWORD
    }

    fun setWakeWord(word: String) {
        sharedPrefs.edit().putString(KEY_WAKEWORD, word.trim().lowercase()).apply()
        cachedVariants = buildVariantSet(word.trim().lowercase())
    }

    fun isModelLoaded(): Boolean = true

    fun startListening() {
        mainHandler.post {
            if (isListeningEnabled) return@post
            Log.i(TAG, "Starting wake-word continuous listening")
            isListeningEnabled = true
            startRecognizerInternal()
        }
    }

    fun stopListening() {
        mainHandler.post {
            if (!isListeningEnabled) return@post
            Log.i(TAG, "Stopping wake-word continuous listening")
            isListeningEnabled = false
            try {
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling wake-word recognizer", e)
                destroyRecognizer()
            }
        }
    }

    fun shutdown() {
        isListeningEnabled = false
        mainHandler.post { destroyRecognizer() }
    }

    private fun startRecognizerInternal() {
        if (!isListeningEnabled) return
        maxRmsSeen = -100f

        // Reuse existing recognizer if healthy; only create new one if null
        if (speechRecognizer == null) {
            try {
                val useOnDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                  SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                speechRecognizer = if (useOnDevice) {
                    Log.i(TAG, "Using on-device speech recognizer for wake-word (no audio focus impact)")
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    Log.i(TAG, "On-device recognizer unavailable, falling back to standard recognizer")
                    SpeechRecognizer.createSpeechRecognizer(context)
                }.apply {
                    setRecognitionListener(buildWakeWordListener())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create wake-word speech recognizer", e)
                mainHandler.postDelayed({ if (isListeningEnabled) startRecognizerInternal() }, 500)
                return
            }
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            // Prefer offline/on-device recognition to minimize battery usage and latency
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start wake-word listening", e)
            destroyRecognizer()
            mainHandler.postDelayed({ if (isListeningEnabled) startRecognizerInternal() }, 500)
        }
    }

    private fun buildWakeWordListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Wake-word recognizer ready")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Wake-word beginning of speech")
            maxRmsSeen = -100f
            consecutiveSilentCycles = 0
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (rmsdB > maxRmsSeen) maxRmsSeen = rmsdB
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "Wake-word end of speech")
        }

        override fun onError(error: Int) {
            val msg = getErrorMessage(error)
            Log.d(TAG, "Wake-word recognizer error: $msg ($error)")
            val isHardError = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                              error == SpeechRecognizer.ERROR_CLIENT ||
                              error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            if (isHardError) {
                destroyRecognizer() // Force full rebind only on hard errors
            }
            
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                consecutiveSilentCycles++
            }
            
            val delay = if (isHardError) 500L else getAdaptiveRestartDelay()
            Log.d(TAG, "Wake-word recognizer restart delay (error): ${delay}ms (silent cycles: $consecutiveSilentCycles)")
            mainHandler.postDelayed({
                if (isListeningEnabled) startRecognizerInternal()
            }, delay)
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (checkMatchesForWakeWord(matches)) {
                Log.i(TAG, "Wake word detected in onResults!")
                consecutiveSilentCycles = 0
                triggerWakeWord()
            } else {
                if (!matches.isNullOrEmpty()) {
                    consecutiveSilentCycles = 0
                } else {
                    consecutiveSilentCycles++
                }
                val delay = getAdaptiveRestartDelay()
                Log.d(TAG, "Wake-word recognizer restart delay (no-match): ${delay}ms (silent cycles: $consecutiveSilentCycles)")
                mainHandler.postDelayed({
                    if (isListeningEnabled) startRecognizerInternal()
                }, delay)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (checkMatchesForWakeWord(matches)) {
                Log.i(TAG, "Wake word detected in onPartialResults!")
                consecutiveSilentCycles = 0
                triggerWakeWord()
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun checkMatchesForWakeWord(matches: ArrayList<String>?): Boolean {
        if (matches == null || matches.isEmpty()) return false

        // Use pre-computed variant set (updated in setWakeWord, avoids per-call allocation)
        val targetVariants = cachedVariants

        for (match in matches) {
            val cleanMatch = match.lowercase().trim()
            if (cleanMatch.isEmpty()) continue

            // Check direct inclusion of target variants (e.g. "hey friday", "friday", "friday help")
            for (variant in targetVariants) {
                if (cleanMatch.contains(variant)) {
                    Log.i(TAG, "Direct match success: '$cleanMatch' contains '$variant'")
                    return true
                }
            }

            // Split into words and perform fuzzy levenshtein comparison
            val words = cleanMatch.split(Regex("\\s+")).filter { it.isNotEmpty() }
            for (word in words) {
                for (variant in targetVariants) {
                    val maxDistance = if (variant.length <= 4) 1 else 2
                    if (levenshteinDistance(word, variant) <= maxDistance) {
                        Log.i(TAG, "Fuzzy match success: '$word' matched variant '$variant'")
                        return true
                    }
                }
            }
        }
        return false
    }

    /**
     * Pre-computes the set of acceptable wake word variants for matching.
     * Called once at construction and whenever the wake word is changed.
     */
    private fun buildVariantSet(word: String): Set<String> {
        val base = word.trim().lowercase()
        val variants = mutableSetOf(base, "friday", "frida", "freeday", "friyay", "fryday", "fri day")
        if (base != "friday") {
            variants.add(base.replace(" ", ""))
        }
        return variants
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        // Early exit: length difference alone exceeds max threshold
        if (kotlin.math.abs(s1.length - s2.length) > 2) return 3

        val len2 = s2.length.coerceAtMost(dpArray.size - 1)
        for (j in 0..len2) dpArray[j] = j

        for (i in 1..s1.length) {
            var prev = i
            var rowMin = i
            for (j in 1..len2) {
                val temp = dpArray[j]
                dpArray[j] = if (s1[i - 1] == s2[j - 1]) {
                    dpArray[j - 1]
                } else {
                    1 + minOf(dpArray[j - 1], dpArray[j], prev)
                }
                if (dpArray[j] < rowMin) rowMin = dpArray[j]
                prev = temp
            }
            // Early exit: entire row exceeds threshold — no match possible
            if (rowMin > 2) return 3
        }
        return dpArray[len2]
    }

    private fun triggerWakeWord() {
        stopListening()
        mainHandler.post {
            onWakeWordDetected()
        }
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.setRecognitionListener(null)
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying wake-word recognizer", e)
        }
        speechRecognizer = null
    }

    private fun getErrorMessage(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No match found"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
            else -> "Unknown error"
        }
    }
}
