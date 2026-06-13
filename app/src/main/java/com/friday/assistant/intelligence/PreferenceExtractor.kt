package com.friday.assistant.intelligence

import android.util.Log
import com.friday.assistant.core.FridayApplication
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object PreferenceExtractor {
    private const val TAG = "PreferenceExtractor"
    private val extractorScope = CoroutineScope(Dispatchers.Default)

    fun extractAndSavePreferences(userInput: String, assistantResponse: String, memoryManager: MemoryManager) {
        extractorScope.launch {
            try {
                // 1. Build extraction prompt.
                // We ask the local llama model to act as a background extractor, outputting a simple JSON object of preferences.
                val prompt = """
                    <|im_start|>system
                    You are a background fact extraction agent. Your job is to extract new personal facts, preferences, habits, or routines about the user from the conversation turn below.
                    Output ONLY a single valid JSON object containing the extracted key-values, or an empty JSON object {} if no new facts or preferences are found. Do not output any chat templates, explanations, markdown formatting, or thoughts.
                    
                    Example 1:
                    User: I love black coffee.
                    Assistant: Noted.
                    Output: {"favorite_coffee": "black coffee"}
                    
                    Example 2:
                    User: My sister's name is Sarah.
                    Assistant: Good to know.
                    Output: {"sister_name": "Sarah"}
                    
                    Example 3:
                    User: Turn on the flashlight.
                    Assistant: Done.
                    Output: {}
                    <|im_end|>
                    <|im_start|>user
                    User: $userInput
                    Assistant: $assistantResponse
                    <|im_end|>
                    <|im_start|>assistant
                """.trimIndent()

                // Generate extraction output with low temperature for deterministic parsing
                val rawResult = FridayApplication.llamaEngine.generate(prompt, maxTokens = 64, temp = 0.1f).trim()
                Log.d(TAG, "Preference extraction raw output: '$rawResult'")

                // Clean up output if the LLM wrapped it in markdown codeblocks
                var jsonStr = rawResult
                if (jsonStr.startsWith("```")) {
                    jsonStr = jsonStr.substringAfter("\n").substringBeforeLast("```").trim()
                }

                if (jsonStr.startsWith("{") && jsonStr.endsWith("}")) {
                    val json = JsonParser.parseString(jsonStr).asJsonObject
                    for (entry in json.entrySet()) {
                        val key = entry.key
                        val value = entry.value?.asString ?: continue
                        if (key.isNotEmpty() && value.isNotEmpty()) {
                            memoryManager.savePreference(key, value)
                        }
                    }
                } else {
                    Log.d(TAG, "No JSON block detected in preference extractor output")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in background preference extraction", e)
            }
        }
    }
}
