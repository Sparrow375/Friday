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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

class AgentCore(
    private val context: Context,
    private val memoryManager: MemoryManager
) {
    companion object {
        private const val TAG = "AgentCore"
        private const val MAX_TOOL_LOOPS = 4

        // Pre-compiled regex patterns — avoids per-call Regex construction overhead
        private val CALL_STRIP_REGEX = Regex("(?i)(call|phone|dial|ring)\\s+")
        private val CLIPBOARD_WRITE_REGEX = Regex("(?i)(?:copy|write)\\s+(.+)\\s+(?:to clipboard|to the clipboard)")
        private val PCT_REGEX = Regex("(\\d+)\\s*(?:%|percent)")
        private val VOL_NUM_REGEX = Regex("volume\\s+(?:to\\s+)?(\\d+)")
        private val BRIGHT_NUM_REGEX = Regex("brightness\\s+(?:to\\s+)?(\\d+)")
        private val TORCH_DIGIT_REGEX = Regex("\\d+")
        private val TORCH_NUM_REGEX = Regex("(\\d+)")
        private val NOTIFY_LIST_REGEX = Regex("(?:check|list|show|any|get)\\s+(?:my\\s+)?(?:messages|notifications|mail)")
        private val NOTIFY_REPLY_REGEX = Regex("(?i)reply\\s+to\\s+(.+?)\\s+(?:saying|with)?\\s*(.+)")
        private val REDDIT_REGEX = Regex("(?i)(?:search|look up)?(.+?)(?: on reddit| reddit)")
        private val GOOGLE_STRIP_REGEX = Regex("(?i)^(google|search google for|search for|search)\\s+")
        private val MEMORY_STORE_REGEX = Regex("(?i)(?:remember that|store that|save that|note that)\\s*(.+)")
        private val MUSIC_PLAY_REGEX = Regex("play\\s+(.+)")
        private val MUSIC_LISTEN_REGEX = Regex("listen\\s+to\\s+(.+)")
        private val NAV_NAVIGATE_REGEX = Regex("navigate\\s+to\\s+(.+)")
        private val NAV_DIRECTIONS_REGEX = Regex("directions\\s+to\\s+(.+)")
        private val ALARM_FOR_REGEX = Regex("alarm\\s+for")
        private val ALARM_WAKE_REGEX = Regex("wake\\s+me\\s+up\\s+at")
        private val ALARM_LABEL_REGEX = Regex("called\\s+([a-zA-Z0-9 ]+)")
        private val ALARM_TIME_REGEX = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?")
        private val TIMER_FOR_REGEX = Regex("timer\\s+for")
        private val TIMER_COUNTDOWN_REGEX = Regex("countdown\\s+for")
        private val TIMER_LABEL_REGEX = Regex("(?:named|called|label)\\s+([a-zA-Z0-9 ]+)")
        private val TIMER_DURATION_REGEX = Regex("(\\d+)\\s*(hour|hr|minute|min|second|sec)")
        private val NOTES_FIND_REGEX = Regex("(?i)(?:find|search|look up)\\s+(?:notes?\\s+)?(?:about|on|for)?\\s*(.+)")
        private val NOTES_ID_REGEX = Regex("(\\d+)")
        private val NOTES_STRIP_REGEX = Regex("(?i)(note|jot|write|save|store|remember)\\s+(that\\s+)?")
        private val NOTES_NOTE_REGEX = Regex("(?i)(?:note that|jot down|write down|save note)\\s*(.+)")
        private val NOTES_REMIND_REGEX = Regex("(?i)(?:remind me)\\s+(?:to\\s+)?(.+)")
        private val NOTES_PLAIN_REGEX = Regex("(?i)note:\\s*(.+)")
        private val TOOL_CALL_REGEX = Regex("\"tool\"\\s*:\\s*\"([^\"]+)\"")
        private val TOOL_ARG_REGEX = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"")
        private val EMOJI_REGEX = Regex("[\\p{So}\\p{Cn}]")
        private val WHATSAPP_PATTERNS = listOf(
            Regex("(?i)(?:message|text|send message to)\\s+(.+?)\\s+on\\s+whatsapp\\s+(?:saying|text|message)?\\s*(.+)"),
            Regex("(?i)(?:message|text|send message to)\\s+(.+?)\\s+(?:saying|text|message)?\\s*(.+)\\s+on\\s+whatsapp"),
            Regex("(?i)whatsapp\\s+(.+?)\\s+(?:saying|text|message)?\\s*(.+)")
        )
        private val EMAIL_PATTERNS = listOf(
            Regex("(?i)(?:send email|send mail|email|mail)\\s+to\\s+(.+?)\\s+subject\\s+(.+?)\\s+body\\s+(.+)"),
            Regex("(?i)(?:send email|send mail|email|mail)\\s+to\\s+(.+?)\\s+(?:saying|message)?\\s*(.+)")
        )
        private val NAV_PATTERNS = listOf(
            Regex("(?i)navigate\\s+to\\s+(.+)"),
            Regex("(?i)directions\\s+to\\s+(.+)"),
            Regex("(?i)go\\s+to\\s+(.+)"),
            Regex("(?i)routes\\s+to\\s+(.+)")
        )
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

        // 1. Run Input Preprocessor
        val preprocessed = InputPreprocessor.preprocess(resolvedInput)
        val cleanQuery = preprocessed.cleanedText.trim().lowercase()

        // 2. Core Intent Classification (Semantic Router, NLU Classifier, or Rule-Based Fallback)
        val (nluIntent, nluConfidence) = withContext(Dispatchers.Default) {
            var intent = "unknown"
            var conf = 0f
            if (semanticRouter.isModelLoaded()) {
                val res = semanticRouter.routeIntent(preprocessed.cleanedText)
                intent = res.first
                conf = res.second
            }
            if (intent == "unknown" && nluClassifier.isModelLoaded()) {
                val res = nluClassifier.classifyIntent(preprocessed.cleanedText)
                intent = res.first
                conf = res.second
            }
            Pair(intent, conf)
        }

        // 3. Post-Classification Validation
        val validation = PostClassificationValidator.validate(context, nluIntent, nluConfidence, preprocessed)
        val matchedIntent = validation.intent
        val confidence = validation.confidence
        val routeToLlm = validation.routeToLlm

        com.friday.assistant.core.FridayLogger.i(
            TAG,
            "NLU result after validation: matchedIntent=$matchedIntent, confidence=$confidence, routeToLlm=$routeToLlm"
        )

        // 4. Direct Command Execution (bypassed if routeToLlm is true)
        if (!routeToLlm) {
            // Daily Briefing voice triggers
            val isReadBriefing = cleanQuery.contains("read briefing") ||
                cleanQuery.contains("read my briefing") ||
                cleanQuery.contains("read daily brief") ||
                cleanQuery.contains("read my daily brief") ||
                cleanQuery.contains("what is in the news") ||
                cleanQuery.contains("what's in the news") ||
                cleanQuery.contains("give me a news update") ||
                cleanQuery.contains("give me a cricket update") ||
                cleanQuery.contains("cricket update") ||
                matchedIntent == "read_news_briefing"
            
            if (isReadBriefing) {
                _agentStatusFlow.emit("Reading daily briefing...")
                val db = com.friday.assistant.core.FridayApplication.database
                val dao = db.dao()
                val items = withContext(Dispatchers.IO) { dao.getNewBriefItems(3) }
                if (items.isEmpty()) {
                    return fast("You have no new briefing items. You can trigger a sync or add topics in the app.")
                }
                val sb = StringBuilder("Here is your daily briefing:\n\n")
                for (item in items) {
                    sb.append("From ${item.sourceName}: ${item.summary}\nSource link: ${item.url}\n\n")
                }
                return fast(sb.toString().trim())
            }

            val isShowBriefing = cleanQuery.contains("show briefing") ||
                cleanQuery.contains("open briefing") ||
                cleanQuery.contains("show my briefing") ||
                cleanQuery.contains("open news") ||
                cleanQuery.contains("show my feed") ||
                cleanQuery.contains("open news feed") ||
                cleanQuery.contains("show daily brief") ||
                matchedIntent == "show_news_briefing"

            if (isShowBriefing) {
                _agentStatusFlow.emit("Opening daily briefing...")
                return try {
                    val intent = Intent(context, com.friday.assistant.ui.screens.MainActivity::class.java).apply {
                        putExtra("navigate_to", "briefing")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    context.startActivity(intent)
                    fast("Opening daily briefing.")
                } catch (e: Exception) {
                    QueryResult("Failed to open daily briefing screen.", true)
                }
            }

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
            val callContact = preprocessed.extractedEntities["[CONTACT]"] 
                ?: EntityExtractor.extractCallContact(preprocessed.originalText)
            val isCallQuery = callContact != null || matchedIntent == "call_contact"
            if (isCallQuery) {
                val name = callContact ?: preprocessed.originalText
                    .replace(CALL_STRIP_REGEX, "")
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
            val smsDetails = EntityExtractor.extractSmsDetails(preprocessed.originalText) ?: run {
                val contact = preprocessed.extractedEntities["[CONTACT]"] ?: preprocessed.extractedEntities["[PHONE]"]
                val msg = preprocessed.extractedEntities["[QUOTE]"]
                if (contact != null && msg != null) Pair(contact, msg) else null
            }
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
                        val textMatch = CLIPBOARD_WRITE_REGEX.find(preprocessed.originalText)
                        val text = textMatch?.groupValues?.get(1)?.trim() ?: preprocessed.extractedEntities["[QUOTE]"]
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
                    val pctMatch = PCT_REGEX.find(cleanQuery) ?: VOL_NUM_REGEX.find(cleanQuery)
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
                    val pctMatch = PCT_REGEX.find(cleanQuery) ?: BRIGHT_NUM_REGEX.find(cleanQuery)
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

                val hasStrengthWord = cleanQuery.contains("strength") || cleanQuery.contains("level") || cleanQuery.contains("intensity") || cleanQuery.contains("brightness") || cleanQuery.contains("max") || cleanQuery.contains("full") || cleanQuery.contains("medium") || cleanQuery.contains("half") || cleanQuery.contains("low") || TORCH_DIGIT_REGEX.containsMatchIn(cleanQuery)

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
                        val numMatch = TORCH_NUM_REGEX.find(cleanQuery)
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
            val isDndQuery = cleanQuery.contains("do not disturb") || cleanQuery.contains("dnd") || matchedIntent == "dnd_toggle"
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
                for (pattern in WHATSAPP_PATTERNS) {
                    val match = pattern.find(preprocessed.originalText)
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
                for (pattern in EMAIL_PATTERNS) {
                    val match = pattern.find(preprocessed.originalText)
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
            val isListNotifications = NOTIFY_LIST_REGEX.containsMatchIn(cleanQuery) ||
                    cleanQuery.contains("notification") || cleanQuery.contains("notifications") ||
                    matchedIntent == "read_notifications"
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
                val match = NOTIFY_REPLY_REGEX.find(preprocessed.originalText)
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
                var searchPhrase = preprocessed.originalText
                val match = REDDIT_REGEX.matchEntire(cleanQuery)
                if (match != null) {
                    searchPhrase = match.groupValues[1].replace("search", "").replace("for", "").trim()
                }
                if (searchPhrase.isEmpty()) searchPhrase = preprocessed.originalText

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

            // L2. Google search
            val isSearchGoogle = matchedIntent == "search_google" && confidence > 0.7f ||
                    cleanQuery.startsWith("google ") ||
                    cleanQuery.contains("search google") ||
                    cleanQuery.contains("search the web for") ||
                    (cleanQuery.contains("look this up") && !cleanQuery.contains("reddit"))
            if (isSearchGoogle) {
                var searchPhrase = cleanQuery
                    .replace(GOOGLE_STRIP_REGEX, "")
                    .trim()
                if (searchPhrase.isEmpty()) searchPhrase = preprocessed.originalText
                _agentStatusFlow.emit("Searching Google...")
                val searchTool = ToolRegistry.get("web_search")
                if (searchTool != null) {
                    val result = searchTool.execute(JsonObject().apply { addProperty("query", searchPhrase) })
                    if (result.success) return fast(result.data)
                }
                return try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://www.google.com/search?q=" + java.net.URLEncoder.encode(searchPhrase, "UTF-8"))
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    fast("Searching Google for '$searchPhrase'.")
                } catch (e: Exception) {
                    QueryResult("Failed to open Google search.", true)
                }
            }

            // L3. Remember / Recall preferences
            val isRemember = matchedIntent == "remember_preference" && confidence > 0.7f ||
                    cleanQuery.startsWith("remember that") || cleanQuery.startsWith("store the fact") ||
                    cleanQuery.startsWith("keep in mind") || cleanQuery.contains("note that i prefer")
            if (isRemember) {
                val rememberTool = ToolRegistry.get("remember")
                if (rememberTool != null) {
                    val factMatch = Regex("(?i)(?:remember that|store the fact that|keep in mind that|note that)\\s+(.+)").find(preprocessed.originalText)
                    val fact = factMatch?.groupValues?.get(1)?.trim() ?: preprocessed.originalText
                    _agentStatusFlow.emit("Saving preference...")
                    val result = rememberTool.execute(JsonObject().apply { addProperty("text", fact) })
                    return toolResult(result)
                }
            }
            val isRecall = matchedIntent == "recall_preference" && confidence > 0.7f ||
                    cleanQuery.contains("what do you remember about me") ||
                    cleanQuery.contains("what have i told you") ||
                    cleanQuery.contains("my saved preferences") ||
                    cleanQuery.contains("recall what you know")
            if (isRecall) {
                val recallTool = ToolRegistry.get("recall")
                if (recallTool != null) {
                    _agentStatusFlow.emit("Recalling preferences...")
                    val result = recallTool.execute(JsonObject())
                    return toolResult(result)
                }
            }

            // L4. Open Files
            val isOpenFiles = matchedIntent == "open_files" && confidence > 0.7f ||
                    cleanQuery.contains("open my files") || cleanQuery.contains("open file manager") ||
                    cleanQuery.contains("show downloads") || cleanQuery.contains("browse documents") ||
                    cleanQuery.contains("show my photos") || cleanQuery.contains("browse my files")
            if (isOpenFiles) {
                _agentStatusFlow.emit("Opening file manager...")
                return try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        type = "resource/folder"
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    fast("Opening file manager.")
                } catch (e: Exception) {
                    // Fallback: open the app launcher with "files"
                    val tool = ToolRegistry.get("app_launcher")
                    if (tool != null) {
                        val result = tool.execute(JsonObject().apply { addProperty("app_name", "files") })
                        if (result.success) return fast(result.data)
                    }
                    QueryResult("Could not open file manager.", true)
                }
            }

            // T. Play Media / Search Music (before open_app to avoid "start playing" collision)
            val isPlayMedia = matchedIntent == "play_media" || matchedIntent == "play_spotify" ||
                matchedIntent == "play_youtube" ||
                MUSIC_PLAY_REGEX.containsMatchIn(cleanQuery) ||
                MUSIC_LISTEN_REGEX.containsMatchIn(cleanQuery)
            if (isPlayMedia && !EntityExtractor.isScreenshotQuery(cleanQuery)) {
                val (mediaQuery, targetApp) = EntityExtractor.extractMediaQuery(preprocessed.originalText)
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
                var appName = EntityExtractor.extractLaunchAppName(preprocessed.originalText)
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
                    NAV_NAVIGATE_REGEX.containsMatchIn(cleanQuery) ||
                    NAV_DIRECTIONS_REGEX.containsMatchIn(cleanQuery)
            if (isNavigateTo) {
                var destination = ""
                for (p in NAV_PATTERNS) {
                    val match = p.find(preprocessed.originalText)
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
                    ALARM_FOR_REGEX.containsMatchIn(cleanQuery) ||
                    ALARM_WAKE_REGEX.containsMatchIn(cleanQuery)
            if (isSetAlarm) {
                var hour = 7
                var minute = 0
                var label = "Friday Alarm"
                
                val labelMatch = ALARM_LABEL_REGEX.find(preprocessed.originalText)
                if (labelMatch != null) {
                    label = labelMatch.groupValues[1].trim()
                }
                
                val timeMatch = ALARM_TIME_REGEX.find(preprocessed.originalText)
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
                    TIMER_FOR_REGEX.containsMatchIn(cleanQuery) ||
                    TIMER_COUNTDOWN_REGEX.containsMatchIn(cleanQuery)
            if (isSetTimer) {
                var durationSeconds = 300 // default 5 minutes
                var label = "Friday Timer"
                
                val labelMatch = TIMER_LABEL_REGEX.find(preprocessed.originalText)
                if (labelMatch != null) {
                    label = labelMatch.groupValues[1].trim()
                }
                
                val durationMatch = TIMER_DURATION_REGEX.find(preprocessed.originalText)
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

            // V1. Airplane Mode
            val isAirplaneModeQuery = matchedIntent == "airplane_mode_toggle" ||
                cleanQuery.contains("airplane mode") || cleanQuery.contains("flight mode") ||
                cleanQuery.contains("aeroplane mode")
            if (isAirplaneModeQuery) {
                val isOff = cleanQuery.contains("off") || cleanQuery.contains("disable") ||
                    cleanQuery.contains("deactivate") || cleanQuery.contains("turn off")
                val isOn = cleanQuery.contains("on") || cleanQuery.contains("enable") ||
                    cleanQuery.contains("activate") || cleanQuery.contains("turn on")
                val state = if (isOff && !isOn) "off" else "on"
                _agentStatusFlow.emit(if (state == "on") "Enabling airplane mode..." else "Disabling airplane mode...")
                val tool = ToolRegistry.get("system_control")
                if (tool != null) {
                    val result = tool.execute(JsonObject().apply {
                        addProperty("action", "toggle_airplane_mode")
                        addProperty("value", state)
                    })
                    return if (result.success) fast(result.data, "airplane_mode", state) else QueryResult("Failed to toggle airplane mode.", true)
                }
            }

            // V2. Mobile Data
            val isMobileDataQuery = matchedIntent == "mobile_data_toggle" ||
                cleanQuery.contains("mobile data") || cleanQuery.contains("cellular data") ||
                cleanQuery.contains("data connection") || cleanQuery.contains("turn on data") ||
                cleanQuery.contains("turn off data") || cleanQuery.contains("enable data") ||
                cleanQuery.contains("disable data")
            if (isMobileDataQuery) {
                val isOff = cleanQuery.contains("off") || cleanQuery.contains("disable") ||
                    cleanQuery.contains("deactivate") || cleanQuery.contains("turn off")
                val isOn = cleanQuery.contains("on") || cleanQuery.contains("enable") ||
                    cleanQuery.contains("activate") || cleanQuery.contains("turn on")
                val state = if (isOff && !isOn) "off" else "on"
                _agentStatusFlow.emit(if (state == "on") "Enabling mobile data..." else "Disabling mobile data...")
                val tool = ToolRegistry.get("system_control")
                if (tool != null) {
                    val result = tool.execute(JsonObject().apply {
                        addProperty("action", "toggle_mobile_data")
                        addProperty("value", state)
                    })
                    return if (result.success) fast(result.data, "mobile_data", state) else QueryResult("Failed to toggle mobile data.", true)
                }
            }

            // V3. Camera
            val isCameraQuery = matchedIntent == "open_camera" ||
                (cleanQuery.contains("camera") && !cleanQuery.contains("screenshot")) ||
                cleanQuery.contains("take a photo") || cleanQuery.contains("take a picture") ||
                cleanQuery.contains("open camera") || cleanQuery.contains("launch camera") ||
                cleanQuery.contains("capture photo") || cleanQuery.contains("snap a photo")
            if (isCameraQuery && !EntityExtractor.isScreenshotQuery(cleanQuery)) {
                val isCapture = cleanQuery.contains("take") || cleanQuery.contains("capture") ||
                    cleanQuery.contains("snap") || cleanQuery.contains("photo") || cleanQuery.contains("picture")
                val cameraAction = if (isCapture) "capture_photo" else "open_camera"
                _agentStatusFlow.emit("Opening camera...")
                val tool = ToolRegistry.get("camera_control")
                if (tool != null) {
                    val result = tool.execute(JsonObject().apply {
                        addProperty("action", cameraAction)
                    })
                    return toolResult(result)
                }
            }

            // V4. Notes
            val isNotesQuery = matchedIntent == "notes_create" || matchedIntent == "notes_list" ||
                matchedIntent == "notes_search" || matchedIntent == "notes_delete" ||
                cleanQuery.contains("note") || cleanQuery.contains("jot down") ||
                cleanQuery.contains("write down") || cleanQuery.contains("save a note") ||
                cleanQuery.contains("remind me") && !cleanQuery.contains("remind me at") ||
                cleanQuery.contains("my notes")
            if (isNotesQuery) {
                val tool = ToolRegistry.get("notes_control")
                if (tool != null) {
                    when {
                        cleanQuery.contains("jot down") || cleanQuery.contains("write down") ||
                        cleanQuery.contains("save a note") || cleanQuery.contains("save note") ||
                        cleanQuery.contains("note that") || cleanQuery.contains("note down") ||
                        matchedIntent == "notes_create" -> {
                            val contentPatterns = listOf(
                                "(?i)(?:note that|jot down|write down|save a? ?note(?:(?: that| saying)?))\\s+(.+)".toRegex(),
                                NOTES_REMIND_REGEX,
                                NOTES_PLAIN_REGEX
                            )
                            var content = ""
                            for (p in contentPatterns) {
                                val m = p.find(preprocessed.originalText)
                                if (m != null) { content = m.groupValues[1].trim(); break }
                            }
                            if (content.isEmpty()) content = preprocessed.originalText
                                .replace(NOTES_STRIP_REGEX, "").trim()
                            if (content.isNotEmpty()) {
                                _agentStatusFlow.emit("Saving note...")
                                val result = tool.execute(JsonObject().apply {
                                    addProperty("action", "create")
                                    addProperty("content", content)
                                })
                                return toolResult(result)
                            }
                        }
                        cleanQuery.contains("find note") || cleanQuery.contains("search note") ||
                        cleanQuery.contains("look up note") || matchedIntent == "notes_search" -> {
                            val qMatch = Regex("(?i)(?:find|search|look up)\\s+(?:notes?\\s+)?(?:about|for|with)?\\s+(.+)").find(preprocessed.originalText)
                            val q = qMatch?.groupValues?.get(1)?.trim() ?: ""
                            if (q.isNotEmpty()) {
                                _agentStatusFlow.emit("Searching notes...")
                                val result = tool.execute(JsonObject().apply {
                                    addProperty("action", "search")
                                    addProperty("query", q)
                                })
                                return toolResult(result)
                            }
                        }
                        (cleanQuery.contains("delete note") || cleanQuery.contains("remove note")) &&
                        matchedIntent == "notes_delete" -> {
                            val idMatch = NOTES_ID_REGEX.find(cleanQuery)
                            if (idMatch != null) {
                                val id = idMatch.groupValues[1].toLong()
                                _agentStatusFlow.emit("Deleting note...")
                                val result = tool.execute(JsonObject().apply {
                                    addProperty("action", "delete")
                                    addProperty("note_id", id)
                                })
                                return toolResult(result)
                            }
                        }
                        else -> {
                            _agentStatusFlow.emit("Reading notes...")
                            val result = tool.execute(JsonObject().apply {
                                addProperty("action", "list")
                            })
                            return toolResult(result)
                        }
                    }
                }
            }

            // U. WhatsApp Send Message (NLU Route Integration)
            if (matchedIntent == "send_whatsapp" && confidence > 0.7f) {
                var recipient = preprocessed.extractedEntities["[CONTACT]"] ?: ""
                var msgText = preprocessed.extractedEntities["[QUOTE]"] ?: ""
                if (recipient.isEmpty() || msgText.isEmpty()) {
                    for (pattern in WHATSAPP_PATTERNS) {
                        val match = pattern.find(preprocessed.originalText)
                        if (match != null) {
                            recipient = match.groupValues[1].trim()
                            msgText = match.groupValues[2].removePrefix("\"").removeSuffix("\"").trim()
                            break
                        }
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
        }        }

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
        val match = TOOL_CALL_REGEX.find(response)
        if (match != null) {
            val toolName = match.groupValues[1]
            val args = JsonObject()
            TOOL_ARG_REGEX.findAll(response).forEach { argMatch ->
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
        return text.replace(EMOJI_REGEX, "").trim()
    }
}
