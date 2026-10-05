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
 * **Schema evolution:** when this schema changes after release, we will write
 * a proper `Migration` from version N to N+1. For the unreleased initial
 * schema, `fallbackToDestructiveMigrationOnDowngrade` covers development.
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
    version = 1,
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
