package com.thundernotes.data.entity

/**
 * Page background type. Per spec §6.6 CreateNotePage:
 *   "The app supports two page types — Blank and Lined — and two
 *    orientations — Portrait and Landscape. Infinite Canvas is not
 *    required for now and may be added in the future."
 *
 * Stored as an Int in the DB for forward compatibility (more types can be
 * added without a migration).
 */
enum class PageType(val rawValue: Int) {
    BLANK(0),
    LINED(1);

    companion object {
        fun fromRaw(value: Int): PageType = entries.firstOrNull { it.rawValue == value } ?: BLANK
    }
}

/** Page orientation. */
enum class PageOrientation(val rawValue: Int) {
    PORTRAIT(0),
    LANDSCAPE(1);

    companion object {
        fun fromRaw(value: Int): PageOrientation = entries.firstOrNull { it.rawValue == value } ?: PORTRAIT
    }
}
