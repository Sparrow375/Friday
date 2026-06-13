package com.friday.assistant.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memories",
    indices = [
        Index(value = ["layer"]),
        Index(value = ["category", "key"], unique = true)
    ]
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val layer: String, // WORKING, EPISODIC, SEMANTIC, PROCEDURAL
    val category: String, // e.g., "user_preference", "user_fact", "system_state"
    val key: String,      // e.g., "name", "favorite_color", "workplace"
    val value: String,    // content or JSON representations
    val confidence: Float = 1.0f,
    val lastUsed: Long = System.currentTimeMillis(),
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val tags: String = "" // comma-separated tags
)

@Entity(
    tableName = "routines",
    indices = [Index(value = ["triggerPhrase"], unique = true)]
)
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val triggerPhrase: String,
    val actionSequenceJson: String, // JSON representing step lists
    val successCount: Int = 0,
    val lastUsed: Long = System.currentTimeMillis()
)
