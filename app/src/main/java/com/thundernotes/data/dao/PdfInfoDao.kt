package com.thundernotes.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.thundernotes.data.entity.PdfInfoEntity
import kotlinx.coroutines.flow.Flow

/**
 * CRUD for [PdfInfoEntity] — PDFs imported as page backgrounds.
 */
@Dao
interface PdfInfoDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(pdfInfo: PdfInfoEntity): Long

    @Update
    suspend fun update(pdfInfo: PdfInfoEntity)

    @Delete
    suspend fun delete(pdfInfo: PdfInfoEntity)

    @Query("DELETE FROM pdf_info WHERE pdf_info_id = :pdfInfoId")
    suspend fun deleteByPdfInfoId(pdfInfoId: String)

    @Query("SELECT * FROM pdf_info WHERE pdf_info_id = :pdfInfoId")
    suspend fun getByPdfInfoId(pdfInfoId: String): PdfInfoEntity?

    @Query("SELECT * FROM pdf_info WHERE pdf_info_id = :pdfInfoId")
    fun observeByPdfInfoId(pdfInfoId: String): Flow<PdfInfoEntity?>

    @Query("SELECT * FROM pdf_info")
    suspend fun getAll(): List<PdfInfoEntity>

    @Query("SELECT * FROM pdf_info")
    fun observeAll(): Flow<List<PdfInfoEntity>>

    @Query("""
        UPDATE pdf_info
        SET current_page = :currentPage
        WHERE pdf_info_id = :pdfInfoId
    """)
    suspend fun updateCurrentPage(pdfInfoId: String, currentPage: Int)

    @Query("""
        UPDATE pdf_info
        SET scale = :scale,
            rotation = :rotation,
            offset_x = :offsetX,
            offset_y = :offsetY
        WHERE pdf_info_id = :pdfInfoId
    """)
    suspend fun updateTransform(
        pdfInfoId: String,
        scale: Float,
        rotation: Int,
        offsetX: Float,
        offsetY: Float
    )
}
