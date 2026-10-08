package com.thundernotes.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.thundernotes.data.entity.TextBoxEntity
import kotlinx.coroutines.flow.Flow

/**
 * CRUD for [TextBoxEntity] — typed text content on the canvas.
 *
 * How to read a DAO: every method below is one SQL statement that Room
 * generates at COMPILE time from the annotation (`@Query("SELECT …")` is
 * checked against the entity columns — a typo fails the build). `suspend fun`
 * = runs on a background dispatcher automatically (never block the UI
 * thread); returning `Flow<…>` = the query re-emits whenever the table
 * changes (live UI updates). `OnConflictStrategy.REPLACE` turns an insert
 * into an upsert — the auto-save path uses it to overwrite edited rows.
 */
@Dao
interface TextBoxDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(textbox: TextBoxEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(textboxes: List<TextBoxEntity>): List<Long>

    /**
     * Insert-or-replace by textbox_id — the editor's incremental auto-save path
     * (textbox edits, undo→redo re-inserts, FILLER stamp updates).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(textbox: TextBoxEntity)

    @Update
    suspend fun update(textbox: TextBoxEntity)

    @Query("DELETE FROM textboxes WHERE textbox_id = :textboxId")
    suspend fun deleteByTextBoxId(textboxId: String)

    @Query("DELETE FROM textboxes WHERE page_id = :pageId")
    suspend fun deleteByPageId(pageId: String)

    @Query("DELETE FROM textboxes WHERE layer_id = :layerId")
    suspend fun deleteByLayerId(layerId: String)

    @Query("SELECT * FROM textboxes WHERE textbox_id = :textboxId")
    suspend fun getByTextBoxId(textboxId: String): TextBoxEntity?

    @Query("SELECT * FROM textboxes WHERE page_id = :pageId ORDER BY created_time ASC")
    suspend fun getByPage(pageId: String): List<TextBoxEntity>

    @Query("SELECT * FROM textboxes WHERE page_id = :pageId ORDER BY created_time ASC")
    fun observeByPage(pageId: String): Flow<List<TextBoxEntity>>

    @Query("SELECT * FROM textboxes WHERE layer_id = :layerId ORDER BY created_time ASC")
    suspend fun getByLayer(layerId: String): List<TextBoxEntity>

    @Query("""
        SELECT * FROM textboxes
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
    ): List<TextBoxEntity>

    /** Update text content only (most-frequent update path: user typing). */
    @Query("""
        UPDATE textboxes
        SET text = :newText,
            modified_time = :now,
            sync_timestamp = :now
        WHERE textbox_id = :textboxId
    """)
    suspend fun updateText(textboxId: String, newText: String, now: Long = System.currentTimeMillis())

    /** Update textbox style (font family / size / bold / italic / underline / colors). */
    @Query("""
        UPDATE textboxes
        SET font_family_id = :fontFamilyId,
            font_size = :fontSize,
            is_bold = :isBold,
            is_italic = :isItalic,
            underline_type = :underlineType,
            fill_color = :fillColor,
            text_color = :textColor,
            modified_time = :now,
            sync_timestamp = :now
        WHERE textbox_id = :textboxId
    """)
    suspend fun updateStyle(
        textboxId: String,
        fontFamilyId: Int,
        fontSize: Float,
        isBold: Boolean,
        isItalic: Boolean,
        underlineType: Int,
        fillColor: Int?,
        textColor: Int,
        now: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE textboxes
        SET left = :l, top = :t, right = :r, bottom = :b,
            rotation = :rotation,
            modified_time = :now,
            sync_timestamp = :now
        WHERE textbox_id = :textboxId
    """)
    suspend fun updateBounds(
        textboxId: String,
        l: Float, t: Float, r: Float, b: Float,
        rotation: Float,
        now: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM textboxes WHERE textbox_id IN (:textboxIds)")
    suspend fun batchDelete(textboxIds: List<String>): Int
}
