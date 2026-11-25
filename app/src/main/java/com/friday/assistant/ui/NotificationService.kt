package com.friday.assistant.ui

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class AppNotificationItem(
    val key: String,
    val appName: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val isOngoing: Boolean
)

class NotificationService : NotificationListenerService() {
    companion object {
        private const val TAG = "NotificationService"
        private const val MAX_ITEMS = 25
        private val activeNotificationsMap = ConcurrentHashMap<String, StatusBarNotification>()

        @Volatile
        private var instance: NotificationService? = null

        private val _notificationsFlow = MutableStateFlow<List<AppNotificationItem>>(emptyList())
        val notificationsFlow: StateFlow<List<AppNotificationItem>> = _notificationsFlow.asStateFlow()

        private val _isConnectedFlow = MutableStateFlow(false)
        val isConnectedFlow: StateFlow<Boolean> = _isConnectedFlow.asStateFlow()

        fun getActiveNotificationsList(): List<StatusBarNotification> {
            return activeNotificationsMap.values.sortedByDescending { it.postTime }
        }

        fun getNotificationByKey(key: String): StatusBarNotification? {
            return activeNotificationsMap[key]
        }

        fun dismissNotification(key: String) {
            try {
                instance?.cancelNotification(key)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cancel notification $key: ${e.message}")
            }
            activeNotificationsMap.remove(key)
            publishSnapshot(instance?.applicationContext)
        }

        private fun publishSnapshot(context: android.content.Context?) {
            val ownPkg = context?.packageName ?: "com.friday.assistant"
            val pm = context?.packageManager
            val items = activeNotificationsMap.values
                .asSequence()
                .filter { sbn ->
                    if (sbn.packageName == ownPkg) return@filter false
                    val flags = sbn.notification.flags
                    val isOngoing = (flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
                        (flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
                    val isGroupSummary = (flags and Notification.FLAG_GROUP_SUMMARY) != 0
                    if (isGroupSummary || isOngoing) return@filter false

                    val extras = sbn.notification.extras
                    val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
                    val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
                    title.isNotEmpty() || text.isNotEmpty()
                }
                .sortedByDescending { it.postTime }
                .take(MAX_ITEMS)
                .map { sbn ->
                    val extras = sbn.notification.extras
                    val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
                    val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
                    val appName = resolveAppName(pm, sbn.packageName)
                    val isOngoing = (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
                    AppNotificationItem(
                        key = sbn.key,
                        appName = appName,
                        packageName = sbn.packageName,
                        title = title.ifEmpty { appName },
                        text = text,
                        postTime = sbn.postTime,
                        isOngoing = isOngoing
                    )
                }
                .toList()
            _notificationsFlow.value = items
        }

        private fun resolveAppName(pm: PackageManager?, pkg: String): String {
            if (pm == null) return pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
            return try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        activeNotificationsMap[sbn.key] = sbn
        publishSnapshot(applicationContext)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        activeNotificationsMap.remove(sbn.key)
        publishSnapshot(applicationContext)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        _isConnectedFlow.value = true
        Log.i(TAG, "Notification Listener connected")
        try {
            val sbns = activeNotifications
            activeNotificationsMap.clear()
            if (sbns != null) {
                for (sbn in sbns) {
                    activeNotificationsMap[sbn.key] = sbn
                }
            }
            publishSnapshot(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Error caching active notifications upon connection", e)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        _isConnectedFlow.value = false
    }
}
