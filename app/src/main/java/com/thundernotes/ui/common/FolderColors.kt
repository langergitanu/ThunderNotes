package com.thundernotes.ui.common

import com.thundernotes.R

/**
 * Shared folder-color palette used by both [com.thundernotes.ui.create.CreateFolderFragment]
 * (the color picker) and [com.thundernotes.ui.folders.FolderAdapter] (the card rendering).
 *
 * Single source of truth so the picker + the adapter don't drift.
 *
 * Per BigPickle S2-2: the original palette had duplicate hues —
 * `thunder_primary_dark` (#920025, dark crimson) was a dupe of `thunder_primary`
 * (#DC2646, crimson), and `thunder_secondary_dark` (#059669, dark emerald) was
 * a dupe of `thunder_secondary` (#10B981, emerald). Replaced both with new
 * distinct hues (purple + teal) for 6 visually distinct colors.
 */
object FolderColors {
    /** 6 visually distinct folder colors. Indexed by [com.thundernotes.data.entity.FolderEntity.color]. */
    val COLORS = intArrayOf(
        R.color.thunder_primary,        // 0: crimson
        R.color.thunder_secondary,      // 1: emerald
        R.color.thunder_tertiary,       // 2: amber
        R.color.thunder_accent_blue,    // 3: blue
        R.color.folder_color_purple,    // 4: purple (NEW — S2-2 fix)
        R.color.folder_color_teal,      // 5: teal   (NEW — S2-2 fix)
    )
}
