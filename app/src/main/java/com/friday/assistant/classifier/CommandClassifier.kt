package com.friday.assistant.classifier

import java.util.regex.Pattern

enum class IntentType {
    VOLUME,
    BRIGHTNESS,
    WIFI,
    BLUETOOTH,
    TORCH,
    DND,
    BATTERY,
    ROUTINE,
    LAUNCH_APP,
    DEEP_LINK_APP,
    NOTE,
    CLIPBOARD,
    ALARM_TIMER,
    CALL,
    FALLBACK_TO_LLM
}

data class LocalCommand(
    val intent: IntentType,
    val parameters: Map<String, String> = emptyMap()
)

class CommandClassifier {

    private fun extractSearchQuery(text: String, platform: String): String {
        val cleanText = text.trim()

        // Match: "search <query> on/in <platform>" (platform at the end)
        val pattern1 = Pattern.compile("(?i)^search\\s+(.+?)\\s+(?:on|in)\\s+$platform$", Pattern.CASE_INSENSITIVE)
        val matcher1 = pattern1.matcher(cleanText)
        if (matcher1.matches()) {
            return matcher1.group(1)?.trim() ?: ""
        }

        // Match: "search <platform> for/about <query>" (platform in the middle)
        val pattern2 = Pattern.compile("(?i)^search\\s+$platform\\s+(?:for|about)?\\s+(.+)$", Pattern.CASE_INSENSITIVE)
        val matcher2 = pattern2.matcher(cleanText)
        if (matcher2.matches()) {
            return matcher2.group(1)?.trim() ?: ""
        }

        // Match: "open <platform> and search (for) <query>"
        val pattern3 = Pattern.compile("(?i)^open\\s+$platform\\s+and\\s+search\\s+(?:for\\s+)?(.+)$", Pattern.CASE_INSENSITIVE)
        val matcher3 = pattern3.matcher(cleanText)
        if (matcher3.matches()) {
            return matcher3.group(1)?.trim() ?: ""
        }

        // Match: "<platform> search <query>"
        if (cleanText.lowercase().startsWith("$platform search ")) {
            return cleanText.substring(platform.length + 8).trim()
        }

        // Fallback: Remove platform name and search keywords from anywhere
        var temp = cleanText
        // Remove "search <platform> for" or similar
        temp = temp.replace(Regex("(?i)\\bsearch\\s+$platform\\s+(for|about)?\\b"), "")
        // Remove "on/in <platform>" at the end
        temp = temp.replace(Regex("(?i)\\b(on|in)\\s+$platform$"), "")
        // Remove leading "search "
        temp = temp.replace(Regex("(?i)^search\\s+"), "")
        return temp.trim()
    }

    private val appNameMap = mapOf(
        "reddit" to "com.reddit.frontpage",
        "discord" to "com.discord",
        "instagram" to "com.instagram.android",
        "insta" to "com.instagram.android",
        "chrome" to "com.android.chrome",
        "spotify" to "com.spotify.music",
        "brave" to "com.brave.browser",
        "whatsapp" to "com.whatsapp"
    )

