package com.thundernotes.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.thundernotes.data.entity.TemplateEntity
import com.thundernotes.data.entity.TemplateSource
import kotlinx.coroutines.flow.Flow

/**
 * CRUD for [TemplateEntity] — cover/paper templates.
 *
 * Per spec §6.8: ≥50 preinstalled engineering-subject covers; user downloads
 * more from the Template Library.
 */
@Dao
interface TemplateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(template: TemplateEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(templates: List<TemplateEntity>): List<Long>

    @Update
    suspend fun update(template: TemplateEntity)

    @Delete
    suspend fun delete(template: TemplateEntity)

    @Query("DELETE FROM templates WHERE template_id = :templateId")
    suspend fun deleteByTemplateId(templateId: String)

    /** Permanently delete all templates with the given source (frees disk space). */
    @Query("DELETE FROM templates WHERE source = :source")
    suspend fun deleteAllWithSource(source: Int): Int

    /** Convenience: permanently delete all DOWNLOADED templates. */
    suspend fun deleteAllDownloaded() = deleteAllWithSource(TemplateSource.DOWNLOADED.rawValue)

    @Query("SELECT * FROM templates WHERE template_id = :templateId")
    suspend fun getByTemplateId(templateId: String): TemplateEntity?

    @Query("SELECT * FROM templates ORDER BY display_name ASC")
    fun observeAll(): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM templates WHERE category = :category ORDER BY display_name ASC")
    fun observeByCategory(category: String): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM templates WHERE source = :source ORDER BY display_name ASC")
    fun observeBySource(source: Int): Flow<List<TemplateEntity>>

    @Query("SELECT COUNT(*) FROM templates")
    suspend fun count(): Int

    @Query("""
        SELECT * FROM templates
        WHERE display_name LIKE '%' || :query || '%'
        ORDER BY display_name ASC
    """)
    fun searchByTitle(query: String): Flow<List<TemplateEntity>>
}
