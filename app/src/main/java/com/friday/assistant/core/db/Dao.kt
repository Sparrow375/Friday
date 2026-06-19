package com.friday.assistant.core.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FridayDao {

    // ==========================================
    // Memories
    // ==========================================
    @Query("SELECT * FROM memories WHERE layer = :layer ORDER BY lastUsed DESC")
    fun getMemoriesByLayer(layer: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE category = :category AND `key` = :key LIMIT 1")
    suspend fun getMemoryByKey(category: String, key: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE layer = 'EPISODIC' ORDER BY timestamp DESC LIMIT :limit")
    fun getEpisodicMemoryHistory(limit: Int): Flow<List<MemoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Delete
    suspend fun deleteMemory(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE layer = :layer")
    suspend fun clearMemoriesByLayer(layer: String)

    @Query("UPDATE memories SET lastUsed = :timestamp WHERE id = :id")
    suspend fun updateMemoryLastUsed(id: Long, timestamp: Long = System.currentTimeMillis())

    // ==========================================
    // Notes
    // ==========================================
    @Query("SELECT * FROM notes ORDER BY timestamp DESC")
    fun getAllNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE content LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchNotes(query: String): Flow<List<NoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: NoteEntity): Long

    @Delete
    suspend fun deleteNote(note: NoteEntity)

    @Query("DELETE FROM notes")
    suspend fun clearAllNotes()

    // ==========================================
    // Routines
    // ==========================================
    @Query("SELECT * FROM routines ORDER BY lastUsed DESC")
    fun getAllRoutines(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines WHERE triggerPhrase = :triggerPhrase LIMIT 1")
    suspend fun getRoutineByTrigger(triggerPhrase: String): RoutineEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutine(routine: RoutineEntity): Long

    @Delete
    suspend fun deleteRoutine(routine: RoutineEntity)

    // ==========================================
    // Interests (Daily Briefing)
    // ==========================================
    @Query("SELECT * FROM interests ORDER BY isCustom ASC, id ASC")
    fun getAllInterestsFlow(): Flow<List<InterestEntity>>

    @Query("SELECT * FROM interests")
    suspend fun getAllInterests(): List<InterestEntity>

    @Query("SELECT * FROM interests WHERE isEnabled = 1")
    suspend fun getEnabledInterests(): List<InterestEntity>

    @Query("SELECT * FROM interests WHERE id = :id LIMIT 1")
    suspend fun getInterestById(id: String): InterestEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInterest(interest: InterestEntity): Long

    @Update
    suspend fun updateInterest(interest: InterestEntity)

    @Delete
    suspend fun deleteInterest(interest: InterestEntity)

    // ==========================================
    // Brief Items (Daily Briefing)
    // ==========================================
    @Query("SELECT * FROM brief_items WHERE status = 'new' ORDER BY pubDate DESC")
    fun getNewBriefItemsFlow(): Flow<List<BriefItemEntity>>

    @Query("SELECT * FROM brief_items WHERE interestId = :interestId ORDER BY pubDate DESC")
    fun getBriefItemsByInterestFlow(interestId: String): Flow<List<BriefItemEntity>>

    @Query("SELECT * FROM brief_items WHERE status = 'new' ORDER BY pubDate DESC LIMIT :limit")
    suspend fun getNewBriefItems(limit: Int): List<BriefItemEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBriefItem(item: BriefItemEntity): Long

    @Query("UPDATE brief_items SET status = :status WHERE id = :id")
    suspend fun updateBriefItemStatus(id: Long, status: String)

    @Query("DELETE FROM brief_items WHERE pubDate < :cutoffTime")
    suspend fun deleteOldBriefItems(cutoffTime: Long)
}
