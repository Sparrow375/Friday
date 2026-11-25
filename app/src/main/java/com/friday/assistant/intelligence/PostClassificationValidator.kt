package com.friday.assistant.intelligence

import android.content.Context
import android.util.Log
import com.friday.assistant.core.InstalledAppsCache

object PostClassificationValidator {
    private const val TAG = "PostClassificationValidator"

    data class ValidationResult(
        val intent: String,
        val confidence: Float,
        val routeToLlm: Boolean
    )

    fun validate(
        context: Context,
        intent: String,
        confidence: Float,
        preprocessed: PreprocessedInput,
        dialogueStateTracker: DialogueStateTracker = DialogueStateTracker
    ): ValidationResult {
        var finalIntent = intent
        var finalConfidence = confidence
        var routeToLlm = false

        // Bias confidence based on the active dialogue domain
        finalConfidence = dialogueStateTracker.biasConfidence(finalIntent, finalConfidence)

        // 0. Sports score guard: prevent Google search / app-launch hijacking
        val originalLowerForSports = preprocessed.originalText.lowercase()
        val isSportsQuery = originalLowerForSports.contains("cricket score") ||
            originalLowerForSports.contains("football score") ||
            originalLowerForSports.contains("soccer score") ||
            originalLowerForSports.contains("ipl score") ||
            originalLowerForSports.contains("match score") ||
            (originalLowerForSports.contains("score") &&
                (originalLowerForSports.contains("cricket") || originalLowerForSports.contains("football") ||
                    originalLowerForSports.contains("real madrid") || originalLowerForSports.contains("india") ||
                    originalLowerForSports.contains("match")))
        if (isSportsQuery) {
            return ValidationResult("sports_score", 0.96f, routeToLlm = false)
        }

        // 0b. Deterministic rescues for short system toggles / files / reminders / calendar / notes
        when {
            (originalLowerForSports.contains("flashlight") || originalLowerForSports.contains("torch")) &&
                (originalLowerForSports.contains("brightness") || originalLowerForSports.contains("strength") ||
                    originalLowerForSports.contains("intensity") || originalLowerForSports.contains("level")) -> {
                finalIntent = "torch_strength"
                finalConfidence = maxOf(finalConfidence, 0.94f)
            }
            originalLowerForSports.contains("downloads folder") || originalLowerForSports.contains("file manager") -> {
                finalIntent = "open_files"
                finalConfidence = maxOf(finalConfidence, 0.92f)
            }
            Regex("\\b(dnd|do not disturb)\\b").containsMatchIn(originalLowerForSports) -> {
                finalIntent = "dnd_toggle"
                finalConfidence = maxOf(finalConfidence, 0.92f)
            }
            Regex("\\b(mobile data|cellular data)\\b").containsMatchIn(originalLowerForSports) -> {
                finalIntent = "mobile_data_toggle"
                finalConfidence = maxOf(finalConfidence, 0.92f)
            }
            Regex("\\b(airplane mode|flight mode|aeroplane mode)\\b").containsMatchIn(originalLowerForSports) -> {
                finalIntent = "airplane_mode_toggle"
                finalConfidence = maxOf(finalConfidence, 0.92f)
            }
            originalLowerForSports.contains("list my reminders") || originalLowerForSports.contains("show my reminders") ||
                originalLowerForSports.contains("what reminders") -> {
                finalIntent = "reminder_list"
                finalConfidence = maxOf(finalConfidence, 0.92f)
            }
        }

        // 1. Play media vs Open app collision check
        if (finalIntent == "play_media" || finalIntent == "play_spotify" || finalIntent == "play_youtube") {
            val queryText = preprocessed.extractedEntities["[QUOTE]"] 
                ?: EntityExtractor.extractMediaQuery(preprocessed.originalText).first
            
            if (queryText.isNotBlank() && InstalledAppsCache.isAppInstalled(context, queryText)) {
                Log.i(TAG, "Reclassifying play_media query '$queryText' as open_app because it matches an installed application.")
                finalIntent = "open_app"
                finalConfidence = 0.95f
            }
        }

        // 2. Google search trap suppression & priority handling
        if (finalIntent == "search_google" || finalIntent == "web_search") {
            val originalTextLower = preprocessed.originalText.lowercase()
            if (originalTextLower.contains("message") || originalTextLower.contains("whatsapp") || originalTextLower.contains("text")) {
                Log.i(TAG, "Suppressed search_google redirection. Forcing send_whatsapp due to presence of message intent keywords.")
                finalIntent = "send_whatsapp"
                finalConfidence = 0.90f
            } else if (originalTextLower.contains("google") || originalTextLower.startsWith("search ") || originalTextLower.startsWith("search for ")) {
                // Ensure search queries are never downgraded to unknown or forced to LLM
                finalConfidence = maxOf(finalConfidence, 0.90f)
            }
        }

        // 3. Spoken Reminder validation: preserve set_reminder or rescue from notes_create/unknown
        val originalTextLower = preprocessed.originalText.lowercase()
        val isSpokenReminder = originalTextLower.contains("remind me") || originalTextLower.contains("set a reminder") || originalTextLower.startsWith("remind ")
        if (isSpokenReminder) {
            if (finalIntent == "notes_create" || finalIntent == "unknown" || finalIntent == "set_alarm") {
                Log.i(TAG, "Reclassifying intent as set_reminder for spoken reminder query: '${preprocessed.originalText}'")
                finalIntent = "set_reminder"
                finalConfidence = maxOf(finalConfidence, 0.92f)
                routeToLlm = false
            } else if (finalIntent == "set_reminder") {
                finalConfidence = maxOf(finalConfidence, 0.92f)
                routeToLlm = false
            }
        }

        // 4. Strict Phone Call Safety Guard: ONLY allow call_contact if explicit call verb is present
        if (finalIntent == "call_contact") {
            val hasExplicitCallVerb = originalTextLower.contains(Regex("\\b(call|dial)\\b"))
            if (!hasExplicitCallVerb) {
                Log.w(TAG, "SAFETY BLOCK: Suppressing false call_contact intent for '${preprocessed.originalText}' - no explicit 'call' or 'dial' verb detected!")
                finalIntent = "unknown"
                finalConfidence = 0.0f
                routeToLlm = true
            }
        }

        // 5. Confidence threshold check
        if (finalIntent == "unknown" || finalConfidence < 0.60f) {
            Log.d(TAG, "Intent is unknown or confidence $finalConfidence is below threshold 0.60. Routing to LLM fallback.")
            routeToLlm = true
        }

        return ValidationResult(finalIntent, finalConfidence, routeToLlm)
    }
}
