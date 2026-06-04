package com.friday.assistant.classifier

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LocalLlmRunner private constructor(private val context: Context) {

    private var llmInference: LlmInference? = null
    private var loadedModelPath: String? = null

    init {
        findAndLoadModel()
    }

    /**
     * Scans typical storage paths for a local LLM model file (.bin or .task) and initializes MediaPipe.
     */
    private fun findAndLoadModel() {
        val possiblePaths = listOf(
            File(context.filesDir, "gemma-2b-it.bin"),
            File(context.filesDir, "model.bin"),
            File(context.getExternalFilesDir(null), "gemma-2b-it.bin"),
            File(context.getExternalFilesDir(null), "gemma.bin"),
            File(context.getExternalFilesDir(null), "llama-3.2-1b.bin"),
            File(context.getExternalFilesDir(null), "llama-3.2-3b.bin"),
            File("/sdcard/Android/data/com.friday.assistant/files/gemma.bin")
        )

        val existingModel = possiblePaths.firstOrNull { it.exists() && it.isFile }
        
        if (existingModel == null) {
            Log.w(TAG, "No local LLM model file found. Searched: ${possiblePaths.joinToString { it.absolutePath }}")
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
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing MediaPipe LLM Inference", e)
        }
    }

    fun isModelLoaded(): Boolean = llmInference != null

    fun getLoadedModelName(): String {
        return loadedModelPath?.let { File(it).name } ?: "No model loaded"
    }

    /**
     * Triggers a model reload (useful if the user copies a model file later).
     */
    fun reloadModel() {
        llmInference?.close()
        llmInference = null
        findAndLoadModel()
    }

    /**
     * Generates a text response offline.
     * Takes the user command, system instructions, and recent conversation history.
     */
    suspend fun generateResponse(
        prompt: String,
        history: List<Pair<String, String>> = emptyList()
    ): String = withContext(Dispatchers.Default) {
        val inference = llmInference 
            ?: return@withContext "I'm offline, and my core language model has not been loaded. Please copy the model.bin file to my directory."

        try {
            // Build the conversational prompt with history
            val fullPromptBuilder = StringBuilder()
            
            // System instructions
            fullPromptBuilder.append("<start_of_turn>system\n")
            fullPromptBuilder.append("You are Friday, Avaneesh's personal voice assistant running offline on his Samsung S24. ")
            fullPromptBuilder.append("Keep your replies very short (1-2 sentences), friendly, conversational, and direct since they will be spoken aloud.\n")
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
        } catch (e: Exception) {
            Log.e(TAG, "Error generating response from local LLM", e)
            "Sorry Avaneesh, I encountered a processing error in my neural core: ${e.localizedMessage}"
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

        fun getInstance(context: Context): LocalLlmRunner {
            return INSTANCE ?: synchronized(this) {
                val instance = LocalLlmRunner(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
