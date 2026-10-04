package com.thundernotes.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A preinstalled or downloaded cover/paper template.
 *
 * Per spec §6.8 CoverSelectionPage:
 *   "There will be at least 50 pre-downloaded cover pages for engineering
 *    subjects (math, electrical, physics, CSE, etc.). The user will download
 *    the rest from the Template Library."
 *
 * Cover templates are PNG files under `assets/covers/` (preinstalled) or
 * downloaded to `getExternalFilesDir(null)/templates/` on demand. The
 * [filePath] is relative to one of those roots depending on [source].
 */
@Entity(
    tableName = "templates",
    indices = [
        Index(value = ["category"]),
        Index(value = ["source"]),
        Index(value = ["display_name"])
    ]
)
data class TemplateEntity(
    @PrimaryKey
    @ColumnInfo(name = "template_id")
    val templateId: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "display_name")
    val displayName: String,

    /** Subject area — one of the engineering subjects named in the spec
     * (math, electrical, physics, cse, etc.). Free-form string for now so
     * we can add categories without a migration. */
    @ColumnInfo(name = "category")
    val category: String,

    /** Where the template lives.
     * 0 = preinstalled in `assets/covers/` (read-only, ships with APK).
     * 1 = downloaded to external storage (deletable). */
    @ColumnInfo(name = "source")
    val source: Int = TemplateSource.PREINSTALLED.rawValue,

    /** Path relative to the source root. */
    @ColumnInfo(name = "file_path")
    val filePath: String,

    /** Optional thumbnail path (if different from the cover itself). */
    @ColumnInfo(name = "thumbnail_path")
    val thumbnailPath: String? = null,

    /** SPDX identifier or short license name (e.g. "OFL-1.1", "CC0-1.0").
     * We refuse to ship templates without a clear license. */
    @ColumnInfo(name = "license")
    val license: String = "OFL-1.1",

    /** Author / source attribution (for the credits screen). */
    @ColumnInfo(name = "attribution")
    val attribution: String? = null,

    @ColumnInfo(name = "created_time")
    val createdTime: Long = System.currentTimeMillis()
)

/** Where a template lives on disk. */
enum class TemplateSource(val rawValue: Int) {
    /** In the APK's `assets/covers/` directory (read-only). */
    PREINSTALLED(0),

    /** In the app's external files dir under `templates/` (deletable). */
    DOWNLOADED(1);
}
