package com.friday.assistant.tools.accessibility

import android.accessibilityservice.AccessibilityService
import com.friday.assistant.automation.UIAutomator
import com.friday.assistant.tools.Tool
import com.friday.assistant.tools.ToolResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class ClickElementTool : Tool {
    override val name: String = "click_element"
    
    override val description: String = """
        Taps or clicks an interactive element on the screen using its short ID (e.g. 1, 2) obtained from a previous screen scan.
    """.trimIndent()

    override val parameters: JsonObject = JsonParser.parseString("""
        {
          "type": "object",
          "properties": {
            "element_id": {
              "type": "integer",
              "description": "The short ID of the element to click"
            }
          },
          "required": ["element_id"]
        }
    """).asJsonObject

    override suspend fun execute(args: JsonObject): ToolResult {
        val elementId = args.get("element_id")?.asInt ?: return ToolResult(false, "Missing required parameter: element_id")
        val success = UIAutomator.tapElement(elementId)
        return if (success) {
            ToolResult(true, "Successfully clicked element [$elementId]")
        } else {
            ToolResult(false, "Failed to click element [$elementId]. It might no longer be visible or active.")
        }
    }
}

class TypeTextTool : Tool {
    override val name: String = "type_text"
    
    override val description: String = """
        Types text into an editable input field on the screen using its short ID (e.g. 1, 2) obtained from a previous screen scan.
    """.trimIndent()

    override val parameters: JsonObject = JsonParser.parseString("""
        {
          "type": "object",
          "properties": {
            "element_id": {
              "type": "integer",
              "description": "The short ID of the editable element"
            },
            "text": {
              "type": "string",
              "description": "The text to input into the field"
            }
          },
          "required": ["element_id", "text"]
        }
    """).asJsonObject

    override suspend fun execute(args: JsonObject): ToolResult {
        val elementId = args.get("element_id")?.asInt ?: return ToolResult(false, "Missing required parameter: element_id")
        val text = args.get("text")?.asString ?: return ToolResult(false, "Missing required parameter: text")
        val success = UIAutomator.typeText(elementId, text)
        return if (success) {
            ToolResult(true, "Successfully typed text into element [$elementId]")
        } else {
            ToolResult(false, "Failed to type text into element [$elementId]. Make sure it is editable.")
        }
    }
}

class ScrollScreenTool : Tool {
    override val name: String = "scroll_screen"
    
    override val description: String = """
        Scrolls the screen content in the specified direction ('forward' or 'backward').
    """.trimIndent()

    override val parameters: JsonObject = JsonParser.parseString("""
        {
          "type": "object",
          "properties": {
            "direction": {
              "type": "string",
              "enum": ["forward", "backward"],
              "description": "The direction to scroll"
            }
          },
          "required": ["direction"]
        }
    """).asJsonObject

    override suspend fun execute(args: JsonObject): ToolResult {
        val direction = args.get("direction")?.asString ?: return ToolResult(false, "Missing required parameter: direction")
        val success = UIAutomator.scroll(direction)
        return if (success) {
            ToolResult(true, "Successfully scrolled $direction")
        } else {
            ToolResult(false, "Failed to scroll. No scrollable container found on the active screen.")
        }
    }
}

class GlobalActionTool : Tool {
    override val name: String = "global_system_action"
    
    override val description: String = """
        Performs a system-wide action like going back, returning to home screen, showing recent apps, or locking the screen.
    """.trimIndent()

    override val parameters: JsonObject = JsonParser.parseString("""
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["back", "home", "recents", "lock"],
              "description": "The system action to perform"
            }
          },
          "required": ["action"]
        }
    """).asJsonObject

    override suspend fun execute(args: JsonObject): ToolResult {
        val actionStr = args.get("action")?.asString ?: return ToolResult(false, "Missing required parameter: action")
        val actionId = when (actionStr.lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "lock" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                } else {
                    return ToolResult(false, "Lock action is only supported on Android 9 (API 28) and above")
                }
            }
            else -> return ToolResult(false, "Invalid action: $actionStr")
        }
        val success = UIAutomator.performGlobalAction(actionId)
        return if (success) {
            ToolResult(true, "Successfully executed global action: $actionStr")
        } else {
            ToolResult(false, "Failed to execute global action: $actionStr")
        }
    }
}
