package com.friday.assistant.intelligence

import android.util.Log

data class PreprocessedInput(
    val cleanedText: String,
    val originalText: String,
    val extractedEntities: Map<String, String>
)

object InputPreprocessor {
    private const val TAG = "InputPreprocessor"

    fun preprocess(query: String): PreprocessedInput {
        val originalText = query
        val entities = mutableMapOf<String, String>()
        var workingText = query

        // 1. Extract quoted strings: "..." or '...' -> [QUOTE]
        val quotesRegex = "\"([^\"]+)\"|'([^']+)'".toRegex()
        var quoteIndex = 1
        var matchResult = quotesRegex.find(workingText)
        while (matchResult != null) {
            val value = matchResult.groupValues[1].ifEmpty { matchResult.groupValues[2] }
            val key = "[QUOTE_${quoteIndex}]"
            entities[key] = value
            workingText = workingText.replace(matchResult.value, key)
            quoteIndex++
            matchResult = quotesRegex.find(workingText)
        }
        
        if (entities.containsKey("[QUOTE_1]")) {
            entities["[QUOTE]"] = entities["[QUOTE_1]"]!!
        }

        // 2. Message payloads: text after "saying/with/that" in message/whatsapp/sms commands
        val messagePatterns = listOf(
            "(?i)(?:text|sms|message|whatsapp|send message to|send whatsapp to)\\s+(.+?)\\s+(?:saying|with|that|message|write)\\s*(.+)".toRegex()
        )
        for (pattern in messagePatterns) {
            val match = pattern.find(workingText)
            if (match != null) {
                val recipient = match.groupValues[1].trim()
                val body = match.groupValues[2].trim()
                
                if (!body.startsWith("[QUOTE")) {
                    val key = "[QUOTE]"
                    entities[key] = body
                    workingText = workingText.replace(body, key)
                }
                
                if (!recipient.startsWith("[CONTACT") && !recipient.startsWith("[PHONE")) {
                    val key = "[CONTACT]"
                    entities[key] = recipient
                    workingText = workingText.replace(recipient, key)
                }
                break
            }
        }

        // 3. Phone numbers: \d{10,} or +\d{1,3}\s?\d{10} -> [PHONE]
        val phoneRegex = "(?:\\+\\d{1,3}\\s?)?\\d{10,}".toRegex()
        var phoneIndex = 1
        var phoneMatch = phoneRegex.find(workingText)
        while (phoneMatch != null) {
            val key = "[PHONE_${phoneIndex}]"
            entities[key] = phoneMatch.value
            workingText = workingText.replace(phoneMatch.value, key)
            phoneIndex++
            phoneMatch = phoneRegex.find(workingText)
        }
        if (entities.containsKey("[PHONE_1]")) {
            entities["[PHONE]"] = entities["[PHONE_1]"]!!
        }

        // 4. Time expressions: \d{1,2}:\d{2}\s*(am|pm)? or \d{1,2}\s*(am|pm) -> [TIME]
        val timeRegex = "\\b\\d{1,2}:\\d{2}\\s*(?:am|pm|AM|PM)?\\b|\\b\\d{1,2}\\s*(?:am|pm|AM|PM)\\b".toRegex()
        var timeIndex = 1
        var timeMatch = timeRegex.find(workingText)
        while (timeMatch != null) {
            val key = "[TIME_${timeIndex}]"
            entities[key] = timeMatch.value
            workingText = workingText.replace(timeMatch.value, key)
            timeIndex++
            timeMatch = timeRegex.find(workingText)
        }
        if (entities.containsKey("[TIME_1]")) {
            entities["[TIME]"] = entities["[TIME_1]"]!!
        }

        Log.d(TAG, "Preprocessed: Cleaned='$workingText', Entities=$entities")
        return PreprocessedInput(workingText, originalText, entities)
    }
}
