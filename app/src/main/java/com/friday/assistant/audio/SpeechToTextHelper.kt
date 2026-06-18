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
import java.util.Locale

class SpeechToTextHelper(
    private val context: Context,
    private val onTranscriptUpdate: (String) -> Unit,
    private val onFinalResult: (String) -> Unit,
    private val onRmsUpdate: (Float) -> Unit,
    private val onStateChanged: (PipelineState) -> Unit,
    private val profile: RecognitionProfile = RecognitionProfile.COMMAND,
    private val onEarlyCommit: ((String) -> Unit)? = null
) {
    enum class RecognitionProfile {
        COMMAND,
        CONVERSATION
    }

    companion object {
        private const val TAG = "SpeechToTextHelper"
        private const val EARLY_COMMIT_STABLE_MS = 400L

        private val FAST_ROUTE_PATTERN = Regex(
            "(?i)(volume|brightness|flashlight|torch|wifi|bluetooth|hotspot|lock|screenshot|screen shot|" +
                "pause|call |dial |mute|unmute|play |next track|previous track|turn it (on|off|up|down))"
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var lastPartialText = ""
    private var lastPartialChangeMs = 0L
    private var earlyCommitFired = false
    private var earlyCommitRunnable: Runnable? = null

    private fun destroyRecognizer() {
        isListening = false
        cancelEarlyCommitTimer()
        try {
            speechRecognizer?.setRecognitionListener(null)
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to destroy speech recognizer", e)
        }
        speechRecognizer = null
    }

    private fun cancelEarlyCommitTimer() {
        earlyCommitRunnable?.let { mainHandler.removeCallbacks(it) }
        earlyCommitRunnable = null
    }

    private fun scheduleEarlyCommitCheck(text: String) {
        if (profile != RecognitionProfile.COMMAND || onEarlyCommit == null) return
        if (earlyCommitFired || text.isBlank() || !FAST_ROUTE_PATTERN.containsMatchIn(text)) return

        if (text != lastPartialText) {
            lastPartialText = text
            lastPartialChangeMs = System.currentTimeMillis()
        }

        cancelEarlyCommitTimer()
        earlyCommitRunnable = Runnable {
            if (earlyCommitFired || !isListening) return@Runnable
            if (lastPartialText == text &&
                System.currentTimeMillis() - lastPartialChangeMs >= EARLY_COMMIT_STABLE_MS
            ) {
                earlyCommitFired = true
                isListening = false
                Log.i(TAG, "Early commit on stable partial: '$text'")
                try {
                    speechRecognizer?.cancel()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to cancel recognizer after early commit", e)
                }
                onFinalResult(text)
            }
        }
        mainHandler.postDelayed(earlyCommitRunnable!!, EARLY_COMMIT_STABLE_MS)
    }

    fun startListening() {
        mainHandler.post {
            if (isListening) return@post
            earlyCommitFired = false
            lastPartialText = ""
            lastPartialChangeMs = 0L
            Log.i(TAG, "Starting native speech recognizer")

            try {
                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                        setRecognitionListener(buildRecognitionListener())
                    }
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    if (profile == RecognitionProfile.COMMAND) {
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 450L)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 350L)
                    } else {
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
                    }
                }

                speechRecognizer?.startListening(intent)
                isListening = true
                onStateChanged(PipelineState.LISTENING)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start speech recognition", e)
                destroyRecognizer()
                onStateChanged(PipelineState.IDLE)
            }
        }
    }

    private fun buildRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Speech recognizer ready")
            isListening = true
            onStateChanged(PipelineState.LISTENING)
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Beginning of speech")
        }

        override fun onRmsChanged(rmsdB: Float) {
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            onRmsUpdate(normalized)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "End of speech detected")
            if (!earlyCommitFired) {
                onStateChanged(PipelineState.PROCESSING)
            }
        }

        override fun onError(error: Int) {
            if (earlyCommitFired) return
            val message = getErrorMessage(error)
            Log.e(TAG, "Speech recognition error: $message ($error)")
            mainHandler.post {
                isListening = false
                cancelEarlyCommitTimer()
                val isHardError = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                    error == SpeechRecognizer.ERROR_CLIENT ||
                    error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                if (isHardError) {
                    destroyRecognizer()
                } else {
                    isListening = false
                }
                onStateChanged(PipelineState.IDLE)
                onTranscriptUpdate("")
            }
        }

        override fun onResults(results: Bundle?) {
            if (earlyCommitFired) return
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val finalResultText = matches?.firstOrNull() ?: ""
            mainHandler.post {
                cancelEarlyCommitTimer()
                isListening = false
                if (finalResultText.isNotBlank()) {
                    Log.i(TAG, "Speech recognition final result: '$finalResultText'")
                    onFinalResult(finalResultText)
                } else {
                    onStateChanged(PipelineState.IDLE)
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (earlyCommitFired) return
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val text = matches[0]
                Log.d(TAG, "Speech recognition partial result: '$text'")
                onTranscriptUpdate(text)
                scheduleEarlyCommitCheck(text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun stopListening() {
        mainHandler.post {
            if (!isListening) return@post
            cancelEarlyCommitTimer()
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop speech recognition", e)
            }
        }
    }

    fun destroy() {
        mainHandler.post {
            destroyRecognizer()
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
