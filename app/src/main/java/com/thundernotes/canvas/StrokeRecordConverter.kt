package com.thundernotes.canvas

import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.thundernotes.data.entity.BrushFamily

/**
 * Converts a pure [StrokeRecord] (the editor's in-memory stroke model) into a
 * live `androidx.ink.strokes.Stroke` so the [CompletedStrokesView] can render
 * it — the keystone that makes **injected/pasted/loaded/redo'd** strokes
 * appear on-screen (the InProgressStrokesView only renders in-progress
 * strokes; finished strokes are handed off to a completed-strokes renderer).
 *
 * ── NEWCOMER PRIMER: why a converter at all? ────────────────────────────
 * The app deliberately keeps TWO stroke representations:
 *   - [StrokeRecord] — pure Kotlin (no Android imports): safe to unit-test,
 *     serialize into the `.thunder` DB, and transform (lasso, paste, trace).
 *   - `androidx.ink.strokes.Stroke` — the renderable object the Ink GL
 *     renderer understands, but native-backed (can't be unit-tested on the
 *     JVM and can't be persisted directly).
 * This class is the ONLY bridge in the record→renderable direction (the
 * reverse capture happens in CanvasInkHost when a stroke finishes). The
 * point-iteration half is split out into [StrokeRecordPoints] precisely so
 * the pure logic stays JVM-testable.
 *
 * Brush-family mapping: our `thunder-*-v1` family IDs ([BrushFamily]) →
 * `StockBrushes` (the public built-in families — Notein's `.brushfamily`
 * assets are proprietary + non-redistributable, so we use StockBrushes).
 */
object StrokeRecordConverter {

    @android.annotation.SuppressLint("RestrictedApi")  // androidx.ink is an alpha Jetpack lib; InputToolType.fromInt is the documented conversion path for our stored tool_type ints.
    fun toInkStroke(record: StrokeRecord): Stroke? {
        val points = StrokeRecordPoints.extract(record) ?: return null
        val batch = MutableStrokeInputBatch()
        val tool = InputToolType.fromInt(record.toolType)
        for (p in points) {
            val x = p[0]; val y = p[1]; val elapsed = p[2].toLong()
            val pressure = if (p[3] > 0f) p[3] else androidx.ink.strokes.StrokeInput.NO_PRESSURE
            val tilt = if (p[4] != 0f) p[4] else androidx.ink.strokes.StrokeInput.NO_TILT
            val orientation = if (p[5] != 0f) p[5] else androidx.ink.strokes.StrokeInput.NO_ORIENTATION
            batch.add(tool, x, y, elapsed,
                androidx.ink.strokes.StrokeInput.NO_STROKE_UNIT_LENGTH,
                pressure, tilt, orientation)
        }
        val inputs = batch.toImmutable()
        val family = familyFor(record.brushFamilyId)
        val brush = Brush.createWithColorIntArgb(
            family, record.brushColorArgb, record.brushSize, record.brushEpsilon,
        )
        return Stroke(brush, inputs)
    }

    /** Map a `thunder-*-v1` family id → a StockBrushes family (public, no asset). */
    private fun familyFor(familyId: String) = when (familyId) {
        BrushFamily.THUNDER_HIGHLIGHTER_V1 ->
            StockBrushes.highlighter(SelfOverlap.ANY, StockBrushes.HighlighterVersion.V1)
        BrushFamily.THUNDER_FOUNTAIN_V1 ->
            StockBrushes.marker()  // fountain texture is a brush-asset concern; marker proxies it
        BrushFamily.THUNDER_PENCIL_V1 ->
            StockBrushes.marker()
        else -> StockBrushes.pressurePen()  // THUNDER_BALLPOINT_V1 + default
    }
}
