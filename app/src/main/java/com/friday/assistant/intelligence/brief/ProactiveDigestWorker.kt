package com.friday.assistant.intelligence.brief

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.friday.assistant.core.FridayApplication
import com.friday.assistant.core.FridayLogger
import com.friday.assistant.tools.calendar.CalendarTool
import com.friday.assistant.ui.screens.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

class ProactiveDigestWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_DIGEST_ENABLED, true)) {
            return@withContext Result.success()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext Result.success()
        }

        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val slot = when (hour) {
            in 7..10 -> "morning_${cal.get(Calendar.DAY_OF_YEAR)}"
            in 19..21 -> "evening_${cal.get(Calendar.DAY_OF_YEAR)}"
            else -> return@withContext Result.success()
        }

        if (prefs.getString(KEY_LAST_SLOT, null) == slot) {
            return@withContext Result.success()
        }

        val dao = FridayApplication.database.dao()
        val endOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
        }.timeInMillis

        val activeReminders = try {
            dao.getActiveRemindersSync().filter { it.triggerAtMs <= endOfToday }
        } catch (_: Exception) {
            emptyList()
        }
        val todayEvents = try {
            CalendarTool.getUpcomingEvents(applicationContext, limit = 5, onlyToday = true)
        } catch (_: Exception) {
            emptyList()
        }
        val now = System.currentTimeMillis()
        val freshnessCutoff = now - 48L * 60L * 60L * 1000L // Only surface news from the last 48 hours
        val notifiedUrls = prefs.getStringSet(KEY_NOTIFIED_URLS, emptySet())?.toMutableSet() ?: mutableSetOf()

        val topNews = try {
            dao.getNewBriefItems(limit = 15)
                .filter { it.pubDate >= freshnessCutoff && !notifiedUrls.contains(it.url) }
        } catch (_: Exception) {
            emptyList()
        }

        val parts = mutableListOf<String>()
        if (activeReminders.isNotEmpty()) {
            parts.add("${activeReminders.size} reminder${if (activeReminders.size > 1) "s" else ""}")
        }
        if (todayEvents.isNotEmpty()) {
            parts.add("${todayEvents.size} calendar event${if (todayEvents.size > 1) "s" else ""}")
        }
        val selectedNews = topNews.firstOrNull()
        if (selectedNews != null) {
            parts.add(selectedNews.title.take(60))
        }
        if (parts.isEmpty()) {
            return@withContext Result.success()
        }

        val isMorning = hour in 7..10
        val title = if (isMorning) "Friday Morning Digest" else "Friday Evening Wrap"
        val body = parts.joinToString(" • ")

        postDigestNotification(applicationContext, title, body)
        if (selectedNews != null) {
            notifiedUrls.add(selectedNews.url)
            // Keep bounded history of last 50 notified URLs
            val trimmedUrls = if (notifiedUrls.size > 50) notifiedUrls.toList().takeLast(50).toSet() else notifiedUrls
            prefs.edit()
                .putString(KEY_LAST_SLOT, slot)
                .putStringSet(KEY_NOTIFIED_URLS, trimmedUrls)
                .apply()
            try {
                dao.updateBriefItemStatus(selectedNews.id, "notified")
            } catch (_: Exception) {}
        } else {
            prefs.edit().putString(KEY_LAST_SLOT, slot).apply()
        }
        FridayLogger.i(TAG, "Posted proactive digest: $body")
        Result.success()
    }

    private fun postDigestNotification(context: Context, title: String, body: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Friday Daily Digest",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Lightweight morning & evening summaries from Friday"
            }
            nm.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            4012,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        nm.notify(4012, notification)
    }

    companion object {
        private const val TAG = "ProactiveDigestWorker"
        private const val PREFS_NAME = "friday_digest_prefs"
        private const val KEY_DIGEST_ENABLED = "digest_enabled"
        private const val KEY_LAST_SLOT = "last_digest_slot"
        private const val KEY_NOTIFIED_URLS = "notified_news_urls"
        private const val CHANNEL_ID = "friday_digest_channel"
        private const val WORK_NAME = "FridayProactiveDigestWork"

        fun isEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_DIGEST_ENABLED, true)
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DIGEST_ENABLED, enabled).apply()
        }

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<ProactiveDigestWorker>(1, TimeUnit.HOURS)
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }
    }
}
