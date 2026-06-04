package com.friday.assistant.ui

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationCompat
import com.friday.assistant.ui.MainActivity
import com.friday.assistant.R
import com.friday.assistant.audio.SpeakerVerifier
import com.friday.assistant.audio.SpeechRecognizerManager
import com.friday.assistant.classifier.CommandClassifier
import com.friday.assistant.classifier.IntentType
import com.friday.assistant.classifier.LocalLlmRunner
import com.friday.assistant.core.FridayApplication
import com.friday.assistant.core.ConversationEntity
import com.friday.assistant.core.RoutineEntity
import com.friday.assistant.executor.RoutineExecutor
import com.friday.assistant.executor.SystemExecutor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.Locale
import kotlin.math.abs

class OverlayService : Service(), TextToSpeech.OnInitListener {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Managers & Executors
    private lateinit var speechManager: SpeechRecognizerManager
    private var speakerVerifier: SpeakerVerifier? = null
    private val commandClassifier = CommandClassifier()
    private var localLlmRunner: LocalLlmRunner? = null
    private lateinit var systemExecutor: SystemExecutor
    private lateinit var routineExecutor: RoutineExecutor
    private var tts: TextToSpeech? = null
    private lateinit var prefs: SharedPreferences

    // Views
    private lateinit var windowManager: WindowManager
    private var bubbleView: FrameLayout? = null
    private var panelView: FrameLayout? = null
    private var visualizerView: AudioVisualizerView? = null
    private var transcriptText: TextView? = null
    private var responseText: TextView? = null
    private var statusText: TextView? = null

    // Layout Params
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var isPanelExpanded = false
    private var enrolledEmbedding: FloatArray? = null
    private var isTtsSpeaking = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Creating OverlayService...")
        isRunning = true
        
        prefs = getSharedPreferences("friday_prefs", Context.MODE_PRIVATE)
        loadEnrolledVoice()

        // Init System & Voice API
        tts = TextToSpeech(this, this)
        serviceScope.launch(Dispatchers.IO) {
            try {
                speakerVerifier = SpeakerVerifier.getInstance(this@OverlayService)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize SpeakerVerifier inside service", t)
            }

            try {
                localLlmRunner = LocalLlmRunner.getInstance(this@OverlayService)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize LocalLlmRunner inside service", t)
            }
        }

        systemExecutor = SystemExecutor(this)
        
        routineExecutor = RoutineExecutor(this, systemExecutor) { ttsText ->
            speak(ttsText)
        }

        speechManager = SpeechRecognizerManager(this, speechCallback)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        setupFloatingViews()
        
