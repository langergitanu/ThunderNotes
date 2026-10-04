package com.thundernotes.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.thundernotes.data.entity.ShapeEntity
import kotlinx.coroutines.flow.Flow

/**
 * CRUD for [ShapeEntity] — vector shapes (line, rect, ellipse, etc.).
 *
 * Same patterns as [StrokeDao] (page-local bounding box, viewport culling,
 * batch operations for lasso).
 */
@Dao
interface ShapeDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(shape: ShapeEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(shapes: List<ShapeEntity>): List<Long>

    @Update
    suspend fun update(shape: ShapeEntity)

    @Query("DELETE FROM shapes WHERE shape_id = :shapeId")
    suspend fun deleteByShapeId(shapeId: String)

    @Query("DELETE FROM shapes WHERE page_id = :pageId")
    suspend fun deleteByPageId(pageId: String)

    @Query("DELETE FROM shapes WHERE layer_id = :layerId")
    suspend fun deleteByLayerId(layerId: String)

    @Query("SELECT * FROM shapes WHERE shape_id = :shapeId")
    suspend fun getByShapeId(shapeId: String): ShapeEntity?

    @Query("SELECT * FROM shapes WHERE page_id = :pageId ORDER BY created_time ASC")
    suspend fun getByPage(pageId: String): List<ShapeEntity>

    @Query("SELECT * FROM shapes WHERE page_id = :pageId ORDER BY created_time ASC")
    fun observeByPage(pageId: String): Flow<List<ShapeEntity>>

    @Query("SELECT * FROM shapes WHERE layer_id = :layerId ORDER BY created_time ASC")
    suspend fun getByLayer(layerId: String): List<ShapeEntity>

    @Query("""
        SELECT * FROM shapes
        WHERE page_id = :pageId
          AND right  >= :viewportLeft
          AND left   <= :viewportRight
          AND bottom >= :viewportTop
          AND top    <= :viewportBottom
        ORDER BY created_time ASC
    """)
    suspend fun getByBoundingBox(
        pageId: String,
        viewportLeft: Float,
        viewportRight: Float,
        viewportTop: Float,
        viewportBottom: Float
    ): List<ShapeEntity>

    /** Lasso "Change Border Color". */
    @Query("""
        UPDATE shapes
        SET border_color = :newColor,
            sync_timestamp = :now
        WHERE shape_id IN (:shapeIds)
    """)
    suspend fun batchUpdateBorderColor(shapeIds: List<String>, newColor: Int, now: Long = System.currentTimeMillis())

    /** Lasso "Change Fill Color". */
    @Query("""
        UPDATE shapes
        SET fill_color = :newColor,
            fill_opacity = :opacity,
            sync_timestamp = :now
        WHERE shape_id IN (:shapeIds)
    """)
    suspend fun batchUpdateFillColor(
        shapeIds: List<String>,
        newColor: Int?,
        opacity: Float,
        now: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE shapes
        SET left = :l, top = :t, right = :r, bottom = :b,
            sync_timestamp = :now
        WHERE shape_id = :shapeId
    """)
    suspend fun updateBoundingBox(
        shapeId: String,
        l: Float, t: Float, r: Float, b: Float,
        now: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM shapes WHERE shape_id IN (:shapeIds)")
    suspend fun batchDelete(shapeIds: List<String>): Int
}
