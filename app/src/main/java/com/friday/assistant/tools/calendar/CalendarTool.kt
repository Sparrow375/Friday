package com.friday.assistant.tools.calendar

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.util.Log
import com.friday.assistant.tools.Tool
import com.friday.assistant.tools.ToolResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class CalendarTool(private val context: Context) : Tool {

    companion object {
        private const val TAG = "CalendarTool"
    }

    override val name: String = "calendar_control"

    override val description: String = """
        Manages calendar events (adding and listing events) and sets device alarms/reminders.
    """.trimIndent()

    override val parameters: JsonObject = JsonParser.parseString("""
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["add_event", "list_events", "set_alarm", "set_timer"],
              "description": "The action to perform"
            },
            "title": {
              "type": "string",
              "description": "The title of the calendar event, alarm label, or timer label"
            },
            "description": {
              "type": "string",
              "description": "The description/notes for the calendar event"
            },
            "start_time": {
              "type": "string",
              "description": "ISO date-time string (e.g. '2026-06-13T14:30:00') for event start"
            },
            "duration": {
              "type": "integer",
              "description": "The duration of the calendar event in minutes, or timer duration in seconds"
            },
            "hour": {
              "type": "integer",
              "description": "Hour for alarm setting (0-23)"
            },
            "minute": {
              "type": "integer",
              "description": "Minute for alarm setting (0-59)"
            }
          },
          "required": ["action"]
        }
    """).asJsonObject

    override suspend fun execute(args: JsonObject): ToolResult {
        val action = args.get("action")?.asString ?: return ToolResult(false, "Missing required parameter: action")
        
        return try {
            when (action) {
                "add_event" -> {
                    val title = args.get("title")?.asString
                        ?: return ToolResult(false, "Missing parameter 'title' for action 'add_event'")
                    val startTimeStr = args.get("start_time")?.asString
                        ?: return ToolResult(false, "Missing parameter 'start_time' for action 'add_event'")
                    val duration = args.get("duration")?.asInt ?: 60
                    val description = args.get("description")?.asString ?: ""
                    addEvent(title, startTimeStr, duration, description)
                }
                "list_events" -> listEvents()
                "set_alarm" -> {
                    val hour = args.get("hour")?.asInt
                        ?: return ToolResult(false, "Missing parameter 'hour' for action 'set_alarm'")
                    val minute = args.get("minute")?.asInt ?: 0
                    val label = args.get("title")?.asString ?: "Friday Alarm"
                    setAlarm(hour, minute, label)
                }
                "set_timer" -> {
                    val duration = args.get("duration")?.asInt
                        ?: return ToolResult(false, "Missing parameter 'duration' (seconds) for action 'set_timer'")
                    val label = args.get("title")?.asString ?: "Friday Timer"
                    setTimer(duration, label)
                }
                else -> ToolResult(false, "Unknown calendar action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing calendar action: $action", e)
            ToolResult(false, "Calendar execution failed: ${e.message}")
        }
    }

    private fun addEvent(title: String, startTimeStr: String, durationMin: Int, desc: String): ToolResult {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val date = sdf.parse(startTimeStr) ?: return ToolResult(false, "Invalid start_time date format: $startTimeStr")
            
            val startMillis = date.time
            val endMillis = startMillis + (durationMin * 60 * 1000)

            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, desc)
                put(CalendarContract.Events.CALENDAR_ID, 1) // Default primary calendar
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }

            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) {
                ToolResult(true, "Successfully added calendar event '$title' on $startTimeStr")
            } else {
                ToolResult(false, "Failed to insert calendar event into calendar database")
            }
        } catch (e: SecurityException) {
            // Intent fallback if calendar write permissions not granted
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.Events.DESCRIPTION, desc)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ToolResult(true, "Opened calendar insert screen since direct calendar permission was not granted")
        } catch (e: Exception) {
            ToolResult(false, "Error creating event: ${e.message}")
        }
    }

    @SuppressLint("Range")
    private fun listEvents(): ToolResult {
        val contentResolver = context.contentResolver
        val uri = CalendarContract.Events.CONTENT_URI
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DESCRIPTION
        )

        val now = System.currentTimeMillis()
        val selection = "${CalendarContract.Events.DTSTART} >= ?"
        val selectionArgs = arrayOf(now.toString())
        
        var cursor: Cursor? = null
        val result = StringBuilder("Upcoming calendar events:\n")
        try {
            cursor = contentResolver.query(uri, projection, selection, selectionArgs, "${CalendarContract.Events.DTSTART} ASC LIMIT 5")
            if (cursor != null && cursor.moveToFirst()) {
                val sdf = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
                do {
                    val title = cursor.getString(cursor.getColumnIndex(CalendarContract.Events.TITLE))
                    val dtstart = cursor.getLong(cursor.getColumnIndex(CalendarContract.Events.DTSTART))
                    val desc = cursor.getString(cursor.getColumnIndex(CalendarContract.Events.DESCRIPTION)) ?: ""
                    val dateStr = sdf.format(Date(dtstart))
                    
                    result.append("- $title on $dateStr" + (if (desc.isNotEmpty()) " ($desc)" else "") + "\n")
                } while (cursor.moveToNext())
                return ToolResult(true, result.toString())
            } else {
                return ToolResult(true, "No upcoming calendar events found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error listing calendar events", e)
            return ToolResult(false, "Failed to query calendar: ${e.message}")
        } finally {
            cursor?.close()
        }
    }

    private fun setAlarm(hour: Int, minute: Int, label: String): ToolResult {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            val timeStr = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
            ToolResult(true, "Successfully set system alarm for $timeStr with label '$label'")
        } catch (e: Exception) {
            ToolResult(false, "Failed to set alarm: ${e.message}")
        }
    }

    private fun setTimer(durationSec: Int, label: String): ToolResult {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, durationSec)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ToolResult(true, "Successfully set timer for $durationSec seconds ($label)")
        } catch (e: Exception) {
            ToolResult(false, "Failed to set timer: ${e.message}")
        }
    }
}
