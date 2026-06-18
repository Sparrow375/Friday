package com.friday.assistant.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class FridayAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FridayAccessibility"
    }

    // AccessibilityService does not expose a mainHandler — declare one explicitly.
    private val mainHandler = Handler(Looper.getMainLooper())

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

    /**
     * Attempts to trigger media playback inside the currently-active app by searching for a
     * Play/Resume button and clicking it. Used as a Tier-3 fallback when deep links fail.
     *
     * Strategy:
     * 1. Look for nodes whose content-description or text contains play/resume keywords.
     * 2. Prefer clickable nodes; walk up the parent chain if the matched node is not clickable.
     * 3. Returns true when a node was found and clicked, false otherwise.
     */
    fun postInAppPlay(callback: (Boolean) -> Unit) {
        mainHandler.post {
            try {
                val root = rootInActiveWindow
                if (root == null) { callback(false); return@post }

                val playLabels = listOf("play", "resume", "start playback", "play/pause")
                var clicked = false

                for (label in playLabels) {
                    // Search by content description (icon buttons use this)
                    val nodes = root.findAccessibilityNodeInfosByText(label)
                    if (!nodes.isNullOrEmpty()) {
                        for (node in nodes) {
                            val target = findClickableNode(node) ?: continue
                            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                                clicked = true
                                break
                            }
                        }
                    }
                    if (clicked) break
                }

                callback(clicked)
            } catch (e: Exception) {
                Log.e(TAG, "postInAppPlay failed", e)
                callback(false)
            }
        }
    }

    /**
     * Walks up the accessibility node tree to find the nearest clickable ancestor (or self).
     */
    private fun findClickableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(4) {   // limit traversal depth to avoid infinite loop
            if (current?.isClickable == true) return current
            current = current?.parent
        }
        return null
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
