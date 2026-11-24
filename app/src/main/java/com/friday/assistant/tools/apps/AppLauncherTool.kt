package com.friday.assistant.tools.apps

import android.content.Context
import android.content.Intent
import android.util.Log
import com.friday.assistant.core.InstalledAppsCache
import com.friday.assistant.intelligence.MemoryManager
import com.friday.assistant.tools.Tool
import com.friday.assistant.tools.ToolResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class AppLauncherTool(
    private val context: Context,
    private val memoryManager: MemoryManager
) : Tool {

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

    private suspend fun launchApp(queryName: String): ToolResult {
        val pm = context.packageManager
        val bestMatch = InstalledAppsCache.findBestLaunchableMatch(context, queryName)
            ?: InstalledAppsCache.let {
                // Force refresh once in case a new app was recently installed
                it.getApps(context, forceRefresh = true)
                it.findBestLaunchableMatch(context, queryName)
            }

        return if (bestMatch != null) {
            try {
                val launchIntent = pm.getLaunchIntentForPackage(bestMatch.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    memoryManager.incrementAppLaunchCount(bestMatch.packageName, bestMatch.label)
                    ToolResult(true, "Launching ${bestMatch.label}")
                } else {
                    ToolResult(false, "Could not resolve launch intent for ${bestMatch.label}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch app: ${bestMatch.packageName}", e)
                ToolResult(false, "Error launching ${bestMatch.label}: ${e.message}")
            }
        } else {
            ToolResult(false, "Could not find any installed application matching '$queryName'")
        }
    }
}
