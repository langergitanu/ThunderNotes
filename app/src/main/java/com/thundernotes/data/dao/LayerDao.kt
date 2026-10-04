package com.thundernotes.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.thundernotes.data.entity.PageLayerEntity
import kotlinx.coroutines.flow.Flow

/**
 * CRUD for [PageLayerEntity] — layers within a page (for multi-layer support).
 *
 * Pattern adopted from Notein's `LayerInfo` model. The "active layer" is
 * the one new strokes/textboxes are written to — tracked by
 * `NoteContentEntity.activatedPageLayerId`.
 */
@Dao
interface LayerDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(layer: PageLayerEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(layers: List<PageLayerEntity>): List<Long>

    @Update
    suspend fun update(layer: PageLayerEntity)

    @Delete
    suspend fun delete(layer: PageLayerEntity)

    @Query("DELETE FROM page_layers WHERE layer_id = :layerId")
    suspend fun deleteByLayerId(layerId: String)

    @Query("DELETE FROM page_layers WHERE page_id = :pageId")
    suspend fun deleteByPageId(pageId: String)

    @Query("SELECT * FROM page_layers WHERE layer_id = :layerId")
    suspend fun getByLayerId(layerId: String): PageLayerEntity?

    @Query("SELECT * FROM page_layers WHERE layer_id = :layerId")
    fun observeByLayerId(layerId: String): Flow<PageLayerEntity?>

    @Query("SELECT * FROM page_layers WHERE page_id = :pageId ORDER BY sort_order ASC")
    suspend fun getByPage(pageId: String): List<PageLayerEntity>

    @Query("SELECT * FROM page_layers WHERE page_id = :pageId ORDER BY sort_order ASC")
    fun observeByPage(pageId: String): Flow<List<PageLayerEntity>>

    @Query("SELECT COUNT(*) FROM page_layers WHERE page_id = :pageId")
    suspend fun countByPage(pageId: String): Int

    /** Toggle visibility — bumps modified_time. */
    @Query("""
        UPDATE page_layers
        SET is_visible = :visible,
            modified_time = :now
        WHERE layer_id = :layerId
    """)
    suspend fun setVisible(layerId: String, visible: Boolean, now: Long = System.currentTimeMillis())

    @Query("""
        UPDATE page_layers
        SET is_locked = :locked,
            modified_time = :now
        WHERE layer_id = :layerId
    """)
    suspend fun setLocked(layerId: String, locked: Boolean, now: Long = System.currentTimeMillis())

    @Query("""
        UPDATE page_layers
        SET opacity = :opacity,
            modified_time = :now
        WHERE layer_id = :layerId
    """)
    suspend fun setOpacity(layerId: String, opacity: Float, now: Long = System.currentTimeMillis())

    /** Reorder layers within a page — pass the layer + its new sort_order. */
    @Query("""
        UPDATE page_layers
        SET sort_order = :sortOrder,
            modified_time = :now
        WHERE layer_id = :layerId
    """)
    suspend fun setSortOrder(layerId: String, sortOrder: Int, now: Long = System.currentTimeMillis())

    /** Get the bottom-most layer (sortOrder = 0) of a page. */
    @Query("SELECT * FROM page_layers WHERE page_id = :pageId ORDER BY sort_order ASC LIMIT 1")
    suspend fun getBottomLayer(pageId: String): PageLayerEntity?

    /** Get the top-most layer (highest sortOrder) of a page. */
    @Query("SELECT * FROM page_layers WHERE page_id = :pageId ORDER BY sort_order DESC LIMIT 1")
    suspend fun getTopLayer(pageId: String): PageLayerEntity?
}
