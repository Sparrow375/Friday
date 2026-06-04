package com.friday.assistant.core

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// --- Entities ---

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long,
    val speaker: String, // "USER" or "FRIDAY"
    val message: String,
    val isOffline: Boolean
)

@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val triggerPhrase: String,
    val commandsJson: String // Serialized list of actions, e.g., [{"type":"TTS","value":"Hello"},{"type":"VOLUME","value":"50"}]
)

@Entity(tableName = "geofences")
data class GeofenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Float,
    val triggerType: String, // "ENTER" or "EXIT"
    val routineId: Int // Links to RoutineEntity.id
)

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val content: String,
    val timestamp: Long
)

// --- DAOs ---

@Dao
interface FridayDao {
    // Conversations
    @Query("SELECT * FROM conversations ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentConversations(limit: Int): Flow<List<ConversationEntity>>

    @Insert
    suspend fun insertConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations")
    suspend fun clearConversations()

    // Routines
    @Query("SELECT * FROM routines")
    fun getAllRoutines(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines WHERE triggerPhrase = :phrase LIMIT 1")
    suspend fun getRoutineByPhrase(phrase: String): RoutineEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutine(routine: RoutineEntity)

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun deleteRoutine(id: Int)

    // Geofences
    @Query("SELECT * FROM geofences")
    fun getAllGeofences(): Flow<List<GeofenceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGeofence(geofence: GeofenceEntity)

    @Query("DELETE FROM geofences WHERE id = :id")
    suspend fun deleteGeofence(id: Int)

    // Notes
    @Query("SELECT * FROM notes ORDER BY timestamp DESC")
    fun getAllNotes(): Flow<List<NoteEntity>>

    @Insert
    suspend fun insertNote(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNote(id: Int)
}

// --- Database Class ---

@Database(
    entities = [ConversationEntity::class, RoutineEntity::class, GeofenceEntity::class, NoteEntity::class],
    version = 1,
    exportSchema = false
)
abstract class FridayDatabase : RoomDatabase() {
    abstract fun fridayDao(): FridayDao

    companion object {
        @Volatile
        private var INSTANCE: FridayDatabase? = null

        fun getDatabase(context: Context): FridayDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    FridayDatabase::class.java,
                    "friday_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
