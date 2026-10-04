package com.thundernotes.data.repository

import android.content.Context
import com.thundernotes.data.dao.TemplateDao
import com.thundernotes.data.entity.TemplateEntity
import com.thundernotes.data.entity.TemplateSource
import kotlinx.coroutines.flow.Flow

/**
 * Business-logic layer for cover/paper templates.
 *
 * Per spec §6.8: "There will be at least 50 pre-downloaded cover pages for
 * engineering subjects (math, electrical, physics, CSE, etc.). The user will
 * download the rest from the Template Library."
 *
 * Preinstalled templates ship under `assets/covers/` in the APK; the
 * [seedPreinstalledTemplates] method loads them into the DB on first launch
 * (called from `ThunderNotesApp.onCreate` once we wire it up — currently
 * deferred until the cover assets land in the repo).
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
     * Seed the DB with preinstalled templates from `assets/covers/`.
     *
     * The `assets/covers/index.json` file is read on first launch (when
     * `count() == 0`) and each entry becomes a [TemplateEntity] with
     * `source = PREINSTALLED`.
     *
     * Schema of `index.json`:
     *   [
     *     {
     *       "displayName": "Calculus Cover",
     *       "category": "math",
     *       "filePath": "math/calculus.png",
     *       "license": "OFL-1.1",
     *       "attribution": "..."
     *     }, ...
     *   ]
     *
     * **Stub for now:** the covers/ assets don't ship in the repo yet
     * (Phase 12 will add the 50+ covers). This method is a no-op until then.
     */
    suspend fun seedPreinstalledTemplates(context: Context) {
        if (templateDao.count() > 0) return  // already seeded
        val coversJson: String = try {
            context.assets.open("covers/index.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            // No covers/index.json asset yet (Phase 12 will add it).
            return
        }
        // TODO (Phase 12): parse the JSON + insert each template.
        // For now, no-op so we don't crash on first launch.
    }

    /** Permanently delete all DOWNLOADED templates (frees disk space). */
    suspend fun deleteAllDownloaded() = templateDao.deleteAllWithSource(TemplateSource.DOWNLOADED.rawValue)
}
