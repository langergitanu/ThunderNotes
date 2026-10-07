package com.thundernotes.snip

/**
 * A pure-JVM image representation (ARGB pixels + dimensions) used by
 * [SnipPreprocessor] so the binarization / line-detection / cropping / upscaling
 * logic (ported from the user's `run_formula.py`) is unit-testable without
 * Android's `Bitmap` or OpenCV.
 *
 * The activity converts `android.graphics.Bitmap` → [SnipImage] (via
 * `bitmap.getPixels(...)`) + back as needed.
 */
data class SnipImage(
    val width: Int,
    val height: Int,
    /** ARGB pixels in row-major order (same layout as `Bitmap.getPixels`). */
    val pixels: IntArray,
) {
    init {
        require(pixels.size == width * height) {
            "pixels.size (${pixels.size}) must equal width*height ($width*$height)"
        }
    }

    /** Grayscale value (0–255) of a pixel at (x, y) using the luminance formula. */
    fun grayAt(x: Int, y: Int): Int {
        val p = pixels[y * width + x]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** Sub-crop to a rectangular region (returns a new [SnipImage]). */
    fun crop(x0: Int, y0: Int, w: Int, h: Int): SnipImage {
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                out[y * w + x] = pixels[(y0 + y) * width + (x0 + x)]
            }
        }
        return SnipImage(w, h, out)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SnipImage) return false
        return width == other.width && height == other.height && pixels.contentEquals(other.pixels)
    }

    override fun hashCode(): Int = 31 * width + height + pixels.contentHashCode()
}
