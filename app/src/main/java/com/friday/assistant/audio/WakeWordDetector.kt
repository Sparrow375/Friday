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
    private val sharedRecognizer: SpeechRecognizer? = null,
    private val onWakeWordDetected: (String?) -> Unit
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
    private var isListeningEnabled = false
    private var maxRmsSeen = -100f
    // Pre-allocated DP array for Levenshtein distance — avoids per-call allocation in hot path
    private val dpArray = IntArray(32)
    private var cachedVariants: Set<String> = buildVariantSet(DEFAULT_WAKEWORD)

    fun getWakeWord(): String {
        return sharedPrefs.getString(KEY_WAKEWORD, DEFAULT_WAKEWORD) ?: DEFAULT_WAKEWORD
    }

    fun setWakeWord(word: String) {
        sharedPrefs.edit().putString(KEY_WAKEWORD, word.trim().lowercase()).apply()
        cachedVariants = buildVariantSet(word.trim().lowercase())
    }

    fun isModelLoaded(): Boolean = true // Now virtual since we use the native system recognizer

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
                // Use on-device recognizer to avoid requesting foreground audio focus.
                // Standard SpeechRecognizer internally claims AUDIOFOCUS_GAIN which causes
                // media apps to pause every time the recognizer restarts (~5s cycle).
                // On-device recognizer uses a background audio path with no focus impact.
                val useOnDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                  SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                speechRecognizer = if (useOnDevice) {
                    Log.i(TAG, "Using on-device speech recognizer for wake-word (no audio focus impact)")
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    Log.i(TAG, "On-device recognizer unavailable, falling back to standard recognizer")
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create wake-word speech recognizer", e)
                mainHandler.postDelayed({ if (isListeningEnabled) startRecognizerInternal() }, 500)
                return
            }
        }

        try {
            speechRecognizer?.setRecognitionListener(buildWakeWordListener())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set wake-word recognition listener", e)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            // Prefer offline/on-device recognition to avoid network calls and audio focus conflicts
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
            mainHandler.postDelayed({
                if (isListeningEnabled) startRecognizerInternal()
            }, if (isHardError) 500L else 250L)
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (checkMatchesForWakeWord(matches)) {
                Log.i(TAG, "Wake word detected in onResults!")
                val command = extractCommand(matches)
                triggerWakeWord(command)
            } else {
                mainHandler.postDelayed({
                    if (isListeningEnabled) startRecognizerInternal()
                }, 150)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (checkMatchesForWakeWord(matches)) {
                Log.i(TAG, "Wake word detected in onPartialResults!")
                val command = extractCommand(matches)
                triggerWakeWord(command)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun checkMatchesForWakeWord(matches: ArrayList<String>?): Boolean {
        if (matches == null) return false
        
        // Gate: Lowered to prevent ignoring soft wake-word utterances.
        if (maxRmsSeen < -20.0f) {
            Log.v(TAG, "Ignoring check: max RMS level indicates absolute silence ($maxRmsSeen)")
            return false
        }

        // Use pre-computed variant set (updated in setWakeWord, avoids per-call allocation)
        val targetVariants = cachedVariants

        for (match in matches) {
            val cleanMatch = match.lowercase().trim()
            
            // Check direct inclusion of target variants
            for (variant in targetVariants) {
                if (cleanMatch.contains(variant)) {
                    return true
                }
            }

            // Split into words and perform fuzzy levenshtein comparison
            val words = cleanMatch.split(Regex("\\s+")).filter { it.isNotEmpty() }
            for (word in words) {
                for (variant in targetVariants) {
                    // Match if edit distance is small enough
                    val maxDistance = if (variant.length <= 4) 1 else 2
                    if (levenshteinDistance(word, variant) <= maxDistance) {
                        Log.i(TAG, "Fuzzy match success: '$word' matched variant '$variant' (max RMS: $maxRmsSeen)")
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
        val variants = mutableSetOf(word, "friday", "frida", "freeday", "friyay")
        if (word != "friday") {
            variants.add(word.replace(" ", ""))
        }
        return variants
    }

    private fun extractCommand(matches: ArrayList<String>?): String? {
        if (matches == null) return null
        val targetVariants = cachedVariants
        
        for (match in matches) {
            val cleanMatch = match.trim()
            val lowerMatch = cleanMatch.lowercase()
            
            for (variant in targetVariants) {
                val idx = lowerMatch.indexOf(variant)
                if (idx != -1) {
                    val commandStart = idx + variant.length
                    if (commandStart < cleanMatch.length) {
                        val command = cleanMatch.substring(commandStart).trim()
                        val cleanedCommand = command.replace(Regex("^[^a-zA-Z0-9]+"), "").trim()
                        if (cleanedCommand.isNotEmpty()) {
                            return cleanedCommand
                        }
                    }
                }
            }
        }
        return null
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

    private fun triggerWakeWord(command: String? = null) {
        stopListening()
        onWakeWordDetected(command)
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.setRecognitionListener(null)
            speechRecognizer?.cancel()
            if (sharedRecognizer == null) {
                speechRecognizer?.destroy()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying wake-word recognizer", e)
        }
        if (sharedRecognizer == null) {
            speechRecognizer = null
        }
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
