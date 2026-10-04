package com.thundernotes.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A hyperlink anchored to a rectangular region on a page (spec mentions
 * hyperlinks in the .thunder format; per Notein's `HyperLinkEntity`).
 *
 * Clicking the anchored region opens [url] in the system browser. The
 * anchored region is rendered with a subtle underline/highlight to
 * indicate it's a link.
 *
 * Links survive theme inversion, page-resize, and lasso operations that
 * move the anchored region.
 */
@Entity(
    tableName = "hyperlinks",
    indices = [
        Index(value = ["page_id"]),
        Index(value = ["left", "top"])
    ]
)
data class HyperLinkEntity(
    @PrimaryKey
    @ColumnInfo(name = "link_id")
    val linkId: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "page_id")
    val pageId: String,

    /** Visible label, e.g. "GitHub repo" or just the URL. */
    @ColumnInfo(name = "label")
    val label: String = "",

    /** Target URL. */
    @ColumnInfo(name = "url")
    val url: String,

    /** Anchor region (page-local canvas units). */
    @ColumnInfo(name = "left")
    val left: Float,
    @ColumnInfo(name = "top")
    val top: Float,
    @ColumnInfo(name = "right")
    val right: Float,
    @ColumnInfo(name = "bottom")
    val bottom: Float,

    @ColumnInfo(name = "created_time")
    val createdTime: Long = System.currentTimeMillis()
)
