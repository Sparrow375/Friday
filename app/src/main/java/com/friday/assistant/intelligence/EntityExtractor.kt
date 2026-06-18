package com.friday.assistant.intelligence

object EntityExtractor {

    private val APP_NAMES = mapOf(
        "spotify" to "spotify",
        "youtube" to "youtube",
        "yt" to "youtube",
        "youtube music" to "youtube music",
        "yt music" to "youtube music",
        "whatsapp" to "whatsapp",
        "instagram" to "instagram",
        "gmail" to "gmail",
        "maps" to "maps",
        "google maps" to "maps",
        "chrome" to "chrome",
        "browser" to "browser",
        "camera" to "camera",
        "settings" to "settings",
        "telegram" to "telegram",
        "netflix" to "netflix"
    )

    fun detectAppName(query: String): String? {
        val lower = query.lowercase()
        return APP_NAMES.entries
            .sortedByDescending { it.key.length }
            .firstOrNull { lower.contains(it.key) }
            ?.value
    }

    fun extractCallContact(query: String): String? {
        val patterns = listOf(
            "(?i)(?:call|phone|dial|ring)\\s+(?:up\\s+)?(.+?)(?:\\s+please)?$".toRegex(),
            "(?i)(?:can you|could you|please)\\s+call\\s+(.+?)(?:\\s+please)?$".toRegex(),
            "(?i)make a call to\\s+(.+?)(?:\\s+please)?$".toRegex(),
            "(?i)give\\s+(.+?)\\s+a call$".toRegex()
        )
        for (pattern in patterns) {
            val match = pattern.find(query.trim()) ?: continue
            val name = cleanEntity(match.groupValues[1])
            if (name.isNotEmpty() && !isGenericWord(name)) return name
        }
        return null
    }

    fun extractSmsDetails(query: String): Pair<String, String>? {
        val patterns = listOf(
            "(?i)(?:text|sms|message)\\s+(.+?)\\s+(?:saying|with|that|message)?\\s*(.+)".toRegex(),
            "(?i)send (?:an? )?(?:sms|text message) to\\s+(.+?)\\s+(?:saying|with|message)?\\s*(.+)".toRegex(),
            "(?i)send\\s+(.+?)\\s+(?:an? )?(?:sms|text)\\s+(?:saying|with)?\\s*(.+)".toRegex()
        )
        for (pattern in patterns) {
            val match = pattern.find(query.trim()) ?: continue
            val recipient = cleanEntity(match.groupValues[1])
            val message = match.groupValues[2].removePrefix("\"").removeSuffix("\"").trim()
            if (recipient.isNotEmpty() && message.isNotEmpty()) return Pair(recipient, message)
        }
        return null
    }

    fun extractMediaQuery(query: String): Pair<String, String?> {
        val lower = query.lowercase()
        val app = detectAppName(query)

        val patterns = listOf(
            "(?i)play\\s+(.+?)\\s+on\\s+(spotify|youtube|youtube music)".toRegex(),
            "(?i)play\\s+(.+?)\\s+from\\s+(spotify|youtube)".toRegex(),
            "(?i)listen to\\s+(.+?)\\s+on\\s+(spotify|youtube)".toRegex(),
            "(?i)play\\s+(.+)".toRegex(),
            "(?i)listen to\\s+(.+)".toRegex(),
            "(?i)start playing\\s+(.+)".toRegex(),
            "(?i)search\\s+(.+?)\\s+on\\s+(spotify|youtube|google)".toRegex(),
            "(?i)search\\s+(?:for\\s+)?(.+?)\\s+on\\s+(spotify|youtube|google)".toRegex()
        )

        for (pattern in patterns) {
            val match = pattern.find(query.trim()) ?: continue
            var mediaQuery = cleanEntity(match.groupValues[1])
            val detectedApp = if (match.groupValues.size > 2 && match.groupValues[2].isNotBlank()) {
                match.groupValues[2].lowercase()
            } else app

            mediaQuery = mediaQuery
                .replace(Regex("\\bon (spotify|youtube|youtube music|google)\\b"), "")
                .replace(Regex("\\bfrom (spotify|youtube)\\b"), "")
                .trim()

            if (mediaQuery.isNotEmpty() && !isGenericWord(mediaQuery)) {
                return Pair(mediaQuery, detectedApp)
            }
        }
        return Pair("", app)
    }

    fun extractLaunchAppName(query: String): String {
        val launchKeywords = listOf("open up ", "go to ", "open ", "launch ", "start ", "show ", "run ")
        val lower = query.lowercase()
        for (kw in launchKeywords) {
            val idx = lower.indexOf(kw)
            if (idx != -1) {
                return cleanEntity(lower.substring(idx + kw.length))
            }
        }
        return cleanEntity(query)
    }

    fun isScreenshotQuery(query: String): Boolean {
        val lower = query.lowercase()
        return lower.contains("screenshot") ||
            lower.contains("screen shot") ||
            lower.contains("capture screen") ||
            lower.contains("take a snap") ||
            (lower.contains("capture") && lower.contains("screen"))
    }

    fun isFollowUpOnly(query: String): Boolean {
        val q = query.trim().lowercase()
        val followUps = listOf(
            "turn it off", "turn it on", "turn that off", "turn that on",
            "switch it off", "switch it on", "disable it", "enable it",
            "turn it up", "turn it down", "make it louder", "make it quieter",
            "make it brighter", "make it dimmer", "stop it", "pause it", "resume it"
        )
        return followUps.any { q == it || q.endsWith(it) }
    }

    private fun cleanEntity(text: String): String {
        return text
            .replace(Regex("\\b(please|thanks|thank you|now|right now)\\b"), "")
            .replace("?", "")
            .replace(".", "")
            .replace("!", "")
            .trim()
    }

    private fun isGenericWord(text: String): Boolean {
        val generic = setOf("music", "song", "something", "it", "that", "this", "media")
        return text.lowercase() in generic
    }
}
