package com.friday.assistant.core

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.friday.assistant.core.db.FridayDatabase
import com.friday.assistant.core.native.LlamaEngine
import com.friday.assistant.core.native.WhisperEngine

class FridayApplication : Application() {

    companion object {
        private const val TAG = "FridayApplication"
        const val CHANNEL_ID = "friday_assistant_channel"
        
        lateinit var database: FridayDatabase
            private set

        val llamaEngine by lazy { LlamaEngine() }
        val whisperEngine by lazy { WhisperEngine() }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Initializing Project Friday Application")
        
        // Initialize Database
        database = FridayDatabase.getDatabase(this)
        
        // Setup Notification Channel
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Friday Assistant Service"
            val descriptionText = "Always-on offline voice assistant foreground service"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        }
    }
}
