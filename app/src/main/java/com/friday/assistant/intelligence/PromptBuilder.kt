package com.friday.assistant.intelligence

import android.os.Build
import com.friday.assistant.tools.ToolRegistry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PromptBuilder(private val memoryManager: MemoryManager) {

    companion object {
        private const val SYSTEM_INSTRUCTION = """
You are Friday, a highly advanced, premium, and intelligent on-device AI assistant. 
You are offline, running entirely on-device on the user's phone.
You must be efficient, helpful, adaptive, and maintain a cool, professional, yet friendly persona.

## Tools & Capabilities
You have direct access to system-level integrations and device capabilities via the tools described below.
To call a tool, you MUST output a single valid JSON object matching this schema:
{"tool": "tool_name", "arguments": {"arg_name": "arg_value"}}

After you output a tool call, the system will execute it and return the result. You can then continue your output or finalize your response.
If no tools are needed to satisfy the user request, respond directly in concise, natural language. Do NOT write tool call JSON if no action is needed.

## Tool Manifest:
{tool_manifest}

## Guidelines
1. Be concise: The user hears your response via Text-To-Speech (TTS). Avoid long paragraphs.
2. Under no circumstances should you generate emojis in your text output (as it breaks TTS flow).
3. Always verify and refer to user preferences when answering.
4. If a tool call fails, explain the failure and ask for guidance or try another approach.
        """
    }

    suspend fun buildPrompt(userInput: String): String {
        // 1. Get tool manifest
        val toolsJson = ToolRegistry.getManifestJson().toString()
        val systemPrompt = SYSTEM_INSTRUCTION.replace("{tool_manifest}", toolsJson)

        // 2. Get dynamic user context
        val memorySummary = memoryManager.getPreferencesSummary()
        val appUsageSummary = memoryManager.getMostUsedAppsSummary()
        
        // 3. Assemble full system prompt
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

        val fullSystemInstruction = "$systemPrompt\n\n$deviceContext"

        // 4. Construct Qwen2.5 Chat Template
        val prompt = StringBuilder()
        prompt.append("<|im_start|>system\n").append(fullSystemInstruction).append("<|im_end|>\n")

        // 5. Append Working Memory Chat History
        val history = memoryManager.getWorkingMemory()
        history.forEach { msg ->
            prompt.append("<|im_start|>").append(msg.role).append("\n")
                .append(msg.content).append("<|im_end|>\n")
        }

        // 6. Append current User Input
        prompt.append("<|im_start|>user\n").append(userInput).append("<|im_end|>\n")
        prompt.append("<|im_start|>assistant\n")

        return prompt.toString()
    }
}
