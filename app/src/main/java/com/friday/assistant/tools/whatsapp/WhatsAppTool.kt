package com.friday.assistant.tools.whatsapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import com.friday.assistant.automation.AutomationBridge
import com.friday.assistant.intelligence.MemoryManager
import com.friday.assistant.tools.Tool
import com.friday.assistant.tools.ToolResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class WhatsAppTool(
    private val context: Context,
    private val memoryManager: MemoryManager
) : Tool {

    companion object {
        private const val TAG = "WhatsAppTool"
    }

    override val name: String = "whatsapp_send"

    override val description: String = """
        Sends a WhatsApp message to a specific contact.
        Looks up the recipient's phone number from the device contacts list, opens WhatsApp,
        and autonomously taps the send button if UI Automation is enabled.
    """.trimIndent()

    override val parameters: JsonObject = JsonParser.parseString("""
        {
          "type": "object",
          "properties": {
            "recipient": {
              "type": "string",
              "description": "The contact name of the recipient (e.g., 'John Doe')"
            },
            "message": {
              "type": "string",
              "description": "The text message content to send"
            }
          },
          "required": ["recipient", "message"]
        }
    """).asJsonObject

    override suspend fun execute(args: JsonObject): ToolResult {
        val recipient = args.get("recipient")?.asString
            ?: return ToolResult(false, "Missing required parameter: recipient")
        val message = args.get("message")?.asString
            ?: return ToolResult(false, "Missing required parameter: message")

        Log.d(TAG, "WhatsAppTool: recipient=$recipient")

        // 1. Resolve contact phone number
        val rawNumber = getPhoneNumber(recipient)
            ?: return ToolResult(
                false,
                "Could not find a phone number for '$recipient' in your contacts."
            )

        // 2. Normalise to E.164-style (prepend India +91 if no country code)
        var cleanNumber = rawNumber.replace(Regex("\\D"), "")
        if (cleanNumber.startsWith("0")) {
            cleanNumber = "91" + cleanNumber.substring(1)
        } else if (cleanNumber.length == 10) {
            cleanNumber = "91" + cleanNumber
        }

        // 3. Launch WhatsApp with pre-filled message via deep link
        return try {
            val uri = Uri.parse(
                "https://api.whatsapp.com/send?phone=$cleanNumber&text=${Uri.encode(message)}"
            )
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            memoryManager.incrementAppLaunchCount("com.whatsapp", "WhatsApp")

            // 4. If Accessibility is available, wait for WhatsApp to load and tap Send
            if (AutomationBridge.isReady()) {
                Log.d(TAG, "Accessibility ready — waiting for WhatsApp send button...")
                val sent = AutomationBridge.performWhatsAppSend()
                if (sent) {
                    return ToolResult(true, "Message sent to $recipient on WhatsApp.")
                }
                Log.w(TAG, "Accessibility send failed — message pre-filled, user must tap Send.")
            }

            // 5. Graceful fallback: message is pre-filled, user taps Send manually
            ToolResult(
                true,
                "Opened WhatsApp for $recipient with your message pre-filled. Tap Send to deliver."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error launching WhatsApp intent", e)
            ToolResult(false, "WhatsApp is not installed or could not handle the request.")
        }
    }

    private fun getPhoneNumber(contactName: String): String? {
        val cr = context.contentResolver
        val cursor = cr.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$contactName%"),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (idx != -1) return it.getString(idx)
            }
        }
        return null
    }
}
