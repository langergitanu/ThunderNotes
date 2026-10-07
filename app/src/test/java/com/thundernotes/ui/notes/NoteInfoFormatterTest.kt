package com.thundernotes.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM unit tests for [NoteInfoFormatter] — no Android/Robolectric needed,
 * so this runs in the plain JUnit runner (fast).
 */
class NoteInfoFormatterTest {

    // ─── formatFileSize ─────────────────────────────────────────────────────

    @Test fun `zero bytes`() = assertEquals("0 B", NoteInfoFormatter.formatFileSize(0))

    @Test fun `sub-kilobyte`() = assertEquals("512 B", NoteInfoFormatter.formatFileSize(512))

    @Test fun `exactly one KB`() = assertEquals("1.0 KB", NoteInfoFormatter.formatFileSize(1024))

    @Test fun `low KB gets one decimal`() = assertEquals("1.5 KB", NoteInfoFormatter.formatFileSize(1536))

    @Test fun `tens of KB gets one decimal`() = assertEquals("823.0 KB", NoteInfoFormatter.formatFileSize(842_752))

    @Test fun `megabytes`() = assertEquals("1.4 MB", NoteInfoFormatter.formatFileSize(1_468_006L))

    @Test fun `large megabytes`() = assertEquals("18.3 MB", NoteInfoFormatter.formatFileSize(19_188_531L))

    @Test fun `gigabytes`() = assertEquals("1.1 GB", NoteInfoFormatter.formatFileSize(1_181_116_933L))

    @Test fun `negative size renders dash`() = assertEquals("—", NoteInfoFormatter.formatFileSize(-1))

    // ─── formatPageCount ────────────────────────────────────────────────────

    @Test fun `single page`() = assertEquals("1 page", NoteInfoFormatter.formatPageCount(1))

    @Test fun `zero pages still says one`() = assertEquals("1 page", NoteInfoFormatter.formatPageCount(0))

    @Test fun `multiple pages`() = assertEquals("12 pages", NoteInfoFormatter.formatPageCount(12))

    // ─── formatFolderLabel ──────────────────────────────────────────────────

    @Test fun `named folder`() = assertEquals("Folder: Math", NoteInfoFormatter.formatFolderLabel("Math"))

    @Test fun `null folder is root`() = assertEquals("Folder: Root", NoteInfoFormatter.formatFolderLabel(null))

    @Test fun `blank folder is root`() = assertEquals("Folder: Root", NoteInfoFormatter.formatFolderLabel("   "))
}
