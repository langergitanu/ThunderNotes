package com.thundernotes.canvas

import com.thundernotes.format.proto.InkStrokeProto
import com.thundernotes.format.proto.InkStrokeSerializer

/**
 * Lossless codec between the in-memory [StrokeRecord] and the on-disk
 * [InkStrokeProto] bytes (the `.thunder` stroke blob format).
 *
 * The proto schema + serializer are already implemented (Phase 3,
 * mirroring Notein's 14-field `InkStrokeProtoSerializer` per the
 * architecture plan §3 / Notein README §3). This codec is the thin adapter
 * the editor's save/load path uses: a finished stroke → [encode] →
 * `StrokeEntity.inkStrokeBlob`; load → [decode] → redraw.
 *
 * Pure + unit-testable (no Android/Ink dependency). The round-trip is
 * verified by [StrokeBlobCodecTest].
 */
object StrokeBlobCodec {

    fun encode(record: StrokeRecord): ByteArray {
        val proto = InkStrokeProto(
            id = record.id,
            layerId = record.layerId,
            creationTime = record.creationTime,
            brushSize = record.brushSize,
            brushColor = record.brushColorArgb,
            brushEpsilon = record.brushEpsilon,
            brushFamilyId = record.brushFamilyId,
            toolType = record.toolType,
            strokeUnitLengthCm = record.strokeUnitLengthCm,
            inputXy = record.inputXy,
            inputAttrs = record.inputAttrs,
            strokeToWorld = record.strokeToWorld,
            worldToView = record.worldToView,
            behaviorParams = record.behaviorParams,
        )
        return InkStrokeSerializer.serialize(proto)
    }

    /** Decode a blob back to a [StrokeRecord]. Returns null if the blob is
     *  malformed (not a ThunderNotes stroke blob). `pageId` is supplied by
     *  the caller because it's not part of the proto (it's a per-note DB column). */
    fun decode(blob: ByteArray, pageId: String): StrokeRecord? {
        if (!InkStrokeSerializer.isThunderNotesBlob(blob)) return null
        val proto = runCatching { InkStrokeSerializer.deserialize(blob) }.getOrNull() ?: return null
        return StrokeRecord(
            id = proto.id,
            layerId = proto.layerId,
            pageId = pageId,
            creationTime = proto.creationTime,
            brushSize = proto.brushSize,
            brushColorArgb = proto.brushColor,
            brushEpsilon = proto.brushEpsilon,
            brushFamilyId = proto.brushFamilyId,
            toolType = proto.toolType,
            strokeUnitLengthCm = proto.strokeUnitLengthCm,
            inputXy = proto.inputXy,
            inputAttrs = proto.inputAttrs,
            strokeToWorld = proto.strokeToWorld,
            worldToView = proto.worldToView,
            behaviorParams = proto.behaviorParams,
        )
    }
}
