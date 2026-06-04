package com.friday.assistant.classifier

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.google.gson.Gson
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class LocalLlmRunner private constructor(private val context: Context) {

    private var llmInference: LlmInference? = null
    private var loadedModelPath: String? = null

    init {
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            findAndLoadModel()
        }
    }

    /**
     * Scans typical storage paths for a local LLM model file (.bin or .task) and initializes MediaPipe.
     */
    private fun findAndLoadModel() {
        val prefs = context.getSharedPreferences("friday_prefs", Context.MODE_PRIVATE)
        val selectedPath = prefs.getString("selected_llm_path", null)
        var existingModel: File? = null
        
        if (selectedPath != null) {
            val file = File(selectedPath)
            if (file.exists() && file.isFile) {
                existingModel = file
            }
        }
        
        if (existingModel == null) {
            val possiblePaths = listOf(
                File(context.filesDir, "gemma-2b-it.bin"),
                File(context.filesDir, "gemma.bin"),
                File(context.filesDir, "model.bin"),
                File(context.getExternalFilesDir(null), "gemma-2b-it.bin"),
                File(context.getExternalFilesDir(null), "gemma.bin"),
                File(context.getExternalFilesDir(null), "llama-3.2-1b.bin"),
                File(context.getExternalFilesDir(null), "llama-3.2-3b.bin"),
                File("/sdcard/Android/data/com.friday.assistant/files/gemma.bin")
            )
            existingModel = possiblePaths.firstOrNull { it.exists() && it.isFile }
        }
        
        if (existingModel == null) {
            Log.w(TAG, "No local LLM model file found.")
            return
        }

        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(existingModel.absolutePath)
                .setMaxTokens(256)
                .setTemperature(0.7f)
                .setRandomSeed(42)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            loadedModelPath = existingModel.absolutePath
            Log.i(TAG, "Successfully loaded Local LLM from: ${existingModel.absolutePath}")
        } catch (t: Throwable) {
            Log.e(TAG, "Error initializing MediaPipe LLM Inference", t)
        }
    }

    fun isModelLoaded(): Boolean {
        val prefs = context.getSharedPreferences("friday_prefs", Context.MODE_PRIVATE)
        val serverIp = prefs.getString("pc_server_ip", null)
        return llmInference != null || !serverIp.isNullOrEmpty()
    }

    fun getLoadedModelName(): String {
        return loadedModelPath?.let { File(it).name } ?: "No model loaded"
    }

    /**
     * Triggers a model reload (useful if the user copies a model file later).
     */
    fun reloadModel() {
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                llmInference?.close()
                llmInference = null
            } catch (e: Exception) {
                Log.e(TAG, "Error closing local LLM", e)
            }
            findAndLoadModel()
        }
    }

    /**
     * Generates a text response offline.
     * Takes the user command, system instructions, and recent conversation history.
     */
    suspend fun generateResponse(
        prompt: String,
        history: List<Pair<String, String>> = emptyList()
    ): String = withContext(Dispatchers.Default) {
        val prefs = context.getSharedPreferences("friday_prefs", Context.MODE_PRIVATE)
        val serverIp = prefs.getString("pc_server_ip", null)
        
        if (!serverIp.isNullOrEmpty()) {
            try {
                return@withContext queryPcServer(serverIp, prompt, history)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to query PC GGUF Server at $serverIp: ${e.localizedMessage}")
            }
        }

        val inference = llmInference 
            ?: return@withContext "Offline LLM model not loaded. Basic offline commands still work! Ask me to turn on torch, change volume, or launch apps."

        try {
            // Build the conversational prompt with history
            val fullPromptBuilder = StringBuilder()
            
            // System instructions
            fullPromptBuilder.append("<start_of_turn>system\n")
            fullPromptBuilder.append("You are Friday, Avaneesh's personal voice assistant running offline on his Samsung S24. ")
            fullPromptBuilder.append("Keep your replies very short (1-2 sentences), friendly, conversational, and direct since they will be spoken aloud. ")
            fullPromptBuilder.append("DO NOT use any emojis, icons, or special visual symbols in your response under any circumstances.\n")
            fullPromptBuilder.append("<end_of_turn>\n")

            // Append context history
            for (chat in history.reversed()) {
                val speaker = if (chat.first == "USER") "user" else "model"
                fullPromptBuilder.append("<start_of_turn>$speaker\n")
                fullPromptBuilder.append("${chat.second}\n")
                fullPromptBuilder.append("<end_of_turn>\n")
            }

            // Current prompt
            fullPromptBuilder.append("<start_of_turn>user\n")
            fullPromptBuilder.append("$prompt\n")
            fullPromptBuilder.append("<end_of_turn>\n")
            fullPromptBuilder.append("<start_of_turn>model\n")

            val finalPrompt = fullPromptBuilder.toString()
            Log.d(TAG, "Feeding prompt to local LLM: $finalPrompt")
            
            val response = inference.generateResponse(finalPrompt)
            
            // Clean up model formatting tags if present in the response
            var cleanResponse = response.trim()
            if (cleanResponse.contains("<end_of_turn>")) {
                cleanResponse = cleanResponse.substringBefore("<end_of_turn>").trim()
            }
            
            cleanResponse
        } catch (t: Throwable) {
            Log.e(TAG, "Error generating response from local LLM", t)
            "Sorry Avaneesh, I encountered a processing error in my neural core: ${t.localizedMessage}"
        }
    }

    private fun queryPcServer(ip: String, prompt: String, history: List<Pair<String, String>>): String {
        val cleanIp = if (ip.startsWith("http://") || ip.startsWith("https://")) ip else "http://$ip"
        val url = URL("$cleanIp/generate")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.connectTimeout = 4000
        connection.readTimeout = 8000
        
        val historyList = history.map { mapOf("speaker" to it.first, "message" to it.second) }
        val requestBody = Gson().toJson(mapOf(
            "prompt" to prompt,
            "history" to historyList
        ))
        
        connection.outputStream.use { os ->
            os.write(requestBody.toByteArray(Charsets.UTF_8))
        }
        
        val responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val map = Gson().fromJson(responseText, Map::class.java)
            return (map["response"] as? String) ?: "Empty response from server"
        } else {
            throw Exception("Server returned HTTP $responseCode")
        }
    }

    fun close() {
        llmInference?.close()
        llmInference = null
    }

    companion object {
        private const val TAG = "LocalLlmRunner"

        @Volatile
        private var INSTANCE: LocalLlmRunner? = null

        fun getInstance(context: Context): LocalLlmRunner? {
            return try {
                INSTANCE ?: synchronized(this) {
                    val instance = LocalLlmRunner(context.applicationContext)
                    INSTANCE = instance
                    instance
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize LocalLlmRunner (JNI linkage / model error)", t)
                null
            }
        }
    }
}
