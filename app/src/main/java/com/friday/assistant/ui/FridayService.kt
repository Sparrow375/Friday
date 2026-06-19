package com.friday.assistant.ui

import android.service.voice.VoiceInteractionService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.friday.assistant.audio.PipelineState
import com.friday.assistant.audio.SpeechToTextHelper
import com.friday.assistant.core.FridayApplication
import com.friday.assistant.core.ModelManager
import com.friday.assistant.intelligence.AgentCore
import com.friday.assistant.tools.ToolRegistrar
import com.friday.assistant.ui.overlay.OverlayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class FridayService : VoiceInteractionService(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "FridayService"
        const val ACTION_RELOAD_MODELS = "com.friday.assistant.ACTION_RELOAD_MODELS"
        const val ACTION_SHOW_OVERLAY = "com.friday.assistant.ACTION_SHOW_OVERLAY"
        const val ACTION_PAUSE_WAKEWORD = "com.friday.assistant.ACTION_PAUSE_WAKEWORD"
        const val ACTION_RESUME_WAKEWORD = "com.friday.assistant.ACTION_RESUME_WAKEWORD"

        @Volatile
        var instance: FridayService? = null
            private set

        fun reloadModels() {
            instance?.let { service ->
                service.serviceScope.launch(Dispatchers.IO) {
                    service.setupNativeModels()
                }
            }
        }

        fun showOverlay() {
            instance?.showOverlay()
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main)

    // Core engine dependencies
    private lateinit var modelManager: ModelManager
    // agentCore and memoryManager are application-level singletons (FridayApplication.agentCore /
    // FridayApplication.memoryManager) — accessed via property delegation to avoid duplicating
    // the heavy NLU ONNX session on every service restart.
    private val agentCore: AgentCore get() = FridayApplication.agentCore
    private lateinit var speechToTextHelper: SpeechToTextHelper
    private var wakeWordDetector: com.friday.assistant.audio.WakeWordDetector? = null
    private var sharedSpeechRecognizer: android.speech.SpeechRecognizer? = null
    
    private var overlayManager: OverlayManager? = null
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false

    // Audio focus management
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    val pipelineState = MutableStateFlow(PipelineState.IDLE)

    override fun onCreate() {
        super.onCreate()
        com.friday.assistant.core.FridayLogger.i(TAG, "FridayService onCreate")
        instance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // 1. Initialize core logic components
        modelManager = ModelManager(this)
        // memoryManager and agentCore are shared singletons held by FridayApplication.
        // Touching FridayApplication.agentCore here ensures the lazy is initialised on the
        // main thread (safe — no heavy work happens inside the lazy block itself).
        FridayApplication.agentCore  // warm up singleton

        // 2. Register Agentic Tools
        ToolRegistrar.registerAll(this, FridayApplication.memoryManager)

        // 3. Setup TTS
        tts = TextToSpeech(this, this)

        // 4. Setup Speech to Text Helper
        val sharedRecognizer = getSharedSpeechRecognizer()
        speechToTextHelper = SpeechToTextHelper(
            context = this,
            onTranscriptUpdate = { text ->
                overlayManager?.updateState(pipelineState.value, "Listening...", trans = text)
            },
            onFinalResult = { finalResult ->
                serviceScope.launch {
                    executeAgentQuery(finalResult)
                }
            },
            onRmsUpdate = { amplitude ->
                if (pipelineState.value == PipelineState.LISTENING) {
                    overlayManager?.updateAmplitude(amplitude)
                }
            },
            onStateChanged = { state ->
                transitionToState(state)
            },
            sharedRecognizer = sharedRecognizer
        )

        // 5. Initialize UI Overlay Manager
        overlayManager = OverlayManager(
            context = this,
            onMicClick = { toggleListening() },
            onClose = {
                com.friday.assistant.core.FridayLogger.i(TAG, "Overlay close clicked - dismissing overlay and stopping speech/listening")
                try {
                    tts?.stop()
                } catch (e: Exception) {
                    com.friday.assistant.core.FridayLogger.e(TAG, "Error stopping TTS on close", e)
                }
                overlayManager?.dismiss()
                transitionToState(PipelineState.IDLE)
            }
        )

        // 6. Observe agent updates to keep overlay text in sync
        agentCore.agentStatusFlow.onEach { statusText ->
            overlayManager?.updateState(pipelineState.value, statusText)
        }.launchIn(serviceScope)

        // 7. Setup Wake Word Detector
        wakeWordDetector = com.friday.assistant.audio.WakeWordDetector(
            context = this,
            modelManager = modelManager,
            sharedRecognizer = sharedRecognizer
        ) { command ->
            com.friday.assistant.core.FridayLogger.i(TAG, "Wake word 'friday' detected! Command: $command")
            serviceScope.launch {
                onWakeWordTriggered(command)
            }
        }
    }

    private fun getSharedSpeechRecognizer(): android.speech.SpeechRecognizer {
        var recognizer = sharedSpeechRecognizer
        if (recognizer == null) {
            val useOnDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                              android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
            recognizer = if (useOnDevice) {
                com.friday.assistant.core.FridayLogger.i(TAG, "Using on-device speech recognizer")
                android.speech.SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                com.friday.assistant.core.FridayLogger.i(TAG, "Using standard speech recognizer")
                android.speech.SpeechRecognizer.createSpeechRecognizer(this)
            }
            sharedSpeechRecognizer = recognizer
        }
        return recognizer
    }

    override fun onReady() {
        super.onReady()
        com.friday.assistant.core.FridayLogger.i(TAG, "VoiceInteractionService is ready")
        instance = this

        serviceScope.launch(Dispatchers.IO) {
            setupNativeModels()
        }
        startWakeWordListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        com.friday.assistant.core.FridayLogger.i(TAG, "onStartCommand received action: $action")
        
        if (action == ACTION_RELOAD_MODELS) {
            serviceScope.launch(Dispatchers.IO) {
                setupNativeModels()
            }
        } else if (action == ACTION_SHOW_OVERLAY) {
            showOverlay()
        } else if (action == ACTION_PAUSE_WAKEWORD) {
            stopWakeWordListening()
        } else if (action == ACTION_RESUME_WAKEWORD) {
            startWakeWordListening()
        }

        return START_STICKY
    }

    private suspend fun setupNativeModels() {
        val whisperPath = modelManager.getWhisperModelPath()
        if (File(whisperPath).exists()) {
            val success = FridayApplication.whisperEngine.loadModel(whisperPath)
            com.friday.assistant.core.FridayLogger.i(TAG, "Whisper model loaded status: $success")
        } else {
            com.friday.assistant.core.FridayLogger.w(TAG, "Whisper model file missing at: $whisperPath")
        }
        // GGUF model is loaded lazily inside AgentCore to prevent memory starvation at startup
    }

    private fun transitionToState(
        newState: PipelineState,
        statusMessage: String? = null,
        responseText: String = "",
        transcriptText: String = ""
    ) {
        val oldState = pipelineState.value
        if (oldState != newState) {
            com.friday.assistant.core.FridayLogger.d(TAG, "Transitioning state from $oldState to $newState")
            pipelineState.value = newState
            val status = statusMessage ?: getStatusText(newState)
            overlayManager?.updateState(newState, status, trans = transcriptText, resp = responseText)
            
            if (newState == PipelineState.IDLE) {
                abandonAudioFocus()
                overlayManager?.updateAmplitude(0f)
                speechToTextHelper.destroy()
                
                // Auto-dismiss overlay — reduced delays since dismiss() now calls hide() (instant re-show)
                val dismissDelay = if (oldState == PipelineState.SPEAKING) 400L else 300L
                serviceScope.launch {
                    kotlinx.coroutines.delay(dismissDelay)
                    if (pipelineState.value == PipelineState.IDLE) {
                        com.friday.assistant.core.FridayLogger.i(TAG, "Auto-dismissing overlay after task completion")
                        overlayManager?.dismiss()
                    }
                }

                // Restart wake word immediately — no extra delay needed
                startWakeWordListening()
            } else {
                stopWakeWordListening()
            }
        }
    }

    private fun startWakeWordListening() {
        val enabled = getSharedPreferences("friday_assistant_prefs", Context.MODE_PRIVATE).getBoolean("assistant_enabled", true)
        if (!enabled) {
            com.friday.assistant.core.FridayLogger.d(TAG, "Assistant is disabled by preference; not starting background listening")
            return
        }

        val hasMicPerm = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasMicPerm) {
            com.friday.assistant.core.FridayLogger.w(TAG, "No mic permission - cannot start background wake-word listening")
            return
        }

        if (pipelineState.value == PipelineState.IDLE) {
            com.friday.assistant.core.FridayLogger.d(TAG, "Starting background wake-word listening")
            // Note: do NOT request audio focus here — SpeechRecognizer manages its own audio session.
            // Requesting focus during passive listening causes media apps (YouTube, Spotify) to pause.
            wakeWordDetector?.startListening()
        }
    }

    private fun stopWakeWordListening() {
        com.friday.assistant.core.FridayLogger.d(TAG, "Stopping background wake-word listening")
        wakeWordDetector?.stopListening()
        // Note: no audio focus to abandon — we never requested it for wake-word listening
    }

    private suspend fun onWakeWordTriggered(command: String? = null) {
        overlayManager?.show()
        val cleanCommand = command?.trim()?.lowercase() ?: ""
        val wakeWordVariants = setOf("friday", "frida", "freeday", "friyay")
        val isWakeWordOnly = cleanCommand.isEmpty() || wakeWordVariants.contains(cleanCommand)
        
        if (!isWakeWordOnly && cleanCommand.length >= 3) {
            com.friday.assistant.core.FridayLogger.d(TAG, "Executing same-breath command directly: $command")
            transitionToState(PipelineState.THINKING, statusMessage = "Thinking...", transcriptText = command)
            executeAgentQuery(command)
        } else {
            transitionToState(PipelineState.LISTENING)
            com.friday.assistant.core.FridayLogger.d(TAG, "STT start triggered immediately (no mic handover delay)")
            speechToTextHelper.startListening()
        }
    }

    private fun toggleListening() {
        if (pipelineState.value == PipelineState.LISTENING) {
            transitionToState(PipelineState.IDLE)
        } else {
            // Cancel TTS if speaking before listening
            if (pipelineState.value == PipelineState.SPEAKING) {
                tts?.stop()
            }
            serviceScope.launch {
                transitionToState(PipelineState.LISTENING)
                speechToTextHelper.startListening()
            }
        }
    }

    private suspend fun executeAgentQuery(query: String) {
        var responseAccumulator = ""
        var ttsBuffer = ""
        var isFirstChunk = true
        var lastUiUpdateMs = 0L

        val queryResult = agentCore.processQuery(query) { token ->
            serviceScope.launch {
                if (pipelineState.value != PipelineState.THINKING && pipelineState.value != PipelineState.SPEAKING) {
                    transitionToState(PipelineState.THINKING, statusMessage = "Thinking...", transcriptText = query)
                }
                responseAccumulator += token
                ttsBuffer += token

                val nowMs = System.currentTimeMillis()
                if (nowMs - lastUiUpdateMs >= 50L) {
                    overlayManager?.updateState(
                        state = pipelineState.value,
                        text = if (isFirstChunk) "Thinking..." else "Speaking...",
                        resp = responseAccumulator,
                        trans = query
                    )
                    lastUiUpdateMs = nowMs
                }

                val boundaryIndex = findPunctuationBoundary(ttsBuffer)
                if (boundaryIndex != -1) {
                    val chunk = ttsBuffer.substring(0, boundaryIndex + 1).trim()
                    ttsBuffer = ttsBuffer.substring(boundaryIndex + 1)
                    if (chunk.isNotEmpty()) {
                        speakStreamChunk(chunk, isFirstChunk, responseAccumulator)
                        isFirstChunk = false
                    }
                }
            }
        }

        // Final unconditional UI flush — ensures the complete response is always shown
        // even if the last tokens were gated out by the 50ms debounce
        if (responseAccumulator.isNotEmpty()) {
            overlayManager?.updateState(
                state = pipelineState.value,
                text = "Speaking...",
                resp = responseAccumulator,
                trans = query
            )
        }

        val response = queryResult.message

        if (queryResult.isFastTool) {
            val prefs = getSharedPreferences("friday_assistant_prefs", Context.MODE_PRIVATE)
            val confirmTools = prefs.getBoolean("voice_confirm_tools", true)
            if (confirmTools && response.isNotBlank()) {
                // Speak the brief confirmation then auto-dismiss
                speakResponse(response)
            } else {
                // No TTS — show result on overlay briefly then dismiss immediately
                overlayManager?.updateState(PipelineState.IDLE, response, trans = query, resp = response)
                transitionToState(PipelineState.IDLE, responseText = response, transcriptText = query)
            }
            return
        }

        if (pipelineState.value != PipelineState.THINKING && pipelineState.value != PipelineState.SPEAKING) {
            transitionToState(PipelineState.THINKING, statusMessage = "Thinking...", transcriptText = query)
        }

        val remainingText = ttsBuffer.trim()
        if (remainingText.isNotEmpty()) {
            speakStreamChunk(remainingText, isFirstChunk, responseAccumulator)
            isFirstChunk = false
        }

        if (responseAccumulator.isEmpty() && response.isNotEmpty()) {
            speakResponse(response)
        } else if (responseAccumulator.isNotEmpty() && isFirstChunk) {
            speakStreamChunk(responseAccumulator, true, responseAccumulator)
        }
    }

    private fun findPunctuationBoundary(text: String): Int {
        val boundaries = charArrayOf('.', '?', '!', '\n', ',', ';', ':')
        for (i in text.indices) {
            val c = text[i]
            if (boundaries.contains(c)) {
                return i
            }
        }
        return -1
    }

    private fun speakStreamChunk(chunk: String, isFirst: Boolean, fullResponseText: String) {
        if (!isTtsInitialized || tts == null) return
        
        val cleanedChunk = chunk.trim()
        if (cleanedChunk.isEmpty()) return

        if (isFirst) {
            transitionToState(PipelineState.SPEAKING, responseText = fullResponseText)
            requestAudioFocus(exclusive = true)  // Full exclusive focus while TTS is speaking
            tts?.speak(cleanedChunk, TextToSpeech.QUEUE_FLUSH, null, "${UTTERANCE_ID}_0")
        } else {
            overlayManager?.updateState(PipelineState.SPEAKING, "Speaking...", resp = fullResponseText)
            tts?.speak(cleanedChunk, TextToSpeech.QUEUE_ADD, null, "${UTTERANCE_ID}_${System.currentTimeMillis()}")
        }
    }

    private fun speakResponse(response: String) {
        if (!isTtsInitialized || tts == null) {
            com.friday.assistant.core.FridayLogger.e(TAG, "TTS not initialized")
            transitionToState(PipelineState.IDLE, responseText = response)
            return
        }

        transitionToState(PipelineState.SPEAKING, responseText = response)
        requestAudioFocus(exclusive = true)
        tts?.speak(response, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    private val UTTERANCE_ID = "friday_tts_utterance"

    private fun requestAudioFocus(exclusive: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusType = if (exclusive) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                            else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            val req = AudioFocusRequest.Builder(focusType)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { focusChange ->
                    // Only stop TTS if we lose focus while actively speaking.
                    // Do NOT transition to IDLE on general focus loss — that creates a loop with media apps.
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS && pipelineState.value == PipelineState.SPEAKING) {
                        com.friday.assistant.core.FridayLogger.d(TAG, "Audio focus lost during TTS — stopping speech")
                        serviceScope.launch {
                            tts?.stop()
                            transitionToState(PipelineState.IDLE)
                        }
                    } else {
                        com.friday.assistant.core.FridayLogger.d(TAG, "Audio focus change: $focusChange (state=${pipelineState.value}) — ignoring")
                    }
                }
                .build()
            val result = audioManager.requestAudioFocus(req)
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            audioFocusRequest = req
            com.friday.assistant.core.FridayLogger.d(TAG, "Audio focus requested (exclusive=$exclusive), granted=$hasAudioFocus")
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
                audioFocusRequest = null
            }
            hasAudioFocus = false
            com.friday.assistant.core.FridayLogger.d(TAG, "Audio focus abandoned")
        }
    }

    // ==========================================
    // TTS Callbacks
    // ==========================================

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    com.friday.assistant.core.FridayLogger.d(TAG, "TTS speaking started")
                }

                override fun onDone(utteranceId: String?) {
                    com.friday.assistant.core.FridayLogger.d(TAG, "TTS speaking finished")
                    serviceScope.launch {
                        transitionToState(PipelineState.IDLE)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    com.friday.assistant.core.FridayLogger.e(TAG, "TTS speaking error")
                    serviceScope.launch {
                        transitionToState(PipelineState.IDLE)
                    }
                }
            })
            isTtsInitialized = true
            com.friday.assistant.core.FridayLogger.i(TAG, "TTS Initialized successfully")
        } else {
            com.friday.assistant.core.FridayLogger.e(TAG, "TTS Initialization failed")
        }
    }



    override fun onDestroy() {
        super.onDestroy()
        com.friday.assistant.core.FridayLogger.i(TAG, "FridayService destroyed")
        instance = null

        wakeWordDetector?.shutdown()
        speechToTextHelper.destroy()
        
        try {
            sharedSpeechRecognizer?.destroy()
        } catch (e: Exception) {
            com.friday.assistant.core.FridayLogger.e(TAG, "Error destroying shared speech recognizer", e)
        }
        sharedSpeechRecognizer = null

        overlayManager?.destroyOverlay()
        tts?.shutdown()

        serviceScope.launch(Dispatchers.IO) {
            FridayApplication.llamaEngine.freeModel()
            FridayApplication.whisperEngine.freeModel()
        }
    }

    fun showOverlay() {
        val enabled = getSharedPreferences("friday_assistant_prefs", Context.MODE_PRIVATE).getBoolean("assistant_enabled", true)
        if (!enabled) {
            com.friday.assistant.core.FridayLogger.d(TAG, "Assistant is disabled by preference; not showing overlay")
            return
        }
        overlayManager?.show()
    }

    /** Called by ScreenshotTool before dispatching the capture action. */
    fun hideOverlayForScreenshot() {
        serviceScope.launch { overlayManager?.dismiss() }
    }

    /** Called by ScreenshotTool after the capture has been dispatched. */
    fun restoreOverlayAfterScreenshot() {
        serviceScope.launch { overlayManager?.show() }
    }

    private fun getStatusText(state: PipelineState): String {
        return when (state) {
            PipelineState.IDLE -> "Active"
            PipelineState.LISTENING -> "Listening..."
            PipelineState.PROCESSING -> "Processing..."
            PipelineState.THINKING -> "Thinking..."
            PipelineState.SPEAKING -> "Speaking..."
        }
    }
}
