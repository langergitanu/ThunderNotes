package com.thundernotes.canvas

/** Pure, Android-free extraction of the per-point tuples packed inside a
 *  [StrokeRecord]'s flat [StrokeRecord.inputXy] + [StrokeRecord.inputAttrs].
 *
 *  Extracted from the (Android-dependent) [StrokeRecordConverter] so the
 *  point-iteration logic is unit-testable on the JVM. Each returned FloatArray
 *  is `[x, y, elapsedMs, pressure, tilt, orientation]` — the 6 values the
 *  androidx.ink `MutableStrokeInputBatch.add(...)` overload expects. */
object StrokeRecordPoints {

    /** Returns null if the record is malformed (xy/attrs length mismatch). */
    fun extract(record: StrokeRecord): List<FloatArray>? {
        val xy = record.inputXy
        val attrs = record.inputAttrs
        if (xy.size % 2 != 0) return null
        val pointCount = xy.size / 2
        if (pointCount == 0) return null
        if (attrs.isNotEmpty() && attrs.size != pointCount * StrokeRecord.ATTR_STRIDE) return null
        val out = ArrayList<FloatArray>(pointCount)
        for (i in 0 until pointCount) {
            val x = xy[i * 2]
            val y = xy[i * 2 + 1]
            val base = i * StrokeRecord.ATTR_STRIDE
            val elapsed = if (attrs.isNotEmpty()) attrs[base] else 0f
            val pressure = if (attrs.isNotEmpty()) attrs[base + 1] else 1f
            val tilt = if (attrs.isNotEmpty()) attrs[base + 2] else 0f
            val orientation = if (attrs.isNotEmpty()) attrs[base + 3] else 0f
            out.add(floatArrayOf(x, y, elapsed, pressure, tilt, orientation))
        }
        return out
    }
}
