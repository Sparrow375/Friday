package com.friday.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.friday.assistant.ui.screens.components.FridayBrandMark
import com.friday.assistant.ui.theme.LocalFridayColors
import com.friday.assistant.ui.viewmodel.ChatMessageItem
import com.friday.assistant.ui.viewmodel.FridayViewModel

@Composable
fun AssistantChatScreen(
    viewModel: FridayViewModel,
    initialPrompt: String? = null,
    onConsumedInitialPrompt: () -> Unit = {}
) {
    val colors = LocalFridayColors.current
    val messages by viewModel.chatMessages.collectAsState()
    val isThinking by viewModel.isThinking.collectAsState()
    val thinkingStatus by viewModel.thinkingStatus.collectAsState()
    val isRecording by viewModel.isRecordingChat.collectAsState()
    val partialTranscript by viewModel.partialTranscriptChat.collectAsState()

    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Pause ambient phone wake-word while interactive voice chat is open
    DisposableEffect(Unit) {
        viewModel.setWakeWordPausedForChat(true)
        onDispose {
            viewModel.setWakeWordPausedForChat(false)
        }
    }

    LaunchedEffect(initialPrompt) {
        if (!initialPrompt.isNullOrBlank()) {
            viewModel.sendChatQuery(initialPrompt)
            onConsumedInitialPrompt()
        }
    }

    LaunchedEffect(messages.size, isThinking) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val quickChips = remember {
        listOf(
            "What's the cricket score?",
            "Real Madrid score",
            "Add a note",
            "List my reminders",
            "What's on my calendar?"
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
    ) {
        // Minimal top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FridayBrandMark(size = 34.dp, isLive = isThinking || isRecording)
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Assistant",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary
                    )
                    Text(
                        text = when {
                            isRecording -> "Listening..."
                            isThinking -> thinkingStatus.ifBlank { "Thinking..." }
                            else -> "On-device NLU v2 + Qwen"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isThinking || isRecording) colors.primary else colors.textSecondary
                    )
                }
            }

            IconButton(
                onClick = { viewModel.clearChatHistory() },
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colors.surface)
                    .border(1.dp, colors.cardBorder, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Clear conversation",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Message list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                ChatBubble(message = msg)
            }

            if (isRecording && partialTranscript.isNotBlank()) {
                item {
                    ChatBubble(
                        message = ChatMessageItem(
                            id = -1L,
                            text = "\"$partialTranscript\"",
                            isUser = true
                        ),
                        isEphemeral = true
                    )
                }
            }

            if (isThinking) {
                item {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.cardBorder, RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colors.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = thinkingStatus.ifBlank { "Processing..." },
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(6.dp)) }
        }

        // Quick suggestions strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            quickChips.forEach { chip ->
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(colors.surface)
                        .border(1.dp, colors.cardBorder, CircleShape)
                        .clickable { if (!isThinking) viewModel.sendChatQuery(chip) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = chip,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )
                }
            }
        }

        // Bottom composer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .border(1.dp, colors.cardBorder, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                placeholder = {
                    Text(
                        text = "Ask Friday anything...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textTertiary
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        val q = textInput.trim()
                        if (q.isNotEmpty() && !isThinking) {
                            textInput = ""
                            viewModel.sendChatQuery(q)
                        }
                    }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.primary,
                    unfocusedBorderColor = colors.cardBorder,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    cursorColor = colors.primary
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Voice toggle button
            IconButton(
                onClick = { viewModel.toggleChatVoiceCapture() },
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (isRecording) colors.errorAccent else colors.surfaceElevated)
                    .border(1.dp, if (isRecording) colors.errorAccent else colors.cardBorder, CircleShape)
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = "Voice input",
                    tint = if (isRecording) androidx.compose.ui.graphics.Color.White else colors.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Send button
            val canSend = textInput.isNotBlank() && !isThinking
            IconButton(
                onClick = {
                    val q = textInput.trim()
                    if (q.isNotEmpty() && !isThinking) {
                        textInput = ""
                        viewModel.sendChatQuery(q)
                    }
                },
                enabled = canSend,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (canSend) colors.primary else colors.surfaceElevated)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (canSend) MaterialTheme.colorScheme.onPrimary else colors.textTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ChatBubble(
    message: ChatMessageItem,
    isEphemeral: Boolean = false
) {
    val colors = LocalFridayColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 310.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (message.isUser) 18.dp else 4.dp,
                        bottomEnd = if (message.isUser) 4.dp else 18.dp
                    )
                )
                .background(
                    when {
                        message.isUser && isEphemeral -> colors.primaryMuted
                        message.isUser -> colors.primary
                        else -> colors.surface
                    }
                )
                .border(
                    width = 1.dp,
                    color = if (message.isUser) colors.primary.copy(alpha = 0.4f) else colors.cardBorder,
                    shape = RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (message.isUser) 18.dp else 4.dp,
                        bottomEnd = if (message.isUser) 4.dp else 18.dp
                    )
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column {
                if (!message.isUser) {
                    Text(
                        text = "Friday",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.secondary
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                }
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (message.isUser && !isEphemeral) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        colors.textPrimary
                    }
                )
            }
        }
    }
}
