package com.friday.assistant.intelligence

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.friday.assistant.core.FridayApplication
import com.friday.assistant.core.FridayLogger
import com.friday.assistant.core.db.ReminderEntity
import com.friday.assistant.ui.ReminderReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object ReminderScheduler {
    private const val TAG = "ReminderScheduler"
    private const val PREFS_NAME = "friday_pending_reminders"
    private const val KEY_REMINDERS = "reminders_json"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class ScheduledReminder(
        val requestCode: Int,
        val triggerAtMs: Long,
        val message: String
    )

    private val WORD_NUMBERS = mapOf(
        "a" to 1L,
        "an" to 1L,
        "one" to 1L,
        "two" to 2L,
        "three" to 3L,
        "four" to 4L,
        "five" to 5L,
        "six" to 6L,
        "seven" to 7L,
        "eight" to 8L,
        "nine" to 9L,
        "ten" to 10L,
        "fifteen" to 15L,
        "twenty" to 20L,
        "thirty" to 30L,
        "forty" to 40L,
        "forty five" to 45L,
        "forty-five" to 45L,
        "fifty" to 50L
    )

    fun schedule(context: Context, delaySeconds: Long, reminderMessage: String): Boolean {
        val triggerAtMs = System.currentTimeMillis() + (delaySeconds * 1000L)
        return scheduleAt(context, triggerAtMs, reminderMessage)
    }

    fun scheduleAt(context: Context, triggerAtMs: Long, reminderMessage: String): Boolean {
        return try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return false

            val requestCode = ((System.currentTimeMillis() xor reminderMessage.hashCode().toLong()) and 0x7FFFFFFF).toInt()
            scheduleAlarmInternal(context, alarmManager, requestCode, triggerAtMs, reminderMessage)

            // Persist to Room (with safe fallback when running outside Application in tests)
            scope.launch {
                try {
                    FridayApplication.database.dao().insertReminder(
                        ReminderEntity(
                            requestCode = requestCode,
                            message = reminderMessage,
                            triggerAtMs = triggerAtMs
                        )
                    )
                } catch (e: Exception) {
                    FridayLogger.e(TAG, "Failed to insert reminder in Room", e)
                }
            }

            val diffSec = (triggerAtMs - System.currentTimeMillis()) / 1000L
            FridayLogger.i(TAG, "Scheduled spoken reminder at ms=$triggerAtMs (~${diffSec}s from now) for: '$reminderMessage'")
            true
        } catch (e: Exception) {
            FridayLogger.e(TAG, "Failed to schedule reminder at timestamp", e)
            false
        }
    }

    fun cancelReminder(context: Context, reminder: ReminderEntity) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_TRIGGER_REMINDER
                putExtra(ReminderReceiver.EXTRA_REMINDER_TEXT, reminder.message)
                putExtra("reminder_code", reminder.requestCode)
            }
            val pi = PendingIntent.getBroadcast(
                context,
                reminder.requestCode,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) {
                alarmManager?.cancel(pi)
                pi.cancel()
            }
        } catch (e: Exception) {
            FridayLogger.w(TAG, "Error cancelling alarm for reminder ${reminder.id}: ${e.message}")
        }
        scope.launch {
            try {
                FridayApplication.database.dao().deleteReminderById(reminder.id)
            } catch (e: Exception) {
                FridayLogger.e(TAG, "Failed to delete reminder ${reminder.id}", e)
            }
        }
    }

    suspend fun cancelAllReminders(context: Context): Int {
        return try {
            val dao = FridayApplication.database.dao()
            val all = dao.getAllRemindersSync()
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            for (r in all) {
                cancelPendingAlarmOnly(context, alarmManager, r)
            }
            dao.clearAllReminders()
            FridayLogger.i(TAG, "Cancelled and deleted all ${all.size} reminders")
            all.size
        } catch (e: Exception) {
            FridayLogger.e(TAG, "Failed to delete all reminders", e)
            0
        }
    }

    suspend fun cancelMatchingReminders(context: Context, queryOrId: String): Int {
        return try {
            val dao = FridayApplication.database.dao()
            val all = dao.getAllRemindersSync()
            if (all.isEmpty()) return 0
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

            val trimmed = queryOrId.trim().lowercase()
            val numericId = trimmed.toLongOrNull()
            val targets = when {
                trimmed == "last" || trimmed == "latest" -> listOfNotNull(all.maxByOrNull { it.id })
                numericId != null -> {
                    val exact = all.filter { it.id == numericId }
                    if (exact.isNotEmpty()) exact
                    else {
                        val idx = (numericId - 1).toInt()
                        if (idx in all.indices) listOf(all[idx]) else emptyList()
                    }
                }
                else -> all.filter { it.message.lowercase().contains(trimmed) }
            }
            for (r in targets) {
                cancelPendingAlarmOnly(context, alarmManager, r)
                dao.deleteReminderById(r.id)
            }
            targets.size
        } catch (e: Exception) {
            FridayLogger.e(TAG, "Failed to cancel matching reminders for '$queryOrId'", e)
            0
        }
    }

    private fun cancelPendingAlarmOnly(context: Context, alarmManager: AlarmManager?, reminder: ReminderEntity) {
        try {
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_TRIGGER_REMINDER
                putExtra(ReminderReceiver.EXTRA_REMINDER_TEXT, reminder.message)
                putExtra("reminder_code", reminder.requestCode)
            }
            val pi = PendingIntent.getBroadcast(
                context,
                reminder.requestCode,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) {
                alarmManager?.cancel(pi)
                pi.cancel()
            }
        } catch (e: Exception) {
            FridayLogger.w(TAG, "Error cancelling alarm for reminder ${reminder.id}: ${e.message}")
        }
    }

    fun parseNaturalDateTime(rawInput: String, nowMs: Long = System.currentTimeMillis()): Long? {
        val input = rawInput.trim().lowercase()
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }

        // 0. Special phrases ("half an hour", "half hour")
        if (Regex("\\b(?:in\\s+)?half\\s+(?:an\\s+)?hour\\b").containsMatchIn(input)) {
            return nowMs + 30L * 60_000L
        }

        // 1. Relative "in/after X [unit]" supporting both digits and word numbers
        val numberAlternation = WORD_NUMBERS.keys
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        val relRegex = Regex(
            "(?i)\\b(?:in|after)\\s+(\\d+|$numberAlternation)\\s*(months?|weeks?|days?|hours?|hrs?|minutes?|mins?|seconds?|secs?)\\b"
        )
        val relMatch = relRegex.find(input)
        if (relMatch != null) {
            val rawVal = relMatch.groupValues[1].lowercase()
            val value = rawVal.toLongOrNull() ?: WORD_NUMBERS[rawVal] ?: 1L
            val unit = relMatch.groupValues[2].lowercase()
            val sec = when {
                unit.startsWith("month") -> value * 30L * 86400L
                unit.startsWith("week") -> value * 7L * 86400L
                unit.startsWith("day") -> value * 86400L
                unit.startsWith("hour") || unit.startsWith("hr") -> value * 3600L
                unit.startsWith("minute") || unit.startsWith("min") -> value * 60L
                else -> value
            }
            return nowMs + (sec * 1000L)
        }

        // 2. Day calculation (today, tomorrow, day-of-week)
        var hasDayModifier = false
        var isExplicitToday = false
        if (input.contains("tomorrow")) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            hasDayModifier = true
        } else if (input.contains("today") || input.contains("tonight")) {
            hasDayModifier = true
            isExplicitToday = true
        } else {
            val daysOfWeek = mapOf(
                "sunday" to java.util.Calendar.SUNDAY,
                "monday" to java.util.Calendar.MONDAY,
                "tuesday" to java.util.Calendar.TUESDAY,
                "wednesday" to java.util.Calendar.WEDNESDAY,
                "thursday" to java.util.Calendar.THURSDAY,
                "friday" to java.util.Calendar.FRIDAY,
                "saturday" to java.util.Calendar.SATURDAY
            )
            for ((dayName, dayConst) in daysOfWeek) {
                if (Regex("\\b$dayName\\b").containsMatchIn(input)) {
                    val currentDay = cal.get(java.util.Calendar.DAY_OF_WEEK)
                    var daysToAdd = dayConst - currentDay
                    if (daysToAdd <= 0) daysToAdd += 7
                    cal.add(java.util.Calendar.DAY_OF_YEAR, daysToAdd)
                    hasDayModifier = true
                    break
                }
            }
        }

        // 3. Time-of-day clock parsing (supports 5:30 pm, 5.30 pm, at 7, 19:00)
        val timeRegex = Regex("(?i)(?:at\\s+)?(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.|o'clock)?\\b")
        val timeMatch = timeRegex.findAll(input).firstOrNull { match ->
            val hasAmPm = match.groupValues[3].isNotEmpty()
            val hasSeparator = match.groupValues[2].isNotEmpty()
            val startsWithAt = match.value.startsWith("at")
            hasAmPm || hasSeparator || startsWithAt
        }

        var hour = -1
        var minute = 0

        if (timeMatch != null) {
            var h = timeMatch.groupValues[1].toInt()
            minute = if (timeMatch.groupValues[2].isNotEmpty()) timeMatch.groupValues[2].toInt() else 0
            val ampm = timeMatch.groupValues[3].lowercase().replace(".", "")
            if (ampm == "pm" && h < 12) h += 12
            else if (ampm == "am" && h == 12) h = 0
            else if (ampm.isEmpty() && (input.contains("tonight") || input.contains("evening")) && h in 1..11) {
                h += 12
            } else if (ampm.isEmpty() && !hasDayModifier && h in 1..11 && h <= cal.get(java.util.Calendar.HOUR_OF_DAY)) {
                h += 12
            }
            hour = h.coerceIn(0, 23)
            minute = minute.coerceIn(0, 59)
        } else if (input.contains("tonight")) {
            hour = 20; minute = 0
        } else if (input.contains("morning")) {
            hour = 9; minute = 0
        } else if (input.contains("afternoon")) {
            hour = 14; minute = 0
        } else if (input.contains("evening")) {
            hour = 18; minute = 0
        } else if (input.contains("noon")) {
            hour = 12; minute = 0
        } else if (input.contains("midnight")) {
            hour = 0; minute = 0
        }

        if (hour >= 0) {
            cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
            cal.set(java.util.Calendar.MINUTE, minute)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)

            if (cal.timeInMillis <= nowMs) {
                if (!hasDayModifier) {
                    cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                } else if (isExplicitToday) {
                    FridayLogger.w(TAG, "Explicit today reminder was requested for a past time, scheduling 60s from now")
                    cal.timeInMillis = nowMs + 60_000L
                }
            }
            return cal.timeInMillis
        } else if (hasDayModifier) {
            cal.set(java.util.Calendar.HOUR_OF_DAY, 9)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        return null
    }

    fun onReminderTriggered(context: Context, requestCode: Int) {
        scope.launch {
            try {
                FridayApplication.database.dao().markReminderCompletedByCode(requestCode)
            } catch (e: Exception) {
                FridayLogger.e(TAG, "Error marking reminder completed in Room", e)
            }
        }
    }

    fun migrateLegacyPrefsIfAny(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_REMINDERS, null) ?: return
        val legacy: List<ScheduledReminder> = try {
            val type = object : com.google.gson.reflect.TypeToken<List<ScheduledReminder>>() {}.type
            com.google.gson.Gson().fromJson(raw, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        if (legacy.isNotEmpty()) {
            scope.launch {
                val dao = FridayApplication.database.dao()
                val now = System.currentTimeMillis()
                legacy.filter { it.triggerAtMs > now }.forEach { item ->
                    dao.insertReminder(
                        ReminderEntity(
                            requestCode = item.requestCode,
                            message = item.message,
                            triggerAtMs = item.triggerAtMs
                        )
                    )
                }
            }
        }
        prefs.edit().remove(KEY_REMINDERS).apply()
    }

    fun rescheduleAll(context: Context) {
        migrateLegacyPrefsIfAny(context)
        scope.launch {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return@launch
                val dao = FridayApplication.database.dao()
                val active = dao.getActiveRemindersSync()
                val now = System.currentTimeMillis()
                for (r in active) {
                    if (r.triggerAtMs > now) {
                        scheduleAlarmInternal(context, alarmManager, r.requestCode, r.triggerAtMs, r.message)
                        FridayLogger.i(TAG, "Restored reminder on boot: '${r.message}' at ${r.triggerAtMs}")
                    } else {
                        dao.setReminderCompleted(r.id, true)
                    }
                }
            } catch (e: Exception) {
                FridayLogger.e(TAG, "Failed to reschedule reminders", e)
            }
        }
    }

    private fun scheduleAlarmInternal(
        context: Context,
        alarmManager: AlarmManager,
        requestCode: Int,
        triggerAtMs: Long,
        reminderMessage: String
    ) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_TRIGGER_REMINDER
            putExtra(ReminderReceiver.EXTRA_REMINDER_TEXT, reminderMessage)
            putExtra("reminder_code", requestCode)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (canExact) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            }
        } else {
            FridayLogger.w(TAG, "Exact alarm permission denied; falling back to inexact allowWhileIdle alarm")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            }
        }
    }
}
