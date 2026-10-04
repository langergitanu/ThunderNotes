package com.thundernotes.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.thundernotes.data.entity.ImageEntity
import kotlinx.coroutines.flow.Flow

/**
 * CRUD for [ImageEntity] — embedded images on the canvas.
 *
 * The actual image bytes live as files under `assets/images/` inside the
 * .thunder ZIP; [ImageEntity] only holds the metadata + display bounds.
 */
@Dao
interface ImageDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(image: ImageEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(images: List<ImageEntity>): List<Long>

    @Update
    suspend fun update(image: ImageEntity)

    @Query("DELETE FROM images WHERE image_id = :imageId")
    suspend fun deleteByImageId(imageId: String)

    @Query("DELETE FROM images WHERE page_id = :pageId")
    suspend fun deleteByPageId(pageId: String)

    @Query("DELETE FROM images WHERE layer_id = :layerId")
    suspend fun deleteByLayerId(layerId: String)

    /** Delete by asset_path — used when removing an asset file from the
     *  .thunder ZIP's assets/ directory (e.g., when an image is unused
     *  after a lasso-delete). */
    @Query("DELETE FROM images WHERE asset_path = :assetPath")
    suspend fun deleteByAssetPath(assetPath: String)

    @Query("SELECT * FROM images WHERE image_id = :imageId")
    suspend fun getByImageId(imageId: String): ImageEntity?

    @Query("SELECT * FROM images WHERE page_id = :pageId ORDER BY created_time ASC")
    suspend fun getByPage(pageId: String): List<ImageEntity>

    @Query("SELECT * FROM images WHERE page_id = :pageId ORDER BY created_time ASC")
    fun observeByPage(pageId: String): Flow<List<ImageEntity>>

    @Query("SELECT * FROM images WHERE layer_id = :layerId ORDER BY created_time ASC")
    suspend fun getByLayer(layerId: String): List<ImageEntity>

    @Query("""
        SELECT * FROM images
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
    ): List<ImageEntity>

    @Query("""
        UPDATE images
        SET left = :l, top = :t, right = :r, bottom = :b,
            rotation = :rotation,
            opacity = :opacity,
            modified_time = :now,
            sync_timestamp = :now
        WHERE image_id = :imageId
    """)
    suspend fun updateBounds(
        imageId: String,
        l: Float, t: Float, r: Float, b: Float,
        rotation: Float,
        opacity: Float,
        now: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM images WHERE image_id IN (:imageIds)")
    suspend fun batchDelete(imageIds: List<String>): Int

    /** Find all asset_paths referenced by this note's images — used to
     *  garbage-collect unreferenced asset files in the .thunder ZIP. */
    @Query("SELECT DISTINCT asset_path FROM images")
    suspend fun getAllReferencedAssetPaths(): List<String>
}