        // Start waiting for the wake word
        speechManager.startWakeWordListening()
    }

    private fun loadEnrolledVoice() {
        val enrolledStr = prefs.getString("enrolled_embedding", null)
        if (!enrolledStr.isNullOrEmpty()) {
            try {
                enrolledEmbedding = enrolledStr.split(",").map { it.toFloat() }.toFloatArray()
                Log.i(TAG, "Loaded enrolled voice embedding: size=${enrolledEmbedding?.size}")
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing enrolled voice embedding", e)
            }
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, FridayApplication.CHANNEL_ID)
            .setContentTitle("Friday Assistant")
            .setContentText("Friday is listening for commands...")
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingViews() {
        // --- 1. Draggable Bubble ---
        bubbleView = FrameLayout(this)
        val bubbleDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xE615151A.toInt())
            setStroke(2, 0x88FFFFFF.toInt())
        }
        bubbleView?.background = bubbleDrawable

        // Add a small inner indicator circle
        val indicator = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFFFFFF.toInt())
            }
        }
        val indicatorParams = FrameLayout.LayoutParams(30, 30, Gravity.CENTER)
        bubbleView?.addView(indicator, indicatorParams)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        bubbleParams = WindowManager.LayoutParams(
            150, 150, // width, height (60dp approx)
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        // Dragging listener for bubble
        bubbleView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var clickTime = 0L

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = bubbleParams!!.x
                        initialY = bubbleParams!!.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        clickTime = System.currentTimeMillis()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        bubbleParams!!.x = initialX + (event.rawX - initialTouchX).toInt()
                        bubbleParams!!.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(bubbleView, bubbleParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val duration = System.currentTimeMillis() - clickTime
                        val diffX = abs(event.rawX - initialTouchX)
                        val diffY = abs(event.rawY - initialTouchY)
                        if (duration < 200 && diffX < 10 && diffY < 10) {
                            // Click event: Toggle Assistant Panel
                            togglePanel()
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(bubbleView, bubbleParams)

        // --- 2. Expansible Glassmorphic Panel ---
        panelView = FrameLayout(this).apply {
            visibility = View.GONE
            background = null // Root view is completely transparent
            setOnClickListener {
                collapsePanel()
            }
        }

        val panelDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 72f // Beautifully rounded floating card (24dp approx)
            colors = intArrayOf(0xF20A0A0C.toInt(), 0xF215151A.toInt()) // Semi-translucent obsidian
            setStroke(2, 0x1EFFFFFF.toInt()) // Subtle silver border
        }

        // Panel Layout
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            background = panelDrawable
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(48, 0, 48, 64) // Floating layout margins
                gravity = Gravity.BOTTOM
            }
            setOnClickListener {
                // Consume click
            }
        }

        // 1. Status Indicator
        statusText = TextView(this).apply {
            text = "FRIDAY ACTIVE"
            setTextColor(0xFF8E909A.toInt())
            textSize = 10f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            gravity = Gravity.CENTER_HORIZONTAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                letterSpacing = 0.2f
            }
        }
        container.addView(statusText)

        // 2. Transcript Box (User STT)
        transcriptText = TextView(this).apply {
            text = "Press Friday to speak..."
            setTextColor(0xFF8E909A.toInt()) // Silver text
            textSize = 14f
            setTypeface(null, Typeface.ITALIC)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 20, 0, 10)
        }
        container.addView(transcriptText)

        // 3. Audio Visualizer (sine waves)
        visualizerView = AudioVisualizerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                120
            ).apply {
                setMargins(0, 8, 0, 8)
            }
        }
        container.addView(visualizerView)

        // 4. Response Box (Assistant TTS)
        responseText = TextView(this).apply {
            text = ""
            setTextColor(0xFFFFFFFF.toInt()) // High contrast white
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 10, 0, 10)
        }
        container.addView(responseText)

        // 5. Text Input Row
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 24, 0, 0)
        }

        val inputField = EditText(this).apply {
            hint = "Type command..."
            setHintTextColor(0xFF8E909A.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f
                setColor(0xFF222228.toInt())
                setStroke(1, 0x1AFFFFFF.toInt())
            }
            setPadding(36, 16, 36, 16)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f
            )
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(true)
        }

        val sendButton = TextView(this).apply {
            text = "Send"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f
                setColor(0xFF3E3E48.toInt())
            }
            setPadding(36, 16, 36, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 16
            }
        }

        val executeAction = {
            val textCmd = inputField.text.toString().trim()
            if (textCmd.isNotEmpty()) {
                inputField.setText("")
                hideKeyboard(inputField)
                handleTextCommand(textCmd)
            }
        }

        inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_ACTION_DONE) {
                executeAction()
                true
            } else {
                false
            }
        }

        sendButton.setOnClickListener {
            executeAction()
        }

        inputRow.addView(inputField)
        inputRow.addView(sendButton)
        container.addView(inputRow)

        panelView?.addView(container)

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = 0
        }

        windowManager.addView(panelView, panelParams)
    }

    private fun togglePanel() {
        if (isPanelExpanded) {
            collapsePanel()
        } else {
            expandPanel()
        }
    }

    private fun expandPanel() {
        isPanelExpanded = true
        panelView?.visibility = View.VISIBLE
        statusText?.text = "LISTENING..."
        statusText?.setTextColor(0xFFE4E4E9.toInt())
        transcriptText?.text = "Listening..."
        responseText?.text = ""
        
        val view = panelView ?: return
        val params = panelParams ?: return
        params.flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                       WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        windowManager.updateViewLayout(view, params)
        
        if (!isTtsSpeaking) {
            val isVoiceLock = prefs.getBoolean("voice_verification_enabled", false)
            speechManager.startActiveCommandListening(recordAudio = isVoiceLock && enrolledEmbedding != null)
        }
    }

    private fun collapsePanel() {
        isPanelExpanded = false
        panelView?.visibility = View.GONE
        
        val view = panelView ?: return
        val params = panelParams ?: return
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                       WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        windowManager.updateViewLayout(view, params)
        
        speechManager.stop()
        if (!isTtsSpeaking) {
            speechManager.startWakeWordListening()
        }
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun sanitizeTextForTts(text: String): String {
        val emojiPattern = Regex(
            "[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]|" +
            "[\\u2600-\\u27BF]|" +
            "[\\u2300-\\u23FF]|" +
            "[\\u2B50]|" +
            "[\\u3030\\u303D]|[\\u3297\\u3299]|" +
            "[\\uD83E\\uDD00-\\uD83E\\uDDFF]|" +
            "[\\uD83D\\uDE00-\\uD83D\\uDE4F]"
        )
        return text.replace(emojiPattern, "").replace(Regex("\\s+"), " ").trim()
    }

    private fun speak(text: String) {
        serviceScope.launch {
            val cleanTtsText = sanitizeTextForTts(text).ifEmpty { text }
            responseText?.text = "Friday: \"$text\""
            statusText?.text = "SPEAKING..."
            statusText?.setTextColor(0xFFFFFFFF.toInt())
            
            // Set flag and stop recognizer to prevent feedback loop
            isTtsSpeaking = true
            speechManager.stop()
            
            tts?.speak(cleanTtsText, TextToSpeech.QUEUE_FLUSH, null, "FridayTTS")
            
            // Save to database
            val dao = (application as FridayApplication).fridayDao
            dao.insertConversation(
                ConversationEntity(
                    timestamp = System.currentTimeMillis(),
                    speaker = "FRIDAY",
                    message = text,
                    isOffline = true
                )
            )
        }
    }

    // --- Speech Recognizer Callbacks ---
    private val speechCallback = object : SpeechRecognizerManager.RecognitionCallback {
        override fun onWakeWordDetected() {
            Log.i(TAG, "Wake word detected!")
            serviceScope.launch(Dispatchers.Main) {
                expandPanel()
                speak("Yes, Avaneesh?")
            }
        }

        override fun onCommandRecognized(text: String, rawAudio: ShortArray) {
            serviceScope.launch(Dispatchers.Main) {
                if (text.isEmpty()) {
                    transcriptText?.text = "I didn't catch that."
                    collapsePanel()
                    return@launch
                }

                transcriptText?.text = "You: \"$text\""
                
                // Save user query to DB
                val dao = (application as FridayApplication).fridayDao
                dao.insertConversation(
                    ConversationEntity(
                        timestamp = System.currentTimeMillis(),
                        speaker = "USER",
                        message = text,
                        isOffline = true
                    )
                )

                // 1. Verify Speaker (Voice Recognition Security)
                val verificationScore = verifyVoice(rawAudio)
                val isVerified = verificationScore >= 0.72f || enrolledEmbedding == null
                
                if (!isVerified) {
                    statusText?.text = "SECURITY CHECK FAILED"
                    statusText?.setTextColor(0xFFFF416C.toInt())
                    speak("Access denied. That voice is not recognized.")
                    delay(3000)
                    collapsePanel()
                    return@launch
                }

                statusText?.text = "PROCESSING..."
                statusText?.setTextColor(0xFF8E909A.toInt())

                // 2. Check routines first
                val phraseNormalized = text.lowercase().trim().replace(Regex("[.,!?]"), "")
                val routine = dao.getRoutineByPhrase(phraseNormalized)
                if (routine != null) {
                    statusText?.text = "RUNNING ROUTINE..."
                    statusText?.setTextColor(0xFF92FE9D.toInt())
                    routineExecutor.executeRoutine(routine.name, routine.commandsJson)
                } else {
                    val command = withContext(Dispatchers.Default) {
                        commandClassifier.classify(text)
                    }
                    executeClassifiedCommand(command, text)
                }
                
                // Keep the panel expanded briefly then collapse
                delay(5000)
                collapsePanel()
            }
        }

        override fun onSpeechVolumeChanged(rmsdB: Float) {
            visualizerView?.setAmplitude(rmsdB)
        }

        override fun onError(errorMsg: String) {
            Log.e(TAG, "Audio engine error: $errorMsg")
            // Show error in visualizer/status briefly
            serviceScope.launch {
                statusText?.text = "ERROR: $errorMsg"
                statusText?.setTextColor(0xFFFF4B2B.toInt())
            }
        }

        override fun onStateChanged(state: SpeechRecognizerManager.State) {
            Log.d(TAG, "Audio engine state: $state")
        }

        override fun onPartialTranscript(text: String) {
            serviceScope.launch(Dispatchers.Main) {
                transcriptText?.text = "You: \"$text\""
            }
        }
    }

    private fun handleTextCommand(text: String) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return
        
        serviceScope.launch(Dispatchers.Main) {
            transcriptText?.text = "You: \"$cleanText\""
            
            // Save user query to DB
            val dao = (application as FridayApplication).fridayDao
            dao.insertConversation(
                ConversationEntity(
                    timestamp = System.currentTimeMillis(),
                    speaker = "USER",
                    message = cleanText,
                    isOffline = true
                )
            )

            statusText?.text = "PROCESSING..."
            statusText?.setTextColor(0xFF8E909A.toInt())

            val phraseNormalized = cleanText.lowercase().trim().replace(Regex("[.,!?]"), "")
            val routine = dao.getRoutineByPhrase(phraseNormalized)
            if (routine != null) {
                statusText?.text = "RUNNING ROUTINE..."
                statusText?.setTextColor(0xFF92FE9D.toInt())
                routineExecutor.executeRoutine(routine.name, routine.commandsJson)
            } else {
                val command = withContext(Dispatchers.Default) {
                    commandClassifier.classify(cleanText)
                }
                executeClassifiedCommand(command, cleanText)
            }
            
            delay(5000)
            collapsePanel()
        }
    }

    private suspend fun executeClassifiedCommand(command: com.friday.assistant.classifier.LocalCommand, text: String) {
        try {
            val dao = (application as FridayApplication).fridayDao
            when (command.intent) {
                IntentType.VOLUME -> speak(systemExecutor.executeVolume(command.parameters))
                IntentType.BRIGHTNESS -> speak(systemExecutor.executeBrightness(command.parameters))
                IntentType.TORCH -> speak(systemExecutor.executeTorch(command.parameters))
                IntentType.WIFI -> speak(systemExecutor.executeWifi(command.parameters))
                IntentType.BLUETOOTH -> speak(systemExecutor.executeBluetooth(command.parameters))
                IntentType.DND -> speak(systemExecutor.executeDnd(command.parameters))
                IntentType.BATTERY -> speak(systemExecutor.executeBattery())
                IntentType.CLIPBOARD -> speak(systemExecutor.executeClipboard(command.parameters))
                IntentType.LAUNCH_APP -> speak(systemExecutor.executeLaunchApp(command.parameters["packageName"] ?: "", command.parameters["appName"] ?: ""))
                IntentType.DEEP_LINK_APP -> speak(systemExecutor.executeDeepLink(command.parameters))
                IntentType.ALARM_TIMER -> speak(systemExecutor.executeAlarmTimer(command.parameters))
                IntentType.CALL -> speak(systemExecutor.executeCall(command.parameters))
                
                IntentType.NOTE -> {
                    val action = command.parameters["action"]
                    if (action == "create") {
                        val content = command.parameters["content"] ?: ""
                        dao.insertNote(com.friday.assistant.core.NoteEntity(content = content, timestamp = System.currentTimeMillis()))
                        speak("I've saved that to your notes.")
                    } else {
                        speak("What would you like me to write down?")
                    }
                }

                IntentType.ROUTINE -> {
                    val phrase = text.lowercase().trim()
                    val routine = dao.getRoutineByPhrase(phrase)
                    if (routine != null) {
                        routineExecutor.executeRoutine(routine.name, routine.commandsJson)
                    } else {
                        speak("I found a matching routine pattern, but it has not been configured.")
                    }
                }

                IntentType.FALLBACK_TO_LLM -> {
                    // Query the Local LLM (Offline Gemma/Llama)
                    statusText?.text = "CONSULTING NEURAL CORE..."
                    statusText?.setTextColor(0xFFE4E4E9.toInt())
                    
                    // Pull recent conversations flow list
                    val recentConversations = dao.getRecentConversations(6).first()
                    val historyPairs = recentConversations.map { Pair(it.speaker, it.message) }
                    
                    val runner = localLlmRunner
                    if (runner != null && runner.isModelLoaded()) {
                        val llmResponse = runner.generateResponse(text, historyPairs)
                        speak(llmResponse)
                    } else {
                        speak("Offline language model not loaded. Basic offline commands still work! Ask me to turn on torch, change volume, or launch apps.")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command: ${e.localizedMessage}", e)
            speak("Error executing command: ${e.localizedMessage}")
        }
    }

    private suspend fun verifyVoice(pcmData: ShortArray): Float = withContext(Dispatchers.Default) {
        val enrolled = enrolledEmbedding ?: return@withContext 1.0f // Bypass check if no voice enrolled
        val verifier = speakerVerifier ?: return@withContext 1.0f // Bypass check if JNI initialization failed
        if (pcmData.isEmpty()) return@withContext 0f
        verifier.verifySpeaker(pcmData, enrolled)
    }

    // --- TextToSpeech OnInit ---
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isTtsSpeaking = true
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        speechManager.stop()
                    }
                }

                override fun onDone(utteranceId: String?) {
                    isTtsSpeaking = false
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (isPanelExpanded) {
                            val isVoiceLock = prefs.getBoolean("voice_verification_enabled", false)
                            speechManager.startActiveCommandListening(recordAudio = isVoiceLock && enrolledEmbedding != null)
                        } else {
                            speechManager.startWakeWordListening()
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    isTtsSpeaking = false
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (isPanelExpanded) {
                            val isVoiceLock = prefs.getBoolean("voice_verification_enabled", false)
                            speechManager.startActiveCommandListening(recordAudio = isVoiceLock && enrolledEmbedding != null)
                        } else {
                            speechManager.startWakeWordListening()
                        }
                    }
                }
            })
        } else {
            Log.e(TAG, "TTS Initialization failed.")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Trigger model reloading check in case a model was newly placed
        localLlmRunner?.reloadModel()
        loadEnrolledVoice()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Destroying OverlayService...")
        isRunning = false
        serviceScope.cancel()
        speechManager.destroy()
        tts?.stop()
        tts?.shutdown()
        localLlmRunner?.close()
        
        try {
            bubbleView?.let { windowManager.removeView(it) }
            panelView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing window views", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIFICATION_ID = 404

        @Volatile
        var isRunning = false
    }
}
