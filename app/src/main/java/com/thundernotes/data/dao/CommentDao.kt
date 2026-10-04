package com.thundernotes.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.thundernotes.data.entity.CommentEntity
import kotlinx.coroutines.flow.Flow

/**
 * CRUD for [CommentEntity] — anchored comments on canvas regions.
 */
@Dao
interface CommentDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(comment: CommentEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(comments: List<CommentEntity>): List<Long>

    @Update
    suspend fun update(comment: CommentEntity)

    @Delete
    suspend fun delete(comment: CommentEntity)

    @Query("DELETE FROM comments WHERE comment_id = :commentId")
    suspend fun deleteByCommentId(commentId: String)

    @Query("DELETE FROM comments WHERE page_id = :pageId")
    suspend fun deleteByPageId(pageId: String)

    @Query("SELECT * FROM comments WHERE comment_id = :commentId")
    suspend fun getByCommentId(commentId: String): CommentEntity?

    @Query("SELECT * FROM comments WHERE page_id = :pageId ORDER BY created_time ASC")
    suspend fun getByPage(pageId: String): List<CommentEntity>

    @Query("SELECT * FROM comments WHERE page_id = :pageId ORDER BY created_time ASC")
    fun observeByPage(pageId: String): Flow<List<CommentEntity>>

    /** Update text content only (the most-frequent update path). */
    @Query("""
        UPDATE comments
        SET text = :newText,
            modified_time = :now
        WHERE comment_id = :commentId
    """)
    suspend fun updateText(commentId: String, newText: String, now: Long = System.currentTimeMillis())

    /** Update the anchor region (e.g., when the lasso moves the anchored
     *  region). */
    @Query("""
        UPDATE comments
        SET anchor_x = :x, anchor_y = :y,
            anchor_w = :w, anchor_h = :h,
            modified_time = :now
        WHERE comment_id = :commentId
    """)
    suspend fun updateAnchor(
        commentId: String,
        x: Float, y: Float, w: Float, h: Float,
        now: Long = System.currentTimeMillis()
    )
}
