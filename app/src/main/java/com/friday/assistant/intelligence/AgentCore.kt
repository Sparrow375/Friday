package com.friday.assistant.intelligence

import android.accessibilityservice.AccessibilityService
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

    // Flow to emit streaming output/status events for the UI overlay to show in real time
    private val _agentStatusFlow = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val agentStatusFlow: SharedFlow<String> = _agentStatusFlow.asSharedFlow()

    suspend fun processQuery(userInput: String): String {
        Log.i(TAG, "AgentCore processing query: '$userInput'")
        
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
        val isVolumeUp = matchedIntent == "volume_up" && confidence > 0.7f || cleanQuery.contains("increase volume") || cleanQuery.contains("volume up") || cleanQuery.contains("make it louder")
        val isVolumeDown = matchedIntent == "volume_down" && confidence > 0.7f || cleanQuery.contains("decrease volume") || cleanQuery.contains("volume down") || cleanQuery.contains("lower volume")
        val isLockPhone = matchedIntent == "lock_phone" && confidence > 0.7f || cleanQuery.contains("lock the phone") || cleanQuery.contains("lock screen") || cleanQuery.contains("lock phone")
        val isSearchReddit = matchedIntent == "search_reddit" && confidence > 0.7f || cleanQuery.contains("on reddit")
        val isLaunchApp = matchedIntent == "open_app" && confidence > 0.7f || cleanQuery.startsWith("open ") || cleanQuery.startsWith("launch ")

        Log.d(TAG, "Routing checks: volUp=$isVolumeUp, volDown=$isVolumeDown, lock=$isLockPhone, reddit=$isSearchReddit, app=$isLaunchApp")

        // 2. Direct Command Execution
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

        if (isLockPhone) {
            _agentStatusFlow.emit("Locking screen...")
            val service = FridayService.instance
            if (service != null) {
                val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                } else {
                    false
                }
                return if (success) {
                    "I've locked your phone."
                } else {
                    "I was unable to lock the screen. Ensure the Friday accessibility service is enabled."
                }
            } else {
                return "The Accessibility service is not running. Please enable it in Settings to allow screen locking."
            }
        }

        if (isSearchReddit) {
            // Extract search phrase: e.g., "search space x on reddit" -> "space x"
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

        if (isLaunchApp) {
            val appName = cleanQuery.removePrefix("open ").removePrefix("launch ").trim()
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
        if (llamaEngine.isModelLoaded()) {
            _agentStatusFlow.emit("Thinking...")
            llamaEngine.clearHistory()
            var currentPrompt = promptBuilder.buildPrompt(userInput)
            var loopCount = 0
            var finalResponse = ""

            while (loopCount < MAX_TOOL_LOOPS) {
                val response = llamaEngine.generate(currentPrompt, maxTokens = 256, temp = 0.5f).trim()
                val toolResult = ToolRegistry.getManifestJson() // Just helper structure checks
                
                // Decode tool calls dynamically
                val executedTool = ToolRegistry.getAll().firstOrNull { tool ->
                    response.contains("\"tool\": \"${tool.name}\"") || response.contains("execute: ${tool.name}")
                }

                if (executedTool != null) {
                    loopCount++
                    _agentStatusFlow.emit("Using tool: ${executedTool.name}...")
                    
                    // Simple parsing for args (simulate dispatch)
                    val mockArgs = JsonObject()
                    val result = executedTool.execute(mockArgs)
                    
                    currentPrompt = "<|im_start|>user\n[Tool Result: ${result.data}]<|im_end|>\n<|im_start|>assistant\n"
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

            return "I'm running in offline assistant mode, but the local reasoning brain (Qwen GGUF) is not loaded. You can load it in the Friday app dashboard, or give me commands like 'increase volume' or 'search [x] on reddit'."
        }
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
