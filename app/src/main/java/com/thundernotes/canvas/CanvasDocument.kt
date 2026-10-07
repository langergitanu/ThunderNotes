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
     * Add a textbox to the current page (spec §7.1). Immediate (not on the
     * undo/redo stack yet — textbox undo/redo is a refinement; the textbox is
     * a full document citizen via [PageRecord.textboxes]).
     */
    fun addTextbox(tb: TextBoxRecord) {
        _pages.getOrNull(currentPageIndex)?.textboxes?.add(tb)
    }

    /** Remove a textbox by id; returns it if found. */
    fun removeTextbox(textboxId: String): TextBoxRecord? {
        val page = _pages.getOrNull(currentPageIndex) ?: return null
        val idx = page.textboxes.indexOfFirst { it.id == textboxId }
        if (idx < 0) return null
        return page.textboxes.removeAt(idx)
    }

    /** Remove a finished stroke by id (eraser / undo of a single stroke). */
    fun removeStroke(strokeId: String): StrokeRecord? {
        val page = _pages.getOrNull(currentPageIndex) ?: return null
        val idx = page.strokes.indexOfFirst { it.id == strokeId }
        if (idx < 0) return null
        val removed = page.strokes.removeAt(idx)
        _undoStack.addLast(DocAction.RemoveStroke(page.id, removed))
        _redoStack.clear()
        return removed
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
            is DocAction.AddPage -> {
                // Only remove if it's still the last + empty (no subsequent edits).
                if (_pages.lastIndex == action.pageIndex &&
                    _pages.getOrNull(action.pageIndex)?.id == action.pageId &&
                    _pages[action.pageIndex].strokes.isEmpty()) {
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
}

/** Sealed undo/redo action — the document's edit log entry. */
sealed class DocAction {
    abstract val pageId: String
    data class AddStroke(override val pageId: String, val stroke: StrokeRecord) : DocAction()
    data class RemoveStroke(override val pageId: String, val stroke: StrokeRecord) : DocAction()
    data class AddPage(override val pageId: String, val pageIndex: Int) : DocAction()
}
