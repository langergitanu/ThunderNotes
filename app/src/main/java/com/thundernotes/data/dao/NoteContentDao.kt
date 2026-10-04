package com.thundernotes.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.thundernotes.data.entity.NoteContentEntity

/**
 * Per-note top-level metadata DAO.
 *
 * Lives inside the `.thunder` ZIP's `note.sqlite` DB (see [com.thundernotes.data.db.NoteDatabase]).
 * There is exactly ONE row per note — the row's primary key is the noteId
 * itself (mirrors the parent `NoteEntity.noteId`).
 */
@Dao
interface NoteContentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(content: NoteContentEntity)

    @Update
    suspend fun update(content: NoteContentEntity)

    @Query("SELECT * FROM note_content WHERE note_id = :noteId")
    suspend fun getByNoteId(noteId: String): NoteContentEntity?

    @Query("SELECT * FROM note_content WHERE note_id = :noteId")
    fun observeByNoteId(noteId: String): kotlinx.coroutines.flow.Flow<NoteContentEntity?>

    @Query("""
        UPDATE note_content
        SET saved_page_index = :pageIndex
        WHERE note_id = :noteId
    """)
    suspend fun updateSavedPageIndex(noteId: String, pageIndex: Int)

    @Query("""
        UPDATE note_content
        SET zoom = :zoom
        WHERE note_id = :noteId
    """)
    suspend fun updateZoom(noteId: String, zoom: Float)

    @Query("""
        UPDATE note_content
        SET page_offset_x = :offsetX,
            page_offset_y = :offsetY
        WHERE note_id = :noteId
    """)
    suspend fun updatePageOffset(noteId: String, offsetX: Float, offsetY: Float)

    @Query("""
        UPDATE note_content
        SET default_page_id = :pageId
        WHERE note_id = :noteId
    """)
    suspend fun updateDefaultPage(noteId: String, pageId: String)

    @Query("""
        UPDATE note_content
        SET activated_page_layer_id = :layerId
        WHERE note_id = :noteId
    """)
    suspend fun updateActivatedLayer(noteId: String, layerId: String?)

    @Query("""
        UPDATE note_content
        SET unbounded_note = :unbounded
        WHERE note_id = :noteId
    """)
    suspend fun updateUnboundedNote(noteId: String, unbounded: Boolean)

    @Query("""
        UPDATE note_content
        SET pdf_info_id = :pdfInfoId
        WHERE note_id = :noteId
    """)
    suspend fun updatePdfInfo(noteId: String, pdfInfoId: String?)

    @Query("""
        UPDATE note_content
        SET extras_json = :extrasJson
        WHERE note_id = :noteId
    """)
    suspend fun updateExtras(noteId: String, extrasJson: String)

    @Query("DELETE FROM note_content WHERE note_id = :noteId")
    suspend fun deleteByNoteId(noteId: String)
}
