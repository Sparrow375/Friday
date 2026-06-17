package com.friday.assistant.ui

import android.service.voice.VoiceInteractionService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.friday.assistant.audio.PipelineState
import com.friday.assistant.audio.SpeechToTextHelper
import com.friday.assistant.core.FridayApplication
import com.friday.assistant.core.ModelManager
import com.friday.assistant.intelligence.AgentCore
import com.friday.assistant.intelligence.MemoryManager
import com.friday.assistant.tools.ToolRegistry
import com.friday.assistant.tools.system.SystemControlsTool
import com.friday.assistant.tools.phone.PhoneTool
import com.friday.assistant.tools.apps.AppLauncherTool
import com.friday.assistant.tools.media.MediaControlTool
import com.friday.assistant.tools.search.WebSearchTool
import com.friday.assistant.tools.clipboard.ClipboardTool
import com.friday.assistant.tools.notes.NotesTool
import com.friday.assistant.tools.notes.RememberPreferenceTool
import com.friday.assistant.tools.notes.RecallPreferenceTool
import com.friday.assistant.tools.calendar.CalendarTool
import com.friday.assistant.tools.notifications.NotificationTool
import com.friday.assistant.tools.location.LocationTool
import com.friday.assistant.tools.camera.CameraTool
import com.friday.assistant.tools.files.FileManagerTool
import com.friday.assistant.tools.whatsapp.WhatsAppTool
import com.friday.assistant.tools.email.EmailTool
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
    private lateinit var memoryManager: MemoryManager
    private lateinit var agentCore: AgentCore
    private lateinit var speechToTextHelper: SpeechToTextHelper
    private var wakeWordDetector: com.friday.assistant.audio.WakeWordDetector? = null
    
    private var overlayManager: OverlayManager? = null
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false

    val pipelineState = MutableStateFlow(PipelineState.IDLE)

    override fun onCreate() {
        super.onCreate()
        com.friday.assistant.core.FridayLogger.i(TAG, "FridayService onCreate")
        instance = this

        // 1. Initialize core logic components
        modelManager = ModelManager(this)
        memoryManager = MemoryManager(this)
        agentCore = AgentCore(this, memoryManager)

        // 2. Register Agentic Tools
        ToolRegistry.register(SystemControlsTool(this))
        ToolRegistry.register(PhoneTool(this))
        ToolRegistry.register(AppLauncherTool(this, memoryManager))
        ToolRegistry.register(MediaControlTool(this))
        ToolRegistry.register(WebSearchTool(this))
        ToolRegistry.register(ClipboardTool(this))
        ToolRegistry.register(NotesTool())
        ToolRegistry.register(CalendarTool(this))
        ToolRegistry.register(NotificationTool(this))
        ToolRegistry.register(LocationTool(this))
        ToolRegistry.register(CameraTool(this))
        ToolRegistry.register(FileManagerTool(this))
        ToolRegistry.register(RememberPreferenceTool(memoryManager))
        ToolRegistry.register(RecallPreferenceTool(memoryManager))
        ToolRegistry.register(WhatsAppTool(this, memoryManager))
        ToolRegistry.register(EmailTool(this, memoryManager))

        // 3. Setup TTS
        tts = TextToSpeech(this, this)

        // 4. Setup Speech to Text Helper
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
            }
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
        wakeWordDetector = com.friday.assistant.audio.WakeWordDetector(this, modelManager) {
            com.friday.assistant.core.FridayLogger.i(TAG, "Wake word 'friday' detected!")
            serviceScope.launch {
                onWakeWordTriggered()
            }
        }
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
                overlayManager?.updateAmplitude(0f)
                speechToTextHelper.destroy()
                
                // Auto-dismiss overlay UI when task finishes
                val dismissDelay = if (oldState == PipelineState.SPEAKING) 4000L else 1500L
                serviceScope.launch {
                    kotlinx.coroutines.delay(dismissDelay)
                    if (pipelineState.value == PipelineState.IDLE) {
                        com.friday.assistant.core.FridayLogger.i(TAG, "Auto-dismissing overlay after task completion")
                        overlayManager?.dismiss()
                    }
                }

                if (oldState == PipelineState.SPEAKING) {
                    serviceScope.launch {
                        kotlinx.coroutines.delay(1500)
                        if (pipelineState.value == PipelineState.IDLE) {
                            startWakeWordListening()
                        }
                    }
                } else {
                    startWakeWordListening()
                }
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
            wakeWordDetector?.startListening()
        }
    }

    private fun stopWakeWordListening() {
        com.friday.assistant.core.FridayLogger.d(TAG, "Stopping background wake-word listening")
        wakeWordDetector?.stopListening()
    }

    private suspend fun onWakeWordTriggered() {
        overlayManager?.show()
        transitionToState(PipelineState.LISTENING)
        kotlinx.coroutines.delay(100) // Allow mic resources to release completely
        speechToTextHelper.startListening()
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
                kotlinx.coroutines.delay(100) // Allow mic resources to release completely
                speechToTextHelper.startListening()
            }
        }
    }

    private suspend fun executeAgentQuery(query: String) {
        transitionToState(PipelineState.THINKING, statusMessage = "Thinking...", transcriptText = query)
        
        var responseAccumulator = ""
        var ttsBuffer = ""
        var isFirstChunk = true
        
        val response = agentCore.processQuery(query) { token ->
            serviceScope.launch {
                responseAccumulator += token
                ttsBuffer += token
                
                // Update overlay UI text in real-time
                overlayManager?.updateState(
                    state = pipelineState.value, 
                    text = if (isFirstChunk) "Thinking..." else "Speaking...", 
                    resp = responseAccumulator, 
                    trans = query
                )
                
                // Check for punctuation boundaries to queue TTS chunks
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
        
        // Handle any leftover TTS buffer
        val remainingText = ttsBuffer.trim()
        if (remainingText.isNotEmpty()) {
            speakStreamChunk(remainingText, isFirstChunk, responseAccumulator)
            isFirstChunk = false
        }
        
        // If it was a fast command mapper response and didn't stream anything
        if (responseAccumulator.isEmpty() && response.isNotEmpty()) {
            speakResponse(response)
        } else if (responseAccumulator.isNotEmpty() && isFirstChunk) {
            // Streamed but no punctuation was found
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
        tts?.speak(response, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    private val UTTERANCE_ID = "friday_tts_utterance"

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

        stopWakeWordListening()
        speechToTextHelper.destroy()
        overlayManager?.dismiss()
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
