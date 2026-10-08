package com.thundernotes.canvas

import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * One page of a ThunderNotes document: an id + the strokes drawn on it.
 * A page is an immutable-join: strokes have page-local coords and never
 * move when writing-space spacers shift (thunder-format-proposal Part C).
 */
data class PageRecord(
    val id: String = UUID.randomUUID().toString(),
    val strokes: MutableList<StrokeRecord> = mutableListOf(),
    val textboxes: MutableList<TextBoxRecord> = mutableListOf(),
) {
    val strokeCount: Int get() = strokes.size
    val textboxCount: Int get() = textboxes.size
}

/**
 * The pure, in-memory canvas document: an ordered list of [PageRecord]s,
 * an undo/redo stack, the current page index, and the zoom level.
 *
 * **No Android/AndroidX-Ink dependency** — fully unit-testable. The
 * [CanvasActivity][com.thundernotes.ui.canvas.CanvasActivity] + the
 * [InProgressStrokesView][androidx.ink.authoring.InProgressStrokesView]
 * host drive this document: a finished stroke → [addStroke]; the undo
 * button → [undo]; the add-page button → [addPage]; the zoom controls →
 * [setZoomPercent]. Persistence to the per-note `.thunder` SQLite DB goes
 * through [StrokeBlobCodec] + the existing `StrokeDao`.
 *
 * Undo/redo model: every mutating op pushes a sealed [DocAction] to the
 * undo stack and clears the redo stack (standard). Undo pops → applies the
 * inverse → pushes to redo. This is enough for stroke add/remove + page
 * add; richer actions (lasso move, erase-collision) extend the enum later.
 */
class CanvasDocument {

    private val _pages = mutableListOf<PageRecord>()
    val pages: List<PageRecord> get() = _pages.toList()

    private val _undoStack = ArrayDeque<DocAction>()
    private val _redoStack = ArrayDeque<DocAction>()

    var currentPageIndex: Int = 0
        private set

    var zoomPercent: Int = 100
        private set

    /** The most-recently undone action (null if the last undo was a no-op). The
     *  editor reads this to remove the undone stroke from the Ink view. */
    var lastUndoneAction: DocAction? = null
        private set

    /** The most-recently redone action (null if the last redo was a no-op). The
     *  editor reads this to re-render the redone stroke on the CompletedStrokesView. */
    var lastRedoneAction: DocAction? = null
        private set

    val totalPages: Int get() = _pages.size
    val currentPage: PageRecord? get() = _pages.getOrNull(currentPageIndex)
    val currentStrokes: List<StrokeRecord> get() = currentPage?.strokes?.toList() ?: emptyList()
    val currentTextboxes: List<TextBoxRecord> get() = currentPage?.textboxes?.toList() ?: emptyList()
    val canUndo: Boolean get() = _undoStack.isNotEmpty()
    val canRedo: Boolean get() = _redoStack.isNotEmpty()

    init {
        // A document always has at least one page.
        _pages.add(PageRecord())
    }

    // ─── mutations ─────────────────────────────────────────────────────────

    /** Add a finished stroke to the current page; record an undo action. */
    fun addStroke(stroke: StrokeRecord) {
        val page = _pages.getOrNull(currentPageIndex) ?: return
        page.strokes.add(stroke)
        _undoStack.addLast(DocAction.AddStroke(page.id, stroke))
        _redoStack.clear()
    }

    /**
     * Add a textbox to the current page (spec §7.1). Undoable — pushes a
     * [DocAction.AddTextbox] so a stray double-tap of the TEXT tool can be
     * undone (the editor removes the rendered TextView on undo + re-renders
     * it on redo).
     */
    fun addTextbox(tb: TextBoxRecord) {
        val page = _pages.getOrNull(currentPageIndex) ?: return
        page.textboxes.add(tb)
        _undoStack.addLast(DocAction.AddTextbox(page.id, tb))
        _redoStack.clear()
    }

    /** Remove a textbox by id; returns it if found. */
    fun removeTextbox(textboxId: String): TextBoxRecord? {
        val page = _pages.getOrNull(currentPageIndex) ?: return null
        val idx = page.textboxes.indexOfFirst { it.id == textboxId }
        if (idx < 0) return null
        return page.textboxes.removeAt(idx)
    }

    /**
     * Replace a textbox (by id) with [updated]. Used by the §7.1 textbox editor
     * popup on Apply — the same id is kept so the view swap is idempotent.
     * Returns true if the textbox was found + replaced.
     */
    fun updateTextbox(updated: TextBoxRecord): Boolean {
        val page = _pages.getOrNull(currentPageIndex) ?: return false
        val idx = page.textboxes.indexOfFirst { it.id == updated.id }
        if (idx < 0) return false
        page.textboxes[idx] = updated
        return true
    }

    /** Remove a finished stroke by id (eraser / undo of a single stroke).
     * Searches the CURRENT page. */
    fun removeStroke(strokeId: String): StrokeRecord? {
        val page = _pages.getOrNull(currentPageIndex) ?: return null
        val idx = page.strokes.indexOfFirst { it.id == strokeId }
        if (idx < 0) return null
        val removed = page.strokes.removeAt(idx)
        _undoStack.addLast(DocAction.RemoveStroke(page.id, removed))
        _redoStack.clear()
        return removed
    }

