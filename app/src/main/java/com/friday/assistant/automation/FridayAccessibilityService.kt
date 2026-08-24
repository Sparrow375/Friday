package com.friday.assistant.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.friday.assistant.ui.FridayService

class FridayAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FridayAccessibility"
        private const val DOUBLE_CLICK_TIME_DELTA = 500L
    }

    // AccessibilityService does not expose a mainHandler — declare one explicitly.
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastVolumeDownTime = 0L
    private var lastVolumeUpTime = 0L

    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize vibrator", e)
            null
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = info.flags or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        serviceInfo = info
        AutomationBridge.bind(this)
        com.friday.assistant.core.FridayLogger.i(TAG, "Friday UI automation service connected with key event filtering (flags=${info.flags})")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onGesture(gestureId: Int): Boolean {
        com.friday.assistant.core.FridayLogger.i(TAG, "Accessibility gesture detected: $gestureId")
        val prefs = getSharedPreferences("friday_assistant_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("gesture_activation_enabled", true) && prefs.getBoolean("assistant_enabled", true)) {
            performHapticFeedback(prefs.getBoolean("haptic_feedback_enabled", true))
            triggerAssistantActivation()
            return true
        }
        return super.onGesture(gestureId)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action
        val repeatCount = event.repeatCount

        // Log volume key events for diagnostics
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            com.friday.assistant.core.FridayLogger.d(TAG, "onKeyEvent: keyCode=$keyCode, action=$action, repeat=$repeatCount")
        }

        val prefs = getSharedPreferences("friday_assistant_prefs", Context.MODE_PRIVATE)
        val gestureEnabled = prefs.getBoolean("gesture_activation_enabled", true)
        val assistantEnabled = prefs.getBoolean("assistant_enabled", true)

        if (!gestureEnabled || !assistantEnabled) {
            return super.onKeyEvent(event)
        }

        val triggerMode = prefs.getString("gesture_trigger_mode", "volume_down_double") ?: "volume_down_double"
        val hapticEnabled = prefs.getBoolean("haptic_feedback_enabled", true)
        val currentTime = SystemClock.uptimeMillis()

        if (action == KeyEvent.ACTION_DOWN) {
            // Check double-tap triggers (repeatCount must be 0 to avoid false fires on holding the button)
            if (repeatCount == 0) {
                when (keyCode) {
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        val supportsVolumeDown = triggerMode == "volume_down_double" || triggerMode == "volume_any_double"
                        if (supportsVolumeDown) {
                            val diff = currentTime - lastVolumeDownTime
                            if (diff in 40L..DOUBLE_CLICK_TIME_DELTA) {
                                com.friday.assistant.core.FridayLogger.i(TAG, "Volume Down double-tap gesture detected! (diff=${diff}ms)")
                                lastVolumeDownTime = 0L
                                performHapticFeedback(hapticEnabled)
                                triggerAssistantActivation()
                                return true
                            }
                            lastVolumeDownTime = currentTime
                        }
                    }
                    KeyEvent.KEYCODE_VOLUME_UP -> {
                        val supportsVolumeUp = triggerMode == "volume_up_double" || triggerMode == "volume_any_double"
                        if (supportsVolumeUp) {
                            val diff = currentTime - lastVolumeUpTime
                            if (diff in 40L..DOUBLE_CLICK_TIME_DELTA) {
                                com.friday.assistant.core.FridayLogger.i(TAG, "Volume Up double-tap gesture detected! (diff=${diff}ms)")
                                lastVolumeUpTime = 0L
                                performHapticFeedback(hapticEnabled)
                                triggerAssistantActivation()
                                return true
                            }
                            lastVolumeUpTime = currentTime
                        }
                    }
                }
            } else if (triggerMode == "volume_down_long" && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && repeatCount == 1) {
                // Long-press detection on repeatCount == 1 (triggered after ~500ms continuous hold)
                com.friday.assistant.core.FridayLogger.i(TAG, "Volume Down long-press gesture detected!")
                performHapticFeedback(hapticEnabled)
                triggerAssistantActivation()
                return true
            }
        }

        return super.onKeyEvent(event)
    }

    private fun performHapticFeedback(enabled: Boolean) {
        if (!enabled) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(35L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Haptic vibration failed", e)
        }
    }

    private fun triggerAssistantActivation() {
        mainHandler.post {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            val isLocked = km?.isKeyguardLocked == true
            val service = FridayService.instance

            if (isLocked) {
                com.friday.assistant.core.FridayLogger.i(TAG, "Device locked — launching TriggerActivity with lockscreen permissions")
                try {
                    val triggerIntent = Intent(this, com.friday.assistant.ui.TriggerActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    }
                    startActivity(triggerIntent)
                } catch (e: Exception) {
                    com.friday.assistant.core.FridayLogger.e(TAG, "Failed to launch TriggerActivity on lockscreen", e)
                }
                if (service != null) {
                    FridayService.triggerGestureActivation()
                }
            } else if (service != null) {
                FridayService.triggerGestureActivation()
            } else {
                com.friday.assistant.core.FridayLogger.w(TAG, "FridayService instance null in gesture; launching TriggerActivity")
                try {
                    val triggerIntent = Intent(this, com.friday.assistant.ui.TriggerActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    }
                    startActivity(triggerIntent)
                } catch (e: Exception) {
                    com.friday.assistant.core.FridayLogger.e(TAG, "Failed to start TriggerActivity from gesture", e)
                }
            }
        }
    }

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
                // Wait for QS panel to partially expand
                Thread.sleep(800)

                var root = rootInActiveWindow
                var tile = if (root != null) findQsTileNode(root, label) else null
                var expanded = false

                if (tile == null) {
                    Log.i(TAG, "QS tile not found in collapsed panel for '$label'. Expanding QS panel...")
                    mainHandler.post { performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) }
                    Thread.sleep(600)
                    root = rootInActiveWindow
                    tile = if (root != null) findQsTileNode(root, label) else null
                    expanded = true
                }

                if (tile == null) {
                    Log.w(TAG, "QS tile not found for label='$label' after expansion")
                    mainHandler.post { performGlobalAction(GLOBAL_ACTION_BACK) }
                    callback(false)
                    return@Thread
                }

                // Determine current state — avoid double-toggle
                val isCurrentlyEnabled = tile.isChecked || tile.isSelected ||
                    tile.contentDescription?.toString()?.lowercase()
                        ?.let { 
                            (it.contains("on") || it.contains("active") || it.contains("connected") || it.contains("enabled")) && 
                            !it.contains("off") && !it.contains("inactive") && !it.contains("disconnected") && !it.contains("disabled")
                        } ?: false

                val needsClick = (enable && !isCurrentlyEnabled) || (!enable && isCurrentlyEnabled)
                var clicked = false
                if (needsClick) {
                    val target = findClickableNode(tile) ?: tile
                    mainHandler.post { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                    Thread.sleep(700)
                    clicked = true
                } else {
                    clicked = true // already in desired state
                }

                Thread.sleep(200)
                mainHandler.post { performGlobalAction(GLOBAL_ACTION_BACK) }
                
                // If we expanded the panel fully, we might need a second BACK to close it completely
                Thread.sleep(100)
                val currentRoot = rootInActiveWindow
                if (currentRoot != null && currentRoot.packageName?.toString()?.contains("systemui", ignoreCase = true) == true) {
                    Log.d(TAG, "SystemUI still focused, sending second back to close panel")
                    mainHandler.post { performGlobalAction(GLOBAL_ACTION_BACK) }
                }

                callback(clicked)
            } catch (e: Exception) {
                Log.e(TAG, "Quick setting toggle failed for $label", e)
                try { mainHandler.post { performGlobalAction(GLOBAL_ACTION_BACK) } } catch (_: Exception) {}
                try {
                    Thread.sleep(200)
                    val currentRoot = rootInActiveWindow
                    if (currentRoot != null && currentRoot.packageName?.toString()?.contains("systemui", ignoreCase = true) == true) {
                        mainHandler.post { performGlobalAction(GLOBAL_ACTION_BACK) }
                    }
                } catch (_: Exception) {}
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
        val targets = qsTileNames(label).map { it.lowercase() }

        // Strategy 1: fast text search
        for (name in targets) {
            val nodes = root.findAccessibilityNodeInfosByText(name)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    val clickable = findClickableNode(node)
                    if (clickable != null) return clickable
                }
            }
        }

        // Strategy 2: full tree walk — matches contentDescription and viewIdResourceName
        val match = walkTree(root) { node ->
            val cd = node.contentDescription?.toString()?.lowercase() ?: ""
            val txt = node.text?.toString()?.lowercase() ?: ""
            val rid = node.viewIdResourceName?.lowercase() ?: ""
            
            targets.any { t ->
                cd.contains(t) || txt.contains(t) || rid.contains(t)
            }
        }
        if (match != null) {
            return findClickableNode(match) ?: match
        }

        return null
    }

    private fun qsTileNames(label: String): List<String> = when (label.lowercase()) {
        "wifi", "wi-fi"        -> listOf("Wi-Fi", "WiFi", "WLAN", "Internet", "Wi‑Fi")
        "bluetooth"            -> listOf("Bluetooth", "BT")
        "hotspot"              -> listOf("Mobile Hotspot", "Hotspot", "Personal Hotspot", "Tethering")
        "airplane", "airplane_mode", "flight mode"
                               -> listOf("Airplane mode", "Flight mode", "Aeroplane mode")
        "mobile_data", "data"  -> listOf("Mobile data", "Cellular data", "Data", "Mobile Data")
        "dnd", "do not disturb"-> listOf("Do Not Disturb", "DND", "Do not disturb")
        else                   -> listOf(label)
    }

    /**
     * Dispatches a real physical screen tap gesture at coordinates (x, y).
     * Works across standard Views, Jetpack Compose, Flutter, and custom Views.
     */
    fun dispatchTap(x: Float, y: Float, callback: ((Boolean) -> Unit)? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = android.graphics.Path().apply {
                moveTo(x, y)
            }
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100)
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(stroke)
                .build()

            val dispatched = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "dispatchTap completed at ($x, $y)")
                    callback?.invoke(true)
                }

                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w(TAG, "dispatchTap cancelled at ($x, $y)")
                    callback?.invoke(false)
                }
            }, null)
            if (!dispatched) callback?.invoke(false)
        } else {
            callback?.invoke(false)
        }
    }

    /**
     * Tries to find and click the play button or first search result in Spotify.
     * Polls rootInActiveWindow for Spotify up to [timeoutMs] ms, then clicks the appropriate play element.
     */
    fun postSpotifyAutoPlay(query: String, timeoutMs: Long = 7000L, callback: (Boolean) -> Unit) {
        Thread {
            val deadline = System.currentTimeMillis() + timeoutMs
            var success = false

            while (System.currentTimeMillis() < deadline) {
                try {
                    val root = rootInActiveWindow
                    if (root != null) {
                        val pkg = root.packageName?.toString() ?: ""
                        if (pkg.contains("spotify", ignoreCase = true)) {
                            val playNode = findSpotifyPlayElement(root, query)
                            if (playNode != null) {
                                val rect = android.graphics.Rect()
                                playNode.getBoundsInScreen(rect)

                                val latch = java.util.concurrent.CountDownLatch(1)
                                mainHandler.post {
                                    val target = findClickableNode(playNode) ?: playNode
                                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    if (rect.width() > 0 && rect.height() > 0) {
                                        dispatchTap(rect.centerX().toFloat(), rect.centerY().toFloat())
                                    }
                                    latch.countDown()
                                }
                                latch.await(600, java.util.concurrent.TimeUnit.MILLISECONDS)
                                success = true
                                Log.i(TAG, "Spotify auto-play element clicked successfully at $rect")
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "postSpotifyAutoPlay poll error", e)
                }
                Thread.sleep(200)
            }
            callback(success)
        }.start()
    }

    private fun findSpotifyPlayElement(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val playButtons = listOf("play", "shuffle play", "shuffle", "resume", "play song", "play track")

        // Strategy 1: Look for "Play" in contentDescription
        val byContentDesc = walkTree(root) { node ->
            val cd = node.contentDescription?.toString()?.lowercase() ?: ""
            playButtons.any { cd == it || cd.startsWith("$it ") || cd.contains("play") }
        }
        if (byContentDesc != null) return byContentDesc

        // Strategy 2: Look for play button viewIdResourceName
        val playIds = listOf("play_button", "button_play_and_pause", "play_pause", "btn_play", "row_view", "card_view")
        val byId = walkTree(root) { node ->
            val rid = node.viewIdResourceName?.lowercase() ?: ""
            playIds.any { rid.contains(it) } && node.isClickable
        }
        if (byId != null) return byId

        // Strategy 3: Look for "Play" in text explicitly
        for (btnText in playButtons) {
            val nodes = root.findAccessibilityNodeInfosByText(btnText)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    if (node.isClickable) return node
                    val clickable = findClickableNode(node)
                    if (clickable != null) return clickable
                }
            }
        }

        // Strategy 4: Search for nodes containing words from the query text
        if (query.isNotEmpty()) {
            val queryWords = query.lowercase().split(" ").filter { it.length > 2 }
            for (w in queryWords) {
                val nodes = root.findAccessibilityNodeInfosByText(w)
                if (!nodes.isNullOrEmpty()) {
                    for (node in nodes) {
                        val clickable = findClickableNode(node)
                        if (clickable != null) return clickable
                    }
                }
            }
        }

        // Strategy 5: Find a list item or card in RecyclerView
        val recyclerView = walkTree(root) { node ->
            node.className?.toString()?.contains("RecyclerView") == true
        }
        if (recyclerView != null && recyclerView.childCount > 0) {
            for (i in 0 until minOf(3, recyclerView.childCount)) {
                val child = recyclerView.getChild(i) ?: continue
                val clickable = findClickableNode(child) ?: (if (child.isClickable) child else null)
                if (clickable != null) return clickable
            }
        }

        return null
    }

    /**
     * Tries to find and click the first video in YouTube search results.
     */
    fun postYouTubeAutoPlay(query: String, timeoutMs: Long = 7000L, callback: (Boolean) -> Unit) {
        Thread {
            val deadline = System.currentTimeMillis() + timeoutMs
            var success = false

            while (System.currentTimeMillis() < deadline) {
                try {
                    val root = rootInActiveWindow
                    if (root != null) {
                        val pkg = root.packageName?.toString() ?: ""
                        if (pkg.contains("youtube", ignoreCase = true)) {
                            val videoNode = findYouTubeVideoElement(root, query)
                            if (videoNode != null) {
                                val rect = android.graphics.Rect()
                                videoNode.getBoundsInScreen(rect)

                                val latch = java.util.concurrent.CountDownLatch(1)
                                mainHandler.post {
                                    val target = findClickableNode(videoNode) ?: videoNode
                                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    if (rect.width() > 0 && rect.height() > 0) {
                                        dispatchTap(rect.centerX().toFloat(), rect.centerY().toFloat())
                                    }
                                    latch.countDown()
                                }
                                latch.await(600, java.util.concurrent.TimeUnit.MILLISECONDS)
                                success = true
                                Log.i(TAG, "YouTube video auto-play clicked successfully at $rect")
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "postYouTubeAutoPlay poll error", e)
                }
                Thread.sleep(200)
            }
            callback(success)
        }.start()
    }

    private fun findYouTubeVideoElement(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        // Strategy 1: Find video title/thumbnail matching query keywords
        if (query.isNotEmpty()) {
            val queryWords = query.lowercase().split(" ").filter { it.length > 2 }
            for (w in queryWords) {
                val nodes = root.findAccessibilityNodeInfosByText(w)
                if (!nodes.isNullOrEmpty()) {
                    for (node in nodes) {
                        val clickable = findClickableNode(node)
                        if (clickable != null) return clickable
                    }
                }
            }
        }

        // Strategy 2: Look for viewId containing video/item/thumbnail/result
        val videoIds = listOf("video_title", "thumbnail", "results", "item_layout", "grid_layout")
        val byId = walkTree(root) { node ->
            val rid = node.viewIdResourceName?.lowercase() ?: ""
            videoIds.any { rid.contains(it) } && (node.isClickable || node.parent?.isClickable == true)
        }
        if (byId != null) return findClickableNode(byId) ?: byId

        // Strategy 3: Find first clickable child in RecyclerView
        val recyclerView = walkTree(root) { node ->
            node.className?.toString()?.contains("RecyclerView") == true
        }
        if (recyclerView != null && recyclerView.childCount > 0) {
            for (i in 0 until minOf(4, recyclerView.childCount)) {
                val child = recyclerView.getChild(i) ?: continue
                val clickable = findClickableNode(child) ?: (if (child.isClickable) child else null)
                if (clickable != null) return clickable
            }
        }

        return null
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
     * to appear and taps it autonomously using both Accessibility Action and hardware Touch Tap.
     * Polls for up to [timeoutMs] ms.
     */
    fun postWhatsAppSend(timeoutMs: Long = 7000L, callback: (Boolean) -> Unit) {
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
                                val rect = android.graphics.Rect()
                                sendNode.getBoundsInScreen(rect)

                                val latch = java.util.concurrent.CountDownLatch(1)
                                mainHandler.post {
                                    val target = findClickableNode(sendNode) ?: sendNode
                                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    if (rect.width() > 0 && rect.height() > 0) {
                                        dispatchTap(rect.centerX().toFloat(), rect.centerY().toFloat())
                                    }
                                    latch.countDown()
                                }
                                latch.await(600, java.util.concurrent.TimeUnit.MILLISECONDS)
                                sent = true
                                Log.i(TAG, "WhatsApp Send button clicked successfully! (bounds: $rect)")
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "postWhatsAppSend poll error", e)
                }
                Thread.sleep(150)
            }

            callback(sent)
        }.start()
    }

    private fun findWhatsAppSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Strategy 1: Search by content description (WhatsApp's icon button uses contentDescription="Send")
        val byContentDesc = walkTree(root) { node ->
            val cd = node.contentDescription?.toString()?.trim() ?: ""
            cd.equals("Send", ignoreCase = true) || cd.equals("send", ignoreCase = true) || cd.contains("send message", ignoreCase = true) || cd.contains("send", ignoreCase = true)
        }
        if (byContentDesc != null) return byContentDesc

        // Strategy 2: Search by viewIdResourceName
        val sendIds = listOf("send", "send_btn", "conversation_entry_action_button", "entry_action", "send_button")
        val byId = walkTree(root) { node ->
            val rid = node.viewIdResourceName?.lowercase() ?: ""
            sendIds.any { rid.endsWith(it) || rid.contains(it) }
        }
        if (byId != null) return byId

        // Strategy 3: Search by visible text
        val sendLabels = listOf("Send", "send")
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

        // Strategy 4: Find ImageButton / ImageView at bottom-right corner of screen
        val screenBounds = android.graphics.Rect()
        root.getBoundsInScreen(screenBounds)
        val screenWidth = screenBounds.width()
        val screenHeight = screenBounds.height()

        if (screenWidth > 0 && screenHeight > 0) {
            val bottomCornerButton = walkTree(root) { node ->
                val nodeRect = android.graphics.Rect()
                node.getBoundsInScreen(nodeRect)
                val isBottom = nodeRect.bottom > screenHeight * 0.50
                val isRight = nodeRect.right > screenWidth * 0.70
                val isButton = node.className?.toString()?.contains("Button") == true ||
                    node.className?.toString()?.contains("Image") == true ||
                    node.isClickable
                isBottom && isRight && isButton && nodeRect.width() > 30 && nodeRect.height() > 30
            }
            if (bottomCornerButton != null) return bottomCornerButton
        }

        return null
    }
}
