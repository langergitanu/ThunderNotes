package com.thundernotes.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.thundernotes.data.dao.CommentDao
import com.thundernotes.data.dao.HyperLinkDao
import com.thundernotes.data.dao.ImageDao
import com.thundernotes.data.dao.LayerDao
import com.thundernotes.data.dao.NoteContentDao
import com.thundernotes.data.dao.NotePageDao
import com.thundernotes.data.dao.OutlineDao
import com.thundernotes.data.dao.PdfInfoDao
import com.thundernotes.data.dao.ShapeDao
import com.thundernotes.data.dao.SpacerDao
import com.thundernotes.data.dao.StrokeDao
import com.thundernotes.data.dao.TextBoxDao
import com.thundernotes.data.entity.CommentEntity
import com.thundernotes.data.entity.HyperLinkEntity
import com.thundernotes.data.entity.ImageEntity
import com.thundernotes.data.entity.NoteContentEntity
import com.thundernotes.data.entity.NotePageEntity
import com.thundernotes.data.entity.OutlineEntity
import com.thundernotes.data.entity.PageLayerEntity
import com.thundernotes.data.entity.PdfInfoEntity
import com.thundernotes.data.entity.ShapeEntity
import com.thundernotes.data.entity.SpacerEntity
import com.thundernotes.data.entity.StrokeEntity
import com.thundernotes.data.entity.TextBoxEntity
import java.io.File

/**
 * Per-note Room database.
 *
 * ── NEWCOMER PRIMER: what is Room? ──────────────────────────────────────
 * Room is Google's SQLite object-mapping library. You write three things:
 *   1. **@Entity data classes** (see `data/entity/`) — each maps to one SQL
 *      table; each property is a column (`@ColumnInfo(name=…)`), one
 *      property is the `@PrimaryKey`.
 *   2. **@Dao interfaces** (see `data/dao/`) — method declarations that Room
 *      turns into SQL at compile time (`@Query("SELECT …")`, `@Insert` …).
 *      Compile-time-checked: a typo in a column name fails the BUILD, not
 *      the runtime.
 *   3. **@Database class** (this file) — declares the entity list + version
 *      + exposes the DAOs. `Room.databaseBuilder(...).build()` opens the DB.
 * **Migrations:** when you add/rename a column you bump the DB `version`
 * and write a `Migration(old, new)` with the `ALTER TABLE` SQL — otherwise
 * Room throws `IllegalStateException` on existing files. Schema JSON is
 * exported to `app/schemas/` (see `ksp { room.schemaLocation }` in
 * build.gradle.kts) so each version is diffable/reviewable.
 *
 * ── What this particular DB is ───────────────────────────────────────────
 * Lives INSIDE the `.thunder` ZIP file as `note.sqlite`. There is one
 * NoteDatabase per open note; it is opened from the extracted `note.sqlite`
 * file by `format.ThunderFile.openForEditing()` (phase 3+) which:
 *
 *   1. Extracts `note.sqlite` from the `.thunder` ZIP to a staging file.
 *   2. Opens it via [open] (this class).
 *   3. Lets the canvas + ink pipeline mutate it.
 *   4. WAL-checkpoints the DB (see `setJournalMode(TRUNCATE)` rationale below).
 *   5. Re-zips `note.sqlite` back into the `.thunder` file with the updated
 *      `manifest.json` checksum.
 *
 * **Why TRUNCATE journal mode:** the default WAL mode produces a `-wal` file
 * alongside the `.sqlite`. When re-zipped, we'd need to also include the
 * `-wal` file AND `checkpoint` it first. Simpler to use TRUNCATE journal mode
 * (single-file DB) so the ZIP always carries exactly `note.sqlite`. Slightly
 * slower for very large notes but ThunderNotes targets <1000-page notes.
 *
 * **Schema evolution:** when this schema changes after release, write a
 * proper `Migration` from version N to N+1 (see [MIGRATION_1_2] for the
 * pattern) + register it in [open]. Downgrades fall back destructively
 * (development convenience only).
 */
@Database(
    entities = [
        NoteContentEntity::class,
        NotePageEntity::class,
        PageLayerEntity::class,
        StrokeEntity::class,
        ShapeEntity::class,
        TextBoxEntity::class,
        ImageEntity::class,
        OutlineEntity::class,
        CommentEntity::class,
        HyperLinkEntity::class,
        SpacerEntity::class,
        PdfInfoEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class NoteDatabase : RoomDatabase() {
    abstract fun noteContentDao(): NoteContentDao
    abstract fun notePageDao(): NotePageDao
    abstract fun layerDao(): LayerDao
    abstract fun strokeDao(): StrokeDao
    abstract fun shapeDao(): ShapeDao
    abstract fun textBoxDao(): TextBoxDao
    abstract fun imageDao(): ImageDao
    abstract fun outlineDao(): OutlineDao
    abstract fun commentDao(): CommentDao
    abstract fun hyperLinkDao(): HyperLinkDao
    abstract fun spacerDao(): SpacerDao
    abstract fun pdfInfoDao(): PdfInfoDao

    companion object {
        /**
         * v1 → v2 (schema 2026-10): `textboxes.code_language` — stores the
         * language of a CODE-snipped textbox so its syntax highlighting
         * survives save + reload (before v2 the column didn't exist and
         * reloaded code textboxes rendered as plain text).
         */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Nullable TEXT column with no default — plain ALTER suffices.
                db.execSQL("ALTER TABLE textboxes ADD COLUMN code_language TEXT")
            }
        }

        /**
         * Open a per-note database from a staged `note.sqlite` file path.
         *
         * @param context any app context (used for Room's init)
         * @param sqliteFile the staged `note.sqlite` file extracted from the
         *                   `.thunder` ZIP. Must be writable.
         */
        fun open(context: Context, sqliteFile: File): NoteDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                NoteDatabase::class.java,
                sqliteFile.absolutePath
            )
                .setJournalMode(JournalMode.TRUNCATE)
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
        }
    }
}

/**
 * Note bootstrap (insert the single [NoteContentEntity] row + the first
 * [NotePageEntity] row + the bottom-most [PageLayerEntity] row inside a
 * single transaction) lives in `data/repository/NotesRepository.kt`
 * (phase 4) — it pairs the per-note DB writes with the corresponding
 * [com.thundernotes.data.entity.NoteEntity] insert in the app-global DB,
 * so it can't live here without dragging in cross-DB coupling.
 */