    /**
     * Remove a finished stroke by id from a SPECIFIC page (found by pageId —
     * the eraser's page-aware path; the visible page and the document's
     * current page can briefly diverge while scrolling). Records the undo
     * action against the real page. Returns the removed record or null.
     */
    fun removeStrokeFromPage(pageId: String, strokeId: String): StrokeRecord? {
        if (pageId.isBlank()) return removeStroke(strokeId)
        val page = _pages.firstOrNull { it.id == pageId } ?: return null
        val idx = page.strokes.indexOfFirst { it.id == strokeId }
        if (idx < 0) return null
        val removed = page.strokes.removeAt(idx)
        _undoStack.addLast(DocAction.RemoveStroke(page.id, removed))
        _redoStack.clear()
        return removed
    }

    /** Replace a stroke (matched by id) in place — used by lasso transforms
     *  (Rotate/Scale/Flip/ChangeColor/ChangeThickness) which produce a new
     *  [StrokeRecord] with the same id. Immediate (not on the undo stack —
     *  transform undo is a refinement). Returns true if a stroke was replaced. */
    fun replaceStroke(newRecord: StrokeRecord): Boolean {
        val page = _pages.getOrNull(currentPageIndex) ?: return false
        val idx = page.strokes.indexOfFirst { it.id == newRecord.id }
        if (idx < 0) return false
        page.strokes[idx] = newRecord
        return true
    }

    /** Append a blank page + make it the current page. Returns the new index. */
    fun addPage(): Int {
        _pages.add(PageRecord())
        val newIdx = _pages.lastIndex
        _undoStack.addLast(DocAction.AddPage(_pages.last().id, newIdx))
        _redoStack.clear()
        currentPageIndex = newIdx
        return newIdx
    }

    fun setZoomPercent(pct: Int) {
        zoomPercent = min(200, max(50, pct))
    }

    // ─── navigation ─────────────────────────────────────────────────────────

    fun nextPage(): Boolean = if (currentPageIndex < _pages.lastIndex) {
        currentPageIndex++; true
    } else false

    fun previousPage(): Boolean = if (currentPageIndex > 0) {
        currentPageIndex--; true
    } else false

    fun goToPage(index: Int) {
        currentPageIndex = index.coerceIn(0, _pages.lastIndex.coerceAtLeast(0))
    }

    // ─── undo / redo ────────────────────────────────────────────────────────

    fun undo(): Boolean {
        val action = _undoStack.removeLastOrNull() ?: run {
            lastUndoneAction = null
            return false
        }
        when (action) {
            is DocAction.AddStroke -> {
                val page = _pages.firstOrNull { it.id == action.pageId }
                page?.strokes?.removeAll { it.id == action.stroke.id }
            }
            is DocAction.RemoveStroke -> {
                val page = _pages.firstOrNull { it.id == action.pageId }
                page?.strokes?.add(action.stroke)
            }
            is DocAction.AddTextbox -> {
                val page = _pages.firstOrNull { it.id == action.pageId }
                page?.textboxes?.removeAll { it.id == action.textbox.id }
            }
            is DocAction.AddPage -> {
                // Only remove if it's still the last + empty (no subsequent edits).
                if (_pages.lastIndex == action.pageIndex &&
                    _pages.getOrNull(action.pageIndex)?.id == action.pageId &&
                    _pages[action.pageIndex].strokes.isEmpty() &&
                    _pages[action.pageIndex].textboxes.isEmpty()) {
                    _pages.removeAt(action.pageIndex)
                    currentPageIndex = (action.pageIndex - 1).coerceAtLeast(0)
                }
            }
        }
        _redoStack.addLast(action)
        lastUndoneAction = action
        return true
    }

    fun redo(): Boolean {
        val action = _redoStack.removeLastOrNull() ?: run {
            lastRedoneAction = null
            return false
        }
        when (action) {
            is DocAction.AddStroke -> {
                val page = _pages.firstOrNull { it.id == action.pageId }
                page?.strokes?.add(action.stroke)
            }
            is DocAction.RemoveStroke -> {
                val page = _pages.firstOrNull { it.id == action.pageId }
                page?.strokes?.removeAll { it.id == action.stroke.id }
            }
            is DocAction.AddTextbox -> {
                val page = _pages.firstOrNull { it.id == action.pageId }
                page?.textboxes?.add(action.textbox)
            }
            is DocAction.AddPage -> {
                if (_pages.none { it.id == action.pageId }) {
                    _pages.add(PageRecord(action.pageId))
                    currentPageIndex = _pages.lastIndex
                }
            }
        }
        _undoStack.addLast(action)
        lastRedoneAction = action
        return true
    }

    /** Clear all undo/redo history (called after a save snapshot). */
    fun clearHistory() {
        _undoStack.clear()
        _redoStack.clear()
    }

    /** All strokes across all pages, for a save snapshot. */
    fun allStrokes(): List<StrokeRecord> = _pages.flatMap { it.strokes.toList() }

    /**
     * Replace the whole page list (load-from-`.thunder` path — the editor
     * rebuilds its page hosts from the DB). Clears undo history: the loaded
     * state is the new baseline.
     */
    fun resetWith(pages: List<PageRecord>) {
        _pages.clear()
        _pages.addAll(pages)
        if (_pages.isEmpty()) _pages.add(PageRecord())
        _undoStack.clear()
        _redoStack.clear()
        currentPageIndex = 0
    }

    /** Index of the page with [pageId], or -1. */
    fun indexOfPage(pageId: String): Int =
        _pages.indexOfFirst { it.id == pageId }
}

/** Sealed undo/redo action — the document's edit log entry. */
sealed class DocAction {
    abstract val pageId: String
    data class AddStroke(override val pageId: String, val stroke: StrokeRecord) : DocAction()
    data class RemoveStroke(override val pageId: String, val stroke: StrokeRecord) : DocAction()
    data class AddTextbox(override val pageId: String, val textbox: TextBoxRecord) : DocAction()
    data class AddPage(override val pageId: String, val pageIndex: Int) : DocAction()
}
