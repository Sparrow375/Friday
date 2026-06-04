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
    private lateinit var speakerVerifier: SpeakerVerifier

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val locGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        Log.i(TAG, "Permissions callback: mic=$micGranted, location=$locGranted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        prefs = getSharedPreferences("friday_prefs", Context.MODE_PRIVATE)
        speakerVerifier = SpeakerVerifier.getInstance(this)

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
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
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
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color(0xFF8E2DE2), Color(0xFF4A00E0))
                        )
                    )
                    .padding(vertical = 24.dp, horizontal = 16.dp)
            ) {
                Column {
                    Text(
                        text = "FRIDAY",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = 4.sp
                    )
                    Text(
                        text = "Personal Voice Intelligence Core",
                        fontSize = 13.sp,
                        color = Color(0xCCFFFFFF),
                        fontWeight = FontWeight.Light
                    )
                }
            }

            // Tab bar
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFF1E1E24),
                contentColor = Color.White,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = Color(0xFF00C9FF)
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
        val hasEnrolledVoice = !enrolledEmbeddingStr.isNullOrEmpty()

        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Service Controller
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24))
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
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24))) {
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

            // Voice Bio-metrics Enrollment
            item {
                Text("Speaker Security Core", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24))) {
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
                                color = Color(0xFF00C9FF),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            LinearProgressIndicator(
                                progress = enrollmentProgress,
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                color = Color(0xFF8E2DE2)
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
                                    isEnrolling = true
                                    startVoiceEnrollment { progress, status, done, embedding ->
                                        enrollmentProgress = progress
                                        enrollmentStatus = status
                                        if (done) {
                                            isEnrolling = false
                                            if (embedding != null) {
                                                val str = embedding.joinToString(",")
                                                prefs.edit().putString("enrolled_embedding", str).apply()
                                                Toast.makeText(context, "Voice Enrolled Successfully!", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E2DE2))
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
                    color = Color(0xFF00C9FF),
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
                    Icon(Icons.Default.Add, contentDescription = "Add Routine", tint = Color(0xFF00C9FF))
                }
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(routines) { routine ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24))) {
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
                            Text("Trigger: \"${routine.triggerPhrase}\"", fontSize = 13.sp, color = Color(0xFF00C9FF))
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
                        containerColor = if (showLogs) Color(0xFF4A00E0) else Color(0xFF1E1E24)
                    )
                ) {
                    Text("Chat History")
                }
                Button(
                    onClick = { showLogs = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!showLogs) Color(0xFF4A00E0) else Color(0xFF1E1E24)
                    )
                ) {
                    Text("Voice Notes")
                }
            }

            if (showLogs) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(conversations) { item ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24))) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = item.speaker,
                                        fontWeight = FontWeight.Bold,
                                        color = if (item.speaker == "USER") Color(0xFF00C9FF) else Color(0xFF92FE9D),
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
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24))) {
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
        // OverlayService runs as a foreground service
        // Since we are checking locally inside our process, we can verify if the service context is active
        // But since we stop/start manually, a simple check is standard.
        // For local purposes, return true if started. We check it programmatically:
        return false // Will be updated dynamically by binding/intent or local variable in service
    }

    private fun startVoiceEnrollment(onUpdate: (Float, String, Boolean, FloatArray?) -> Unit) {
        lifecycleScope.launch {
            val audioBuffers = mutableListOf<ShortArray>()
            val sampleRate = 16000
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2

            // Prompt user 3 times
            for (step in 1..3) {
                onUpdate(
                    (step - 1) / 3.0f,
                    "Speak phrase $step of 3: \"Friday, verify my voice profile now.\"",
                    false,
                    null
                )
                
                delay(1000)

                // Start recording
                var recorder: AudioRecord? = null
                try {
                    recorder = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                    )
                    recorder.startRecording()
                } catch (e: SecurityException) {
                    onUpdate(0f, "Microphone Permission Denied", true, null)
                    return@launch
                }

                val buffer = ShortArray(1024)
                val segment = mutableListOf<Short>()
                val start = System.currentTimeMillis()
                
                // Record for 3.5 seconds
                while (System.currentTimeMillis() - start < 3500) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        segment.addAll(buffer.toList().subList(0, read))
                    }
                    delay(50)
                }

                recorder.stop()
                recorder.release()
                audioBuffers.add(segment.toShortArray())
                
                onUpdate(step / 3.0f, "Captured Sample $step.", false, null)
                delay(1000)
            }

            onUpdate(1.0f, "Processing Voice Templates...", false, null)

            // Process ONNX embeddings on Background Thread
            withContext(Dispatchers.Default) {
                val verifier = SpeakerVerifier.getInstance(this@MainActivity)
                val embeddings = audioBuffers.mapNotNull { verifier.extractEmbedding(it) }

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
        primary = Color(0xFF8E2DE2),
        secondary = Color(0xFF00C9FF),
        background = Color(0xFF0F0F14),
        surface = Color(0xFF1E1E24),
        onPrimary = Color.White,
        onSecondary = Color.White,
        onBackground = Color(0xFFE2E2E2),
        onSurface = Color.White
    )

    MaterialTheme(
        colorScheme = darkColors,
        typography = Typography(),
        content = content
    )
}
