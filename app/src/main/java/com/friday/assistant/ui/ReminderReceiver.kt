package com.friday.assistant.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.friday.assistant.core.FridayLogger
import com.friday.assistant.ui.screens.MainActivity

class ReminderReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ReminderReceiver"
        const val ACTION_TRIGGER_REMINDER = "com.friday.assistant.ACTION_TRIGGER_REMINDER"
        const val EXTRA_REMINDER_TEXT = "reminder_text"
        private const val CHANNEL_ID = "friday_reminders"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val reminderText = intent?.getStringExtra(EXTRA_REMINDER_TEXT) ?: "Time for your reminder!"
        FridayLogger.i(TAG, "Reminder alarm triggered: '$reminderText'")

        // 1. Acquire temporary wake lock to keep CPU awake while speaking
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = pm?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Friday:ReminderWakeLock"
        )
        wakeLock?.acquire(8000L)

        // 2. Post a high-priority heads-up notification
        postNotification(context, reminderText)

        // 3. Trigger FridayService to speak out the reminder
        val serviceIntent = Intent(context, FridayService::class.java).apply {
            action = FridayService.ACTION_SPEAK_REMINDER
            putExtra(EXTRA_REMINDER_TEXT, reminderText)
        }

        try {
            if (FridayService.instance != null) {
                FridayService.instance?.speakReminder(reminderText)
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        } catch (e: Exception) {
            FridayLogger.e(TAG, "Failed to deliver reminder to FridayService", e)
        }
    }

    private fun postNotification(context: Context, text: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Friday Reminders",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Spoken reminders scheduled through Friday"
                    enableVibration(true)
                }
                nm.createNotificationChannel(channel)
            }

            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Friday Reminder")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()

            nm.notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: Exception) {
            FridayLogger.e(TAG, "Failed to post reminder notification", e)
        }
    }
}
