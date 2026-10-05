package com.thundernotes.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thundernotes.data.db.AppDatabase
import com.thundernotes.data.entity.NoteEntity
import com.thundernotes.data.entity.PageOrientation
import com.thundernotes.data.entity.PageType
import com.thundernotes.data.repository.FoldersRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Exercises the Room data layer (entities + DAOs + repositories) end-to-end
 * against an in-memory Room DB.
 *
 * **Why this test exists:** BigPickle's Sync 2 round 3 report (BigPickle.txt
 * "Open Issue 1") flagged that the Room layer was compiled-but-never-executed:
 * `RepositoryModule` was referenced only in its own KDoc, `/data/data/com.thundernotes/databases/`
 * didn't exist on device, and all 13 existing tests covered only `format/`. This
 * test exercises entities + DAOs + Room wiring together so a DAO bug fails HERE
 * (in a unit test) rather than in Phase 5 UI crashes.
 *
 * **What's covered:**
 *   - AppDatabase construction (in-memory via `Room.inMemoryDatabaseBuilder`)
 *   - FolderEntity + FolderDao + FolderClosureDao (closure-table transactions)
 *   - NoteEntity + NoteDao (CRUD + bookmark + recycle bin + permanent delete)
 *   - FoldersRepository (createFolder + closure maintenance + moveFolder + delete)
 *   - NoteDao.searchByTitle (title-only LIKE search)
 *
 * **What's NOT covered (deferred to later phases):**
 *   - NoteDatabase (per-note DB inside .thunder ZIP) — needs filesystem staging;
 *     will be tested in Phase 3+ via `ThunderFileRoundTripTest` extension.
 *   - NotesRepository.createNote/openNote/saveNote (involves .thunder ZIP I/O;
 *     will be tested when Phase 5 UI can actually drive the create→open→save flow).
 *   - TrashRepository / BookmarksRepository / SearchRepository combined flows
 *     (depend on the above; will be added incrementally).
 *
 * Run with: `./gradlew :app:testDebugUnitTest --tests *.AppDatabaseTest`
 *
 * Robolectric SDK note: `@Config(sdk = [33])` because Robolectric 4.13 supports
 * up to SDK 33; our minSdk is 31 so this is fine. When Robolectric 4.14+ is
 * available, bump to [34] or [36].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AppDatabaseTest {

    private lateinit var db: AppDatabase
    private lateinit var foldersRepo: FoldersRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()  // tests only — never do this in production
            .build()
        foldersRepo = FoldersRepository(db)
    }

    @After
    fun teardown() {
        db.close()
    }

    // ─── Folder + closure-table tests ────────────────────────────────────────

    @Test
    fun `createFolder at root inserts self-closure row`() = runBlocking {
        val folderId = foldersRepo.createFolder("Test Folder", parentFolderId = null, color = 0)

        val folder = foldersRepo.getFolder(folderId)
        assertNotNull(folder)
        assertEquals("Test Folder", folder?.displayName)
        assertNull(folder?.parentFolderId)

        // Self-closure should exist (depth 0), so getAncestors (which excludes
        // self) should return an empty list.
        val ancestors = foldersRepo.getAncestors(folderId)
        assertTrue("Root folder has no ancestors (excluding self)", ancestors.isEmpty())

        // Direct child count of THIS folder should be 0 (no children yet).
        assertEquals(0, foldersRepo.getDirectChildCount(folderId))
    }

    @Test
    fun `createFolder hierarchy builds correct closure table`() = runBlocking {
        // Build a 3-level hierarchy: Root → Middle → Leaf
        val rootId = foldersRepo.createFolder("Root", parentFolderId = null)
        val middleId = foldersRepo.createFolder("Middle", parentFolderId = rootId)
        val leafId = foldersRepo.createFolder("Leaf", parentFolderId = middleId)

        // Leaf's ancestors should be [Root, Middle] in that order
        // (root first, then direct parent — getAncestors orders by depth DESC).
        val leafAncestors = foldersRepo.getAncestors(leafId)
        assertEquals(2, leafAncestors.size)
        assertEquals(rootId, leafAncestors[0])    // grandparent (root)
        assertEquals(middleId, leafAncestors[1])  // direct parent

        // Middle's ancestors should be just [Root].
        val middleAncestors = foldersRepo.getAncestors(middleId)
        assertEquals(1, middleAncestors.size)
        assertEquals(rootId, middleAncestors[0])

        // Root's ancestors should be empty (it's at root).
        val rootAncestors = foldersRepo.getAncestors(rootId)
        assertTrue("Root at top has no ancestors", rootAncestors.isEmpty())

        // Direct child counts.
        assertEquals(1, foldersRepo.getDirectChildCount(rootId))    // has Middle
        assertEquals(1, foldersRepo.getDirectChildCount(middleId))  // has Leaf
        assertEquals(0, foldersRepo.getDirectChildCount(leafId))    // leaf has none
    }

    @Test
    fun `moveFolder rebuilds closure table under new parent`() = runBlocking {
        // Build: RootA → Child, RootB (root)
        val rootAId = foldersRepo.createFolder("RootA", parentFolderId = null)
        val rootBId = foldersRepo.createFolder("RootB", parentFolderId = null)
        val childId = foldersRepo.createFolder("Child", parentFolderId = rootAId)

        // Initially, Child's ancestors = [RootA].
        assertEquals(listOf(rootAId), foldersRepo.getAncestors(childId))

        // Move Child under RootB.
        foldersRepo.moveFolder(childId, rootBId)

        // Now Child's ancestors should be [RootB] (closure rebuilt).
        val newAncestors = foldersRepo.getAncestors(childId)
        assertEquals(1, newAncestors.size)
        assertEquals(rootBId, newAncestors[0])

        // RootA should have 0 direct children now; RootB should have 1.
        assertEquals(0, foldersRepo.getDirectChildCount(rootAId))
        assertEquals(1, foldersRepo.getDirectChildCount(rootBId))
    }

    @Test
    fun `deleteFolderPermanently removes folder and its closure subtree`() = runBlocking {
        val rootId = foldersRepo.createFolder("Root", parentFolderId = null)
        val childId = foldersRepo.createFolder("Child", parentFolderId = rootId)

        // Verify both exist + closure is wired.
        assertNotNull(foldersRepo.getFolder(rootId))
        assertNotNull(foldersRepo.getFolder(childId))
        assertEquals(1, foldersRepo.getDirectChildCount(rootId))

        // Delete the child permanently.
        foldersRepo.deleteFolderPermanently(childId)

        // Child should be gone; root should still exist with 0 children.
        assertNull(foldersRepo.getFolder(childId))
        assertNotNull(foldersRepo.getFolder(rootId))
        assertEquals(0, foldersRepo.getDirectChildCount(rootId))
    }

    // ─── Note CRUD + recycle bin tests ───────────────────────────────────────

    @Test
    fun `NoteDao CRUD + bookmark + recycle bin flow`() = runBlocking {
        val noteDao = db.noteDao()
        val now = System.currentTimeMillis()

        // ── Insert ──────────────────────────────────────────────────────────
        val note = NoteEntity(
            noteId = "test-note-1",
            parentFolderId = null,
            displayName = "Test Note",
            filePath = "/tmp/test-note-1.thunder",
            coverPath = null,
            color = 0,
            pageCount = 1,
            fileSizeBytes = 1024L,
            defaultPageType = PageType.BLANK.rawValue,
            defaultOrientation = PageOrientation.PORTRAIT.rawValue,
            createdTime = now,
            modifiedTime = now,
            syncTimestamp = now
        )
        noteDao.insert(note)

        // ── Read back ───────────────────────────────────────────────────────
        val fetched = noteDao.getByNoteId("test-note-1")
        assertNotNull(fetched)
        assertEquals("Test Note", fetched?.displayName)
        assertFalse(fetched?.isBookmarked ?: true)
        assertNull(fetched?.recycleBinTimeMoved)

        // ── Bookmark toggle ─────────────────────────────────────────────────
        noteDao.setBookmarked("test-note-1", true)
        assertEquals(true, noteDao.getByNoteId("test-note-1")?.isBookmarked)

        // ── Trash (soft delete) ─────────────────────────────────────────────
        noteDao.moveToRecycleBin("test-note-1")
        val trashed = noteDao.getByNoteId("test-note-1")
        assertNotNull(trashed?.recycleBinTimeMoved)
        assertNotNull(trashed?.recycleBinExpiresAt)
        // moveToRecycleBin clears the bookmark (per NoteDao query).
        assertEquals(false, trashed?.isBookmarked)

        // ── Restore ─────────────────────────────────────────────────────────
        noteDao.restoreFromRecycleBin("test-note-1")
        val restored = noteDao.getByNoteId("test-note-1")
        assertNull(restored?.recycleBinTimeMoved)
        assertNull(restored?.recycleBinExpiresAt)
        // Bookmark stays cleared after restore (per NoteDao query — restore
        // doesn't re-bookmark).
        assertEquals(false, restored?.isBookmarked)

        // ── Permanent delete ───────────────────────────────────────────────
        noteDao.deleteByNoteId("test-note-1")
        assertNull(noteDao.getByNoteId("test-note-1"))
    }

    @Test
    fun `NoteDao title search returns matching notes only`() = runBlocking {
        val noteDao = db.noteDao()
        val now = System.currentTimeMillis()

        // Insert 3 notes with overlapping title words.
        listOf("Calculus notes", "Physics homework", "Calculus homework").forEachIndexed { i, name ->
            noteDao.insert(
                NoteEntity(
                    noteId = "note-$i",
                    parentFolderId = null,
                    displayName = name,
                    filePath = "/tmp/note-$i.thunder",
                    createdTime = now,
                    modifiedTime = now,
                    syncTimestamp = now
                )
            )
        }

        // Search for "Calculus" — should match 2 (Calculus notes + Calculus homework).
        val calcResults = noteDao.searchByTitle("Calculus").first()
        assertEquals(2, calcResults.size)
        assertTrue(calcResults.all { it.displayName.contains("Calculus") })

        // Search for "homework" — should match 2 (Physics homework + Calculus homework).
        val homeworkResults = noteDao.searchByTitle("homework").first()
        assertEquals(2, homeworkResults.size)

        // Search for nonexistent — should match 0.
        val noResults = noteDao.searchByTitle("nonexistent").first()
        assertTrue(noResults.isEmpty())

        // Search is case-insensitive for ASCII (SQLite LIKE default).
        val lowerResults = noteDao.searchByTitle("calculus").first()
        assertEquals(2, lowerResults.size)
    }
}
