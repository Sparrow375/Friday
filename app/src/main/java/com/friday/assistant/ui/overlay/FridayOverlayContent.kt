package com.friday.assistant.ui.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import android.util.Log
import com.friday.assistant.audio.PipelineState
import com.friday.assistant.ui.theme.*
import kotlinx.coroutines.delay
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.text.ClickableText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FridayOverlayContent(
    pipelineState: PipelineState,
    statusText: String,
    transcript: String,
    assistantResponse: String,
    audioAmplitude: Float,
    onClose: () -> Unit,
    onMicClick: () -> Unit,
    onDrag: (Int, Int) -> Unit
) {
    FridayTheme {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(16.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            // Glassmorphic Panel Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x.toInt(), dragAmount.y.toInt())
                        }
                    }
                    .border(
                        width = 1.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(CyberBorder, Color.Transparent, CyberBorder)
                        ),
                        shape = RoundedCornerShape(28.dp)
                    ),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = GlassObsidian)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Top Header bar with status and close action
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Glowing status indicator
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GlowingOrb(pipelineState = pipelineState)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = getStatusColor(pipelineState)
                            )
                        }

                        // Close button
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color(0x1AFFFFFF), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close overlay",
                                tint = SilverText,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Live Transcript Display
                    if (transcript.isNotEmpty() && (pipelineState == PipelineState.LISTENING || pipelineState == PipelineState.PROCESSING)) {
                        Text(
                            text = "\"$transcript\"",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Main visualization/response container
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when (pipelineState) {
                            PipelineState.IDLE -> {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.radialGradient(
                                                colors = listOf(NeonBlue.copy(alpha = 0.2f), Color.Transparent)
                                            )
                                        )
                                        .clickable { onMicClick() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = "Trigger Assistant",
                                        tint = NeonBlue,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                            PipelineState.LISTENING, PipelineState.SPEAKING -> {
                                AudioWaveformComposable(amplitude = audioAmplitude)
                            }
                            PipelineState.PROCESSING, PipelineState.THINKING -> {
                                CircularProgressIndicator(
                                    color = NeonCyan,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }
                    }

                    // Assistant Text Response (with typewriter scroll effect)
                    if (assistantResponse.isNotEmpty() && (pipelineState == PipelineState.SPEAKING || pipelineState == PipelineState.THINKING)) {
                        Spacer(modifier = Modifier.height(12.dp))
                        TypewriterText(
                            text = assistantResponse,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
@Composable
fun GlowingOrb(pipelineState: PipelineState) {
    val infiniteTransition = rememberInfiniteTransition()
    val glowScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    val color = getStatusColor(pipelineState)

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(16.dp)) {
        // Outer glow
        Box(
            modifier = Modifier
                .graphicsLayer(scaleX = glowScale, scaleY = glowScale)
                .size(12.dp)
                .background(color.copy(alpha = 0.3f), CircleShape)
        )
        // Core orb
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color, CircleShape)
        )
    }
}

@Composable
fun TypewriterText(
    text: String,
    style: TextStyle = LocalTextStyle.current,
    modifier: Modifier = Modifier
) {
    var visibleText by remember { mutableStateOf("") }
    val context = LocalContext.current
    
    // Animate typing on response changes
    LaunchedEffect(text) {
        visibleText = ""
        for (char in text) {
            visibleText += char
            delay(12) // Typing speed
        }
    }
    
    val urlPattern = "(https?://[\\w-]+(\\.[\\w-]+)+(?:/[\\w-./?%&=]*)?)".toRegex()
    val hasUrl = remember(visibleText) { urlPattern.containsMatchIn(visibleText) }

    if (!hasUrl) {
        Text(
            text = visibleText,
            style = style,
            modifier = modifier
        )
    } else {
        val annotatedString = remember(visibleText) {
            buildAnnotatedString {
                var lastIndex = 0
                urlPattern.findAll(visibleText).forEach { matchResult ->
                    val start = matchResult.range.first
                    val end = matchResult.range.last + 1
                    
                    if (start > lastIndex) {
                        append(visibleText.substring(lastIndex, start))
                    }
                    
                    val url = matchResult.value
                    pushStringAnnotation(tag = "URL", annotation = url)
                    withStyle(style = SpanStyle(
                        color = NeonCyan,
                        textDecoration = TextDecoration.Underline,
                        fontWeight = FontWeight.Bold
                    )) {
                        append(url)
                    }
                    pop()
                    lastIndex = end
                }
                if (lastIndex < visibleText.length) {
                    append(visibleText.substring(lastIndex))
                }
            }
        }

        ClickableText(
            text = annotatedString,
            style = style.copy(color = if (style.color != Color.Unspecified) style.color else Color.White),
            modifier = modifier,
            onClick = { offset ->
                annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                    .firstOrNull()?.let { annotation ->
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(annotation.item)).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Log.e("TypewriterText", "Could not open URL: ${annotation.item}", e)
                        }
                    }
            }
        )
    }
}

private fun getStatusColor(state: PipelineState): Color {
    return when (state) {
        PipelineState.IDLE -> NeonBlue
        PipelineState.LISTENING -> GlowGreen
        PipelineState.PROCESSING -> NeonCyan
        PipelineState.THINKING -> NeonCyan
        PipelineState.SPEAKING -> NeonBlue
    }
}
