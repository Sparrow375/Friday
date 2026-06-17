package com.friday.assistant.ui.screens

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
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
import java.io.FileOutputStream

// Added imports for Chat Screen and speech helper
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.friday.assistant.intelligence.AgentCore
import com.friday.assistant.intelligence.MemoryManager
import com.friday.assistant.audio.SpeechToTextHelper
import com.friday.assistant.audio.PipelineState

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var modelManager: ModelManager
    private var speakerVerifier: SpeakerVerifier? = null
    private lateinit var agentCore: AgentCore
    private lateinit var memoryManager: MemoryManager
    private var activitySpeechToTextHelper: SpeechToTextHelper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "MainActivity onCreate")

        modelManager = ModelManager(this)
        speakerVerifier = SpeakerVerifier(this, modelManager)
        memoryManager = MemoryManager(this)
        agentCore = AgentCore(this, memoryManager)

        setContent {
            FridayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = ObsidianDark
                ) {
                    MainScreenContainer()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun DashboardScreen() {
        val context = LocalContext.current
        val scrollState = rememberScrollState()

        // Core Permission statuses
        var hasMicPermission by remember { mutableStateOf(checkPermission(Manifest.permission.RECORD_AUDIO)) }
        var hasNotificationPermission by remember { 
            mutableStateOf(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    checkPermission(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    true
                }
            )
        }
        var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
        var hasAssistantRole by remember { mutableStateOf(isDefaultAssistant()) }
        var hasWriteSettingsPermission by remember { mutableStateOf(Settings.System.canWrite(context)) }
        var assistantEnabled by remember {
            mutableStateOf(context.getSharedPreferences("friday_assistant_prefs", Context.MODE_PRIVATE).getBoolean("assistant_enabled", true))
        }

        var useLlm by remember { 
            mutableStateOf(context.getSharedPreferences("friday_model_prefs", Context.MODE_PRIVATE).getBoolean("use_llm", true)) 
        }
        var llmDownloading by remember { mutableStateOf(false) }
        var llmDownloadProgress by remember { mutableStateOf(0f) }
        var llmDownloadError by remember { mutableStateOf<String?>(null) }

        // Optional Permissions statuses
        var hasLocationPermission by remember { mutableStateOf(checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)) }
        var hasContactsPermission by remember { mutableStateOf(checkPermission(Manifest.permission.READ_CONTACTS)) }
        var hasPhonePermission by remember { mutableStateOf(checkPermission(Manifest.permission.CALL_PHONE)) }
        var hasDeviceAdmin by remember {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComp = android.content.ComponentName(context, com.friday.assistant.core.FridayDeviceAdminReceiver::class.java)
            mutableStateOf(dpm.isAdminActive(adminComp))
        }
        var hasDndPermission by remember {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            mutableStateOf(nm.isNotificationPolicyAccessGranted)
        }

        // Model statuses
        var whisperLoaded by remember { mutableStateOf(modelManager.isWhisperLoaded()) }
        var llmLoaded by remember { mutableStateOf(modelManager.isLlmLoaded()) }
        var speakerLoaded by remember { mutableStateOf(modelManager.isSpeakerLoaded()) }
        var wakeWordLoaded by remember { mutableStateOf(modelManager.isWakeWordLoaded()) }
        var nluLoaded by remember { mutableStateOf(modelManager.isNluLoaded()) }

        // Enrollment states
        var isEnrolled by remember { mutableStateOf(speakerVerifier?.isEnrolled() == true) }
        var enrollmentProgress by remember { mutableStateOf(0f) }
        var isEnrolling by remember { mutableStateOf(false) }
        var enrollmentStatusText by remember { mutableStateOf("") }

        // Settings inputs
        var customWakeWord by remember { mutableStateOf("") }
        var llmPathInput by remember { mutableStateOf(modelManager.getLlmModelPath()) }

        // Copy GGUF model states
        var copyingProgress by remember { mutableStateOf(-1f) }
        var copyingFileName by remember { mutableStateOf("") }

        // Refresh wake word default text on start
        LaunchedEffect(Unit) {
            val prefs = context.getSharedPreferences("friday_wakeword_prefs", Context.MODE_PRIVATE)
            customWakeWord = prefs.getString("custom_wakeword", "friday") ?: "friday"

            // Request normal permissions at startup
            val list = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(list.toTypedArray())
        }

        // Periodically refresh permission states
        LaunchedEffect(Unit) {
            while (true) {
                val micGranted = checkPermission(Manifest.permission.RECORD_AUDIO)
                hasMicPermission = micGranted
                hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    checkPermission(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    true
                }
                hasOverlayPermission = Settings.canDrawOverlays(context)
                hasAssistantRole = isDefaultAssistant()
                hasWriteSettingsPermission = Settings.System.canWrite(context)
                hasLocationPermission = checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                hasContactsPermission = checkPermission(Manifest.permission.READ_CONTACTS)
                hasPhonePermission = checkPermission(Manifest.permission.CALL_PHONE)

                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                val adminComp = android.content.ComponentName(context, com.friday.assistant.core.FridayDeviceAdminReceiver::class.java)
                hasDeviceAdmin = dpm.isAdminActive(adminComp)

                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                hasDndPermission = nm.isNotificationPolicyAccessGranted
                
                assistantEnabled = context.getSharedPreferences("friday_assistant_prefs", Context.MODE_PRIVATE).getBoolean("assistant_enabled", true)

                // Also update model loading status
                whisperLoaded = modelManager.isWhisperLoaded()
                llmLoaded = modelManager.isLlmLoaded()
                speakerLoaded = modelManager.isSpeakerLoaded()
                wakeWordLoaded = modelManager.isWakeWordLoaded()
                nluLoaded = modelManager.isNluLoaded()
                
                delay(1500)
            }
        }

        // Permission request launcher
        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val micGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: hasMicPermission
            hasMicPermission = micGranted
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                hasNotificationPermission = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: hasNotificationPermission
            }
            hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: hasLocationPermission
            hasContactsPermission = permissions[Manifest.permission.READ_CONTACTS] ?: hasContactsPermission
            hasPhonePermission = permissions[Manifest.permission.CALL_PHONE] ?: hasPhonePermission
        }

        // LLF GGUF File Picker launcher
        val filePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val contentResolver = context.contentResolver
                        var name = "qwen2.5-3b-instruct-q4_k_m.gguf"
                        var totalBytes = -1L
                        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIndex != -1 && cursor.moveToFirst()) {
                                name = cursor.getString(nameIndex)
                            }
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeIndex != -1 && cursor.moveToFirst()) {
                                totalBytes = cursor.getLong(sizeIndex)
                            }
                        }

                        withContext(Dispatchers.Main) {
                            copyingFileName = name
                            copyingProgress = 0f
                        }

                        val destDir = context.getExternalFilesDir("models") ?: context.filesDir
                        if (!destDir.exists()) {
                            destDir.mkdirs()
                        }
                        val destFile = File(destDir, name)
                        val tempFile = File(destDir, "$name.tmp")

                        contentResolver.openInputStream(uri)?.use { inputStream ->
                            FileOutputStream(tempFile).use { outputStream ->
                                val buffer = ByteArray(64 * 1024)
                                var bytesRead: Int
                                var bytesCopied = 0L
                                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                    outputStream.write(buffer, 0, bytesRead)
                                    bytesCopied += bytesRead
                                    if (totalBytes > 0) {
                                        val progress = bytesCopied.toFloat() / totalBytes
                                        withContext(Dispatchers.Main) {
                                            copyingProgress = progress
                                        }
                                    }
                                }
                            }
                        }

                        if (tempFile.exists()) {
                            if (destFile.exists()) {
                                destFile.delete()
                            }
                            if (tempFile.renameTo(destFile)) {
                                withContext(Dispatchers.Main) {
                                    modelManager.setLlmModelPath(destFile.absolutePath)
                                    llmLoaded = true
                                    llmPathInput = destFile.absolutePath
                                    copyingProgress = -1f
                                    Toast.makeText(context, "LLM Model copied successfully", Toast.LENGTH_SHORT).show()
                                    
                                    if (FridayService.instance != null) {
                                        FridayService.reloadModels()
                                    } else {
                                        Toast.makeText(context, "Model GGUF copied. Set Friday as default assistant to load it.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            } else {
                                throw Exception("Failed to rename temporary file to destination")
                            }
                        } else {
                            throw Exception("Temporary file was not created")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error copying GGUF file", e)
                        withContext(Dispatchers.Main) {
                            copyingProgress = -1f
                            Toast.makeText(context, "Failed to copy model: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Sleek Minimal Header
            Spacer(modifier = Modifier.height(16.dp))
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "Friday Logo",
                tint = NeonBlue,
                modifier = Modifier
                    .size(48.dp)
                    .background(NeonBlue.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
                    .padding(8.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Friday",
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "Offline Voice Assistant Dashboard",
                style = MaterialTheme.typography.bodyLarge,
                color = SilverText,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            // System Active Banner
            val allCorePermissionsGranted = hasMicPermission && hasOverlayPermission && hasAssistantRole
            val systemReady = allCorePermissionsGranted && (!useLlm || llmLoaded) && whisperLoaded

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (systemReady) GlowGreen.copy(alpha = 0.08f) else AlertRed.copy(alpha = 0.08f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (systemReady) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = "Status Icon",
                        tint = if (systemReady) GlowGreen else AlertRed,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (systemReady) "Friday is Active & Listening" else "Setup Required",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = if (systemReady) "Wake-word trigger: '$customWakeWord'" else "Resolve checklist items to activate the assistant",
                            color = SilverText,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SlateGray)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable Assistant",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Toggle background wake-word listening and overlays",
                            color = SilverText,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Switch(
                        checked = assistantEnabled,
                        onCheckedChange = { checked ->
                            assistantEnabled = checked
                            context.getSharedPreferences("friday_assistant_prefs", Context.MODE_PRIVATE)
                                .edit().putBoolean("assistant_enabled", checked).apply()
                            
                            val action = if (checked) FridayService.ACTION_RESUME_WAKEWORD else FridayService.ACTION_PAUSE_WAKEWORD
                            context.startService(Intent(context, FridayService::class.java).apply {
                                this.action = action
                            })
                            
                            Toast.makeText(context, if (checked) "Assistant Enabled" else "Assistant Disabled", Toast.LENGTH_SHORT).show()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NeonCyan,
                            checkedTrackColor = NeonBlue.copy(alpha = 0.5f)
                        )
                    )
                }
            }

            // Open Overlay button — visible when core microphone, overlay, and assistant permissions are granted
            if (hasMicPermission && hasOverlayPermission && hasAssistantRole) {
                Button(
                    onClick = {
                        val activeService = FridayService.instance
                        if (activeService != null) {
                            activeService.showOverlay()
                        } else {
                            Toast.makeText(context, "Please set Friday as your Default Assistant App in Settings first", Toast.LENGTH_LONG).show()
                            try {
                                val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to launch voice assistant settings", e)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp)
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Assistant Overlay", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            // Copying Progress indicator
            if (copyingProgress >= 0f) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SlateGray)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Copying model file...",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = copyingFileName,
                            color = SilverText,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        LinearProgressIndicator(
                            progress = copyingProgress,
                            color = NeonBlue,
                            trackColor = Color.DarkGray,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${(copyingProgress * 100).toInt()}%",
                            color = NeonCyan,
                            fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.align(Alignment.End)
                        )
                    }
                }
            }

            // 1. Core Integration Card
            DashboardCard(title = "Core Integrations & Permissions") {
                ChecklistItem(
                    title = "Microphone Access",
                    description = "Used to capture wake word and speech input",
                    status = hasMicPermission,
                    onAction = { permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }
                )
                Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 12.dp))
                
                ChecklistItem(
                    title = "Notification Service",
                    description = "Required to show active listening background notification",
                    status = hasNotificationPermission,
                    onAction = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        }
                    }
                )
                Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 12.dp))
                
                ChecklistItem(
                    title = "Overlay Bubble (Alert Window)",
                    description = "Displays the bottom interactive voice waveform",
                    status = hasOverlayPermission,
                    onAction = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    }
                )
                Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 12.dp))
                
                ChecklistItem(
                    title = "Default Digital Assistant",
                    description = "Allows Friday to run in the background and capture wake words",
                    status = hasAssistantRole,
                    onAction = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? android.app.role.RoleManager
                            if (roleManager != null) {
                                val intent = roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_ASSISTANT)
                                try {
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to request role directly", e)
                                    val fallback = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                                    context.startActivity(fallback)
                                }
                            }
                        } else {
                            val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                            context.startActivity(intent)
                        }
                    }
                )
                Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 12.dp))
                
                ChecklistItem(
                    title = "Modify System Settings",
                    description = "Allows Friday to control brightness and volumes",
                    status = hasWriteSettingsPermission,
                    onAction = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    }
                )
                Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 12.dp))

                ChecklistItem(
                    title = "Device Administrator",
                    description = "Enables programmatic screen locking functionality",
                    status = hasDeviceAdmin,
                    onAction = {
                        val adminComp = android.content.ComponentName(context, com.friday.assistant.core.FridayDeviceAdminReceiver::class.java)
                        val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                            putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComp)
                            putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Friday requires Admin access to lock your screen when requested.")
                        }
                        startActivity(intent)
                    }
                )
                Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 12.dp))

                ChecklistItem(
                    title = "Do Not Disturb (DND) Access",
                    description = "Allows Friday to toggle Do Not Disturb mode",
                    status = hasDndPermission,
                    onAction = {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        startActivity(intent)
                    }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 2. Local Models Card
            DashboardCard(title = "On-Device AI Brain Models") {
                ModelRow(
                    name = "Speech Recognition (Whisper)",
                    status = whisperLoaded,
                    details = "Auto-configured ggml-tiny-q5_1 model"
                )
                Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 12.dp))
                
                ModelRow(
                    name = "Voice Authenticator (ONNX)",
                    status = speakerLoaded,
                    details = "Auto-configured speaker verification profile"
                )
                Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 12.dp))
                
                ModelRow(
                    name = "Offline Wake Word (ONNX)",
                    status = wakeWordLoaded,
                    details = "Custom 1D CNN 'Friday' wake-word model"
                )
                Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 12.dp))

                ModelRow(
                    name = "NLU Intent Classifier (ONNX)",
                    status = nluLoaded,
                    details = "Custom sequence classifier model"
                )
                Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 12.dp))

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Reasoning Engine (Qwen 1.5B GGUF)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )
                            Text(
                                text = if (llmLoaded) "Loaded: ${llmPathInput.substringAfterLast("/")}" else "File missing, download Qwen 1.5B or select manually",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (llmLoaded) GlowGreen else AlertRed
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (llmLoaded) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Reasoning Brain (LLM)",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Switch(
                                checked = useLlm,
                                onCheckedChange = { checked ->
                                    useLlm = checked
                                    context.getSharedPreferences("friday_model_prefs", Context.MODE_PRIVATE)
                                        .edit().putBoolean("use_llm", checked).apply()
                                    Toast.makeText(context, if (checked) "LLM Reasoning Enabled" else "LLM Reasoning Disabled (Command Mode)", Toast.LENGTH_SHORT).show()
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = NeonCyan,
                                    checkedTrackColor = NeonBlue.copy(alpha = 0.5f)
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    if (!llmLoaded && !llmDownloading) {
                        Button(
                            onClick = {
                                llmDownloading = true
                                llmDownloadError = null
                                lifecycleScope.launch(Dispatchers.IO) {
                                    modelManager.downloadLlmModel(
                                        onProgress = { progress ->
                                            lifecycleScope.launch(Dispatchers.Main) {
                                                llmDownloadProgress = progress
                                            }
                                        },
                                        onFinished = { success, errorOrPath ->
                                            lifecycleScope.launch(Dispatchers.Main) {
                                                llmDownloading = false
                                                if (success && errorOrPath != null) {
                                                    llmLoaded = true
                                                    llmPathInput = errorOrPath
                                                    Toast.makeText(context, "Qwen GGUF downloaded successfully!", Toast.LENGTH_LONG).show()
                                                    if (FridayService.instance != null) {
                                                        FridayService.reloadModels()
                                                    }
                                                } else {
                                                    llmDownloadError = errorOrPath ?: "Unknown download error"
                                                    Toast.makeText(context, "Download failed: $llmDownloadError", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                    )
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "Download")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Download Qwen 1.5B (~1.0 GB)", color = Color.White)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (llmDownloading) {
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(
                                text = "Downloading Qwen-2.5-1.5B GGUF...",
                                color = NeonCyan,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = llmDownloadProgress,
                                color = NeonBlue,
                                trackColor = Color.DarkGray,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${(llmDownloadProgress * 100).toInt()}%",
                                color = NeonCyan,
                                fontWeight = FontWeight.Medium,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                    }

                    if (llmDownloadError != null) {
                        Text(
                            text = "Error: $llmDownloadError",
                            color = AlertRed,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    Button(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        colors = ButtonDefaults.buttonColors(containerColor = SlateGray),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Select GGUF")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (llmLoaded) "Change Custom GGUF File" else "Choose Local GGUF File", color = Color.White)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 3. Speaker Voice Profile
            DashboardCard(title = "Speaker Verification Enrollment") {
                Text(
                    text = "Teach Friday your unique voice. Unrecognized speaker requests will be rejected.",
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
                            text = "Voice Signature Active",
                            color = GlowGreen,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(
                            onClick = {
                                speakerVerifier?.clearEnrollment()
                                isEnrolled = false
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = AlertRed)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Profile")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Reset Profile")
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
                                    if (!hasMicPermission) {
                                        permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                    } else {
                                        isEnrolling = true
                                        startVoiceEnrollment(
                                            onProgress = { progress, text ->
                                                enrollmentProgress = progress
                                                enrollmentStatusText = text
                                            },
                                            onFinished = { success ->
                                                isEnrolling = false
                                                if (success) {
                                                    isEnrolled = true
                                                }
                                            }
                                        )
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Enroll My Voice", color = Color.White)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 4. Optional Permissions Card
            DashboardCard(title = "Optional Tool Permissions") {
                OptionalPermissionRow(
                    name = "Contacts Directory",
                    description = "Enables Friday to read contacts and look up phone numbers",
                    status = hasContactsPermission,
                    onGrant = { permissionLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS)) }
                )
                Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 12.dp))
                
                OptionalPermissionRow(
                    name = "Phone Dialer",
                    description = "Allows Friday to draft calls and open dialer automatically",
                    status = hasPhonePermission,
                    onGrant = { permissionLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE)) }
                )
                Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 12.dp))
                
                OptionalPermissionRow(
                    name = "Location Services",
                    description = "Powers location lookup tools to fetch nearby details",
                    status = hasLocationPermission,
                    onGrant = { permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 5. Settings Card
            DashboardCard(title = "Preferences") {
                OutlinedTextField(
                    value = customWakeWord,
                    onValueChange = { customWakeWord = it },
                    label = { Text("Wake Word", color = SilverText) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonBlue,
                        unfocusedBorderColor = Color.DarkGray,
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
                            context.getSharedPreferences("friday_wakeword_prefs", Context.MODE_PRIVATE)
                                .edit().putString("custom_wakeword", trigger).apply()
                            Toast.makeText(context, "Settings Applied", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Settings", color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 6. Log Viewer Card
            var logsText by remember { mutableStateOf("No logs loaded.") }
            LaunchedEffect(Unit) {
                logsText = readLogs()
            }

            DashboardCard(title = "System Debug Logs") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = logsText,
                        style = TextStyle(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color.LightGray
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(
                        onClick = { logsText = readLogs() },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Refresh", color = Color.White)
                    }
                    Button(
                        onClick = {
                            com.friday.assistant.core.FridayLogger.clearLogs()
                            logsText = readLogs()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AlertRed),
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Clear Logs", color = Color.White)
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }

    private fun readLogs(): String {
        val file = com.friday.assistant.core.FridayLogger.getLogFile()
        return if (file != null && file.exists()) {
            try {
                val lines = file.readLines()
                if (lines.size > 200) {
                    lines.takeLast(200).joinToString("\n")
                } else {
                    lines.joinToString("\n")
                }
            } catch (e: Exception) {
                "Error reading logs: ${e.localizedMessage}"
            }
        } else {
            "Log file does not exist yet."
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
                    color = CyberBorder,
                    shape = RoundedCornerShape(16.dp)
                ),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SlateGray)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = NeonCyan,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                content()
            }
        }
    }

    @Composable
    fun ChecklistItem(
        title: String,
        description: String,
        status: Boolean,
        onAction: () -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelMedium,
                    color = SilverText
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (status) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Status Active",
                    tint = GlowGreen,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(containerColor = AlertRed)
                ) {
                    Text("Grant", color = Color.White)
                }
            }
        }
    }

    @Composable
    fun OptionalPermissionRow(
        name: String,
        description: String,
        status: Boolean,
        onGrant: () -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelMedium,
                    color = SilverText
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (status) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Granted",
                    tint = GlowGreen,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                TextButton(
                    onClick = onGrant,
                    colors = ButtonDefaults.textButtonColors(contentColor = NeonCyan)
                ) {
                    Text("Grant")
                }
            }
        }
    }

    @Composable
    fun ModelRow(
        name: String,
        status: Boolean,
        details: String
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
                Text(
                    text = details,
                    style = MaterialTheme.typography.labelMedium,
                    color = SilverText
                )
            }
            Icon(
                imageVector = if (status) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = if (status) "Found" else "Not Configured",
                tint = if (status) GlowGreen else AlertRed,
                modifier = Modifier.size(20.dp)
            )
        }
    }

    private fun checkPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun isDefaultAssistant(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(Context.ROLE_SERVICE) as? RoleManager
            roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        } else {
            val setting = Settings.Secure.getString(contentResolver, "assistant")
            setting != null && setting.contains(packageName)
        }
    }

    private fun startVoiceEnrollment(onProgress: (Float, String) -> Unit, onFinished: (Boolean) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            var success = false
            try {
                val verifier = speakerVerifier ?: return@launch

                delay(600) // Wait for any active audio captures to release

                withContext(Dispatchers.Main) {
                    onProgress(0.05f, "Initializing Enrollment Session...")
                }
                verifier.startEnrollmentSession()

                // 2. Try to ensure Whisper is loaded locally so we can transcribe what the user says
                val whisperEngine = FridayApplication.whisperEngine
                if (!whisperEngine.isModelLoaded()) {
                    val whisperPath = modelManager.getWhisperModelPath()
                    if (File(whisperPath).exists()) {
                        whisperEngine.loadModel(whisperPath)
                    }
                }

                val samplesToCollect = 5
                var successCount = 0

                for (i in 1..samplesToCollect) {
                    withContext(Dispatchers.Main) {
                        onProgress((i - 1).toFloat() / samplesToCollect, "Say: 'Hey Friday' (Sample $i of $samplesToCollect)")
                    }

                    // Capture 2.5s of audio using a dedicated AudioRecord (not the shared manager)
                    val audioData = captureEnrollmentSample()
                    if (audioData == null) {
                        withContext(Dispatchers.Main) {
                            onProgress(0f, "Microphone capture failed. Is mic permission granted?")
                        }
                        return@launch
                    }

                    val added = verifier.addEnrollmentSample(audioData)
                    if (added) {
                        successCount++
                    }

                    // Run local Whisper transcription to show user what we captured
                    var transcriptionText = "Heard: silence or noise"
                    try {
                        val floatData = FloatArray(audioData.size) { idx -> audioData[idx].toFloat() / 32768.0f }
                        if (whisperEngine.isModelLoaded()) {
                            val transcript = whisperEngine.transcribe(floatData).trim()
                            if (transcript.isNotEmpty()) {
                                transcriptionText = "Heard: \"$transcript\""
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to run preview transcription", e)
                    }

                    withContext(Dispatchers.Main) {
                        onProgress(
                            i.toFloat() / samplesToCollect,
                            "Sample $i of $samplesToCollect added ($transcriptionText)"
                        )
                    }
                    delay(1200) // Let user prepare and clean the screen log
                }

                withContext(Dispatchers.Main) {
                    onProgress(0.9f, "Finalizing voice profile...")
                }

                success = successCount > 0 && verifier.finalizeEnrollment()
                withContext(Dispatchers.Main) {
                    if (success) {
                        onProgress(1.0f, "Voice Enrollment Complete!")
                    } else {
                        onProgress(0f, "Voice Enrollment Failed.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in startVoiceEnrollment", e)
            } finally {
                delay(500)
                withContext(Dispatchers.Main) {
                    onFinished(success)
                }
            }
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun captureEnrollmentSample(): ShortArray? {
        // Collect 2.5 seconds of 16kHz mono audio = 40,000 samples
        val sampleRate = 16000
        val targetSamples = sampleRate * 25 / 10  // 2.5 seconds
        val channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO
        val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
        val minBuf = android.media.AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        if (minBuf <= 0) {
            Log.e(TAG, "AudioRecord getMinBufferSize failed: $minBuf")
            return null
        }

        val bufferSize = minBuf * 2
        val record = try {
            android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create enrollment AudioRecord", e)
            return null
        }

        if (record.state != android.media.AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Enrollment AudioRecord not initialized (mic may still be locked)")
            record.release()
            return null
        }

        return try {
            record.startRecording()
            val frameSize = sampleRate / 10  // 100ms frames = 1600 samples
            val collected = ArrayList<Short>(targetSamples)
            val frameBuffer = ShortArray(frameSize)
            val deadlineMs = System.currentTimeMillis() + 8000L // 8-second absolute timeout

            while (collected.size < targetSamples) {
                if (System.currentTimeMillis() > deadlineMs) {
                    Log.w(TAG, "Enrollment capture timed out after 8 seconds")
                    break
                }
                val read = record.read(frameBuffer, 0, frameSize)
                if (read > 0) {
                    for (i in 0 until read) {
                        if (collected.size < targetSamples) collected.add(frameBuffer[i])
                    }
                } else if (read < 0) {
                    Log.e(TAG, "AudioRecord read error during enrollment: $read")
                    break
                }
                // Yield to prevent blocking the dispatcher completely
                kotlinx.coroutines.yield()
            }

            if (collected.size < targetSamples / 2) {
                Log.w(TAG, "Only captured ${collected.size} of $targetSamples samples – aborting")
                null
            } else {
                collected.take(targetSamples).toShortArray()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during enrollment capture", e)
            null
        } finally {
            try {
                record.stop()
                record.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing enrollment AudioRecord", e)
            }
        }
    }

    // ==========================================
    // Added for Chat Screen and Navigation Tab
    // ==========================================

    enum class AppScreen {
        DASHBOARD,
        CHAT
    }

    data class ChatMessage(
        val text: String,
        val isUser: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    private fun getActivitySpeechHelper(
        onTranscript: (String) -> Unit,
        onFinal: (String) -> Unit,
        onState: (PipelineState) -> Unit
    ): SpeechToTextHelper {
        var helper = activitySpeechToTextHelper
        if (helper == null) {
            helper = SpeechToTextHelper(
                context = this,
                onTranscriptUpdate = onTranscript,
                onFinalResult = onFinal,
                onRmsUpdate = { },
                onStateChanged = onState
            )
            activitySpeechToTextHelper = helper
        }
        return helper
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreenContainer() {
        var currentScreen by remember { mutableStateOf(AppScreen.DASHBOARD) }

        Scaffold(
            bottomBar = {
                NavigationBar(
                    containerColor = GlassObsidian,
                    modifier = Modifier.border(
                        width = 1.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(CyberBorder, Color.Transparent, CyberBorder)
                        ),
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    )
                ) {
                    NavigationBarItem(
                        selected = currentScreen == AppScreen.DASHBOARD,
                        onClick = { currentScreen = AppScreen.DASHBOARD },
                        icon = { Icon(Icons.Default.Dashboard, contentDescription = "Dashboard") },
                        label = { Text("Dashboard") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NeonCyan,
                            selectedTextColor = NeonCyan,
                            unselectedIconColor = SilverText,
                            unselectedTextColor = SilverText,
                            indicatorColor = Color.Transparent
                        )
                    )
                    NavigationBarItem(
                        selected = currentScreen == AppScreen.CHAT,
                        onClick = { currentScreen = AppScreen.CHAT },
                        icon = { Icon(Icons.Default.Chat, contentDescription = "Assistant") },
                        label = { Text("Assistant") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NeonCyan,
                            selectedTextColor = NeonCyan,
                            unselectedIconColor = SilverText,
                            unselectedTextColor = SilverText,
                            indicatorColor = Color.Transparent
                        )
                    )
                }
            },
            containerColor = ObsidianDark
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                when (currentScreen) {
                    AppScreen.DASHBOARD -> DashboardScreen()
                    AppScreen.CHAT -> ChatScreen()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ChatScreen() {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        var textInput by remember { mutableStateOf("") }
        var isThinking by remember { mutableStateOf(false) }
        var thinkingStatus by remember { mutableStateOf("") }
        
        val chatMessages = remember { mutableStateListOf<ChatMessage>().apply {
            if (isEmpty()) {
                add(ChatMessage("Hello, I am Friday. How can I help you today?", isUser = false))
            }
        }}
        
        var isRecording by remember { mutableStateOf(false) }
        var partialTranscript by remember { mutableStateOf("") }
        
        val listState = rememberLazyListState()
        
        // Pause/resume background wake-word listening on lifecycle
        DisposableEffect(Unit) {
            val intent = Intent(context, FridayService::class.java).apply {
                action = FridayService.ACTION_PAUSE_WAKEWORD
            }
            try { context.startService(intent) } catch (e: Exception) { Log.e(TAG, "Failed to pause wake word", e) }
            
            onDispose {
                val intent = Intent(context, FridayService::class.java).apply {
                    action = FridayService.ACTION_RESUME_WAKEWORD
                }
                try { context.startService(intent) } catch (e: Exception) { Log.e(TAG, "Failed to resume wake word", e) }
            }
        }
        
        LaunchedEffect(chatMessages.size) {
            if (chatMessages.isNotEmpty()) {
                listState.animateScrollToItem(chatMessages.size - 1)
            }
        }
        
        val speechHelper = remember {
            getActivitySpeechHelper(
                onTranscript = { text ->
                    partialTranscript = text
                },
                onFinal = { finalResult ->
                    if (finalResult.isNotBlank()) {
                        chatMessages.add(ChatMessage(finalResult, isUser = true))
                        
                        coroutineScope.launch {
                            isThinking = true
                            thinkingStatus = "Thinking..."
                            
                            val statusJob = coroutineScope.launch {
                                agentCore.agentStatusFlow.collect { status ->
                                    thinkingStatus = status
                                }
                            }
                            
                            try {
                                val reply = agentCore.processQuery(finalResult)
                                chatMessages.add(ChatMessage(reply, isUser = false))
                            } catch (e: Exception) {
                                chatMessages.add(ChatMessage("Error: ${e.localizedMessage}", isUser = false))
                            } finally {
                                statusJob.cancel()
                                isThinking = false
                            }
                        }
                    }
                    isRecording = false
                    partialTranscript = ""
                },
                onState = { state ->
                    isRecording = (state == PipelineState.LISTENING)
                }
            )
        }
        
        val handleSend = {
            val query = textInput.trim()
            if (query.isNotEmpty() && !isThinking) {
                chatMessages.add(ChatMessage(query, isUser = true))
                textInput = ""
                coroutineScope.launch {
                    isThinking = true
                    thinkingStatus = "Thinking..."
                    
                    val statusJob = coroutineScope.launch {
                        agentCore.agentStatusFlow.collect { status ->
                            thinkingStatus = status
                        }
                    }
                    
                    try {
                        val reply = agentCore.processQuery(query)
                        chatMessages.add(ChatMessage(reply, isUser = false))
                    } catch (e: Exception) {
                        chatMessages.add(ChatMessage("Error: ${e.localizedMessage}", isUser = false))
                    } finally {
                        statusJob.cancel()
                        isThinking = false
                    }
                }
            }
        }
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ObsidianDark)
                .padding(16.dp)
        ) {
            // Header bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Chat,
                    contentDescription = null,
                    tint = NeonBlue,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Friday Chat Assistant",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            
            // Message log
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                items(chatMessages) { message ->
                    val isUser = message.isUser
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
                    ) {
                        Surface(
                            shape = if (isUser) {
                                RoundedCornerShape(16.dp, 16.dp, 0.dp, 16.dp)
                            } else {
                                RoundedCornerShape(16.dp, 16.dp, 16.dp, 0.dp)
                            },
                            color = Color.Transparent,
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .border(
                                    width = 1.dp,
                                    brush = Brush.linearGradient(
                                        colors = if (isUser) {
                                            listOf(CyberBorder, CyberBorder)
                                        } else {
                                            listOf(Color.Transparent, CyberBorder)
                                        }
                                    ),
                                    shape = if (isUser) {
                                        RoundedCornerShape(16.dp, 16.dp, 0.dp, 16.dp)
                                    } else {
                                        RoundedCornerShape(16.dp, 16.dp, 16.dp, 0.dp)
                                    }
                                )
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        brush = if (isUser) {
                                            Brush.linearGradient(colors = listOf(NeonBlue, NeonCyan))
                                        } else {
                                            Brush.linearGradient(colors = listOf(SlateGray, GlassObsidian))
                                        }
                                    )
                                    .padding(14.dp)
                            ) {
                                Text(
                                    text = message.text,
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    lineHeight = 20.sp
                                )
                            }
                        }
                    }
                }
                
                if (isThinking) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = NeonCyan,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = thinkingStatus,
                                color = SilverText,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
            
            // Live partial voice transcript view
            if (isRecording) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .border(1.dp, AlertRed.copy(alpha = 0.5f), RoundedCornerShape(14.dp)),
                    colors = CardDefaults.cardColors(containerColor = GlassObsidian)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(AlertRed, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (partialTranscript.isEmpty()) "Listening..." else "\"$partialTranscript\"",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                speechHelper.stopListening()
                                isRecording = false
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = SilverText, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            
            // Text input box / Microphone controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text("Ask Friday...", color = SilverText.copy(alpha = 0.5f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonBlue,
                        unfocusedBorderColor = CyberBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Send
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSend = { handleSend() }
                    )
                )
                
                // Mic Button
                IconButton(
                    onClick = {
                        if (isRecording) {
                            speechHelper.stopListening()
                        } else {
                            speechHelper.startListening()
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .background(if (isRecording) AlertRed else NeonBlue, RoundedCornerShape(12.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Voice Input",
                        tint = Color.White
                    )
                }
                
                if (textInput.isNotBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = { handleSend() },
                        modifier = Modifier
                            .size(48.dp)
                            .background(NeonCyan, RoundedCornerShape(12.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send Message",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}
