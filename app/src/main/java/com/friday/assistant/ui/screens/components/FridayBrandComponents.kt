package com.friday.assistant.ui.screens.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.friday.assistant.ui.theme.LocalFridayColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun FridayBrandMark(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    isLive: Boolean = false
) {
    val colors = LocalFridayColors.current
    val pulseScale = if (isLive) {
        val transition = rememberInfiniteTransition(label = "BrandPulse")
        val value by transition.animateFloat(
            initialValue = 0.85f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(1300, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "PulseValue"
        )
        value
    } else {
        1f
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape((size.value * 0.28f).dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(colors.surfaceElevated, colors.surface)
                )
            )
            .border(1.dp, colors.primary.copy(alpha = 0.45f), RoundedCornerShape((size.value * 0.28f).dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding((size.value * 0.16f).dp)) {
            val w = this.size.width
            val h = this.size.height

            // Subtle orbital ring arc
            drawArc(
                color = colors.primary.copy(alpha = 0.35f),
                startAngle = -140f,
                sweepAngle = 220f,
                useCenter = false,
                topLeft = Offset(0f, 0f),
                size = Size(w, h),
                style = Stroke(width = w * 0.08f, cap = StrokeCap.Round)
            )

            // Crisp geometric 'F' spine
            drawRoundRect(
                color = colors.textPrimary,
                topLeft = Offset(w * 0.25f, h * 0.18f),
                size = Size(w * 0.17f, h * 0.64f),
                cornerRadius = CornerRadius(w * 0.06f, w * 0.06f)
            )
            // Top bar
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(colors.textPrimary, colors.primary)),
                topLeft = Offset(w * 0.25f, h * 0.18f),
                size = Size(w * 0.54f, h * 0.16f),
                cornerRadius = CornerRadius(w * 0.06f, w * 0.06f)
            )
            // Mid bar
            drawRoundRect(
                color = colors.primary,
                topLeft = Offset(w * 0.25f, h * 0.44f),
                size = Size(w * 0.40f, h * 0.15f),
                cornerRadius = CornerRadius(w * 0.06f, w * 0.06f)
            )
            // Resonance pulse dot
            drawCircle(
                color = colors.secondary,
                radius = (w * 0.10f) * pulseScale,
                center = Offset(w * 0.72f, h * 0.72f)
            )
        }
    }
}

@Composable
fun FridayCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalFridayColors.current
    val baseMod = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(20.dp))
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }

    Card(
        modifier = baseMod,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.cardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            if (title != null || trailing != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = if (subtitle != null) 4.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        if (title != null) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary
                            )
                        }
                    }
                    if (trailing != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        trailing()
                    }
                }
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            content()
        }
    }
}

@Composable
fun FridaySectionHeader(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val colors = LocalFridayColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
            color = colors.textTertiary
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.primary
                )
            }
        }
    }
}

@Composable
fun StatusPill(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = LocalFridayColors.current
    val dotColor by animateColorAsState(
        targetValue = if (active) colors.secondary else colors.textTertiary,
        label = "StatusDot"
    )
    val bgColor = if (active) colors.primaryMuted else colors.surfaceElevated
    val borderColor = if (active) colors.primary.copy(alpha = 0.38f) else colors.cardBorder

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(bgColor)
            .border(1.dp, borderColor, CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) colors.textPrimary else colors.textSecondary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun EmptyStateBanner(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    val colors = LocalFridayColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceElevated)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.primaryMuted),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary
            )
        }
    }
}

fun formatTimestampCompact(timeMs: Long): String {
    if (timeMs <= 0L) return ""
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = timeMs }
    val sameDay = now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "h:mm a" else "EEE, MMM d • h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timeMs))
}

fun formatRelativeMinutes(timeMs: Long): String {
    val diff = System.currentTimeMillis() - timeMs
    val min = (diff / 60_000L).coerceAtLeast(0L)
    return when {
        min < 1L -> "just now"
        min < 60L -> "${min}m ago"
        min < 1440L -> "${min / 60L}h ago"
        else -> "${min / 1440L}d ago"
    }
}
