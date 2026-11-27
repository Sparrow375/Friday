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
    ): ValidationResult = validateRules(
        intent = intent,
        confidence = confidence,
        preprocessed = preprocessed,
        isInstalledApp = { queryText -> InstalledAppsCache.isAppInstalled(context, queryText) },
        dialogueStateTracker = dialogueStateTracker,
        logInfo = { msg -> Log.i(TAG, msg) },
        logWarn = { msg -> Log.w(TAG, msg) },
        logDebug = { msg -> Log.d(TAG, msg) }
    )

    internal fun validateRules(
        intent: String,
        confidence: Float,
        preprocessed: PreprocessedInput,
        isInstalledApp: (String) -> Boolean = { false },
        dialogueStateTracker: DialogueStateTracker = DialogueStateTracker,
        logInfo: (String) -> Unit = {},
        logWarn: (String) -> Unit = {},
        logDebug: (String) -> Unit = {}
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

        val originalTextLower = preprocessed.originalText.lowercase().trim()
        val hasExplicitSearchKeyword = Regex("\\bsearch\\b").containsMatchIn(originalTextLower)

        // 0b. Deterministic rescues for short system toggles / files / reminders / calendar / notes / media / alarms
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
            Regex("\\b(list|show|what|check|any|pending)\\b.*\\breminders?\\b").containsMatchIn(originalTextLower) ||
                originalTextLower.contains("list my reminders") || originalTextLower.contains("show my reminders") ||
                originalTextLower.contains("what reminders") -> {
                finalIntent = "reminder_list"
                finalConfidence = maxOf(finalConfidence, 0.94f)
                routeToLlm = false
            }
            Regex("\\b(remind|reminder|reminders)\\b").containsMatchIn(originalTextLower) ||
                originalTextLower.contains("don't let me forget") || originalTextLower.contains("dont let me forget") -> {
                finalIntent = "set_reminder"
                finalConfidence = maxOf(finalConfidence, 0.95f)
                routeToLlm = false
            }
            Regex("\\b(alarm|wake me up)\\b").containsMatchIn(originalTextLower) -> {
                finalIntent = "set_alarm"
                finalConfidence = maxOf(finalConfidence, 0.92f)
            }
            Regex("\\b(timer|countdown)\\b").containsMatchIn(originalTextLower) -> {
                finalIntent = "set_timer"
                finalConfidence = maxOf(finalConfidence, 0.92f)
            }
            EntityExtractor.isScreenshotQuery(originalTextLower) -> {
                finalIntent = "take_screenshot"
                finalConfidence = maxOf(finalConfidence, 0.94f)
            }
            Regex("\\b(turn down|lower|decrease|quieter)\\b").containsMatchIn(originalTextLower) &&
                Regex("\\b(volume|sound|audio|music)\\b").containsMatchIn(originalTextLower) -> {
                finalIntent = "volume_down"
                finalConfidence = maxOf(finalConfidence, 0.92f)
            }
            Regex("\\b(turn up|raise|increase|louder)\\b").containsMatchIn(originalTextLower) &&
                Regex("\\b(volume|sound|audio|music)\\b").containsMatchIn(originalTextLower) -> {
                finalIntent = "volume_up"
                finalConfidence = maxOf(finalConfidence, 0.92f)
            }
            originalTextLower.contains("dark in here") || originalTextLower.contains("need some light") -> {
                finalIntent = "torch_toggle"
                finalConfidence = maxOf(finalConfidence, 0.90f)
            }
            originalTextLower.contains("blinding") || originalTextLower.contains("too bright") -> {
                finalIntent = "brightness_down"
                finalConfidence = maxOf(finalConfidence, 0.90f)
            }
        }

        // 1. Play media vs Open app collision check
        if (finalIntent == "play_media" || finalIntent == "play_spotify" || finalIntent == "play_youtube") {
            val queryText = preprocessed.extractedEntities["[QUOTE]"]
                ?: EntityExtractor.extractMediaQuery(preprocessed.originalText).first

            if (queryText.isNotBlank() && isInstalledApp(queryText)) {
                logInfo("Reclassifying play_media query '$queryText' as open_app because it matches an installed application.")
                finalIntent = "open_app"
                finalConfidence = 0.95f
            }
        }

        // 2. Strict Web Search Lock: search_google / web_search ONLY allowed when explicit 'search' keyword is present
        if (finalIntent == "search_google" || finalIntent == "web_search") {
            if (originalTextLower.contains("message") || originalTextLower.contains("whatsapp") || originalTextLower.contains("text")) {
                logInfo("Suppressed search_google redirection. Forcing send_whatsapp due to presence of message intent keywords.")
                finalIntent = "send_whatsapp"
                finalConfidence = 0.90f
            } else if (!hasExplicitSearchKeyword) {
                logInfo("Blocked search intent '$finalIntent' for '${preprocessed.originalText}' because 'search' keyword was not spoken.")
                finalIntent = "unknown"
                finalConfidence = 0.0f
                routeToLlm = true
            } else {
                finalConfidence = maxOf(finalConfidence, 0.92f)
            }
        }

        // 3. Explicit 'search ...' safeguard (unless searching personal notes or Reddit)
        if (hasExplicitSearchKeyword &&
            !originalTextLower.contains("note") &&
            !originalTextLower.contains("reddit") &&
            finalIntent != "set_reminder" &&
            finalIntent != "reminder_list"
        ) {
            finalIntent = "search_google"
            finalConfidence = maxOf(finalConfidence, 0.92f)
            routeToLlm = false
        }

        // 4. Strict Phone Call Safety Guard: ONLY allow call_contact if explicit call verb is present
        if (finalIntent == "call_contact") {
            val hasExplicitCallVerb = originalTextLower.contains(Regex("\\b(call|dial)\\b"))
            if (!hasExplicitCallVerb) {
                logWarn("SAFETY BLOCK: Suppressing false call_contact intent for '${preprocessed.originalText}' - no explicit 'call' or 'dial' verb detected!")
                finalIntent = "unknown"
                finalConfidence = 0.0f
                routeToLlm = true
            }
        }

        // 5. Confidence threshold check
        if (finalIntent == "unknown" || finalConfidence < 0.60f) {
            logDebug("Intent is unknown or confidence $finalConfidence is below threshold 0.60. Routing to LLM fallback.")
            routeToLlm = true
        }

        return ValidationResult(finalIntent, finalConfidence, routeToLlm)
    }
}
