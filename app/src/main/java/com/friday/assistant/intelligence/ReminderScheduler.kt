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
        return try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return false

            val triggerAtMs = System.currentTimeMillis() + (delaySeconds * 1000L)
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

            FridayLogger.i(TAG, "Scheduled spoken reminder in ${delaySeconds}s (triggerAtMs=$triggerAtMs) for: '$reminderMessage'")
            true
        } catch (e: Exception) {
            FridayLogger.e(TAG, "Failed to schedule reminder", e)
            false
        }
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
