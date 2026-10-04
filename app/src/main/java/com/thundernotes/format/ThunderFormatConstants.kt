package com.thundernotes.format

/**
 * Constants for the `.thunder` file format.
 *
 * A `.thunder` file is a ZIP archive containing:
 *   - `manifest.json`     — see [ThunderManifest]
 *   - `note.sqlite`       — Room DB (per-note content; see NoteDatabase)
 *   - `preview.png`       — small thumbnail for the library grid
 *   - `assets/`           — embedded images, PDFs, audio (self-contained)
 *
 * See `docs/thunder-format-proposal.md` Part A for the full design.
 */
object ThunderFormatConstants {

    /** File extension for ThunderNotes documents. */
    const val EXTENSION = "thunder"

    /** MIME type — per spec, treated as application/octet-stream (Notein does the same). */
    const val MIME_TYPE = "application/octet-stream"

    /** ZIP entry paths inside the .thunder ZIP archive. */
    object Entry {
        const val MANIFEST = "manifest.json"
        const val NOTE_SQLITE = "note.sqlite"
        const val PREVIEW_PNG = "preview.png"
        const val ASSETS_DIR = "assets/"
    }

    /** Magic bytes at the start of every stroke blob (ASCII "TNPD" =
     *  ThunderNotes Protobuf Data). Distinct from Notein's "NIPB" so a
     *  blob's source app can be unambiguously identified. */
    val STROKE_MAGIC_BYTES = byteArrayOf(0x54, 0x4E, 0x50, 0x44)

    /** ASCII string form of [STROKE_MAGIC_BYTES] for error messages. */
    const val STROKE_MAGIC_STRING = "TNPD"

    /** Minimum valid stroke blob length: 4 bytes magic + at least 0 protobuf payload. */
    const val STROKE_MIN_BLOB_LENGTH = 4
}
