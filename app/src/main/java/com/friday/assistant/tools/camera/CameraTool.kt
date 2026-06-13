package com.friday.assistant.tools.camera

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.util.Log
import com.friday.assistant.tools.Tool
import com.friday.assistant.tools.ToolResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class CameraTool(private val context: Context) : Tool {

    companion object {
        private const val TAG = "CameraTool"
    }

    override val name: String = "camera_control"

    override val description: String = """
        Controls camera: opening the camera application to take photos or triggering the photo capture screen.
    """.trimIndent()

    override val parameters: JsonObject = JsonParser.parseString("""
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["open_camera", "capture_photo"],
              "description": "The camera action: 'open_camera' launches the camera viewfinder, 'capture_photo' starts standard snap capture"
            }
          },
          "required": ["action"]
        }
    """).asJsonObject

    override suspend fun execute(args: JsonObject): ToolResult {
        val action = args.get("action")?.asString ?: return ToolResult(false, "Missing required parameter: action")
        
        return when (action) {
            "open_camera" -> openCameraApp()
            "capture_photo" -> triggerPhotoCapture()
            else -> ToolResult(false, "Unknown camera action: $action")
        }
    }

    private fun openCameraApp(): ToolResult {
        return try {
            val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ToolResult(true, "Successfully opened the camera application")
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera app intent", e)
            ToolResult(false, "Failed to launch camera application: ${e.message}")
        }
    }

    private fun triggerPhotoCapture(): ToolResult {
        return try {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ToolResult(true, "Triggered photo capture dialog screen")
        } catch (e: Exception) {
            Log.e(TAG, "Error launching photo capture screen", e)
            ToolResult(false, "Failed to initiate photo capture screen: ${e.message}")
        }
    }
}
