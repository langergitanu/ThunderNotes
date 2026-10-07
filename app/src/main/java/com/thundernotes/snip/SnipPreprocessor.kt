package com.thundernotes.snip

import kotlin.math.max
import kotlin.math.min

/**
 * Image preprocessing for the snip pipeline — a Kotlin port of the user's
 * `run_formula.py` optimizations, applied to BOTH text and equation snips:
 *
 * 1. **Binarize** (auto-polarity + Otsu): converts to pure black-on-white.
 *    Auto-detects the image polarity from the border mean (handles coloured
 *    ink + dark-canvas captures). Otsu threshold finds the optimal split.
 *
 * 2. **Line-aware detection** (horizontal projection): detects text-row bands
 *    via ink-density per row. Fixes the "tall multi-line image gets squeezed
 *    to a fixed-size ViT encoder → late lines garble" problem — each row is
 *    recognised separately at full resolution, then rejoined.
 *
 * 3. **Tight cropping**: crops each line band with padding + tight horizontal
 *    crop on ink columns (removes whitespace → the OCR encoder sees only ink).
 *
 * 4. **Upscaling** (optional, integer factor): small text → upscale before
 *    recognition (e.g. 2× with cubic interpolation → better small-glyph
 *    recognition).
 *
 * Pure + unit-testable (operates on [SnipImage], no Android/OpenCV dependency).
 * The activity converts `Bitmap` → `SnipImage` + back as needed.
 *
 * Per spec §7.3.6: "The app must apply this preprocessing automatically for snips
 * captured from a dark canvas."
 */
object SnipPreprocessor {

    // ─── 1. Binarize (auto-polarity + Otsu) ───────────────────────────────

    /**
     * Convert to pure black-on-white:
     *  - Auto-detect polarity from the border mean (if border is dark → invert).
     *  - Otsu threshold → optimal black/white split.
     * Returns a new [SnipImage] where every pixel is either 0xFF000000 (black ink)
     * or 0xFFFFFFFF (white background).
     */
    fun binarize(img: SnipImage): SnipImage {
        val gray = IntArray(img.width * img.height) { i -> img.grayAt(i % img.width, i / img.width) }

        // Auto-polarity: if border is dark, invert before thresholding.
        val borderMean = computeBorderMean(gray, img.width, img.height)
        val invert = borderMean < 127
        if (invert) {
            for (i in gray.indices) gray[i] = 255 - gray[i]
        }

        // Otsu threshold.
        val thresh = otsuThreshold(gray)
        val out = IntArray(gray.size)
        for (i in gray.indices) {
            out[i] = if (gray[i] <= thresh) BLACK else WHITE
        }
        return SnipImage(img.width, img.height, out)
    }

    // ─── 2. Line-aware detection (horizontal projection) ──────────────────

    /** A text-row band: y0 (inclusive) to y1 (exclusive). */
    data class Band(val y0: Int, val y1: Int) { val height: Int get() = y1 - y0 }

    /**
     * Detect text-row bands via horizontal projection (ink density per row).
     * Mirrors `run_formula.py::detect_line_bands`:
     *  - ink = pixel < 128 (black ink on white).
     *  - projection = sum of ink per row.
     *  - threshold = max(2, max_proj * ink_frac).
     *  - merge bands separated by a gap < minGap.
     *  - drop bands shorter than minHeight.
     */
    fun detectLineBands(
        img: SnipImage,
        minGap: Int = 12,
        minHeight: Int = 15,
        inkFraction: Double = 0.01,
    ): List<Band> {
        val ink = IntArray(img.height)
        for (y in 0 until img.height) {
            var count = 0
            for (x in 0 until img.width) {
                if (img.grayAt(x, y) < 128) count++
            }
            ink[y] = count
        }
        val maxProj = ink.maxOrNull() ?: 0
        if (maxProj == 0) return emptyList()
        val thresh = max(2, (maxProj * inkFraction).toInt())
        val rows = BooleanArray(img.height) { ink[it] > thresh }

        // Find raw bands.
        val rawBands = mutableListOf<Pair<Int, Int>>()
        var start: Int? = null
        for (y in 0 until img.height) {
            if (rows[y] && start == null) start = y
            else if (!rows[y] && start != null) { rawBands.add(start to y); start = null }
        }
        if (start != null) rawBands.add(start to img.height)

        // Merge bands separated by a small gap.
        val merged = mutableListOf<Pair<Int, Int>>()
        for (b in rawBands) {
            if (merged.isNotEmpty() && b.first - merged.last().second < minGap) {
                merged[merged.lastIndex] = merged.last().first to b.second
            } else {
                merged.add(b)
            }
        }

        // Drop bands shorter than minHeight.
        return merged.filter { it.second - it.first >= minHeight }
            .map { Band(it.first, it.second) }
    }

    // ─── 3. Tight cropping (per-line, with padding + horizontal tight crop) ─

    /**
     * Crop a line band from the image with padding + tight horizontal crop on
     * ink columns. Mirrors `run_formula.py::crop_with_pad`:
     *  - Vertical: pad above/below by [pad] px.
     *  - Horizontal: tight crop to the leftmost/rightmost ink column (with pad).
     */
    fun cropLine(img: SnipImage, band: Band, pad: Int = 6): SnipImage {
        val y0 = max(0, band.y0 - pad)
        val y1 = min(img.height, band.y1 + pad)
        // Horizontal tight crop on ink columns.
        var x0 = img.width; var x1 = 0
        for (y in y0 until y1) {
            for (x in 0 until img.width) {
                if (img.grayAt(x, y) < 128) {
                    x0 = min(x0, x); x1 = max(x1, x)
                }
            }
        }
        if (x0 > x1) { x0 = 0; x1 = img.width }  // no ink found → full width
        x0 = max(0, x0 - pad); x1 = min(img.width, x1 + pad)
        return img.crop(x0, y0, x1 - x0, y1 - y0)
    }

    // ─── 4. Upscaling (integer factor, nearest/cubic) ──────────────────────

    /**
     * Upscale by an integer [factor] (1 = no-op). Uses nearest-neighbour
     * (sufficient for binarized images; cubic is a refinement for greyscale).
     */
    fun upscale(img: SnipImage, factor: Int): SnipImage {
        if (factor <= 1) return img
        val w = img.width * factor; val h = img.height * factor
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                out[y * w + x] = img.pixels[(y / factor) * img.width + (x / factor)]
            }
        }
        return SnipImage(w, h, out)
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    /** Compute the mean grayscale of the image border (top + bottom + left + right rows). */
    private fun computeBorderMean(gray: IntArray, w: Int, h: Int): Int {
        if (w == 0 || h == 0) return 255
        var sum = 0L; var count = 0
        for (x in 0 until w) { sum += gray[x]; sum += gray[(h - 1) * w + x]; count += 2 }
        for (y in 0 until h) { sum += gray[y * w]; sum += gray[y * w + w - 1]; count += 2 }
        return (sum / count).toInt()
    }

    /** Otsu's method: find the threshold that minimises intra-class variance. */
    private fun otsuThreshold(gray: IntArray): Int {
        val hist = IntArray(256)
        for (v in gray) hist[v.coerceIn(0, 255)]++
        val total = gray.size
        var sum = 0L
        for (i in 0..255) sum += i.toLong() * hist[i]
        var sumB = 0L; var wB = 0; var maxVar = 0.0; var threshold = 127
        for (i in 0..255) {
            wB += hist[i]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += i.toLong() * hist[i]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val varBetween = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (varBetween > maxVar) { maxVar = varBetween; threshold = i }
        }
        return threshold
    }

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}
