package com.thundernotes.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.thundernotes.data.entity.SpacerEntity
import kotlinx.coroutines.flow.Flow

/**
 * CRUD for [SpacerEntity] — "Add Extra Writing Space" metadata.
 *
 * The Fenwick prefix-sum that turns this table into O(log N) lookup is
 * maintained in code by `data/spacer/SpacerManager.kt` (phase 7).
 * This DAO just persists the raw spacer rows.
 *
 * Per spec §7.4: "If the document has more than 1000 pages, we do not
 * guarantee it will not hang during add/delete writing space (it may
 * slightly hang), but documents smaller than 1000 pages must not hang
 * during this operation." The Fenwick structure is what enforces this.
 */
@Dao
interface SpacerDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(spacer: SpacerEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(spacers: List<SpacerEntity>): List<Long>

    @Update
    suspend fun update(spacer: SpacerEntity)

    @Query("DELETE FROM spacers WHERE spacer_id = :spacerId")
    suspend fun deleteBySpacerId(spacerId: String)

    @Query("DELETE FROM spacers WHERE anchor_page_id = :pageId")
    suspend fun deleteByPageId(pageId: String)

    @Query("DELETE FROM spacers")
    suspend fun deleteAll(): Int

    @Query("SELECT * FROM spacers WHERE spacer_id = :spacerId")
    suspend fun getBySpacerId(spacerId: String): SpacerEntity?

    /** All spacers on a page, ordered by their offset within the page.
     *  The Fenwick tree in SpacerManager consumes this list to build
     *  its prefix sum. */
    @Query("SELECT * FROM spacers WHERE anchor_page_id = :pageId ORDER BY offset_in_page ASC")
    suspend fun getByPageOrdered(pageId: String): List<SpacerEntity>

    @Query("SELECT * FROM spacers WHERE anchor_page_id = :pageId ORDER BY offset_in_page ASC")
    fun observeByPage(pageId: String): Flow<List<SpacerEntity>>

    @Query("SELECT COUNT(*) FROM spacers WHERE anchor_page_id = :pageId")
    suspend fun countByPage(pageId: String): Int

    /** Update the spacer's height (when the user drags the spacer handle
     *  to resize the writing space). */
    @Query("""
        UPDATE spacers
        SET height = :newHeight,
            offset_in_page = :newOffset,
            modified_time = :now
        WHERE spacer_id = :spacerId
    """)
    suspend fun updateHeightAndOffset(
        spacerId: String,
        newHeight: Float,
        newOffset: Float,
        now: Long = System.currentTimeMillis()
    )
}
