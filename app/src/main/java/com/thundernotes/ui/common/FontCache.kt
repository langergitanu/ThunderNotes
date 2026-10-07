package com.thundernotes.ui.common

import android.content.Context
import android.graphics.Typeface
import com.thundernotes.data.entity.FontFamily

/**
 * Loads + caches the 10 spec fonts (§7.1: 2 sans-serif + 2 serif + 6 handwriting)
 * + JetBrains Mono (for code). The TTFs are bundled in `assets/fonts/`.
 *
 * Typeface.createFromAsset is expensive (disk I/O + parsing) → this cache loads
 * each font once + reuses the Typeface instance. Thread-safe (volatile + sync).
 *
 * Font index → TTF filename:
 *  0  NOTO_SANS          → NotoSans-Regular.ttf
 *  1  INTER               → Inter-Regular.ttf
 *  2  STIX_TWO_TEXT       → STIXTwoText-Regular.ttf
 *  3  NOTO_SERIF          → NotoSerif-Regular.ttf
 *  4  PATRICK_HAND        → PatrickHand-Regular.ttf
 *  5  SHORT_STACK         → ShortStack-Regular.ttf
 *  6  COMIC_NEUE          → ComicNeue-Regular.ttf
 *  7  CAVEAT              → Caveat-Regular.ttf
 *  8  KALAM               → Kalam-Regular.ttf
 *  9  EDU_AU_VIC_WA_NT_HAND → EduAUVICWANTHand-Regular.ttf
 *  10 JETBRAINS_MONO (code) → JetBrainsMono-Regular.ttf
 */
object FontCache {

    /** Extra font index for code (monospace). */
    const val JETBRAINS_MONO = 10

    private val fontFiles = arrayOf(
        "NotoSans-Regular.ttf",           // 0
        "Inter-Regular.ttf",              // 1
        "STIXTwoText-Regular.ttf",        // 2
        "NotoSerif-Regular.ttf",           // 3
        "PatrickHand-Regular.ttf",        // 4
        "ShortStack-Regular.ttf",         // 5
        "ComicNeue-Regular.ttf",          // 6
        "Caveat-Regular.ttf",             // 7
        "Kalam-Regular.ttf",              // 8
        "EduAUVICWANTHand-Regular.ttf",   // 9
        "JetBrainsMono-Regular.ttf",      // 10 (code monospace)
    )

    @Volatile
    private var cache: Array<Typeface?> = arrayOfNulls(fontFiles.size)
    @Volatile
    private var initialized = false

    /** Load + cache all Typefaces. Call once from Application.onCreate (background). */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val am = context.assets
            for (i in fontFiles.indices) {
                cache[i] = runCatching { Typeface.createFromAsset(am, "fonts/${fontFiles[i]}") }
                    .getOrNull() ?: Typeface.DEFAULT
            }
            initialized = true
        }
    }

    /** Get the Typeface for a font family index (0-9 spec + 10 code). */
    fun get(fontFamily: Int): Typeface {
        if (!initialized) return Typeface.DEFAULT
        return cache.getOrElse(fontFamily) { Typeface.DEFAULT } ?: Typeface.DEFAULT
    }

    /** Get the Typeface for code (JetBrains Mono / monospace). */
    fun getMonospace(): Typeface = get(JETBRAINS_MONO)
}
