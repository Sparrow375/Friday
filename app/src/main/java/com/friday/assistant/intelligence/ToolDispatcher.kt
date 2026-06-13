package com.friday.assistant.intelligence

import android.util.Log
import com.friday.assistant.tools.ToolRegistry
import com.friday.assistant.tools.ToolResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class ToolDispatcher {
    companion object {
        private const val TAG = "ToolDispatcher"
    }

    /**
     * Parses a potential JSON tool call from LLM response and runs the tool.
     * Returns null if the text is not a valid JSON tool call.
     */
    suspend fun dispatch(llmResponseText: String): ToolResult? {
        val trimmed = llmResponseText.trim()
        
        // Simple heuristic to check if it looks like a JSON block
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return null
        }

        try {
            val jsonObject = JsonParser.parseString(trimmed).asJsonObject
            
            // Check if it has "tool" field
            if (!jsonObject.has("tool")) {
                return null
            }
            
            val toolName = jsonObject.get("tool").asString
            val arguments = if (jsonObject.has("arguments")) {
                jsonObject.get("arguments").asJsonObject
            } else {
                JsonObject()
            }

            Log.i(TAG, "Parsing tool call request: '$toolName' with args: $arguments")
            val tool = ToolRegistry.get(toolName)
            if (tool != null) {
                return try {
                    tool.execute(arguments)
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing tool '$toolName'", e)
                    ToolResult(false, "Tool execution error: ${e.message}")
                }
            } else {
                Log.w(TAG, "Requested tool '$toolName' was not found in ToolRegistry")
                return ToolResult(false, "Tool '$toolName' is not registered or unavailable")
            }
        } catch (e: Exception) {
            Log.d(TAG, "Text was formatted as JSON but did not match tool schema or had syntax errors: ${e.message}")
            return null
        }
    }
}
