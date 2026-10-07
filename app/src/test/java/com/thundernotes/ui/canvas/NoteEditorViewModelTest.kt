package com.thundernotes.ui.canvas

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thundernotes.data.db.AppDatabase
import com.thundernotes.data.entity.NoteEntity
import com.thundernotes.data.entity.PageOrientation
import com.thundernotes.data.entity.PageType
import com.thundernotes.data.repository.NotesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Unit tests for [NoteEditorViewModel] — the canvas editor's interaction/state VM.
 *
 * Covers the tool state machine (color/stroke-width visibility per tool),
 * page navigation bounds, zoom bounds, and the load/rename note lifecycle
 * via an in-memory fake repository. Phase 6 contract.
 *
 * Run: `./gradlew :app:testDebugUnitTest --tests *.NoteEditorViewModelTest`
 *
 * Robolectric SDK pinned to 33 (matches the rest of the suite; 4.13 ceiling).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var fake: FakeNotesRepository
    private lateinit var vm: NoteEditorViewModel

    @Before
    fun setup() {
        // UnconfinedTestDispatcher runs viewModelScope.launch eagerly so the
        // state mutations in load() complete synchronously inside the test body.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fake = FakeNotesRepository(context, db)
        vm = NoteEditorViewModel(fake)
    }

    @After
    fun teardown() {
        db.close()
        Dispatchers.resetMain()
    }

    // ─── load lifecycle ──────────────────────────────────────────────────────

    @Test
    fun `load existing note populates title and clears loading`() = runTest {
        fake.seed("abc", "Math Lecture 3")

        vm.load("abc")

        val s = vm.uiState.value
        assertEquals("abc", s.noteId)
        assertEquals("Math Lecture 3", s.noteTitle)
        assertFalse(s.isLoading)
        assertEquals(null, s.errorMessage)
    }

    @Test
    fun `load with blank id creates a new untitled note`() = runTest {
        vm.load(null)

        val s = vm.uiState.value
        assertEquals(NoteEditorViewModel.DEFAULT_NEW_TITLE, s.noteTitle)
        assertFalse(s.isLoading)
        // The created note was registered in the fake.
        assertEquals(1, fake.notes.size)
        assertEquals(s.noteId, fake.notes.keys.single())
    }

    @Test
    fun `load with unknown id falls back to creating a fresh note`() = runTest {
        vm.load("does-not-exist")

        val s = vm.uiState.value
        assertEquals(NoteEditorViewModel.DEFAULT_NEW_TITLE, s.noteTitle)
        assertFalse(s.isLoading)
    }

    // ─── tool state machine ──────────────────────────────────────────────────

    @Test
    fun `default tool is PEN and shows both color and stroke width`() {
        val s = vm.uiState.value
        assertEquals(EditorTool.PEN, s.selectedTool)
        assertTrue(s.showsColorPicker)
        assertTrue(s.showsStrokeWidth)
    }

    @Test
    fun `selecting eraser hides color picker but keeps stroke width hidden`() {
        vm.selectTool(EditorTool.ERASER)
        val s = vm.uiState.value
        assertEquals(EditorTool.ERASER, s.selectedTool)
        assertFalse(s.showsColorPicker)
        assertFalse(s.showsStrokeWidth)  // eraser has no width row either
    }

    @Test
    fun `selecting lasso hides color picker`() {
        vm.selectTool(EditorTool.LASSO)
        assertFalse(vm.uiState.value.showsColorPicker)
    }

    @Test
    fun `selecting text shows color but hides stroke width`() {
        vm.selectTool(EditorTool.TEXT)
        val s = vm.uiState.value
        assertTrue(s.showsColorPicker)
        assertFalse(s.showsStrokeWidth)
    }

    @Test
    fun `selecting shape shows both color and stroke width`() {
        vm.selectTool(EditorTool.SHAPE)
        assertTrue(vm.uiState.value.showsColorPicker)
        assertTrue(vm.uiState.value.showsStrokeWidth)
    }

    @Test
    fun `switching eraser to pen restores color picker and keeps held color index`() {
        vm.selectColor(3)            // green
        vm.selectTool(EditorTool.ERASER)
        vm.selectTool(EditorTool.PEN)
        val s = vm.uiState.value
        assertEquals(EditorTool.PEN, s.selectedTool)
        assertEquals(3, s.selectedColorIndex)
        assertTrue(s.showsColorPicker)
    }

    // ─── color + stroke width selection ──────────────────────────────────────

    @Test
    fun `selectColor with valid index updates state`() {
        vm.selectColor(2)
        assertEquals(2, vm.uiState.value.selectedColorIndex)
    }

    @Test
    fun `selectColor with out-of-range index is ignored`() {
        val before = vm.uiState.value.selectedColorIndex
        vm.selectColor(999)
        assertEquals(before, vm.uiState.value.selectedColorIndex)
        vm.selectColor(-1)
        assertEquals(before, vm.uiState.value.selectedColorIndex)
    }

    @Test
    fun `setStrokeWidth with valid index updates state`() {
        vm.setStrokeWidth(0)
        assertEquals(0, vm.uiState.value.strokeWidthIndex)
    }

    @Test
    fun `setStrokeWidth with out-of-range index is ignored`() {
        val before = vm.uiState.value.strokeWidthIndex
        vm.setStrokeWidth(99)
        assertEquals(before, vm.uiState.value.strokeWidthIndex)
    }

    // ─── palette switching (spec §6.10 Row 3c) ──────────────────────────────

    @Test
    fun `default palette is ThunderDark index 0`() {
        assertEquals(0, vm.uiState.value.selectedPaletteIndex)
    }

    @Test
    fun `selectPalette switches the active palette + selectedColorArgb resolves from it`() {
        vm.selectPalette(2)  // Sunflower
        assertEquals(2, vm.uiState.value.selectedPaletteIndex)
        vm.selectColor(0)
        assertEquals(EditorPalette.SUNFLOWER[0], vm.uiState.value.selectedColorArgb)
    }

    @Test
    fun `selectPalette clamps the color index into the new palette's range`() {
        vm.selectPalette(1)  // ThunderLight
        vm.selectColor(7)    // last index (8 colors → 0..7)
        assertEquals(7, vm.uiState.value.selectedColorIndex)
        assertEquals(EditorPalette.THUNDER_LIGHT[7], vm.uiState.value.selectedColorArgb)
    }

    @Test
    fun `selectPalette with out-of-range index is ignored`() {
        val before = vm.uiState.value.selectedPaletteIndex
        vm.selectPalette(99)
        assertEquals(before, vm.uiState.value.selectedPaletteIndex)
    }

    // ─── line type (spec §6.10 Row 3a) ──────────────────────────────────────

    @Test
    fun `default line type is STRAIGHT`() {
        assertEquals(LineType.STRAIGHT, vm.uiState.value.selectedLineType)
    }

    @Test
    fun `selectLineType switches the active line type`() {
        vm.selectLineType(LineType.DASHED)
        assertEquals(LineType.DASHED, vm.uiState.value.selectedLineType)
    }

    // ─── constant scaling (spec §7.5 settings) ─────────────────────────────

    @Test
    fun `constant scaling defaults on`() {
        assertTrue(vm.uiState.value.constantScaling)
    }

    @Test
    fun `setConstantScaling toggles the flag`() {
        vm.setConstantScaling(false)
        assertFalse(vm.uiState.value.constantScaling)
        vm.setConstantScaling(true)
        assertTrue(vm.uiState.value.constantScaling)
    }

    // ─── palm rejection (§6.1.9, on by default) ──────────────────────────

    @Test
    fun `palm rejection defaults on`() {
        assertTrue(vm.uiState.value.palmRejection)
    }

    @Test
    fun `setPalmRejection toggles the flag`() {
        vm.setPalmRejection(false)
        assertFalse(vm.uiState.value.palmRejection)
        vm.setPalmRejection(true)
        assertTrue(vm.uiState.value.palmRejection)
    }

    // ─── gridline (§6.10 Row 2c) + shape picker (§6.10 Row 3g) ────────────

    @Test
    fun `gridline is off by default`() {
        assertFalse(vm.uiState.value.gridVisible)
    }

    @Test
    fun `toggleGrid flips the grid visibility`() {
        vm.toggleGrid()
        assertTrue(vm.uiState.value.gridVisible)
        vm.toggleGrid()
        assertFalse(vm.uiState.value.gridVisible)
    }

    @Test
    fun `setGridSize clamps rows and cols into 1 to 40`() {
        vm.setGridSize(0, 100)
        assertEquals(1, vm.uiState.value.gridRows)
        assertEquals(40, vm.uiState.value.gridCols)
    }

    @Test
    fun `default shape type is RECTANGLE`() {
        assertEquals(ShapeType.RECTANGLE, vm.uiState.value.shapeType)
    }

    @Test
    fun `setShapeType switches the active shape`() {
        vm.setShapeType(ShapeType.ELLIPSE)
        assertEquals(ShapeType.ELLIPSE, vm.uiState.value.shapeType)
    }

    // ─── zoom bounds ──────────────────────────────────────────────────────────

    @Test
    fun `zoomIn never exceeds MAX_PERCENT`() {
        repeat(50) { vm.zoomIn() }
        assertEquals(EditorZoom.MAX_PERCENT, vm.uiState.value.zoomPercent)
    }

    @Test
    fun `zoomOut never drops below MIN_PERCENT`() {
        repeat(50) { vm.zoomOut() }
        assertEquals(EditorZoom.MIN_PERCENT, vm.uiState.value.zoomPercent)
    }

    @Test
    fun `zoomIn and zoomOut step by STEP_PERCENT`() {
        vm.zoomIn()
        assertEquals(EditorZoom.DEFAULT_PERCENT + EditorZoom.STEP_PERCENT, vm.uiState.value.zoomPercent)
        vm.zoomOut()
        vm.zoomOut()
        assertEquals(EditorZoom.DEFAULT_PERCENT - EditorZoom.STEP_PERCENT, vm.uiState.value.zoomPercent)
    }

    // ─── page navigation ───────────────────────────────────────────────────────

    @Test
    fun `previousPage at first page is a no-op`() {
        vm.previousPage()
        assertEquals(0, vm.uiState.value.currentPageIndex)
        assertEquals(1, vm.uiState.value.totalPages)
    }

    @Test
    fun `addPage increments total and jumps to the new page`() {
        vm.addPage()
        val s = vm.uiState.value
        assertEquals(2, s.totalPages)
        assertEquals(1, s.currentPageIndex)
    }

    @Test
    fun `nextPage at last page is a no-op`() {
        vm.addPage()   // total = 2, current = 1
        vm.nextPage()  // already last → stays
        assertEquals(1, vm.uiState.value.currentPageIndex)
        vm.previousPage()
        assertEquals(0, vm.uiState.value.currentPageIndex)
        vm.nextPage()
        assertEquals(1, vm.uiState.value.currentPageIndex)
    }

    // ─── rename ────────────────────────────────────────────────────────────────

    @Test
    fun `renameNote updates ui state title and persists via repository`() = runTest {
        fake.seed("n1", "Old")
        vm.load("n1")
        vm.renameNote("New Title")
        assertEquals("New Title", vm.uiState.value.noteTitle)
        assertEquals("n1" to "New Title", fake.lastRenamed)
    }

    @Test
    fun `renameNote with blank falls back to default untitled`() = runTest {
        fake.seed("n2", "Keep")
        vm.load("n2")
        vm.renameNote("   ")
        assertEquals(NoteEditorViewModel.DEFAULT_NEW_TITLE, vm.uiState.value.noteTitle)
    }

    // ─── undo / redo availability flags ───────────────────────────────────────

    @Test
    fun `markUndoAvailable and markRedoAvailable toggle flags`() {
        vm.markUndoAvailable(true)
        vm.markRedoAvailable(false)
        assertTrue(vm.uiState.value.canUndo)
        assertFalse(vm.uiState.value.canRedo)
    }

    // ─── fake ──────────────────────────────────────────────────────────────────

    /** In-memory NotesRepository: overrides the 3 methods the VM uses, ignores
     *  the .thunder ZIP / DAO plumbing. */
    private class FakeNotesRepository(
        context: Context,
        db: AppDatabase,
    ) : NotesRepository(context, db) {

        val notes = mutableMapOf<String, NoteEntity>()
        var lastRenamed: Pair<String, String>? = null

        fun seed(id: String, title: String) {
            notes[id] = NoteEntity(
                noteId = id,
                displayName = title,
                filePath = "",
            )
        }

        override suspend fun createNote(
            displayName: String,
            parentFolderId: String?,
            pageType: PageType,
            orientation: PageOrientation,
            color: Int,
        ): String {
            val id = UUID.randomUUID().toString()
            notes[id] = NoteEntity(
                noteId = id,
                displayName = displayName,
                parentFolderId = parentFolderId,
                filePath = "",
            )
            return id
        }

        override suspend fun getNote(noteId: String): NoteEntity? = notes[noteId]

        override suspend fun renameNote(noteId: String, newName: String) {
            lastRenamed = noteId to newName
            notes[noteId]?.let { existing ->
                notes[noteId] = existing.copy(displayName = newName)
            }
        }
    }
}
