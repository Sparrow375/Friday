package com.friday.assistant.tools.whatsapp

import android.content.Context
import android.content.Intent
import android.util.Log
import com.friday.assistant.automation.ScreenReader
import com.friday.assistant.automation.UIAutomator
import com.friday.assistant.intelligence.MemoryManager
import com.friday.assistant.tools.Tool
import com.friday.assistant.tools.ToolResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.delay

class WhatsAppTool(
    private val context: Context,
    private val memoryManager: MemoryManager
) : Tool {

    companion object {
        private const val TAG = "WhatsAppTool"
    }

    override val name: String = "whatsapp_send"

    override val description: String = """
        Automates sending a WhatsApp message to a specific contact. 
        Requires the recipient's name (contact name) and the message text.
        This runs offline by simulating screen touches using Android Accessibility.
    """.trimIndent()

    override val parameters: JsonObject = JsonParser.parseString("""
        {
          "type": "object",
          "properties": {
            "recipient": {
              "type": "string",
              "description": "The exact or partial contact name of the recipient (e.g., 'John Doe')"
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
        
        // 1. Launch WhatsApp
        val intent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
            ?: return ToolResult(false, "WhatsApp is not installed on this device.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        
        // Track app launch stats in semantic memory
        memoryManager.incrementAppLaunchCount("com.whatsapp", "WhatsApp")
        
        // Wait for app launch animation and chats screen rendering
        delay(2500)
        
        // 2. Scan screen to find search button/icon
        ScreenReader.readScreen()
        
        var searchId = ScreenReader.findCachedElementId { node ->
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val resId = node.viewIdResourceName?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            desc.contains("search") || resId.contains("search") || text.contains("search")
        }
        
        if (searchId == null) {
            // Fallback: try to find any clickable image/button
            Log.w(TAG, "Search button not found by keyword, looking for clickable ImageView/Button fallback")
            searchId = ScreenReader.findCachedElementId { node ->
                val className = node.className?.toString() ?: ""
                node.isClickable && (className.contains("ImageView") || className.contains("Button")) &&
                        (node.contentDescription?.toString() != null)
            }
        }
        
        if (searchId == null) {
            return ToolResult(false, "Failed to find Search icon/button in WhatsApp. Make sure WhatsApp is on the chats screen.")
        }
        
        // Tap search button
        if (!UIAutomator.tapElement(searchId)) {
            return ToolResult(false, "Failed to tap the WhatsApp Search button.")
        }
        
        delay(1500)
        
        // 3. Scan screen to find search input field and type recipient
        ScreenReader.readScreen()
        val searchInputId = ScreenReader.findCachedElementId { node ->
            node.isEditable && (node.className?.toString()?.contains("EditText") == true)
        }
        
        if (searchInputId == null) {
            return ToolResult(false, "Failed to find Search text field after tapping Search button.")
        }
        
        if (!UIAutomator.typeText(searchInputId, recipient)) {
            return ToolResult(false, "Failed to enter recipient name in search field.")
        }
        
        // Wait for search results
        delay(2000)
        
        // 4. Scan screen to find the matching contact
        ScreenReader.readScreen()
        
        // We want to find a contact name in the search results list.
        // It should match the recipient name fuzzy or exactly.
        // Also it should NOT be the EditText itself (which contains the recipient's name as input text).
        val contactId = ScreenReader.findCachedElementId { node ->
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val isEditable = node.isEditable
            val className = node.className?.toString() ?: ""
            
            !isEditable && !className.contains("EditText") && 
            (text.lowercase().contains(recipient.lowercase()) || desc.lowercase().contains(recipient.lowercase()))
        }
        
        if (contactId == null) {
            return ToolResult(false, "Could not find contact '$recipient' in the WhatsApp search results.")
        }
        
        // Tap the contact to open chat
        if (!UIAutomator.tapElement(contactId)) {
            return ToolResult(false, "Failed to tap contact '$recipient' to open chat.")
        }
        
        delay(2000)
        
        // 5. Scan screen to find message entry text box
        ScreenReader.readScreen()
        
        val messageInputId = ScreenReader.findCachedElementId { node ->
            node.isEditable && (node.className?.toString()?.contains("EditText") == true)
        }
        
        if (messageInputId == null) {
            return ToolResult(false, "Failed to find message input box in WhatsApp chat.")
        }
        
        if (!UIAutomator.typeText(messageInputId, message)) {
            return ToolResult(false, "Failed to enter message text.")
        }
        
        delay(1000)
        
        // 6. Scan screen to find and tap send button
        ScreenReader.readScreen()
        
        val sendButtonId = ScreenReader.findCachedElementId { node ->
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val resId = node.viewIdResourceName?.toString()?.lowercase() ?: ""
            desc.contains("send") || resId.contains("send")
        }
        
        if (sendButtonId == null) {
            return ToolResult(false, "Failed to locate WhatsApp Send button after typing message.")
        }
        
        if (!UIAutomator.tapElement(sendButtonId)) {
            return ToolResult(false, "Failed to tap the WhatsApp Send button.")
        }
        
        delay(1000)
        
        return ToolResult(true, "Successfully sent WhatsApp message to $recipient: \"$message\"")
    }
}
