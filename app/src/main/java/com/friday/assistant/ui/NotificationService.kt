package com.friday.assistant.ui

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationService : NotificationListenerService() {
    companion object {
        private const val TAG = "NotificationService"
        private val activeNotificationsMap = mutableMapOf<String, StatusBarNotification>()
        
        fun getActiveNotificationsList(): List<StatusBarNotification> {
            return activeNotificationsMap.values.toList()
        }
        
        fun getNotificationByKey(key: String): StatusBarNotification? {
            return activeNotificationsMap[key]
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        activeNotificationsMap[sbn.key] = sbn
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        activeNotificationsMap.remove(sbn.key)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification Listener connected")
        try {
            val sbns = activeNotifications
            activeNotificationsMap.clear()
            if (sbns != null) {
                for (sbn in sbns) {
                    activeNotificationsMap[sbn.key] = sbn
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error caching active notifications upon connection", e)
        }
    }
}
