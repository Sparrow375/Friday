package com.friday.assistant.tools.phone

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.util.Log
import com.friday.assistant.tools.Tool
import com.friday.assistant.tools.ToolResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhoneTool(private val context: Context) : Tool {

    companion object {
        private const val TAG = "PhoneTool"
    }

    override val name: String = "phone_control"

    override val description: String = """
        Provides telephony features: making phone calls to contacts, sending SMS messages, 
        reading recent incoming text messages, and listing recent call logs.
    """.trimIndent()

    override val parameters: JsonObject = JsonParser.parseString("""
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["call", "send_sms", "read_sms", "read_call_log"],
              "description": "The telephony action to perform"
            },
            "contact_name": {
              "type": "string",
              "description": "Name of the contact to call"
            },
            "recipient": {
              "type": "string",
              "description": "Phone number or contact name to send the SMS to"
            },
            "message": {
              "type": "string",
              "description": "The text message content to send"
            }
          },
          "required": ["action"]
        }
    """).asJsonObject

    override suspend fun execute(args: JsonObject): ToolResult {
        val action = args.get("action")?.asString ?: return ToolResult(false, "Missing required parameter: action")
        
        return try {
            when (action) {
                "call" -> {
                    val name = args.get("contact_name")?.asString
                        ?: return ToolResult(false, "Missing parameter 'contact_name' for action 'call'")
                    makeCall(name)
                }
                "send_sms" -> {
                    val recipient = args.get("recipient")?.asString
                        ?: return ToolResult(false, "Missing parameter 'recipient' for action 'send_sms'")
                    val message = args.get("message")?.asString
                        ?: return ToolResult(false, "Missing parameter 'message' for action 'send_sms'")
                    sendSms(recipient, message)
                }
                "read_sms" -> readSms()
                "read_call_log" -> readCallLog()
                else -> ToolResult(false, "Unknown phone action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing phone action: $action", e)
            ToolResult(false, "Phone control execution failed: ${e.message}")
        }
    }

    @SuppressLint("Range")
    private fun findContactNumber(name: String): String? {
        val contentResolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )
        
        // Search contacts with simple fuzzy matching
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$name%")
        
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                // Return first matched phone number
                return cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying contacts", e)
        } finally {
            cursor?.close()
        }
        return null
    }

    private fun makeCall(contactName: String): ToolResult {
        val phoneNumber = findContactNumber(contactName)
            ?: return ToolResult(false, "Could not find a contact named '$contactName'")

        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:${Uri.encode(phoneNumber)}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        
        return try {
            context.startActivity(intent)
            ToolResult(true, "Placing call to $contactName ($phoneNumber)")
        } catch (e: SecurityException) {
            // Fallback to DIAL if CALL_PHONE permission not granted
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:${Uri.encode(phoneNumber)}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(dialIntent)
            ToolResult(true, "Opened dialer for $contactName ($phoneNumber) since direct calling permission is missing")
        } catch (e: Exception) {
            ToolResult(false, "Failed to initiate call: ${e.message}")
        }
    }

    private fun sendSms(recipient: String, message: String): ToolResult {
        // Resolve recipient: check if it's a contact name or a direct phone number
        val number = if (recipient.all { it.isDigit() || it == '+' || it == '-' || it == ' ' }) {
            recipient
        } else {
            findContactNumber(recipient)
                ?: return ToolResult(false, "Could not find contact '$recipient' to send SMS to")
        }

        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            
            smsManager.sendTextMessage(number, null, message, null, null)
            ToolResult(true, "Successfully sent SMS to $recipient ($number)")
        } catch (e: Exception) {
            ToolResult(false, "Failed to send SMS: ${e.message}")
        }
    }

    @SuppressLint("Range")
    private fun readSms(): ToolResult {
        val contentResolver = context.contentResolver
        val uri = Uri.parse("content://sms/inbox")
        val projection = arrayOf("address", "body", "date")
        
        var cursor: Cursor? = null
        val result = StringBuilder("Recent incoming messages:\n")
        try {
            cursor = contentResolver.query(uri, projection, null, null, "date DESC LIMIT 5")
            if (cursor != null && cursor.moveToFirst()) {
                val sdf = SimpleDateFormat("HH:mm, MMM d", Locale.getDefault())
                var count = 0
                do {
                    val address = cursor.getString(cursor.getColumnIndex("address"))
                    val body = cursor.getString(cursor.getColumnIndex("body"))
                    val dateLong = cursor.getLong(cursor.getColumnIndex("date"))
                    val dateStr = sdf.format(Date(dateLong))
                    
                    result.append("- From $address at $dateStr: \"$body\"\n")
                    count++
                } while (cursor.moveToNext())
                return ToolResult(true, result.toString())
            } else {
                return ToolResult(true, "No incoming text messages found in inbox")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading SMS inbox", e)
            return ToolResult(false, "Failed to query SMS inbox: ${e.message}")
        } finally {
            cursor?.close()
        }
    }

    @SuppressLint("Range")
    private fun readCallLog(): ToolResult {
        val contentResolver = context.contentResolver
        val uri = CallLog.Calls.CONTENT_URI
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE
        )
        
        var cursor: Cursor? = null
        val result = StringBuilder("Recent call logs:\n")
        try {
            cursor = contentResolver.query(uri, projection, null, null, "${CallLog.Calls.DATE} DESC LIMIT 5")
            if (cursor != null && cursor.moveToFirst()) {
                val sdf = SimpleDateFormat("HH:mm, MMM d", Locale.getDefault())
                do {
                    val number = cursor.getString(cursor.getColumnIndex(CallLog.Calls.NUMBER))
                    val name = cursor.getString(cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)) ?: "Unknown"
                    val type = cursor.getInt(cursor.getColumnIndex(CallLog.Calls.TYPE))
                    val dateLong = cursor.getLong(cursor.getColumnIndex(CallLog.Calls.DATE))
                    val dateStr = sdf.format(Date(dateLong))

                    val typeStr = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> "Incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        CallLog.Calls.MISSED_TYPE -> "Missed"
                        CallLog.Calls.REJECTED_TYPE -> "Rejected"
                        else -> "Call"
                    }
                    
                    result.append("- $typeStr call with $name ($number) at $dateStr\n")
                } while (cursor.moveToNext())
                return ToolResult(true, result.toString())
            } else {
                return ToolResult(true, "No calls found in call log history")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading call logs", e)
            return ToolResult(false, "Failed to query call logs: ${e.message}")
        } finally {
            cursor?.close()
        }
    }
}
