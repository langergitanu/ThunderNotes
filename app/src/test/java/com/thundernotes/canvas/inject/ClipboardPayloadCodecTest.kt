package com.thundernotes.canvas.inject

import com.thundernotes.canvas.StrokeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM round-trip tests for [ClipboardPayloadCodec] — the external
 * live-injection JSON wire format (thunder-format-proposal Part B2).
 */
class ClipboardPayloadCodecTest {

    private fun stroke(id: String) = StrokeRecord(
        id = id, layerId = "l0", pageId = "external",
        brushSize = 2.5f, brushColorArgb = 0xFF1E88E5.toInt(),
        brushEpsilon = 0.1f, brushFamilyId = "thunder-ballpoint-v1", toolType = 1,
        inputXy = listOf(0f, 0f, 5f, 5f),
        inputAttrs = listOf(0f, 1f, 0f, 0f, 0f, 10f, 0.8f, 0f, 0f, 0f),
    )

    @Test fun `stroke group round-trips through encode → decode`() {
        val original = ClipboardItem.StrokeGroup(
            strokes = listOf(stroke("s1"), stroke("s2")),
            bbox = floatArrayOf(0f, 0f, 5f, 5f),
        )
        val json = ClipboardPayloadCodec.encode(original)
        val decoded = ClipboardPayloadCodec.decode(json)
        assertNotNull(decoded)
        assertTrue(decoded is ClipboardItem.StrokeGroup)
        val dg = decoded as ClipboardItem.StrokeGroup
        assertEquals(2, dg.strokes.size)
        // Stroke fields survive the blob → base64 → blob round-trip.
        assertEquals("s1", dg.strokes[0].id)
        assertEquals(0xFF1E88E5.toInt(), dg.strokes[0].brushColorArgb)
        assertEquals("thunder-ballpoint-v1", dg.strokes[0].brushFamilyId)
        assertEquals(listOf(0f, 0f, 5f, 5f), dg.strokes[0].inputXy)
        // bbox round-trips
        assertEquals(original.bbox.toList(), dg.bbox.toList())
    }

    @Test fun `textbox round-trips through encode → decode`() {
        val original = ClipboardItem.TextBox(
            text = "∫ sin(x) dx", fontFamily = 2, bold = true, italic = false,
            underline = 0, x = 10f, y = 20f, bbox = floatArrayOf(10f, 20f, 110f, 40f),
        )
        val json = ClipboardPayloadCodec.encode(original)
        val decoded = ClipboardPayloadCodec.decode(json)
        assertNotNull(decoded)
        assertTrue(decoded is ClipboardItem.TextBox)
        val dt = decoded as ClipboardItem.TextBox
        assertEquals("∫ sin(x) dx", dt.text)
        assertEquals(2, dt.fontFamily)
        assertTrue(dt.bold)
        assertEquals(10f, dt.x, 0f)
        assertEquals(20f, dt.y, 0f)
    }

    @Test fun `decode rejects malformed JSON`() {
        assertNull(ClipboardPayloadCodec.decode("not json {"))
    }

    @Test fun `decode rejects unknown type`() {
        val json = """{"type":"unknown","bbox":[0,0,1,1]}"""
        assertNull(ClipboardPayloadCodec.decode(json))
    }

    @Test fun `decode rejects stroke group with zero strokes`() {
        val json = """{"type":"stroke_group","bbox":[0,0,1,1],"strokes":[]}"""
        assertNull(ClipboardPayloadCodec.decode(json))
    }

    @Test fun `roundTrip convenience returns the item`() {
        val item = ClipboardItem.StrokeGroup(
            strokes = listOf(stroke("s1")), bbox = floatArrayOf(0f, 0f, 1f, 1f),
        )
        val rt = ClipboardPayloadCodec.roundTrip(item)
        assertNotNull(rt)
        assertTrue(rt is ClipboardItem.StrokeGroup)
    }
}
