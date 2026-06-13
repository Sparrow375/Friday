package com.friday.assistant.tools

import com.google.gson.JsonObject

interface Tool {
    val name: String
    val description: String
    val parameters: JsonObject // JSON Schema of arguments
    suspend fun execute(args: JsonObject): ToolResult
}

data class ToolResult(
    val success: Boolean,
    val data: String,          // Response description for the LLM
    val rawData: Any? = null   // Structured output if needed
)

object ToolRegistry {
    private val registry = mutableMapOf<String, Tool>()

    fun register(tool: Tool) {
        registry[tool.name] = tool
    }

    fun get(name: String): Tool? {
        return registry[name]
    }

    fun getAll(): Collection<Tool> {
        return registry.values
    }

    fun getManifestJson(): JsonObject {
        val manifest = JsonObject()
        for (tool in registry.values) {
            val toolObj = JsonObject().apply {
                addProperty("name", tool.name)
                addProperty("description", tool.description)
                add("parameters", tool.parameters)
            }
            manifest.add(tool.name, toolObj)
        }
        return manifest
    }
}
