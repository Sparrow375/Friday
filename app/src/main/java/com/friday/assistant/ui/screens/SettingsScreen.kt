package com.friday.assistant.ui.screens

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.friday.assistant.ble.FridayBleWearableManager
import com.friday.assistant.core.AccessibilityHelper
import com.friday.assistant.ui.screens.components.FridayCard
import com.friday.assistant.ui.screens.components.FridaySectionHeader
import com.friday.assistant.ui.screens.components.StatusPill
import com.friday.assistant.ui.theme.LocalFridayColors
import com.friday.assistant.ui.theme.ThemeStore
import com.friday.assistant.ui.viewmodel.FridayViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: FridayViewModel,
    onBack: () -> Unit,
    onOpenThemeStudio: () -> Unit
) {
    val context = LocalContext.current
    val colors = LocalFridayColors.current
    val scrollState = rememberScrollState()

    val prefs by viewModel.prefs.collectAsState()
    val perms by viewModel.permissions.collectAsState()
    val models by viewModel.models.collectAsState()
    val bleState by viewModel.bleConnectionState.collectAsState()
    val bleDevice by viewModel.bleDeviceName.collectAsState()
    val cricketTeams by viewModel.cricketTeams.collectAsState()
    val footballTeams by viewModel.footballTeams.collectAsState()
    val themeConfig by ThemeStore.configFlow.collectAsState()

    var newTeamInput by remember { mutableStateOf("") }
    var showLogsDialog by remember { mutableStateOf(false) }
    var logsSnapshot by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshSystemStatus()
    }

    val ggufPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importCustomGguf(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(scrollState)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.surface)
                    .border(1.dp, colors.cardBorder, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.textPrimary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.textPrimary
                )
                Text(
                    text = "Theme, hardware pendant, models & system access",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // 1. Appearance
        FridaySectionHeader(title = "Appearance")
        FridayCard(onClick = onOpenThemeStudio) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.primaryMuted),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = null,
                            tint = colors.primary
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Theme Studio",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "${themeConfig.preset.displayName} • ${themeConfig.surfaceStyle.displayName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                    }
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.textTertiary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Hardware Pendant & Voice Triggers
        FridaySectionHeader(title = "Voice & BLE Pendant")
        FridayCard {
            SettingToggleRow(
                title = "Assistant Active",
                subtitle = "Master switch for background triggers & voice pipeline",
                checked = prefs.assistantEnabled,
                onCheckedChange = viewModel::setAssistantEnabled
            )
            HorizontalDivider(color = colors.cardBorder, modifier = Modifier.padding(vertical = 10.dp))

            SettingToggleRow(
                title = "BLE Pendant Wearable",
                subtitle = when (bleState) {
                    FridayBleWearableManager.ConnectionState.CONNECTED -> "Connected to ${bleDevice ?: "Friday-Pendant"}"
                    FridayBleWearableManager.ConnectionState.SCANNING -> "Scanning for XIAO nRF52840 pendant..."
                    FridayBleWearableManager.ConnectionState.CONNECTING -> "Establishing GATT link..."
                    FridayBleWearableManager.ConnectionState.DISCONNECTED -> "Disconnected"
                },
                checked = prefs.bleWearableEnabled,
                onCheckedChange = viewModel::setBleWearableEnabled
            )
            if (prefs.bleWearableEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { viewModel.triggerBleReconnect() }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Rescan Pendant", color = colors.primary)
                    }
                }
            }

            HorizontalDivider(color = colors.cardBorder, modifier = Modifier.padding(vertical = 10.dp))
            SettingToggleRow(
                title = "Speak Interrupt (Barge-In)",
                subtitle = "Say \"Friday\" while she speaks long replies to interrupt cleanly",
                checked = prefs.bargeInEnabled,
                onCheckedChange = viewModel::setBargeInEnabled
            )

            HorizontalDivider(color = colors.cardBorder, modifier = Modifier.padding(vertical = 10.dp))
            SettingToggleRow(
                title = "24/7 Phone Wake-Word",
                subtitle = "Keep PhoneticWakeWordNet listening continuously on phone mic",
                checked = prefs.wakeWordEnabled,
                onCheckedChange = viewModel::setWakeWordEnabled
            )

            HorizontalDivider(color = colors.cardBorder, modifier = Modifier.padding(vertical = 10.dp))
            SettingToggleRow(
                title = "Hardware Volume Gesture",
                subtitle = "Double-press volume down to summon overlay anywhere",
                checked = prefs.gestureActivationEnabled,
                onCheckedChange = viewModel::setGestureActivationEnabled
            )

            HorizontalDivider(color = colors.cardBorder, modifier = Modifier.padding(vertical = 10.dp))
            SettingToggleRow(
                title = "Haptic Confirmation & Chime",
                subtitle = "Tactile buzz & soft chime when listening begins",
                checked = prefs.hapticFeedbackEnabled && prefs.audioChimeEnabled,
                onCheckedChange = {
                    viewModel.setHapticFeedbackEnabled(it)
                    viewModel.setAudioChimeEnabled(it)
                }
            )

            HorizontalDivider(color = colors.cardBorder, modifier = Modifier.padding(vertical = 10.dp))
            SettingToggleRow(
                title = "Morning & Evening Digest",
                subtitle = "Post concise reminder, calendar & score summary notification",
                checked = prefs.digestEnabled,
                onCheckedChange = viewModel::setDigestEnabled
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. Followed Sports Teams
        FridaySectionHeader(title = "Followed Sports Teams")
        FridayCard {
            val allTeams = remember(cricketTeams, footballTeams) {
                cricketTeams.map { it to "Cricket" } + footballTeams.map { it to "Football" }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                allTeams.forEach { (team, sport) ->
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.removeSportsTeam(team) },
                        label = { Text("$team ($sport)") },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove $team",
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.primaryMuted,
                            selectedLabelColor = colors.textPrimary
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newTeamInput,
                    onValueChange = { newTeamInput = it },
                    placeholder = { Text("Add team (e.g. Arsenal, MI)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (newTeamInput.isNotBlank()) {
                            viewModel.addSportsTeam(newTeamInput)
                            newTeamInput = ""
                        }
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.primary)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add team", tint = Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 4. On-Device Brain Models
        FridaySectionHeader(title = "On-Device AI Models")
        FridayCard {
            ModelStatusRow(
                title = "Whisper STT",
                detail = "ggml-tiny-q5_1 (ARM64 NEON)",
                ready = models.whisperLoaded
            )
            HorizontalDivider(color = colors.cardBorder, modifier = Modifier.padding(vertical = 10.dp))

            ModelStatusRow(
                title = "Joint NLU Engine",
                detail = "12-Layer BGE ONNX Intent + BIO Slot Tagger",
                ready = models.nluLoaded
            )
            HorizontalDivider(color = colors.cardBorder, modifier = Modifier.padding(vertical = 10.dp))

            SettingToggleRow(
                title = "Qwen 2.5 0.5B Instruct Fallback",
                subtitle = if (models.llmLoaded) "GGUF ready • Prefix KV Cache active" else "Optional conversational LLM (~400 MB Q4_K_M)",
                checked = prefs.useLlm,
                onCheckedChange = viewModel::setUseLlm
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (models.llmDownloading) {
                LinearProgressIndicator(
                    progress = { models.llmDownloadProgress },
                    color = colors.primary,
                    trackColor = colors.surfaceElevated,
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Downloading Qwen 2.5 0.5B... ${(models.llmDownloadProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.secondary
                )
            } else if (models.copyingProgress >= 0f) {
                LinearProgressIndicator(
                    progress = { models.copyingProgress },
                    color = colors.primary,
                    trackColor = colors.surfaceElevated,
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Importing ${models.copyingFileName}... ${(models.copyingProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.secondary
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!models.llmLoaded) {
                        Button(
                            onClick = { viewModel.downloadDefaultLlm() },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Download Qwen 0.5B", color = Color.White)
                        }
                    }
                    OutlinedButton(
                        onClick = { ggufPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (models.llmLoaded) "Change GGUF" else "Pick GGUF", color = colors.textPrimary)
                    }
                }
            }
            if (!models.llmDownloadError.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Download error: ${models.llmDownloadError}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.errorAccent
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 5. Permissions Checklist (Device Admin removed; biometric lock via Accessibility)
        FridaySectionHeader(title = "System Permissions")
        FridayCard {
            PermissionRow("Microphone", "Voice commands & speech recognition", perms.hasMic) {
                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
            HorizontalDivider(color = colors.cardBorder, modifier = Modifier.padding(vertical = 8.dp))

            PermissionRow("Floating Overlay", "Displays hands-free HUD above apps", perms.hasOverlay) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
            }
            HorizontalDivider(color = colors.cardBorder, modifier = Modifier.padding(vertical = 8.dp))

            PermissionRow("Default Assistant Role", "Hold-power / hardware summon", perms.hasAssistantRole) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val rm = context.getSystemService(RoleManager::class.java)
                    if (rm != null) {
                        try {
                            context.startActivity(rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                        } catch (_: Exception) {
                            context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                        }
                    }
                } else {
                    context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                }
            }
            HorizontalDivider(color = colors.cardBorder, modifier = Modifier.padding(vertical = 8.dp))

            PermissionRow("UI Automation (Accessibility)", "Biometric-friendly screen lock & silent controls", perms.hasAccessibility) {
                AccessibilityHelper.openAccessibilitySettings(context)
            }
            HorizontalDivider(color = colors.cardBorder, modifier = Modifier.padding(vertical = 8.dp))

            PermissionRow("Calendar", "Show today's events & add calendar entries", perms.hasCalendar) {
                permissionLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
            }
            HorizontalDivider(color = colors.cardBorder, modifier = Modifier.padding(vertical = 8.dp))

            PermissionRow("Notifications Inbox", "Read & dismiss notifications on Home", perms.hasNotificationListener) {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            HorizontalDivider(color = colors.cardBorder, modifier = Modifier.padding(vertical = 8.dp))

            PermissionRow("Contacts, Phone & Location", "Calling contacts & local queries", perms.hasContacts && perms.hasPhone && perms.hasLocation) {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_CONTACTS,
                        Manifest.permission.CALL_PHONE,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.POST_NOTIFICATIONS
                    )
                )
            }
            HorizontalDivider(color = colors.cardBorder, modifier = Modifier.padding(vertical = 8.dp))

            PermissionRow("DND & Write Settings", "System volume, brightness & Do Not Disturb", perms.hasDnd && perms.hasWriteSettings) {
                if (!perms.hasDnd) {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                } else {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 6. Diagnostics
        FridaySectionHeader(title = "Diagnostics")
        FridayCard(
            onClick = {
                logsSnapshot = viewModel.readLatestLogs()
                showLogsDialog = true
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Inspect Runtime Logs",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "View latest pipeline, BLE & NLU trace entries",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.textTertiary
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
    }

    if (showLogsDialog) {
        AlertDialog(
            onDismissRequest = { showLogsDialog = false },
            containerColor = colors.surface,
            title = { Text("Friday Runtime Logs", color = colors.textPrimary) },
            text = {
                Column(modifier = Modifier.height(340.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        text = logsSnapshot,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = colors.textSecondary
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogsDialog = false }) {
                    Text("Close", color = colors.primary)
                }
            }
        )
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = LocalFridayColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = colors.textPrimary)
            Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = colors.primary
            )
        )
    }
}

@Composable
private fun ModelStatusRow(title: String, detail: String, ready: Boolean) {
    val colors = LocalFridayColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = colors.textPrimary)
            Text(text = detail, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
        }
        StatusPill(label = if (ready) "READY" else "MISSING", active = ready)
    }
}

@Composable
private fun PermissionRow(title: String, subtitle: String, granted: Boolean, onGrant: () -> Unit) {
    val colors = LocalFridayColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = colors.textPrimary)
            Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
        }
        if (granted) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Granted",
                tint = colors.secondary,
                modifier = Modifier.size(22.dp)
            )
        } else {
            TextButton(onClick = onGrant) {
                Text("Grant", color = colors.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}
