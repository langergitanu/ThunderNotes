package com.thundernotes.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.thundernotes.data.entity.OutlineEntity
import kotlinx.coroutines.flow.Flow

/**
 * CRUD for [OutlineEntity] — page outline entries (TOC) for the page minimap.
 */
@Dao
interface OutlineDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(outline: OutlineEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(outlines: List<OutlineEntity>): List<Long>

    @Update
    suspend fun update(outline: OutlineEntity)

    @Delete
    suspend fun delete(outline: OutlineEntity)

    @Query("DELETE FROM outlines WHERE outline_id = :outlineId")
    suspend fun deleteByOutlineId(outlineId: String)

    @Query("DELETE FROM outlines WHERE page_id = :pageId")
    suspend fun deleteByPageId(pageId: String)

    @Query("SELECT * FROM outlines WHERE outline_id = :outlineId")
    suspend fun getByOutlineId(outlineId: String): OutlineEntity?

    /** All outline entries across all pages, ordered by target page index then
     *  level (so the minimap shows them in reading order). */
    @Query("SELECT * FROM outlines ORDER BY target_page_index ASC, level ASC")
    fun observeAll(): Flow<List<OutlineEntity>>

    @Query("SELECT * FROM outlines WHERE page_id = :pageId ORDER BY level ASC")
    fun observeByPage(pageId: String): Flow<List<OutlineEntity>>

    @Query("SELECT * FROM outlines WHERE page_id = :pageId ORDER BY level ASC")
    suspend fun getByPage(pageId: String): List<OutlineEntity>

    @Query("SELECT * FROM outlines WHERE target_page_index = :pageIndex ORDER BY level ASC")
    suspend fun getByTargetPageIndex(pageIndex: Int): List<OutlineEntity>
}
