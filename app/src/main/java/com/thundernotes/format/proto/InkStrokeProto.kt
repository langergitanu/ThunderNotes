package com.thundernotes.format.proto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Serialized form of an ink stroke, stored as a BLOB in
 * [com.thundernotes.data.entity.StrokeEntity.inkStrokeBlob].
 *
 * Pattern adopted from Notein's `InkStrokeProto` (readable in
 * `penkit/serialization/ink/proto/InkStrokeProto.java`; see
 * `docs/Notein-README.md` §3). We mirror Notein's 14 protobuf fields
 * with the same field numbers so we can study/compare stroke blobs
 * across both apps.
 *
 * **CRITICAL: Field numbers (@ProtoNumber) MUST stay stable.** Changing a
 * field number breaks binary compatibility with all existing .thunder
 * files in the wild. Add new fields with new field numbers (e.g. 15, 16, ...);
 * never reuse a retired number.
 *
 * Field map (mirrors Notein's):
 *   1   id                     String (UUID)
 *   2   layer_id               String
 *   3   creation_time          Long (epoch ms)
 *   4   brush_size              Float
 *   5   brush_color             Int (ARGB)
 *   6   brush_epsilon           Float
 *   7   brush_family_id         String (e.g. "thunder-ballpoint-v1")
 *   8   tool_type               Int (see [com.thundernotes.data.entity.ToolType])
 *   9   stroke_unit_length_cm   Float
 *   10  input_xy                List<Float> (flat [x0, y0, x1, y1, ...])
 *   11  input_attrs             List<Float> (5 floats per point:
 *                                 timestamp_ms, pressure, tilt, orientation, ???)
 *   12  stroke_to_world         List<Float> (9-value affine Matrix; empty = identity)
 *   13  world_to_view            List<Float> (9-value affine Matrix; empty = identity)
 *   14  behavior_params          Map<String, Float> (e.g. {"pressure_correct": 1.2})
 *
 * **Invariants** (enforced in init block):
 *   - input_xy.size must be 2 × N (where N is point count) and N ≥ 1.
 *   - input_attrs.size must be 5 × N (or empty if not yet populated).
 *   - stroke_to_world / world_to_view must be empty (identity) or 9 elements.
 */
@Serializable
data class InkStrokeProto(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) @SerialName("layer_id") val layerId: String,
    @ProtoNumber(3) @SerialName("creation_time") val creationTime: Long,
    @ProtoNumber(4) @SerialName("brush_size") val brushSize: Float,
    @ProtoNumber(5) @SerialName("brush_color") val brushColor: Int,
    @ProtoNumber(6) @SerialName("brush_epsilon") val brushEpsilon: Float,
    @ProtoNumber(7) @SerialName("brush_family_id") val brushFamilyId: String,
    @ProtoNumber(8) @SerialName("tool_type") val toolType: Int,
    @ProtoNumber(9) @SerialName("stroke_unit_length_cm") val strokeUnitLengthCm: Float,
    @ProtoNumber(10) @SerialName("input_xy") val inputXy: List<Float> = emptyList(),
    @ProtoNumber(11) @SerialName("input_attrs") val inputAttrs: List<Float> = emptyList(),
    @ProtoNumber(12) @SerialName("stroke_to_world") val strokeToWorld: List<Float> = emptyList(),
    @ProtoNumber(13) @SerialName("world_to_view") val worldToView: List<Float> = emptyList(),
    @ProtoNumber(14) @SerialName("behavior_params") val behaviorParams: Map<String, Float> = emptyMap()
) {
    /** Number of points in the stroke (input_xy.size / 2). */
    val pointCount: Int get() = inputXy.size / 2

    init {
        require(inputXy.isNotEmpty()) { "Stroke must have at least 1 point; got empty input_xy." }
        require(inputXy.size % 2 == 0) {
            "input_xy must be a flat [x0, y0, x1, y1, ...] list — size must be even; got ${inputXy.size}."
        }
        val expected = pointCount * 5
        require(inputAttrs.isEmpty() || inputAttrs.size == expected) {
            "input_attrs must be empty or have exactly 5 floats per point; expected $expected, got ${inputAttrs.size}."
        }
        require(strokeToWorld.isEmpty() || strokeToWorld.size == 9) {
            "stroke_to_world must be empty (identity) or exactly 9 elements; got ${strokeToWorld.size}."
        }
        require(worldToView.isEmpty() || worldToView.size == 9) {
            "world_to_view must be empty (identity) or exactly 9 elements; got ${worldToView.size}."
        }
    }
}
