package com.friday.assistant.intelligence

data class QueryResult(
    val message: String,
    val isFastTool: Boolean = false
)
