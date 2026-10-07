package com.thundernotes.ui.notes

import java.util.Locale

/**
 * Pure formatting helpers for the Note "Information" popup (spec §6.2.4
 * overflow → Information). Extracted from the view so the size + date
 * formatting is unit-testable without an emulator.
 *
 * Pattern adapted from Samsung Notes' "Information" popup, which surfaces
 * file name / size / created / modified from the document metadata row —
 * here we read the same fields off [com.thundernotes.data.entity.NoteEntity].
 */
object NoteInfoFormatter {

    /** Human-readable file size: "1.4 MB", "823 KB", "512 B". */
    fun formatFileSize(bytes: Long): String {
        if (bytes < 0) return "—"
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var unitIdx = 0
        while (value >= 1024.0 && unitIdx < units.lastIndex) {
            value /= 1024.0
            unitIdx++
        }
        // 1 decimal for MB+, 0 decimals for KB below 10
        val pattern = if (unitIdx == 0 && value < 10.0) "%.1f" else "%.1f"
        return String.format(Locale.US, pattern, value) + " " + units[unitIdx]
    }

    /** Page-count line: "12 pages" / "1 page". */
    fun formatPageCount(pages: Int): String =
        if (pages <= 1) "1 page" else "$pages pages"

    /** Folder label: "Folder: <name>" or "Folder: Root". */
    fun formatFolderLabel(folderName: String?): String =
        "Folder: " + (folderName?.takeIf { it.isNotBlank() } ?: "Root")
}
