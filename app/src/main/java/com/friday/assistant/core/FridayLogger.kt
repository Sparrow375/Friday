package com.friday.assistant.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FridayLogger {
    private const val TAG = "FridayLogger"
    private const val LOG_FILE_NAME = "friday_debug_logs.txt"
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun init(context: Context) {
        synchronized(this) {
            if (logFile == null) {
                val dir = context.getExternalFilesDir(null) ?: context.filesDir
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                logFile = File(dir, LOG_FILE_NAME)
                i(TAG, "FridayLogger initialized. Log file path: ${logFile?.absolutePath}")
            }
        }
    }

    private fun logToFile(level: String, tag: String, message: String, tr: Throwable? = null) {
        val file = logFile ?: return
        try {
            val timestamp = dateFormat.format(Date())
            val writer = FileWriter(file, true)
            val printWriter = PrintWriter(writer)
            
            val logLine = "$timestamp $level/$tag: $message"
            printWriter.println(logLine)
            
            tr?.let {
                it.printStackTrace(printWriter)
            }
            
            printWriter.flush()
            printWriter.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log to file", e)
        }
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        logToFile("D", tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        logToFile("I", tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        logToFile("W", tag, message)
    }

    fun e(tag: String, message: String, tr: Throwable? = null) {
        Log.e(tag, message, tr)
        logToFile("E", tag, message, tr)
    }

    fun getLogFile(): File? = logFile

    fun clearLogs() {
        val file = logFile ?: return
        try {
            if (file.exists()) {
                file.delete()
            }
            file.createNewFile()
            i(TAG, "Logs cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear logs", e)
        }
    }
}
