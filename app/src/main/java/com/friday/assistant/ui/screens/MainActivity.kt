package com.friday.assistant.ui.screens

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
import java.io.FileOutputStream

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
        var hasAccessibilityPermission by remember { mutableStateOf(isAccessibilityServiceEnabled()) }
        var hasWriteSettingsPermission by remember { mutableStateOf(Settings.System.canWrite(context)) }

        // Optional Permissions statuses
        var hasLocationPermission by remember { mutableStateOf(checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)) }
        var hasContactsPermission by remember { mutableStateOf(checkPermission(Manifest.permission.READ_CONTACTS)) }
        var hasPhonePermission by remember { mutableStateOf(checkPermission(Manifest.permission.CALL_PHONE)) }

        // Model statuses
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

        // Copy GGUF model states
        var copyingProgress by remember { mutableStateOf(-1f) }
        var copyingFileName by remember { mutableStateOf("") }

        // Refresh wake word default text on start
        LaunchedEffect(Unit) {
            val prefs = context.getSharedPreferences("friday_wakeword_prefs", Context.MODE_PRIVATE)
            customWakeWord = prefs.getString("custom_wakeword", "friday") ?: "friday"
        }

        // Periodically refresh permission states
        LaunchedEffect(Unit) {
            while (true) {
                hasMicPermission = checkPermission(Manifest.permission.RECORD_AUDIO)
                hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    checkPermission(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    true
                }
                hasOverlayPermission = Settings.canDrawOverlays(context)
                hasAccessibilityPermission = isAccessibilityServiceEnabled()
                hasWriteSettingsPermission = Settings.System.canWrite(context)
                hasLocationPermission = checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                hasContactsPermission = checkPermission(Manifest.permission.READ_CONTACTS)
                hasPhonePermission = checkPermission(Manifest.permission.CALL_PHONE)
                
                // Also update model loading status
                whisperLoaded = modelManager.isWhisperLoaded()
                llmLoaded = modelManager.isLlmLoaded()
                speakerLoaded = modelManager.isSpeakerLoaded()
                
                delay(1500)
            }
        }

        // Permission request launcher
        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            hasMicPermission = permissions[Manifest.permission.RECORD_AUDIO] ?: hasMicPermission
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
                        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIndex != -1 && cursor.moveToFirst()) {
                                name = cursor.getString(nameIndex)
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

                        val pfd = contentResolver.openAssetFileDescriptor(uri, "r")
                        val totalBytes = pfd?.length ?: -1L
                        pfd?.close()

                        contentResolver.openInputStream(uri)?.use { inputStream ->
                            FileOutputStream(destFile).use { outputStream ->
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

                        withContext(Dispatchers.Main) {
                            modelManager.setLlmModelPath(destFile.absolutePath)
                            llmLoaded = true
                            llmPathInput = destFile.absolutePath
                            copyingProgress = -1f
                            Toast.makeText(context, "LLM Model copied successfully", Toast.LENGTH_SHORT).show()
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
            val allCorePermissionsGranted = hasMicPermission && hasOverlayPermission && hasAccessibilityPermission
            val systemReady = allCorePermissionsGranted && llmLoaded && whisperLoaded
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
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
                    Column {
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
                    title = "Accessibility Automation",
                    description = "Powers click, type, and screen automation features",
                    status = hasAccessibilityPermission,
                    onAction = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        startActivity(intent)
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Reasoning Engine (Qwen GGUF)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                        Text(
                            text = if (llmLoaded) "Loaded: ${llmPathInput.substringAfterLast("/")}" else "File missing, select GGUF file to initialize",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (llmLoaded) GlowGreen else AlertRed
                        )
                    }
                    Button(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonBlue)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Select GGUF")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (llmLoaded) "Change" else "Choose File", color = Color.White)
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
                                        startVoiceEnrollment { progress, text ->
                                            enrollmentProgress = progress
                                            enrollmentStatusText = text
                                            isEnrolling = progress < 1f
                                            if (progress >= 1f) {
                                                isEnrolled = true
                                            }
                                        }
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

            Spacer(modifier = Modifier.height(40.dp))
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
