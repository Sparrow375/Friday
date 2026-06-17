package com.friday.assistant.tools.system

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import android.net.wifi.WifiManager
import android.bluetooth.BluetoothAdapter
import android.app.NotificationManager
import com.friday.assistant.tools.Tool
import com.friday.assistant.tools.ToolResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class SystemControlsTool(private val context: Context) : Tool {

    companion object {
        private const val TAG = "SystemControlsTool"
    }

    override val name: String = "system_control"
    
    override val description: String = """
        Controls physical device settings including flashlight, flashlight strength level, volume levels, 
        screen brightness, WiFi status, Bluetooth status, Do Not Disturb (DND), screen mirroring (screencast), 
        and battery saver settings.
    """.trimIndent()

    override val parameters: JsonObject = JsonParser.parseString("""
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["toggle_torch", "set_volume", "set_brightness", "toggle_wifi", "toggle_bluetooth", "toggle_dnd", "set_torch_strength", "toggle_screencast", "toggle_power_saver", "lock_phone", "toggle_hotspot"],
              "description": "The system setting control action to execute"
            },
            "value": {
              "type": "string",
              "description": "The setting state or value. For toggle action: 'on' or 'off'. For levels: 'up', 'down', or absolute percentage '0'-'100'."
            }
          },
          "required": ["action"]
        }
    """).asJsonObject

    override suspend fun execute(args: JsonObject): ToolResult {
        val action = args.get("action")?.asString ?: return ToolResult(false, "Missing required parameter: action")
        val value = args.get("value")?.asString

        return try {
            when (action) {
                "toggle_torch" -> toggleTorch(value == "on")
                "set_volume" -> setVolume(value)
                "set_brightness" -> setBrightness(value)
                "toggle_wifi" -> toggleWifi(value == "on")
                "toggle_bluetooth" -> toggleBluetooth(value == "on")
                "toggle_dnd" -> toggleDnd(value == "on")
                "set_torch_strength" -> setTorchStrength(value)
                "toggle_screencast" -> toggleScreencast()
                "toggle_power_saver" -> togglePowerSaver()
                "lock_phone" -> lockPhone()
                "toggle_hotspot" -> toggleHotspot()
                else -> ToolResult(false, "Unknown system action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing system control action: $action", e)
            ToolResult(false, "System control execution failed: ${e.message}")
        }
    }

    private fun lockPhone(): ToolResult {
        val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val adminComponent = android.content.ComponentName(context, com.friday.assistant.core.FridayDeviceAdminReceiver::class.java)
        return if (devicePolicyManager.isAdminActive(adminComponent)) {
            try {
                devicePolicyManager.lockNow()
                ToolResult(true, "Locked the phone screen")
            } catch (e: SecurityException) {
                ToolResult(false, "Failed to lock screen: ${e.message}")
            }
        } else {
            ToolResult(false, "Device Admin permission is not active. Please grant it in Friday settings.")
        }
    }

    private fun toggleTorch(enable: Boolean): ToolResult {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            val cameraId = cameraManager.cameraIdList.firstOrNull()
            if (cameraId != null) {
                cameraManager.setTorchMode(cameraId, enable)
                val status = if (enable) "on" else "off"
                ToolResult(true, "Flashlight has been turned $status")
            } else {
                ToolResult(false, "No camera flash unit found")
            }
        } catch (e: Exception) {
            ToolResult(false, "Failed to toggle flashlight: ${e.message}")
        }
    }

    private fun setVolume(value: String?): ToolResult {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_MUSIC
        val maxVolume = audioManager.getStreamMaxVolume(stream)
        val currentVolume = audioManager.getStreamVolume(stream)

        if (value == null) {
            return ToolResult(true, "Current media volume is ${((currentVolume.toFloat() / maxVolume) * 100).toInt()}%")
        }

        val targetVolume = when (value.lowercase()) {
            "up" -> (currentVolume + (maxVolume * 0.1f).toInt().coerceAtLeast(1)).coerceAtMost(maxVolume)
            "down" -> (currentVolume - (maxVolume * 0.1f).toInt().coerceAtLeast(1)).coerceAtLeast(0)
            "mute" -> 0
            else -> {
                val pct = value.removeSuffix("%").toIntOrNull()
                if (pct != null && pct in 0..100) {
                    (maxVolume * (pct / 100f)).toInt()
                } else {
                    return ToolResult(false, "Invalid volume parameter value: $value")
                }
            }
        }

        audioManager.setStreamVolume(stream, targetVolume, AudioManager.FLAG_SHOW_UI)
        val finalPct = ((targetVolume.toFloat() / maxVolume) * 100).toInt()
        return ToolResult(true, "Media volume has been set to $finalPct%")
    }

    private fun setBrightness(value: String?): ToolResult {
        if (!Settings.System.canWrite(context)) {
            return ToolResult(false, "Permission WRITE_SETTINGS is not granted. Cannot modify brightness.")
        }
        
        val contentResolver = context.contentResolver
        val currentBrightness = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 127)

        if (value == null) {
            return ToolResult(true, "Current screen brightness is ${((currentBrightness / 255f) * 100).toInt()}%")
        }

        val targetBrightness = when (value.lowercase()) {
            "up" -> (currentBrightness + 25).coerceAtMost(255)
            "down" -> (currentBrightness - 25).coerceAtLeast(0)
            else -> {
                val pct = value.removeSuffix("%").toIntOrNull()
                if (pct != null && pct in 0..100) {
                    (255 * (pct / 100f)).toInt()
                } else {
                    return ToolResult(false, "Invalid brightness value: $value")
                }
            }
        }

        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, targetBrightness)
        val finalPct = ((targetBrightness / 255f) * 100).toInt()
        return ToolResult(true, "Screen brightness has been set to $finalPct%")
    }

    private fun toggleWifi(enable: Boolean): ToolResult {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return try {
                val intent = Intent(Settings.Panel.ACTION_WIFI).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ToolResult(true, "Opening Wi-Fi settings panel...")
            } catch (e: Exception) {
                ToolResult(false, "Failed to open Wi-Fi settings panel: ${e.message}")
            }
        }
        return try {
            @Suppress("DEPRECATION")
            wifiManager.isWifiEnabled = enable
            val status = if (enable) "enabled" else "disabled"
            ToolResult(true, "WiFi has been $status")
        } catch (e: Exception) {
            ToolResult(false, "Failed to toggle WiFi: ${e.message}. System restrictions may apply.")
        }
    }

    private fun toggleBluetooth(enable: Boolean): ToolResult {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            ?: return ToolResult(false, "Bluetooth is not supported on this device")
        
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            return try {
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ToolResult(true, "Opening Bluetooth settings...")
            } catch (e: Exception) {
                ToolResult(false, "Failed to open Bluetooth settings: ${e.message}")
            }
        }
        
        return try {
            @Suppress("DEPRECATION")
            if (enable) bluetoothAdapter.enable() else bluetoothAdapter.disable()
            val status = if (enable) "enabled" else "disabled"
            ToolResult(true, "Bluetooth has been $status")
        } catch (e: Exception) {
            ToolResult(false, "Failed to toggle Bluetooth: ${e.message}. System restrictions may apply.")
        }
    }

    private fun toggleHotspot(): ToolResult {
        return try {
            val intent = Intent().apply {
                action = "android.settings.TETHER_SETTINGS"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ToolResult(true, "Opening Hotspot / Tethering settings...")
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ToolResult(true, "Opening Wireless settings...")
            } catch (ex: Exception) {
                ToolResult(false, "Failed to open Hotspot settings: ${ex.message}")
            }
        }
    }

    private fun toggleDnd(enable: Boolean): ToolResult {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            return ToolResult(false, "Access to Notification Policy (DND) is not granted")
        }
        
        val mode = if (enable) {
            NotificationManager.INTERRUPTION_FILTER_NONE
        } else {
            NotificationManager.INTERRUPTION_FILTER_ALL
        }
        notificationManager.setInterruptionFilter(mode)
        val status = if (enable) "activated" else "deactivated"
        return ToolResult(true, "Do Not Disturb (DND) mode has been $status")
    }

    private fun setTorchStrength(value: String?): ToolResult {
        if (value == null) {
            return ToolResult(false, "Missing value for torch strength")
        }
        val pct = value.removeSuffix("%").toIntOrNull() ?: 50
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            val cameraId = cameraManager.cameraIdList.firstOrNull()
            if (cameraId != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                    val maxStrength = characteristics.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
                    if (maxStrength > 1) {
                        val targetStrength = (maxStrength * (pct / 100.0)).toInt().coerceIn(1, maxStrength)
                        cameraManager.turnOnTorchWithStrengthLevel(cameraId, targetStrength)
                        ToolResult(true, "Flashlight strength set to $pct% (level $targetStrength of $maxStrength)")
                    } else {
                        cameraManager.setTorchMode(cameraId, true)
                        ToolResult(true, "Flashlight turned on (device does not support strength adjustment)")
                    }
                } else {
                    cameraManager.setTorchMode(cameraId, true)
                    ToolResult(true, "Flashlight turned on (strength adjustment requires Android 13+)")
                }
            } else {
                ToolResult(false, "No camera flash unit found")
            }
        } catch (e: Exception) {
            ToolResult(false, "Failed to set flashlight strength: ${e.message}")
        }
    }

    private fun toggleScreencast(): ToolResult {
        return try {
            val intent = Intent(Settings.ACTION_CAST_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ToolResult(true, "Opening Screen Cast / Screen Mirroring settings...")
        } catch (e: Exception) {
            ToolResult(false, "Failed to open Screen Cast settings: ${e.message}")
        }
    }

    private fun togglePowerSaver(): ToolResult {
        return try {
            val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ToolResult(true, "Opening Battery Saver settings...")
        } catch (e: Exception) {
            ToolResult(false, "Failed to open Battery Saver settings: ${e.message}")
        }
    }
}
