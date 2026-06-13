package com.friday.assistant.intelligence

import android.content.Context
import android.util.Log
import com.friday.assistant.core.FridayApplication
import com.friday.assistant.core.db.MemoryEntity
import kotlinx.coroutines.flow.first
import java.util.Locale

class MemoryManager(private val context: Context) {
    companion object {
        private const val TAG = "MemoryManager"
        private const val MAX_WORKING_MEMORY_TURNS = 10
    }

    private val dao = FridayApplication.database.dao()

    // Working Memory (in-RAM cache of recent conversation turns in current session)
    private val workingMemory = mutableListOf<ChatMessage>()

    data class ChatMessage(val role: String, val content: String, val timestamp: Long = System.currentTimeMillis())

    // ==========================================
    // Working Memory Operations
    // ==========================================

    fun addMessageToWorkingMemory(role: String, content: String) {
        workingMemory.add(ChatMessage(role, content))
        if (workingMemory.size > MAX_WORKING_MEMORY_TURNS * 2) { // 2 messages per turn (user + assistant)
            workingMemory.removeAt(0)
            workingMemory.removeAt(0)
        }
    }

    fun getWorkingMemory(): List<ChatMessage> {
        return workingMemory
    }

    fun clearWorkingMemory() {
        workingMemory.clear()
    }

    // ==========================================
    // Episodic Memory (Room-based logs)
    // ==========================================

    suspend fun saveConversationTurn(userMessage: String, assistantResponse: String) {
        try {
            // Save user turn
            val userMemory = MemoryEntity(
                layer = "EPISODIC",
                category = "chat_log",
                key = "user_${System.currentTimeMillis()}",
                value = userMessage
            )
            dao.insertMemory(userMemory)

            // Save assistant turn
            val assistantMemory = MemoryEntity(
                layer = "EPISODIC",
                category = "chat_log",
                key = "assistant_${System.currentTimeMillis()}",
                value = assistantResponse
            )
            dao.insertMemory(assistantMemory)
            
            // Also add to working memory
            addMessageToWorkingMemory("user", userMessage)
            addMessageToWorkingMemory("assistant", assistantResponse)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving episodic conversation log", e)
        }
    }

    // ==========================================
    // Semantic Memory (Facts / Preferences)
    // ==========================================

    suspend fun savePreference(key: String, value: String, confidence: Float = 1.0f) {
        try {
            val normalizedKey = key.trim().lowercase(Locale.ROOT).replace(" ", "_")
            val memory = MemoryEntity(
                layer = "SEMANTIC",
                category = "user_preference",
                key = normalizedKey,
                value = value.trim(),
                confidence = confidence
            )
            dao.insertMemory(memory)
            Log.i(TAG, "Remembered user preference: $normalizedKey = $value")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving user preference in semantic memory", e)
        }
    }

    suspend fun getPreference(key: String): String? {
        val normalizedKey = key.trim().lowercase(Locale.ROOT).replace(" ", "_")
        return try {
            val memory = dao.getMemoryByKey("user_preference", normalizedKey)
            memory?.value
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving preference: $normalizedKey", e)
            null
        }
    }

    suspend fun getAllPreferences(): List<MemoryEntity> {
        return try {
            dao.getMemoriesByLayer("SEMANTIC").first()
        } catch (e: Exception) {
            Log.e(TAG, "Error listing semantic preferences", e)
            emptyList()
        }
    }

    suspend fun forgetPreference(key: String) {
        val normalizedKey = key.trim().lowercase(Locale.ROOT).replace(" ", "_")
        try {
            val memory = dao.getMemoryByKey("user_preference", normalizedKey)
            if (memory != null) {
                dao.deleteMemory(memory)
                Log.d(TAG, "Forgot user preference: $normalizedKey")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting preference: $normalizedKey", e)
        }
    }

    /**
     * Reconstructs a memory summary block for system prompt context injector.
     */
    suspend fun getPreferencesSummary(): String {
        val prefs = getAllPreferences()
        if (prefs.isEmpty()) return "No user preferences remembered yet."
        val summary = StringBuilder("User preferences and facts:\n")
        prefs.forEach {
            summary.append("- ${it.key.replace("_", " ")}: ${it.value}\n")
        }
        return summary.toString()
    }
}
