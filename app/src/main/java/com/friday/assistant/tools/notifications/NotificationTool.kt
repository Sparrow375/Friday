package com.friday.assistant.tools.notifications

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import com.friday.assistant.tools.Tool
import com.friday.assistant.tools.ToolResult
import com.friday.assistant.ui.NotificationService
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class NotificationTool(private val context: Context) : Tool {

    companion object {
        private const val TAG = "NotificationTool"
    }

    override val name: String = "notification_control"

    override val description: String = """
        Inspects active status bar notifications or replies directly to messaging notifications (like WhatsApp or SMS) using system RemoteInput actions.
    """.trimIndent()

    override val parameters: JsonObject = JsonParser.parseString("""
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["list", "reply"],
              "description": "The notification action to perform"
            },
            "notification_key": {
              "type": "string",
              "description": "The unique system key string of the notification to reply to (required for 'reply' action)"
            },
            "reply_text": {
              "type": "string",
              "description": "The message text to send as the reply (required for 'reply' action)"
            }
          },
          "required": ["action"]
        }
    """).asJsonObject

    override suspend fun execute(args: JsonObject): ToolResult {
        val action = args.get("action")?.asString ?: return ToolResult(false, "Missing required parameter: action")
        
        return when (action) {
            "list" -> listNotifications()
            "reply" -> {
                val key = args.get("notification_key")?.asString
                    ?: return ToolResult(false, "Missing parameter 'notification_key' for action 'reply'")
                val text = args.get("reply_text")?.asString
                    ?: return ToolResult(false, "Missing parameter 'reply_text' for action 'reply'")
                replyNotification(key, text)
            }
            else -> ToolResult(false, "Unknown notification action: $action")
        }
    }

    private fun listNotifications(): ToolResult {
        val sbns = NotificationService.getActiveNotificationsList()
        if (sbns.isEmpty()) {
            return ToolResult(true, "There are currently no active notifications in the status bar shade.")
        }

        val result = StringBuilder("Active notifications:\n")
        sbns.forEach { sbn ->
            val packageName = sbn.packageName
            val notification = sbn.notification
            val extras = notification.extras
            val title = extras.getString(Notification.EXTRA_TITLE) ?: "No Title"
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "No Text"
            
            // Check if it has direct reply capability
            val hasReply = hasDirectReply(sbn)
            val replySuffix = if (hasReply) " [Replyable]" else ""

            result.append("- [Key: ${sbn.key}] App: $packageName | Title: $title | Text: $text $replySuffix\n")
        }
        return ToolResult(true, result.toString())
    }

    private fun replyNotification(key: String, replyText: String): ToolResult {
        val sbn = NotificationService.getNotificationByKey(key)
            ?: return ToolResult(false, "Could not find active notification with key: $key")

        val notification = sbn.notification
        val actions = notification.actions ?: return ToolResult(false, "This notification does not have any reply actions.")

        for (action in actions) {
            val remoteInputs = action.remoteInputs ?: continue
            for (remoteInput in remoteInputs) {
                // Prepare reply bundle mapping resultKey to replyText
                val intent = Intent().apply {
                    val bundle = Bundle()
                    bundle.putCharSequence(remoteInput.resultKey, replyText)
                    RemoteInput.addResultsToIntent(arrayOf(remoteInput), this, bundle)
                }
                
                try {
                    // Send action intent back to the app
                    action.actionIntent.send(context, 0, intent)
                    Log.i(TAG, "Sent reply text successfully to key $key")
                    return ToolResult(true, "Successfully sent reply \"$replyText\" to notification key $key")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed sending action intent", e)
                    return ToolResult(false, "Failed sending reply intent: ${e.message}")
                }
            }
        }
        return ToolResult(false, "No direct reply remote inputs found on this notification.")
    }

    private fun hasDirectReply(sbn: StatusBarNotification): Boolean {
        val actions = sbn.notification.actions ?: return false
        for (action in actions) {
            if (action.remoteInputs != null) {
                return true
            }
        }
        return false
    }
}
