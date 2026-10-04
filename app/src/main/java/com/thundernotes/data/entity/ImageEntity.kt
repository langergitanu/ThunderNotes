package com.thundernotes.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * An embedded image on the canvas (PNG / JPEG / WebP).
 *
 * Per thunder-format-proposal Part A, the .thunder ZIP's `assets/` directory
 * holds images embedded with relative paths — i.e. the .thunder file is
 * self-contained (no external image references that would break if the
 * .thunder is moved or shared).
 *
 * [assetPath] is relative to the .thunder ZIP's `assets/` root, e.g.
 * `images/img_abc123.png`. The format/ThunderFile.kt reader extracts the
 * asset to a staging directory on first open and resolves [assetPath]
 * against that staging dir for fast canvas rendering.
 *
 * Page-local bounding box, same convention as strokes/shapes/textboxes.
 */
@Entity(
    tableName = "images",
    indices = [
        Index(value = ["page_id", "layer_id"]),
        Index(value = ["layer_id"]),
        Index(value = ["asset_path"]),
        Index(value = ["page_id", "left", "right", "top", "bottom"])
    ]
)
data class ImageEntity(
    @PrimaryKey
    @ColumnInfo(name = "image_id")
    val imageId: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "layer_id")
    val layerId: String,

    @ColumnInfo(name = "page_id")
    val pageId: String,

    /** Path relative to the .thunder ZIP's `assets/` root, e.g.
     *  `images/img_abc123.png`. */
    @ColumnInfo(name = "asset_path")
    val assetPath: String,

    /** Original image dimensions in pixels (for aspect-ratio preservation
     *  when the user resizes the textbox-style image handle). */
    @ColumnInfo(name = "original_width")
    val originalWidth: Int = 0,
    @ColumnInfo(name = "original_height")
    val originalHeight: Int = 0,

    /** Display dimensions in canvas units (the size the image is rendered
     *  on the canvas — may differ from original dims if the user has
     *  resized the image). */
    @ColumnInfo(name = "display_width")
    val displayWidth: Float = 0.0f,
    @ColumnInfo(name = "display_height")
    val displayHeight: Float = 0.0f,

    @ColumnInfo(name = "left")
    val left: Float,
    @ColumnInfo(name = "top")
    val top: Float,
    @ColumnInfo(name = "right")
    val right: Float,
    @ColumnInfo(name = "bottom")
    val bottom: Float,

    /** 0..1 — opacity multiplier. */
    @ColumnInfo(name = "opacity")
    val opacity: Float = 1.0f,

    /** Rotation in degrees. */
    @ColumnInfo(name = "rotation")
    val rotation: Float = 0.0f,

    @ColumnInfo(name = "created_time")
    val createdTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "modified_time")
    val modifiedTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "sync_timestamp")
    val syncTimestamp: Long = System.currentTimeMillis()
)
