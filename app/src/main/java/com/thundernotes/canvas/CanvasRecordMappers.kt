package com.thundernotes.canvas

import com.thundernotes.data.entity.StrokeEntity
import com.thundernotes.data.entity.TextBoxEntity

/**
 * Pure mappers between the editor's in-memory records ([StrokeRecord] /
 * [TextBoxRecord]) and the per-note Room entities ([StrokeEntity] /
 * [TextBoxEntity]) used by the `.thunder` persistence pipeline.
 *
 * **Coordinates are page-local** on both sides, so mapping is direct. The
 * stroke's serialized `InkStrokeProto` blob ([StrokeBlobCodec]) is the
 * geometry source of truth; the entity's denormalized columns (brush size /
 * color / family / bounding box) are mirrors for indexed queries.
 *
 * TextBox entities need a bounding box (left/top/right/bottom) for viewport
 * culling. Records only carry x/y (+ optional fixed width/height for the
 * FILLER stamp), so the mapper estimates the right/bottom edge from the text
 * length and font size when the record doesn't fix them. The estimate is
 * only used for queries — rendering uses the record's x/y + WRAP_CONTENT.
 */
object CanvasRecordMappers {

    // ─── Stroke: record → entity ────────────────────────────────────────────

    fun StrokeRecord.toEntity(realLayerId: String?): StrokeEntity {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var i = 0
        while (i + 1 < inputXy.size) {
            val x = inputXy[i]; val y = inputXy[i + 1]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            i += 2
        }
        // Degenerate (single-point) strokes get a tiny non-zero box so the
        // spatial queries can still find them.
        if (inputXy.isEmpty()) { minX = 0f; minY = 0f; maxX = 0f; maxY = 0f }
        if (maxX < minX) { minX = 0f; maxX = 0f }
        if (maxY < minY) { minY = 0f; maxY = 0f }
        val half = brushSize / 2f
        return com.thundernotes.data.entity.StrokeEntity(
            strokeId = id,
            layerId = realLayerId ?: layerId,
            pageId = pageId,
            creationTime = creationTime,
            brushSize = brushSize,
            brushColor = brushColorArgb,
            brushEpsilon = brushEpsilon,
            brushFamilyId = brushFamilyId,
            toolType = toolType,
            inkStrokeBlob = StrokeBlobCodec.encode(this),
            left = minX - half,
            top = minY - half,
            right = maxX + half,
            bottom = maxY + half,
            syncTimestamp = System.currentTimeMillis(),
        )
    }

    // ─── Stroke: entity → record ────────────────────────────────────────────

    fun com.thundernotes.data.entity.StrokeEntity.toRecord(): StrokeRecord? =
        StrokeBlobCodec.decode(inkStrokeBlob, pageId)?.copy(layerId = layerId)

    // ─── Textbox: record → entity ───────────────────────────────────────────

    fun TextBoxRecord.toEntity(realLayerId: String?): TextBoxEntity {
        val w = widthPx ?: estimateWidth(text, fontSizeSp, bold)
        val h = heightPx ?: estimateHeight(text, fontSizeSp)
        return TextBoxEntity(
            textboxId = id,
            layerId = realLayerId ?: "layer-0",
            pageId = pageId,
            text = text,
            fontFamilyId = fontFamily,
            fontSize = fontSizeSp,
            isBold = bold,
            isItalic = italic,
            underlineType = underline,
            fillColor = fillColor,
            textColor = colorArgb,
            codeLanguage = codeLanguage,
            left = x,
            top = y,
            right = x + w,
            bottom = y + h,
            rotation = 0f,
            createdTime = System.currentTimeMillis(),
            modifiedTime = System.currentTimeMillis(),
            syncTimestamp = System.currentTimeMillis(),
        )
    }

    // ─── Textbox: entity → record ───────────────────────────────────────────

    fun TextBoxEntity.toRecord(): TextBoxRecord {
        // A FILLER stamp is an empty-text textbox with a fill color + fixed
        // size (the entity's bbox preserves the stamp's width/height).
        val isStamp = text.isEmpty() && fillColor != null &&
            (right - left) > 1f && (bottom - top) > 1f
        return TextBoxRecord(
            id = textboxId,
            pageId = pageId,
            text = text,
            x = left,
            y = top,
            fontFamily = fontFamilyId,
            fontSizeSp = fontSize,
            bold = isBold,
            italic = isItalic,
            underline = underlineType,
            colorArgb = textColor,
            fillColor = fillColor,
            codeLanguage = codeLanguage,   // v2 column — code highlighting survives reload
            widthPx = if (isStamp) (right - left) else null,
            heightPx = if (isStamp) (bottom - top) else null,
        )
    }

    // ─── bbox estimation helpers (query-only; render uses WRAP_CONTENT) ────

    private fun estimateWidth(text: String, fontSizeSp: Float, bold: Boolean): Float {
        if (text.isEmpty()) return 0f
        // ~0.55 em average glyph advance (bold slightly wider).
        val em = fontSizeSp * (if (bold) 0.62f else 0.55f)
        val lines = text.count { it == '\n' } + 1
        val maxLineLen = text.split('\n').maxOf { it.length }
        return maxLineLen * em + lines * 4f
    }

    private fun estimateHeight(text: String, fontSizeSp: Float): Float {
        val lines = (text.count { it == '\n' } + 1).coerceAtLeast(1)
        return lines * fontSizeSp * 1.35f
    }
}
