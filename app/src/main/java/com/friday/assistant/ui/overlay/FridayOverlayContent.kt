package com.friday.assistant.ui.overlay

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.friday.assistant.audio.PipelineState
import com.friday.assistant.ui.theme.FridayTheme
import com.friday.assistant.ui.theme.LocalFridayColors
import kotlinx.coroutines.delay

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
        val colors = LocalFridayColors.current
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(14.dp),
            contentAlignment = Alignment.TopCenter
        ) {
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
                            colors = listOf(
                                colors.primary.copy(alpha = 0.42f),
                                colors.cardBorder,
                                colors.secondary.copy(alpha = 0.35f)
                            )
                        ),
                        shape = RoundedCornerShape(24.dp)
                    ),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = colors.glassOverlay),
                elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header row: Brand pill + status + close action
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GlowingOrb(pipelineState = pipelineState)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "FRIDAY",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = colors.textSecondary,
                                letterSpacing = 1.4.sp
                            )
                            Text(
                                text = " • ",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textTertiary
                            )
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = getStatusColor(pipelineState)
                            )
                        }

                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .size(28.dp)
                                .background(colors.surfaceElevated, CircleShape)
                                .border(0.5.dp, colors.cardBorder, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close overlay",
                                tint = colors.textSecondary,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Live Transcript Display
                    if (transcript.isNotEmpty() &&
                        (pipelineState == PipelineState.LISTENING || pipelineState == PipelineState.PROCESSING)
                    ) {
                        Text(
                            text = "\"$transcript\"",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Active Visualization / Mic control
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(84.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when (pipelineState) {
                            PipelineState.IDLE -> {
                                Box(
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(CircleShape)
                                        .background(colors.primaryMuted)
                                        .border(1.dp, colors.primary.copy(alpha = 0.5f), CircleShape)
                                        .clickable { onMicClick() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = "Trigger Assistant",
                                        tint = colors.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                            PipelineState.LISTENING, PipelineState.SPEAKING -> {
                                AudioWaveformComposable(amplitude = audioAmplitude)
                            }
                            PipelineState.PROCESSING, PipelineState.THINKING -> {
                                CircularProgressIndicator(
                                    color = colors.primary,
                                    trackColor = colors.surfaceElevated,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                    }

                    // Response
                    if (assistantResponse.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        TypewriterText(
                            text = assistantResponse,
                            style = MaterialTheme.typography.bodyLarge.copy(color = colors.textPrimary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GlowingOrb(pipelineState: PipelineState) {
    val isAnimated = pipelineState != PipelineState.IDLE
    val infiniteTransition = rememberInfiniteTransition(label = "OrbPulse")
    val glowScale by if (isAnimated) {
        infiniteTransition.animateFloat(
            initialValue = 0.85f,
            targetValue = 1.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "GlowScale"
        )
    } else {
        remember { mutableStateOf(1.0f) }
    }

    val color = getStatusColor(pipelineState)

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(16.dp)) {
        Box(
            modifier = Modifier
                .graphicsLayer(scaleX = glowScale, scaleY = glowScale)
                .size(12.dp)
                .background(color.copy(alpha = 0.28f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color, CircleShape)
        )
    }
}

private val URL_REGEX = Regex("(https?://[\\w-]+(\\.[\\w-]+)+(?:/[\\w-./?%&=]*)?)")

@Composable
fun TypewriterText(
    text: String,
    style: TextStyle = LocalTextStyle.current,
    modifier: Modifier = Modifier
) {
    val colors = LocalFridayColors.current
    var visibleCount by remember(text) { mutableIntStateOf(0) }

    // O(N) chunked reveal instead of quadratic string reallocation per character
    LaunchedEffect(text) {
        visibleCount = 0
        val step = if (text.length > 160) 3 else 1
        while (visibleCount < text.length) {
            visibleCount = (visibleCount + step).coerceAtMost(text.length)
            delay(10)
        }
    }

    val visibleText = text.substring(0, visibleCount)
    val linkColor = colors.secondary

    val annotated = remember(visibleText, linkColor) {
        if (!visibleText.contains("http")) {
            null
        } else {
            buildAnnotatedString {
                var lastIndex = 0
                URL_REGEX.findAll(visibleText).forEach { matchResult ->
                    val start = matchResult.range.first
                    val end = matchResult.range.last + 1
                    if (start > lastIndex) {
                        append(visibleText.substring(lastIndex, start))
                    }
                    val url = matchResult.value
                    withLink(
                        LinkAnnotation.Url(
                            url = url,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        )
                    ) {
                        append(url)
                    }
                    lastIndex = end
                }
                if (lastIndex < visibleText.length) {
                    append(visibleText.substring(lastIndex))
                }
            }
        }
    }

    if (annotated != null) {
        Text(
            text = annotated,
            style = style.copy(color = if (style.color != Color.Unspecified) style.color else colors.textPrimary),
            modifier = modifier
        )
    } else {
        Text(
            text = visibleText,
            style = style.copy(color = if (style.color != Color.Unspecified) style.color else colors.textPrimary),
            modifier = modifier
        )
    }
}

@Composable
private fun getStatusColor(state: PipelineState): Color {
    val colors = LocalFridayColors.current
    return when (state) {
        PipelineState.IDLE -> colors.textSecondary
        PipelineState.LISTENING -> colors.secondary
        PipelineState.PROCESSING -> colors.primary
        PipelineState.THINKING -> colors.primary
        PipelineState.SPEAKING -> colors.primary
    }
}
