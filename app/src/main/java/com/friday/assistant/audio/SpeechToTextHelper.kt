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
    private val onRmsChanged: (Float) -> Unit,
    private val onStateChanged: (PipelineState) -> Unit
) {
    companion object {
        private const val TAG = "SpeechToTextHelper"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    fun startListening() {
        mainHandler.post {
            if (isListening) return@post
            Log.i(TAG, "Starting native speech recognizer")

            try {
                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                        setRecognitionListener(object : RecognitionListener {
                            override fun onReadyForSpeech(params: Bundle?) {
                                Log.d(TAG, "Speech recognizer ready")
                                isListening = true
                                onStateChanged(PipelineState.LISTENING)
                            }

                            override fun onBeginningOfSpeech() {
                                Log.d(TAG, "Beginning of speech")
                            }

                            override fun onRmsChanged(rmsdB: Float) {
                                // Normalize standard rmsdB values (approx -2dB to 10dB) to a 0.0 - 1.0 range
                                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                                onRmsChanged(normalized)
                            }

                            override fun onBufferReceived(buffer: ByteArray?) {}

                            override fun onEndOfSpeech() {
                                Log.d(TAG, "End of speech detected")
                                onStateChanged(PipelineState.PROCESSING)
                            }

                            override fun onError(error: Int) {
                                val message = getErrorMessage(error)
                                Log.e(TAG, "Speech recognition error: $message")
                                stopListening()
                                onStateChanged(PipelineState.IDLE)
                                onTranscriptUpdate("")
                            }

                            override fun onResults(results: Bundle?) {
                                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                if (!matches.isNullOrEmpty()) {
                                    val text = matches[0]
                                    Log.i(TAG, "Speech recognition final result: '$text'")
                                    onFinalResult(text)
                                } else {
                                    onStateChanged(PipelineState.IDLE)
                                }
                                isListening = false
                            }

                            override fun onPartialResults(partialResults: Bundle?) {
                                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                if (!matches.isNullOrEmpty()) {
                                    val text = matches[0]
                                    Log.d(TAG, "Speech recognition partial result: '$text'")
                                    onTranscriptUpdate(text)
                                }
                            }

                            override fun onEvent(eventType: Int, params: Bundle?) {}
                        })
                    }
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    // Ensure the recognizer stays open long enough
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                }

                speechRecognizer?.startListening(intent)
                isListening = true
                onStateChanged(PipelineState.LISTENING)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start speech recognition", e)
                onStateChanged(PipelineState.IDLE)
                isListening = false
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            if (!isListening) return@post
            isListening = false
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop speech recognition", e)
            }
        }
    }

    fun destroy() {
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to destroy speech recognizer", e)
            }
            speechRecognizer = null
            isListening = false
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
