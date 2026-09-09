package com.friday.assistant.intelligence

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.friday.assistant.core.FridayLogger
import com.friday.assistant.ui.ReminderReceiver

object ReminderScheduler {
    private const val TAG = "ReminderScheduler"
    private const val PREFS_NAME = "friday_pending_reminders"
    private const val KEY_REMINDERS = "reminders_json"

    data class ScheduledReminder(
        val requestCode: Int,
        val triggerAtMs: Long,
        val message: String
    )

    fun schedule(context: Context, delaySeconds: Long, reminderMessage: String): Boolean {
        val triggerAtMs = System.currentTimeMillis() + (delaySeconds * 1000L)
        return scheduleAt(context, triggerAtMs, reminderMessage)
    }

    fun scheduleAt(context: Context, triggerAtMs: Long, reminderMessage: String): Boolean {
        return try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return false

            val requestCode = (System.currentTimeMillis() % 100000000).toInt()

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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMs,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMs,
                    pendingIntent
                )
            }

            // Save to persistent storage to survive device reboots
            saveReminder(context, ScheduledReminder(requestCode, triggerAtMs, reminderMessage))

            val diffSec = (triggerAtMs - System.currentTimeMillis()) / 1000L
            FridayLogger.i(TAG, "Scheduled spoken reminder at ms=$triggerAtMs (~${diffSec}s from now) for: '$reminderMessage'")
            true
        } catch (e: Exception) {
            FridayLogger.e(TAG, "Failed to schedule reminder at timestamp", e)
            false
        }
    }

    fun parseNaturalDateTime(rawInput: String): Long? {
        val input = rawInput.trim().lowercase()
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance()

        // 1. Relative "in X [unit]"
        val relRegex = Regex("(?i)\\b(?:in|after)\\s+(\\d+)\\s*(months?|weeks?|days?|hours?|hrs?|minutes?|mins?|seconds?|secs?)\\b")
        val relMatch = relRegex.find(input)
        if (relMatch != null) {
            val value = relMatch.groupValues[1].toLong()
            val unit = relMatch.groupValues[2].lowercase()
            val sec = when {
                unit.startsWith("month") -> value * 30L * 86400L
                unit.startsWith("week") -> value * 7L * 86400L
                unit.startsWith("day") -> value * 86400L
                unit.startsWith("hour") || unit.startsWith("hr") -> value * 3600L
                unit.startsWith("minute") || unit.startsWith("min") -> value * 60L
                else -> value
            }
            return now + (sec * 1000L)
        }

        // 2. Day calculation (today, tomorrow, day-of-week)
        var hasDayModifier = false
        if (input.contains("tomorrow")) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            hasDayModifier = true
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
                if (input.contains(dayName)) {
                    val currentDay = cal.get(java.util.Calendar.DAY_OF_WEEK)
                    var daysToAdd = dayConst - currentDay
                    if (daysToAdd <= 0) daysToAdd += 7
                    cal.add(java.util.Calendar.DAY_OF_YEAR, daysToAdd)
                    hasDayModifier = true
                    break
                }
            }
        }

        // 3. Time-of-day clock parsing
        val timeRegex = Regex("(?i)(?:at\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.|o'clock)?\\b")
        val timeMatch = timeRegex.findAll(input).firstOrNull { match ->
            val hasAmPm = match.groupValues[3].isNotEmpty()
            val hasColon = match.groupValues[2].isNotEmpty()
            val startsWithAt = match.value.startsWith("at")
            hasAmPm || hasColon || startsWithAt
        }

        var hour = -1
        var minute = 0

        if (timeMatch != null) {
            var h = timeMatch.groupValues[1].toInt()
            minute = if (timeMatch.groupValues[2].isNotEmpty()) timeMatch.groupValues[2].toInt() else 0
            val ampm = timeMatch.groupValues[3].lowercase().replace(".", "")
            if (ampm == "pm" && h < 12) h += 12
            else if (ampm == "am" && h == 12) h = 0
            else if (ampm.isEmpty() && !hasDayModifier && h in 1..11 && h <= cal.get(java.util.Calendar.HOUR_OF_DAY)) {
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

            if (cal.timeInMillis <= now && !hasDayModifier) {
                cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
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
        try {
            val list = getSavedReminders(context).toMutableList()
            list.removeAll { it.requestCode == requestCode }
            persistList(context, list)
        } catch (e: Exception) {
            FridayLogger.e(TAG, "Error cleaning up triggered reminder", e)
        }
    }

    fun rescheduleAll(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val list = getSavedReminders(context)
            val now = System.currentTimeMillis()
            val remaining = mutableListOf<ScheduledReminder>()

            for (r in list) {
                if (r.triggerAtMs > now) {
                    val intent = Intent(context, ReminderReceiver::class.java).apply {
                        action = ReminderReceiver.ACTION_TRIGGER_REMINDER
                        putExtra(ReminderReceiver.EXTRA_REMINDER_TEXT, r.message)
                        putExtra("reminder_code", r.requestCode)
                    }
                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        r.requestCode,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerAtMs, pendingIntent)
                    } else {
                        alarmManager.setExact(AlarmManager.RTC_WAKEUP, r.triggerAtMs, pendingIntent)
                    }
                    remaining.add(r)
                    FridayLogger.i(TAG, "Restored reminder on boot: '${r.message}' at ${r.triggerAtMs}")
                }
            }
            persistList(context, remaining)
        } catch (e: Exception) {
            FridayLogger.e(TAG, "Failed to reschedule reminders on boot", e)
        }
    }

    private fun saveReminder(context: Context, reminder: ScheduledReminder) {
        val list = getSavedReminders(context).toMutableList()
        list.add(reminder)
        persistList(context, list)
    }

    private fun getSavedReminders(context: Context): List<ScheduledReminder> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_REMINDERS, null) ?: return emptyList()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<ScheduledReminder>>() {}.type
            com.google.gson.Gson().fromJson(raw, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun persistList(context: Context, list: List<ScheduledReminder>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = com.google.gson.Gson().toJson(list)
        prefs.edit().putString(KEY_REMINDERS, json).apply()
    }
}
