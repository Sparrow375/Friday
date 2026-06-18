package com.friday.assistant.tools.system

import android.content.Context
import android.os.Environment
import android.util.Log
import com.friday.assistant.automation.AutomationBridge
import com.friday.assistant.tools.Tool
import com.friday.assistant.tools.ToolResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenshotTool(private val context: Context) : Tool {

    companion object {
        private const val TAG = "ScreenshotTool"
    }

    override val name: String = "screenshot"
    override val description: String = "Captures a screenshot of the current screen and saves it to Pictures/Friday."

    override val parameters: JsonObject = JsonParser.parseString("""
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["capture"],
              "description": "Capture a screenshot"
            }
          },
          "required": ["action"]
        }
    """).asJsonObject

    override suspend fun execute(args: JsonObject): ToolResult {
        val action = args.get("action")?.asString ?: "capture"
        if (action != "capture") return ToolResult(false, "Unknown screenshot action: $action")

        // Tier 1: Accessibility global action (silent, no UI)
        if (AutomationBridge.isReady()) {
            val ok = AutomationBridge.takeScreenshot()
            if (ok) return ToolResult(true, "Screenshot captured.")
        }

        // Tier 2: Save to Pictures/Friday via shell screencap (works on some devices with shell permission)
        return captureViaShell()
    }

    private fun captureViaShell(): ToolResult {
        return try {
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val fridayDir = File(picturesDir, "Friday")
            if (!fridayDir.exists()) fridayDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outFile = File(fridayDir, "screenshot_$timestamp.png")

            val process = Runtime.getRuntime().exec(arrayOf("screencap", "-p", outFile.absolutePath))
            val exitCode = process.waitFor()
            if (exitCode == 0 && outFile.exists() && outFile.length() > 0) {
                ToolResult(true, "Screenshot saved to ${outFile.absolutePath}")
            } else {
                ToolResult(
                    false,
                    "Could not capture screenshot. Enable UI Automation in Friday settings for silent capture."
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shell screencap failed", e)
            ToolResult(
                false,
                "Screenshot failed. Enable UI Automation in Friday settings, then try again."
            )
        }
    }
}
