package com.friday.assistant.tools.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.util.Log
import com.friday.assistant.tools.Tool
import com.friday.assistant.tools.ToolResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLEncoder

class LocationTool(private val context: Context) : Tool {

    companion object {
        private const val TAG = "LocationTool"
    }

    override val name: String = "location_control"

    override val description: String = """
        Gets the current GPS location coordinates or initiates turn-by-turn navigation on Google Maps to a specified address or landmark.
    """.trimIndent()

    override val parameters: JsonObject = JsonParser.parseString("""
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["get_location", "navigate"],
              "description": "The location action to execute"
            },
            "destination": {
              "type": "string",
              "description": "The destination address or landmark to navigate to (required for 'navigate' action)"
            }
          },
          "required": ["action"]
        }
    """).asJsonObject

    override suspend fun execute(args: JsonObject): ToolResult {
        val action = args.get("action")?.asString ?: return ToolResult(false, "Missing required parameter: action")
        
        return when (action) {
            "get_location" -> getCurrentLocation()
            "navigate" -> {
                val dest = args.get("destination")?.asString
                    ?: return ToolResult(false, "Missing parameter 'destination' for action 'navigate'")
                startNavigation(dest)
            }
            else -> ToolResult(false, "Unknown location action: $action")
        }
    }

    @SuppressLint("MissingPermission")
    private fun getCurrentLocation(): ToolResult {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            val providers = locationManager.getProviders(true)
            var bestLocation: Location? = null
            
            for (provider in providers) {
                val loc = locationManager.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null || loc.accuracy < bestLocation.accuracy) {
                    bestLocation = loc
                }
            }

            if (bestLocation != null) {
                val result = "Latitude: ${bestLocation.latitude}, Longitude: ${bestLocation.longitude} (Accuracy: ${bestLocation.accuracy}m)"
                ToolResult(true, "Current coordinates: $result")
            } else {
                ToolResult(false, "Location services are active but no coordinates could be retrieved. Ensure GPS has a signal.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch GPS coordinates", e)
            ToolResult(false, "Failed to read GPS coordinates: ${e.message}")
        }
    }

    private fun startNavigation(destination: String): ToolResult {
        return try {
            val encodedDest = URLEncoder.encode(destination, "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("google.navigation:q=$encodedDest")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ToolResult(true, "Launching Google Maps navigation to '$destination'")
        } catch (e: Exception) {
            ToolResult(false, "Failed to initiate navigation: ${e.message}")
        }
    }
}
