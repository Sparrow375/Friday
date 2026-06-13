package com.friday.assistant.tools.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.friday.assistant.tools.Tool
import com.friday.assistant.tools.ToolResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ClipboardTool(private val context: Context) : Tool {

    companion object {
        private const val TAG = "ClipboardTool"
    }

    override val name: String = "clipboard_control"

    override val description: String = """
        Copies text to the system clipboard or reads the current text copied in the clipboard.
    """.trimIndent()

    override val parameters: JsonObject = JsonParser.parseString("""
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["read", "write"],
              "description": "Teleport text: 'write' to copy text to clipboard, 'read' to view current clipboard content"
            },
            "text": {
              "type": "string",
              "description": "The text content to copy to the clipboard (required for 'write' action)"
            }
          },
          "required": ["action"]
        }
    """).asJsonObject

    override suspend fun execute(args: JsonObject): ToolResult {
        val action = args.get("action")?.asString ?: return ToolResult(false, "Missing required parameter: action")
        
        return withContext(Dispatchers.Main) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            
            when (action) {
                "write" -> {
                    val text = args.get("text")?.asString 
                        ?: return@withContext ToolResult(false, "Missing parameter 'text' for action 'write'")
                    
                    try {
                        val clip = ClipData.newPlainText("Friday Clip", text)
                        clipboard.setPrimaryClip(clip)
                        ToolResult(true, "Successfully copied text to clipboard")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to copy text to clipboard", e)
                        ToolResult(false, "Failed to copy to clipboard: ${e.message}")
                    }
                }
                "read" -> {
                    try {
                        if (clipboard.hasPrimaryClip() && clipboard.primaryClipDescription != null) {
                            val item = clipboard.primaryClip?.getItemAt(0)
                            val clipText = item?.text?.toString() ?: ""
                            if (clipText.isNotEmpty()) {
                                ToolResult(true, "Clipboard content: \"$clipText\"")
                            } else {
                                ToolResult(true, "Clipboard is currently empty")
                            }
                        } else {
                            ToolResult(true, "Clipboard is empty")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to read text from clipboard", e)
                        ToolResult(false, "Failed to read from clipboard: ${e.message}")
                    }
                }
                else -> ToolResult(false, "Unknown clipboard action: $action")
            }
        }
    }
}
