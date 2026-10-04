package com.thundernotes.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.thundernotes.data.entity.NotePageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Per-note pages DAO.
 *
 * Lives inside the `.thunder` ZIP's `note.sqlite` DB. There is one row per
 * page; pages are ordered by [NotePageEntity.pageIndex] (0-indexed).
 *
 * **Page-local coordinates (CRITICAL):** every stroke's stored x/y is relative
 * to the page's top-left corner, NOT a global canvas coordinate. When the
 * user adds writing space above a page, the strokes DON'T move — only the
 * page's render offset (computed via `data/spacer/SpacerManager.kt`) changes.
 */
@Dao
interface NotePageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(page: NotePageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(pages: List<NotePageEntity>): List<Long>

    @Update
    suspend fun update(page: NotePageEntity)

    @Delete
    suspend fun delete(page: NotePageEntity)

    @Query("DELETE FROM pages WHERE page_id = :pageId")
    suspend fun deleteByPageId(pageId: String)

    /** Per-note DB only ever has pages for ONE note, so deleting all pages
     * clears the table. Used when re-initializing a fresh .thunder file. */
    @Query("DELETE FROM pages")
    suspend fun deleteAll(): Int

    @Query("SELECT * FROM pages WHERE page_id = :pageId")
    suspend fun getByPageId(pageId: String): NotePageEntity?

    @Query("""
        SELECT * FROM pages
        ORDER BY page_index ASC
    """)
    fun observeAllOrdered(): Flow<List<NotePageEntity>>

    @Query("""
        SELECT * FROM pages
        ORDER BY page_index ASC
    """)
    suspend fun getAllOrdered(): List<NotePageEntity>

    @Query("""
        SELECT * FROM pages
        WHERE page_index = :pageIndex
    """)
    suspend fun getByIndex(pageIndex: Int): NotePageEntity?

    @Query("SELECT COUNT(*) FROM pages")
    suspend fun getPageCount(): Int

    @Query("SELECT MAX(page_index) FROM pages")
    suspend fun getLastIndex(): Int?

    @Query("""
        INSERT INTO pages (page_id, page_index, page_type, orientation, page_ratio,
                           page_height_px, page_background_color,
                           created_time, modified_time)
        VALUES (:pageId, :pageIndex, :pageType, :orientation, :pageRatio,
                :pageHeightPx, :pageBackgroundColor,
                :createdTime, :modifiedTime)
    """)
    suspend fun insertRaw(
        pageId: String,
        pageIndex: Int,
        pageType: Int,
        orientation: Int,
        pageRatio: Float,
        pageHeightPx: Int,
        pageBackgroundColor: Int,
        createdTime: Long = System.currentTimeMillis(),
        modifiedTime: Long = System.currentTimeMillis()
    )

    /** Insert a new page at [pageIndex], shifting all pages at index >= [pageIndex] up by 1. */
    @Query("UPDATE pages SET page_index = page_index + 1 WHERE page_index >= :pageIndex")
    suspend fun shiftPagesDown(pageIndex: Int)
}
