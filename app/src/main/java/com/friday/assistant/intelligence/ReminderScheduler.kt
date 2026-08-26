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

    fun schedule(context: Context, delaySeconds: Long, reminderMessage: String): Boolean {
        return try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return false

            val triggerAtMs = System.currentTimeMillis() + (delaySeconds * 1000L)

            val intent = Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_TRIGGER_REMINDER
                putExtra(ReminderReceiver.EXTRA_REMINDER_TEXT, reminderMessage)
            }

            val requestCode = System.currentTimeMillis().toInt()
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
            FridayLogger.i(TAG, "Scheduled spoken reminder in ${delaySeconds}s for: '$reminderMessage'")
            true
        } catch (e: Exception) {
            FridayLogger.e(TAG, "Failed to schedule reminder", e)
            false
        }
    }
}
