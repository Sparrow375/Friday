package com.friday.assistant.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.Locale

class SpeechRecognizerManager(
    private val context: Context,
    private val callback: RecognitionCallback
) {

    interface RecognitionCallback {
        fun onWakeWordDetected()
        fun onCommandRecognized(text: String, rawAudio: ShortArray)
        fun onSpeechVolumeChanged(rmsdB: Float)
        fun onError(errorMsg: String)
        fun onStateChanged(state: State)
    }

    enum class State {
        IDLE,
        LISTENING_FOR_WAKE_WORD,
        LISTENING_FOR_COMMAND
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceRecorder: VoiceRecorder? = null
    private var currentState = State.IDLE
    
    // Accumulator for raw PCM audio during command capture
    private val audioBufferStream = ByteArrayOutputStream()
    private val shortAudioBuffer = mutableListOf<Short>()

    private val recorderCallback = object : VoiceRecorder.AudioCallback {
        override fun onAudioBuffer(buffer: ShortArray) {
            if (currentState == State.LISTENING_FOR_COMMAND) {
                synchronized(shortAudioBuffer) {
                    shortAudioBuffer.addAll(buffer.toList())
                }
            }
            // Compute RMS volume for the visualizer
            var sum = 0.0
            for (sample in buffer) {
                sum += sample * sample
            }
            val rms = Math.sqrt(sum / buffer.size)
            // Scale to approximate dB scale (0 to 100)
            val volume = (20 * Math.log10(rms.coerceAtLeast(1.0))).toFloat()
            callback.onSpeechVolumeChanged(volume)
        }

        override fun onError(message: String) {
            Log.e(TAG, "VoiceRecorder error: $message")
        }
    }

    init {
        initializeRecognizer()
        voiceRecorder = VoiceRecorder(recorderCallback)
    }

    private fun initializeRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(speechListener)
            }
        } else {
            callback.onError("Speech recognition not available on this device")
        }
    }

    fun startWakeWordListening() {
        currentState = State.LISTENING_FOR_WAKE_WORD
        callback.onStateChanged(currentState)
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting wake word listening", e)
            // Re-initialize and retry if crash
            initializeRecognizer()
            speechRecognizer?.startListening(intent)
        }
    }

    fun startActiveCommandListening() {
        currentState = State.LISTENING_FOR_COMMAND
        callback.onStateChanged(currentState)
        
        synchronized(shortAudioBuffer) {
            shortAudioBuffer.clear()
        }
        
        // Start recording raw audio for speaker verification
        voiceRecorder?.startRecording()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting command listening", e)
            voiceRecorder?.stopRecording()
            startWakeWordListening()
        }
    }

    fun stop() {
        currentState = State.IDLE
        callback.onStateChanged(currentState)
        speechRecognizer?.stopListening()
        voiceRecorder?.stopRecording()
    }

    fun destroy() {
        stop()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private val speechListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "onReadyForSpeech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "onBeginningOfSpeech")
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (currentState == State.LISTENING_FOR_WAKE_WORD) {
                callback.onSpeechVolumeChanged(rmsdB)
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            // Unreliable on many Android devices, which is why we use VoiceRecorder
        }

        override fun onEndOfSpeech() {
            Log.d(TAG, "onEndOfSpeech")
            if (currentState == State.LISTENING_FOR_COMMAND) {
                voiceRecorder?.stopRecording()
            }
        }

        override fun onError(error: Int) {
            val message = getErrorString(error)
            Log.e(TAG, "SpeechRecognizer error: $message")
            
            // Stop recorder if listening for command
            if (currentState == State.LISTENING_FOR_COMMAND) {
                voiceRecorder?.stopRecording()
            }

            callback.onError(message)
            
            // Restart wake word loop if we were in wake word state or command timed out
            if (currentState != State.IDLE) {
                startWakeWordListening()
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            Log.i(TAG, "Speech results: $text")

            if (currentState == State.LISTENING_FOR_WAKE_WORD) {
                if (text.contains("friday", ignoreCase = true)) {
                    callback.onWakeWordDetected()
                } else {
                    // Loop back to listening
                    startWakeWordListening()
                }
            } else if (currentState == State.LISTENING_FOR_COMMAND) {
                val audioData = synchronized(shortAudioBuffer) {
                    shortAudioBuffer.toShortArray()
                }
                callback.onCommandRecognized(text, audioData)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partialText = matches?.firstOrNull() ?: ""
            
            if (currentState == State.LISTENING_FOR_WAKE_WORD) {
                if (partialText.contains("friday", ignoreCase = true)) {
                    // Cancel current listening session to transition
                    speechRecognizer?.cancel()
                    callback.onWakeWordDetected()
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun getErrorString(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client-side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech match found"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input detected"
            else -> "Unknown speech recognizer error ($errorCode)"
        }
    }

    companion object {
        private const val TAG = "SpeechRecognizerManager"
    }
}
