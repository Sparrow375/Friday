package com.friday.assistant.core

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class FridayApplication : Application() {

    val database: FridayDatabase by lazy { FridayDatabase.getDatabase(this) }
    val fridayDao: FridayDao by lazy { database.fridayDao() }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Friday Assistant Channel"
            val descriptionText = "Channel for Friday foreground voice and overlay UI service"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "friday_service_channel"
    }
}
