package com.friday.assistant.intelligence

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.friday.assistant.core.FridayApplication
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
    private val modelManager = com.friday.assistant.core.ModelManager(context)

    // Flow to emit streaming output/status events for the UI overlay to show in real time
    private val _agentStatusFlow = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val agentStatusFlow: SharedFlow<String> = _agentStatusFlow.asSharedFlow()

    suspend fun processQuery(userInput: String): String {
        com.friday.assistant.core.FridayLogger.i(TAG, "AgentCore processing query: '$userInput'")
        
        // 1. Core Intent Classification (NLU Classifier or Rule-Based Fallback)
        val cleanQuery = userInput.trim().lowercase()
        var matchedIntent = "unknown"
        var confidence = 0f

        if (nluClassifier.isModelLoaded()) {
            val res = nluClassifier.classifyIntent(userInput)
            matchedIntent = res.first
            confidence = res.second
        }

        // Apply rules directly if intent matched with high confidence (>0.7), OR if we fallback to rule matching
        val isVolumeUp = matchedIntent == "volume_up" && confidence > 0.7f ||
                Regex("volume\\s+up").containsMatchIn(cleanQuery) ||
                Regex("increase\\s+(?:the\\s+)?volume").containsMatchIn(cleanQuery) ||
                Regex("make\\s+(?:it|the\\s+volume)\\s+louder").containsMatchIn(cleanQuery) ||
                Regex("turn\\s+(?:it|the\\s+volume)\\s+up").containsMatchIn(cleanQuery)

        val isVolumeDown = matchedIntent == "volume_down" && confidence > 0.7f ||
                Regex("volume\\s+down").containsMatchIn(cleanQuery) ||
                Regex("decrease\\s+(?:the\\s+)?volume").containsMatchIn(cleanQuery) ||
                Regex("lower\\s+(?:the\\s+)?volume").containsMatchIn(cleanQuery) ||
                Regex("make\\s+(?:it|the\\s+volume)\\s+quieter").containsMatchIn(cleanQuery) ||
                Regex("turn\\s+(?:it|the\\s+volume)\\s+down").containsMatchIn(cleanQuery)

        val isMute = Regex("mute\\s+(?:the\\s+)?volume").containsMatchIn(cleanQuery) ||
                Regex("mute\\s+(?:the\\s+)?audio").containsMatchIn(cleanQuery) ||
                (cleanQuery.contains("mute") && !cleanQuery.contains("unmute"))

        val isUnmute = Regex("unmute\\s+(?:the\\s+)?volume").containsMatchIn(cleanQuery) ||
                Regex("unmute\\s+(?:the\\s+)?audio").containsMatchIn(cleanQuery) ||
                cleanQuery.contains("unmute")

        val isLockPhone = matchedIntent == "lock_phone" && confidence > 0.7f ||
                Regex("lock\\s+(?:the\\s+)?(?:phone|screen)").containsMatchIn(cleanQuery) ||
                cleanQuery.contains("lock screen") ||
                Regex("turn\\s+off\\s+(?:the\\s+)?screen").containsMatchIn(cleanQuery)

        val isSearchReddit = matchedIntent == "search_reddit" && confidence > 0.7f ||
                cleanQuery.contains("on reddit") ||
                cleanQuery.contains("reddit search")

        val isLaunchApp = matchedIntent == "open_app" && confidence > 0.7f ||
                Regex("^(?:open|launch|start)\\s+(.+)").containsMatchIn(cleanQuery)

        com.friday.assistant.core.FridayLogger.d(TAG, "Routing checks: volUp=$isVolumeUp, volDown=$isVolumeDown, mute=$isMute, unmute=$isUnmute, lock=$isLockPhone, reddit=$isSearchReddit, app=$isLaunchApp")

        // 2. Direct Command Execution for system controls
        // A. Volume Controls
        if (isVolumeUp) {
            _agentStatusFlow.emit("Increasing volume...")
            val tool = ToolRegistry.get("system_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply {
                    addProperty("action", "set_volume")
                    addProperty("value", "up")
                })
                return if (result.success) result.data else "Failed to adjust volume."
            }
        }
        if (isVolumeDown) {
            _agentStatusFlow.emit("Decreasing volume...")
            val tool = ToolRegistry.get("system_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply {
                    addProperty("action", "set_volume")
                    addProperty("value", "down")
                })
                return if (result.success) result.data else "Failed to adjust volume."
            }
        }
        if (isMute) {
            _agentStatusFlow.emit("Muting volume...")
            val tool = ToolRegistry.get("system_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply {
                    addProperty("action", "set_volume")
                    addProperty("value", "mute")
                })
                return if (result.success) result.data else "Failed to mute volume."
            }
        }
        if (isUnmute) {
            _agentStatusFlow.emit("Unmuting volume...")
            val tool = ToolRegistry.get("system_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply {
                    addProperty("action", "set_volume")
                    addProperty("value", "50%")
                })
                return if (result.success) result.data else "Failed to unmute volume."
            }
        }

        // B. Brightness Controls
        val isBrightnessUp = Regex("increase\\s+(?:the\\s+)?brightness").containsMatchIn(cleanQuery) ||
                Regex("brightness\\s+up").containsMatchIn(cleanQuery) ||
                Regex("make\\s+(?:it|the\\s+screen)\\s+brighter").containsMatchIn(cleanQuery) ||
                Regex("brighter\\s+screen").containsMatchIn(cleanQuery) ||
                Regex("turn\\s+(?:the\\s+)?brightness\\s+up").containsMatchIn(cleanQuery)

        val isBrightnessDown = Regex("decrease\\s+(?:the\\s+)?brightness").containsMatchIn(cleanQuery) ||
                Regex("brightness\\s+down").containsMatchIn(cleanQuery) ||
                Regex("dim\\s+(?:the\\s+)?screen").containsMatchIn(cleanQuery) ||
                Regex("dimmer\\s+screen").containsMatchIn(cleanQuery) ||
                Regex("lower\\s+(?:the\\s+)?brightness").containsMatchIn(cleanQuery) ||
                Regex("turn\\s+(?:the\\s+)?brightness\\s+down").containsMatchIn(cleanQuery)

        val brightnessPctMatch = Regex("brightness\\s+(?:to\\s+)?(\\d+)(?:\\s*%|\\s*percent)?").find(cleanQuery)
        
        if (isBrightnessUp) {
            _agentStatusFlow.emit("Increasing brightness...")
            val tool = ToolRegistry.get("system_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply {
                    addProperty("action", "set_brightness")
                    addProperty("value", "up")
                })
                return if (result.success) result.data else "Failed to adjust brightness."
            }
        }
        if (isBrightnessDown) {
            _agentStatusFlow.emit("Decreasing brightness...")
            val tool = ToolRegistry.get("system_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply {
                    addProperty("action", "set_brightness")
                    addProperty("value", "down")
                })
                return if (result.success) result.data else "Failed to adjust brightness."
            }
        }
        if (brightnessPctMatch != null) {
            val pct = brightnessPctMatch.groupValues[1]
            _agentStatusFlow.emit("Setting brightness to $pct%...")
            val tool = ToolRegistry.get("system_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply {
                    addProperty("action", "set_brightness")
                    addProperty("value", "$pct%")
                })
                return if (result.success) result.data else "Failed to set brightness."
            }
        }

        // C. Flashlight (Torch)
        val isFlashlightOn = Regex("(?:turn\\s+)?on\\s+(?:the\\s+)?(?:flashlight|torch)").containsMatchIn(cleanQuery) ||
                Regex("(?:flashlight|torch)\\s+on").containsMatchIn(cleanQuery)

        val isFlashlightOff = Regex("(?:turn\\s+)?off\\s+(?:the\\s+)?(?:flashlight|torch)").containsMatchIn(cleanQuery) ||
                Regex("(?:flashlight|torch)\\s+off").containsMatchIn(cleanQuery)

        if (isFlashlightOn) {
            _agentStatusFlow.emit("Turning flashlight on...")
            val tool = ToolRegistry.get("system_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply {
                    addProperty("action", "toggle_torch")
                    addProperty("value", "on")
                })
                return if (result.success) result.data else "Failed to toggle flashlight."
            }
        }
        if (isFlashlightOff) {
            _agentStatusFlow.emit("Turning flashlight off...")
            val tool = ToolRegistry.get("system_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply {
                    addProperty("action", "toggle_torch")
                    addProperty("value", "off")
                })
                return if (result.success) result.data else "Failed to toggle flashlight."
            }
        }

        // D. WiFi Controls
        val isWifiOn = Regex("(?:turn\\s+)?on\\s+(?:the\\s+)?wifi").containsMatchIn(cleanQuery) ||
                Regex("wifi\\s+on").containsMatchIn(cleanQuery) ||
                Regex("enable\\s+(?:the\\s+)?wifi").containsMatchIn(cleanQuery)

        val isWifiOff = Regex("(?:turn\\s+)?off\\s+(?:the\\s+)?wifi").containsMatchIn(cleanQuery) ||
                Regex("wifi\\s+off").containsMatchIn(cleanQuery) ||
                Regex("disable\\s+(?:the\\s+)?wifi").containsMatchIn(cleanQuery)

        if (isWifiOn) {
            _agentStatusFlow.emit("Turning WiFi on...")
            val tool = ToolRegistry.get("system_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply {
                    addProperty("action", "toggle_wifi")
                    addProperty("value", "on")
                })
                return if (result.success) result.data else "Failed to toggle WiFi."
            }
        }
        if (isWifiOff) {
            _agentStatusFlow.emit("Turning WiFi off...")
            val tool = ToolRegistry.get("system_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply {
                    addProperty("action", "toggle_wifi")
                    addProperty("value", "off")
                })
                return if (result.success) result.data else "Failed to toggle WiFi."
            }
        }

        // E. Bluetooth Controls
        val isBluetoothOn = Regex("(?:turn\\s+)?on\\s+(?:the\\s+)?bluetooth").containsMatchIn(cleanQuery) ||
                Regex("bluetooth\\s+on").containsMatchIn(cleanQuery) ||
                Regex("enable\\s+(?:the\\s+)?bluetooth").containsMatchIn(cleanQuery)

        val isBluetoothOff = Regex("(?:turn\\s+)?off\\s+(?:the\\s+)?bluetooth").containsMatchIn(cleanQuery) ||
                Regex("bluetooth\\s+off").containsMatchIn(cleanQuery) ||
                Regex("disable\\s+(?:the\\s+)?bluetooth").containsMatchIn(cleanQuery)

        if (isBluetoothOn) {
            _agentStatusFlow.emit("Turning Bluetooth on...")
            val tool = ToolRegistry.get("system_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply {
                    addProperty("action", "toggle_bluetooth")
                    addProperty("value", "on")
                })
                return if (result.success) result.data else "Failed to toggle Bluetooth."
            }
        }
        if (isBluetoothOff) {
            _agentStatusFlow.emit("Turning Bluetooth off...")
            val tool = ToolRegistry.get("system_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply {
                    addProperty("action", "toggle_bluetooth")
                    addProperty("value", "off")
                })
                return if (result.success) result.data else "Failed to toggle Bluetooth."
            }
        }

        // F. Do Not Disturb (DND)
        val isDndOn = Regex("(?:turn\\s+)?on\\s+(?:do\\s+not\\s+disturb|dnd)").containsMatchIn(cleanQuery) ||
                Regex("(?:do\\s+not\\s+disturb|dnd)\\s+on").containsMatchIn(cleanQuery) ||
                Regex("(?:enable|activate)\\s+(?:do\\s+not\\s+disturb|dnd)").containsMatchIn(cleanQuery)

        val isDndOff = Regex("(?:turn\\s+)?off\\s+(?:do\\s+not\\s+disturb|dnd)").containsMatchIn(cleanQuery) ||
                Regex("(?:do\\s+not\\s+disturb|dnd)\\s+off").containsMatchIn(cleanQuery) ||
                Regex("(?:disable|deactivate)\\s+(?:do\\s+not\\s+disturb|dnd)").containsMatchIn(cleanQuery)

        if (isDndOn) {
            _agentStatusFlow.emit("Activating DND...")
            val tool = ToolRegistry.get("system_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply {
                    addProperty("action", "toggle_dnd")
                    addProperty("value", "on")
                })
                return if (result.success) result.data else "Failed to toggle DND."
            }
        }
        if (isDndOff) {
            _agentStatusFlow.emit("Deactivating DND...")
            val tool = ToolRegistry.get("system_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply {
                    addProperty("action", "toggle_dnd")
                    addProperty("value", "off")
                })
                return if (result.success) result.data else "Failed to toggle DND."
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
                            return result.data
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
                            return result.data
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
                            return result.data
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
                return result.data
            }
        }

        // J. Replying to Notifications
        if (cleanQuery.contains("reply") && cleanQuery.contains("notification")) {
            val replyPattern = "(?i)reply\\s+to\\s+(.+?)\\s+(?:saying|with)?\\s*(.+)".toRegex()
            val match = replyPattern.find(userInput)
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
                    return result.data
                }
            }
        }

        // K. Lock screen
        if (isLockPhone) {
            return "Locking the screen is not supported in Default Assistant mode without Accessibility privileges."
        }

        // L. Reddit search
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
                "Searching for '$searchPhrase' on Reddit."
            } catch (e: Exception) {
                "Failed to open Reddit search."
            }
        }

        // M. Open App
        if (isLaunchApp) {
            var appName = cleanQuery.removePrefix("open ").removePrefix("launch ").removePrefix("start ").trim()
            if (appName.startsWith("the ")) {
                appName = appName.substring(4).trim()
            }
            if (appName.isNotEmpty()) {
                _agentStatusFlow.emit("Opening $appName...")
                val tool = ToolRegistry.get("app_launcher")
                if (tool != null) {
                    val result = tool.execute(JsonObject().apply {
                        addProperty("app_name", appName)
                    })
                    if (result.success) return result.data
                }
            }
        }

        // N. Flashlight Strength Adjustment
        val isTorchStrength = matchedIntent == "torch_strength" && confidence > 0.7f ||
                cleanQuery.contains("torch strength") || cleanQuery.contains("flashlight strength") || cleanQuery.contains("torch level")
        if (isTorchStrength) {
            val pctMatch = Regex("(\\d+)").find(cleanQuery)
            val pct = if (pctMatch != null) {
                val num = pctMatch.groupValues[1].toInt()
                if (num <= 5) (num * 20).toString() else num.toString()
            } else if (cleanQuery.contains("max") || cleanQuery.contains("full")) {
                "100"
            } else if (cleanQuery.contains("low") || cleanQuery.contains("min")) {
                "20"
            } else if (cleanQuery.contains("medium") || cleanQuery.contains("half")) {
                "50"
            } else {
                "50"
            }
            _agentStatusFlow.emit("Setting torch strength to $pct%...")
            val tool = ToolRegistry.get("system_control")
            if (tool != null) {
                val result = tool.execute(JsonObject().apply {
                    addProperty("action", "set_torch_strength")
                    addProperty("value", "$pct%")
                })
                return if (result.success) result.data else "Failed to adjust torch strength."
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
                return if (result.success) result.data else "Failed to open screen cast settings."
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
                return if (result.success) result.data else "Failed to open battery saver settings."
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
                    return result.data
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
                return result.data
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
                return result.data
            }
        }

        // T. Play Media / Search Music
        val isPlayMedia = matchedIntent == "play_media" && confidence > 0.7f ||
                Regex("play\\s+(.+)").containsMatchIn(cleanQuery) ||
                Regex("listen\\s+to\\s+(.+)").containsMatchIn(cleanQuery)
        if (isPlayMedia) {
            val patterns = listOf(
                "(?i)play\\s+(.+)".toRegex(),
                "(?i)listen\\s+to\\s+(.+)".toRegex(),
                "(?i)start\\s+playing\\s+(.+)".toRegex()
            )
            var searchQuery = ""
            for (p in patterns) {
                val match = p.find(userInput)
                if (match != null) {
                    searchQuery = match.groupValues[1].trim()
                    break
                }
            }
            if (searchQuery.isEmpty()) {
                searchQuery = cleanQuery.replace("play", "").replace("listen", "").replace("to", "").replace("music", "").trim()
            }
            
            if (searchQuery.isNotEmpty()) {
                _agentStatusFlow.emit("Playing music...")
                val mediaTool = ToolRegistry.get("media_control")
                if (mediaTool != null) {
                    val result = mediaTool.execute(JsonObject().apply {
                        addProperty("action", "play_search")
                        addProperty("query", searchQuery)
                    })
                    return result.data
                }
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
                    return result.data
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
                    return "Failed to load the local brain. Please check if your device has enough free memory."
                }
            }
            _agentStatusFlow.emit("Thinking...")
            val currentPrompt = promptBuilder.buildPrompt(userInput)
            val response = llamaEngine.generate(currentPrompt, maxTokens = 128, temp = 0.7f).trim()
            val finalResponse = sanitizeResponse(response)
            
            memoryManager.saveConversationTurn(userInput, finalResponse)
            return finalResponse
        } else {
            // General Search Fallback for unstructured queries
            if (cleanQuery.startsWith("search ") || cleanQuery.startsWith("google ") || cleanQuery.contains("what is") || cleanQuery.contains("how to") || cleanQuery.contains("who is")) {
                _agentStatusFlow.emit("Searching the web...")
                val searchTool = ToolRegistry.get("web_search")
                if (searchTool != null) {
                    val result = searchTool.execute(JsonObject().apply {
                        addProperty("query", userInput)
                    })
                    if (result.success) return result.data
                }
            }

            return "I'm running in offline assistant mode, but the local brain (Qwen GGUF) is not loaded or has been offloaded. You can download or enable it in the Friday app dashboard."
        }
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
