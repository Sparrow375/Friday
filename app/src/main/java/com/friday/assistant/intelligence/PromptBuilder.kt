package com.friday.assistant.intelligence

import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PromptBuilder(private val memoryManager: MemoryManager) {

    companion object {
        private const val SYSTEM_INSTRUCTION = """
You are Friday, an offline premium AI assistant. Keep your responses cool, extremely concise (1-2 sentences maximum), and friendly. Do not use any emojis under any circumstances (as it breaks TTS flow).
        """
    }

    suspend fun buildPrompt(userInput: String): String {
        // 1. Get dynamic user context
        val memorySummary = memoryManager.getPreferencesSummary()
        val appUsageSummary = memoryManager.getMostUsedAppsSummary()
        
        // 2. Assemble full system prompt
        val sdf = SimpleDateFormat("EEEE, d MMMM yyyy HH:mm", Locale.getDefault())
        val currentDateStr = sdf.format(Date())
        val deviceContext = """
## Context Info
- Current Date/Time: $currentDateStr
- Device Model: ${Build.MANUFACTURER} ${Build.MODEL}
- OS Version: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})

## Memory Context
$memorySummary
- $appUsageSummary
        """.trimIndent()

        val fullSystemInstruction = "${SYSTEM_INSTRUCTION.trim()}\n\n$deviceContext"

        // 3. Construct Qwen2.5 Chat Template
        val prompt = StringBuilder()
        prompt.append("<|im_start|>system\n").append(fullSystemInstruction).append("<|im_end|>\n")

        // 4. Append Working Memory Chat History
        val history = memoryManager.getWorkingMemory().takeLast(8)
        history.forEach { msg ->
            prompt.append("<|im_start|>").append(msg.role).append("\n")
                .append(msg.content).append("<|im_end|>\n")
        }

        // 5. Append current User Input
        prompt.append("<|im_start|>user\n").append(userInput).append("<|im_end|>\n")
        prompt.append("<|im_start|>assistant\n")

        return prompt.toString()
    }

    /**
     * Minimal prompt for conversational fallback — no device context, last 4 turns only.
     * Used when NLU has already ruled out all tool intents and we just need a chat response.
     * Reduces average prompt from ~350 tokens to under 150 tokens.
     */
    suspend fun buildMinimalPrompt(userInput: String): String {
        val prompt = StringBuilder()
        prompt.append("<|im_start|>system\n")
        prompt.append(SYSTEM_INSTRUCTION.trim())
        prompt.append("<|im_end|>\n")

        // Only include last 4 turns of history (8 messages: 4 user + 4 assistant)
        val history = memoryManager.getWorkingMemory().takeLast(8)
        history.forEach { msg ->
            prompt.append("<|im_start|>").append(msg.role).append("\n")
                .append(msg.content).append("<|im_end|>\n")
        }

        prompt.append("<|im_start|>user\n").append(userInput).append("<|im_end|>\n")
        prompt.append("<|im_start|>assistant\n")

        return prompt.toString()
    }
}
