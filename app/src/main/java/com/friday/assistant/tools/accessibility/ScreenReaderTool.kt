package com.friday.assistant.tools.accessibility

import com.friday.assistant.automation.ScreenReader
import com.friday.assistant.tools.Tool
import com.friday.assistant.tools.ToolResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class ScreenReaderTool : Tool {
    override val name: String = "read_screen"
    
    override val description: String = """
        Scans and reads the text and interactive elements visible on the active screen.
        Returns a list of text snippets and interactive elements mapped to short IDs (like [1], [2]).
        Use these IDs with other interaction tools (like click_element or type_text) to interact with them.
    """.trimIndent()

    override val parameters: JsonObject = JsonParser.parseString("""
        {
          "type": "object",
          "properties": {}
        }
    """).asJsonObject

    override suspend fun execute(args: JsonObject): ToolResult {
        val screenContent = ScreenReader.readScreen()
        return ToolResult(true, screenContent)
    }
}
