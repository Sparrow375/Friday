package com.friday.assistant.tools.notes

import com.friday.assistant.intelligence.MemoryManager
import com.friday.assistant.tools.Tool
import com.friday.assistant.tools.ToolResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class RememberPreferenceTool(private val memoryManager: MemoryManager) : Tool {

    override val name: String = "remember_preference"

    override val description: String = """
        Saves a personal preference, fact, or routine about the user in semantic memory 
        so that Friday can recall it in future interactions (e.g. key: 'favorite_coffee', value: 'latte').
    """.trimIndent()

    override val parameters: JsonObject = JsonParser.parseString("""
        {
          "type": "object",
          "properties": {
            "key": {
              "type": "string",
              "description": "The normalized identifier of the preference (e.g., 'home_address', 'friend_name')"
            },
            "value": {
              "type": "string",
              "description": "The fact or detail to remember (e.g., '123 Cyber Lane', 'Justin')"
            }
          },
          "required": ["key", "value"]
        }
    """).asJsonObject

    override suspend fun execute(args: JsonObject): ToolResult {
        val key = args.get("key")?.asString ?: return ToolResult(false, "Missing parameter: key")
        val value = args.get("value")?.asString ?: return ToolResult(false, "Missing parameter: value")
        
        memoryManager.savePreference(key, value)
        return ToolResult(true, "I will remember that: $key is $value")
    }
}

class RecallPreferenceTool(private val memoryManager: MemoryManager) : Tool {

    override val name: String = "recall_preference"

    override val description: String = """
        Recalls a saved user preference or fact by its key identifier.
    """.trimIndent()

    override val parameters: JsonObject = JsonParser.parseString("""
        {
          "type": "object",
          "properties": {
            "key": {
              "type": "string",
              "description": "The key of the preference to look up"
            }
          },
          "required": ["key"]
        }
    """).asJsonObject

    override suspend fun execute(args: JsonObject): ToolResult {
        val key = args.get("key")?.asString ?: return ToolResult(false, "Missing parameter: key")
        val value = memoryManager.getPreference(key)
        
        if (value != null) {
            return ToolResult(true, "Memory recall for '$key': $value")
        }

        // Fallback: search notes for the query key
        val notesTool = com.friday.assistant.tools.ToolRegistry.get("notes_control")
        if (notesTool != null) {
            val noteRes = notesTool.execute(JsonObject().apply {
                addProperty("action", "search")
                addProperty("query", key)
            })
            if (noteRes.success && !noteRes.data.startsWith("No notes found") && !noteRes.data.startsWith("You do not have")) {
                return noteRes
            }
        }

        return ToolResult(true, "I do not have any memory or note saved about '$key'.")
    }
}
