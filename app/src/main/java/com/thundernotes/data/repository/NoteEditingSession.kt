package com.thundernotes.data.repository

import com.thundernotes.data.db.NoteDatabase
import com.thundernotes.format.ThunderManifest
import java.io.File

/**
 * An open editing session for one note.
 *
 * Created by [NotesRepository.openNote]; closed by the caller (typically via
 * `.use { session -> ... }`). Closing without calling [NotesRepository.saveNote]
 * discards in-memory changes; the staging files remain on disk until the next
 * open (which re-extracts from the .thunder, so unsaved changes are LOST).
 *
 * **Lifecycle:**
 *   1. `openNote(noteId)` → extracts the .thunder to [stagingDir], opens
 *      [noteDatabase] from the staged `note.sqlite`, returns this session.
 *   2. The canvas + ink pipeline mutate the note via the DAOs exposed by
 *      [noteDatabase].
 *   3. `saveNote(session)` → closes the NoteDatabase (TRUNCATE journal = single
 *      file), re-zips `note.sqlite` + `assets/` + `preview.png` back into the
 *      .thunder file at [thunderFile], updates the NoteEntity row in the
 *      app-global DB. Returns the new [ThunderManifest].
 *   4. `close()` → if still open, closes the NoteDatabase (without saving).
 *
 * The session is **NOT thread-safe** — one session per note per process.
 */
class NoteEditingSession internal constructor(
    /** The note's UUID (matches [com.thundernotes.data.entity.NoteEntity.noteId]). */
    val noteId: String,

    /** Staging directory under `<app-external-files-dir>/staging/<noteId>/`. */
    val stagingDir: File,

    /** Open per-note Room DB. All canvas mutations go through this. */
    val noteDatabase: NoteDatabase,

    /** The .thunder ZIP file on disk that this session was opened from. */
    val thunderFile: File,

    /** The manifest as it was when the session was opened (or after the last
     *  successful save). Updated by `NotesRepository.saveNote`. */
    internal var currentManifest: ThunderManifest
) : AutoCloseable {

    /** Read-only view of the current manifest. */
    val manifest: ThunderManifest get() = currentManifest

    /** True if the NoteDatabase is still open (i.e., `close()` has not been
     *  called by `saveNote` or by `close()`). */
    val isOpen: Boolean get() = noteDatabase.isOpen

    override fun close() {
        if (noteDatabase.isOpen) {
            noteDatabase.close()
        }
    }
}
