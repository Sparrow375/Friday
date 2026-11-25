package com.friday.assistant.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.friday.assistant.core.FridayLogger
import com.friday.assistant.intelligence.ReminderScheduler
import com.friday.assistant.ui.screens.MainActivity
import java.util.Locale

class ReminderReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ReminderReceiver"
        const val ACTION_TRIGGER_REMINDER = "com.friday.assistant.ACTION_TRIGGER_REMINDER"
        const val EXTRA_REMINDER_TEXT = "reminder_text"
        private const val CHANNEL_ID = "friday_reminders"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val reminderText = intent?.getStringExtra(EXTRA_REMINDER_TEXT) ?: "Time for your reminder!"
        val reminderCode = intent?.getIntExtra("reminder_code", -1) ?: -1
        if (reminderCode != -1) {
            ReminderScheduler.onReminderTriggered(context, reminderCode)
        }
        FridayLogger.i(TAG, "Reminder alarm triggered: '$reminderText'")

        // 1. Acquire brief partial wake lock while posting/speaking
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = pm?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Friday:ReminderWakeLock"
        )
        wakeLock?.acquire(9000L)

        // 2. Always post heads-up notification
        postNotification(context, reminderText)

        // 3. Speak via active FridayService if alive, else use goAsync() + short-lived TextToSpeech
        val activeService = FridayService.instance
        if (activeService != null) {
            try {
                activeService.speakReminder(reminderText)
            } catch (e: Exception) {
                FridayLogger.e(TAG, "Active FridayService failed to speak reminder", e)
            } finally {
                if (wakeLock?.isHeld == true) wakeLock.release()
            }
            return
        }

        val pendingResult = goAsync()
        val mainHandler = Handler(Looper.getMainLooper())
        var tts: TextToSpeech? = null
        val finishCleanup = Runnable {
            try {
                tts?.stop()
                tts?.shutdown()
            } catch (_: Exception) {
            }
            if (wakeLock?.isHeld == true) {
                try {
                    wakeLock.release()
                } catch (_: Exception) {
                }
            }
            try {
                pendingResult.finish()
            } catch (_: Exception) {
            }
        }
        // Hard safety timeout so receiver never hangs
        mainHandler.postDelayed(finishCleanup, 8000L)

        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.getDefault()
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            mainHandler.removeCallbacks(finishCleanup)
                            mainHandler.post(finishCleanup)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            mainHandler.removeCallbacks(finishCleanup)
                            mainHandler.post(finishCleanup)
                        }
                    })
                    tts?.speak("Reminder: $reminderText", TextToSpeech.QUEUE_FLUSH, null, "friday_reminder_${System.currentTimeMillis()}")
                } else {
                    mainHandler.removeCallbacks(finishCleanup)
                    mainHandler.post(finishCleanup)
                }
            }
        } catch (e: Exception) {
            FridayLogger.e(TAG, "Short-lived TTS fallback failed", e)
            mainHandler.removeCallbacks(finishCleanup)
            finishCleanup.run()
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
