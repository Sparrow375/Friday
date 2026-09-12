package com.friday.assistant.ui

import android.service.voice.VoiceInteractionService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
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
        const val ACTION_TRIGGER_GESTURE = "com.friday.assistant.ACTION_TRIGGER_GESTURE"
        const val ACTION_PAUSE_WAKEWORD = "com.friday.assistant.ACTION_PAUSE_WAKEWORD"
        const val ACTION_RESUME_WAKEWORD = "com.friday.assistant.ACTION_RESUME_WAKEWORD"
        const val ACTION_SPEAK_REMINDER = "com.friday.assistant.ACTION_SPEAK_REMINDER"

        @Volatile
        var instance: FridayService? = null
            private set

        const val ACTION_RELOAD_BLE_WEARABLE = "com.friday.assistant.ACTION_RELOAD_BLE_WEARABLE"

        fun reloadModels() {
            instance?.let { service ->
                service.serviceScope.launch(Dispatchers.IO) {
                    val modelManager = com.friday.assistant.core.ModelManager(service)
                    if (modelManager.isLlmLoaded()) {
                        val path = modelManager.getLlmModelPath()
                        com.friday.assistant.core.FridayLogger.i(TAG, "Reloading LLM GGUF model: $path")
                        FridayApplication.llamaEngine.loadModel(path)
                    }
                }
            }
        }

        fun reloadBleWearable() {
            instance?.reloadBleWearableInternal()
        }

        fun showOverlay() {
            instance?.showOverlay()
        }

        fun triggerGestureActivation() {
            instance?.triggerGestureActivationInternal()
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main)

    // Core engine dependencies
    private lateinit var modelManager: ModelManager
    // agentCore and memoryManager are application-level singletons (FridayApplication.agentCore /
    // FridayApplication.memoryManager) — accessed via property delegation to avoid duplicating
    // the heavy NLU ONNX session on every service restart.
    private val agentCore: AgentCore get() = FridayApplication.agentCore
    private lateinit var audioCaptureManager: com.friday.assistant.audio.AudioCaptureManager
    private lateinit var speechToTextHelper: SpeechToTextHelper
    private var wakeWordDetector: com.friday.assistant.audio.WakeWordDetector? = null
    private var bleWearableManager: com.friday.assistant.ble.FridayBleWearableManager? = null
    
    private var overlayManager: OverlayManager? = null
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false

    // Audio focus management
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var ttsSafetyTimeoutJob: kotlinx.coroutines.Job? = null

    val pipelineState = MutableStateFlow(PipelineState.IDLE)

    override fun onCreate() {
        super.onCreate()
        com.friday.assistant.core.FridayLogger.i(TAG, "FridayService onCreate")
        instance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // 1. Initialize core logic components
        modelManager = ModelManager(this)
        audioCaptureManager = com.friday.assistant.audio.AudioCaptureManager(this)
        FridayApplication.agentCore  // warm up singleton

        // 2. Register Agentic Tools
        ToolRegistrar.registerAll(this, FridayApplication.memoryManager)

        // 3. Setup TTS
        tts = TextToSpeech(applicationContext, this)

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
        ).apply {
            warmUp()
        }

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

        // 7. Setup Two-Stage Neural Wake Word Detector
        wakeWordDetector = com.friday.assistant.audio.WakeWordDetector(this, modelManager) {
            com.friday.assistant.core.FridayLogger.i(TAG, "Neural wake word 'friday' detected!")
            serviceScope.launch {
                onWakeWordTriggered()
            }
        }

        // 8. Setup BLE Wearable Manager (Pi Zero 2 W)
        bleWearableManager = com.friday.assistant.ble.FridayBleWearableManager.getInstance(this).apply {
            onWakeWordDetected = {
                onWearableWakeWordTriggered()
            }
            onCommandAudioReceived = { audioSamples ->
                onWearableAudioCommandReceived(audioSamples)
            }
            onCommandTextReceived = { text ->
                onWearableTextCommandReceived(text)
            }
        }
    }

    override fun onReady() {
        super.onReady()
        com.friday.assistant.core.FridayLogger.i(TAG, "VoiceInteractionService is ready")
        instance = this

        // Models are loaded on-demand to minimize startup RAM and battery consumption
        startWakeWordListening()
        reloadBleWearableInternal()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        com.friday.assistant.core.FridayLogger.i(TAG, "onStartCommand received action: $action")
        
        if (action == ACTION_SHOW_OVERLAY) {
            showOverlay()
        } else if (action == ACTION_TRIGGER_GESTURE) {
            triggerGestureActivationInternal()
        } else if (action == ACTION_PAUSE_WAKEWORD) {
            stopWakeWordListening()
        } else if (action == ACTION_RESUME_WAKEWORD) {
            startWakeWordListening()
        } else if (action == ACTION_RELOAD_BLE_WEARABLE) {
            reloadBleWearableInternal()
        } else if (action == ACTION_SPEAK_REMINDER) {
            val text = intent.getStringExtra(ReminderReceiver.EXTRA_REMINDER_TEXT) ?: "You have a reminder."
            speakReminder(text)
        }

        return START_STICKY
    }

    private suspend fun setupNativeModels() {
        // GGUF model is loaded lazily inside AgentCore on first complex query to prevent memory starvation at startup
    }

    fun speakReminder(reminderText: String) {
        serviceScope.launch {
            overlayManager?.show(PipelineState.SPEAKING)
            val speech = "Reminder: $reminderText"
            transitionToState(PipelineState.SPEAKING, statusMessage = "Reminder", responseText = speech)
            speakResponse(speech)
        }
    }

    private fun transitionToState(
        newState: PipelineState,
        statusMessage: String? = null,
        responseText: String = "",
        transcriptText: String = ""
    ) {
        if (newState != PipelineState.SPEAKING) {
            cancelTtsSafetyTimeout()
        }
        val oldState = pipelineState.value
        if (oldState != newState) {
            com.friday.assistant.core.FridayLogger.d(TAG, "Transitioning state from $oldState to $newState")
            pipelineState.value = newState
            val status = statusMessage ?: getStatusText(newState)
            overlayManager?.updateState(newState, status, trans = transcriptText, resp = responseText)
            
            if (newState == PipelineState.IDLE) {
                abandonAudioFocus()
                overlayManager?.updateAmplitude(0f)
                speechToTextHelper.onIdle()
                
                // Auto-dismiss overlay — hold for 3 seconds after speaking or 1.5s so user can read response
                val dismissDelay = if (oldState == PipelineState.SPEAKING) 3000L else 1500L
                serviceScope.launch {
                    kotlinx.coroutines.delay(dismissDelay)
                    if (pipelineState.value == PipelineState.IDLE) {
                        com.friday.assistant.core.FridayLogger.i(TAG, "Auto-dismissing overlay after task completion")
                        overlayManager?.dismiss()
                    }
                }

                // Restart wake word only if wake_word_enabled is true
                startWakeWordListening()
            } else {
                stopWakeWordListening()
            }
        }
    }

    private fun startWakeWordListening() {
        val prefs = getSharedPreferences("friday_assistant_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("assistant_enabled", true)
        val wakeWordEnabled = prefs.getBoolean("wake_word_enabled", false)
        if (!enabled || !wakeWordEnabled) {
            com.friday.assistant.core.FridayLogger.d(TAG, "Background wake-word listening inactive (assistantEnabled=$enabled, wakeWordEnabled=$wakeWordEnabled)")
            return
        }

        val hasMicPerm = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasMicPerm) {
            com.friday.assistant.core.FridayLogger.w(TAG, "No mic permission - cannot start background wake-word listening")
            return
        }

        if (pipelineState.value == PipelineState.IDLE) {
            com.friday.assistant.core.FridayLogger.d(TAG, "Starting low-power VAD-gated wake-word listening")
            val detector = wakeWordDetector
            if (detector != null) {
                audioCaptureManager.registerListener(detector)
                detector.startListening()
                audioCaptureManager.startCapture()
            }
        }
    }

    private fun stopWakeWordListening() {
        com.friday.assistant.core.FridayLogger.d(TAG, "Stopping background wake-word listening")
        val detector = wakeWordDetector
        if (detector != null) {
            detector.stopListening()
            audioCaptureManager.unregisterListener(detector)
        }
        audioCaptureManager.stopCapture()
    }

    fun triggerGestureActivationInternal() {
        val enabled = getSharedPreferences("friday_assistant_prefs", Context.MODE_PRIVATE).getBoolean("assistant_enabled", true)
        if (!enabled) {
            com.friday.assistant.core.FridayLogger.d(TAG, "triggerGestureActivation ignored — assistant is disabled by preference")
            return
        }
        com.friday.assistant.core.FridayLogger.i(TAG, "Triggering instant gesture voice activation")
        serviceScope.launch {
            if (pipelineState.value == PipelineState.SPEAKING) {
                try { tts?.stop() } catch (e: Exception) { Log.e(TAG, "Error stopping TTS", e) }
            }
            overlayManager?.show()
            transitionToState(PipelineState.LISTENING)
            speechToTextHelper.startListening()
        }
    }

    private suspend fun onWakeWordTriggered() {
        overlayManager?.show()
        transitionToState(PipelineState.LISTENING)
        // No delay needed — SpeechRecognizer manages its own audio session setup
        com.friday.assistant.core.FridayLogger.d(TAG, "STT start triggered immediately (no mic handover delay)")
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
            com.friday.assistant.core.FridayLogger.d(TAG, "Fast tool result received (${response.length} chars): ${response.take(100)}")
            val prefs = getSharedPreferences("friday_assistant_prefs", Context.MODE_PRIVATE)
            val confirmTools = prefs.getBoolean("voice_confirm_tools", true)
            if (confirmTools && response.isNotBlank()) {
                // Speak the confirmation/result then auto-dismiss
                com.friday.assistant.core.FridayLogger.d(TAG, "Speaking fast tool response via TTS")
                speakResponse(response)
            } else if (response.isNotBlank()) {
                // voice_confirm_tools is off — show result on overlay without TTS
                com.friday.assistant.core.FridayLogger.d(TAG, "voice_confirm_tools=false, showing result silently")
                overlayManager?.updateState(PipelineState.IDLE, response, trans = query, resp = response)
                transitionToState(PipelineState.IDLE, responseText = response, transcriptText = query)
            } else {
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

    private fun cleanTextForTts(text: String): String {
        var cleaned = text.replace("(?i)source\\s*link:\\s*https?://\\S+".toRegex(), "")
        cleaned = cleaned.replace("(?i)source:\\s*https?://\\S+".toRegex(), "")
        cleaned = cleaned.replace("https?://\\S+".toRegex(), "")
        cleaned = cleaned.replace(Regex("[*#_`~]"), "") // Strip markdown formatting symbols
        cleaned = cleaned.replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1") // Strip markdown links
        cleaned = cleaned.replace(Regex("\\s+"), " ")
        return cleaned.trim()
    }

    private fun speakStreamChunk(chunk: String, isFirst: Boolean, fullResponseText: String) {
        if (!isTtsInitialized || tts == null) return
        
        val cleanedChunk = chunk.trim()
        if (cleanedChunk.isEmpty()) return

        val textToSpeak = cleanTextForTts(cleanedChunk)
        if (textToSpeak.isEmpty()) return

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        val result = if (isFirst) {
            transitionToState(PipelineState.SPEAKING, responseText = fullResponseText)
            requestAudioFocus(exclusive = false)
            tts?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, params, "${UTTERANCE_ID}_0")
        } else {
            overlayManager?.updateState(PipelineState.SPEAKING, "Speaking...", resp = fullResponseText)
            tts?.speak(textToSpeak, TextToSpeech.QUEUE_ADD, params, "${UTTERANCE_ID}_${System.currentTimeMillis()}")
        }

        if (result == TextToSpeech.ERROR) {
            com.friday.assistant.core.FridayLogger.e(TAG, "tts.speak returned ERROR in speakStreamChunk")
            serviceScope.launch {
                transitionToState(PipelineState.IDLE)
            }
        } else {
            startTtsSafetyTimeout(textToSpeak)
        }
    }

    private fun speakResponse(response: String) {
        if (!isTtsInitialized || tts == null) {
            com.friday.assistant.core.FridayLogger.e(TAG, "TTS not initialized (isTtsInitialized=$isTtsInitialized, tts=$tts)")
            transitionToState(PipelineState.IDLE, responseText = response)
            return
        }

        transitionToState(PipelineState.SPEAKING, responseText = response)
        requestAudioFocus(exclusive = false)
        val textToSpeak = cleanTextForTts(response)
        if (textToSpeak.isBlank()) {
            transitionToState(PipelineState.IDLE, responseText = response)
            return
        }
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        com.friday.assistant.core.FridayLogger.i(TAG, "Speaking via TTS (${textToSpeak.length} chars): ${textToSpeak.take(80)}")
        val result = tts?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)

        if (result == TextToSpeech.ERROR) {
            com.friday.assistant.core.FridayLogger.e(TAG, "tts.speak returned ERROR in speakResponse")
            transitionToState(PipelineState.IDLE, responseText = response)
        } else {
            startTtsSafetyTimeout(textToSpeak)
        }
    }

    private fun startTtsSafetyTimeout(text: String) {
        ttsSafetyTimeoutJob?.cancel()
        val timeoutMs = (text.length * 60L) + 3000L
        ttsSafetyTimeoutJob = serviceScope.launch {
            kotlinx.coroutines.delay(timeoutMs)
            if (pipelineState.value == PipelineState.SPEAKING) {
                com.friday.assistant.core.FridayLogger.w(TAG, "TTS safety timeout of ${timeoutMs}ms expired while in SPEAKING state. Forcing to IDLE.")
                transitionToState(PipelineState.IDLE)
            }
        }
    }

    private fun cancelTtsSafetyTimeout() {
        ttsSafetyTimeoutJob?.cancel()
        ttsSafetyTimeoutJob = null
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
                    com.friday.assistant.core.FridayLogger.d(TAG, "Audio focus change: $focusChange (state=${pipelineState.value})")
                    // Do NOT kill TTS on audio focus loss — the TTS engine itself manages its audio track,
                    // and on many devices (e.g. Samsung One UI) the TTS engine's track triggers a transient
                    // focus notification to our listener.
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(audioAttributes)
            }
            var langResult = tts?.setLanguage(Locale.US)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                com.friday.assistant.core.FridayLogger.w(TAG, "Locale.US not supported/missing data, falling back to default locale")
                langResult = tts?.setLanguage(Locale.getDefault())
                com.friday.assistant.core.FridayLogger.i(TAG, "Fallback locale set with result: $langResult")
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    com.friday.assistant.core.FridayLogger.d(TAG, "TTS speaking started: $utteranceId")
                }

                override fun onDone(utteranceId: String?) {
                    com.friday.assistant.core.FridayLogger.d(TAG, "TTS speaking finished: $utteranceId")
                    serviceScope.launch {
                        transitionToState(PipelineState.IDLE)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    com.friday.assistant.core.FridayLogger.e(TAG, "TTS speaking error: $utteranceId")
                    serviceScope.launch {
                        transitionToState(PipelineState.IDLE)
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    com.friday.assistant.core.FridayLogger.e(TAG, "TTS speaking error: $utteranceId, errorCode=$errorCode")
                    serviceScope.launch {
                        transitionToState(PipelineState.IDLE)
                    }
                }
            })
            isTtsInitialized = true
            com.friday.assistant.core.FridayLogger.i(TAG, "TTS Initialized successfully")
        } else {
            com.friday.assistant.core.FridayLogger.e(TAG, "TTS Initialization failed with status=$status")
        }
    }



    override fun onDestroy() {
        super.onDestroy()
        com.friday.assistant.core.FridayLogger.i(TAG, "FridayService destroyed")
        instance = null

        stopWakeWordListening()
        wakeWordDetector?.shutdown()
        bleWearableManager?.setEnabled(false)
        speechToTextHelper.destroy()
        overlayManager?.destroyOverlay()
        tts?.shutdown()

        serviceScope.launch(Dispatchers.IO) {
            FridayApplication.llamaEngine.freeModel()
            FridayApplication.whisperEngine.freeModel()
        }
    }

    fun reloadBleWearableInternal() {
        val prefs = getSharedPreferences("friday_assistant_prefs", Context.MODE_PRIVATE)
        val assistantEnabled = prefs.getBoolean("assistant_enabled", true)
        val bleEnabled = prefs.getBoolean("ble_wearable_enabled", true)
        val shouldEnable = assistantEnabled && bleEnabled
        com.friday.assistant.core.FridayLogger.i(TAG, "reloadBleWearable: shouldEnable=$shouldEnable (assistant=$assistantEnabled, ble=$bleEnabled)")
        bleWearableManager?.setEnabled(shouldEnable)
    }

    private fun onWearableWakeWordTriggered() {
        val enabled = getSharedPreferences("friday_assistant_prefs", Context.MODE_PRIVATE).getBoolean("assistant_enabled", true)
        if (!enabled) return

        com.friday.assistant.core.FridayLogger.i(TAG, "Wearable wake-word detected — waking screen and showing overlay")
        serviceScope.launch {
            if (pipelineState.value == PipelineState.SPEAKING) {
                try { tts?.stop() } catch (e: Exception) { Log.e(TAG, "Error stopping TTS", e) }
            }
            overlayManager?.show()
            transitionToState(PipelineState.LISTENING, statusMessage = "Listening to Wearable...")
        }
    }

    private fun onWearableAudioCommandReceived(audioSamples: FloatArray) {
        serviceScope.launch {
            transitionToState(PipelineState.THINKING, statusMessage = "Processing wearable speech...")
            val whisperEngine = FridayApplication.whisperEngine
            if (!whisperEngine.isModelLoaded()) {
                val whisperPath = modelManager.getWhisperModelPath()
                if (whisperPath != null && java.io.File(whisperPath).exists()) {
                    whisperEngine.loadModel(whisperPath)
                }
            }

            val transcribed = if (whisperEngine.isModelLoaded()) {
                whisperEngine.transcribe(audioSamples).trim()
            } else {
                ""
            }

            if (transcribed.isNotBlank()) {
                com.friday.assistant.core.FridayLogger.i(TAG, "Wearable speech transcribed: '$transcribed'")
                executeAgentQuery(transcribed)
            } else {
                com.friday.assistant.core.FridayLogger.w(TAG, "Wearable audio transcription was blank")
                overlayManager?.updateState(PipelineState.IDLE, "Could not recognize wearable speech")
                kotlinx.coroutines.delay(2000)
                transitionToState(PipelineState.IDLE)
                overlayManager?.dismiss()
            }
        }
    }

    private fun onWearableTextCommandReceived(text: String) {
        if (text.isNotBlank()) {
            serviceScope.launch {
                overlayManager?.show()
                executeAgentQuery(text)
            }
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
