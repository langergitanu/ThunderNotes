package com.thundernotes.ui.canvas

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences-backed store for user-renamed palettes (spec §6.10.7:
 * "Sunflower — a user-created color set; can be renamed"). The default names
 * are "ThunderDark" / "ThunderLight" / "Sunflower"; the user can rename any
 * of them via the palette-switcher's long-press → Rename popup.
 *
 * The store is a process-wide singleton (initialized from the app context).
 * On the pure-JVM (unit tests), the [prefs] field stays null →
 * [currentNamesOrDefault] returns the defaults — so [EditorPalette.PALETTE_NAMES]
 * stays pure + testable.
 *
 * Pure-JVM tests: [PaletteNameStore.prefs] is null → [currentNamesOrDefault]
 * returns the defaults. The pure logic (default-or-override resolution) is
 * the testable surface; the SharedPreferences glue is thin + Android-only.
 */
object PaletteNameStore {
    private const val PREFS_NAME = "thunder_palette_names"
    private const val KEY_PREFIX = "palette_name_"
    private var prefs: SharedPreferences? = null

    /** Initialize with the app context (call from Application.onCreate). */
    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /** The current palette names, falling back to [defaults] when no override
     *  is set (or on the JVM where [prefs] is null). */
    fun currentNamesOrDefault(defaults: List<String>): List<String> {
        val p = prefs ?: return defaults
        return defaults.mapIndexed { idx, default ->
            p.getString("$KEY_PREFIX$idx", null) ?: default
        }
    }

    /** Rename the palette at [index] (0-based). Persists immediately. */
    fun rename(index: Int, newName: String) {
        val p = prefs ?: return
        p.edit().putString("$KEY_PREFIX$index", newName.trim().ifEmpty { return }).apply()
    }
}
