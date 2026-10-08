package com.thundernotes.ui.canvas

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thundernotes.canvas.CanvasRecordMappers
import com.thundernotes.canvas.CanvasRecordMappers.toEntity
import com.thundernotes.canvas.CanvasRecordMappers.toRecord
import com.thundernotes.canvas.StrokeRecord
import com.thundernotes.canvas.TextBoxRecord
import com.thundernotes.data.entity.PageOrientation
import com.thundernotes.data.entity.PageType
import com.thundernotes.data.repository.NoteEditingSession
import com.thundernotes.data.repository.NotesRepository
import com.thundernotes.data.repository.RepositoryModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * ViewModel backing [CanvasActivity] — the full-screen note editor.
 *
 * Owns the editor UI state (tool / color / stroke width / page / zoom) plus
 * the lightweight note-metadata lifecycle (load + rename). The actual stroke
 * authoring + save-to-`.thunder` pipeline is Phase 7; this VM is the
 * interaction + navigation contract today and is fully unit-testable.
 *
 * Constructed with a [NotesRepository] so tests can inject a fake. Production
 * uses [RepositoryModule.notes] via the default factory.
 */
class NoteEditorViewModel(
    private val notesRepo: NotesRepository,
) : ViewModel() {

    /** Production constructor — uses the app-global [RepositoryModule.notes] singleton. */
    constructor() : this(RepositoryModule.notes)

    private val _uiState = MutableStateFlow(
        EditorUiState(noteId = "", noteTitle = "", isLoading = true)
    )
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    /**
     * Load (or implicitly create) the note identified by [noteId].
     *
     * Empty/null noteId means "open a brand-new untitled note" — the VM creates
     * one via the repository so the editor always has a real backing row.
     * If a non-empty id is given but the row was deleted concurrently, we fall
     * back to a fresh note so the editor never opens on a null document.
     */
    fun load(noteId: String?) {
        viewModelScope.launch {
            try {
                val id = noteId?.takeIf { it.isNotBlank() }
                if (id == null) {
                    openFresh()
                    return@launch
                }
                val existing = notesRepo.getNote(id)
                if (existing != null) {
                    _uiState.value = EditorUiState(
                        noteId = existing.noteId,
                        noteTitle = existing.displayName,
                        isLoading = false,
                    )
                } else {
                    openFresh()
                }
            } catch (t: Throwable) {
                _uiState.update { it.copy(isLoading = false, errorMessage = t.message ?: "load failed") }
            }
        }
    }

    private suspend fun openFresh() {
        val newId = notesRepo.createNote(displayName = DEFAULT_NEW_TITLE)
        _uiState.value = EditorUiState(
            noteId = newId,
            noteTitle = DEFAULT_NEW_TITLE,
            isLoading = false,
        )
    }

    fun selectTool(tool: EditorTool) = _uiState.update {
        // Switching away from a color-less tool (eraser/lasso) back to a
        // color-capable one restores the previously-held color index, which
        // is preserved in state. Nothing extra to do — color index stays.
        it.copy(selectedTool = tool)
    }

    fun selectColor(index: Int) {
        // Validate against the active palette's color range (8 colors).
        val palette = EditorPalette.PALETTES.getOrNull(_uiState.value.selectedPaletteIndex)
            ?: EditorPalette.PALETTES[EditorPalette.DEFAULT_PALETTE_INDEX]
        if (index !in palette.indices) return
        _uiState.update { it.copy(selectedColorIndex = index) }
    }

    /** Switch the active color palette (spec §6.10 Row 3c: ThunderDark /
     *  ThunderLight / Sunflower). Clamps the color index into the new palette's range. */
    fun selectPalette(paletteIndex: Int) {
        if (paletteIndex !in EditorPalette.PALETTES.indices) return
        val newPalette = EditorPalette.PALETTES[paletteIndex]
        _uiState.update {
            it.copy(
                selectedPaletteIndex = paletteIndex,
                selectedColorIndex = it.selectedColorIndex.coerceIn(0, newPalette.lastIndex),
            )
        }
    }

    fun setStrokeWidth(index: Int) {
        if (index !in EditorStrokeWidths.WIDTHS_DP.indices) return
        _uiState.update { it.copy(strokeWidthIndex = index) }
    }

    /** Set the pen line type (spec §6.10 Row 3a: straight / dotted / dashed). */
    fun selectLineType(type: LineType) = _uiState.update {
        it.copy(selectedLineType = type)
    }

    /** Toggle constant scaling (spec §7.5 settings popup): ON → lasso Enlarge/Reduce
     *  keeps stroke thickness; OFF → thickness scales with the resize. */
    fun setConstantScaling(on: Boolean) = _uiState.update {
        it.copy(constantScaling = on)
    }

    /** Toggle palm rejection (spec §6.1.9 — on by default). When on, the Ink host
     *  rejects FINGER touches, accepting only STYLUS/ERASER (Notein's pattern). */
    fun setPalmRejection(on: Boolean) = _uiState.update {
        it.copy(palmRejection = on)
    }

    /** Toggle the m×n gridline overlay (spec §6.10 Row 2c Gridline). */
    fun toggleGrid() = _uiState.update { it.copy(gridVisible = !it.gridVisible) }
    fun setGridVisible(on: Boolean) = _uiState.update { it.copy(gridVisible = on) }

    /** Set the gridline m×n (rows × cols). Clamped to a sane 1..40 range. */
    fun setGridSize(rows: Int, cols: Int) = _uiState.update {
        it.copy(
            gridRows = rows.coerceIn(1, 40),
            gridCols = cols.coerceIn(1, 40),
        )
    }

    /** Set the active shape type for the SHAPE tool (spec §6.10 Row 3g). */
    fun setShapeType(type: ShapeType) = _uiState.update { it.copy(shapeType = type) }

    /** Spec §6.10.6d Eraser Type (AREA / SHAPE). */
    fun setEraserType(type: EraserType) = _uiState.update { it.copy(eraserType = type) }
    /** Spec §6.10.6d Eraser Size (reuses the stroke-width slider indices). */
    fun setEraserSize(index: Int) = _uiState.update {
        it.copy(eraserSizeIndex = index.coerceIn(0, EditorStrokeWidths.WIDTHS_DP.lastIndex))
    }
    /** Spec §6.10.6e Lasso Mode (RECT / FREEFORM). */
    fun setLassoMode(mode: LassoMode) = _uiState.update { it.copy(lassoMode = mode) }

    fun nextPage() = _uiState.update {
        val next = (it.currentPageIndex + 1).coerceAtMost((it.totalPages - 1).coerceAtLeast(0))
        it.copy(currentPageIndex = next)
    }

    fun previousPage() = _uiState.update {
        it.copy(currentPageIndex = (it.currentPageIndex - 1).coerceAtLeast(0))
    }

    /** Jump directly to a page (pages sidebar / minimap tap, canvasUtilityPage).
     *  Clamps into the valid page range. */
    fun goToPage(index: Int) = _uiState.update {
        it.copy(currentPageIndex = index.coerceIn(0, (it.totalPages - 1).coerceAtLeast(0)))
    }

    fun addPage() = _uiState.update {
        val total = it.totalPages + 1
        it.copy(totalPages = total, currentPageIndex = total - 1)
    }

    fun zoomIn() = _uiState.update {
        it.copy(zoomPercent = (it.zoomPercent + EditorZoom.STEP_PERCENT)
            .coerceAtMost(EditorZoom.MAX_PERCENT))
    }

    fun zoomOut() = _uiState.update {
        it.copy(zoomPercent = (it.zoomPercent - EditorZoom.STEP_PERCENT)
            .coerceAtLeast(EditorZoom.MIN_PERCENT))
    }

    /** Reset zoom to the default 100% (spec §6.10 Canvas Area: 100% Fit button). */
    fun resetZoom() = _uiState.update {
        it.copy(zoomPercent = EditorZoom.DEFAULT_PERCENT)
    }

    fun renameNote(newTitle: String) {
        val trimmed = newTitle.trim().ifEmpty { DEFAULT_NEW_TITLE }
        // No-op when unchanged (focus-loss fires on every blur — don't write
        // the DB + emit a redundant state update each time).
        if (trimmed == _uiState.value.noteTitle) return
        _uiState.update { it.copy(noteTitle = trimmed) }
        val id = _uiState.value.noteId
        if (id.isNotBlank()) {
            viewModelScope.launch {
                try { notesRepo.renameNote(id, trimmed) }
                catch (_: Throwable) { /* surfaced via the DB flow on next load */ }
            }
        }
    }

    fun markUndoAvailable(can: Boolean) = _uiState.update { it.copy(canUndo = can) }
    fun markRedoAvailable(can: Boolean) = _uiState.update { it.copy(canRedo = can) }

    /** Keep the VM's page counters in sync with the document (load / add-page undo). */
    fun syncPageCount(totalPages: Int, currentPage: Int = 0) = _uiState.update {
        it.copy(
            totalPages = totalPages.coerceAtLeast(1),
            currentPageIndex = currentPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0)),
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Persistence (spec §2.5 — Data Integrity & Auto-Save, the #1 priority:
    // "Data must not be lost under any cost").
    //
    // Architecture:
    //  1. On note open → [openSessionAndLoad] opens a NoteEditingSession
    //     (staging .sqlite) and loads its pages/strokes/textboxes.
    //  2. Every mutation writes through to the per-note DB immediately
    //     (DAO upsert/delete — incremental, O(1) per change).
    //  3. A debounced loop + onStop trigger [zipNow] which flushes the
    //     staging DB into the .thunder ZIP (atomic rename inside
    //     ThunderFile.write) and updates the NoteEntity row.
    //  4. Crash safety: if the process dies before a ZIP flush, the staging
    //     DB survives and openNote(resumeStaging=true) recovers it on the
    //     next open — no data loss window beyond the last DAO write (which
    //     is immediate).
    // ─────────────────────────────────────────────────────────────────────

    /** One loaded page from the .thunder DB. */
    data class LoadedPage(
        val pageId: String,
        val pageHeightPx: Int,
        val strokes: List<StrokeRecord>,
        val textboxes: List<TextBoxRecord>,
    )

    /** The open editing session (null until the note loads / if it failed). */
    var session: NoteEditingSession? = null
        private set

    @Volatile
    private var dirty = false

    private val zipMutex = Mutex()
    private var autoSaveJob: Job? = null

    /**
     * Open the note's .thunder DB + load all pages (strokes + textboxes).
     * Returns null on failure (the editor then runs in-memory-only mode —
     * degraded but never crashes). Must be called once after [load].
     */
    suspend fun openSessionAndLoad(noteId: String): List<LoadedPage>? {
        return try {
            val s = notesRepo.openNote(noteId, resumeStaging = true)
            session = s
            val db = s.noteDatabase
            db.notePageDao().getAllOrdered().map { page ->
                LoadedPage(
                    pageId = page.pageId,
                    pageHeightPx = page.pageHeightPx,
                    strokes = db.strokeDao().getByPage(page.pageId)
                        .mapNotNull { it.toRecord() },
                    textboxes = db.textBoxDao().getByPage(page.pageId)
                        .map { it.toRecord() },
                )
            }.ifEmpty {
                // A DB with no page rows (shouldn't happen — createNote
                // bootstraps one) — fall back to one blank page.
                listOf(LoadedPage(pageId = UUID.randomUUID().toString(),
                    pageHeightPx = 0, strokes = emptyList(), textboxes = emptyList()))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "openSessionAndLoad failed — editor runs memory-only", t)
            null
        }
    }

    /** Launch the debounced auto-ZIP loop (call once after load). */
    fun startAutoSaveLoop() {
        if (autoSaveJob?.isActive == true) return
        autoSaveJob = viewModelScope.launch {
            while (true) {
                delay(AUTOSAVE_INTERVAL_MS)
                if (dirty) runCatching { zipNow() }
            }
        }
    }

    /**
     * Flush the staging DB into the .thunder ZIP (atomic). Re-opens the
     * session afterwards so DAO writes can continue. Safe to call anytime.
     */
    suspend fun zipNow() = zipMutex.withLock {
        val s = session ?: return@withLock false
        if (!s.noteDatabase.isOpen) return@withLock false
        val noteId = s.noteId
        try {
            val newManifest = notesRepo.saveNote(s)
            // saveNote closed the DB — re-open WITHOUT re-extracting (the
            // staging dir now matches the ZIP byte-for-byte).
            session = notesRepo.openNote(noteId, resumeStaging = true)
            dirty = false
            Log.d(TAG, "auto-save zip complete: $noteId pages=${newManifest.pageCount}")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "auto-save zip failed (will retry): $noteId", t)
            // The DB is closed after a failed saveNote — re-open to continue.
            runCatching {
                session = notesRepo.openNote(noteId, resumeStaging = true)
            }
            false
        }
    }

    /** True when there are unsaved DAO writes (zip pending). */
    val hasUnsavedChanges: Boolean get() = dirty

    // ─── incremental write-through persistence ops ───────────────────────

    private fun persist(block: suspend (NoteEditingSession) -> Unit) {
        val s = session ?: return
        if (!s.noteDatabase.isOpen) return
        viewModelScope.launch(Dispatchers.IO) {
            // The auto-ZIP loop closes + re-opens the DB (saveNote closes it).
            // A write that races the close fails — retry on the NEW session
            // instead of dropping the mutation (spec §2.5: never lose data).
            var attempt = 0
            while (attempt < 5) {
                val current = session
                if (current == null) return@launch
                if (!current.noteDatabase.isOpen) {
                    delay(250); attempt++; continue
                }
                try {
                    block(current)
                    dirty = true
                    return@launch
                } catch (t: Throwable) {
                    Log.w(TAG, "persist op failed (attempt ${attempt + 1}/5)", t)
                    delay(250)
                    attempt++
                }
            }
        }
    }

    /** The page's bottom-most layer id (strokes/textboxes are single-layer
     *  for now — the schema supports more). */
    private suspend fun realLayerId(s: NoteEditingSession, pageId: String): String? =
        runCatching {
            s.noteDatabase.layerDao().getBottomLayer(pageId)?.layerId
        }.getOrNull()

    fun persistStrokeUpsert(record: StrokeRecord) = persist { s ->
        s.noteDatabase.strokeDao().upsert(record.toEntity(realLayerId(s, record.pageId)))
    }

    fun persistStrokeRemove(pageId: String, strokeId: String) = persist { s ->
        s.noteDatabase.strokeDao().deleteByStrokeId(strokeId)
    }

    fun persistTextboxUpsert(tb: TextBoxRecord) = persist { s ->
        s.noteDatabase.textBoxDao().upsert(tb.toEntity(realLayerId(s, tb.pageId)))
    }

    fun persistTextboxRemove(textboxId: String) = persist { s ->
        s.noteDatabase.textBoxDao().deleteByTextBoxId(textboxId)
    }

    /** Persist a new page row (+ its bottom layer) — [pageId] must be the
     *  SAME id the in-memory document uses so strokes join up on reload. */
    fun persistPageAdd(pageId: String, pageIndex: Int, pageHeightPx: Int) = persist { s ->
        val now = System.currentTimeMillis()
        val layerId = UUID.randomUUID().toString()
        s.noteDatabase.notePageDao().insert(
            com.thundernotes.data.entity.NotePageEntity(
                pageId = pageId,
                pageIndex = pageIndex,
                pageType = PageType.BLANK.rawValue,
                orientation = PageOrientation.PORTRAIT.rawValue,
                pageRatio = 0.707f,
                pageHeightPx = pageHeightPx,
                pageBackgroundColor = 0xFFFFFFFF.toInt(),
                createdTime = now,
                modifiedTime = now,
            )
        )
        s.noteDatabase.layerDao().insert(
            com.thundernotes.data.entity.PageLayerEntity(
                layerId = layerId,
                pageId = pageId,
                layerName = "Layer 1",
                isVisible = true,
                isLocked = false,
                opacity = 1.0f,
                sortOrder = 0,
                createdTime = now,
                modifiedTime = now,
            )
        )
    }

    /** Persist a page deletion (page row + layer + all its content). */
    fun persistPageDelete(pageId: String) = persist { s ->
        val db = s.noteDatabase
        db.strokeDao().deleteByPageId(pageId)
        db.textBoxDao().deleteByPageId(pageId)
        db.layerDao().deleteByPageId(pageId)
        db.notePageDao().deleteByPageId(pageId)
    }

    /** Persist a page-height change (Add Writing Space §7.4 grows the page). */
    fun persistPageHeight(pageId: String, newHeightPx: Int) = persist { s ->
        val dao = s.noteDatabase.notePageDao()
        val page = dao.getByPageId(pageId) ?: return@persist
        dao.update(page.copy(pageHeightPx = newHeightPx,
            modifiedTime = System.currentTimeMillis()))
    }

    /** Persist a §7.4 writing-space spacer row. */
    fun persistSpacer(pageId: String, offsetInPage: Float, heightPx: Float) = persist { s ->
        s.noteDatabase.spacerDao().insert(
            com.thundernotes.data.entity.SpacerEntity(
                anchorPageId = pageId,
                offsetInPage = offsetInPage,
                height = heightPx,
            )
        )
    }

    companion object {
        const val DEFAULT_NEW_TITLE = "Untitled"
        private const val TAG = "NoteEditorViewModel"

        /** Debounce window for the auto-ZIP loop (spec §2.5 auto-save). */
        private const val AUTOSAVE_INTERVAL_MS = 10_000L
    }
}
