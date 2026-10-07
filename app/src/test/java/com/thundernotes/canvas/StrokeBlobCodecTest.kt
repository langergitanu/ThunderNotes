package com.thundernotes.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM round-trip tests for [StrokeBlobCodec] (StrokeRecord ↔ InkStrokeProto bytes). */
class StrokeBlobCodecTest {

    private fun sample(pageId: String = "page-0") = StrokeRecord(
        id = "stroke-abc",
        layerId = "layer-0",
        pageId = pageId,
        creationTime = 1_700_000_000L,
        brushSize = 2.5f,
        brushColorArgb = 0xFF1E88E5.toInt(),
        brushEpsilon = 0.1f,
        brushFamilyId = "thunder-ballpoint-v1",
        toolType = 1, // STYLUS
        strokeUnitLengthCm = 12.34f,
        inputXy = listOf(1f, 2f, 3f, 4f, 5f, 6f),       // 3 points
        inputAttrs = listOf(                              // 5 attrs × 3 points
            0f, 1f, 0f, 0f, 0f,
            10f, 0.8f, 0.1f, 0f, 0f,
            20f, 0.6f, 0f, 0f, 0f,
        ),
        strokeToWorld = listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), // identity 3×3
        worldToView = listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        behaviorParams = mapOf("pressure_correct" to 0.5f, "speed_correct" to 1.2f),
    )

    @Test fun `round-trip preserves every field`() {
        val original = sample()
        val blob = StrokeBlobCodec.encode(original)
        val decoded = StrokeBlobCodec.decode(blob, pageId = "page-0")

        assertNotNull(decoded)
        val d = decoded!!
        assertEquals(original.id, d.id)
        assertEquals(original.layerId, d.layerId)
        assertEquals("page-0", d.pageId)   // pageId is supplied by caller
        assertEquals(original.creationTime, d.creationTime)
        assertEquals(original.brushSize, d.brushSize, 0.0001f)
        assertEquals(original.brushColorArgb, d.brushColorArgb)
        assertEquals(original.brushEpsilon, d.brushEpsilon, 0.0001f)
        assertEquals(original.brushFamilyId, d.brushFamilyId)
        assertEquals(original.toolType, d.toolType)
        assertEquals(original.strokeUnitLengthCm, d.strokeUnitLengthCm, 0.0001f)
        assertEquals(original.inputXy, d.inputXy)
        assertEquals(original.inputAttrs, d.inputAttrs)
        assertEquals(original.strokeToWorld, d.strokeToWorld)
        assertEquals(original.worldToView, d.worldToView)
        assertEquals(original.behaviorParams, d.behaviorParams)
    }

    @Test fun `round-trip with minimal single-point stroke`() {
        val minimal = StrokeRecord(
            id = "s1", layerId = "l0", pageId = "p0",
            brushSize = 1f, brushColorArgb = 0xFF000000.toInt(),
            brushEpsilon = 0.1f, brushFamilyId = "thunder-highlighter-v1",
            toolType = 1,
            inputXy = listOf(1f, 2f),                  // 1 point
            inputAttrs = listOf(0f, 1f, 0f, 0f, 0f),   // 5 attrs
        )
        val decoded = StrokeBlobCodec.decode(StrokeBlobCodec.encode(minimal), "p0")
        assertNotNull(decoded)
        assertEquals(1, decoded!!.pointCount)
        assertTrue(decoded.isWellFormed)
    }

    @Test fun `decode rejects non-thunder blob`() {
        val garbage = "not a stroke".toByteArray()
        assertNull(StrokeBlobCodec.decode(garbage, "p0"))
    }

    @Test fun `decode rejects empty blob`() {
        assertNull(StrokeBlobCodec.decode(ByteArray(0), "p0"))
    }

    @Test fun `encode then decode preserves point count + well-formedness`() {
        val s = sample()
        val decoded = StrokeBlobCodec.decode(StrokeBlobCodec.encode(s), "p0")!!
        assertEquals(3, decoded.pointCount)
        assertTrue(decoded.isWellFormed)
    }

    @Test fun `encode rejects a malformed record and the guard flags it first`() {
        // InkStrokeProto itself enforces the 5-attrs-per-point rule at construction,
        // so encode() throws for a bad record. StrokeRecord.isWellFormed flags it first.
        val bad = sample().copy(inputAttrs = listOf(0f, 1f, 2f)) // 3 attrs, not 5×3
        assertFalse(bad.isWellFormed)
        assertThrows(IllegalArgumentException::class.java) {
            StrokeBlobCodec.encode(bad)
        }
    }
}
