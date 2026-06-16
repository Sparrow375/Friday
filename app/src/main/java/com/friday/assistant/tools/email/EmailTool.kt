package com.friday.assistant.tools.email

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.friday.assistant.intelligence.MemoryManager
import com.friday.assistant.tools.Tool
import com.friday.assistant.tools.ToolResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class EmailTool(
    private val context: Context,
    private val memoryManager: MemoryManager
) : Tool {

    companion object {
        private const val TAG = "EmailTool"
    }

    override val name: String = "gmail_send"

    override val description: String = """
        Composes and sends an email via the default device email client.
        Requires the recipient's email address ('to'), a 'subject', and the email 'body'.
    """.trimIndent()

    override val parameters: JsonObject = JsonParser.parseString("""
        {
          "type": "object",
          "properties": {
            "to": {
              "type": "string",
              "description": "The recipient's email address (e.g., 'user@example.com')"
            },
            "subject": {
              "type": "string",
              "description": "The subject of the email"
            },
            "body": {
              "type": "string",
              "description": "The body content of the email"
            }
          },
          "required": ["to", "subject", "body"]
        }
    """).asJsonObject

    override suspend fun execute(args: JsonObject): ToolResult {
        val to = args.get("to")?.asString ?: return ToolResult(false, "Missing required parameter: to")
        val subject = args.get("subject")?.asString ?: return ToolResult(false, "Missing required parameter: subject")
        val body = args.get("body")?.asString ?: return ToolResult(false, "Missing required parameter: body")

        Log.d(TAG, "Executing EmailTool: to=$to, subject=$subject, body=$body")

        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)

            // Track app launch stats in semantic memory
            memoryManager.incrementAppLaunchCount("com.google.android.gm", "Gmail")

            return ToolResult(true, "Successfully opened email composer for $to with subject \"$subject\"")
        } catch (e: Exception) {
            Log.e(TAG, "Error launching email intent", e)
            return ToolResult(false, "Failed to launch email application.")
        }
    }
}
