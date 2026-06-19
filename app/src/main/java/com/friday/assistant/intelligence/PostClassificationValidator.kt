package com.friday.assistant.intelligence

import android.content.Context
import android.util.Log

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

        // 1. Play media vs Open app collision check
        if (finalIntent == "play_media" || finalIntent == "play_spotify" || finalIntent == "play_youtube") {
            val queryText = preprocessed.extractedEntities["[QUOTE]"] 
                ?: EntityExtractor.extractMediaQuery(preprocessed.originalText).first
            
            if (queryText.isNotBlank() && isAppInstalled(context, queryText)) {
                Log.i(TAG, "Reclassifying play_media query '$queryText' as open_app because it matches an installed application.")
                finalIntent = "open_app"
                finalConfidence = 0.95f
            }
        }

        // 2. Google search trap suppression
        if (finalIntent == "search_google" || finalIntent == "web_search") {
            val originalTextLower = preprocessed.originalText.lowercase()
            if (originalTextLower.contains("message") || originalTextLower.contains("whatsapp") || originalTextLower.contains("text")) {
                if (originalTextLower.contains("whatsapp") || originalTextLower.contains("message")) {
                    Log.i(TAG, "Suppressed search_google redirection. Forcing send_whatsapp due to presence of message intent keywords.")
                    finalIntent = "send_whatsapp"
                    finalConfidence = 0.90f
                } else if (originalTextLower.contains("sms") || originalTextLower.contains("text")) {
                    Log.i(TAG, "Suppressed search_google redirection. Forcing send_sms due to presence of sms intent keywords.")
                    finalIntent = "send_sms"
                    finalConfidence = 0.90f
                }
            }
        }

        // 3. Confidence threshold check
        if (finalIntent == "unknown" || finalConfidence < 0.60f) {
            Log.d(TAG, "Intent is unknown or confidence $finalConfidence is below threshold 0.60. Routing to LLM fallback.")
            routeToLlm = true
        }

        return ValidationResult(finalIntent, finalConfidence, routeToLlm)
    }

    private fun isAppInstalled(context: Context, appName: String): Boolean {
        return try {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(0)
            val cleanName = appName.lowercase().trim()
            packages.any {
                val label = it.applicationInfo.loadLabel(pm).toString().lowercase().trim()
                label == cleanName || label.replace(" ", "") == cleanName.replace(" ", "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking installed apps", e)
            false
        }
    }
}
