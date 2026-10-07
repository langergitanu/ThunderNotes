package com.thundernotes.canvas.inject

import com.thundernotes.canvas.StrokeRecord
import com.thundernotes.canvas.StrokeBlobCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * Wire format for the **external live-injection ingress**
 * (thunder-format-proposal Part B2): a JSON payload carried by the
 * `com.thundernotes.action.INJECT_CONTENT` intent, so an external tool
 * (AI snip service, plugin, ADB `am start -a`) can push a stroke group or
 * textbox into ThunderNotes' [ThunderClipboard] while a note is open.
 *
 * Schema:
 * ```
 * { "type": "stroke_group", "bbox": [l,t,r,b],
 *   "strokes": [ {"blob_b64": "<base64 InkStrokeProto bytes>"} ] }
 * { "type": "textbox", "text": "...", "font": 4, "bold": false,
 *   "italic": false, "underline": 0, "x": 0, "y": 0, "bbox": [l,t,r,b] }
 * ```
 *
 * `blob_b64` is the base64 of the on-disk `.thunder` stroke blob — the same
 * bytes [StrokeBlobCodec] / `InkStrokeSerializer` produce, so a sender just
 * serializes an `InkStrokeProto` + base64-encodes it.
 *
 * Pure (kotlinx-serialization + Base64 + InkStrokeSerializer — no Android)
 * → unit-testable round-trip on the JVM.
 */
@Serializable
data class ClipboardPayload(
    val type: String,
    val bbox: List<Float>,
    val strokes: List<StrokePayload> = emptyList(),
    val text: String? = null,
    val font: Int = 0,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Int = 0,
    val x: Float = 0f,
    val y: Float = 0f,
)

@Serializable
data class StrokePayload(val blobB64: String)

object ClipboardPayloadCodec {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Decode an external JSON payload → a [ClipboardItem], or null if malformed. */
    fun decode(jsonStr: String): ClipboardItem? {
        val payload = runCatching { json.decodeFromString<ClipboardPayload>(jsonStr) }.getOrNull() ?: return null
        return when (payload.type) {
            "stroke_group" -> {
                val strokes = payload.strokes.mapNotNull { sp ->
                    val blob = runCatching { Base64.getDecoder().decode(sp.blobB64) }.getOrNull() ?: return@mapNotNull null
                    StrokeBlobCodec.decode(blob, pageId = "external")
                }
                if (strokes.isEmpty()) return null
                ClipboardItem.StrokeGroup(strokes, payload.bbox.toFloatArray())
            }
            "textbox" -> {
                val text = payload.text ?: return null
                ClipboardItem.TextBox(
                    text = text,
                    fontFamily = payload.font,
                    bold = payload.bold,
                    italic = payload.italic,
                    underline = payload.underline,
                    x = payload.x,
                    y = payload.y,
                    bbox = payload.bbox.toFloatArray(),
                )
            }
            else -> null
        }
    }

    /** Encode a [ClipboardItem] → JSON (for the sender side + round-trip tests). */
    fun encode(item: ClipboardItem): String {
        val payload = when (item) {
            is ClipboardItem.StrokeGroup -> ClipboardPayload(
                type = "stroke_group",
                bbox = item.bbox.toList(),
                strokes = item.strokes.map { s ->
                    StrokePayload(
                        Base64.getEncoder().encodeToString(StrokeBlobCodec.encode(s))
                    )
                },
            )
            is ClipboardItem.TextBox -> ClipboardPayload(
                type = "textbox",
                bbox = item.bbox.toList(),
                text = item.text,
                font = item.fontFamily,
                bold = item.bold,
                italic = item.italic,
                underline = item.underline,
                x = item.x,
                y = item.y,
            )
        }
        return json.encodeToString(payload)
    }

    /** Convenience: round-trip a [ClipboardItem] through encode → decode. */
    fun roundTrip(item: ClipboardItem): ClipboardItem? = decode(encode(item))
}
