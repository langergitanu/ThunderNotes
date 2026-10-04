package com.thundernotes.format.proto

import com.thundernotes.format.ThunderFormatConstants
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * Serializes/deserializes [InkStrokeProto] to/from the BLOB stored in
 * [com.thundernotes.data.entity.StrokeEntity.inkStrokeBlob].
 *
 * Pattern adopted from Notein's `InkStrokeProtoSerializer` (readable in
 * `penkit/serialization/ink/proto/InkStrokeProtoSerializer.java`; see
 * `docs/Notein-README.md` §3). Notein uses magic-bytes prefix
 * `{78, 73, 80, 66}` = ASCII "NIPB" (Notein Protobuf).
 *
 * Our magic bytes: ASCII "TNPD" (ThunderNotes Protobuf Data) — distinct
 * from Notein's so a blob's source app is unambiguously identifiable.
 *
 * **Wire format compatibility:** the bytes after the magic header are
 * standard protobuf wire format (via kotlinx-serialization-protobuf),
 * so other tools that read protobuf can decode them.
 *
 * Two modes (matching Notein's API shape):
 *   - [serialize] / [deserialize]              — raw bytes (DB BLOB)
 *   - [serializeToBase64] / [deserializeFromBase64] — for text channels
 *
 * We don't ship a Base64 mode yet (the canvas always reads from the DB);
 * the API is here for forward-compat with future IPC injection over intent
 * extras (where ByteArray isn't directly serializable).
 */
object InkStrokeSerializer {

    /** Magic bytes prepended to every serialized stroke blob. ASCII "TNPD". */
    private val MAGIC_BYTES = ThunderFormatConstants.STROKE_MAGIC_BYTES

    /** Minimum valid blob length: 4 bytes magic + at least 0 protobuf payload. */
    private const val MIN_BLOB_LENGTH = ThunderFormatConstants.STROKE_MIN_BLOB_LENGTH

    @OptIn(ExperimentalSerializationApi::class)
    private val protoBuf = ProtoBuf {
        // Encode defaults so a stroke's empty optionals don't disappear
        // from the blob (avoids "missing field" decode errors when reading
        // blobs from older app versions).
        encodeDefaults = true
    }

    /** Serialize a stroke to a magic-prefixed blob. */
    fun serialize(stroke: InkStrokeProto): ByteArray {
        val payload = protoBuf.encodeToByteArray(InkStrokeProto.serializer(), stroke)
        return MAGIC_BYTES + payload
    }

    /**
     * Deserialize a stroke from a magic-prefixed blob. Throws
     * [IllegalArgumentException] on bad magic or [kotlinx.serialization.SerializationException]
     * on decode failure.
     */
    fun deserialize(blob: ByteArray): InkStrokeProto {
        require(blob.size >= MIN_BLOB_LENGTH) {
            "Stroke blob too small: ${blob.size} bytes (minimum is $MIN_BLOB_LENGTH for the magic header)."
        }
        require(blob.copyOfRange(0, 4).contentEquals(MAGIC_BYTES)) {
            "Bad magic header: expected '${ThunderFormatConstants.STROKE_MAGIC_STRING}', " +
                "got '${String(blob, 0, minOf(4, blob.size), Charsets.US_ASCII)}'."
        }
        val payload = blob.copyOfRange(4, blob.size)
        return protoBuf.decodeFromByteArray(InkStrokeProto.serializer(), payload)
    }

    /** True if the blob starts with the ThunderNotes magic header. */
    fun isThunderNotesBlob(blob: ByteArray): Boolean =
        blob.size >= MIN_BLOB_LENGTH && blob.copyOfRange(0, 4).contentEquals(MAGIC_BYTES)

    /** Serialize to a Base64 string (for text channels like intent extras).
     *  Uses java.util.Base64 (no line breaks) — available on both JVM (for
     *  unit tests) and Android API 26+ (our minSdk is 31). */
    fun serializeToBase64(stroke: InkStrokeProto): String {
        val blob = serialize(stroke)
        return java.util.Base64.getEncoder().encodeToString(blob)
    }

    /** Deserialize from a Base64 string. */
    fun deserializeFromBase64(b64: String): InkStrokeProto {
        val blob = java.util.Base64.getDecoder().decode(b64)
        return deserialize(blob)
    }
}
