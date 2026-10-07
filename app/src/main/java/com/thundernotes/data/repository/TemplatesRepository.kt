package com.thundernotes.data.repository

import com.thundernotes.data.dao.TemplateDao
import com.thundernotes.data.entity.TemplateEntity
import com.thundernotes.data.entity.TemplateSource
import com.thundernotes.data.seed.TemplatesSeed
import kotlinx.coroutines.flow.Flow

/**
 * Business-logic layer for cover/paper templates.
 *
 * Per spec §6.8: "There will be at least 50 pre-downloaded cover pages for
 * engineering subjects (math, electrical, physics, CSE, etc.). The user will
 * download the rest from the Template Library."
 *
 * The 60 preinstalled templates are defined in [TemplatesSeed] (names mirror
 * the CoverSelectionPage mock — Topology-Torus, Complex Plane, Venn, Matrix,
 * Rhombus, …) + seeded into the DB on first launch by [seedPreinstalledTemplates]
 * (called from `ThunderNotesApp.onCreate`). The cover preview is rendered in
 * the UI from the category + colorIndex (no real cover-image asset is shipped
 * yet — that's a later content pack; the metadata is enough to populate the
 * picker with 50+ items now).
 */
class TemplatesRepository(
    private val templateDao: TemplateDao,
) {

    fun observeAll(): Flow<List<TemplateEntity>> = templateDao.observeAll()
    fun observeByCategory(category: String): Flow<List<TemplateEntity>> =
        templateDao.observeByCategory(category)
    fun observeBySource(source: Int): Flow<List<TemplateEntity>> =
        templateDao.observeBySource(source)

    suspend fun getTemplate(templateId: String): TemplateEntity? =
        templateDao.getByTemplateId(templateId)

    suspend fun count(): Int = templateDao.count()

    /**
     * Seed the DB with the 60 preinstalled engineering-subject templates from
     * [TemplatesSeed]. Idempotent — no-ops if the DB already has templates.
     *
     * Each template becomes a [TemplateEntity] with `source = PREINSTALLED`;
     * `filePath` is empty (the cover preview is generated in the UI from
     * category + colorIndex until real cover-image assets ship).
     */
    suspend fun seedPreinstalledTemplates() {
        if (templateDao.count() > 0) return  // already seeded — idempotent
        val rows = TemplatesSeed.ALL.map { seed ->
            TemplateEntity(
                displayName = seed.displayName,
                category = seed.category,
                source = TemplateSource.PREINSTALLED.rawValue,
                filePath = "",
                thumbnailPath = null,
            )
        }
        templateDao.insertAll(rows)
    }

    /** Permanently delete all DOWNLOADED templates (frees disk space). */
    suspend fun deleteAllDownloaded() = templateDao.deleteAllWithSource(TemplateSource.DOWNLOADED.rawValue)
}
