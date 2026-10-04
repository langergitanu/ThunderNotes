package com.thundernotes.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * An entry in the page outline (table of contents) — for the page minimap
 * and the outline navigation panel (per spec §7.6 Page Minimap).
 *
 * Outlines are organized hierarchically by [level]: level 0 = top-level
 * entries, level 1 = children, etc. Clicking an outline entry scrolls
 * the canvas to [targetPageIndex] / [targetYOffset].
 *
 * Outline entries can be created by the user (Add Outline action) or
 * auto-extracted from textbox content marked as headings.
 */
@Entity(
    tableName = "outlines",
    indices = [
        Index(value = ["page_id"]),
        Index(value = ["level"]),
        Index(value = ["target_page_index"])
    ]
)
data class OutlineEntity(
    @PrimaryKey
    @ColumnInfo(name = "outline_id")
    val outlineId: String = UUID.randomUUID().toString(),

    /** Page where this outline entry was created. */
    @ColumnInfo(name = "page_id")
    val pageId: String,

    /** Outline entry title (e.g., "Step 1: Setup", "Section A: Circuits"). */
    @ColumnInfo(name = "title")
    val title: String,

    /** 0 = top-level entry, 1 = child, 2 = grandchild, ... */
    @ColumnInfo(name = "level")
    val level: Int = 0,

    /** Page index to jump to when this entry is clicked. */
    @ColumnInfo(name = "target_page_index")
    val targetPageIndex: Int = 0,

    /** Y offset within the target page (in canvas units at 100% zoom). */
    @ColumnInfo(name = "target_y_offset")
    val targetYOffset: Float = 0.0f,

    @ColumnInfo(name = "created_time")
    val createdTime: Long = System.currentTimeMillis()
)
