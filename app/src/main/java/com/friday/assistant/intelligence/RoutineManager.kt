package com.friday.assistant.intelligence

import com.friday.assistant.core.db.FridayDao
import com.friday.assistant.core.db.RoutineEntity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first

data class ParsedRoutineDefinition(
    val triggerPhrase: String,
    val steps: List<String>
)

object RoutineManager {
    private val gson = Gson()

    private val CREATE_PATTERNS = listOf(
        Regex("(?i)^(?:when i say|whenever i say)\\s+[\"']?(.+?)[\"']?(?:,\\s*|\\s+(?:then|do|run)\\s+|\\s+(?=(?:turn|set|toggle|lock|open|play|pause|stop|enable|disable|send|call|mute|unmute|dim|brighten|check)\\b))(.+)$"),
        Regex("(?i)^(?:create|make|add|save|set up)\\s+(?:a\\s+)?(?:voice\\s+)?routine\\s+(?:called|named)?\\s*[\"']?(.+?)[\"']?\\s+(?:that|to|with|:)\\s+(.+)$")
    )

    fun parseRoutineCommand(rawInput: String): ParsedRoutineDefinition? {
        val text = rawInput.trim()
        for (pattern in CREATE_PATTERNS) {
            val match = pattern.find(text) ?: continue
            val trigger = match.groupValues[1]
                .replace(Regex("(?i)^(called|named)\\s+"), "")
                .trim()
                .lowercase()
            val rawSteps = match.groupValues[2].trim()
            val steps = splitNaturalSteps(rawSteps)
            if (trigger.isNotEmpty() && steps.isNotEmpty()) {
                return ParsedRoutineDefinition(triggerPhrase = trigger, steps = steps)
            }
        }
        return null
    }

    fun splitNaturalSteps(rawSteps: String): List<String> {
        return rawSteps
            .replace(Regex("(?i)\\s+(?:and then|then|and)\\s+"), "\n")
            .replace(",", "\n")
            .lines()
            .map { it.trim().replace(Regex("(?i)^to\\s+"), "") }
            .map { normalizeRoutineVerb(it) }
            .filter { it.length >= 3 }
            .take(6)
    }

    private fun normalizeRoutineVerb(step: String): String {
        val lower = step.lowercase()
        return when {
            lower.startsWith("turns on ") -> "turn on " + step.substring(9)
            lower.startsWith("turns off ") -> "turn off " + step.substring(10)
            lower.startsWith("locks ") -> "lock " + step.substring(6)
            lower.startsWith("opens ") -> "open " + step.substring(6)
            lower.startsWith("plays ") -> "play " + step.substring(6)
            lower.startsWith("sets ") -> "set " + step.substring(5)
            else -> step
        }.trim()
    }

    fun serializeSteps(steps: List<String>): String = gson.toJson(steps)

    fun parseStepList(actionSequenceJson: String): List<String> {
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(actionSequenceJson, type).orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun findMatchingRoutine(cleanQuery: String, dao: FridayDao): RoutineEntity? {
        val normalized = cleanQuery
            .replace(Regex("(?i)^(?:run|execute|start|trigger|activate)\\s+(?:my\\s+)?(?:routine\\s+)?"), "")
            .replace(Regex("(?i)\\s+(?:routine|macro)$"), "")
            .trim()
            .lowercase()

        if (normalized.isEmpty()) return null
        val direct = dao.getRoutineByTrigger(normalized)
        if (direct != null) return direct

        val all = dao.getAllRoutines().first()
        return all.firstOrNull {
            val trig = it.triggerPhrase.trim().lowercase()
            trig == normalized || cleanQuery == trig || cleanQuery == "$trig routine"
        }
    }
}
