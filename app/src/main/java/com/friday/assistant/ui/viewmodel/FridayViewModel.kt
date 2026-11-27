package com.friday.assistant.ui.viewmodel

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.friday.assistant.audio.PipelineState
import com.friday.assistant.audio.SpeechToTextHelper
import com.friday.assistant.ble.FridayBleWearableManager
import com.friday.assistant.core.AccessibilityHelper
import com.friday.assistant.core.FridayApplication
import com.friday.assistant.core.FridayLogger
import com.friday.assistant.core.ModelManager
import com.friday.assistant.core.db.BriefItemEntity
import com.friday.assistant.core.db.MemoryEntity
import com.friday.assistant.core.db.NoteEntity
import com.friday.assistant.core.db.ReminderEntity
import com.friday.assistant.core.db.RoutineEntity
import com.friday.assistant.intelligence.ReminderScheduler
import com.friday.assistant.intelligence.brief.BriefScheduler
import com.friday.assistant.intelligence.brief.ProactiveDigestWorker
import com.friday.assistant.tools.ToolRegistrar
import com.friday.assistant.tools.calendar.CalendarEventItem
import com.friday.assistant.tools.calendar.CalendarTool
import com.friday.assistant.tools.search.SportsPrefs
import com.friday.assistant.tools.search.SportsScoreCard
import com.friday.assistant.tools.search.SportsScoreTool
import com.friday.assistant.ui.AppNotificationItem
import com.friday.assistant.ui.FridayService
import com.friday.assistant.ui.NotificationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class ChatMessageItem(
    val id: Long = System.nanoTime(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

data class SystemPermissionsState(
    val hasMic: Boolean = false,
    val hasNotifications: Boolean = true,
    val hasOverlay: Boolean = false,
    val hasAssistantRole: Boolean = false,
    val hasWriteSettings: Boolean = false,
    val hasDnd: Boolean = false,
    val hasAccessibility: Boolean = false,
    val hasCalendar: Boolean = false,
    val hasContacts: Boolean = false,
    val hasPhone: Boolean = false,
    val hasLocation: Boolean = false,
    val hasNotificationListener: Boolean = false
) {
    val coreReady: Boolean get() = hasMic && hasOverlay && hasAssistantRole
}

data class ModelsStatusState(
    val whisperLoaded: Boolean = false,
    val nluLoaded: Boolean = false,
    val llmLoaded: Boolean = false,
    val llmPath: String = "",
    val llmDownloading: Boolean = false,
    val llmDownloadProgress: Float = 0f,
    val llmDownloadError: String? = null,
    val copyingFileName: String = "",
    val copyingProgress: Float = -1f
)

data class AssistantPrefsState(
    val assistantEnabled: Boolean = true,
    val wakeWordEnabled: Boolean = false,
    val bargeInEnabled: Boolean = true,
    val gestureActivationEnabled: Boolean = true,
    val gestureTriggerMode: String = "volume_down_double",
    val hapticFeedbackEnabled: Boolean = true,
    val audioChimeEnabled: Boolean = true,
    val bleWearableEnabled: Boolean = true,
    val useLlm: Boolean = true,
    val digestEnabled: Boolean = true
)

class FridayViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Context = application.applicationContext
    private val dao = FridayApplication.database.dao()
    private val modelManager = ModelManager(appContext)
    private val bleManager = FridayBleWearableManager.getInstance(appContext)

    init {
        ToolRegistrar.registerAll(appContext, FridayApplication.memoryManager)
    }

    // Room Reactive Flows
    val activeReminders: StateFlow<List<ReminderEntity>> = dao.getActiveRemindersFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allReminders: StateFlow<List<ReminderEntity>> = dao.getAllRemindersFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val notes: StateFlow<List<NoteEntity>> = dao.getAllNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val routines: StateFlow<List<RoutineEntity>> = dao.getAllRoutines()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val semanticMemories: StateFlow<List<MemoryEntity>> = dao.getMemoriesByLayer("SEMANTIC")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val episodicMemories: StateFlow<List<MemoryEntity>> = dao.getEpisodicMemoryHistory(40)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val newsDigest: StateFlow<List<BriefItemEntity>> = dao.getNewBriefItemsFlow()
        .map { it.take(3) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Notifications & BLE
    val notifications: StateFlow<List<AppNotificationItem>> = NotificationService.notificationsFlow
    val notificationListenerConnected: StateFlow<Boolean> = NotificationService.isConnectedFlow
    val bleConnectionState: StateFlow<FridayBleWearableManager.ConnectionState> = bleManager.connectionState
    val bleDeviceName: StateFlow<String?> = bleManager.deviceName
    val bleBatteryPercent: StateFlow<Int?> = bleManager.batteryPercent
    val bleBatteryMillivolts: StateFlow<Int?> = bleManager.batteryMillivolts

    // Dashboard Live Data
    private val _sportsCards = MutableStateFlow<List<SportsScoreCard>>(emptyList())
    val sportsCards: StateFlow<List<SportsScoreCard>> = _sportsCards.asStateFlow()

    private val _isLoadingSports = MutableStateFlow(false)
    val isLoadingSports: StateFlow<Boolean> = _isLoadingSports.asStateFlow()

    private val _todayEvents = MutableStateFlow<List<CalendarEventItem>>(emptyList())
    val todayEvents: StateFlow<List<CalendarEventItem>> = _todayEvents.asStateFlow()

    // Sports Teams
    private val _cricketTeams = MutableStateFlow(SportsPrefs.getCricketTeams(appContext))
    val cricketTeams: StateFlow<List<String>> = _cricketTeams.asStateFlow()

    private val _footballTeams = MutableStateFlow(SportsPrefs.getFootballTeams(appContext))
    val footballTeams: StateFlow<List<String>> = _footballTeams.asStateFlow()

    // Permissions, Models, Prefs
    private val _permissions = MutableStateFlow(readPermissionsSnapshot())
    val permissions: StateFlow<SystemPermissionsState> = _permissions.asStateFlow()

    private val _models = MutableStateFlow(readModelsSnapshot())
    val models: StateFlow<ModelsStatusState> = _models.asStateFlow()

    private val _prefs = MutableStateFlow(readPrefsSnapshot())
    val prefs: StateFlow<AssistantPrefsState> = _prefs.asStateFlow()

    // Assistant Chat
    private val _chatMessages = MutableStateFlow(
        listOf(
            ChatMessageItem(
                text = "Ready. Ask by voice, tap a suggestion below, or type a command.",
                isUser = false
            )
        )
    )
    val chatMessages: StateFlow<List<ChatMessageItem>> = _chatMessages.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    private val _thinkingStatus = MutableStateFlow("")
    val thinkingStatus: StateFlow<String> = _thinkingStatus.asStateFlow()

    private val _isRecordingChat = MutableStateFlow(false)
    val isRecordingChat: StateFlow<Boolean> = _isRecordingChat.asStateFlow()

    private val _partialTranscriptChat = MutableStateFlow("")
    val partialTranscriptChat: StateFlow<String> = _partialTranscriptChat.asStateFlow()

    private var chatSpeechHelper: SpeechToTextHelper? = null

    init {
        refreshSystemStatus()
        refreshDashboardFeeds(forceSports = false)
        if (_prefs.value.bleWearableEnabled && bleManager.hasPermissions()) {
            bleManager.setEnabled(true)
        }
    }

    fun refreshSystemStatus() {
        _permissions.value = readPermissionsSnapshot()
        _models.update {
            it.copy(
                whisperLoaded = modelManager.isWhisperLoaded(),
                nluLoaded = modelManager.isNluLoaded(),
                llmLoaded = modelManager.isLlmLoaded(),
                llmPath = modelManager.getLlmModelPath()
            )
        }
        _cricketTeams.value = SportsPrefs.getCricketTeams(appContext)
        _footballTeams.value = SportsPrefs.getFootballTeams(appContext)
        refreshTodayCalendar()
    }

    fun refreshDashboardFeeds(forceSports: Boolean = false) {
        refreshTodayCalendar()
        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingSports.value = true
            try {
                val cards = SportsScoreTool(appContext).fetchDashboardCards(forceRefresh = forceSports)
                _sportsCards.value = cards
            } catch (e: Exception) {
                FridayLogger.w("FridayViewModel", "Sports fetch failed: ${e.message}")
            } finally {
                _isLoadingSports.value = false
            }
        }
        if (forceSports) {
            BriefScheduler.triggerOneTimeCrawl(appContext)
        }
    }

    private fun refreshTodayCalendar() {
        viewModelScope.launch(Dispatchers.IO) {
            _todayEvents.value = CalendarTool.getUpcomingEvents(appContext, limit = 6, onlyToday = false)
        }
    }

    // Chat actions
    fun sendChatQuery(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty() || _isThinking.value) return
        _chatMessages.update { it + ChatMessageItem(text = query, isUser = true) }

        viewModelScope.launch {
            _isThinking.value = true
            _thinkingStatus.value = "Routing intent..."
            val statusJob = launch {
                FridayApplication.agentCore.agentStatusFlow.collect { s ->
                    _thinkingStatus.value = s
                }
            }
            try {
                val result = FridayApplication.agentCore.processQuery(query)
                _chatMessages.update { it + ChatMessageItem(text = result.message, isUser = false) }
            } catch (e: Exception) {
                _chatMessages.update {
                    it + ChatMessageItem(text = "Error: ${e.localizedMessage ?: "Unknown failure"}", isUser = false)
                }
            } finally {
                statusJob.cancel()
                _isThinking.value = false
                _thinkingStatus.value = ""
            }
        }
    }

    fun clearChatHistory() {
        _chatMessages.value = listOf(
            ChatMessageItem(text = "Conversation cleared. Ready when you are.", isUser = false)
        )
    }

    fun toggleChatVoiceCapture() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(appContext, "Grant Microphone permission in Settings first.", Toast.LENGTH_SHORT).show()
            return
        }
        val helper = chatSpeechHelper ?: SpeechToTextHelper(
            context = appContext,
            onTranscriptUpdate = { _partialTranscriptChat.value = it },
            onFinalResult = { finalText ->
                _isRecordingChat.value = false
                _partialTranscriptChat.value = ""
                if (finalText.isNotBlank()) sendChatQuery(finalText)
            },
            onRmsUpdate = {},
            onStateChanged = { state ->
                _isRecordingChat.value = (state == PipelineState.LISTENING)
            },
            profile = SpeechToTextHelper.RecognitionProfile.CONVERSATION
        ).also { chatSpeechHelper = it }

        if (_isRecordingChat.value) {
            helper.stopListening()
            _isRecordingChat.value = false
        } else {
            _partialTranscriptChat.value = ""
            helper.startListening()
        }
    }

    fun setWakeWordPausedForChat(paused: Boolean) {
        val intent = Intent(appContext, FridayService::class.java).apply {
            action = if (paused) FridayService.ACTION_PAUSE_WAKEWORD else FridayService.ACTION_RESUME_WAKEWORD
        }
        try {
            appContext.startService(intent)
        } catch (_: Exception) {
        }
    }

    fun triggerVoiceOverlay() {
        FridayService.triggerGestureActivation()
    }

    // Vault CRUD: Reminders
    fun createReminder(message: String, whenInput: String) {
        val clean = message.trim()
        if (clean.isEmpty()) return
        val parsed = ReminderScheduler.parseNaturalDateTime(whenInput)
            ?: (System.currentTimeMillis() + 60 * 60_000L)
        ReminderScheduler.scheduleAt(appContext, parsed, clean)
        Toast.makeText(appContext, "Reminder scheduled", Toast.LENGTH_SHORT).show()
    }

    fun toggleReminderCompleted(reminder: ReminderEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.setReminderCompleted(reminder.id, !reminder.isCompleted)
        }
    }

    fun deleteReminder(reminder: ReminderEntity) {
        ReminderScheduler.cancelReminder(appContext, reminder)
    }

    // Vault CRUD: Notes
    fun addNote(content: String, tags: String) {
        val clean = content.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            dao.insertNote(NoteEntity(content = clean, tags = tags.trim()))
        }
    }

    fun updateNote(note: NoteEntity, content: String, tags: String) {
        val clean = content.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateNote(note.copy(content = clean, tags = tags.trim(), timestamp = System.currentTimeMillis()))
        }
    }

    fun toggleNotePinned(note: NoteEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateNotePinned(note.id, !note.isPinned)
        }
    }

    fun deleteNote(note: NoteEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteNote(note)
        }
    }

    // Vault CRUD: Memories & Routines
    fun deleteMemory(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteMemoryById(id)
        }
    }

    fun deleteRoutine(routine: RoutineEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteRoutineById(routine.id)
        }
    }

    fun saveSimpleRoutine(triggerPhrase: String, stepsText: String) {
        val trigger = triggerPhrase.trim().lowercase()
        val steps = stepsText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (trigger.isEmpty() || steps.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val json = com.google.gson.Gson().toJson(steps)
            dao.insertRoutine(RoutineEntity(triggerPhrase = trigger, actionSequenceJson = json))
        }
    }

    fun dismissNotification(key: String) {
        NotificationService.dismissNotification(key)
    }

    // Sports team management
    fun addSportsTeam(teamName: String, isFootball: Boolean? = null) {
        val clean = teamName.trim()
        if (clean.isEmpty()) return
        SportsPrefs.addTeam(appContext, clean, isFootball)
        _cricketTeams.value = SportsPrefs.getCricketTeams(appContext)
        _footballTeams.value = SportsPrefs.getFootballTeams(appContext)
        refreshDashboardFeeds(forceSports = true)
    }

    fun removeSportsTeam(teamName: String) {
        SportsPrefs.removeTeam(appContext, teamName)
        _cricketTeams.value = SportsPrefs.getCricketTeams(appContext)
        _footballTeams.value = SportsPrefs.getFootballTeams(appContext)
        refreshDashboardFeeds(forceSports = true)
    }

    // Settings Toggles
    fun setAssistantEnabled(enabled: Boolean) = updateAssistantPref("assistant_enabled", enabled) {
        _prefs.update { it.copy(assistantEnabled = enabled) }
        notifyServiceAction(if (enabled) FridayService.ACTION_RESUME_WAKEWORD else FridayService.ACTION_PAUSE_WAKEWORD)
    }

    fun setWakeWordEnabled(enabled: Boolean) = updateAssistantPref("wake_word_enabled", enabled) {
        _prefs.update { it.copy(wakeWordEnabled = enabled) }
        notifyServiceAction(if (enabled) FridayService.ACTION_RESUME_WAKEWORD else FridayService.ACTION_PAUSE_WAKEWORD)
    }

    fun setBargeInEnabled(enabled: Boolean) = updateAssistantPref("barge_in_enabled", enabled) {
        _prefs.update { it.copy(bargeInEnabled = enabled) }
    }

    fun setGestureActivationEnabled(enabled: Boolean) = updateAssistantPref("gesture_activation_enabled", enabled) {
        _prefs.update { it.copy(gestureActivationEnabled = enabled) }
    }

    fun setGestureTriggerMode(mode: String) {
        appContext.getSharedPreferences("friday_assistant_prefs", Context.MODE_PRIVATE)
            .edit().putString("gesture_trigger_mode", mode).apply()
        _prefs.update { it.copy(gestureTriggerMode = mode) }
    }

    fun setHapticFeedbackEnabled(enabled: Boolean) = updateAssistantPref("haptic_feedback_enabled", enabled) {
        _prefs.update { it.copy(hapticFeedbackEnabled = enabled) }
    }

    fun setAudioChimeEnabled(enabled: Boolean) = updateAssistantPref("audio_chime_enabled", enabled) {
        _prefs.update { it.copy(audioChimeEnabled = enabled) }
    }

    fun setBleWearableEnabled(enabled: Boolean) = updateAssistantPref("ble_wearable_enabled", enabled) {
        _prefs.update { it.copy(bleWearableEnabled = enabled) }
        notifyServiceAction(FridayService.ACTION_RELOAD_BLE_WEARABLE)
        if (enabled && bleManager.hasPermissions()) {
            bleManager.setEnabled(true)
        } else if (!enabled) {
            bleManager.setEnabled(false)
        }
    }

    fun setUseLlm(enabled: Boolean) {
        appContext.getSharedPreferences("friday_model_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("use_llm", enabled).apply()
        _prefs.update { it.copy(useLlm = enabled) }
    }

    fun setDigestEnabled(enabled: Boolean) {
        ProactiveDigestWorker.setEnabled(appContext, enabled)
        _prefs.update { it.copy(digestEnabled = enabled) }
    }

    fun triggerBleReconnect() {
        if (bleManager.hasPermissions()) {
            bleManager.setEnabled(false)
            bleManager.setEnabled(true)
        } else {
            Toast.makeText(appContext, "Bluetooth permissions required", Toast.LENGTH_SHORT).show()
        }
    }

    fun downloadDefaultLlm() {
        if (_models.value.llmDownloading) return
        _models.update { it.copy(llmDownloading = true, llmDownloadProgress = 0f, llmDownloadError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            modelManager.downloadLlmModel(
                onProgress = { progress ->
                    _models.update { it.copy(llmDownloadProgress = progress) }
                },
                onFinished = { success, error ->
                    _models.update {
                        it.copy(
                            llmDownloading = false,
                            llmDownloadError = error,
                            llmLoaded = success || modelManager.isLlmLoaded(),
                            llmPath = modelManager.getLlmModelPath()
                        )
                    }
                    if (success) {
                        FridayService.reloadModels()
                    }
                }
            )
        }
    }

    fun importCustomGguf(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cr = appContext.contentResolver
                var name = "custom-model.gguf"
                var totalBytes = -1L
                cr.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx != -1 && cursor.moveToFirst()) name = cursor.getString(nameIdx)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx != -1 && cursor.moveToFirst()) totalBytes = cursor.getLong(sizeIdx)
                }

                _models.update { it.copy(copyingFileName = name, copyingProgress = 0f) }

                val destDir = appContext.getExternalFilesDir("models") ?: appContext.filesDir
                if (!destDir.exists()) destDir.mkdirs()
                val destFile = File(destDir, name)
                val tempFile = File(destDir, "$name.tmp")

                cr.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        var copied = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            copied += bytesRead
                            if (totalBytes > 0) {
                                _models.update { it.copy(copyingProgress = copied.toFloat() / totalBytes) }
                            }
                        }
                    }
                }

                if (tempFile.exists()) {
                    if (destFile.exists()) destFile.delete()
                    if (tempFile.renameTo(destFile)) {
                        modelManager.setLlmModelPath(destFile.absolutePath)
                        _models.update {
                            it.copy(
                                copyingProgress = -1f,
                                copyingFileName = "",
                                llmLoaded = true,
                                llmPath = destFile.absolutePath
                            )
                        }
                        FridayService.reloadModels()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(appContext, "GGUF imported successfully", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                _models.update { it.copy(copyingProgress = -1f, copyingFileName = "") }
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun readLatestLogs(): String {
        val file = FridayLogger.getLogFile()
        return if (file != null && file.exists()) {
            try {
                val lines = file.readLines()
                lines.takeLast(180).joinToString("\n")
            } catch (e: Exception) {
                "Error reading logs: ${e.localizedMessage}"
            }
        } else {
            "No diagnostic log recorded yet."
        }
    }

    private inline fun updateAssistantPref(key: String, value: Boolean, crossinline onDone: () -> Unit) {
        appContext.getSharedPreferences("friday_assistant_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean(key, value).apply()
        onDone()
    }

    private fun notifyServiceAction(action: String) {
        val intent = Intent(appContext, FridayService::class.java).apply { this.action = action }
        try {
            appContext.startService(intent)
        } catch (_: Exception) {
        }
    }

    private fun hasPermission(perm: String): Boolean {
        return ContextCompat.checkSelfPermission(appContext, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun isDefaultAssistant(): Boolean {
        val roleActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = appContext.getSystemService(Context.ROLE_SERVICE) as? android.app.role.RoleManager
            rm?.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) == true
        } else false
        if (roleActive) return true
        return try {
            val assistantStr = Settings.Secure.getString(appContext.contentResolver, "assistant")
                ?: Settings.Secure.getString(appContext.contentResolver, "voice_interaction_service")
            assistantStr?.contains(appContext.packageName) == true
        } catch (_: Exception) {
            false
        }
    }

    private fun readPermissionsSnapshot(): SystemPermissionsState {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val listenerEnabled = try {
            Settings.Secure.getString(appContext.contentResolver, "enabled_notification_listeners")
                ?.contains(appContext.packageName) == true
        } catch (_: Exception) {
            false
        }
        return SystemPermissionsState(
            hasMic = hasPermission(Manifest.permission.RECORD_AUDIO),
            hasNotifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                hasPermission(Manifest.permission.POST_NOTIFICATIONS),
            hasOverlay = Settings.canDrawOverlays(appContext),
            hasAssistantRole = isDefaultAssistant(),
            hasWriteSettings = Settings.System.canWrite(appContext),
            hasDnd = nm.isNotificationPolicyAccessGranted,
            hasAccessibility = AccessibilityHelper.isFridayAccessibilityEnabled(appContext),
            hasCalendar = hasPermission(Manifest.permission.READ_CALENDAR),
            hasContacts = hasPermission(Manifest.permission.READ_CONTACTS),
            hasPhone = hasPermission(Manifest.permission.CALL_PHONE),
            hasLocation = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
            hasNotificationListener = listenerEnabled
        )
    }

    private fun readModelsSnapshot(): ModelsStatusState {
        return ModelsStatusState(
            whisperLoaded = modelManager.isWhisperLoaded(),
            nluLoaded = modelManager.isNluLoaded(),
            llmLoaded = modelManager.isLlmLoaded(),
            llmPath = modelManager.getLlmModelPath()
        )
    }

    private fun readPrefsSnapshot(): AssistantPrefsState {
        val p = appContext.getSharedPreferences("friday_assistant_prefs", Context.MODE_PRIVATE)
        val mp = appContext.getSharedPreferences("friday_model_prefs", Context.MODE_PRIVATE)
        return AssistantPrefsState(
            assistantEnabled = p.getBoolean("assistant_enabled", true),
            wakeWordEnabled = p.getBoolean("wake_word_enabled", false),
            bargeInEnabled = p.getBoolean("barge_in_enabled", true),
            gestureActivationEnabled = p.getBoolean("gesture_activation_enabled", true),
            gestureTriggerMode = p.getString("gesture_trigger_mode", "volume_down_double") ?: "volume_down_double",
            hapticFeedbackEnabled = p.getBoolean("haptic_feedback_enabled", true),
            audioChimeEnabled = p.getBoolean("audio_chime_enabled", true),
            bleWearableEnabled = p.getBoolean("ble_wearable_enabled", true),
            useLlm = mp.getBoolean("use_llm", true),
            digestEnabled = ProactiveDigestWorker.isEnabled(appContext)
        )
    }

    override fun onCleared() {
        super.onCleared()
        try {
            chatSpeechHelper?.destroy()
        } catch (_: Exception) {
        }
    }
}
