package com.thundernotes.ui.canvas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thundernotes.data.repository.NotesRepository
import com.thundernotes.data.repository.RepositoryModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    fun renameNote(newTitle: String) {
        val trimmed = newTitle.trim().ifEmpty { DEFAULT_NEW_TITLE }
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

    companion object {
        const val DEFAULT_NEW_TITLE = "Untitled"
    }
}
