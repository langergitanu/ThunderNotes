package com.thundernotes.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.thundernotes.data.dao.FolderClosureDao
import com.thundernotes.data.dao.FolderDao
import com.thundernotes.data.dao.NoteDao
import com.thundernotes.data.dao.TemplateDao
import com.thundernotes.data.entity.FolderClosureEntity
import com.thundernotes.data.entity.FolderEntity
import com.thundernotes.data.entity.NoteEntity
import com.thundernotes.data.entity.TemplateEntity

/**
 * The app-global Room database.
 *
 * Tables here are everything the library/trash/bookmarks UI needs — i.e. the
 * metadata for every note and folder plus the closure table for the folder
 * hierarchy. The actual page/stroke/textbox content lives in a SEPARATE
 * per-note DB inside each `.thunder` ZIP file (see [NoteDatabase]).
 *
 * Pattern: single-row-per-app-singleton (lives at
 * `~/.thundernotes/thundernotes-app.db` under app-external storage).
 *
 * Forwards-compat:
 *   - `exportSchema = true` (default) writes a JSON schema per version under
 *     `app/schemas/` so migrations can be auto-validated. We will commit those
 *     schemas as the schema evolves.
 *   - `fallbackToDestructiveMigrationOnDowngrade` so a downgrade during
 *     development doesn't brick the DB. (We will write proper upward migrations
 *     once v2 lands.)
 */
@Database(
    entities = [
        NoteEntity::class,
        FolderEntity::class,
        FolderClosureEntity::class,
        TemplateEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun folderDao(): FolderDao
    abstract fun folderClosureDao(): FolderClosureDao
    abstract fun templateDao(): TemplateDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        /** For tests + tools that want to inject a different DB. */
        fun setManualInstance(db: AppDatabase) {
            INSTANCE = db
        }

        /** For tests — close the singleton. */
        fun closeSingleton() {
            INSTANCE?.let {
                if (it.isOpen) it.close()
                INSTANCE = null
            }
        }

        const val DB_NAME = "thundernotes-app.db"
    }
}
