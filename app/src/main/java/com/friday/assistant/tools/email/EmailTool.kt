package com.friday.assistant.tools.email

import android.content.Context
import android.content.Intent
import android.util.Log
import com.friday.assistant.automation.ScreenReader
import com.friday.assistant.automation.UIAutomator
import com.friday.assistant.tools.Tool
import com.friday.assistant.tools.ToolResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.delay

class EmailTool(private val context: Context) : Tool {

    companion object {
        private const val TAG = "EmailTool"
    }

    override val name: String = "gmail_send"

    override val description: String = """
        Automates composing and sending an email via the Gmail app.
        Requires the recipient's email address ('to'), a 'subject', and the email 'body'.
        This runs offline by simulating screen touches using Android Accessibility.
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

        // 1. Launch Gmail
        val intent = context.packageManager.getLaunchIntentForPackage("com.google.android.gm")
            ?: return ToolResult(false, "Gmail is not installed on this device.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)

        // Wait for app launch animation
        delay(2500)

        // 2. Scan screen to find Compose button
        ScreenReader.readScreen()

        val composeId = ScreenReader.findCachedElementId { node ->
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val resId = node.viewIdResourceName?.toString()?.lowercase() ?: ""
            text.contains("compose") || desc.contains("compose") || resId.contains("compose")
        }

        if (composeId == null) {
            return ToolResult(false, "Failed to find 'Compose' button in Gmail. Make sure Gmail is on the inbox screen.")
        }

        // Tap Compose button
        if (!UIAutomator.tapElement(composeId)) {
            return ToolResult(false, "Failed to tap Gmail 'Compose' button.")
        }

        // Wait for compose screen to load
        delay(2500)

        // 3. Scan screen to locate recipient "To", "Subject", and "Body" fields
        ScreenReader.readScreen()

        // To Field: editable, has resId containing "to", or description containing "to", or it's a MultiAutoCompleteTextView
        val toFieldId = ScreenReader.findCachedElementId { node ->
            val resId = node.viewIdResourceName?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            val className = node.className?.toString() ?: ""
            node.isEditable && (resId.endsWith("to") || desc == "to" || text.startsWith("to") || className.contains("MultiAutoCompleteTextView"))
        }

        if (toFieldId == null) {
            return ToolResult(false, "Failed to find 'To' recipient input field in Gmail Compose screen.")
        }

        // Enter Recipient Email (appended with a space to trigger chip resolution if necessary)
        if (!UIAutomator.typeText(toFieldId, "$to ")) {
            return ToolResult(false, "Failed to enter recipient email address.")
        }

        delay(1500)

        // Rescan to find Subject and Body fields (sometimes typing recipient changes focus/view structure)
        ScreenReader.readScreen()

        // Subject Field: editable, has resId containing "subject", or text/hint containing "subject"
        val subjectFieldId = ScreenReader.findCachedElementId { node ->
            val resId = node.viewIdResourceName?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            node.isEditable && (resId.contains("subject") || text.contains("subject") || desc.contains("subject"))
        }

        if (subjectFieldId == null) {
            return ToolResult(false, "Failed to find 'Subject' input field in Gmail Compose screen.")
        }

        // Enter Subject
        if (!UIAutomator.typeText(subjectFieldId, subject)) {
            return ToolResult(false, "Failed to enter subject.")
        }

        delay(1000)

        // Rescan to find Body field
        ScreenReader.readScreen()

        // Body Field: editable, has resId containing "body" or "compose_area_holder" or text/hint containing "compose email" or "email"
        val bodyFieldId = ScreenReader.findCachedElementId { node ->
            val resId = node.viewIdResourceName?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            node.isEditable && (resId.contains("body") || resId.contains("compose_area") || text.contains("compose email") || desc.contains("compose email") || text.contains("email"))
        }

        if (bodyFieldId == null) {
            return ToolResult(false, "Failed to find 'Compose email' body input field in Gmail Compose screen.")
        }

        // Enter Body
        if (!UIAutomator.typeText(bodyFieldId, body)) {
            return ToolResult(false, "Failed to enter email body.")
        }

        delay(1000)

        // 4. Scan screen to find Send button
        ScreenReader.readScreen()

        val sendButtonId = ScreenReader.findCachedElementId { node ->
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val resId = node.viewIdResourceName?.toString()?.lowercase() ?: ""
            desc.contains("send") || resId.contains("send")
        }

        if (sendButtonId == null) {
            return ToolResult(false, "Failed to locate Gmail Send button after typing subject and body.")
        }

        // Tap Send button
        if (!UIAutomator.tapElement(sendButtonId)) {
            return ToolResult(false, "Failed to tap the Gmail Send button.")
        }

        delay(1000)

        return ToolResult(true, "Successfully sent email to $to with subject \"$subject\"")
    }
}
