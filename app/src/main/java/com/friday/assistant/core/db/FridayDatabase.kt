package com.friday.assistant.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MemoryEntity::class,
        NoteEntity::class,
        RoutineEntity::class,
        ReminderEntity::class,
        InterestEntity::class,
        BriefItemEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class FridayDatabase : RoomDatabase() {

    abstract fun dao(): FridayDao

    companion object {
        @Volatile
        private var INSTANCE: FridayDatabase? = null

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `reminders` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `requestCode` INTEGER NOT NULL,
                        `message` TEXT NOT NULL,
                        `triggerAtMs` INTEGER NOT NULL,
                        `isCompleted` INTEGER NOT NULL DEFAULT 0,
                        `createdAtMs` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_triggerAtMs` ON `reminders` (`triggerAtMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_requestCode` ON `reminders` (`requestCode`)")
            }
        }

        fun getDatabase(context: Context): FridayDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    FridayDatabase::class.java,
                    "friday_database"
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration(dropAllTables = false)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
