package com.thundernotes.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A pen stroke — the most frequently written entity in the app.
 *
 * Pattern adopted from Notein's `InkStrokeProto` (14 protobuf fields,
 * see `docs/Notein-README.md` §3) + `StrokeEntity` from Notein's Room
 * data layer. We mirror Notein's field names where they map cleanly,
 * and add the columns our app needs (page_id for page-local coords,
 * bounding box for spatial queries).
 *
 * **The `ink_stroke_blob` column is the source of truth for stroke
 * geometry.** It contains the serialized `InkStrokeProto` (our own
 * protobuf schema, mirroring Notein's 14 fields: input_xy as flat
 * [x0,y0,x1,y1,…], input_attrs as 5 floats per point [timestamp,
 * pressure, tilt, orientation, …], stroke_to_world + world_to_view
 * as 9-value affine matrices, behavior_params as Map<String,Float>).
 *
 * **Why we also store brush_size / brush_color / brush_epsilon /
 * brush_family_id / tool_type as columns:** so we can filter strokes
 * ("all strokes with color X on page Y") WITHOUT deserializing the
 * blob. The columns are denormalized mirrors of fields inside the
 * protobuf; they are kept in sync by the StrokeRepository on save.
 *
 * **Why we store the bounding box (left/top/right/bottom):** viewport
 * culling. When rendering a page, we query only strokes whose bounding
 * box intersects the visible viewport — this is what makes the canvas
 * performant on 1000-page notes (see `data/spacer/SpacerManager.kt`
 * for how page offsets compose with bounding boxes).
 *
 * **Page-local coordinates (CRITICAL):** every stroke's coordinates
 * (inside the blob) are page-local — i.e., relative to the page's
 * top-left corner at 100% zoom. The bounding box here is also
 * page-local. When the user adds writing space above this page,
 * the strokes DON'T move; only the page's render offset (computed
 * via the spacer+Fenwick system) changes.
 */
@Entity(
    tableName = "strokes",
    indices = [
        Index(value = ["page_id", "layer_id"]),
        Index(value = ["layer_id"]),
        Index(value = ["brush_family_id"]),
        Index(value = ["brush_color"]),
        Index(value = ["tool_type"]),
        // Composite index for viewport-culling spatial queries.
        Index(value = ["page_id", "left", "right", "top", "bottom"]),
        Index(value = ["creation_time"]),
        Index(value = ["sync_timestamp"])
    ]
)
data class StrokeEntity(
    @PrimaryKey
    @ColumnInfo(name = "stroke_id")
    val strokeId: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "layer_id")
    val layerId: String,

    /** Page this stroke belongs to (denormalized from PageLayerEntity for fast
     * page-scoped queries without a join). */
    @ColumnInfo(name = "page_id")
    val pageId: String,

    @ColumnInfo(name = "creation_time")
    val creationTime: Long = System.currentTimeMillis(),

    /** Brush size in canvas units (cm-equivalent; same scale Notein uses
     *  for `brush_size`). */
    @ColumnInfo(name = "brush_size")
    val brushSize: Float = 2.0f,

    /** ARGB int (e.g. 0xFF000000 for black). */
    @ColumnInfo(name = "brush_color")
    val brushColor: Int = 0xFF000000.toInt(),

    /** Epsilon for the brush (a small radius used by AndroidX Ink for
     *  hit-testing and stroke joins). */
    @ColumnInfo(name = "brush_epsilon")
    val brushEpsilon: Float = 0.1f,

    /** One of [com.thundernotes.data.entity.BrushFamily] IDs, e.g.
     *  `thunder-ballpoint-v1`. */
    @ColumnInfo(name = "brush_family_id")
    val brushFamilyId: String = BrushFamily.THUNDER_BALLPOINT_V1,

    /** STYLUS / TOUCH / MOUSE / UNKNOWN — see [ToolType]. */
    @ColumnInfo(name = "tool_type")
    val toolType: Int = ToolType.STYLUS.rawValue,

    /** Length of the stroke in cm (for statistics + future sync). */
    @ColumnInfo(name = "stroke_unit_length_cm")
    val strokeUnitLengthCm: Float = 0.0f,

    /** The serialized `InkStrokeProto` bytes (our own schema, mirroring
     *  Notein's 14 fields). This is the source of truth for stroke geometry. */
    @ColumnInfo(name = "ink_stroke_blob")
    val inkStrokeBlob: ByteArray,

    /** Page-local bounding box (in canvas units at 100% zoom). */
    @ColumnInfo(name = "left")
    val left: Float,
    @ColumnInfo(name = "top")
    val top: Float,
    @ColumnInfo(name = "right")
    val right: Float,
    @ColumnInfo(name = "bottom")
    val bottom: Float,

    @ColumnInfo(name = "sync_timestamp")
    val syncTimestamp: Long = System.currentTimeMillis()
) {
    // ByteArray's default equals/hashCode are reference-based; for data classes
    // holding a ByteArray, we override equals to compare content (and skip
    // hashCode since the blob can be huge).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StrokeEntity) return false
        return strokeId == other.strokeId
    }
    override fun hashCode(): Int = strokeId.hashCode()
}
