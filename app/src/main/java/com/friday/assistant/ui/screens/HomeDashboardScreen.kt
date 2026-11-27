package com.friday.assistant.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.friday.assistant.ble.FridayBleWearableManager
import com.friday.assistant.ui.screens.components.EmptyStateBanner
import com.friday.assistant.ui.screens.components.FridayBrandMark
import com.friday.assistant.ui.screens.components.FridayCard
import com.friday.assistant.ui.screens.components.FridaySectionHeader
import com.friday.assistant.ui.screens.components.StatusPill
import com.friday.assistant.ui.screens.components.formatRelativeMinutes
import com.friday.assistant.ui.screens.components.formatTimestampCompact
import com.friday.assistant.ui.theme.LocalFridayColors
import com.friday.assistant.ui.viewmodel.FridayViewModel
import java.util.Calendar

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeDashboardScreen(
    viewModel: FridayViewModel,
    onOpenSettings: () -> Unit,
    onOpenThemeStudio: () -> Unit,
    onOpenVaultTab: (Int) -> Unit,
    onAskPrompt: (String) -> Unit,
    onRequestCalendarPermission: () -> Unit
) {
    val context = LocalContext.current
    val colors = LocalFridayColors.current
    val scrollState = rememberScrollState()

    val reminders by viewModel.activeReminders.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val routines by viewModel.routines.collectAsState()
    val sportsCards by viewModel.sportsCards.collectAsState()
    val isLoadingSports by viewModel.isLoadingSports.collectAsState()
    val todayEvents by viewModel.todayEvents.collectAsState()
    val notifications by viewModel.notifications.collectAsState()
    val notificationConnected by viewModel.notificationListenerConnected.collectAsState()
    val newsDigest by viewModel.newsDigest.collectAsState()
    val bleState by viewModel.bleConnectionState.collectAsState()
    val bleBattery by viewModel.bleBatteryPercent.collectAsState()
    val perms by viewModel.permissions.collectAsState()

    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 4..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Late night"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(scrollState)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        // Brand Header + Theme + Settings
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FridayBrandMark(size = 40.dp, isLive = perms.coreReady)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = greeting,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )
                    Text(
                        text = "Friday",
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.textPrimary
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onOpenThemeStudio,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(colors.surface)
                        .border(1.dp, colors.cardBorder, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Palette,
                        contentDescription = "Theme Studio",
                        tint = colors.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(colors.surface)
                        .border(1.dp, colors.cardBorder, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = colors.textPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Live Telemetry Pill Row
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusPill(
                label = if (perms.coreReady) "Core Ready" else "Setup Needed",
                active = perms.coreReady
            )
            StatusPill(
                label = when (bleState) {
                    FridayBleWearableManager.ConnectionState.CONNECTED -> {
                        val bat = bleBattery
                        if (bat != null) "Pendant • $bat%" else "Pendant Linked"
                    }
                    FridayBleWearableManager.ConnectionState.SCANNING,
                    FridayBleWearableManager.ConnectionState.CONNECTING -> "Scanning BLE"
                    FridayBleWearableManager.ConnectionState.DISCONNECTED -> "Pendant Idle"
                },
                active = bleState == FridayBleWearableManager.ConnectionState.CONNECTED && (bleBattery == null || bleBattery!! > 20)
            )
            if (bleState == FridayBleWearableManager.ConnectionState.CONNECTED && bleBattery != null && bleBattery!! <= 20) {
                StatusPill(
                    label = "Charge Pendant (${bleBattery}%)",
                    active = false
                )
            }
            StatusPill(
                label = "${reminders.size} Reminders",
                active = reminders.isNotEmpty()
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Quick Ask Hero Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(colors.surface)
                .border(1.dp, colors.primary.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                .clickable { viewModel.triggerVoiceOverlay() }
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Tap to speak to Friday",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Or say \"Hey Friday\" on your pendant • Instant offline NLU v2",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(colors.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Voice Command",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // 1. Live Sports Card
        FridaySectionHeader(
            title = "Followed Teams",
            actionLabel = if (isLoadingSports) "Updating..." else "Refresh",
            onAction = { viewModel.refreshDashboardFeeds(forceSports = true) }
        )
        FridayCard(
            trailing = {
                Icon(
                    imageVector = Icons.Default.SportsSoccer,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        ) {
            if (sportsCards.isEmpty()) {
                EmptyStateBanner(
                    icon = Icons.Default.SportsSoccer,
                    title = if (isLoadingSports) "Fetching live scores..." else "India & Real Madrid ready",
                    subtitle = "Tap Refresh or ask Friday \"What's the cricket score?\""
                )
            } else {
                sportsCards.forEachIndexed { index, card ->
                    if (index > 0) {
                        HorizontalDivider(color = colors.cardBorder, modifier = Modifier.padding(vertical = 10.dp))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAskPrompt("What is the ${card.sport.lowercase()} score?") },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = card.sport.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = card.badge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (card.isLive) colors.secondary else colors.textTertiary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = card.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = card.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Upcoming Reminders & Calendar Card
        FridaySectionHeader(
            title = "Up Next",
            actionLabel = "All Reminders",
            onAction = { onOpenVaultTab(0) }
        )
        FridayCard {
            if (reminders.isEmpty() && todayEvents.isEmpty()) {
                if (!perms.hasCalendar) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Calendar & Reminders",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "Grant Calendar access to view upcoming events alongside voice reminders.",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary
                            )
                        }
                        TextButton(onClick = onRequestCalendarPermission) {
                            Text("Allow", color = colors.primary)
                        }
                    }
                } else {
                    EmptyStateBanner(
                        icon = Icons.Default.Alarm,
                        title = "Clear schedule ahead",
                        subtitle = "Say \"Remind me at 6 PM to call Alex\" or add in Vault."
                    )
                }
            } else {
                reminders.take(3).forEachIndexed { idx, rem ->
                    if (idx > 0) HorizontalDivider(color = colors.cardBorder, modifier = Modifier.padding(vertical = 8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { viewModel.toggleReminderCompleted(rem) },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircleOutline,
                                contentDescription = "Complete reminder",
                                tint = colors.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = rem.message,
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formatTimestampCompact(rem.triggerAtMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.secondary
                            )
                        }
                    }
                }

                if (reminders.isNotEmpty() && todayEvents.isNotEmpty()) {
                    HorizontalDivider(color = colors.cardBorder, modifier = Modifier.padding(vertical = 8.dp))
                }

                todayEvents.take(3).forEachIndexed { idx, ev ->
                    if (idx > 0) HorizontalDivider(color = colors.cardBorder, modifier = Modifier.padding(vertical = 8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CalendarToday,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = ev.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formatTimestampCompact(ev.startMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. Notes You May Check
        FridaySectionHeader(
            title = "Pinned & Recent Notes",
            actionLabel = "Open Vault",
            onAction = { onOpenVaultTab(1) }
        )
        FridayCard {
            if (notes.isEmpty()) {
                EmptyStateBanner(
                    icon = Icons.AutoMirrored.Filled.StickyNote2,
                    title = "No notes saved yet",
                    subtitle = "Say \"Take a note...\" or create one in Vault."
                )
            } else {
                notes.take(3).forEachIndexed { idx, note ->
                    if (idx > 0) HorizontalDivider(color = colors.cardBorder, modifier = Modifier.padding(vertical = 8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenVaultTab(1) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (note.isPinned) {
                            Icon(
                                imageVector = Icons.Default.PushPin,
                                contentDescription = "Pinned",
                                tint = colors.primary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = note.content,
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.textPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
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
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 4. Smart Routines Shortcut Strip (if user has routines)
        if (routines.isNotEmpty()) {
            FridaySectionHeader(
                title = "Voice Routines",
                actionLabel = "Manage",
                onAction = { onOpenVaultTab(3) }
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                routines.take(4).forEach { routine ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.cardBorder, RoundedCornerShape(14.dp))
                            .clickable { onAskPrompt(routine.triggerPhrase) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = routine.triggerPhrase.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textPrimary
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 5. Important Notifications Inbox
        FridaySectionHeader(
            title = "Notifications",
            actionLabel = if (!notificationConnected) "Enable" else null,
            onAction = if (!notificationConnected) {
                {
                    try {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    } catch (_: Exception) {
                    }
                }
            } else null
        )
        FridayCard {
            if (!notificationConnected) {
                EmptyStateBanner(
                    icon = Icons.Default.NotificationsActive,
                    title = "Notification Listener inactive",
                    subtitle = "Tap Enable so Friday can triage and read recent notifications."
                )
            } else if (notifications.isEmpty()) {
                EmptyStateBanner(
                    icon = Icons.Default.NotificationsActive,
                    title = "Inbox clear",
                    subtitle = "Important incoming alerts appear here automatically."
                )
            } else {
                notifications.take(3).forEachIndexed { idx, item ->
                    if (idx > 0) HorizontalDivider(color = colors.cardBorder, modifier = Modifier.padding(vertical = 8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${item.appName} • ${formatRelativeMinutes(item.postTime)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.primary
                            )
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (item.text.isNotBlank()) {
                                Text(
                                    text = item.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        IconButton(
                            onClick = { viewModel.dismissNotification(item.key) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss notification",
                                tint = colors.textTertiary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 6. Compact Important News Digest (replaces old Briefings tab)
        FridaySectionHeader(
            title = "Important News",
            actionLabel = "Sync",
            onAction = { viewModel.refreshDashboardFeeds(forceSports = true) }
        )
        FridayCard {
            if (newsDigest.isEmpty()) {
                EmptyStateBanner(
                    icon = Icons.Default.Newspaper,
                    title = "No fresh headlines cached",
                    subtitle = "Tap Sync to fetch concise RSS highlights without draining battery."
                )
            } else {
                newsDigest.forEachIndexed { idx, article ->
                    if (idx > 0) HorizontalDivider(color = colors.cardBorder, modifier = Modifier.padding(vertical = 8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                try {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(article.url)).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                    )
                                } catch (_: Exception) {
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = article.sourceName.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.secondary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = article.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.textPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Open article",
                            tint = colors.textTertiary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
