package com.thundernotes.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.thundernotes.data.entity.HyperLinkEntity
import kotlinx.coroutines.flow.Flow

/**
 * CRUD for [HyperLinkEntity] — anchored hyperlinks on canvas regions.
 */
@Dao
interface HyperLinkDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(link: HyperLinkEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(links: List<HyperLinkEntity>): List<Long>

    @Update
    suspend fun update(link: HyperLinkEntity)

    @Delete
    suspend fun delete(link: HyperLinkEntity)

    @Query("DELETE FROM hyperlinks WHERE link_id = :linkId")
    suspend fun deleteByLinkId(linkId: String)

    @Query("DELETE FROM hyperlinks WHERE page_id = :pageId")
    suspend fun deleteByPageId(pageId: String)

    @Query("SELECT * FROM hyperlinks WHERE link_id = :linkId")
    suspend fun getByLinkId(linkId: String): HyperLinkEntity?

    @Query("SELECT * FROM hyperlinks WHERE page_id = :pageId ORDER BY created_time ASC")
    suspend fun getByPage(pageId: String): List<HyperLinkEntity>

    @Query("SELECT * FROM hyperlinks WHERE page_id = :pageId ORDER BY created_time ASC")
    fun observeByPage(pageId: String): Flow<List<HyperLinkEntity>>

    /** Update the anchor region (e.g., when the lasso moves the anchored region). */
    @Query("""
        UPDATE hyperlinks
        SET left = :l, top = :t, right = :r, bottom = :b
        WHERE link_id = :linkId
    """)
    suspend fun updateBounds(linkId: String, l: Float, t: Float, r: Float, b: Float)

    /** Update label + URL. */
    @Query("""
        UPDATE hyperlinks
        SET label = :label,
            url = :url
        WHERE link_id = :linkId
    """)
    suspend fun updateLabelAndUrl(linkId: String, label: String, url: String)
}
