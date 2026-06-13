package com.friday.assistant.tools.files

import android.content.Context
import android.os.Environment
import android.util.Log
import com.friday.assistant.tools.Tool
import com.friday.assistant.tools.ToolResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.text.DecimalFormat

class FileManagerTool(private val context: Context) : Tool {

    companion object {
        private const val TAG = "FileManagerTool"
    }

    override val name: String = "file_manager"

    override val description: String = """
        Lists files in directories on device storage or checks total and free disk storage space.
    """.trimIndent()

    override val parameters: JsonObject = JsonParser.parseString("""
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["list_files", "check_storage"],
              "description": "The file action to perform"
            },
            "directory_path": {
              "type": "string",
              "description": "The absolute folder path to list files for (only used for 'list_files' action. Leave blank to default to Friday external storage root)"
            }
          },
          "required": ["action"]
        }
    """).asJsonObject

    override suspend fun execute(args: JsonObject): ToolResult {
        val action = args.get("action")?.asString ?: return ToolResult(false, "Missing required parameter: action")
        
        return when (action) {
            "list_files" -> {
                val path = args.get("directory_path")?.asString ?: ""
                listFiles(path)
            }
            "check_storage" -> checkStorageSpace()
            else -> ToolResult(false, "Unknown file action: $action")
        }
    }

    private fun listFiles(path: String): ToolResult {
        val targetDir = if (path.isEmpty()) {
            context.getExternalFilesDir(null) ?: context.filesDir
        } else {
            File(path)
        }

        if (!targetDir.exists()) {
            return ToolResult(false, "Directory does not exist: ${targetDir.absolutePath}")
        }
        if (!targetDir.isDirectory) {
            return ToolResult(false, "Path is a file, not a directory: ${targetDir.absolutePath}")
        }

        return try {
            val files = targetDir.listFiles()
            if (files.isNullOrEmpty()) {
                return ToolResult(true, "Directory '${targetDir.name}' is empty.")
            }

            val sb = StringBuilder("Files in directory '${targetDir.absolutePath}':\n")
            files.sortedBy { it.name }.forEach { file ->
                val type = if (file.isDirectory) "[DIR]" else "[FILE]"
                val size = if (file.isFile) formatFileSize(file.length()) else ""
                sb.append("- $type ${file.name} $size\n")
            }
            ToolResult(true, sb.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error listing files in: ${targetDir.absolutePath}", e)
            ToolResult(false, "Failed to list files: ${e.message}")
        }
    }

    private fun checkStorageSpace(): ToolResult {
        return try {
            val internalFile = context.filesDir
            val internalFree = internalFile.freeSpace
            val internalTotal = internalFile.totalSpace

            val externalFile = context.getExternalFilesDir(null) ?: context.filesDir
            val externalFree = externalFile.freeSpace
            val externalTotal = externalFile.totalSpace

            val df = DecimalFormat("#.##")
            val internalFreeGb = df.format(internalFree / (1024f * 1024f * 1024f))
            val internalTotalGb = df.format(internalTotal / (1024f * 1024f * 1024f))
            val externalFreeGb = df.format(externalFree / (1024f * 1024f * 1024f))
            val externalTotalGb = df.format(externalTotal / (1024f * 1024f * 1024f))

            val result = """
                Device Storage Info:
                - Internal Storage: $internalFreeGb GB free of $internalTotalGb GB total.
                - External App Storage: $externalFreeGb GB free of $externalTotalGb GB total.
            """.trimIndent()
            ToolResult(true, result)
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading storage capacity", e)
            ToolResult(false, "Failed to check storage: ${e.message}")
        }
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
    }
}
