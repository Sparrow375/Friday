package com.friday.assistant.ui

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import com.friday.assistant.R
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
import com.friday.assistant.tools.accessibility.ScreenReaderTool
import com.friday.assistant.tools.accessibility.ClickElementTool
import com.friday.assistant.tools.accessibility.TypeTextTool
import com.friday.assistant.tools.accessibility.ScrollScreenTool
import com.friday.assistant.tools.accessibility.GlobalActionTool
import com.friday.assistant.tools.whatsapp.WhatsAppTool
import com.friday.assistant.tools.email.EmailTool
import com.friday.assistant.ui.overlay.OverlayManager
import com.friday.assistant.ui.screens.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class FridayForegroundService : Service(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "FridayFgService"
        const val NOTIFICATION_ID = 2026
        private const val UTTERANCE_ID = "friday_tts_utterance"

        @Volatile
        var instance: FridayForegroundService? = null
            private set

        fun start(context: Context) {
            val hasMicPerm = context.checkSelfPermission(
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasMicPerm) {
                Log.w(TAG, "RECORD_AUDIO not granted – skipping foreground start")
                return
            }
            val intent = Intent(context, FridayForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start FridayForegroundService", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FridayForegroundService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main)
    
    // Core engine dependencies
    private lateinit var modelManager: ModelManager
    private lateinit var memoryManager: MemoryManager
    private lateinit var agentCore: AgentCore
    private lateinit var speechToTextHelper: SpeechToTextHelper
    
    private var overlayManager: OverlayManager? = null
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false

    val pipelineState = MutableStateFlow(PipelineState.IDLE)

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "FridayForegroundService onCreate")
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
        ToolRegistry.register(ScreenReaderTool())
        ToolRegistry.register(WhatsAppTool(this, memoryManager))
        ToolRegistry.register(EmailTool(this, memoryManager))
        ToolRegistry.register(ClickElementTool())
        ToolRegistry.register(TypeTextTool())
        ToolRegistry.register(ScrollScreenTool())
        ToolRegistry.register(GlobalActionTool())

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
                pipelineState.value = state
                val status = getStatusText(state)
                overlayManager?.updateState(state, status)
                if (state == PipelineState.IDLE) {
                    overlayManager?.updateAmplitude(0f)
                }
            }
        )

        // 5. Initialize UI Overlay Manager
        overlayManager = OverlayManager(
            context = this,
            onMicClick = { toggleListening() },
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

        // 6. Observe agent updates to keep overlay text in sync
        agentCore.agentStatusFlow.onEach { statusText ->
            overlayManager?.updateState(pipelineState.value, statusText)
        }.launchIn(serviceScope)

        // 7. Setup native models if downloaded
        serviceScope.launch(Dispatchers.IO) {
            setupNativeModels()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand – promoting to foreground")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Throwable) {
            Log.e(TAG, "startForeground failed", e)
        }

        // Show floating bubble UI overlay on start
        overlayManager?.show()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

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

    private fun toggleListening() {
        if (pipelineState.value == PipelineState.LISTENING) {
            speechToTextHelper.stopListening()
        } else {
            // Cancel TTS if speaking before listening
            if (pipelineState.value == PipelineState.SPEAKING) {
                tts?.stop()
            }
            speechToTextHelper.startListening()
        }
    }

    private suspend fun executeAgentQuery(query: String) {
        pipelineState.value = PipelineState.THINKING
        overlayManager?.updateState(PipelineState.THINKING, "Thinking...", trans = query)

        val response = agentCore.processQuery(query)

        speakResponse(response)
    }

    private fun speakResponse(response: String) {
        if (!isTtsInitialized || tts == null) {
            Log.e(TAG, "TTS not initialized")
            pipelineState.value = PipelineState.IDLE
            overlayManager?.updateState(PipelineState.IDLE, "Active", resp = response)
            return
        }

        pipelineState.value = PipelineState.SPEAKING
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
                        pipelineState.value = PipelineState.IDLE
                        overlayManager?.updateState(PipelineState.IDLE, "Active")
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS speaking error")
                    serviceScope.launch {
                        pipelineState.value = PipelineState.IDLE
                        overlayManager?.updateState(PipelineState.IDLE, "Active")
                    }
                }
            })
            isTtsInitialized = true
            Log.i(TAG, "TTS Initialized successfully")
        } else {
            Log.e(TAG, "TTS Initialization failed")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "FridayForegroundService destroyed")
        instance = null

        speechToTextHelper.destroy()
        overlayManager?.dismiss()
        tts?.shutdown()

        serviceScope.launch(Dispatchers.IO) {
            FridayApplication.llamaEngine.freeModel()
            FridayApplication.whisperEngine.freeModel()
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, FridayApplication.CHANNEL_ID)
            .setContentTitle("Friday Assistant")
            .setContentText("Offline voice assistant is active and listening")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
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

    fun showOverlay() {
        overlayManager?.show()
    }
}
