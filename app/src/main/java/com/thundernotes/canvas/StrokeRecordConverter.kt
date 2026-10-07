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
 * Pure point-iteration is delegated to [StrokeRecordPoints] (unit-tested);
 * this class wraps the androidx.ink construction (native-backed, so not
 * unit-testable on the JVM — but the logic it drives is tested via
 * [StrokeRecordPoints]).
 *
 * Brush-family mapping: our `thunder-*-v1` family IDs ([BrushFamily]) →
 * `StockBrushes` (the public built-in families — Notein's `.brushfamily`
 * assets are proprietary + non-redistributable, so we use StockBrushes).
 */
object StrokeRecordConverter {

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
