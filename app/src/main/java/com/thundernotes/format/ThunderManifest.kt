package com.thundernotes.format

import kotlinx.serialization.Serializable

/**
 * Top-level manifest of a `.thunder` file. Stored as `manifest.json` at the
 * ZIP root of every `.thunder` file (per `docs/thunder-format-proposal.md` Part A).
 *
 * The manifest is the FIRST entry read when opening a .thunder — it lets us
 * validate format/schema version before attempting to open the SQLite DB.
 *
 * Schema is JSON (via kotlinx-serialization-json). Field names are
 * snake_case to match the rest of our wire format (and the .thunder
 * file is intended to be debuggable by hand if needed).
 */
@Serializable
data class ThunderManifest(
    /**
     * Bumped when the ZIP layout itself changes (e.g., we add a new top-level
     * file to the ZIP). Old apps refuse to open files with a newer format version.
     */
    val formatVersion: Int = FORMAT_VERSION_CURRENT,

    /**
     * Bumped when the `note.sqlite` schema changes (Room migrations). Old apps
     * refuse to open if the schema version is newer than what they understand.
     */
    val schemaVersion: Int = SCHEMA_VERSION_CURRENT,

    /** App version that wrote this file (e.g. "0.1.0"). For diagnostics only. */
    val appVersion: String = "0.1.0",

    /** The note's UUID — mirrors [com.thundernotes.data.entity.NoteEntity.noteId]. */
    val noteId: String,

    /** Display name (mirrored for fast library rendering without opening SQLite). */
    val displayName: String,

    /** Total page count. */
    val pageCount: Int,

    /** Total file size of the .thunder ZIP in bytes (for the "Information" popup). */
    val fileSizeBytes: Long,

    /**
     * SHA-256 of `note.sqlite` (lowercase hex) — lets us detect corruption
     * when transferring the .thunder file (e.g., via cloud sync or USB).
     * The ZIP itself also has CRC32 checks; this is a stronger second line.
     */
    val checksum: String,

    /** Epoch ms when the .thunder was first created. */
    val createdAt: Long,

    /** Epoch ms when the .thunder was last written. */
    val modifiedAt: Long,

    /**
     * Free-form extras (e.g., source app version, sync metadata, future flags).
     * Use string values only — adding typed fields here would force a schema
     * bump; using extras keeps backward compatibility.
     */
    val extras: Map<String, String> = emptyMap()
) {
    companion object {
        /** Current ZIP-layout version this app writes. */
        const val FORMAT_VERSION_CURRENT = 1

        /** Current `note.sqlite` schema version this app writes. */
        const val SCHEMA_VERSION_CURRENT = 1
    }
}