    /**
     * Classifies a voice command text into a structured LocalCommand.
     */
    fun classify(text: String): LocalCommand {
        val cleanText = text.lowercase().trim().replace(Regex("[.,!?]"), "")

        // --- Layer 1: Rule-Based / Pattern-Matching ---

        // 1. Torch / Flashlight Control
        if (cleanText.contains("flashlight") || cleanText.contains("torch")) {
            val state = if (cleanText.contains("off") || cleanText.contains("disable")) "off" else "on"
            return LocalCommand(IntentType.TORCH, mapOf("state" to state))
        }

        // 2. Wi-Fi Control
        if (cleanText.contains("wifi") || cleanText.contains("wi-fi") || cleanText.contains("internet")) {
            if (cleanText.contains("on") || cleanText.contains("enable") || cleanText.contains("activate")) {
                return LocalCommand(IntentType.WIFI, mapOf("state" to "on"))
            } else if (cleanText.contains("off") || cleanText.contains("disable") || cleanText.contains("deactivate")) {
                return LocalCommand(IntentType.WIFI, mapOf("state" to "off"))
            }
        }

        // 3. Bluetooth Control
        if (cleanText.contains("bluetooth")) {
            if (cleanText.contains("on") || cleanText.contains("enable")) {
                return LocalCommand(IntentType.BLUETOOTH, mapOf("state" to "on"))
            } else if (cleanText.contains("off") || cleanText.contains("disable")) {
                return LocalCommand(IntentType.BLUETOOTH, mapOf("state" to "off"))
            }
        }

        // 4. Do Not Disturb (DND)
        if (cleanText.contains("dnd") || cleanText.contains("do not disturb") || cleanText.contains("silent") || cleanText.contains("silence")) {
            val state = if (cleanText.contains("off") || cleanText.contains("disable") || cleanText.contains("stop")) "off" else "on"
            return LocalCommand(IntentType.DND, mapOf("state" to state))
        }

        // 5. Volume Control
        val volumePattern = Pattern.compile("(?:set|change|put)?\\s*volume\\s*(?:to)?\\s*(\\d+)")
        val volumeMatcher = volumePattern.matcher(cleanText)
        if (volumeMatcher.find()) {
            val level = volumeMatcher.group(1) ?: "50"
            return LocalCommand(IntentType.VOLUME, mapOf("action" to "set", "level" to level))
        } else if (cleanText.contains("volume up") || cleanText.contains("raise volume") || cleanText.contains("louder") || cleanText.contains("increase volume")) {
            return LocalCommand(IntentType.VOLUME, mapOf("action" to "up"))
        } else if (cleanText.contains("volume down") || cleanText.contains("lower volume") || cleanText.contains("quieter") || cleanText.contains("decrease volume")) {
            return LocalCommand(IntentType.VOLUME, mapOf("action" to "down"))
        } else if (cleanText.contains("mute")) {
            return LocalCommand(IntentType.VOLUME, mapOf("action" to "mute"))
        } else if (cleanText.contains("unmute")) {
            return LocalCommand(IntentType.VOLUME, mapOf("action" to "unmute"))
        }

        // 6. Brightness Control
        val brightnessPattern = Pattern.compile("(?:set|change|put)?\\s*brightness\\s*(?:to)?\\s*(\\d+)")
        val brightnessMatcher = brightnessPattern.matcher(cleanText)
        if (brightnessMatcher.find()) {
            val level = brightnessMatcher.group(1) ?: "50"
            return LocalCommand(IntentType.BRIGHTNESS, mapOf("action" to "set", "level" to level))
        } else if (cleanText.contains("brighter") || cleanText.contains("increase brightness") || cleanText.contains("raise brightness")) {
            return LocalCommand(IntentType.BRIGHTNESS, mapOf("action" to "up"))
        } else if (cleanText.contains("dim") || cleanText.contains("darker") || cleanText.contains("decrease brightness") || cleanText.contains("lower brightness")) {
            return LocalCommand(IntentType.BRIGHTNESS, mapOf("action" to "down"))
        }

        // 7. Battery Status
        if (cleanText.contains("battery") || cleanText.contains("power status")) {
            return LocalCommand(IntentType.BATTERY)
        }

        // 8. Clipboard Actions
        if (cleanText.contains("clipboard")) {
            if (cleanText.contains("copy") || cleanText.contains("save") || cleanText.contains("write")) {
                // Extract what to copy. E.g. "copy Hello to clipboard" -> "Hello"
                var copyText = cleanText.replace("copy", "").replace("to clipboard", "").replace("save", "").replace("write", "").trim()
                return LocalCommand(IntentType.CLIPBOARD, mapOf("action" to "copy", "text" to copyText))
            } else if (cleanText.contains("what") || cleanText.contains("read") || cleanText.contains("get") || cleanText.contains("paste")) {
                return LocalCommand(IntentType.CLIPBOARD, mapOf("action" to "read"))
            }
        }

        // 9. Quick-Notes
        if (cleanText.startsWith("take a note") || cleanText.startsWith("note down") || cleanText.startsWith("save note") || cleanText.startsWith("write down")) {
            val noteContent = text.replace(Regex("(?i)^(take a note|note down|save note|write down)\\s*"), "").trim()
            return if (noteContent.isNotEmpty()) {
                LocalCommand(IntentType.NOTE, mapOf("action" to "create", "content" to noteContent))
            } else {
                LocalCommand(IntentType.NOTE, mapOf("action" to "prompt"))
            }
        }

        // 10. Alarm & Timers
        val timerPattern = Pattern.compile("set a timer for (\\d+)\\s*(minute|second|hour)s?")
        val timerMatcher = timerPattern.matcher(cleanText)
        if (timerMatcher.find()) {
            val duration = timerMatcher.group(1) ?: "0"
            val unit = timerMatcher.group(2) ?: "minute"
            return LocalCommand(IntentType.ALARM_TIMER, mapOf("type" to "timer", "duration" to duration, "unit" to unit))
        }
        val alarmPattern = Pattern.compile("set an alarm for (\\d+[:.]?\\d*\\s*(?:am|pm)?)")
        val alarmMatcher = alarmPattern.matcher(cleanText)
        if (alarmMatcher.find()) {
            val time = alarmMatcher.group(1) ?: ""
            return LocalCommand(IntentType.ALARM_TIMER, mapOf("type" to "alarm", "time" to time))
        }

        // --- Layer 2: On-Device ML/Fuzzy Intent matching ---

        // Check for specific apps Launching
        for ((name, packageName) in appNameMap) {
            if (cleanText == "open $name" || cleanText == "launch $name" || cleanText == "go to $name") {
                return LocalCommand(IntentType.LAUNCH_APP, mapOf("packageName" to packageName, "appName" to name))
            }
        }

        // Check for App Deep Links (e.g. Spotify play, WhatsApp message, Brave/Chrome searches)
        // A. Spotify: "play X on spotify" or "play X"
        if (cleanText.startsWith("play ") && (cleanText.contains("spotify") || cleanText.contains("music") || cleanText.contains("song"))) {
            val query = text.replace(Regex("(?i)play\\s+"), "")
                .replace(Regex("(?i)\\s+on\\s+spotify"), "")
                .replace(Regex("(?i)\\s+music"), "")
                .replace(Regex("(?i)\\s+song"), "").trim()
            return LocalCommand(IntentType.DEEP_LINK_APP, mapOf("app" to "spotify", "query" to query))
        }

        // B. WhatsApp: "message X on whatsapp" or "text X on whatsapp" or "whatsapp message to X"
        if (cleanText.contains("whatsapp")) {
            val parts = text.split(Regex("(?i)whatsapp"), 2)
            val namePart = parts[0].replace(Regex("(?i)(message|text|send message to|whatsapp)\\s*"), "").trim()
            val query = if (parts.size > 1) parts[1].replace(Regex("^\\s*(to|on)\\s*"), "").trim() else ""
            
            val targetName = namePart.ifEmpty { query }
            return LocalCommand(IntentType.DEEP_LINK_APP, mapOf("app" to "whatsapp", "name" to targetName))
        }

        // C. Reddit: "search reddit for X" or "search X on reddit"
        if (cleanText.contains("reddit")) {
            val query = extractSearchQuery(text, "reddit")
            return LocalCommand(IntentType.DEEP_LINK_APP, mapOf("app" to "reddit", "query" to query))
        }

        // D. YouTube: "search youtube for X" or "search X on youtube"
        if (cleanText.contains("youtube")) {
            val query = extractSearchQuery(text, "youtube")
            return LocalCommand(IntentType.DEEP_LINK_APP, mapOf("app" to "youtube", "query" to query))
        }

        // E. Brave: "search brave for X" or "search X on brave"
        if (cleanText.contains("brave")) {
            val query = extractSearchQuery(text, "brave")
            return LocalCommand(IntentType.DEEP_LINK_APP, mapOf("app" to "brave", "query" to query))
        }

        // F. Discord: "open discord" or "chat on discord"
        if (cleanText.contains("discord") && (cleanText.contains("open") || cleanText.contains("launch"))) {
            return LocalCommand(IntentType.LAUNCH_APP, mapOf("packageName" to appNameMap["discord"]!!, "appName" to "discord"))
        }

        // G. Instagram: "open instagram"
        if ((cleanText.contains("instagram") || cleanText.contains("insta")) && (cleanText.contains("open") || cleanText.contains("launch"))) {
            return LocalCommand(IntentType.LAUNCH_APP, mapOf("packageName" to appNameMap["instagram"]!!, "appName" to "instagram"))
        }

        // H. Chrome (X / Twitter): "search twitter for X" or "search X on twitter" or "search x for X"
        if (cleanText.contains("twitter") || cleanText.contains("search x ") || cleanText.contains("search on x ") || cleanText.endsWith(" on x")) {
            val query = extractSearchQuery(text, "twitter").ifEmpty { extractSearchQuery(text, "x") }
            return LocalCommand(IntentType.DEEP_LINK_APP, mapOf("app" to "chrome_x", "query" to query))
        }

        // I. General Google Search: "search X" or "search on google for X"
        if (cleanText.contains("google") || cleanText.startsWith("search ")) {
            val query = extractSearchQuery(text, "google")
            return LocalCommand(IntentType.DEEP_LINK_APP, mapOf("app" to "google", "query" to query))
        }

        // H. Phone Calls: "call X" or "dial X" or "phone X"
        if (cleanText.startsWith("call ") || cleanText.startsWith("dial ") || cleanText.startsWith("phone ")) {
            val contactName = text.replace(Regex("(?i)^(call|dial|phone)\\s*"), "").trim()
            return LocalCommand(IntentType.CALL, mapOf("name" to contactName))
        }

        // --- Layer 3: Fallback to Local LLM ---
        return LocalCommand(IntentType.FALLBACK_TO_LLM)
    }
}
