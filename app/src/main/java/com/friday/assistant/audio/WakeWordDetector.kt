package com.friday.assistant.audio

import android.content.Context
import android.content.Intent
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
    private var isListeningEnabled = false

    fun getWakeWord(): String {
        return sharedPrefs.getString(KEY_WAKEWORD, DEFAULT_WAKEWORD) ?: DEFAULT_WAKEWORD
    }

    fun setWakeWord(word: String) {
        sharedPrefs.edit().putString(KEY_WAKEWORD, word.trim().lowercase()).apply()
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
            destroyRecognizer()
        }
    }

    private fun startRecognizerInternal() {
        if (!isListeningEnabled) return
        destroyRecognizer()

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "Wake-word recognizer ready")
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d(TAG, "Wake-word beginning of speech")
                    }

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.d(TAG, "Wake-word end of speech")
                    }

                    override fun onError(error: Int) {
                        val msg = getErrorMessage(error)
                        Log.d(TAG, "Wake-word recognizer error: $msg ($error)")
                        
                        // Restart listening after a short delay if enabled
                        mainHandler.postDelayed({
                            if (isListeningEnabled) {
                                startRecognizerInternal()
                            }
                        }, 250)
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (checkMatchesForWakeWord(matches)) {
                            Log.i(TAG, "Wake word detected in onResults!")
                            triggerWakeWord()
                        } else {
                            // Restart listening
                            mainHandler.postDelayed({
                                if (isListeningEnabled) {
                                    startRecognizerInternal()
                                }
                            }, 150)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (checkMatchesForWakeWord(matches)) {
                            Log.i(TAG, "Wake word detected in onPartialResults!")
                            triggerWakeWord()
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            }

            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start wake-word speech recognizer", e)
            mainHandler.postDelayed({
                if (isListeningEnabled) {
                    startRecognizerInternal()
                }
            }, 500)
        }
    }

    private fun checkMatchesForWakeWord(matches: ArrayList<String>?): Boolean {
        if (matches == null) return false
        val targetWakeWord = getWakeWord().lowercase().trim()
        
        // Phonetic variants
        val variants = mutableSetOf(targetWakeWord, "friday", "fri day", "frida", "freeday", "free day")
        if (targetWakeWord != "friday") {
            variants.add(targetWakeWord.replace(" ", ""))
        }

        for (match in matches) {
            val cleanMatch = match.lowercase().trim()
            for (variant in variants) {
                if (cleanMatch.contains(variant)) {
                    return true
                }
            }
        }
        return false
    }

    private fun triggerWakeWord() {
        stopListening()
        onWakeWordDetected()
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
