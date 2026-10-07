package com.thundernotes.canvas.lasso

import com.thundernotes.canvas.CanvasDocument
import com.thundernotes.canvas.StrokeRecord
import com.thundernotes.canvas.inject.ClipboardItem
import com.thundernotes.canvas.inject.ThunderClipboard

/**
 * The lasso's lifecycle ops (spec §7.2): **Cut** (clipboard + remove),
 * **Copy** (clipboard only), **Delete** (remove only). The other 6 lasso
 * functions are geometric/brush transforms in [StrokeTransforms].
 *
 * Cut/Copy build a [ClipboardItem.StrokeGroup] with **relative coords** —
 * the group's bbox is normalized to (0,0) at the top-left, so a later paste
 * drops the group at any (dropX, dropY) via [com.thundernotes.canvas.inject.InkInjector].
 * This feeds the live-injection clipboard (spec §7.3: copied items go to the
 * app's clipboard → paste button glows → paste as a floating lasso).
 *
 * Pure + unit-testable (operates on the pure [CanvasDocument] + [ThunderClipboard]).
 */
object LassoOps {

    /** Build a clipboard StrokeGroup from the selected strokes, with relative
     *  coords (bbox normalized to 0,0). */
    fun toClipboardItem(strokes: List<StrokeRecord>): ClipboardItem.StrokeGroup {
        val bb = StrokeTransforms.groupBounds(strokes)
        val minX = bb[0]; val minY = bb[1]
        val rel = strokes.map { s ->
            s.copy(inputXy = translate(s.inputXy, -minX, -minY))
        }
        val width = bb[2] - bb[0]
        val height = bb[3] - bb[1]
        return ClipboardItem.StrokeGroup(rel, floatArrayOf(0f, 0f, width, height))
    }

    /** Copy — put the group into [ThunderClipboard] (paste button glows). */
    fun copy(strokes: List<StrokeRecord>) {
        if (strokes.isEmpty()) return
        ThunderClipboard.put(toClipboardItem(strokes))
    }

    /** Cut — copy + remove the strokes from the document. Returns the removed ids. */
    fun cut(document: CanvasDocument, strokes: List<StrokeRecord>): List<String> {
        if (strokes.isEmpty()) return emptyList()
        copy(strokes)
        return strokes.mapNotNull { s ->
            if (document.removeStroke(s.id) != null) s.id else null
        }
    }

    /** Delete — remove the strokes from the document (no clipboard). Returns the removed ids. */
    fun delete(document: CanvasDocument, strokes: List<StrokeRecord>): List<String> {
        val removed = mutableListOf<String>()
        for (s in strokes) {
            if (document.removeStroke(s.id) != null) removed.add(s.id)
        }
        return removed
    }

    private fun translate(xy: List<Float>, dx: Float, dy: Float): List<Float> {
        val out = ArrayList<Float>(xy.size)
        var i = 0
        while (i + 1 < xy.size) {
            out.add(xy[i] + dx); out.add(xy[i + 1] + dy); i += 2
        }
        return out
    }
}
