package com.friday.assistant.tools.system

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import com.friday.assistant.automation.AutomationBridge
import com.friday.assistant.tools.Tool
import com.friday.assistant.tools.ToolResult
import com.friday.assistant.ui.FridayService
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenshotTool(private val context: Context) : Tool {

    companion object {
        private const val TAG = "ScreenshotTool"
        /** Delay (ms) between overlay hide and capture dispatch so the UI has time to vanish. */
        private const val OVERLAY_HIDE_DELAY_MS = 250L
        /** Delay (ms) after capture dispatch before restoring the overlay. */
        private const val OVERLAY_RESTORE_DELAY_MS = 800L
    }

    override val name: String = "screenshot"
    override val description: String =
        "Captures a screenshot of the current screen and saves it to Pictures/Friday."

    override val parameters: JsonObject = JsonParser.parseString(
        """
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
        """
    ).asJsonObject

    override suspend fun execute(args: JsonObject): ToolResult {
        val action = args.get("action")?.asString ?: "capture"
        if (action != "capture") return ToolResult(false, "Unknown screenshot action: $action")

        // Tier 1: Accessibility GLOBAL_ACTION_TAKE_SCREENSHOT
        // The action is async — the system saves the screenshot independently after dispatch.
        // We MUST hide the overlay first so it doesn't appear in the capture.
        if (AutomationBridge.isReady() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val service = FridayService.instance

            // Hide overlay, wait briefly, then dispatch the screenshot action
            service?.hideOverlayForScreenshot()
            Thread.sleep(OVERLAY_HIDE_DELAY_MS)

            val dispatched = AutomationBridge.takeScreenshot()

            // Restore overlay after a short wait regardless of dispatch result
            Thread.sleep(OVERLAY_RESTORE_DELAY_MS)
            service?.restoreOverlayAfterScreenshot()

            if (dispatched) {
                return ToolResult(true, "Screenshot taken.")
            }
            // Fall through to shell tier if dispatch failed
        }

        // Tier 2: shell screencap fallback (works on some rooted / debug devices)
        return captureViaShell()
    }

    private fun captureViaShell(): ToolResult {
        return try {
            val picturesDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val fridayDir = File(picturesDir, "Friday")
            if (!fridayDir.exists()) fridayDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outFile = File(fridayDir, "screenshot_$timestamp.png")

            val process =
                Runtime.getRuntime().exec(arrayOf("screencap", "-p", outFile.absolutePath))
            val exitCode = process.waitFor()
            if (exitCode == 0 && outFile.exists() && outFile.length() > 0) {
                ToolResult(true, "Screenshot saved.")
            } else {
                ToolResult(
                    false,
                    "Could not capture screenshot. Enable UI Automation in Friday settings."
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shell screencap failed", e)
            ToolResult(false, "Screenshot failed. Enable UI Automation in Friday settings.")
        }
    }
}
