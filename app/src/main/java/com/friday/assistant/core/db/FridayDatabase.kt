package com.friday.assistant.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [MemoryEntity::class, NoteEntity::class, RoutineEntity::class],
    version = 1,
    exportSchema = false
)
abstract class FridayDatabase : RoomDatabase() {

    abstract fun dao(): FridayDao

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
                .fallbackToDestructiveMigration() // Destructive migration for alpha/beta phase transitions
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
