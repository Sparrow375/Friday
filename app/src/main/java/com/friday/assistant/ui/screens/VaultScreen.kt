package com.friday.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.friday.assistant.core.db.NoteEntity
import com.friday.assistant.ui.screens.components.EmptyStateBanner
import com.friday.assistant.ui.screens.components.FridayCard
import com.friday.assistant.ui.screens.components.formatRelativeMinutes
import com.friday.assistant.ui.screens.components.formatTimestampCompact
import com.friday.assistant.ui.theme.LocalFridayColors
import com.friday.assistant.ui.viewmodel.FridayViewModel

@Composable
fun VaultScreen(
    viewModel: FridayViewModel,
    initialSubTab: Int = 0,
    onRunRoutine: (String) -> Unit
) {
    val colors = LocalFridayColors.current
    var selectedTab by remember(initialSubTab) { mutableIntStateOf(initialSubTab.coerceIn(0, 3)) }
    val tabs = remember { listOf("Reminders", "Notes", "Memory", "Routines") }

    var searchQuery by remember { mutableStateOf("") }
    var showAddReminderDialog by remember { mutableStateOf(false) }
    var showNoteDialog by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<NoteEntity?>(null) }
    var showAddRoutineDialog by remember { mutableStateOf(false) }

    val allReminders by viewModel.allReminders.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val semanticMemories by viewModel.semanticMemories.collectAsState()
    val episodicMemories by viewModel.episodicMemories.collectAsState()
    val routines by viewModel.routines.collectAsState()

    Scaffold(
        containerColor = colors.bg,
        floatingActionButton = {
            if (selectedTab != 2) {
                FloatingActionButton(
                    onClick = {
                        when (selectedTab) {
                            0 -> showAddReminderDialog = true
                            1 -> {
                                editingNote = null
                                showNoteDialog = true
                            }
                            3 -> showAddRoutineDialog = true
                        }
                    },
                    containerColor = colors.primary,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Add item")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Text(
                text = "Personal Vault",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.textPrimary
            )
            Text(
                text = "Reminders, notes, learned preferences & multi-step voice macros",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Segmented Pill Switcher
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.cardBorder, RoundedCornerShape(14.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                tabs.forEachIndexed { index, title ->
                    val selected = selectedTab == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) colors.primaryMuted else Color.Transparent)
                            .clickable { selectedTab = index }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) colors.primary else colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (selectedTab == 1 || selectedTab == 2) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = colors.textTertiary
                        )
                    },
                    placeholder = {
                        Text(
                            text = if (selectedTab == 1) "Search notes or tags..." else "Search saved memories...",
                            color = colors.textTertiary
                        )
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.primary,
                        unfocusedBorderColor = colors.cardBorder,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (selectedTab) {
                    0 -> {
                        if (allReminders.isEmpty()) {
                            item {
                                EmptyStateBanner(
                                    icon = Icons.Default.Alarm,
                                    title = "No reminders scheduled",
                                    subtitle = "Tap + below or say \"Remind me in 20 minutes to stretch\"."
                                )
                            }
                        } else {
                            items(allReminders, key = { it.id }) { rem ->
                                FridayCard {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(onClick = { viewModel.toggleReminderCompleted(rem) }) {
                                            Icon(
                                                imageVector = if (rem.isCompleted) {
                                                    Icons.Default.CheckCircle
                                                } else {
                                                    Icons.Default.CheckCircleOutline
                                                },
                                                contentDescription = "Toggle complete",
                                                tint = if (rem.isCompleted) colors.secondary else colors.primary
                                            )
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = rem.message,
                                                style = MaterialTheme.typography.bodyLarge.copy(
                                                    textDecoration = if (rem.isCompleted) TextDecoration.LineThrough else TextDecoration.None
                                                ),
                                                color = if (rem.isCompleted) colors.textTertiary else colors.textPrimary
                                            )
                                            Text(
                                                text = formatTimestampCompact(rem.triggerAtMs),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (rem.isCompleted) colors.textTertiary else colors.secondary
                                            )
                                        }
                                        IconButton(onClick = { viewModel.deleteReminder(rem) }) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "Delete reminder",
                                                tint = colors.textTertiary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        val filtered = notes.filter {
                            searchQuery.isBlank() ||
                                it.content.contains(searchQuery, ignoreCase = true) ||
                                it.tags.contains(searchQuery, ignoreCase = true)
                        }
                        if (filtered.isEmpty()) {
                            item {
                                EmptyStateBanner(
                                    icon = Icons.AutoMirrored.Filled.StickyNote2,
                                    title = if (searchQuery.isBlank()) "No notes yet" else "No matching notes",
                                    subtitle = "Tap + to jot down an idea or speak \"Note that down\"."
                                )
                            }
                        } else {
                            items(filtered, key = { it.id }) { note ->
                                FridayCard(
                                    onClick = {
                                        editingNote = note
                                        showNoteDialog = true
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = note.content,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = colors.textPrimary
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            val meta = buildString {
                                                append(formatRelativeMinutes(note.timestamp))
                                                if (note.tags.isNotBlank()) append(" • #${note.tags}")
                                            }
                                            Text(
                                                text = meta,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = colors.textTertiary
                                            )
                                        }
                                        IconButton(onClick = { viewModel.toggleNotePinned(note) }) {
                                            Icon(
                                                imageVector = if (note.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                                contentDescription = "Pin note",
                                                tint = if (note.isPinned) colors.primary else colors.textTertiary
                                            )
                                        }
                                        IconButton(onClick = { viewModel.deleteNote(note) }) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "Delete note",
                                                tint = colors.textTertiary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    2 -> {
                        val combined = (semanticMemories + episodicMemories)
                            .distinctBy { it.id }
                            .filter {
                                searchQuery.isBlank() ||
                                    it.key.contains(searchQuery, ignoreCase = true) ||
                                    it.value.contains(searchQuery, ignoreCase = true) ||
                                    it.category.contains(searchQuery, ignoreCase = true)
                            }
                        if (combined.isEmpty()) {
                            item {
                                EmptyStateBanner(
                                    icon = Icons.Default.Psychology,
                                    title = "Memory vault is clean",
                                    subtitle = "Tell Friday \"Remember my home Wi-Fi\" or \"I prefer dark coffee\"."
                                )
                            }
                        } else {
                            items(combined, key = { it.id }) { mem ->
                                FridayCard {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${mem.layer} • ${mem.key}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = colors.primary,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = mem.value,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = colors.textPrimary
                                            )
                                            Text(
                                                text = formatRelativeMinutes(mem.timestamp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = colors.textTertiary
                                            )
                                        }
                                        IconButton(onClick = { viewModel.deleteMemory(mem.id) }) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "Forget memory",
                                                tint = colors.textTertiary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    3 -> {
                        if (routines.isEmpty()) {
                            item {
                                EmptyStateBanner(
                                    icon = Icons.Default.AutoAwesome,
                                    title = "No voice routines yet",
                                    subtitle = "Create a macro like \"Goodnight\" -> turn off bluetooth, turn on DND, lock phone."
                                )
                            }
                        } else {
                            items(routines, key = { it.id }) { routine ->
                                FridayCard {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "\"${routine.triggerPhrase}\"",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = colors.textPrimary
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = routine.actionSequenceJson
                                                    .removePrefix("[").removeSuffix("]")
                                                    .replace("\"", "")
                                                    .replace(",", " → "),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = colors.textSecondary
                                            )
                                            Text(
                                                text = "Runs: ${routine.successCount}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = colors.textTertiary
                                            )
                                        }
                                        IconButton(onClick = { onRunRoutine(routine.triggerPhrase) }) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Run routine",
                                                tint = colors.primary
                                            )
                                        }
                                        IconButton(onClick = { viewModel.deleteRoutine(routine) }) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "Delete routine",
                                                tint = colors.textTertiary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(64.dp)) }
            }
        }
    }

    if (showAddReminderDialog) {
        var msg by remember { mutableStateOf("") }
        var whenTxt by remember { mutableStateOf("in 30 minutes") }
        AlertDialog(
            onDismissRequest = { showAddReminderDialog = false },
            containerColor = colors.surface,
            title = { Text("New Reminder", color = colors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = msg,
                        onValueChange = { msg = it },
                        label = { Text("Remind me to...") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = whenTxt,
                        onValueChange = { whenTxt = it },
                        label = { Text("When (e.g. tomorrow 9 am, in 20 mins)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (msg.isNotBlank()) {
                            viewModel.createReminder(msg, whenTxt)
                            showAddReminderDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
                ) {
                    Text("Schedule", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddReminderDialog = false }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            }
        )
    }

    if (showNoteDialog) {
        var content by remember(editingNote) { mutableStateOf(editingNote?.content ?: "") }
        var tags by remember(editingNote) { mutableStateOf(editingNote?.tags ?: "") }
        AlertDialog(
            onDismissRequest = { showNoteDialog = false },
            containerColor = colors.surface,
            title = { Text(if (editingNote == null) "New Note" else "Edit Note", color = colors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text("Note content") },
                        minLines = 3
                    )
                    OutlinedTextField(
                        value = tags,
                        onValueChange = { tags = it },
                        label = { Text("Tags (optional, comma-separated)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (content.isNotBlank()) {
                            val current = editingNote
                            if (current == null) {
                                viewModel.addNote(content, tags)
                            } else {
                                viewModel.updateNote(current, content, tags)
                            }
                            showNoteDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
                ) {
                    Text("Save", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNoteDialog = false }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            }
        )
    }

    if (showAddRoutineDialog) {
        var trigger by remember { mutableStateOf("") }
        var steps by remember { mutableStateOf("turn off bluetooth\nturn on dnd\nlock phone") }
        AlertDialog(
            onDismissRequest = { showAddRoutineDialog = false },
            containerColor = colors.surface,
            title = { Text("New Voice Routine", color = colors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = trigger,
                        onValueChange = { trigger = it },
                        label = { Text("Trigger phrase (e.g. goodnight)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = steps,
                        onValueChange = { steps = it },
                        label = { Text("Commands (one per line)") },
                        minLines = 3
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (trigger.isNotBlank() && steps.isNotBlank()) {
                            viewModel.saveSimpleRoutine(trigger, steps)
                            showAddRoutineDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
                ) {
                    Text("Save Routine", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddRoutineDialog = false }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            }
        )
    }
}
