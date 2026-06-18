package com.friday.assistant.intelligence

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.friday.assistant.core.FridayApplication
import com.friday.assistant.core.native.LlamaEngine
import com.friday.assistant.intelligence.nlu.NluIntentClassifier
import com.friday.assistant.tools.ToolRegistry
import com.friday.assistant.ui.FridayService
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class AgentCore(
    private val context: Context,
    private val memoryManager: MemoryManager
) {
    companion object {
        private const val TAG = "AgentCore"
        private const val MAX_TOOL_LOOPS = 4
    }

    private val llamaEngine = FridayApplication.llamaEngine
    private val promptBuilder = PromptBuilder(memoryManager)
    private val nluClassifier = NluIntentClassifier(context)
    private val semanticRouter = com.friday.assistant.intelligence.nlu.SemanticIntentRouter(context)
    private val modelManager = com.friday.assistant.core.ModelManager(context)

    // Flow to emit streaming output/status events for the UI overlay to show in real time
    private val _agentStatusFlow = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val agentStatusFlow: SharedFlow<String> = _agentStatusFlow.asSharedFlow()

    suspend fun processQuery(userInput: String, onToken: (String) -> Unit = {}): QueryResult {
        com.friday.assistant.core.FridayLogger.i(TAG, "AgentCore processing query: '$userInput'")

        // 0. Resolve anaphoric follow-ups ("turn it off" after "turn on torch")
        val resolvedInput = DialogueStateTracker.resolveFollowUp(userInput)
        if (resolvedInput != userInput) {
            com.friday.assistant.core.FridayLogger.i(TAG, "Follow-up resolved: '$userInput' -> '$resolvedInput'")
        }

        // 1. Core Intent Classification (Semantic Router, NLU Classifier, or Rule-Based Fallback)
        val cleanQuery = resolvedInput.trim().lowercase()
        var matchedIntent = "unknown"
        var confidence = 0f

        if (semanticRouter.isModelLoaded()) {
            val res = semanticRouter.routeIntent(resolvedInput)
            matchedIntent = res.first
            confidence = res.second
        }
        
        if (matchedIntent == "unknown" && nluClassifier.isModelLoaded()) {
            val res = nluClassifier.classifyIntent(resolvedInput)
            matchedIntent = res.first
            confidence = res.second
        }

        // 2. Direct Command Execution — high-priority routes first

        // A0. Screenshot (must run before brightness — "screen" keyword collision)
        if (EntityExtractor.isScreenshotQuery(cleanQuery) || matchedIntent == "take_screenshot") {
            _agentStatusFlow.emit("Taking screenshot...")
            val tool = ToolRegistry.get("screenshot")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply { addProperty("action", "capture") })
                return toolResult(result)
            }
        }

        // A0b. Phone call — "call Mom", "can you call John"
        val callContact = EntityExtractor.extractCallContact(resolvedInput)
        val isCallQuery = callContact != null || matchedIntent == "call_contact"
        if (isCallQuery) {
            val name = callContact ?: cleanQuery
                .replace(Regex("(?i)(call|phone|dial|ring)\\s+"), "")
                .trim()
            if (name.isNotEmpty()) {
                _agentStatusFlow.emit("Calling $name...")
                val tool = ToolRegistry.get("phone_control")
                if (tool != null) {
                    val result = tool.execute(JsonObject().apply {
                        addProperty("action", "call")
                        addProperty("contact_name", name)
                    })
                    return toolResult(result)
                }
            }
        }

        // A0c. Send SMS
        val smsDetails = EntityExtractor.extractSmsDetails(resolvedInput)
        if (smsDetails != null || matchedIntent == "send_sms") {
            if (smsDetails != null) {
                _agentStatusFlow.emit("Sending SMS to ${smsDetails.first}...")
                val tool = ToolRegistry.get("phone_control")
                if (tool != null) {
                    val result = tool.execute(JsonObject().apply {
                        addProperty("action", "send_sms")
                        addProperty("recipient", smsDetails.first)
                        addProperty("message", smsDetails.second)
                    })
                    return toolResult(result)
                }
            }
        }

        // A0d. Read SMS / call log
        if (cleanQuery.contains("read sms") || cleanQuery.contains("read my messages") ||
            cleanQuery.contains("check messages") || matchedIntent == "read_sms") {
            _agentStatusFlow.emit("Reading messages...")
            val tool = ToolRegistry.get("phone_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply { addProperty("action", "read_sms") })
                return toolResult(result)
            }
        }
        if (cleanQuery.contains("call log") || cleanQuery.contains("recent calls") || matchedIntent == "read_call_log") {
            _agentStatusFlow.emit("Reading call log...")
            val tool = ToolRegistry.get("phone_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply { addProperty("action", "read_call_log") })
                return toolResult(result)
            }
        }

        // A0e. Clipboard
        if (cleanQuery.contains("clipboard") || matchedIntent == "clipboard_read" || matchedIntent == "clipboard_write") {
            val tool = ToolRegistry.get("clipboard_control")
            if (tool != null) {
                if (cleanQuery.contains("copy") || cleanQuery.contains("write") || matchedIntent == "clipboard_write") {
                    val textMatch = Regex("(?i)(?:copy|write)\\s+(.+)\\s+(?:to clipboard|to the clipboard)").find(resolvedInput)
                    val text = textMatch?.groupValues?.get(1)?.trim()
                    if (!text.isNullOrEmpty()) {
                        _agentStatusFlow.emit("Copying to clipboard...")
                        val result = tool.execute(JsonObject().apply {
                            addProperty("action", "write")
                            addProperty("text", text)
                        })
                        return toolResult(result)
                    }
                } else {
                    _agentStatusFlow.emit("Reading clipboard...")
                    val result = tool.execute(JsonObject().apply { addProperty("action", "read") })
                    return toolResult(result)
                }
            }
        }

        // A0f. Battery / time
        if (cleanQuery.contains("battery") || matchedIntent == "get_battery") {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val msg = "Battery is at $level percent."
            return fast(msg)
        }
        if (cleanQuery.contains("what time") || cleanQuery.contains("current time") || matchedIntent == "get_time") {
            val fmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
            val msg = "It's ${fmt.format(java.util.Date())}."
            return fast(msg)
        }

        // A0g. Media transport controls (pause / next / previous / resume)
        if (cleanQuery.contains("pause") && (cleanQuery.contains("music") || cleanQuery.contains("media") || cleanQuery.contains("playback")) ||
            matchedIntent == "pause_media" || cleanQuery == "pause" || cleanQuery == "pause it") {
            _agentStatusFlow.emit("Pausing media...")
            val tool = ToolRegistry.get("media_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply { addProperty("action", "pause") })
                DialogueStateTracker.record("media", "off")
                return toolResult(result)
            }
        }
        if (cleanQuery.contains("next track") || cleanQuery.contains("skip") || matchedIntent == "next_track") {
            val tool = ToolRegistry.get("media_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply { addProperty("action", "next") })
                return toolResult(result)
            }
        }
        if (cleanQuery.contains("previous track") || cleanQuery.contains("go back") && cleanQuery.contains("song") ||
            matchedIntent == "previous_track") {
            val tool = ToolRegistry.get("media_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply { addProperty("action", "previous") })
                return toolResult(result)
            }
        }
        if (cleanQuery.contains("resume") && cleanQuery.contains("music") || cleanQuery == "resume" || cleanQuery == "resume it") {
            val tool = ToolRegistry.get("media_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply { addProperty("action", "play") })
                DialogueStateTracker.record("media", "on")
                return toolResult(result)
            }
        }

        // A. Volume Controls
        val isVolumeQuery = cleanQuery.contains("volume") || cleanQuery.contains("sound") || cleanQuery.contains("audio") || cleanQuery.contains("mute") || cleanQuery.contains("unmute") || cleanQuery.contains("louder") || cleanQuery.contains("quieter") || matchedIntent == "volume_up" || matchedIntent == "volume_down"
        if (isVolumeQuery) {
            val actionVal: String
            val actionText: String
            if (cleanQuery.contains("unmute")) {
                actionVal = "50%"
                actionText = "Unmuting volume to 50%..."
            } else if (cleanQuery.contains("mute") || cleanQuery.contains("silent") || cleanQuery.contains("silence")) {
                actionVal = "mute"
                actionText = "Muting volume..."
            } else if (cleanQuery.contains("max") || cleanQuery.contains("full") || cleanQuery.contains("maximum") || cleanQuery.contains("100%")) {
                actionVal = "100%"
                actionText = "Setting volume to maximum..."
            } else if (cleanQuery.contains("low") || cleanQuery.contains("minimum") || cleanQuery.contains("10%")) {
                actionVal = "10%"
                actionText = "Setting volume to low..."
            } else if (cleanQuery.contains("medium") || cleanQuery.contains("half") || cleanQuery.contains("50%")) {
                actionVal = "50%"
                actionText = "Setting volume to 50%..."
            } else {
                val pctMatch = Regex("(\\d+)\\s*(?:%|percent)").find(cleanQuery) ?: Regex("volume\\s+(?:to\\s+)?(\\d+)").find(cleanQuery)
                if (pctMatch != null) {
                    val pct = pctMatch.groupValues[1]
                    actionVal = "$pct%"
                    actionText = "Setting volume to $pct%..."
                } else if (cleanQuery.contains("up") || cleanQuery.contains("increase") || cleanQuery.contains("raise") || cleanQuery.contains("louder") || cleanQuery.contains("higher") || matchedIntent == "volume_up") {
                    actionVal = "up"
                    actionText = "Increasing volume..."
                } else if (cleanQuery.contains("down") || cleanQuery.contains("decrease") || cleanQuery.contains("lower") || cleanQuery.contains("quieter") || cleanQuery.contains("diminish") || matchedIntent == "volume_down") {
                    actionVal = "down"
                    actionText = "Decreasing volume..."
                } else {
                    actionVal = "up"
                    actionText = "Increasing volume..."
                }
            }
            
            _agentStatusFlow.emit(actionText)
            val tool = ToolRegistry.get("system_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply {
                    addProperty("action", "set_volume")
                    addProperty("value", actionVal)
                })
                return if (result.success) fast(result.data, "volume", actionVal) else QueryResult("Failed to adjust volume.", true)
            }
        }

        // B. Brightness Controls — require brightness keywords (NOT bare "screen")
        val isBrightnessQuery = cleanQuery.contains("brightness") || cleanQuery.contains("dim") ||
            cleanQuery.contains("brighter") || cleanQuery.contains("dimmer") ||
            matchedIntent == "brightness_up" || matchedIntent == "brightness_down"
        if (isBrightnessQuery) {
            val actionVal: String
            val actionText: String
            if (cleanQuery.contains("max") || cleanQuery.contains("full") || cleanQuery.contains("maximum") || cleanQuery.contains("100%") || cleanQuery.contains("brightest") || cleanQuery.contains("highest")) {
                actionVal = "100%"
                actionText = "Setting brightness to maximum..."
            } else if (cleanQuery.contains("low") || cleanQuery.contains("minimum") || cleanQuery.contains("lowest") || cleanQuery.contains("darkest") || cleanQuery.contains("0%") || cleanQuery.contains("10%")) {
                actionVal = "10%"
                actionText = "Setting brightness to minimum..."
            } else if (cleanQuery.contains("medium") || cleanQuery.contains("half") || cleanQuery.contains("50%")) {
                actionVal = "50%"
                actionText = "Setting brightness to 50%..."
            } else {
                val pctMatch = Regex("(\\d+)\\s*(?:%|percent)").find(cleanQuery) ?: Regex("brightness\\s+(?:to\\s+)?(\\d+)").find(cleanQuery)
                if (pctMatch != null) {
                    val pct = pctMatch.groupValues[1]
                    actionVal = "$pct%"
                    actionText = "Setting brightness to $pct%..."
                } else if (cleanQuery.contains("up") || cleanQuery.contains("increase") || cleanQuery.contains("raise") || cleanQuery.contains("brighter") || cleanQuery.contains("higher") || matchedIntent == "brightness_up") {
                    actionVal = "up"
                    actionText = "Increasing brightness..."
                } else if (cleanQuery.contains("down") || cleanQuery.contains("decrease") || cleanQuery.contains("lower") || cleanQuery.contains("dimmer") || cleanQuery.contains("less") || matchedIntent == "brightness_down") {
                    actionVal = "down"
                    actionText = "Decreasing brightness..."
                } else {
                    actionVal = "up"
                    actionText = "Increasing brightness..."
                }
            }
            
            _agentStatusFlow.emit(actionText)
            val tool = ToolRegistry.get("system_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply {
                    addProperty("action", "set_brightness")
                    addProperty("value", actionVal)
                })
                return if (result.success) fast(result.data, "brightness", actionVal) else QueryResult("Failed to adjust brightness.", true)
            }
        }

        // C. Flashlight (Torch) Controls (including strength)
        val isTorchQuery = cleanQuery.contains("flashlight") || cleanQuery.contains("torch") || matchedIntent == "torch_toggle" || matchedIntent == "torch_strength"
        if (isTorchQuery) {
            val isOff = cleanQuery.contains("off") || cleanQuery.contains("disable") || cleanQuery.contains("stop") || cleanQuery.contains("deactivate")
            val isOn = cleanQuery.contains("on") || cleanQuery.contains("enable") || cleanQuery.contains("start") || cleanQuery.contains("activate")

            val hasStrengthWord = cleanQuery.contains("strength") || cleanQuery.contains("level") || cleanQuery.contains("intensity") || cleanQuery.contains("brightness") || cleanQuery.contains("max") || cleanQuery.contains("full") || cleanQuery.contains("medium") || cleanQuery.contains("half") || cleanQuery.contains("low") || Regex("\\d+").containsMatchIn(cleanQuery)

            if (isOff && !isOn) {
                _agentStatusFlow.emit("Turning flashlight off...")
                val tool = ToolRegistry.get("system_control")
                if (tool != null) {
                    val result = tool.execute(JsonObject().apply {
                        addProperty("action", "toggle_torch")
                        addProperty("value", "off")
                    })
                    return if (result.success) fast(result.data, "torch", "off") else QueryResult("Failed to toggle flashlight.", true)
                }
            } else if (hasStrengthWord) {
                val pctVal: String
                if (cleanQuery.contains("max") || cleanQuery.contains("full") || cleanQuery.contains("maximum") || cleanQuery.contains("100%") || cleanQuery.contains("high")) {
                    pctVal = "100%"
                } else if (cleanQuery.contains("low") || cleanQuery.contains("minimum") || cleanQuery.contains("lowest") || cleanQuery.contains("darkest") || cleanQuery.contains("20%")) {
                    pctVal = "20%"
                } else if (cleanQuery.contains("medium") || cleanQuery.contains("half") || cleanQuery.contains("50%")) {
                    pctVal = "50%"
                } else {
                    val numMatch = Regex("(\\d+)").find(cleanQuery)
                    pctVal = if (numMatch != null) {
                        val num = numMatch.groupValues[1].toInt()
                        if (num <= 5) (num * 20).toString() + "%" else num.toString() + "%"
                    } else {
                        "50%"
                    }
                }
                _agentStatusFlow.emit("Setting torch strength to $pctVal...")
                val tool = ToolRegistry.get("system_control")
                if (tool != null) {
                    val result = tool.execute(JsonObject().apply {
                        addProperty("action", "set_torch_strength")
                        addProperty("value", pctVal)
                    })
                    return if (result.success) fast(result.data, "torch", pctVal) else QueryResult("Failed to adjust torch strength.", true)
                }
            } else {
                _agentStatusFlow.emit("Turning flashlight on...")
                val tool = ToolRegistry.get("system_control")
                if (tool != null) {
                    val result = tool.execute(JsonObject().apply {
                        addProperty("action", "toggle_torch")
                        addProperty("value", "on")
                    })
                    return if (result.success) fast(result.data, "torch", "on") else QueryResult("Failed to toggle flashlight.", true)
                }
            }
        }

        // D. WiFi Controls
        val isWifiQuery = cleanQuery.contains("wifi") || cleanQuery.contains("wi-fi") || matchedIntent == "wifi_toggle"
        if (isWifiQuery) {
            val isOff = cleanQuery.contains("off") || cleanQuery.contains("disable") || cleanQuery.contains("stop") || cleanQuery.contains("deactivate") || cleanQuery.contains("turnoff")
            val isOn = cleanQuery.contains("on") || cleanQuery.contains("enable") || cleanQuery.contains("start") || cleanQuery.contains("activate") || cleanQuery.contains("turnon")
            val state = if (isOff && !isOn) "off" else "on"
            
            _agentStatusFlow.emit(if (state == "on") "Turning WiFi on..." else "Turning WiFi off...")
            val tool = ToolRegistry.get("system_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply {
                    addProperty("action", "toggle_wifi")
                    addProperty("value", state)
                })
                return if (result.success) fast(result.data, "wifi", state) else QueryResult("Failed to toggle WiFi.", true)
            }
        }

        // E. Bluetooth Controls
        val isBluetoothQuery = cleanQuery.contains("bluetooth") || cleanQuery.contains("blue tooth") || matchedIntent == "bluetooth_toggle"
        if (isBluetoothQuery) {
            val isOff = cleanQuery.contains("off") || cleanQuery.contains("disable") || cleanQuery.contains("stop") || cleanQuery.contains("deactivate") || cleanQuery.contains("turnoff")
            val isOn = cleanQuery.contains("on") || cleanQuery.contains("enable") || cleanQuery.contains("start") || cleanQuery.contains("activate") || cleanQuery.contains("turnon")
            val state = if (isOff && !isOn) "off" else "on"
            
            _agentStatusFlow.emit(if (state == "on") "Turning Bluetooth on..." else "Turning Bluetooth off...")
            val tool = ToolRegistry.get("system_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply {
                    addProperty("action", "toggle_bluetooth")
                    addProperty("value", state)
                })
                return if (result.success) fast(result.data, "bluetooth", state) else QueryResult("Failed to toggle Bluetooth.", true)
            }
        }

        // F. Hotspot Controls
        val isHotspotQuery = cleanQuery.contains("hotspot") || cleanQuery.contains("hot spot") || cleanQuery.contains("tethering") || cleanQuery.contains("tether") || matchedIntent == "hotspot_toggle"
        if (isHotspotQuery) {
            _agentStatusFlow.emit("Toggling Hotspot...")
            val tool = ToolRegistry.get("system_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply {
                    addProperty("action", "toggle_hotspot")
                })
                return if (result.success) fast(result.data, "hotspot", null) else QueryResult("Failed to toggle Hotspot.", true)
            }
        }

        // G. Do Not Disturb (DND)
        val isDndQuery = cleanQuery.contains("do not disturb") || cleanQuery.contains("dnd")
        if (isDndQuery) {
            val isOff = cleanQuery.contains("off") || cleanQuery.contains("disable") || cleanQuery.contains("stop") || cleanQuery.contains("deactivate")
            val isOn = cleanQuery.contains("on") || cleanQuery.contains("enable") || cleanQuery.contains("start") || cleanQuery.contains("activate")
            val state = if (isOff && !isOn) "off" else "on"
            
            _agentStatusFlow.emit(if (state == "on") "Activating DND..." else "Deactivating DND...")
            val tool = ToolRegistry.get("system_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply {
                    addProperty("action", "toggle_dnd")
                    addProperty("value", state)
                })
                return if (result.success) fast(result.data, "dnd", state) else QueryResult("Failed to toggle DND.", true)
            }
        }

        // G. WhatsApp Direct Messaging Route
        if (cleanQuery.contains("whatsapp") && (cleanQuery.contains("message") || cleanQuery.contains("send") || cleanQuery.contains("text"))) {
            val patterns = listOf(
                "(?i)(?:message|text|send message to)\\s+(.+?)\\s+on\\s+whatsapp\\s+(?:saying|text|message)?\\s*(.+)".toRegex(),
                "(?i)(?:message|text|send message to)\\s+(.+?)\\s+(?:saying|text|message)?\\s*(.+)\\s+on\\s+whatsapp".toRegex(),
                "(?i)whatsapp\\s+(.+?)\\s+(?:saying|text|message)?\\s*(.+)".toRegex()
            )
            for (pattern in patterns) {
                val match = pattern.find(userInput)
                if (match != null) {
                    val recipient = match.groupValues[1].trim()
                    val msgText = match.groupValues[2].removePrefix("\"").removeSuffix("\"").trim()
                    if (recipient.isNotEmpty() && msgText.isNotEmpty()) {
                        _agentStatusFlow.emit("Messaging $recipient on WhatsApp...")
                        val whatsappTool = ToolRegistry.get("whatsapp_send")
                        if (whatsappTool != null) {
                            val result = whatsappTool.execute(JsonObject().apply {
                                addProperty("recipient", recipient)
                                addProperty("message", msgText)
                            })
                            return fast(result.data)
                        }
                    }
                }
            }
        }

        // H. Gmail Direct Mail Route
        if (cleanQuery.contains("email") || cleanQuery.contains("mail") || cleanQuery.contains("gmail")) {
            val patterns = listOf(
                "(?i)(?:send email|send mail|email|mail)\\s+to\\s+(.+?)\\s+subject\\s+(.+?)\\s+body\\s+(.+)".toRegex(),
                "(?i)(?:send email|send mail|email|mail)\\s+to\\s+(.+?)\\s+(?:saying|message)?\\s*(.+)".toRegex()
            )
            for (pattern in patterns) {
                val match = pattern.find(userInput)
                if (match != null) {
                    val to = match.groupValues[1].trim()
                    if (match.groupValues.size == 4) {
                        val subject = match.groupValues[2].trim()
                        val body = match.groupValues[3].trim()
                        _agentStatusFlow.emit("Composing email to $to...")
                        val emailTool = ToolRegistry.get("gmail_send")
                        if (emailTool != null) {
                            val result = emailTool.execute(JsonObject().apply {
                                addProperty("to", to)
                                addProperty("subject", subject)
                                addProperty("body", body)
                            })
                            return fast(result.data)
                        }
                    } else if (match.groupValues.size == 3) {
                        val body = match.groupValues[2].trim()
                        _agentStatusFlow.emit("Composing email to $to...")
                        val emailTool = ToolRegistry.get("gmail_send")
                        if (emailTool != null) {
                            val result = emailTool.execute(JsonObject().apply {
                                addProperty("to", to)
                                addProperty("subject", "Message from Friday")
                                addProperty("body", body)
                            })
                            return fast(result.data)
                        }
                    }
                }
            }
        }

        // I. Checking Notifications
        val isListNotifications = Regex("(?:check|list|show|any|get)\\s+(?:my\\s+)?(?:messages|notifications|mail)").containsMatchIn(cleanQuery) ||
                cleanQuery.contains("notification") || cleanQuery.contains("notifications")
        if (isListNotifications) {
            _agentStatusFlow.emit("Checking notifications...")
            val tool = ToolRegistry.get("notification_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply {
                    addProperty("action", "list")
                })
                return toolResult(result)
            }
        }

        // J. Replying to Notifications
        if (cleanQuery.contains("reply") && cleanQuery.contains("notification")) {
            val replyPattern = "(?i)reply\\s+to\\s+(.+?)\\s+(?:saying|with)?\\s*(.+)".toRegex()
            val match = replyPattern.find(resolvedInput)
            if (match != null) {
                val key = match.groupValues[1].trim()
                val text = match.groupValues[2].trim()
                _agentStatusFlow.emit("Replying to notification...")
                val tool = ToolRegistry.get("notification_control")
                if (tool != null) {
                    val result = tool.execute(JsonObject().apply {
                        addProperty("action", "reply")
                        addProperty("notification_key", key)
                        addProperty("reply_text", text)
                    })
                    return toolResult(result)
                }
            }
        }

        // K. Lock screen
        val isLockQuery = matchedIntent == "lock_phone" ||
                cleanQuery.contains("lock screen") ||
                cleanQuery.contains("lock phone") ||
                (cleanQuery.contains("lock") && (cleanQuery.contains("phone") || cleanQuery.contains("screen") || cleanQuery.contains("device"))) ||
                cleanQuery.contains("turn off screen") ||
                cleanQuery.contains("turn off the screen")
        if (isLockQuery) {
            _agentStatusFlow.emit("Locking screen...")
            val tool = ToolRegistry.get("system_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply {
                    addProperty("action", "lock_phone")
                })
                return if (result.success) fast(result.data) else QueryResult("Failed to lock the screen: ${result.data}", true)
            }
        }

        // L. Reddit search
        val isSearchReddit = matchedIntent == "search_reddit" && confidence > 0.7f ||
                cleanQuery.contains("on reddit") ||
                cleanQuery.contains("reddit search")
        if (isSearchReddit) {
            var searchPhrase = userInput
            val redditRegex = "(?i)(?:search|look up)?(.+?)(?: on reddit| reddit)?".toRegex()
            val match = redditRegex.matchEntire(cleanQuery)
            if (match != null) {
                searchPhrase = match.groupValues[1].replace("search", "").replace("for", "").trim()
            }
            if (searchPhrase.isEmpty()) searchPhrase = userInput

            _agentStatusFlow.emit("Searching on Reddit...")
            return try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://www.reddit.com/search/?q=" + java.net.URLEncoder.encode(searchPhrase, "UTF-8"))
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                fast("Searching for '$searchPhrase' on Reddit.")
            } catch (e: Exception) {
                QueryResult("Failed to open Reddit search.", true)
            }
        }

        // T. Play Media / Search Music (before open_app to avoid "start playing" collision)
        val isPlayMedia = matchedIntent == "play_media" || matchedIntent == "play_spotify" ||
            matchedIntent == "play_youtube" ||
            Regex("play\\s+(.+)").containsMatchIn(cleanQuery) ||
            Regex("listen\\s+to\\s+(.+)").containsMatchIn(cleanQuery)
        if (isPlayMedia && !EntityExtractor.isScreenshotQuery(cleanQuery)) {
            val (mediaQuery, targetApp) = EntityExtractor.extractMediaQuery(resolvedInput)
            if (mediaQuery.isNotEmpty()) {
                val app = targetApp ?: when (matchedIntent) {
                    "play_spotify" -> "spotify"
                    "play_youtube" -> "youtube"
                    else -> null
                }
                _agentStatusFlow.emit("Playing $mediaQuery${if (app != null) " on $app" else ""}...")
                val mediaTool = ToolRegistry.get("media_control")
                if (mediaTool != null) {
                    val result = mediaTool.execute(JsonObject().apply {
                        addProperty("action", "play_search")
                        addProperty("query", mediaQuery)
                        if (app != null) addProperty("app", app)
                    })
                    DialogueStateTracker.record("media", "on")
                    return toolResult(result)
                }
            }
        }

        // M. Open App
        val isLaunchApp = (matchedIntent == "open_app" && confidence > 0.7f ||
                cleanQuery.contains("open ") || cleanQuery.contains("launch ") ||
                cleanQuery.contains("go to ") || cleanQuery.contains("open up ") || cleanQuery.contains("show ")) &&
            !cleanQuery.contains("play ") && !cleanQuery.contains("listen to") &&
            matchedIntent != "play_media" && matchedIntent != "play_spotify" && matchedIntent != "play_youtube"
        if (isLaunchApp) {
            var appName = EntityExtractor.extractLaunchAppName(resolvedInput)
            if (appName.isEmpty() && matchedIntent == "open_app" && confidence > 0.7f) {
                appName = cleanQuery.replace("please", "").replace("can you", "").replace("could you", "").trim()
            }
            if (appName.startsWith("the ")) appName = appName.substring(4).trim()
            if (appName.isNotEmpty()) {
                _agentStatusFlow.emit("Opening $appName...")
                val tool = ToolRegistry.get("app_launcher")
                if (tool != null) {
                    val result = tool.execute(JsonObject().apply {
                        addProperty("app_name", appName)
                    })
                    if (result.success) return fast(result.data)
                }
            }
        }

        // O. Screen Mirroring / Screencast Toggle
        val isScreencast = matchedIntent == "screencast_toggle" && confidence > 0.7f ||
                cleanQuery.contains("screen cast") || cleanQuery.contains("screencast") || cleanQuery.contains("smart view") || cleanQuery.contains("mirror screen") || cleanQuery.contains("screen mirroring")
        if (isScreencast) {
            _agentStatusFlow.emit("Opening screen mirroring...")
            val tool = ToolRegistry.get("system_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply {
                    addProperty("action", "toggle_screencast")
                })
                return if (result.success) fast(result.data, "screencast", null) else QueryResult("Failed to open screen cast settings.", true)
            }
        }

        // P. Battery Saver Toggle
        val isPowerSaver = matchedIntent == "power_saver_toggle" && confidence > 0.7f ||
                cleanQuery.contains("power saver") || cleanQuery.contains("battery saver") || cleanQuery.contains("low power mode")
        if (isPowerSaver) {
            _agentStatusFlow.emit("Opening battery saver...")
            val tool = ToolRegistry.get("system_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply {
                    addProperty("action", "toggle_power_saver")
                })
                return if (result.success) fast(result.data, "power_saver", null) else QueryResult("Failed to open battery saver settings.", true)
            }
        }

        // Q. Turn-by-Turn Navigation (Maps)
        val isNavigateTo = matchedIntent == "navigate_to" && confidence > 0.7f ||
                Regex("navigate\\s+to\\s+(.+)").containsMatchIn(cleanQuery) ||
                Regex("directions\\s+to\\s+(.+)").containsMatchIn(cleanQuery)
        if (isNavigateTo) {
            val patterns = listOf(
                "(?i)navigate\\s+to\\s+(.+)".toRegex(),
                "(?i)directions\\s+to\\s+(.+)".toRegex(),
                "(?i)go\\s+to\\s+(.+)".toRegex(),
                "(?i)routes\\s+to\\s+(.+)".toRegex()
            )
            var destination = ""
            for (p in patterns) {
                val match = p.find(userInput)
                if (match != null) {
                    destination = match.groupValues[1].trim()
                    break
                }
            }
            if (destination.isEmpty()) {
                destination = cleanQuery.replace("navigate", "").replace("to", "").replace("directions", "").replace("show", "").trim()
            }
            
            if (destination.isNotEmpty()) {
                _agentStatusFlow.emit("Navigating to $destination...")
                val locationTool = ToolRegistry.get("location_control")
                if (locationTool != null) {
                    val result = locationTool.execute(JsonObject().apply {
                        addProperty("action", "navigate")
                        addProperty("destination", destination)
                    })
                    return toolResult(result)
                }
            }
        }

        // R. Create System Alarm
        val isSetAlarm = matchedIntent == "set_alarm" && confidence > 0.7f ||
                Regex("alarm\\s+for").containsMatchIn(cleanQuery) ||
                Regex("wake\\s+me\\s+up\\s+at").containsMatchIn(cleanQuery)
        if (isSetAlarm) {
            var hour = 7
            var minute = 0
            var label = "Friday Alarm"
            
            val labelMatch = Regex("called\\s+([a-zA-Z0-9 ]+)").find(cleanQuery)
            if (labelMatch != null) {
                label = labelMatch.groupValues[1].trim()
            }
            
            val timeMatch = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?").find(cleanQuery)
            if (timeMatch != null) {
                var h = timeMatch.groupValues[1].toInt()
                val m = if (timeMatch.groupValues[2].isNotEmpty()) timeMatch.groupValues[2].toInt() else 0
                val ampm = timeMatch.groupValues[3].lowercase()
                
                if (ampm == "pm" && h < 12) {
                    h += 12
                } else if (ampm == "am" && h == 12) {
                    h = 0
                }
                hour = h.coerceIn(0, 23)
                minute = m.coerceIn(0, 59)
            } else if (cleanQuery.contains("noon")) {
                hour = 12
                minute = 0
            } else if (cleanQuery.contains("midnight")) {
                hour = 0
                minute = 0
            }
            
            _agentStatusFlow.emit("Setting alarm...")
            val calendarTool = ToolRegistry.get("calendar_control")
            if (calendarTool != null) {
                val result = calendarTool.execute(JsonObject().apply {
                    addProperty("action", "set_alarm")
                    addProperty("hour", hour)
                    addProperty("minute", minute)
                    addProperty("title", label)
                })
                return toolResult(result)
            }
        }

        // S. Create Countdown Timer
        val isSetTimer = matchedIntent == "set_timer" && confidence > 0.7f ||
                Regex("timer\\s+for").containsMatchIn(cleanQuery) ||
                Regex("countdown\\s+for").containsMatchIn(cleanQuery)
        if (isSetTimer) {
            var durationSeconds = 300 // default 5 minutes
            var label = "Friday Timer"
            
            val labelMatch = Regex("(?:named|called|label)\\s+([a-zA-Z0-9 ]+)").find(cleanQuery)
            if (labelMatch != null) {
                label = labelMatch.groupValues[1].trim()
            }
            
            val durationMatch = Regex("(\\d+)\\s*(hour|hr|minute|min|second|sec)s?").find(cleanQuery)
            if (durationMatch != null) {
                val value = durationMatch.groupValues[1].toInt()
                val unit = durationMatch.groupValues[2].lowercase()
                durationSeconds = when {
                    unit.startsWith("hour") || unit.startsWith("hr") -> value * 3600
                    unit.startsWith("minute") || unit.startsWith("min") -> value * 60
                    else -> value
                }
            }
            
            _agentStatusFlow.emit("Setting timer...")
            val calendarTool = ToolRegistry.get("calendar_control")
            if (calendarTool != null) {
                val result = calendarTool.execute(JsonObject().apply {
                    addProperty("action", "set_timer")
                    addProperty("duration", durationSeconds)
                    addProperty("title", label)
                })
                return toolResult(result)
            }
        }

        // U. WhatsApp Send Message (NLU Route Integration)
        if (matchedIntent == "send_whatsapp" && confidence > 0.7f) {
            val patterns = listOf(
                "(?i)(?:message|text|send message to)\\s+(.+?)\\s+on\\s+whatsapp\\s+(?:saying|text|message)?\\s*(.+)".toRegex(),
                "(?i)(?:message|text|send message to)\\s+(.+?)\\s+(?:saying|text|message)?\\s*(.+)\\s+on\\s+whatsapp".toRegex(),
                "(?i)whatsapp\\s+(.+?)\\s+(?:saying|text|message)?\\s*(.+)".toRegex()
            )
            var recipient = ""
            var msgText = ""
            for (pattern in patterns) {
                val match = pattern.find(userInput)
                if (match != null) {
                    recipient = match.groupValues[1].trim()
                    msgText = match.groupValues[2].removePrefix("\"").removeSuffix("\"").trim()
                    break
                }
            }
            if (recipient.isNotEmpty() && msgText.isNotEmpty()) {
                _agentStatusFlow.emit("Messaging $recipient on WhatsApp...")
                val whatsappTool = ToolRegistry.get("whatsapp_send")
                if (whatsappTool != null) {
                    val result = whatsappTool.execute(JsonObject().apply {
                        addProperty("recipient", recipient)
                        addProperty("message", msgText)
                    })
                    return toolResult(result)
                }
            }
        }

        // 3. Fallback: LLM Chat Brain (Free of Tool Calling Loop)
        val sharedPrefs = context.getSharedPreferences("friday_model_prefs", Context.MODE_PRIVATE)
        val useLlm = sharedPrefs.getBoolean("use_llm", true)

        if (useLlm && modelManager.isLlmLoaded()) {
            if (!llamaEngine.isModelLoaded()) {
                _agentStatusFlow.emit("Loading brain...")
                val path = modelManager.getLlmModelPath()
                com.friday.assistant.core.FridayLogger.i(TAG, "Lazy loading LLM GGUF model from: $path")
                val success = llamaEngine.loadModel(path)
                com.friday.assistant.core.FridayLogger.i(TAG, "LLM GGUF model load success: $success")
                if (!success) {
                    return QueryResult("Failed to load the local brain. Please check if your device has enough free memory.", false)
                }
            }
            _agentStatusFlow.emit("Thinking...")
            val currentPrompt = promptBuilder.buildMinimalPrompt(resolvedInput)
            val response = llamaEngine.generateStream(currentPrompt, maxTokens = 128, temp = 0.7f, callback = object : LlamaEngine.TokenCallback {
                override fun onToken(token: String) {
                    onToken(token)
                }
            }).trim()
            val finalResponse = sanitizeResponse(response)

            memoryManager.saveConversationTurn(resolvedInput, finalResponse)
            return QueryResult(finalResponse, false)
        } else {
            if (cleanQuery.startsWith("search ") || cleanQuery.startsWith("google ") || cleanQuery.contains("what is") || cleanQuery.contains("how to") || cleanQuery.contains("who is")) {
                _agentStatusFlow.emit("Searching the web...")
                val searchTool = ToolRegistry.get("web_search")
                if (searchTool != null) {
                    val result = searchTool.execute(JsonObject().apply {
                        addProperty("query", resolvedInput)
                    })
                    if (result.success) return fast(result.data)
                }
            }

            return QueryResult("I'm running in offline assistant mode, but the local brain (Qwen GGUF) is not loaded or has been offloaded. You can download or enable it in the Friday app dashboard.", false)
        }
    }

    private fun fast(msg: String, domain: String? = null, action: String? = null): QueryResult {
        if (domain != null) DialogueStateTracker.record(domain, action)
        return QueryResult(msg, true)
    }

    private fun toolResult(result: com.friday.assistant.tools.ToolResult, domain: String? = null, action: String? = null): QueryResult {
        if (result.success && domain != null) DialogueStateTracker.record(domain, action)
        return QueryResult(result.data, true)
    }

    private fun parseToolCall(response: String): Pair<String, JsonObject>? {
        try {
            val startIndex = response.indexOf('{')
            val endIndex = response.lastIndexOf('}')
            if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
                val jsonStr = response.substring(startIndex, endIndex + 1)
                val element = com.google.gson.JsonParser.parseString(jsonStr)
                if (element.isJsonObject) {
                    val obj = element.asJsonObject
                    if (obj.has("tool")) {
                        val toolName = obj.get("tool").asString
                        val arguments = if (obj.has("arguments") && obj.get("arguments").isJsonObject) {
                            obj.getAsJsonObject("arguments")
                        } else {
                            JsonObject()
                        }
                        return Pair(toolName, arguments)
                    }
                }
            }
        } catch (e: Exception) {
            com.friday.assistant.core.FridayLogger.e(TAG, "Failed to parse tool call JSON from: $response", e)
        }
        
        // RegEx fallback for less structured tool output
        val toolRegex = "\"tool\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        val match = toolRegex.find(response)
        if (match != null) {
            val toolName = match.groupValues[1]
            val args = JsonObject()
            val argRegex = "\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"".toRegex()
            argRegex.findAll(response).forEach { argMatch ->
                val key = argMatch.groupValues[1]
                val value = argMatch.groupValues[2]
                if (key != "tool" && key != "arguments") {
                    args.addProperty(key, value)
                }
            }
            return Pair(toolName, args)
        }
        
        return null
    }

    private fun sanitizeResponse(raw: String): String {
        var clean = raw
            .replace("<|im_end|>", "")
            .replace("<|im_start|>", "")
            .trim()

        if (clean.startsWith("```") && clean.endsWith("```")) {
            clean = clean.substringAfter("\n").substringBeforeLast("```").trim()
        }
        
        return removeEmojis(clean)
    }

    private fun removeEmojis(text: String): String {
        val emojiRegex = "[\\p{So}\\p{Cn}]".toRegex()
        return text.replace(emojiRegex, "").trim()
    }
}
