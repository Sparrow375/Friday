package com.friday.assistant.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.friday.assistant.audio.SpeakerVerifier
import com.friday.assistant.classifier.LocalLlmRunner
import com.friday.assistant.core.FridayApplication
import com.friday.assistant.core.ConversationEntity
import com.friday.assistant.core.NoteEntity
import com.friday.assistant.core.RoutineEntity
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private var speakerVerifier: SpeakerVerifier? = null

    // Compose state properties for manual model loading and progress tracking
    private var isImportingLlm by mutableStateOf(false)
    private var isImportingSpeaker by mutableStateOf(false)
    private var llmLoaded by mutableStateOf(false)
    private var onnxLoaded by mutableStateOf(false)
    private var llmName by mutableStateOf("No model loaded")

    // Downloader state properties
    private var downloadProgressLlm by mutableStateOf(-1f)
    private var downloadStatusLlm by mutableStateOf("")
    private var downloadProgressSpeaker by mutableStateOf(-1f)
    private var downloadStatusSpeaker by mutableStateOf("")

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val locGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        Log.i(TAG, "Permissions callback: mic=$micGranted, location=$locGranted")
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        name = cursor.getString(nameIndex)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.path
            val cut = name?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                name = name?.substring(cut + 1)
            }
        }
        return name
    }

    private val selectLlmLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            isImportingLlm = true
            lifecycleScope.launch(Dispatchers.IO) {
                val filename = getFileName(uri) ?: "gemma.bin"
                val file = copyUriToInternalStorage(uri, filename)
                withContext(Dispatchers.Main) {
                    isImportingLlm = false
                    if (file != null) {
                        prefs.edit().putString("selected_llm_path", file.absolutePath).apply()
                        val r = LocalLlmRunner.getInstance(this@MainActivity)
                        r?.reloadModel()
                        llmLoaded = r?.isModelLoaded() ?: false
                        llmName = r?.getLoadedModelName() ?: "No model loaded"
                        Toast.makeText(this@MainActivity, "LLM Model imported and loaded!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Failed to import LLM model file.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else {
            isImportingLlm = false
        }
    }

    private val selectSpeakerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            isImportingSpeaker = true
            lifecycleScope.launch(Dispatchers.IO) {
                val filename = getFileName(uri) ?: "speaker_verification.onnx"
                val file = copyUriToInternalStorage(uri, filename)
                withContext(Dispatchers.Main) {
                    isImportingSpeaker = false
                    if (file != null) {
                        prefs.edit().putString("selected_speaker_path", file.absolutePath).apply()
                        speakerVerifier = SpeakerVerifier.getInstance(this@MainActivity)
                        onnxLoaded = speakerVerifier?.isModelLoaded() ?: false
                        Toast.makeText(this@MainActivity, "Speaker model imported and loaded!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Failed to import Speaker model file.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else {
            isImportingSpeaker = false
        }
    }

    private fun copyUriToInternalStorage(uri: Uri, destFileName: String): File? {
        return try {
            val destFile = File(filesDir, destFileName)
            contentResolver.openInputStream(uri)?.use { inputStream ->
                destFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            destFile
        } catch (e: Exception) {
            Log.e(TAG, "Error copying model file to internal storage", e)
            null
        }
    }

    private fun downloadModelFile(
        urlStr: String,
        destFileName: String,
        onProgress: (Float, String) -> Unit,
        onComplete: (File?) -> Unit
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            var input: java.io.InputStream? = null
            var output: java.io.FileOutputStream? = null
            var connection: java.net.HttpURLConnection? = null
            try {
                val url = java.net.URL(urlStr)
                connection = url.openConnection() as java.net.HttpURLConnection
                connection.connect()

                if (connection.responseCode != java.net.HttpURLConnection.HTTP_OK) {
                    withContext(Dispatchers.Main) {
                        onProgress(-1f, "HTTP Error: ${connection.responseCode}")
                    }
                    onComplete(null)
                    return@launch
                }

                val fileLength = connection.contentLength
                input = connection.inputStream
                val destFile = File(filesDir, destFileName)
                output = java.io.FileOutputStream(destFile)

                val data = ByteArray(4096)
                var total = 0L
                var count: Int
                var lastProgressUpdate = 0L

                while (input.read(data).also { count = it } != -1) {
                    total += count
                    output.write(data, 0, count)
                    if (fileLength > 0) {
                        val progress = total.toFloat() / fileLength
                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate > 100) {
                            lastProgressUpdate = now
                            val pct = (progress * 100).toInt()
                            withContext(Dispatchers.Main) {
                                onProgress(progress, "Downloading: $pct%")
                            }
                        }
                    }
                }
                
                withContext(Dispatchers.Main) {
                    onProgress(-1f, "Download complete!")
                }
                onComplete(destFile)
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading model", e)
                withContext(Dispatchers.Main) {
                    onProgress(-1f, "Error: ${e.localizedMessage}")
                }
                onComplete(null)
            } finally {
                try {
                    output?.close()
                    input?.close()
                } catch (e: Exception) {}
                connection?.disconnect()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        prefs = getSharedPreferences("friday_prefs", Context.MODE_PRIVATE)
        try {
            speakerVerifier = SpeakerVerifier.getInstance(this)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize SpeakerVerifier (ONNX Runtime JNI linkage issue)", t)
        }

        // Initialize model load states on start
        val r = LocalLlmRunner.getInstance(this)
        llmLoaded = r?.isModelLoaded() ?: false
        llmName = r?.getLoadedModelName() ?: "No model loaded"
        onnxLoaded = speakerVerifier?.isModelLoaded() ?: false

        // Seed default routines if none exist
        seedDefaultRoutines()

        // Request basic permissions
        checkAndRequestPermissions()

        setContent {
            FridayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F0F14) // Rich dark blue-gray
                ) {
                    AssistantSettingsScreen()
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun seedDefaultRoutines() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = (application as FridayApplication).fridayDao
            val existing = dao.getAllRoutines().firstOrNull()
            if (existing.isNullOrEmpty()) {
                val gson = Gson()
                
                // Good morning routine
                val morningActions = listOf(
                    mapOf("type" to "TTS", "value" to "Good morning, Avaneesh. System diagnostic normal. Flashlight is off. Music is ready."),
                    mapOf("type" to "VOLUME", "value" to "40"),
                    mapOf("type" to "DEEP_LINK_APP", "value" to "spotify", "extraParams" to mapOf("query" to "lofi beats"))
                )
                dao.insertRoutine(
                    RoutineEntity(
                        name = "Good Morning",
                        triggerPhrase = "good morning",
                        commandsJson = gson.toJson(morningActions)
                    )
                )

                // Work mode
                val workActions = listOf(
                    mapOf("type" to "TTS", "value" to "Work mode active. Silencing device and opening Chrome."),
                    mapOf("type" to "DND", "value" to "on"),
                    mapOf("type" to "VOLUME", "value" to "0"),
                    mapOf("type" to "LAUNCH_APP", "value" to "com.android.chrome", "extraParams" to mapOf("appName" to "Chrome"))
                )
                dao.insertRoutine(
                    RoutineEntity(
                        name = "Work Mode",
                        triggerPhrase = "work mode",
                        commandsJson = gson.toJson(workActions)
                    )
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun AssistantSettingsScreen() {
        var selectedTab by remember { mutableIntStateOf(0) }
        val tabs = listOf("Dashboard", "Routines", "Logs & Notes")

        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF15151A))
                    .padding(vertical = 20.dp, horizontal = 16.dp)
            ) {
                Column {
                    Text(
                        text = "FRIDAY",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = 6.sp
                    )
                    Text(
                        text = "OFFLINE VOICE INTELLIGENCE",
                        fontSize = 11.sp,
                        color = Color(0xFF8E909A),
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.5.sp
                    )
                }
            }

            // Tab bar
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFF15151A),
                contentColor = Color.White,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = Color.White
                    )
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontWeight = FontWeight.SemiBold) }
                    )
                }
            }

            // Content
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .padding(16.dp)
            ) {
                when (selectedTab) {
                    0 -> DashboardTab()
                    1 -> RoutinesTab()
                    2 -> LogsTab()
                }
            }
        }
    }

    @Composable
    fun DashboardTab() {
        val context = LocalContext.current
        var overlayAllowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
        var writeSettingsAllowed by remember { mutableStateOf(Settings.System.canWrite(context)) }
        var dndAllowed by remember {
            mutableStateOf(
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .isNotificationPolicyAccessGranted
            )
        }
        var serviceRunning by remember { mutableStateOf(isServiceRunning()) }
        var isEnrolling by remember { mutableStateOf(false) }
        var enrollmentProgress by remember { mutableFloatStateOf(0f) }
        var enrollmentStatus by remember { mutableStateOf("Ready to enroll") }

        val enrolledEmbeddingStr = prefs.getString("enrolled_embedding", null)
        var hasEnrolledVoice by remember { mutableStateOf(!enrolledEmbeddingStr.isNullOrEmpty()) }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Service Controller
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF15151A))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Friday Assistant Service", fontWeight = FontWeight.Bold, color = Color.White)
                            Text(
                                if (serviceRunning) "Status: Running background overlay" else "Status: Inactive",
                                fontSize = 13.sp,
                                color = if (serviceRunning) Color(0xFF92FE9D) else Color(0xFFFF4B2B)
                            )
                        }
                        Switch(
                            checked = serviceRunning,
                            onCheckedChange = { start ->
                                if (start) {
                                    if (overlayAllowed) {
                                        context.startForegroundService(Intent(context, OverlayService::class.java))
                                        serviceRunning = true
                                    } else {
                                        Toast.makeText(context, "Please enable Overlay Permission first", Toast.LENGTH_LONG).show()
                                    }
                                } else {
                                    context.stopService(Intent(context, OverlayService::class.java))
                                    serviceRunning = false
                                }
                            }
                        )
                    }
                }
            }

            // Permissions Checklist
            item {
                Text("System Integrations Checklist", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF15151A))) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PermissionRow(
                            title = "System Overlay (Floating UI)",
                            granted = overlayAllowed,
                            onRequest = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            }
                        )
                        PermissionRow(
                            title = "System Settings (Brightness & Vol)",
                            granted = writeSettingsAllowed,
                            onRequest = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            }
                        )
                        PermissionRow(
                            title = "Do Not Disturb Toggle",
                            granted = dndAllowed,
                            onRequest = {
                                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                                context.startActivity(intent)
                            }
                        )
                    }
                }
            }

            // Local Models Configuration & Diagnostic
            item {
                Text("Local Models Configuration", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF15151A))) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (llmLoaded) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (llmLoaded) Color(0xFF92FE9D) else Color(0xFFFF4B2B),
                                modifier = Modifier.padding(end = 12.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text("On-Device LLM (Generative AI)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                                Text(
                                    text = if (llmLoaded) "Loaded: $llmName" else "Missing model (.bin task format)",
                                    fontSize = 12.sp,
                                    color = Color.LightGray
                                )
                                Text(
                                    text = "Requires converted .bin format (not raw .gguf)",
                                    fontSize = 10.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                        
                        if (downloadProgressLlm >= 0f) {
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text("LLM: $downloadStatusLlm", fontSize = 11.sp, color = Color.LightGray)
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = downloadProgressLlm,
                                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                    color = Color.White
                                )
                            }
                        }

                        HorizontalDivider(color = Color(0x1EFFFFFF))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (onnxLoaded) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (onnxLoaded) Color(0xFF92FE9D) else Color(0xFFFF4B2B),
                                modifier = Modifier.padding(end = 12.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Speaker Recognition Model (ONNX)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                                Text(
                                    text = if (onnxLoaded) "Loaded: ONNX verification model" else "Missing speaker_verification.onnx",
                                    fontSize = 12.sp,
                                    color = Color.LightGray
                                )
                                Text(
                                    text = "Required for secure offline voice lock",
                                    fontSize = 10.sp,
                                    color = Color.Gray
                                )
                            }
                        }

                        if (downloadProgressSpeaker >= 0f) {
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text("Speaker: $downloadStatusSpeaker", fontSize = 11.sp, color = Color.LightGray)
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = downloadProgressSpeaker,
                                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                    color = Color.White
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = {
                                    if (!isImportingLlm) {
                                        selectLlmLauncher.launch(arrayOf("*/*"))
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C35)),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isImportingLlm) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                } else {
                                    Text("Select LLM (.bin/.gguf)", fontSize = 11.sp, color = Color.White)
                                }
                            }
                            Button(
                                onClick = {
                                    if (!isImportingSpeaker) {
                                        selectSpeakerLauncher.launch(arrayOf("*/*"))
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C35)),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isImportingSpeaker) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                } else {
                                    Text("Select Speaker (.onnx)", fontSize = 11.sp, color = Color.White)
                                }
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = {
                                    if (downloadProgressLlm < 0f) {
                                        downloadStatusLlm = "Starting download..."
                                        downloadProgressLlm = 0f
                                        downloadModelFile(
                                            "https://archive.org/download/gemma-2b-it-gpu-gemma-2b-it-cpu/gemma-2b-it-cpu.bin",
                                            "gemma.bin"
                                        , { progress, status ->
                                            downloadProgressLlm = progress
                                            downloadStatusLlm = status
                                        }) { file ->
                                            if (file != null) {
                                                prefs.edit().putString("selected_llm_path", file.absolutePath).apply()
                                                val r = LocalLlmRunner.getInstance(context)
                                                r?.reloadModel()
                                                llmLoaded = r?.isModelLoaded() ?: false
                                                llmName = r?.getLoadedModelName() ?: "No model loaded"
                                                Toast.makeText(context, "LLM Model downloaded and loaded!", Toast.LENGTH_LONG).show()
                                            } else {
                                                Toast.makeText(context, "Failed to download LLM model.", Toast.LENGTH_LONG).show()
                                            }
                                            downloadProgressLlm = -1f
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                                modifier = Modifier.weight(1f),
                                enabled = (downloadProgressLlm < 0f)
                            ) {
                                Text("Auto Download LLM (1.3GB)", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                            
                            Button(
                                onClick = {
                                    if (downloadProgressSpeaker < 0f) {
                                        downloadStatusSpeaker = "Starting download..."
                                        downloadProgressSpeaker = 0f
                                        downloadModelFile(
                                            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_3dspeaker_16k.onnx",
                                            "speaker_verification.onnx"
                                        , { progress, status ->
                                            downloadProgressSpeaker = progress
                                            downloadStatusSpeaker = status
                                        }) { file ->
                                            if (file != null) {
                                                prefs.edit().putString("selected_speaker_path", file.absolutePath).apply()
                                                speakerVerifier = SpeakerVerifier.getInstance(context)
                                                onnxLoaded = speakerVerifier?.isModelLoaded() ?: false
                                                Toast.makeText(context, "Speaker Model downloaded and loaded!", Toast.LENGTH_LONG).show()
                                            } else {
                                                Toast.makeText(context, "Failed to download Speaker model.", Toast.LENGTH_LONG).show()
                                            }
                                            downloadProgressSpeaker = -1f
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                                modifier = Modifier.weight(1f),
                                enabled = (downloadProgressSpeaker < 0f)
                            ) {
                                Text("Auto Download Speaker (17MB)", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Button(
                            onClick = {
                                try {
                                    val r = LocalLlmRunner.getInstance(context)
                                    r?.reloadModel()
                                    speakerVerifier = SpeakerVerifier.getInstance(context)
                                    val isLlmActive = r?.isModelLoaded() ?: false
                                    val isOnnxActive = speakerVerifier?.isModelLoaded() ?: false
                                    llmLoaded = isLlmActive
                                    llmName = r?.getLoadedModelName() ?: "Missing or JNI Error"
                                    onnxLoaded = isOnnxActive
                                    
                                    val msg = "Sync Complete. LLM: ${if (isLlmActive) "Loaded" else "Not Found"}, ONNX: ${if (isOnnxActive) "Loaded" else "Not Found"}"
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                } catch (t: Throwable) {
                                    Toast.makeText(context, "Error syncing: ${t.localizedMessage}", Toast.LENGTH_LONG).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Scan & Sync Local Models", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Voice Bio-metrics Enrollment
            item {
                Text("Speaker Security Core", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF15151A))) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Personal Voice Lock", fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            text = if (hasEnrolledVoice) "Voice Profile Loaded: Secure mode available." 
                                   else "No profile. Friday will respond to any voice.",
                            fontSize = 13.sp,
                            color = if (hasEnrolledVoice) Color(0xFF92FE9D) else Color(0xCCFFFFFF),
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        if (isEnrolling) {
                            Text(
                                "Read: \"Friday, verify my voice profile now.\"",
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            LinearProgressIndicator(
                                progress = enrollmentProgress,
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                color = Color.White
                            )
                            Text(
                                enrollmentStatus,
                                fontSize = 12.sp,
                                color = Color.LightGray,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        } else {
                            Button(
                                onClick = {
                                    if (!onnxLoaded) {
                                        Toast.makeText(context, "Speaker ONNX model is not loaded. Please select it first.", Toast.LENGTH_LONG).show()
                                        return@Button
                                    }
                                    isEnrolling = true
                                    startVoiceEnrollment { progress, status, done, embedding ->
                                        enrollmentProgress = progress
                                        enrollmentStatus = status
                                        if (done) {
                                            isEnrolling = false
                                            if (embedding != null) {
                                                val str = embedding.joinToString(",")
                                                prefs.edit().putString("enrolled_embedding", str).apply()
                                                hasEnrolledVoice = true
                                                Toast.makeText(context, "Voice Enrolled Successfully!", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                            ) {
                                Text(if (hasEnrolledVoice) "Retrain Voice Profile" else "Enroll My Voice")
                            }
                        }
                    }
                }
            }
            
            // Refresh checklist on resume
            item {
                LaunchedEffect(Unit) {
                    while(true) {
                        delay(2000)
                        overlayAllowed = Settings.canDrawOverlays(context)
                        writeSettingsAllowed = Settings.System.canWrite(context)
                        dndAllowed = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                            .isNotificationPolicyAccessGranted
                        serviceRunning = isServiceRunning()
                    }
                }
            }
        }
    }

    @Composable
    fun PermissionRow(title: String, granted: Boolean, onRequest: () -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (granted) Color(0xFF92FE9D) else Color(0xFFFF4B2B),
                modifier = Modifier.padding(end = 12.dp)
            )
            Text(title, color = Color.White, modifier = Modifier.weight(1f), fontSize = 14.sp)
            if (!granted) {
                Text(
                    "GRANT",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { onRequest() }
                )
            }
        }
    }

    @Composable
    fun RoutinesTab() {
        val dao = (application as FridayApplication).fridayDao
        val routinesFlow = remember { dao.getAllRoutines() }
        val routines by routinesFlow.collectAsState(initial = emptyList())

        var showDialog by remember { mutableStateOf(false) }
        var name by remember { mutableStateOf("") }
        var phrase by remember { mutableStateOf("") }
        var speechResponse by remember { mutableStateOf("") }

        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Automation Routines", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 18.sp)
                IconButton(onClick = { showDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Routine", tint = Color.White)
                }
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(routines) { routine ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF15151A))) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(routine.name, fontWeight = FontWeight.Bold, color = Color.White)
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = Color(0xFFFF4B2B),
                                    modifier = Modifier.clickable {
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            dao.deleteRoutine(routine.id)
                                        }
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Trigger: \"${routine.triggerPhrase}\"", fontSize = 13.sp, color = Color.LightGray)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Actions JSON: ${routine.commandsJson}",
                                fontSize = 11.sp,
                                color = Color.Gray,
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }

        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text("Create Routine") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Routine Name (e.g. Bedtime)") }
                        )
                        OutlinedTextField(
                            value = phrase,
                            onValueChange = { phrase = it },
                            label = { Text("Voice Trigger (e.g. good night)") }
                        )
                        OutlinedTextField(
                            value = speechResponse,
                            onValueChange = { speechResponse = it },
                            label = { Text("Voice Response text") }
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (name.isNotEmpty() && phrase.isNotEmpty()) {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val actionsList = listOf(
                                        mapOf("type" to "TTS", "value" to speechResponse)
                                    )
                                    dao.insertRoutine(
                                        RoutineEntity(
                                            name = name,
                                            triggerPhrase = phrase.lowercase().trim(),
                                            commandsJson = Gson().toJson(actionsList)
                                        )
                                    )
                                }
                                showDialog = false
                                name = ""
                                phrase = ""
                                speechResponse = ""
                            }
                        }
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }

    @Composable
    fun LogsTab() {
        val dao = (application as FridayApplication).fridayDao
        val conversations by dao.getRecentConversations(20).collectAsState(initial = emptyList())
        val notes by dao.getAllNotes().collectAsState(initial = emptyList())

        var showLogs by remember { mutableStateOf(true) }

        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                Button(
                    onClick = { showLogs = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (showLogs) Color.White else Color(0xFF15151A),
                        contentColor = if (showLogs) Color.Black else Color.White
                    )
                ) {
                    Text("Chat History")
                }
                Button(
                    onClick = { showLogs = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!showLogs) Color.White else Color(0xFF15151A),
                        contentColor = if (!showLogs) Color.Black else Color.White
                    )
                ) {
                    Text("Voice Notes")
                }
            }

            if (showLogs) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(conversations) { item ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF15151A))) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = item.speaker,
                                        fontWeight = FontWeight.Bold,
                                        color = if (item.speaker == "USER") Color.White else Color(0xFF8E909A),
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = android.text.format.DateFormat.format("hh:mm a", item.timestamp).toString(),
                                        fontSize = 10.sp,
                                        color = Color.Gray
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(item.message, color = Color.White, fontSize = 14.sp)
                            }
                        }
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(notes) { note ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF15151A))) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = android.text.format.DateFormat.format("MMM dd, hh:mm a", note.timestamp).toString(),
                                        fontSize = 11.sp,
                                        color = Color.Gray
                                    )
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = Color(0xFFFF4B2B),
                                        modifier = Modifier.clickable {
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                dao.deleteNote(note.id)
                                            }
                                        }
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(note.content, color = Color.White, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun isServiceRunning(): Boolean {
        return OverlayService.isRunning
    }

    private fun startVoiceEnrollment(onUpdate: (Float, String, Boolean, FloatArray?) -> Unit) {
        lifecycleScope.launch {
            val audioBuffers = mutableListOf<ShortArray>()
            val sampleRate = 16000

            // Prompt user 3 times
            for (step in 1..3) {
                onUpdate(
                    (step - 1) / 3.0f,
                    "Speak phrase $step of 3: \"Friday, verify my voice profile now.\"",
                    false,
                    null
                )
                
                delay(1500) // Give user time to read

                onUpdate(
                    (step - 1) / 3.0f,
                    "Recording phrase $step of 3...",
                    false,
                    null
                )

                val segment = withContext(Dispatchers.IO) {
                    val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
                    var recorder: AudioRecord? = null
                    try {
                        recorder = AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            sampleRate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize
                        )
                        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                            Log.e(TAG, "AudioRecord state not initialized")
                            return@withContext null
                        }
                        recorder.startRecording()

                        val buffer = ShortArray(1024)
                        val audioData = mutableListOf<Short>()
                        val startTime = System.currentTimeMillis()

                        // Record for 3.5 seconds
                        while (System.currentTimeMillis() - startTime < 3500) {
                            val read = recorder.read(buffer, 0, buffer.size)
                            if (read > 0) {
                                for (i in 0 until read) {
                                    audioData.add(buffer[i])
                                }
                            } else if (read < 0) {
                                Log.e(TAG, "AudioRecord read error during enrollment: $read")
                                break
                            }
                        }

                        recorder.stop()
                        recorder.release()
                        audioData.toShortArray()
                    } catch (e: Exception) {
                        Log.e(TAG, "AudioRecord exception during voice enrollment", e)
                        recorder?.release()
                        null
                    }
                }

                if (segment == null || segment.isEmpty()) {
                    onUpdate(0f, "Error: Audio recording failed or was empty.", true, null)
                    return@launch
                }

                audioBuffers.add(segment)
                onUpdate(step / 3.0f, "Captured Sample $step.", false, null)
                delay(1000)
            }

            onUpdate(1.0f, "Processing Voice Templates...", false, null)

            // Process ONNX embeddings on Background Thread
            withContext(Dispatchers.Default) {
                try {
                    val verifier = SpeakerVerifier.getInstance(this@MainActivity)
                    val embeddings = audioBuffers.mapNotNull { verifier?.extractEmbedding(it) }

                    if (embeddings.size < 2) {
                        withContext(Dispatchers.Main) {
                            onUpdate(1.0f, "Failed: Embeddings extraction failed. ONNX Model missing?", true, null)
                        }
                        return@withContext
                    }

                    // Average the embeddings to make a robust template
                    val vectorSize = embeddings[0].size
                    val averageEmbedding = FloatArray(vectorSize)
                    for (i in 0 until vectorSize) {
                        var sum = 0f
                        for (emb in embeddings) {
                            sum += emb[i]
                        }
                        averageEmbedding[i] = sum / embeddings.size
                    }

                    withContext(Dispatchers.Main) {
                        onUpdate(1.0f, "Verification core trained successfully.", true, averageEmbedding)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Speaker verifier JNI extract error", t)
                    withContext(Dispatchers.Main) {
                        onUpdate(1.0f, "Failed: Speaker verifier JNI / linkage error.", true, null)
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

// --- Preview & Custom Theme for Premium Look ---

@Composable
fun FridayTheme(content: @Composable () -> Unit) {
    val darkColors = darkColorScheme(
        primary = Color(0xFFFFFFFF),
        secondary = Color(0xFF8E909A),
        background = Color(0xFF0A0A0C),
        surface = Color(0xFF15151A),
        onPrimary = Color.Black,
        onSecondary = Color.White,
        onBackground = Color(0xFFE4E4E9),
        onSurface = Color.White
    )

    MaterialTheme(
        colorScheme = darkColors,
        typography = Typography(),
        content = content
    )
}
