package com.thundernotes.format

import com.thundernotes.format.proto.InkStrokeProto
import com.thundernotes.format.proto.InkStrokeSerializer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip test for [InkStrokeSerializer] — verifies that a stroke
 * serializes to a magic-prefixed blob and deserializes back to an equal
 * [InkStrokeProto] instance.
 *
 * Run with: `./gradlew :app:testDebugUnitTest --tests *.InkStrokeSerializerTest`
 */
class InkStrokeSerializerTest {

    @Test
    fun `round-trip preserves all 14 fields`() {
        val original = InkStrokeProto(
            id = "stroke-001",
            layerId = "layer-001",
            creationTime = 1_700_000_000_000L,
            brushSize = 2.5f,
            brushColor = 0xFFFF0000.toInt(),  // red
            brushEpsilon = 0.15f,
            brushFamilyId = "thunder-ballpoint-v1",
            toolType = 1,  // STYLUS
            strokeUnitLengthCm = 12.34f,
            inputXy = listOf(0f, 0f, 10f, 5f, 20f, 10f),  // 3 points
            inputAttrs = listOf(
                // 5 attrs × 3 points = 15 floats
                0f, 0.5f, 0f, 0f, 0f,
                16f, 0.6f, 0f, 0f, 0f,
                32f, 0.7f, 0f, 0f, 0f
            ),
            strokeToWorld = listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),  // identity
            worldToView = listOf(2f, 0f, 0f, 0f, 2f, 0f, 0f, 0f, 1f),   // 2× scale
            behaviorParams = mapOf("pressure_correct" to 1.2f, "speed_correct" to 0.8f)
        )

        val blob = InkStrokeSerializer.serialize(original)
        assertTrue("Blob should have magic prefix",
            InkStrokeSerializer.isThunderNotesBlob(blob))

        val deserialized = InkStrokeSerializer.deserialize(blob)
        assertEquals(original, deserialized)
        assertEquals(3, deserialized.pointCount)
    }

    @Test
    fun `blob starts with TNPD magic bytes`() {
        val stroke = makeMinimalStroke()
        val blob = InkStrokeSerializer.serialize(stroke)
        // Prefix is 4 bytes; rest is protobuf payload.
        assertTrue(blob.size > 4)
        assertArrayEquals(
            ThunderFormatConstants.STROKE_MAGIC_BYTES,
            blob.copyOfRange(0, 4)
        )
    }

    @Test
    fun `isThunderNotesBlob returns false for non-TN blobs`() {
        assertFalse(InkStrokeSerializer.isThunderNotesBlob(byteArrayOf(0, 0, 0, 0)))
        assertFalse(InkStrokeSerializer.isThunderNotesBlob(byteArrayOf()))
        // Notein's "NIPB" prefix should NOT be recognized as ours.
        assertFalse(InkStrokeSerializer.isThunderNotesBlob(byteArrayOf(0x4E, 0x49, 0x50, 0x42)))
    }

    @Test
    fun `deserialize rejects blobs with bad magic header`() {
        val badBlob = byteArrayOf(0x4E, 0x49, 0x50, 0x42, 0x00) // NIPB + 0 byte
        assertThrows(IllegalArgumentException::class.java) {
            InkStrokeSerializer.deserialize(badBlob)
        }
    }

    @Test
    fun `deserialize rejects too-small blobs`() {
        assertThrows(IllegalArgumentException::class.java) {
            InkStrokeSerializer.deserialize(byteArrayOf(0x54, 0x4E, 0x50)) // 3 bytes
        }
    }

    @Test
    fun `init block rejects uneven input_xy`() {
        assertThrows(IllegalArgumentException::class.java) {
            InkStrokeProto(
                id = "x", layerId = "l", creationTime = 0L,
                brushSize = 1f, brushColor = 0, brushEpsilon = 0.1f,
                brushFamilyId = "thunder-ballpoint-v1", toolType = 1,
                strokeUnitLengthCm = 0f,
                inputXy = listOf(0f, 0f, 10f)  // 3 elements — odd, invalid
            )
        }
    }

    @Test
    fun `init block rejects mismatched input_attrs count`() {
        assertThrows(IllegalArgumentException::class.java) {
            InkStrokeProto(
                id = "x", layerId = "l", creationTime = 0L,
                brushSize = 1f, brushColor = 0, brushEpsilon = 0.1f,
                brushFamilyId = "thunder-ballpoint-v1", toolType = 1,
                strokeUnitLengthCm = 0f,
                inputXy = listOf(0f, 0f, 10f, 10f),  // 2 points
                inputAttrs = listOf(0f, 0f, 0f)     // 3 floats — should be 10
            )
        }
    }

    @Test
    fun `init block rejects 8-element matrix`() {
        assertThrows(IllegalArgumentException::class.java) {
            InkStrokeProto(
                id = "x", layerId = "l", creationTime = 0L,
                brushSize = 1f, brushColor = 0, brushEpsilon = 0.1f,
                brushFamilyId = "thunder-ballpoint-v1", toolType = 1,
                strokeUnitLengthCm = 0f,
                inputXy = listOf(0f, 0f),
                strokeToWorld = listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f)  // 8 elements, not 9
            )
        }
    }

    @Test
    fun `empty matrices are accepted as identity`() {
        val stroke = InkStrokeProto(
            id = "x", layerId = "l", creationTime = 0L,
            brushSize = 1f, brushColor = 0, brushEpsilon = 0.1f,
            brushFamilyId = "thunder-ballpoint-v1", toolType = 1,
            strokeUnitLengthCm = 0f,
            inputXy = listOf(0f, 0f)
            // strokeToWorld + worldToView default to emptyList() — accepted as identity.
        )
        assertEquals(1, stroke.pointCount)
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private fun makeMinimalStroke() = InkStrokeProto(
        id = "minimal",
        layerId = "layer-001",
        creationTime = 0L,
        brushSize = 1f,
        brushColor = 0xFF000000.toInt(),
        brushEpsilon = 0.1f,
        brushFamilyId = "thunder-ballpoint-v1",
        toolType = 1,
        strokeUnitLengthCm = 0f,
        inputXy = listOf(0f, 0f)
    )
}
