package com.friday.assistant.tools.whatsapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
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
        Looks up the recipient's phone number from the device contacts list and launches WhatsApp.
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
        val recipient = args.get("recipient")?.asString ?: return ToolResult(false, "Missing required parameter: recipient")
        val message = args.get("message")?.asString ?: return ToolResult(false, "Missing required parameter: message")
        
        Log.d(TAG, "Executing WhatsAppTool: recipient=$recipient, message=$message")
        
        // 1. Resolve contact number from name
        val rawNumber = getPhoneNumber(recipient)
        if (rawNumber == null) {
            return ToolResult(false, "Could not find a phone number for contact '$recipient' in your phonebook.")
        }
        
        // 2. Clean phone number format for WhatsApp api
        var cleanNumber = rawNumber.replace(Regex("\\D"), "")
        if (cleanNumber.startsWith("0")) {
            cleanNumber = "91" + cleanNumber.substring(1) // Default to India country code if local zero
        } else if (cleanNumber.length == 10) {
            cleanNumber = "91" + cleanNumber             // Prepend India country code if standard 10 digit
        }
        
        // 3. Launch WhatsApp via deep link intent
        try {
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber&text=${Uri.encode(message)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            
            // Track app launch stats in semantic memory
            memoryManager.incrementAppLaunchCount("com.whatsapp", "WhatsApp")
            
            return ToolResult(true, "Opened WhatsApp chat for $recipient ($cleanNumber) with pre-filled message.")
        } catch (e: Exception) {
            Log.e(TAG, "Error launching WhatsApp intent", e)
            return ToolResult(false, "WhatsApp is either not installed or failed to handle the message request.")
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
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (numberIndex != -1) {
                    return it.getString(numberIndex)
                }
            }
        }
        return null
    }
}
