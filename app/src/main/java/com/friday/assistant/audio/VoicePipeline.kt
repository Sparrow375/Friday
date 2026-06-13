package com.friday.assistant.audio

import android.content.Context
import android.util.Log
import com.friday.assistant.core.FridayApplication
import com.friday.assistant.core.native.WhisperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.sqrt

enum class PipelineState {
    IDLE,        // Monitoring for wake-word
    LISTENING,   // Actively recording user command
    PROCESSING,  // Transcribing speech
    THINKING,    // Running LLM and executing tools
    SPEAKING     // Playing voice response via TTS
}

class VoicePipeline(
    private val context: Context,
    private val audioCaptureManager: AudioCaptureManager,
    private val whisperEngine: WhisperEngine,
    private val speakerVerifier: SpeakerVerifier,
    private val onCommandReady: (String) -> Unit
) : AudioCaptureManager.AudioFrameListener {

    companion object {
        private const val TAG = "VoicePipeline"
        private const val SILENCE_THRESHOLD_RMS = 180f
        private const val ENDPOINT_SILENCE_TIMEOUT_MS = 1200L
        private const val MAX_COMMAND_DURATION_MS = 8000L
    }

    private val _state = MutableStateFlow(PipelineState.IDLE)
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default)
    private var wakeWordDetector: WakeWordDetector? = null

    // Buffer to store the active command audio
    private val commandAudioBuffer = mutableListOf<Short>()
    
    // Silence endpointing variables
    private var silenceStartTimestamp = 0L
    private var commandStartTimestamp = 0L

    init {
        wakeWordDetector = WakeWordDetector(context, whisperEngine) {
            onWakeWordMatched()
        }
    }

    fun startPipeline() {
        Log.i(TAG, "Starting Voice Pipeline")
        audioCaptureManager.registerListener(this)
        audioCaptureManager.registerListener(wakeWordDetector!!)
        audioCaptureManager.startCapture()
        _state.value = PipelineState.IDLE
    }

    fun stopPipeline() {
        Log.i(TAG, "Stopping Voice Pipeline")
        audioCaptureManager.stopCapture()
        audioCaptureManager.unregisterListener(this)
        audioCaptureManager.unregisterListener(wakeWordDetector!!)
        _state.value = PipelineState.IDLE
    }

    fun forceTrigger() {
        if (_state.value == PipelineState.IDLE) {
            Log.i(TAG, "Manual trigger activated")
            scope.launch {
                triggerWake()
            }
        }
    }

    fun setSpeakingState(isSpeaking: Boolean) {
        if (isSpeaking) {
            _state.value = PipelineState.SPEAKING
            // Mute mic/suspend pipeline listener to prevent self-feedback
        } else {
            _state.value = PipelineState.IDLE
        }
    }

    private fun onWakeWordMatched() {
        if (_state.value == PipelineState.IDLE) {
            scope.launch {
                triggerWake()
            }
        }
    }

    private suspend fun triggerWake() {
        Log.i(TAG, "Transitioning from IDLE to LISTENING")
        _state.value = PipelineState.LISTENING
        commandAudioBuffer.clear()
        commandStartTimestamp = System.currentTimeMillis()
        silenceStartTimestamp = 0L
        
        // Play wake chime or sound (implemented in Service or UI layer later)
    }

    override fun onAudioFrame(pcmData: ShortArray) {
        val currentState = _state.value
        
        if (currentState == PipelineState.LISTENING) {
            // Store audio samples
            pcmData.forEach { commandAudioBuffer.add(it) }

            val now = System.currentTimeMillis()
            val rms = calculateRMS(pcmData)

            // Dynamic silence detection
            if (rms < SILENCE_THRESHOLD_RMS) {
                if (silenceStartTimestamp == 0L) {
                    silenceStartTimestamp = now
                }
            } else {
                silenceStartTimestamp = 0L // Reset silence timer if they speak
            }

            val commandDuration = now - commandStartTimestamp
            val silenceDuration = if (silenceStartTimestamp > 0L) now - silenceStartTimestamp else 0L

            // Stop recording when silence threshold is hit or max duration exceeded
            if (silenceDuration > ENDPOINT_SILENCE_TIMEOUT_MS || commandDuration > MAX_COMMAND_DURATION_MS) {
                Log.d(TAG, "Command recording finished. Silence duration: $silenceDuration, Total duration: $commandDuration")
                scope.launch {
                    processSpeechCommand()
                }
            }
        }
    }

    private suspend fun processSpeechCommand() {
        _state.value = PipelineState.PROCESSING
        val audioData = commandAudioBuffer.toShortArray()
        commandAudioBuffer.clear()

        // 1. Run speaker verification in parallel
        val isVerified = speakerVerifier.verify(audioData)
        if (!isVerified) {
            Log.w(TAG, "Speaker verification rejected this query. Aborting command execution.")
            _state.value = PipelineState.IDLE
            return
        }

        // 2. Transcribe using Whisper
        val floatData = FloatArray(audioData.size)
        for (i in audioData.indices) {
            floatData[i] = audioData[i].toFloat() / 32768.0f
        }

        val transcript = whisperEngine.transcribe(floatData).trim()
        Log.i(TAG, "Voice Command Transcript: '$transcript'")

        if (transcript.isNotEmpty()) {
            _state.value = PipelineState.THINKING
            onCommandReady(transcript)
        } else {
            _state.value = PipelineState.IDLE
        }
    }

    private fun calculateRMS(pcmData: ShortArray): Float {
        var sum = 0.0
        for (sample in pcmData) {
            sum += sample.toDouble() * sample.toDouble()
        }
        return sqrt(sum / pcmData.size).toFloat()
    }
}
