package com.thundernotes.data.entity

/**
 * Brush family IDs for ThunderNotes.
 *
 * Pattern adopted from Notein's `BrushFamilyProvider` (see
 * `docs/Notein-README.md` §2). We ship our own family IDs (not Notein's
 * `notein-*` names) since our brush definitions are our own; the
 * AndroidX Ink `BrushFamily` instances are constructed from these IDs
 * by `ink/InkBrushProvider.kt` (phase 6).
 *
 * Spec §6.10 Row 3 top-left Penset section:
 *   - Fountain Pen
 *   - Ballpoint Pen
 *   - Highlighter
 *   - Eraser (not a brush family — separate tool)
 *   - Lasso (not a brush family — separate tool)
 *   - Filler (not a brush family — separate tool)
 *
 * The Eraser/Lasso/Filler are tools, not brush families. Each penset
 * (Fountain, Ballpoint, Highlighter) gets its own family ID here.
 */
object BrushFamily {
    /** Ballpoint pen — solid line, slight pressure response. */
    const val THUNDER_BALLPOINT_V1 = "thunder-ballpoint-v1"

    /** Fountain pen — variable-width stroke based on pressure + velocity. */
    const val THUNDER_FOUNTAIN_V1 = "thunder-fountain-v1"

    /** Highlighter — wide semi-transparent stroke, no pressure response. */
    const val THUNDER_HIGHLIGHTER_V1 = "thunder-highlighter-v1"

    /** Pencil — textured stroke (not in spec's required penset but
     *  commonly wanted; enable in settings if added). */
    const val THUNDER_PENCIL_V1 = "thunder-pencil-v1"

    /** All brush family IDs that ship in the base APK. */
    val ALL = listOf(
        THUNDER_BALLPOINT_V1,
        THUNDER_FOUNTAIN_V1,
        THUNDER_HIGHLIGHTER_V1,
        THUNDER_PENCIL_V1
    )
}

/**
 * Input tool type — per Notein's `ToolType.java` (readable in
 * `penkit/serialization/ink/proto/ToolType.java`).
 *
 * Stored as an Int in the DB. The AndroidX Ink `Stroke.InputTools` enum
 * carries the same semantics; we keep our own enum so the DB schema is
 * stable even if AndroidX Ink's enum changes.
 */
enum class ToolType(val rawValue: Int) {
    UNKNOWN(0),
    STYLUS(1),
    TOUCH(2),
    MOUSE(3);

    companion object {
        fun fromRaw(value: Int): ToolType = entries.firstOrNull { it.rawValue == value } ?: UNKNOWN
    }
}

/**
 * Shape types supported by the canvas. Per spec §6.10 Row 3 top-right
 * (Shape Picker), we support a baseline set; more can be added without
 * a schema migration since [ShapeEntity.shapeType] is an Int.
 */
enum class ShapeType(val rawValue: Int) {
    LINE(0),
    RECTANGLE(1),
    ROUNDED_RECTANGLE(2),
    ELLIPSE(3),
    TRIANGLE(4),
    ARROW(5),
    POLYGON(6),
    STAR(7);

    companion object {
        fun fromRaw(value: Int): ShapeType = entries.firstOrNull { it.rawValue == value } ?: LINE
    }
}

/**
 * Line type for strokes and shape borders.
 * Per spec §6.10 Row 3 top-left, both Fountain Pen and Ballpoint Pen
 * support straight/dotted/dashed line types.
 *
 * NOTE: The full mock UI shows 8 line types in the customization popup
 * (canvasCustomizationPage); we expose 3 baseline here and add more in a
 * future schema migration once we settle the rendering rules.
 */
enum class LineType(val rawValue: Int) {
    STRAIGHT(0),
    DOTTED(1),
    DASHED(2);

    companion object {
        fun fromRaw(value: Int): LineType = entries.firstOrNull { it.rawValue == value } ?: STRAIGHT
    }
}

/**
 * Underline type for textboxes. Per spec §7.1:
 *   "Bold, Italic, Underline (thin, thick, dashed, wavy), Font Size,
 *    Font Family, Fill Color."
 */
enum class UnderlineType(val rawValue: Int) {
    NONE(0),
    THIN(1),
    THICK(2),
    DASHED(3),
    WAVY(4);

    companion object {
        fun fromRaw(value: Int): UnderlineType = entries.firstOrNull { it.rawValue == value } ?: NONE
    }
}

/**
 * Font family IDs (Int indices) for textboxes. Per spec §7.1, the 10
 * predefined fonts are:
 *
 *   Sans-serif (2):
 *     0  Noto Sans       (owner-chosen)
 *     1  Inter           (developer-chosen)
 *
 *   Serif (2):
 *     2  STIX Two Text   (owner-chosen, strong math/equation glyph coverage)
 *     3  Noto Serif       (developer-chosen, pairs with Noto Sans)
 *
 *   Handwriting (6):
 *     4  Patrick Hand    (owner-chosen)
 *     5  Short Stack     (owner-chosen)
 *     6  Comic Neue      (owner-chosen)
 *     7  Caveat          (developer-chosen, in Notein's font-licenses)
 *     8  Kalam           (developer-chosen, in Notein's font-licenses)
 *     9  Edu AU VIC WA NT Hand  (developer-chosen, in Notein's font-licenses)
 *
 * The corresponding TTFs ship under `assets/fonts/<name>.ttf` and are
 * loaded by the textbox renderer. See also `assets/font-licenses/`
 * for the OFL/Apache license files.
 */
object FontFamily {
    const val NOTO_SANS = 0
    const val INTER = 1
    const val STIX_TWO_TEXT = 2
    const val NOTO_SERIF = 3
    const val PATRICK_HAND = 4
    const val SHORT_STACK = 5
    const val COMIC_NEUE = 6
    const val CAVEAT = 7
    const val KALAM = 8
    const val EDU_AU_VIC_WA_NT_HAND = 9

    /** All font indices in canonical order. */
    val ALL = listOf(
        NOTO_SANS, INTER, STIX_TWO_TEXT, NOTO_SERIF,
        PATRICK_HAND, SHORT_STACK, COMIC_NEUE,
        CAVEAT, KALAM, EDU_AU_VIC_WA_NT_HAND
    )

    /** Maps an index to the TTF filename (without extension) under
     *  `assets/fonts/`. */
    fun assetFileName(id: Int): String = when (id) {
        NOTO_SANS               -> "NotoSans-Regular"
        INTER                   -> "Inter-Regular"
        STIX_TWO_TEXT           -> "STIXTwoText-Regular"
        NOTO_SERIF              -> "NotoSerif-Regular"
        PATRICK_HAND            -> "PatrickHand-Regular"
        SHORT_STACK             -> "ShortStack-Regular"
        COMIC_NEUE              -> "ComicNeue-Regular"
        CAVEAT                  -> "Caveat-Regular"
        KALAM                   -> "Kalam-Regular"
        EDU_AU_VIC_WA_NT_HAND   -> "EduAUVICWANTHand-Regular"
        else -> "NotoSans-Regular"
    }
}
