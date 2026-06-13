package com.friday.assistant.tools.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import com.friday.assistant.tools.Tool
import com.friday.assistant.tools.ToolResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

class AppLauncherTool(private val context: Context) : Tool {

    companion object {
        private const val TAG = "AppLauncherTool"
    }

    override val name: String = "app_launcher"

    override val description: String = """
        Launches any installed application on the device by performing fuzzy matching on the app name.
    """.trimIndent()

    override val parameters: JsonObject = JsonParser.parseString("""
        {
          "type": "object",
          "properties": {
            "app_name": {
              "type": "string",
              "description": "The name of the application to launch (e.g., 'Spotify', 'WhatsApp', 'Brave')"
            }
          },
          "required": ["app_name"]
        }
    """).asJsonObject

    override suspend fun execute(args: JsonObject): ToolResult {
        val appName = args.get("app_name")?.asString ?: return ToolResult(false, "Missing required parameter: app_name")
        return launchApp(appName)
    }

    private fun launchApp(queryName: String): ToolResult {
        val pm = context.packageManager
        val queryLower = queryName.trim().lowercase(Locale.ROOT)
        
        // Retrieve all installed applications
        val appsList = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        var bestMatchApp: ApplicationInfo? = null
        var bestMatchLabel = ""
        var bestScore = 0 // Fuzzy matching score

        for (app in appsList) {
            // Filter out system apps that are not launchable (optional, but checking launch intent is better)
            val launchIntent = pm.getLaunchIntentForPackage(app.packageName) ?: continue
            val label = app.loadLabel(pm).toString().lowercase(Locale.ROOT)
            
            val score = when {
                label == queryLower -> 100
                label.startsWith(queryLower) -> 80
                label.contains(queryLower) -> 50
                // Simple levenshtein or fuzzy distance could go here, but contains/startsWith works great for voice
                else -> 0
            }

            if (score > bestScore) {
                bestScore = score
                bestMatchApp = app
                bestMatchLabel = app.loadLabel(pm).toString()
            }
        }

        return if (bestMatchApp != null && bestScore > 0) {
            try {
                val launchIntent = pm.getLaunchIntentForPackage(bestMatchApp.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    ToolResult(true, "Launching $bestMatchLabel")
                } else {
                    ToolResult(false, "Could not resolve launch intent for $bestMatchLabel")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch app: ${bestMatchApp.packageName}", e)
                ToolResult(false, "Error launching $bestMatchLabel: ${e.message}")
            }
        } else {
            ToolResult(false, "Could not find any installed application matching '$queryName'")
        }
    }
}
