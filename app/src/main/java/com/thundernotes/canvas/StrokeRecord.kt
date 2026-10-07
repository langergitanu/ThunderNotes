package com.thundernotes.canvas

import java.util.UUID

/**
 * A single finished pen stroke, in pure-Kotlin form (no Android/AndroidX-Ink
 * dependency). This is the editor's in-memory stroke model — the bridge
 * between the live [androidx.ink.strokes.Stroke] produced by
 * [androidx.ink.authoring.InProgressStrokesView] and our on-disk
 * [com.thundernotes.format.proto.InkStrokeProto] blob.
 *
 * Field layout mirrors [com.thundernotes.format.proto.InkStrokeProto] 1:1
 * (Notein's 14-field schema, copied structurally per the architecture plan),
 * so [StrokeBlobCodec] can convert losslessly in both directions.
 *
 * Coordinates are **page-local and immutable** (per thunder-format-proposal
 * Part C — the Add-Writing-Space model: strokes never move when space is
 * added; only spacer metadata shifts). `pageId` ties the stroke to a page.
 *
 * @param inputXy flat `[x0,y0, x1,y1, …]` — 2 floats per point.
 * @param inputAttrs flat 5 floats per point (timestamp, pressure, tilt,
 *   orientation, …) — `ATTR_STRIDE = 5` per Notein's InkStrokeProtoSerializer.
 * @param strokeToWorld / worldToView 0 or 9 affine-Matrix floats.
 */
data class StrokeRecord(
    val id: String = UUID.randomUUID().toString(),
    val layerId: String,
    val pageId: String,
    val creationTime: Long = System.currentTimeMillis(),
    val brushSize: Float,
    val brushColorArgb: Int,
    val brushEpsilon: Float,
    val brushFamilyId: String,
    val toolType: Int,
    val strokeUnitLengthCm: Float = 0f,
    val inputXy: List<Float> = emptyList(),
    val inputAttrs: List<Float> = emptyList(),
    val strokeToWorld: List<Float> = emptyList(),
    val worldToView: List<Float> = emptyList(),
    val behaviorParams: Map<String, Float> = emptyMap(),
) {
    /** Number of input points (inputXy.length / 2). */
    val pointCount: Int get() = inputXy.size / 2

    /** Validity guard: attrs must be 5× points; matrices 0 or 9. */
    val isWellFormed: Boolean
        get() = inputAttrs.size == pointCount * ATTR_STRIDE &&
            (strokeToWorld.isEmpty() || strokeToWorld.size == MATRIX_SIZE) &&
            (worldToView.isEmpty() || worldToView.size == MATRIX_SIZE)

    companion object {
        const val ATTR_STRIDE = 5
        const val MATRIX_SIZE = 9
    }
}
