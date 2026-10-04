package com.thundernotes.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A comment anchored to a region on a page (spec mentions comments in
 * the .thunder format; per Notein's `CommentEntity`).
 *
 * For the personal-use build, [author] is always "me". The [anchorX/Y/W/H]
 * define a rectangular region on the page that the comment is attached to.
 * When the canvas renders, an indicator badge is drawn at the anchor;
 * clicking the badge opens the comment text.
 *
 * Comments survive theme inversion, page-resize, and lasso operations
 * that move the anchored region (the anchor transforms with the lasso).
 */
@Entity(
    tableName = "comments",
    indices = [
        Index(value = ["page_id"]),
        Index(value = ["anchor_x", "anchor_y"])
    ]
)
data class CommentEntity(
    @PrimaryKey
    @ColumnInfo(name = "comment_id")
    val commentId: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "page_id")
    val pageId: String,

    /** Always "me" for the personal-use build; will be a user handle in the
     *  cloud-sync future. */
    @ColumnInfo(name = "author")
    val author: String = "me",

    /** Comment body (may include newlines). */
    @ColumnInfo(name = "text")
    val text: String = "",

    /** Anchor region (page-local canvas units). */
    @ColumnInfo(name = "anchor_x")
    val anchorX: Float,
    @ColumnInfo(name = "anchor_y")
    val anchorY: Float,
    @ColumnInfo(name = "anchor_w")
    val anchorW: Float,
    @ColumnInfo(name = "anchor_h")
    val anchorH: Float,

    @ColumnInfo(name = "created_time")
    val createdTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "modified_time")
    val modifiedTime: Long = System.currentTimeMillis()
)
