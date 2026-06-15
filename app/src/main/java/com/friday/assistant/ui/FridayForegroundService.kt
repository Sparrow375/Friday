package com.friday.assistant.ui

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.friday.assistant.R
import com.friday.assistant.core.FridayApplication
import com.friday.assistant.ui.screens.MainActivity

/**
 * A dedicated lightweight foreground Service that holds the persistent notification
 * for the Friday assistant. Keeping this separate from FridayService (AccessibilityService)
 * is mandatory because calling startForeground() on an AccessibilityService causes Android
 * to mark it as "Not working" and can crash it on some devices/API levels.
 */
class FridayForegroundService : Service() {

    companion object {
        private const val TAG = "FridayFgService"
        const val NOTIFICATION_ID = 2026

        fun start(context: Context) {
            val hasMicPerm = context.checkSelfPermission(
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasMicPerm) {
                Log.w(TAG, "RECORD_AUDIO not granted – skipping foreground start")
                return
            }
            val intent = Intent(context, FridayForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start FridayForegroundService", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FridayForegroundService::class.java))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand – promoting to foreground")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Throwable) {
            Log.e(TAG, "startForeground failed", e)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "FridayForegroundService destroyed")
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, FridayApplication.CHANNEL_ID)
            .setContentTitle("Friday Assistant")
            .setContentText("Offline voice assistant is active and listening")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }
}
