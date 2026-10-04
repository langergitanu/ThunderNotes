package com.thundernotes.data.repository

import android.content.Context
import com.thundernotes.ThunderNotesApp
import com.thundernotes.data.db.AppDatabase

/**
 * Manual DI container — provides singletons of the repositories to activities
 * and fragments.
 *
 * **Why manual and not Hilt (yet):** Hilt adds ~30s of KSP compile time per
 * build and a learning curve. For the personal-use build, a manual singleton
 * object is simpler and works just as well. We can swap to Hilt in phase 5+
 * when the dependency graph grows past ~10 classes.
 *
 * Usage from an Activity/Fragment:
 * ```
 * val notesRepo = RepositoryModule.notesRepository
 * val foldersRepo = RepositoryModule.foldersRepository
 * ```
 *
 * The repositories are constructed lazily on first access; subsequent accesses
 * return the same instance.
 *
 * **Test override:** tests can call [setManualNotesRepository] /
 * [setManualFoldersRepository] etc. to inject fakes (e.g. an in-memory
 * AppDatabase via `Room.inMemoryDatabaseBuilder`).
 */
object RepositoryModule {

    private val appContext: Context
        get() = ThunderNotesApp.get().applicationContext

    private val appDatabase: AppDatabase
        get() = AppDatabase.get(appContext)

    @Volatile
    private var notesRepository: NotesRepository? = null

    @Volatile
    private var foldersRepository: FoldersRepository? = null

    @Volatile
    private var trashRepository: TrashRepository? = null

    @Volatile
    private var bookmarksRepository: BookmarksRepository? = null

    @Volatile
    private var templatesRepository: TemplatesRepository? = null

    @Volatile
    private var searchRepository: SearchRepository? = null

    val notes: NotesRepository
        get() = notesRepository ?: synchronized(this) {
            notesRepository ?: NotesRepository(appContext, appDatabase).also { notesRepository = it }
        }

    val folders: FoldersRepository
        get() = foldersRepository ?: synchronized(this) {
            foldersRepository ?: FoldersRepository(appDatabase).also { foldersRepository = it }
        }

    val trash: TrashRepository
        get() = trashRepository ?: synchronized(this) {
            trashRepository ?: TrashRepository(
                notesRepo = notes,
                foldersRepo = folders,
                appDatabase = appDatabase
            ).also { trashRepository = it }
        }

    val bookmarks: BookmarksRepository
        get() = bookmarksRepository ?: synchronized(this) {
            bookmarksRepository ?: BookmarksRepository(
                noteDao = appDatabase.noteDao(),
                folderDao = appDatabase.folderDao()
            ).also { bookmarksRepository = it }
        }

    val templates: TemplatesRepository
        get() = templatesRepository ?: synchronized(this) {
            templatesRepository ?: TemplatesRepository(appDatabase.templateDao()).also { templatesRepository = it }
        }

    val search: SearchRepository
        get() = searchRepository ?: synchronized(this) {
            searchRepository ?: SearchRepository(
                noteDao = appDatabase.noteDao(),
                folderDao = appDatabase.folderDao()
            ).also { searchRepository = it }
        }

    // ─── test overrides ────────────────────────────────────────────────────

    fun setManualNotesRepository(repo: NotesRepository) { notesRepository = repo }
    fun setManualFoldersRepository(repo: FoldersRepository) { foldersRepository = repo }
    fun setManualTrashRepository(repo: TrashRepository) { trashRepository = repo }
    fun setManualBookmarksRepository(repo: BookmarksRepository) { bookmarksRepository = repo }
    fun setManualTemplatesRepository(repo: TemplatesRepository) { templatesRepository = repo }
    fun setManualSearchRepository(repo: SearchRepository) { searchRepository = repo }

    /** For tests — clears all singletons so the next access re-creates them. */
    fun reset() {
        synchronized(this) {
            notesRepository = null
            foldersRepository = null
            trashRepository = null
            bookmarksRepository = null
            templatesRepository = null
            searchRepository = null
        }
    }
}
