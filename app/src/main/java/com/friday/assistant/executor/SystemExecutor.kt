package com.friday.assistant.executor

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import java.net.URLEncoder
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

class SystemExecutor(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /**
     * Executes the system controls or deep linking based on parameters.
     * Returns a text response summarizing the outcome (e.g. "Volume set to 60%").
     */
    fun executeVolume(params: Map<String, String>): String {
        val action = params["action"]
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        return when (action) {
            "set" -> {
                val levelPercent = params["level"]?.toIntOrNull() ?: 50
                val targetLevel = (maxVolume * (levelPercent / 100f)).toInt()
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetLevel, AudioManager.FLAG_SHOW_UI)
                "I've set the volume to $levelPercent percent."
            }
            "up" -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "Turning the volume up."
            }
            "down" -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "Turning the volume down."
            }
            "mute" -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                } else {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                }
                "System muted."
            }
            "unmute" -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                }
                "System unmuted."
            }
            else -> "Volume is currently at ${(currentVolume * 100f / maxVolume).toInt()} percent."
        }
    }

    fun executeTorch(params: Map<String, String>): String {
        val state = params["state"] ?: "on"
        return try {
            val cameraId = cameraManager.cameraIdList[0]
            if (state == "on") {
                cameraManager.setTorchMode(cameraId, true)
                "Flashlight is now on."
            } else {
                cameraManager.setTorchMode(cameraId, false)
                "Flashlight turned off."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling torch", e)
            "Sorry, I couldn't toggle your flashlight."
        }
    }

    @SuppressLint("WifiManagerPotentialLeak")
    fun executeWifi(params: Map<String, String>): String {
        val state = params["state"] ?: "on"
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // Android 10+ requires opening settings panel
            val panelIntent = Intent(Settings.Panel.ACTION_WIFI)
            panelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(panelIntent)
            "Opening your Wi-Fi control panel."
        } else {
            try {
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = (state == "on")
                if (state == "on") "Wi-Fi enabled." else "Wi-Fi disabled."
            } catch (e: Exception) {
                "Failed to change Wi-Fi state: ${e.message}"
            }
        }
    }

    fun executeBluetooth(params: Map<String, String>): String {
        val state = params["state"] ?: "on"
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
            ?: return "Your device does not support Bluetooth."

        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ requires launching settings
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Opening Bluetooth settings."
        } else {
            try {
                @SuppressLint("MissingPermission")
                if (state == "on") {
                    @Suppress("DEPRECATION")
                    bluetoothAdapter.enable()
                    "Bluetooth enabled."
                } else {
                    @Suppress("DEPRECATION")
                    bluetoothAdapter.disable()
                    "Bluetooth disabled."
                }
            } catch (e: Exception) {
                "I couldn't modify Bluetooth state. Opening settings instead."
            }
        }
    }

    fun executeDnd(params: Map<String, String>): String {
        val state = params["state"] ?: "on"
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return "Please grant Do Not Disturb permissions to Friday."
        }

        return try {
            if (state == "on") {
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                "Do Not Disturb enabled."
            } else {
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                "Do Not Disturb disabled."
            }
        } catch (e: Exception) {
            "Error setting DND filter: ${e.message}"
        }
    }

    fun executeBrightness(params: Map<String, String>): String {
        val action = params["action"]
        val contentResolver = context.contentResolver
        
        if (!Settings.System.canWrite(context)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return "I need screen modify permissions to adjust brightness."
        }

        try {
            val currentBrightness = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            return when (action) {
                "set" -> {
                    val levelPercent = params["level"]?.toIntOrNull() ?: 50
                    val targetValue = (255 * (levelPercent / 100f)).toInt()
                    Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, targetValue)
                    "Brightness set to $levelPercent percent."
                }
                "up" -> {
                    val targetValue = (currentBrightness + 51).coerceAtMost(255)
                    Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, targetValue)
                    "Increasing brightness."
                }
                "down" -> {
                    val targetValue = (currentBrightness - 51).coerceAtLeast(0)
                    Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, targetValue)
                    "Dimming brightness."
                }
                else -> "Screen brightness is currently at ${(currentBrightness * 100 / 255)} percent."
            }
        } catch (e: Exception) {
            return "Error adjusting brightness: ${e.localizedMessage}"
        }
    }

    fun executeBattery(): String {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "You have $level percent battery remaining."
    }

    fun executeClipboard(params: Map<String, String>): String {
        val action = params["action"] ?: "read"
        return if (action == "copy") {
            val text = params["text"] ?: ""
            if (text.isNotEmpty()) {
                val clip = ClipData.newPlainText("Friday Clip", text)
                clipboardManager.setPrimaryClip(clip)
                "Saved \"$text\" to your clipboard."
            } else {
                "Nothing to copy."
            }
        } else {
            val clipData = clipboardManager.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val clipText = clipData.getItemAt(0).text.toString()
                "Clipboard contents read: \"$clipText\"."
            } else {
                "Your clipboard is currently empty."
            }
        }
    }

    fun executeLaunchApp(packageName: String, appName: String): String {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            "Opening $appName."
        } else {
            // Attempt to search app in play store
            try {
                val storeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(storeIntent)
                "I couldn't find $appName on your device, opening Play Store details."
            } catch (e: Exception) {
                "I couldn't open $appName. It might not be installed."
            }
        }
    }

    fun executeDeepLink(params: Map<String, String>): String {
        val app = params["app"] ?: ""
        val query = params["query"] ?: ""
        val name = params["name"] ?: ""

        when (app) {
            "spotify" -> {
                return try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${URLEncoder.encode(query, "UTF-8")}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    "Searching Spotify for $query."
                } catch (e: Exception) {
                    // Fallback web intent
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/${URLEncoder.encode(query, "UTF-8")}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    "Opening Spotify search for $query."
                }
            }

            "whatsapp" -> {
                // WhatsApp deep link. If contact name is given, we open whatsapp chat list or search.
                // We can open contact selection or direct message if we query contacts (needs CONTACTS permission)
                // To keep it simple, we open WhatsApp search panel or chat search
                return try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?text=")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    "Opening WhatsApp for $name."
                } catch (e: Exception) {
                    executeLaunchApp("com.whatsapp", "WhatsApp")
                }
            }

            "brave" -> {
                // Open YouTube results in Brave
                return try {
                    val searchUrl = "https://www.youtube.com/results?search_query=${URLEncoder.encode(query, "UTF-8")}"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                        setPackage("com.brave.browser")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    "Searching YouTube on Brave browser."
                } catch (e: Exception) {
                    // General web search in Brave
                    val searchUrl = "https://www.google.com/search?q=${URLEncoder.encode(query, "UTF-8")}"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                        setPackage("com.brave.browser")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    "Searching Brave for $query."
                }
            }

            "reddit" -> {
                return try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("reddit://search?q=${URLEncoder.encode(query, "UTF-8")}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    "Searching Reddit for $query."
                } catch (e: Exception) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.reddit.com/search/?q=${URLEncoder.encode(query, "UTF-8")}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    "Opening Reddit search for $query."
                }
            }

            "chrome_x" -> {
                // Search X (Twitter) on Chrome
                return try {
                    val searchUrl = "https://x.com/search?q=${URLEncoder.encode(query, "UTF-8")}"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                        setPackage("com.android.chrome")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    "Searching X on Chrome."
                } catch (e: Exception) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://x.com/search?q=${URLEncoder.encode(query, "UTF-8")}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    "Opening X search in browser."
                }
            }
        }
        return "Command executed."
    }

    fun executeAlarmTimer(params: Map<String, String>): String {
        val type = params["type"] ?: "timer"
        
        return if (type == "timer") {
            val durationStr = params["duration"] ?: "0"
            val duration = durationStr.toIntOrNull() ?: 0
            val unit = params["unit"] ?: "minute"

            val durationSeconds = when (unit) {
                "second" -> duration
                "hour" -> duration * 3600
                else -> duration * 60 // minutes
            }

            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, durationSeconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, "Friday Timer")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                "Setting a $duration $unit timer."
            } catch (e: Exception) {
                "Sorry, I couldn't launch the system timer."
            }
        } else {
            // Alarm
            val timeStr = params["time"] ?: ""
            // Simple parsing of time (e.g. "8 am", "7:30 pm")
            // To keep it simple, we launch alarm settings or try custom parsing
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_MESSAGE, "Friday Alarm")
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                "Opening alarm creation for $timeStr."
            } catch (e: Exception) {
                "Couldn't set the alarm."
            }
        }
    }

    fun executeCall(params: Map<String, String>): String {
        val contactName = params["name"] ?: ""
        if (contactName.isEmpty()) {
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialIntent)
            return "Opening your phone dialer."
        }

        var phoneNumber: String? = null
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            try {
                val cursor = context.contentResolver.query(
                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER, android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                    "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                    arrayOf("%$contactName%"),
                    null
                )
                cursor?.use {
                    if (it.moveToFirst()) {
                        val numIndex = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                        if (numIndex >= 0) {
                            phoneNumber = it.getString(numIndex)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying contacts", e)
            }
        }

        if (phoneNumber != null) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(callIntent)
                return "Calling $contactName."
            } else {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
                return "Opening dialer for $contactName."
            }
        } else {
            return try {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${URLEncoder.encode(contactName, "UTF-8")}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
                "I couldn't find $contactName in your contacts, opening dialer."
            } catch (e: Exception) {
                "Failed to place call to $contactName."
            }
        }
    }

    companion object {
        private const val TAG = "SystemExecutor"
    }
}
