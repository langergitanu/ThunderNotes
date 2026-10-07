package com.thundernotes.canvas.inject

import com.thundernotes.canvas.CanvasDocument
import com.thundernotes.canvas.StrokeRecord
import java.util.UUID

/**
 * Internal paste path (thunder-format-proposal Part B — "Internally, we own
 * the canvas + DB, so an internal InkInjector just builds a Stroke from an
 * InkStrokeProto blob, writes StrokeEntity (+ bounds), invalidates the page
 * + schedules a redraw"). Here, the pure side: translate a pasted
 * [ClipboardItem] by the drop point + add its strokes to the [CanvasDocument]
 * (each with a fresh id so a paste of the same clipboard item is a distinct
 * stroke set, not a duplicate id).
 *
 * The host-side redraw of injected strokes needs the completed-strokes
 * renderer (same dependency as live redo) — the [CanvasDocument] model is
 * updated here; the on-canvas appearance is the renderer's job. Pure +
 * unit-testable (operates on the pure [CanvasDocument]).
 */
class InkInjector(private val document: CanvasDocument) {

    /**
     * Drop [item] at ([dropX], [dropY]) on the current page.
     * Returns the **translated** [StrokeRecord]s written (each with a fresh id
     * + the page id) so the caller can also render them on-screen via
     * [com.thundernotes.ui.canvas.CompletedStrokesView.addFromRecord].
     * Empty for textbox (see [injectTextbox]).
     */
    fun inject(item: ClipboardItem, dropX: Float, dropY: Float): List<StrokeRecord> =
        when (item) {
            is ClipboardItem.StrokeGroup -> injectStrokeGroup(item, dropX, dropY)
            is ClipboardItem.TextBox -> { injectTextbox(item, dropX, dropY); emptyList() }
        }

    private fun injectStrokeGroup(
        group: ClipboardItem.StrokeGroup,
        dropX: Float,
        dropY: Float,
    ): List<StrokeRecord> {
        val page = document.currentPage ?: return emptyList()
        val out = mutableListOf<StrokeRecord>()
        for (s in group.strokes) {
            val translated = s.copy(
                id = UUID.randomUUID().toString(),
                pageId = page.id,
                inputXy = translateCoords(s.inputXy, dropX, dropY),
            )
            document.addStroke(translated)
            out.add(translated)
        }
        return out
    }

    /**
     * Drop a textbox. The textbox model + TextBoxDao exist in the per-note DB;
     * the on-canvas textbox renderer lands in a later sub-phase. For now this
     * records the paste intent (the data path is ready; the renderer is the gap).
     */
    private fun injectTextbox(item: ClipboardItem.TextBox, dropX: Float, dropY: Float) {
        // TODO (Phase 8b- textbox renderer): write a TextBoxEntity at
        // (item.x + dropX, item.y + dropY) via NoteDatabase.textBoxDao().
        // The NoteDatabase write happens through NotesRepository.openNote session.
    }

    /** Translate a flat [x0,y0,x1,y1,…] list by (dx, dy). */
    private fun translateCoords(xy: List<Float>, dx: Float, dy: Float): List<Float> {
        val out = ArrayList<Float>(xy.size)
        var i = 0
        while (i + 1 < xy.size) {
            out.add(xy[i] + dx)
            out.add(xy[i + 1] + dy)
            i += 2
        }
        return out
    }

    companion object {
        /** Convenience for tests/host: translate a flat xy list by (dx, dy). */
        fun translate(xy: List<Float>, dx: Float, dy: Float): List<Float> {
            val out = ArrayList<Float>(xy.size)
            var i = 0
            while (i + 1 < xy.size) {
                out.add(xy[i] + dx)
                out.add(xy[i + 1] + dy)
                i += 2
            }
            return out
        }
    }
}
