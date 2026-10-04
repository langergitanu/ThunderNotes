package com.thundernotes.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A vector shape on the canvas — line, rectangle, ellipse, triangle, arrow,
 * etc. Per spec §6.10 Row 3 top-right, the Shape Picker supports 7 options:
 *   Shape Types, Border Width, Line Type (8 line types), Corner Radius,
 *   Border Color, Fill Color (can be None), Fill Opacity.
 *
 * Shape-specific geometry (e.g. line endpoints, ellipse radii, polygon
 * vertices) is stored as JSON in [geometryData] — typed columns would
 * over-fit the schema. The entity itself stores the universal properties
 * (border, fill, bounds) that every shape has.
 *
 * Like strokes, shapes are page-local (the bounding box is in canvas
 * units relative to the page's top-left corner at 100% zoom).
 */
@Entity(
    tableName = "shapes",
    indices = [
        Index(value = ["page_id", "layer_id"]),
        Index(value = ["layer_id"]),
        Index(value = ["shape_type"]),
        Index(value = ["page_id", "left", "right", "top", "bottom"])
    ]
)
data class ShapeEntity(
    @PrimaryKey
    @ColumnInfo(name = "shape_id")
    val shapeId: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "layer_id")
    val layerId: String,

    @ColumnInfo(name = "page_id")
    val pageId: String,

    /** See [ShapeType]. */
    @ColumnInfo(name = "shape_type")
    val shapeType: Int = ShapeType.LINE.rawValue,

    @ColumnInfo(name = "border_width")
    val borderWidth: Float = 2.0f,

    /** See [LineType]. */
    @ColumnInfo(name = "line_type")
    val lineType: Int = LineType.STRAIGHT.rawValue,

    /** Corner radius in canvas units (used by RECTANGLE / ROUNDED_RECT). */
    @ColumnInfo(name = "corner_radius")
    val cornerRadius: Float = 0.0f,

    /** ARGB int border color. */
    @ColumnInfo(name = "border_color")
    val borderColor: Int = 0xFF000000.toInt(),

    /** ARGB int fill color, or null for "no fill" (spec allows None). */
    @ColumnInfo(name = "fill_color")
    val fillColor: Int? = null,

    /** 0..1 — fill opacity multiplier. */
    @ColumnInfo(name = "fill_opacity")
    val fillOpacity: Float = 1.0f,

    /** Shape-specific geometry as JSON. Examples:
     *   LINE:        {"x1":0,"y1":0,"x2":100,"y2":100}
     *   RECTANGLE:   {}  (geometry derived from bounding box)
     *   ELLIPSE:     {}  (derived from bounding box)
     *   POLYGON:     {"vertices":[{"x":..,"y":..}, ...]}
     *   ARROW:       {"head_length":10,"head_angle":30}
     *   STAR:        {"points":5,"inner_ratio":0.5}  */
    @ColumnInfo(name = "geometry_data")
    val geometryData: String = "{}",

    @ColumnInfo(name = "left")
    val left: Float,
    @ColumnInfo(name = "top")
    val top: Float,
    @ColumnInfo(name = "right")
    val right: Float,
    @ColumnInfo(name = "bottom")
    val bottom: Float,

    @ColumnInfo(name = "created_time")
    val createdTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "modified_time")
    val modifiedTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "sync_timestamp")
    val syncTimestamp: Long = System.currentTimeMillis()
)
