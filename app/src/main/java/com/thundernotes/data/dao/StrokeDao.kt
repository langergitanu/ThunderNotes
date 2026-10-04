package com.thundernotes.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.thundernotes.data.entity.StrokeEntity
import kotlinx.coroutines.flow.Flow

/**
 * CRUD for [StrokeEntity] — the workhorse DAO (every pen stroke writes here).
 *
 * **Spatial viewport culling** (the most performance-critical query in the
 * app): when rendering a page, we query only strokes whose bounding box
 * intersects the visible viewport. This is what makes the canvas performant
 * on 1000-page notes — we never read strokes from off-screen pages.
 *
 * **Lasso operations** (per spec §7.2): the lasso supports 9 functions —
 * Cut, Copy, Rotate, Enlarge/Reduce, Change Color, Change Stroke Thickness,
 * Horizontal Flip, Vertical Flip, Delete. Most of these are batch UPDATEs
 * on the strokes inside the lasso; we expose them as suspend methods here.
 */
@Dao
interface StrokeDao {

    // ─── inserts / updates / deletes ─────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(stroke: StrokeEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(strokes: List<StrokeEntity>): List<Long>

    @Update
    suspend fun update(stroke: StrokeEntity)

    @Query("DELETE FROM strokes WHERE stroke_id = :strokeId")
    suspend fun deleteByStrokeId(strokeId: String)

    @Query("DELETE FROM strokes WHERE layer_id = :layerId")
    suspend fun deleteByLayerId(layerId: String)

    @Query("DELETE FROM strokes WHERE page_id = :pageId")
    suspend fun deleteByPageId(pageId: String)

    // ─── reads ───────────────────────────────────────────────────────────────

    @Query("SELECT * FROM strokes WHERE stroke_id = :strokeId")
    suspend fun getByStrokeId(strokeId: String): StrokeEntity?

    @Query("SELECT * FROM strokes WHERE page_id = :pageId ORDER BY creation_time ASC")
    suspend fun getByPage(pageId: String): List<StrokeEntity>

    @Query("SELECT * FROM strokes WHERE page_id = :pageId ORDER BY creation_time ASC")
    fun observeByPage(pageId: String): Flow<List<StrokeEntity>>

    @Query("SELECT * FROM strokes WHERE layer_id = :layerId ORDER BY creation_time ASC")
    suspend fun getByLayer(layerId: String): List<StrokeEntity>

    @Query("SELECT * FROM strokes WHERE brush_family_id = :familyId")
    fun observeByBrushFamily(familyId: String): Flow<List<StrokeEntity>>

    @Query("SELECT * FROM strokes WHERE brush_color = :color")
    fun observeByBrushColor(color: Int): Flow<List<StrokeEntity>>

    /**
     * Viewport-culling spatial query — the most performance-critical read
     * in the app. Returns strokes on [pageId] whose bounding box intersects
     * the viewport rectangle. Backed by the composite index
     * `(page_id, left, right, top, bottom)`.
     */
    @Query("""
        SELECT * FROM strokes
        WHERE page_id = :pageId
          AND right  >= :viewportLeft
          AND left   <= :viewportRight
          AND bottom >= :viewportTop
          AND top    <= :viewportBottom
        ORDER BY creation_time ASC
    """)
    suspend fun getByBoundingBox(
        pageId: String,
        viewportLeft: Float,
        viewportRight: Float,
        viewportTop: Float,
        viewportBottom: Float
    ): List<StrokeEntity>

    @Query("SELECT COUNT(*) FROM strokes WHERE page_id = :pageId")
    suspend fun countByPage(pageId: String): Int

    @Query("SELECT COUNT(*) FROM strokes WHERE layer_id = :layerId")
    suspend fun countByLayer(layerId: String): Int

    // ─── lasso batch operations (spec §7.2: 9 functions) ─────────────────────

    /** Lasso "Change Color" — bulk-update brush_color for the given stroke IDs. */
    @Query("""
        UPDATE strokes
        SET brush_color = :newColor,
            sync_timestamp = :now
        WHERE stroke_id IN (:strokeIds)
    """)
    suspend fun batchUpdateBrushColor(strokeIds: List<String>, newColor: Int, now: Long = System.currentTimeMillis())

    /** Lasso "Change Stroke Thickness" — bulk-update brush_size + brush_epsilon. */
    @Query("""
        UPDATE strokes
        SET brush_size = :newSize,
            brush_epsilon = :newEpsilon,
            sync_timestamp = :now
        WHERE stroke_id IN (:strokeIds)
    """)
    suspend fun batchUpdateBrushSize(
        strokeIds: List<String>,
        newSize: Float,
        newEpsilon: Float,
        now: Long = System.currentTimeMillis()
    )

    /** Lasso "Rotate / Enlarge-Reduce / Flip" — bulk-update bounding box.
     *  The blob geometry transform is handled in code by InkInjector +
     *  StrokeRepository; here we just persist the post-transform bounds. */
    @Query("""
        UPDATE strokes
        SET left = :l, top = :t, right = :r, bottom = :b,
            sync_timestamp = :now
        WHERE stroke_id = :strokeId
    """)
    suspend fun updateBoundingBox(
        strokeId: String,
        l: Float, t: Float, r: Float, b: Float,
        now: Long = System.currentTimeMillis()
    )

    /** Lasso "Delete" — bulk delete. */
    @Query("DELETE FROM strokes WHERE stroke_id IN (:strokeIds)")
    suspend fun batchDelete(strokeIds: List<String>): Int
}
