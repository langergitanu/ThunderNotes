package com.thundernotes.ui.canvas.tabs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-scoped registry of the notes currently open in the canvas editor —
 * the multi-file tab system (spec §6.10 Row 1: "The user can open multiple
 * native files side by side — maximum 10 files at once in the explorer").
 *
 * Each open note is an [OpenNote] (id + title). The registry enforces the
 * 10-tab maximum + tracks the active tab. The CanvasActivity renders the tab
 * strip from [tabs] + switches sessions on tab click. Process-scoped
 * (survives across CanvasActivity re-launches within a session; cleared on
 * process death).
 *
 * Pure + unit-testable (kotlinx.coroutines.flow only).
 */
data class OpenNote(
    val noteId: String,
    val title: String,
    val openedAt: Long = System.currentTimeMillis(),
)

object CanvasTabs {

    private const val MAX_TABS = 10

    private val _tabs = MutableStateFlow<List<OpenNote>>(emptyList())
    val tabs: StateFlow<List<OpenNote>> = _tabs.asStateFlow()

    private var _activeNoteId: String? = null
    val activeNoteId: String? get() = _activeNoteId

    /** Open (or switch to) a note. If already open, just activates it; if at the
     *  10-tab max, the oldest tab is evicted (FIFO). Returns the active noteId. */
    fun open(noteId: String, title: String): String {
        val current = _tabs.value
        val existing = current.firstOrNull { it.noteId == noteId }
        if (existing != null) {
            _activeNoteId = noteId
            return noteId  // already open — just switch
        }
        val newNote = OpenNote(noteId, title)
        val updated = if (current.size >= MAX_TABS) {
            // Evict the oldest (first in). Never evict if it would leave 0 — but
            // we're adding one, so the result is exactly MAX_TABS.
            current.drop(1) + newNote
        } else {
            current + newNote
        }
        _tabs.value = updated
        _activeNoteId = noteId
        return noteId
    }

    /** Close a tab. If it was active, switch to the next (or previous, or null). */
    fun close(noteId: String) {
        val current = _tabs.value
        val idx = current.indexOfFirst { it.noteId == noteId }
        if (idx < 0) return
        val updated = current.toMutableList().also { it.removeAt(idx) }
        _tabs.value = updated
        _activeNoteId = if (noteId == _activeNoteId) {
            // Switch to the next tab (or the previous if this was last).
            updated.getOrNull(idx.coerceAtMost(updated.lastIndex.coerceAtLeast(0)))?.noteId
                ?: updated.lastOrNull()?.noteId
        } else {
            _activeNoteId  // active wasn't closed
        }
    }

    /** Switch the active tab. No-op if the note isn't open. */
    fun switch(noteId: String) {
        if (_tabs.value.any { it.noteId == noteId }) _activeNoteId = noteId
    }

    /** The "second" tab — the one shown side-by-side with the active. If only one
     *  tab is open, returns null. Heuristic: the most-recently-opened non-active tab. */
    fun secondTab(): OpenNote? {
        val current = _tabs.value
        if (current.size < 2) return null
        return current.last { it.noteId != _activeNoteId }
    }

    fun clear() {
        _tabs.value = emptyList()
        _activeNoteId = null
    }

    val tabCount: Int get() = _tabs.value.size
}
