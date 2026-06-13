package com.friday.assistant.intelligence

import android.content.Context
import android.util.Log
import com.friday.assistant.core.FridayApplication
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
    private val toolDispatcher = ToolDispatcher()

    // Flow to emit streaming output/status events for the UI overlay to show in real time
    private val _agentStatusFlow = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val agentStatusFlow: SharedFlow<String> = _agentStatusFlow.asSharedFlow()

    suspend fun processQuery(userInput: String): String {
        Log.i(TAG, "AgentCore processing query: '$userInput'")
        _agentStatusFlow.emit("Thinking...")

        // Build the initial prompt (includes system prompts, tool schemas, preferences, history)
        var currentPrompt = promptBuilder.buildPrompt(userInput)
        var loopCount = 0
        var finalResponse = ""

        while (loopCount < MAX_TOOL_LOOPS) {
            Log.d(TAG, "Agent Loop turn: $loopCount")
            
            // Run inference
            val response = llamaEngine.generate(currentPrompt, maxTokens = 256, temp = 0.5f).trim()
            Log.d(TAG, "Llama Raw Output: '$response'")

            // Check if response is a tool call
            val toolResult = toolDispatcher.dispatch(response)
            
            if (toolResult != null) {
                // Yes, it was a tool call and has been executed
                loopCount++
                val statusMessage = "Using tool: ${toolResult.data}"
                _agentStatusFlow.emit(statusMessage)
                Log.i(TAG, "Tool executed: $statusMessage")

                // Formulate feedback prompt for the LLM
                // We append the result into the chat context template
                currentPrompt = "<|im_start|>user\n[Tool Result: ${toolResult.data}]<|im_end|>\n<|im_start|>assistant\n"
                // The loop continues, feeding the tool result back into LlamaEngine (which keeps history cache)
            } else {
                // No tool call detected, it is a final natural language response!
                finalResponse = sanitizeResponse(response)
                break
            }
        }

        if (finalResponse.isEmpty()) {
            finalResponse = "I encountered an issue executing that command."
        }

        // Save conversation turn to SQLite database and working memory
        memoryManager.saveConversationTurn(userInput, finalResponse)

        // Asynchronously extract and save user preferences from the interaction
        PreferenceExtractor.extractAndSavePreferences(userInput, finalResponse, memoryManager)

        Log.i(TAG, "AgentCore finished processing. Final response: '$finalResponse'")
        return finalResponse
    }

    /**
     * Cleans up the raw LLM output, removing templates, markdown fences, or stray formatting.
     */
    private fun sanitizeResponse(raw: String): String {
        var clean = raw
            .replace("<|im_end|>", "")
            .replace("<|im_start|>", "")
            .trim()

        // Strip any markdown code block fences if the model wrapped its plain text
        if (clean.startsWith("```") && clean.endsWith("```")) {
            clean = clean.substringAfter("\n").substringBeforeLast("```").trim()
        }
        
        // Ensure no emojis are returned (just in case LLM ignored the system instructions)
        clean = removeEmojis(clean)
        
        return clean
    }

    private fun removeEmojis(text: String): String {
        // Simple regex to remove emojis and standard miscellaneous symbols
        val emojiRegex = "[\\p{So}\\p{Cn}]".toRegex()
        return text.replace(emojiRegex, "").trim()
    }
}
