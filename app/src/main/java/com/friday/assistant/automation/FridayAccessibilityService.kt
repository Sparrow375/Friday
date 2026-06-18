package com.friday.assistant.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class FridayAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FridayAccessibility"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        AutomationBridge.bind(this)
        Log.i(TAG, "Friday UI automation service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        AutomationBridge.unbind()
        super.onDestroy()
    }

    fun postTakeScreenshot(callback: (Boolean) -> Unit) {
        mainHandler.post {
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            } else {
                false
            }
            callback(ok)
        }
    }

    fun postToggleQuickSetting(label: String, enable: Boolean, callback: (Boolean) -> Unit) {
        mainHandler.post {
            try {
                // Expand quick settings panel
                if (!performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)) {
                    callback(false)
                    return@post
                }
                Thread.sleep(400)

                val root = rootInActiveWindow ?: run {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    callback(false)
                    return@post
                }

                val toggle = findToggleNode(root, label)
                if (toggle != null) {
                    val clicked = toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Thread.sleep(200)
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    callback(clicked)
                } else {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    callback(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Quick setting toggle failed for $label", e)
                performGlobalAction(GLOBAL_ACTION_BACK)
                callback(false)
            }
        }
    }

    private fun findToggleNode(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        val targets = when (label.lowercase()) {
            "wifi", "wi-fi" -> listOf("Wi-Fi", "WiFi", "WLAN")
            "bluetooth" -> listOf("Bluetooth")
            "hotspot" -> listOf("Mobile Hotspot", "Hotspot", "Tethering")
            else -> listOf(label)
        }
        for (name in targets) {
            val nodes = root.findAccessibilityNodeInfosByText(name)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    if (node.isClickable) return node
                    node.parent?.let { if (it.isClickable) return it }
                }
                return nodes[0]
            }
        }
        return null
    }
}
