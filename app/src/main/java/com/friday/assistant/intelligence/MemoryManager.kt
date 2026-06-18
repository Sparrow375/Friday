package com.friday.assistant.intelligence

import android.content.Context
import android.util.Log
import com.friday.assistant.core.FridayApplication
import com.friday.assistant.core.db.MemoryEntity
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.first
import java.util.Locale

class MemoryManager(private val context: Context) {
    companion object {
        private const val TAG = "MemoryManager"
        private const val MAX_WORKING_MEMORY_TURNS = 10
        private const val CACHE_TTL_MS = 60_000L
    }

    private val dao = FridayApplication.database.dao()

    // Working Memory (in-RAM cache of recent conversation turns in current session)
    private val workingMemory = mutableListOf<ChatMessage>()

    private var preferencesCacheValue = ""
    private var preferencesCacheTime = 0L
    private var appCacheValue = ""
    private var appCacheTime = 0L

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
            preferencesCacheTime = 0L  // Invalidate preferences cache
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
        val now = System.currentTimeMillis()
        if (preferencesCacheValue.isNotEmpty() && now - preferencesCacheTime < CACHE_TTL_MS) {
            com.friday.assistant.core.FridayLogger.d(TAG, "Preferences cache HIT")
            return preferencesCacheValue
        }
        com.friday.assistant.core.FridayLogger.d(TAG, "Preferences cache MISS — querying DB")
        val prefs = getAllPreferences()
        val result = if (prefs.isEmpty()) {
            "No user preferences remembered yet."
        } else {
            val summary = StringBuilder("User preferences and facts:\n")
            prefs.forEach {
                summary.append("- ${it.key.replace("_", " ")}: ${it.value}\n")
            }
            summary.toString()
        }
        preferencesCacheValue = result
        preferencesCacheTime = now
        return result
    }

    // ==========================================
    // App Usage Statistics
    // ==========================================

    suspend fun incrementAppLaunchCount(packageName: String, appName: String) {
        try {
            val key = packageName
            val existing = dao.getMemoryByKey("app_usage", key)
            val now = System.currentTimeMillis()
            val newValue = if (existing != null) {
                val json = JsonParser.parseString(existing.value).asJsonObject
                val count = json.get("launch_count")?.asInt ?: 0
                json.addProperty("launch_count", count + 1)
                json.addProperty("last_used", now)
                json.toString()
            } else {
                val json = JsonObject()
                json.addProperty("app_name", appName)
                json.addProperty("launch_count", 1)
                json.addProperty("last_used", now)
                json.toString()
            }
            val memory = MemoryEntity(
                id = existing?.id ?: 0,
                layer = "SEMANTIC",
                category = "app_usage",
                key = key,
                value = newValue
            )
            dao.insertMemory(memory)
            appCacheTime = 0L  // Invalidate app usage cache
            Log.d(TAG, "Incremented app launch count for $packageName ($appName)")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating app launch stats", e)
        }
    }

    suspend fun getMostUsedApps(limit: Int = 5): List<String> {
        return try {
            val memories = dao.getMemoriesByLayer("SEMANTIC").first()
            memories
                .filter { it.category == "app_usage" }
                .mapNotNull {
                    try {
                        val json = JsonParser.parseString(it.value).asJsonObject
                        val appName = json.get("app_name")?.asString ?: ""
                        val count = json.get("launch_count")?.asInt ?: 0
                        Pair(appName, count)
                    } catch (e: Exception) {
                        null
                    }
                }
                .sortedByDescending { it.second }
                .take(limit)
                .map { it.first }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving most used apps", e)
            emptyList()
        }
    }

    suspend fun getMostUsedAppsSummary(): String {
        val now = System.currentTimeMillis()
        if (appCacheValue.isNotEmpty() && now - appCacheTime < CACHE_TTL_MS) {
            com.friday.assistant.core.FridayLogger.d(TAG, "App usage cache HIT")
            return appCacheValue
        }
        com.friday.assistant.core.FridayLogger.d(TAG, "App usage cache MISS — querying DB")
        val mostUsed = getMostUsedApps(3)
        val result = if (mostUsed.isEmpty()) "No frequent apps tracked yet."
                     else "Most frequently launched apps: ${mostUsed.joinToString(", ")}"
        appCacheValue = result
        appCacheTime = now
        return result
    }
}
