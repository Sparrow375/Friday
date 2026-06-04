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

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Creating OverlayService...")
        
        prefs = getSharedPreferences("friday_prefs", Context.MODE_PRIVATE)
        loadEnrolledVoice()

        // Init System & Voice API
        tts = TextToSpeech(this, this)
        try {
            speakerVerifier = SpeakerVerifier.getInstance(this)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize SpeakerVerifier inside service", t)
        }

        try {
            localLlmRunner = LocalLlmRunner.getInstance(this)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize LocalLlmRunner inside service", t)
        }

        systemExecutor = SystemExecutor(this)
        
        routineExecutor = RoutineExecutor(this, systemExecutor) { ttsText ->
            speak(ttsText)
        }

        speechManager = SpeechRecognizerManager(this, speechCallback)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        startForeground(NOTIFICATION_ID, createNotification())
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
            colors = intArrayOf(0xFF8E2DE2.toInt(), 0xFF4A00E0.toInt()) // Gradient Purple
            orientation = GradientDrawable.Orientation.TL_BR
            setStroke(2, 0xFFFFFFFF.toInt())
        }
        bubbleView?.background = bubbleDrawable

        // Add a small inner indicator circle
        val indicator = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x88FFFFFF.toInt())
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
        }

        val panelDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(40f, 40f, 40f, 40f, 0f, 0f, 0f, 0f) // Rounded top corners
            colors = intArrayOf(0xE61E1E24.toInt(), 0xE60D0D11.toInt()) // Sleek dark translucent
            setStroke(2, 0x33FFFFFF.toInt()) // Subtle glass border
        }
        panelView?.background = panelDrawable

        // Panel Layout
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 1. Status Indicator
        statusText = TextView(this).apply {
            text = "FRIDAY ACTIVE"
            setTextColor(0xFF00C9FF.toInt())
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        container.addView(statusText)

        // 2. Transcript Box
        transcriptText = TextView(this).apply {
            text = "Press Friday to speak..."
            setTextColor(0xFFE2E2E2.toInt())
            textSize = 16f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 20, 0, 10)
        }
        container.addView(transcriptText)

        // 3. Audio Visualizer (sine waves)
        visualizerView = AudioVisualizerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                150
            ).apply {
                setMargins(0, 10, 0, 10)
            }
        }
        container.addView(visualizerView)

        // 4. Response Box
        responseText = TextView(this).apply {
            text = ""
            setTextColor(0xFFA5D6A7.toInt()) // Soft green response
            textSize = 15f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 10, 0, 10)
        }
        container.addView(responseText)

        panelView?.addView(container)

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
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
        statusText?.setTextColor(0xFFE2DE2.toInt())
        transcriptText?.text = "I'm listening, Avaneesh..."
        responseText?.text = ""
        
        speechManager.startActiveCommandListening()
    }

    private fun collapsePanel() {
        isPanelExpanded = false
        panelView?.visibility = View.GONE
        speechManager.stop()
        speechManager.startWakeWordListening()
    }

    private fun speak(text: String) {
        serviceScope.launch {
            responseText?.text = text
            statusText?.text = "SPEAKING..."
            statusText?.setTextColor(0xFF92FE9D.toInt())
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "FridayTTS")
            
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

                transcriptText?.text = "\"$text\""
                
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
                statusText?.setTextColor(0xFF00C9FF.toInt())

                // 2. Classify intent
                val command = commandClassifier.classify(text)
                
                // 3. Execute
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
                    
                    IntentType.NOTE -> {
                        val action = command.parameters["action"]
                        if (action == "create") {
                            val content = command.parameters["content"] ?: ""
                            dao.insertNote(com.friday.assistant.core.NoteEntity(content = content, timestamp = System.currentTimeMillis()))
                            speak("I've saved that to your notes.")
                        } else {
                            speak("What would you like me to write down?")
                            // Let's expand again to record the content
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
                        statusText?.setTextColor(0xFF8E2DE2.toInt())
                        
                        // Pull recent conversations flow list
                        val recentConversations = dao.getRecentConversations(6).first()
                        val historyPairs = recentConversations.map { Pair(it.speaker, it.message) }
                        
                        val runner = localLlmRunner
                        if (runner != null) {
                            val llmResponse = runner.generateResponse(text, historyPairs)
                            speak(llmResponse)
                        } else {
                            speak("I'm sorry, my local language model could not be initialized due to a JNI linkage error.")
                        }
                    }
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
                statusText?.text = "ERROR"
                statusText?.setTextColor(0xFFFF4B2B.toInt())
            }
        }

        override fun onStateChanged(state: SpeechRecognizerManager.State) {
            Log.d(TAG, "Audio engine state: $state")
        }
    }

    private fun verifyVoice(pcmData: ShortArray): Float {
        val enrolled = enrolledEmbedding ?: return 1.0f // Bypass check if no voice enrolled
        val verifier = speakerVerifier ?: return 1.0f // Bypass check if JNI initialization failed
        if (pcmData.isEmpty()) return 0f
        return verifier.verifySpeaker(pcmData, enrolled)
    }

    // --- TextToSpeech OnInit ---
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
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
    }
}
