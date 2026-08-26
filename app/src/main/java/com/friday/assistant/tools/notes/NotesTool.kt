package com.friday.assistant.tools.notes

import android.util.Log
import com.friday.assistant.core.FridayApplication
import com.friday.assistant.core.db.NoteEntity
import com.friday.assistant.tools.Tool
import com.friday.assistant.tools.ToolResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotesTool : Tool {

    companion object {
        private const val TAG = "NotesTool"
    }

    override val name: String = "notes_control"

    override val description: String = """
        Manages personal notes: creating new notes, listing saved notes, searching through notes, 
        and deleting specific notes by ID.
    """.trimIndent()

    override val parameters: JsonObject = JsonParser.parseString("""
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["create", "list", "search", "delete"],
              "description": "The note action to perform"
            },
            "content": {
              "type": "string",
              "description": "The text content of the note (required for 'create' action)"
            },
            "tags": {
              "type": "string",
              "description": "Optional comma-separated tags for the note (only used for 'create' action)"
            },
            "query": {
              "type": "string",
              "description": "The search keyword to look up inside notes (required for 'search' action)"
            },
            "note_id": {
              "type": "integer",
              "description": "The unique numerical ID of the note to delete (required for 'delete' action)"
            }
          },
          "required": ["action"]
        }
    """).asJsonObject

    private val dao = FridayApplication.database.dao()

    override suspend fun execute(args: JsonObject): ToolResult {
        val action = args.get("action")?.asString ?: return ToolResult(false, "Missing required parameter: action")
        
        return try {
            when (action) {
                "create" -> {
                    val content = args.get("content")?.asString
                        ?: return ToolResult(false, "Missing parameter 'content' for action 'create'")
                    val tags = args.get("tags")?.asString ?: ""
                    createNote(content, tags)
                }
                "list" -> listNotes()
                "search" -> {
                    val query = args.get("query")?.asString
                        ?: return ToolResult(false, "Missing parameter 'query' for action 'search'")
                    searchNotes(query)
                }
                "delete" -> {
                    val id = args.get("note_id")?.asLong
                        ?: return ToolResult(false, "Missing parameter 'note_id' for action 'delete'")
                    deleteNote(id)
                }
                else -> ToolResult(false, "Unknown note action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing notes action: $action", e)
            ToolResult(false, "Notes control execution failed: ${e.message}")
        }
    }

    private suspend fun createNote(content: String, tags: String): ToolResult {
        val note = NoteEntity(content = content, tags = tags)
        val noteId = dao.insertNote(note)
        return ToolResult(true, "Saved note with ID: $noteId")
    }

    private suspend fun listNotes(): ToolResult {
        val notes = dao.getAllNotes().first()
        if (notes.isEmpty()) {
            return ToolResult(true, "You do not have any saved notes.")
        }
        
        val sdf = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
        val sb = StringBuilder("Your saved notes:\n")
        notes.forEach { note ->
            val dateStr = sdf.format(Date(note.timestamp))
            sb.append("- [ID: ${note.id}] ($dateStr) tags: [${note.tags}]: \"${note.content}\"\n")
        }
        return ToolResult(true, sb.toString())
    }

    private suspend fun searchNotes(rawQuery: String): ToolResult {
        val stopWords = setOf(
            "what", "is", "my", "the", "a", "an", "for", "of", "in", "to",
            "tell", "me", "about", "show", "search", "notes", "note", "check",
            "do", "you", "remember", "whats", "what's"
        )
        val cleanTerms = rawQuery.lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 1 && it !in stopWords }

        val allNotes = dao.getAllNotes().first()
        if (allNotes.isEmpty()) {
            return ToolResult(true, "You do not have any saved notes.")
        }

        // Rank notes by relevance to keywords
        val scoredNotes = allNotes.map { note ->
            val contentLower = note.content.lowercase(Locale.getDefault())
            val tagsLower = note.tags.lowercase(Locale.getDefault())
            var score = 0

            // Exact phrase match
            if (rawQuery.isNotBlank() && contentLower.contains(rawQuery.lowercase(Locale.getDefault()))) {
                score += 100
            }

            // Keyword matches
            for (term in cleanTerms) {
                if (contentLower.contains(term) || tagsLower.contains(term)) {
                    score += 30
                } else {
                    // Check typo / fuzzy match (Levenshtein distance <= 2 for words >= 5 chars)
                    val words = contentLower.split(Regex("\\s+"))
                    if (words.any { w -> w.length >= 4 && kotlin.math.abs(w.length - term.length) <= 2 && isFuzzyMatch(w, term) }) {
                        score += 20
                    }
                }
            }
            Pair(note, score)
        }.filter { it.second > 0 }
         .sortedByDescending { it.second }
         .map { it.first }

        if (scoredNotes.isEmpty()) {
            return ToolResult(true, "No notes found matching '$rawQuery'.")
        }

        if (scoredNotes.size == 1) {
            return ToolResult(true, "Your note says: ${scoredNotes.first().content}")
        }

        val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        val sb = StringBuilder("Found ${scoredNotes.size} matching notes:\n")
        scoredNotes.take(3).forEach { note ->
            val dateStr = sdf.format(Date(note.timestamp))
            sb.append("- ($dateStr): \"${note.content}\"\n")
        }
        return ToolResult(true, sb.toString().trim())
    }

    private fun isFuzzyMatch(s1: String, s2: String): Boolean {
        if (s1 == s2) return true
        if (kotlin.math.abs(s1.length - s2.length) > 2) return false
        var diff = 0
        val minLen = minOf(s1.length, s2.length)
        for (i in 0 until minLen) {
            if (s1[i] != s2[i]) diff++
            if (diff > 2) return false
        }
        diff += kotlin.math.abs(s1.length - s2.length)
        return diff <= 2
    }

    private suspend fun deleteNote(id: Long): ToolResult {
        // Find if note exists by listing and filtering (simplest without direct getById query)
        val notes = dao.getAllNotes().first()
        val noteToDelete = notes.find { it.id == id }
        
        return if (noteToDelete != null) {
            dao.deleteNote(noteToDelete)
            ToolResult(true, "Successfully deleted note with ID: $id")
        } else {
            ToolResult(false, "Could not find a note with ID: $id")
        }
    }
}
