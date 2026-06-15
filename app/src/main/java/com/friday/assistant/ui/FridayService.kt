package com.friday.assistant.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.friday.assistant.audio.AudioCaptureManager
import com.friday.assistant.audio.PipelineState
import com.friday.assistant.audio.SpeakerVerifier
import com.friday.assistant.audio.VoicePipeline
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
import com.friday.assistant.tools.accessibility.ScreenReaderTool
import com.friday.assistant.tools.accessibility.ClickElementTool
import com.friday.assistant.tools.accessibility.TypeTextTool
import com.friday.assistant.tools.accessibility.ScrollScreenTool
import com.friday.assistant.tools.accessibility.GlobalActionTool
import com.friday.assistant.tools.whatsapp.WhatsAppTool
import com.friday.assistant.tools.email.EmailTool
import com.friday.assistant.ui.overlay.OverlayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.math.sqrt
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class FridayService : AccessibilityService(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "FridayService"
        private const val UTTERANCE_ID = "friday_tts_utterance"

        @Volatile
        var instance: FridayService? = null
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main)
    
    // Core engine dependencies
    private lateinit var modelManager: ModelManager
    private lateinit var memoryManager: MemoryManager
    private lateinit var agentCore: AgentCore
    private lateinit var audioCaptureManager: AudioCaptureManager
    private lateinit var speakerVerifier: SpeakerVerifier
    private lateinit var voicePipeline: VoicePipeline
    
    private var overlayManager: OverlayManager? = null
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "FridayService Created")
        
        // 1. Initialize logic components
        modelManager = ModelManager(this)
        memoryManager = MemoryManager(this)
        agentCore = AgentCore(this, memoryManager)
        audioCaptureManager = FridayApplication.audioCaptureManager
        speakerVerifier = SpeakerVerifier(this, modelManager)
        
        // 2. Initialize Voice pipeline
        voicePipeline = VoicePipeline(
            context = this,
            audioCaptureManager = audioCaptureManager,
            whisperEngine = FridayApplication.whisperEngine,
            speakerVerifier = speakerVerifier
        ) { query ->
            // Trigger LLM Agent loop on voice command transcription
            serviceScope.launch {
                executeAgentQuery(query)
            }
        }

        // 3. Register Agentic Tools
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
        ToolRegistry.register(ScreenReaderTool())
        ToolRegistry.register(WhatsAppTool(this, memoryManager))
        ToolRegistry.register(EmailTool(this, memoryManager))
        ToolRegistry.register(ClickElementTool())
        ToolRegistry.register(TypeTextTool())
        ToolRegistry.register(ScrollScreenTool())
        ToolRegistry.register(GlobalActionTool())

        // 4. Initialize TTS
        tts = TextToSpeech(this, this)

        // 5. Initialize UI Overlay Manager
        overlayManager = OverlayManager(
            context = this,
            onMicClick = { voicePipeline.forceTrigger() },
            onClose = {
                Log.i(TAG, "Overlay close clicked - dismissing overlay")
                overlayManager?.dismiss()
            },
            onTextSubmit = { text ->
                serviceScope.launch {
                    executeAgentQuery(text)
                }
            }
        )

        // 6. Observe Pipeline State to update UI status text and waveforms
        voicePipeline.state.onEach { state ->
            val status = getStatusText(state)
            
            // Auto-restore overlay when voice activity is detected or assistant is active
            if (state != PipelineState.IDLE) {
                overlayManager?.show()
            }
            
            overlayManager?.updateState(state, status)
            
            if (state == PipelineState.IDLE) {
                // Reset audio amplitude visualizer
                overlayManager?.updateAmplitude(0f)
            }
        }.launchIn(serviceScope)

        // 7. Observe Agent output updates
        agentCore.agentStatusFlow.onEach { statusText ->
            overlayManager?.updateState(voicePipeline.state.value, statusText)
        }.launchIn(serviceScope)

        // Feed audio frames to overlay manager for dynamic wave visualizer in active states
        audioCaptureManager.registerListener(object : AudioCaptureManager.AudioFrameListener {
            override fun onAudioFrame(pcmData: ShortArray) {
                val state = voicePipeline.state.value
                if (state == PipelineState.LISTENING || state == PipelineState.SPEAKING) {
                    val rms = calculateRMS(pcmData)
                    val normalized = (rms / 3000f).coerceIn(0f, 1f)
                    overlayManager?.updateAmplitude(normalized)
                }
            }
        })
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility Service Connected")
        instance = this

        // Start the dedicated foreground service for the persistent notification.
        // AccessibilityService must NOT call startForeground() itself – doing so causes
        // Android to mark the service as "Not working" in Accessibility Settings.
        FridayForegroundService.start(this)

        // Show floating bubble UI overlay
        overlayManager?.show()

        // Setup native engine models if they are downloaded
        serviceScope.launch(Dispatchers.IO) {
            setupNativeModels()
        }

        // Activate voice pipeline
        voicePipeline.startPipeline()
    }

    fun promoteToMicrophoneForeground() {
        // Delegate to FridayForegroundService so that the AccessibilityService
        // is never responsible for its own foreground promotion.
        FridayForegroundService.start(this)
    }

    private suspend fun setupNativeModels() {
        val whisperPath = modelManager.getWhisperModelPath()
        val llmPath = modelManager.getLlmModelPath()

        if (File(whisperPath).exists()) {
            val success = FridayApplication.whisperEngine.loadModel(whisperPath)
            Log.i(TAG, "Whisper model loaded status: $success")
        } else {
            Log.w(TAG, "Whisper model file missing at: $whisperPath")
        }

        if (File(llmPath).exists()) {
            val success = FridayApplication.llamaEngine.loadModel(llmPath)
            Log.i(TAG, "Llama model loaded status: $success")
        } else {
            Log.w(TAG, "Llama GGUF model file missing at: $llmPath")
        }
    }

    private suspend fun executeAgentQuery(query: String) {
        // Run agent thinking loop
        val response = agentCore.processQuery(query)
        
        // Output response via TTS
        speakResponse(response)
    }

    private fun speakResponse(response: String) {
        if (!isTtsInitialized || tts == null) {
            Log.e(TAG, "TTS not initialized")
            voicePipeline.setSpeakingState(false)
            return
        }

        voicePipeline.setSpeakingState(true)
        overlayManager?.updateState(PipelineState.SPEAKING, "Speaking...", resp = response)

        tts?.speak(response, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    // ==========================================
    // TTS Callbacks
    // ==========================================

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS speaking started")
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "TTS speaking finished")
                    serviceScope.launch {
                        voicePipeline.setSpeakingState(false)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS speaking error")
                    serviceScope.launch {
                        voicePipeline.setSpeakingState(false)
                    }
                }
            })
            isTtsInitialized = true
            Log.i(TAG, "TTS Initialized successfully")
        } else {
            Log.e(TAG, "TTS Initialization failed")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Stub for Phase 1. Used in Phase 3 for UI content scanning.
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "FridayService Destroyed")
        instance = null
        voicePipeline.stopPipeline()
        overlayManager?.dismiss()
        tts?.shutdown()
        FridayForegroundService.stop(this)
        
        serviceScope.launch(Dispatchers.IO) {
            FridayApplication.llamaEngine.freeModel()
            FridayApplication.whisperEngine.freeModel()
        }
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

    private fun calculateRMS(pcmData: ShortArray): Float {
        var sum = 0.0
        for (sample in pcmData) {
            sum += sample.toDouble() * sample.toDouble()
        }
        return sqrt(sum / pcmData.size).toFloat()
    }

    fun pauseVoicePipeline() {
        Log.i(TAG, "Pausing voice pipeline for external audio use")
        voicePipeline.stopPipeline()
    }

    fun resumeVoicePipeline() {
        Log.i(TAG, "Resuming voice pipeline after external audio use")
        voicePipeline.startPipeline()
    }

    fun showOverlay() {
        overlayManager?.show()
    }
}
