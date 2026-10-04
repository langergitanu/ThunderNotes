package com.thundernotes.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A layer within a page — for the multi-layer support (e.g., a background
 * layer, an annotations layer, an answers layer that can be toggled
 * visible/invisible per page).
 *
 * Pattern adopted from Notein's `LayerInfo` model. We expose this as a
 * first-class entity (Notein stores layer info as JSON inside
 * `NoteContentEntity.pageLayerListJson` — we prefer a typed table).
 *
 * Layer ordering within a page is by [sortOrder] ascending. The first
 * layer (sortOrder = 0) is the bottom; higher sortOrders render on top.
 *
 * The canvas theme toggle (dark↔light) does NOT toggle layer visibility —
 * it inverts presentation colors. Layer visibility is a separate,
 * user-controlled flag.
 */
@Entity(
    tableName = "page_layers",
    indices = [
        Index(value = ["page_id"]),
        Index(value = ["sort_order"])
    ]
)
data class PageLayerEntity(
    @PrimaryKey
    @ColumnInfo(name = "layer_id")
    val layerId: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "page_id")
    val pageId: String,

    @ColumnInfo(name = "layer_name")
    val layerName: String = "Layer",

    @ColumnInfo(name = "is_visible")
    val isVisible: Boolean = true,

    @ColumnInfo(name = "is_locked")
    val isLocked: Boolean = false,

    /** 0..1 — layer-wide opacity multiplier applied on top of stroke colors. */
    @ColumnInfo(name = "opacity")
    val opacity: Float = 1.0f,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    @ColumnInfo(name = "created_time")
    val createdTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "modified_time")
    val modifiedTime: Long = System.currentTimeMillis()
)
