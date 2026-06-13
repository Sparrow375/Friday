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

    private suspend fun searchNotes(query: String): ToolResult {
        val notes = dao.searchNotes(query).first()
        if (notes.isEmpty()) {
            return ToolResult(true, "No notes found matching the term '$query'.")
        }
        
        val sdf = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
        val sb = StringBuilder("Search results for '$query':\n")
        notes.forEach { note ->
            val dateStr = sdf.format(Date(note.timestamp))
            sb.append("- [ID: ${note.id}] ($dateStr): \"${note.content}\"\n")
        }
        return ToolResult(true, sb.toString())
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
