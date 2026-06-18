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
            // GLOBAL_ACTION_TAKE_SCREENSHOT is fire-and-forget — the system captures
            // asynchronously. We dispatch and immediately return true; checking the
            // return value of performGlobalAction only tells us if the dispatch succeeded,
            // not whether the capture completed.
            val dispatched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            } else {
                false
            }
            callback(dispatched)
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
        // Run entirely on a background thread — Thread.sleep() on the main thread
        // blocks rootInActiveWindow from refreshing and prevents click delivery.
        Thread {
            try {
                mainHandler.post { performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) }
                // Wait for QS panel to fully expand
                Thread.sleep(700)

                val root = rootInActiveWindow ?: run {
                    mainHandler.post { performGlobalAction(GLOBAL_ACTION_BACK) }
                    callback(false)
                    return@Thread
                }

                val tile = findQsTileNode(root, label)
                if (tile == null) {
                    Log.w(TAG, "QS tile not found for label='$label'")
                    mainHandler.post { performGlobalAction(GLOBAL_ACTION_BACK) }
                    callback(false)
                    return@Thread
                }

                // Determine current state — avoid double-toggle
                val isCurrentlyEnabled = tile.isChecked || tile.isSelected ||
                    tile.contentDescription?.toString()?.lowercase()
                        ?.let { it.contains("on") && !it.contains("off") } ?: false

                val needsClick = (enable && !isCurrentlyEnabled) || (!enable && isCurrentlyEnabled)
                var clicked = false
                if (needsClick) {
                    val target = findClickableNode(tile) ?: tile
                    mainHandler.post { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                    Thread.sleep(400)
                    clicked = true
                } else {
                    clicked = true // already in desired state
                }

                Thread.sleep(200)
                mainHandler.post { performGlobalAction(GLOBAL_ACTION_BACK) }
                callback(clicked)
            } catch (e: Exception) {
                Log.e(TAG, "Quick setting toggle failed for $label", e)
                try { mainHandler.post { performGlobalAction(GLOBAL_ACTION_BACK) } } catch (_: Exception) {}
                callback(false)
            }
        }.start()
    }

    /**
     * Finds a Quick Settings tile node using multiple search strategies:
     * 1. findAccessibilityNodeInfosByText (visible text label)
     * 2. Full tree walk matching contentDescription, text, or viewIdResourceName
     *    — covers Samsung One UI, AOSP, and Pixel QS implementations.
     */
    private fun findQsTileNode(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        val targets = qsTileNames(label)

        // Strategy 1: fast text search
        for (name in targets) {
            val nodes = root.findAccessibilityNodeInfosByText(name)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    val clickable = findClickableNode(node)
                    if (clickable != null) return clickable
                }
                // Return first match even if not directly clickable — caller will walk up
                return nodes[0]
            }
        }

        // Strategy 2: full tree walk — matches contentDescription and viewIdResourceName
        return walkTree(root) { node ->
            val cd = node.contentDescription?.toString()?.lowercase() ?: ""
            val txt = node.text?.toString()?.lowercase() ?: ""
            val rid = node.viewIdResourceName?.lowercase() ?: ""
            targets.any { t ->
                val tl = t.lowercase()
                cd.contains(tl) || txt.contains(tl) || rid.contains(tl)
            }
        }
    }

    private fun qsTileNames(label: String): List<String> = when (label.lowercase()) {
        "wifi", "wi-fi"        -> listOf("Wi-Fi", "WiFi", "WLAN", "Internet")
        "bluetooth"            -> listOf("Bluetooth")
        "hotspot"              -> listOf("Mobile Hotspot", "Hotspot", "Personal Hotspot", "Tethering")
        "airplane", "airplane_mode", "flight mode"
                               -> listOf("Airplane mode", "Flight mode", "Aeroplane mode")
        "mobile_data", "data"  -> listOf("Mobile data", "Cellular data", "Data", "Mobile Data")
        "dnd", "do not disturb"-> listOf("Do Not Disturb", "DND", "Do not disturb")
        else                   -> listOf(label)
    }

    /** DFS tree walk; returns first node for which [predicate] returns true. */
    private fun walkTree(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = walkTree(child, predicate)
            if (result != null) return result
        }
        return null
    }

    /**
     * After a WhatsApp chat is opened (via deep link), waits for the send button
     * to appear and taps it. Polls for up to [timeoutMs] ms.
     */
    fun postWhatsAppSend(timeoutMs: Long = 4000L, callback: (Boolean) -> Unit) {
        // Run on a background thread — polling with Thread.sleep() on the main thread
        // blocks rootInActiveWindow from seeing the WhatsApp window.
        Thread {
            val deadline = System.currentTimeMillis() + timeoutMs
            var sent = false

            while (System.currentTimeMillis() < deadline) {
                try {
                    val root = rootInActiveWindow
                    if (root != null) {
                        val pkg = root.packageName?.toString() ?: ""
                        if (pkg.contains("whatsapp", ignoreCase = true)) {
                            val sendNode = findWhatsAppSendButton(root)
                            if (sendNode != null) {
                                // Perform click on main thread, then wait for it to register
                                var clickResult = false
                                val latch = java.util.concurrent.CountDownLatch(1)
                                mainHandler.post {
                                    clickResult = sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    latch.countDown()
                                }
                                latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                                if (clickResult) { sent = true; break }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "postWhatsAppSend poll error", e)
                }
                Thread.sleep(200)
            }

            callback(sent)
        }.start()
    }

    private fun findWhatsAppSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // WhatsApp send button identifiers across versions
        val sendIds = listOf("send", "send_btn", "conversation_entry_action_button")
        val sendLabels = listOf("Send", "send")

        // viewIdResourceName search
        val byId = walkTree(root) { node ->
            val rid = node.viewIdResourceName?.lowercase() ?: ""
            sendIds.any { rid.endsWith(it) }
        }
        if (byId != null) return byId

        // contentDescription / text search
        for (label in sendLabels) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    if (node.isClickable) return node
                    val parent = findClickableNode(node)
                    if (parent != null) return parent
                }
            }
        }
        return null
    }
}
