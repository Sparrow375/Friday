package com.friday.assistant.intelligence

/**
 * Tracks the last executed command domain so follow-up utterances like
 * "turn it off" resolve to the correct target (e.g. torch after "turn on torch").
 */
object DialogueStateTracker {

    private const val CONTEXT_TTL_MS = 90_000L // 90 seconds

    data class ActiveContext(
        val domain: String,
        val lastAction: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    @Volatile
    private var activeContext: ActiveContext? = null

    fun record(domain: String, action: String? = null) {
        activeContext = ActiveContext(domain, action)
    }

    fun clear() {
        activeContext = null
    }

    /**
     * If [query] is a pronoun/anaphoric follow-up, returns an expanded query string.
     * Otherwise returns the original query unchanged.
     */
    fun resolveFollowUp(query: String): String {
        val ctx = activeContext ?: return query
        if (System.currentTimeMillis() - ctx.timestamp > CONTEXT_TTL_MS) {
            activeContext = null
            return query
        }

        val q = query.trim().lowercase()
            .replace(Regex("\\b(hey|ok|please|friday|can you|could you|would you)\\b"), "")
            .trim()

        val offPatterns = listOf(
            "turn it off", "turn that off", "switch it off", "switch that off",
            "disable it", "disable that", "stop it", "stop that", "shut it off",
            "turn off", "switch off", "kill it"
        )
        val onPatterns = listOf(
            "turn it on", "turn that on", "switch it on", "switch that on",
            "enable it", "enable that", "start it", "start that", "turn on", "switch on"
        )
        val upPatterns = listOf(
            "turn it up", "increase it", "raise it", "make it louder", "make it brighter", "crank it up"
        )
        val downPatterns = listOf(
            "turn it down", "decrease it", "lower it", "make it quieter", "make it dimmer", "reduce it"
        )

        val isOff = offPatterns.any { q == it || q.endsWith(it) || q.contains(it) }
        val isOn = onPatterns.any { q == it || q.endsWith(it) || q.contains(it) }
        val isUp = upPatterns.any { q == it || q.endsWith(it) || q.contains(it) }
        val isDown = downPatterns.any { q == it || q.endsWith(it) || q.contains(it) }

        if (!isOff && !isOn && !isUp && !isDown) return query

        val expanded = when (ctx.domain) {
            "torch" -> when {
                isOff -> "turn off flashlight"
                isOn -> "turn on flashlight"
                else -> query
            }
            "wifi" -> when {
                isOff -> "turn off wifi"
                isOn -> "turn on wifi"
                else -> query
            }
            "bluetooth" -> when {
                isOff -> "turn off bluetooth"
                isOn -> "turn on bluetooth"
                else -> query
            }
            "hotspot" -> when {
                isOff -> "turn off hotspot"
                isOn -> "turn on hotspot"
                else -> query
            }
            "dnd" -> when {
                isOff -> "turn off do not disturb"
                isOn -> "turn on do not disturb"
                else -> query
            }
            "power_saver" -> when {
                isOff -> "turn off battery saver"
                isOn -> "turn on battery saver"
                else -> query
            }
            "screencast" -> when {
                isOff -> "stop screen cast"
                isOn -> "start screen cast"
                else -> query
            }
            "airplane_mode" -> when {
                isOff -> "turn off airplane mode"
                isOn -> "turn on airplane mode"
                else -> query
            }
            "mobile_data" -> when {
                isOff -> "turn off mobile data"
                isOn -> "turn on mobile data"
                else -> query
            }
            "camera" -> when {
                isOn -> "open camera"
                else -> query
            }
            "volume" -> when {
                isUp -> "increase volume"
                isDown -> "decrease volume"
                isOff -> "mute volume"
                else -> query
            }
            "brightness" -> when {
                isUp -> "increase brightness"
                isDown -> "decrease brightness"
                else -> query
            }
            "media" -> when {
                isOff -> "pause media"
                isOn -> "resume music"
                else -> query
            }
            else -> query
        }

        if (expanded != query) {
            com.friday.assistant.core.FridayLogger.i(
                "DialogueStateTracker",
                "Resolved follow-up '$query' -> '$expanded' (domain=${ctx.domain})"
            )
        }
        return expanded
    }
}
