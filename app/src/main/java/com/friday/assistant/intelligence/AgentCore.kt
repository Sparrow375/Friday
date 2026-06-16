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
        val isVolumeUp = matchedIntent == "volume_up" && confidence > 0.7f || cleanQuery.contains("increase volume") || cleanQuery.contains("volume up") || cleanQuery.contains("make it louder") || cleanQuery.contains("turn it up")
        val isVolumeDown = matchedIntent == "volume_down" && confidence > 0.7f || cleanQuery.contains("decrease volume") || cleanQuery.contains("volume down") || cleanQuery.contains("lower volume") || cleanQuery.contains("make it quieter") || cleanQuery.contains("turn it down")
        val isMute = cleanQuery.contains("mute") && !cleanQuery.contains("unmute")
        val isUnmute = cleanQuery.contains("unmute")
        val isLockPhone = matchedIntent == "lock_phone" && confidence > 0.7f || cleanQuery.contains("lock the phone") || cleanQuery.contains("lock screen") || cleanQuery.contains("lock phone") || cleanQuery.contains("turn off screen")
        val isSearchReddit = matchedIntent == "search_reddit" && confidence > 0.7f || cleanQuery.contains("on reddit")
        val isLaunchApp = matchedIntent == "open_app" && confidence > 0.7f || cleanQuery.startsWith("open ") || cleanQuery.startsWith("launch ") || cleanQuery.startsWith("start ")

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
        val isBrightnessUp = cleanQuery.contains("increase brightness") || cleanQuery.contains("brightness up") || cleanQuery.contains("make it brighter") || cleanQuery.contains("brighter screen")
        val isBrightnessDown = cleanQuery.contains("decrease brightness") || cleanQuery.contains("brightness down") || cleanQuery.contains("dim screen") || cleanQuery.contains("dimmer screen") || cleanQuery.contains("lower brightness")
        val brightnessPctMatch = "brightness(?: to)?\\s*(\\d+)%".toRegex().find(cleanQuery)
        
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
        val isFlashlightOn = cleanQuery.contains("flashlight on") || cleanQuery.contains("turn on flashlight") || cleanQuery.contains("torch on") || cleanQuery.contains("turn on torch")
        val isFlashlightOff = cleanQuery.contains("flashlight off") || cleanQuery.contains("turn off flashlight") || cleanQuery.contains("torch off") || cleanQuery.contains("turn off torch")
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
        val isWifiOn = cleanQuery.contains("turn on wifi") || cleanQuery.contains("wifi on") || cleanQuery.contains("enable wifi")
        val isWifiOff = cleanQuery.contains("turn off wifi") || cleanQuery.contains("wifi off") || cleanQuery.contains("disable wifi")
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
        val isBluetoothOn = cleanQuery.contains("turn on bluetooth") || cleanQuery.contains("bluetooth on") || cleanQuery.contains("enable bluetooth")
        val isBluetoothOff = cleanQuery.contains("turn off bluetooth") || cleanQuery.contains("bluetooth off") || cleanQuery.contains("disable bluetooth")
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
        val isDndOn = cleanQuery.contains("turn on do not disturb") || cleanQuery.contains("dnd on") || cleanQuery.contains("enable dnd") || cleanQuery.contains("activate do not disturb")
        val isDndOff = cleanQuery.contains("turn off do not disturb") || cleanQuery.contains("dnd off") || cleanQuery.contains("disable dnd") || cleanQuery.contains("deactivate do not disturb")
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
        val isListNotifications = cleanQuery.contains("notifications") || cleanQuery.contains("notification") || cleanQuery.contains("check messages") || cleanQuery.contains("any messages") || cleanQuery.contains("any mail")
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
            val appName = cleanQuery.removePrefix("open ").removePrefix("launch ").removePrefix("start ").trim()
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

        // 3. Fallback: LLM Reasoner or Search Fallback
        val sharedPrefs = context.getSharedPreferences("friday_model_prefs", Context.MODE_PRIVATE)
        val useLlm = sharedPrefs.getBoolean("use_llm", true)

        if (useLlm && modelManager.isLlmLoaded()) {
            if (!llamaEngine.isModelLoaded()) {
                _agentStatusFlow.emit("Loading reasoning brain...")
                val path = modelManager.getLlmModelPath()
                com.friday.assistant.core.FridayLogger.i(TAG, "Lazy loading LLM GGUF model from: $path")
                val success = llamaEngine.loadModel(path)
                com.friday.assistant.core.FridayLogger.i(TAG, "LLM GGUF model load success: $success")
                if (!success) {
                    return "Failed to load the local reasoning brain. Please check if your device has enough free memory."
                }
            }
            _agentStatusFlow.emit("Thinking...")
            llamaEngine.clearHistory()
            var currentPrompt = promptBuilder.buildPrompt(userInput)
            var loopCount = 0
            var finalResponse = ""

            while (loopCount < MAX_TOOL_LOOPS) {
                val response = llamaEngine.generate(currentPrompt, maxTokens = 256, temp = 0.5f).trim()
                com.friday.assistant.core.FridayLogger.d(TAG, "Llama response iteration $loopCount: '$response'")
                
                // Parse tool call JSON dynamically
                val toolCall = parseToolCall(response)
                
                if (toolCall != null) {
                    val toolName = toolCall.first
                    val arguments = toolCall.second
                    
                    val executedTool = ToolRegistry.get(toolName)
                    if (executedTool != null) {
                        loopCount++
                        _agentStatusFlow.emit("Using tool: ${executedTool.name}...")
                        com.friday.assistant.core.FridayLogger.i(TAG, "Executing tool '${executedTool.name}' with arguments: $arguments")
                        
                        val result = executedTool.execute(arguments)
                        com.friday.assistant.core.FridayLogger.i(TAG, "Tool result success: ${result.success}, data: ${result.data}")
                        
                        // Append model's response and tool result to currentPrompt
                        val responseWithEnd = if (response.endsWith("<|im_end|>")) response else "$response<|im_end|>\n"
                        val updatedHistory = currentPrompt.removeSuffix("<|im_start|>assistant\n") + 
                                             "<|im_start|>assistant\n" + responseWithEnd + 
                                             "<|im_start|>user\n[Tool Result: ${result.data}]<|im_end|>\n" +
                                             "<|im_start|>assistant\n"
                        currentPrompt = updatedHistory
                    } else {
                        com.friday.assistant.core.FridayLogger.w(TAG, "Tool '$toolName' matched but not found in ToolRegistry")
                        finalResponse = sanitizeResponse(response)
                        break
                    }
                } else {
                    finalResponse = sanitizeResponse(response)
                    break
                }
            }

            if (finalResponse.isEmpty()) {
                finalResponse = "I couldn't finish executing that query."
            }

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

            return "I'm running in offline assistant mode, but the local reasoning brain (Qwen GGUF) is not loaded or has been offloaded. You can download or enable it in the Friday app dashboard."
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
