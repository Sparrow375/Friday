package com.friday.assistant.ui.screens

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.friday.assistant.audio.AudioCaptureManager
import com.friday.assistant.audio.SpeakerVerifier
import com.friday.assistant.core.FridayApplication
import com.friday.assistant.core.ModelManager
import com.friday.assistant.ui.FridayService
import com.friday.assistant.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var modelManager: ModelManager
    private var speakerVerifier: SpeakerVerifier? = null
    private val audioCaptureManager = AudioCaptureManager(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "MainActivity onCreate")

        modelManager = ModelManager(this)
        speakerVerifier = SpeakerVerifier(this, modelManager)

        setContent {
            FridayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = ObsidianDark
                ) {
                    DashboardScreen()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun DashboardScreen() {
        val context = LocalContext.current
        val scrollState = rememberScrollState()

        // Permissions states
        var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
        var hasAccessibilityPermission by remember { mutableStateOf(isAccessibilityServiceEnabled()) }
        var hasWriteSettingsPermission by remember { mutableStateOf(Settings.System.canWrite(context)) }

        // Model states
        var whisperLoaded by remember { mutableStateOf(modelManager.isWhisperLoaded()) }
        var llmLoaded by remember { mutableStateOf(modelManager.isLlmLoaded()) }
        var speakerLoaded by remember { mutableStateOf(modelManager.isSpeakerLoaded()) }

        // Enrollment states
        var isEnrolled by remember { mutableStateOf(speakerVerifier?.isEnrolled() == true) }
        var enrollmentProgress by remember { mutableStateOf(0f) }
        var isEnrolling by remember { mutableStateOf(false) }
        var enrollmentStatusText by remember { mutableStateOf("") }

        // Settings inputs
        var customWakeWord by remember { mutableStateOf("") }
        var llmPathInput by remember { mutableStateOf(modelManager.getLlmModelPath()) }
        var whisperPathInput by remember { mutableStateOf(modelManager.getWhisperModelPath()) }

        // Periodically refresh permission states
        LaunchedEffect(Unit) {
            while (true) {
                hasOverlayPermission = Settings.canDrawOverlays(context)
                hasAccessibilityPermission = isAccessibilityServiceEnabled()
                hasWriteSettingsPermission = Settings.System.canWrite(context)
                delay(1500)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Cyber Title
            Text(
                text = "FRIDAY TERMINAL",
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.ExtraBold,
                color = NeonBlue,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                text = "Offline Intelligent Assistant Config",
                style = MaterialTheme.typography.labelMedium,
                color = SilverText.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 32.dp)
            )

            // 1. System Integrations (Permissions) Card
            DashboardCard(title = "SYSTEM INTEGRATION") {
                PermissionItem(
                    title = "Overlay Bubble (SYSTEM_ALERT)",
                    isEnabled = hasOverlayPermission,
                    onRequest = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    }
                )
                Divider(color = CyberBorder, modifier = Modifier.padding(vertical = 8.dp))
                PermissionItem(
                    title = "UI Automation (ACCESSIBILITY)",
                    isEnabled = hasAccessibilityPermission,
                    onRequest = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        startActivity(intent)
                    }
                )
                Divider(color = CyberBorder, modifier = Modifier.padding(vertical = 8.dp))
                PermissionItem(
                    title = "Write Settings (BRIGHTNESS)",
                    isEnabled = hasWriteSettingsPermission,
                    onRequest = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 2. Local Model Status Card
            DashboardCard(title = "ON-DEVICE BRAIN MODELS") {
                ModelStatusItem(name = "Speech Recognition (Whisper)", isLoaded = whisperLoaded, path = whisperPathInput)
                Divider(color = CyberBorder, modifier = Modifier.padding(vertical = 8.dp))
                ModelStatusItem(name = "Reasoning Engine (Qwen GGUF)", isLoaded = llmLoaded, path = llmPathInput)
                Divider(color = CyberBorder, modifier = Modifier.padding(vertical = 8.dp))
                ModelStatusItem(name = "Voice Authenticator (ONNX)", isLoaded = speakerLoaded, path = modelManager.getSpeakerModelPath())
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 3. Speaker Enrollment Card
            DashboardCard(title = "SPEAKER VERIFICATION PROFILE") {
                Text(
                    text = "Enrolling your voice prevents Friday from responding to other speakers.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = SilverText,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                if (isEnrolled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Speaker Profile Active",
                            color = GlowGreen,
                            fontWeight = FontWeight.Bold
                        )
                        Button(
                            onClick = {
                                speakerVerifier?.clearEnrollment()
                                isEnrolled = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AlertRed)
                        ) {
                            Text("Delete Profile", color = Color.White)
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (isEnrolling) enrollmentStatusText else "No speaker profile created",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isEnrolling) NeonCyan else AlertRed,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        if (isEnrolling) {
                            LinearProgressIndicator(
                                progress = enrollmentProgress,
                                color = NeonBlue,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                        } else {
                            Button(
                                onClick = {
                                    startVoiceEnrollment { progress, text ->
                                        enrollmentProgress = progress
                                        enrollmentStatusText = text
                                        isEnrolling = progress < 1f
                                        if (progress >= 1f) {
                                            isEnrolled = true
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Start Voice Enrollment", color = Color.Black)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 4. Configuration Card
            DashboardCard(title = "SETTINGS") {
                OutlinedTextField(
                    value = customWakeWord,
                    onValueChange = { customWakeWord = it },
                    label = { Text("Custom Wake Word", color = NeonCyan) },
                    placeholder = { Text("e.g. jarvis, friday", color = SilverText.copy(alpha = 0.5f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonBlue,
                        unfocusedBorderColor = CyberBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = llmPathInput,
                    onValueChange = { llmPathInput = it },
                    label = { Text("Qwen GGUF Path", color = NeonCyan) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonBlue,
                        unfocusedBorderColor = CyberBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = whisperPathInput,
                    onValueChange = { whisperPathInput = it },
                    label = { Text("Whisper BIN Path", color = NeonCyan) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonBlue,
                        unfocusedBorderColor = CyberBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (customWakeWord.isNotBlank()) {
                            val trigger = customWakeWord.trim().lowercase()
                            modelManager.context.getSharedPreferences("friday_wakeword_prefs", Context.MODE_PRIVATE)
                                .edit().putString("custom_wakeword", trigger).apply()
                        }
                        modelManager.setLlmModelPath(llmPathInput)
                        modelManager.setWhisperModelPath(whisperPathInput)
                        
                        // Validate paths
                        whisperLoaded = File(whisperPathInput).exists()
                        llmLoaded = File(llmPathInput).exists()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Apply & Save Settings", color = Color.Black)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    @Composable
    fun DashboardCard(
        title: String,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(CyberBorder, Color.Transparent)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SlateGray.copy(alpha = 0.4f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = NeonBlue,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                content()
            }
        }
    }

    @Composable
    fun PermissionItem(
        title: String,
        isEnabled: Boolean,
        onRequest: () -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, color = Color.White)
            }
            if (isEnabled) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Permission granted",
                    tint = GlowGreen,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Button(
                    onClick = onRequest,
                    colors = ButtonDefaults.buttonColors(containerColor = AlertRed)
                ) {
                    Text("Enable", color = Color.White)
                }
            }
        }
    }

    @Composable
    fun ModelStatusItem(
        name: String,
        isLoaded: Boolean,
        path: String
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = name, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isLoaded) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = if (isLoaded) "Loaded" else "Missing",
                        tint = if (isLoaded) GlowGreen else AlertRed,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isLoaded) "Found" else "Missing",
                        color = if (isLoaded) GlowGreen else AlertRed,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                text = "Path: $path",
                style = MaterialTheme.typography.labelMedium,
                color = SilverText.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        for (service in enabledServices) {
            if (service.resolveInfo.serviceInfo.packageName == packageName &&
                service.resolveInfo.serviceInfo.name == FridayService::class.java.name
            ) {
                return true
            }
        }
        return false
    }

    private fun startVoiceEnrollment(onProgress: (Float, String) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            val verifier = speakerVerifier ?: return@launch
            withContext(Dispatchers.Main) {
                onProgress(0.1f, "Initializing Enrollment Session...")
            }
            verifier.startEnrollmentSession()

            // Capture manager start
            audioCaptureManager.startCapture()

            val samplesToCollect = 5
            for (i in 1..samplesToCollect) {
                withContext(Dispatchers.Main) {
                    onProgress((i - 1).toFloat() / samplesToCollect, "Say: 'Hey Friday' (Sample $i of $samplesToCollect)")
                }

                // Buffer speech clip (approx 2.5s)
                val audioData = captureEnrollmentSample()
                verifier.addEnrollmentSample(audioData)
                delay(500) // cool-down delay between records
            }

            withContext(Dispatchers.Main) {
                onProgress(0.9f, "Finalizing voice profile...")
            }
            audioCaptureManager.stopCapture()
            
            val success = verifier.finalizeEnrollment()
            withContext(Dispatchers.Main) {
                if (success) {
                    onProgress(1.0f, "Voice Enrollment Complete!")
                } else {
                    onProgress(0f, "Voice Enrollment Failed.")
                }
            }
        }
    }

    private suspend fun captureEnrollmentSample(): ShortArray {
        // Collect 2.5 seconds of 16kHz audio = 40,000 samples
        val targetSize = 16000 * 25 / 10
        val collected = mutableListOf<Short>()
        
        val listener = object : AudioCaptureManager.AudioFrameListener {
            override fun onAudioFrame(pcmData: ShortArray) {
                if (collected.size < targetSize) {
                    pcmData.forEach { collected.add(it) }
                }
            }
        }

        audioCaptureManager.registerListener(listener)
        while (collected.size < targetSize) {
            delay(100)
        }
        audioCaptureManager.unregisterListener(listener)

        return collected.take(targetSize).toShortArray()
    }
}
