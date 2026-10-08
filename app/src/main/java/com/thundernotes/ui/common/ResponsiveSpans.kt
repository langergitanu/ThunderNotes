package com.thundernotes.ui.common

import android.content.Context
import android.util.DisplayMetrics
import kotlin.math.roundToInt

/**
 * Tablet-responsive grid span counts.
 *
 * The library grids previously hardcoded 3 (or 4) columns — tuned for a ~12"
 * tablet in landscape but cramped on the OnePlus Pad 2's 7:5 3000x2120 panel
 * in landscape (~1300-1600dp wide) and oversized in portrait. This helper
 * derives the span count from the actual available width so cards stay a
 * consistent ~250-320dp wide across devices + orientations (spec §6.1.5/§2.6:
 * tablet-first, 12in+ devices, both orientations).
 */
object ResponsiveSpans {

    /** Span count for note/folder/trash/bookmark card grids. */
    fun cardSpans(context: Context): Int {
        val widthDp = usableWidthDp(context)
        return (widthDp / 280f).roundToInt().coerceIn(2, 6)
    }

    /** Span count for compact cover/template tile grids (smaller cards). */
    fun tileSpans(context: Context): Int {
        val widthDp = usableWidthDp(context)
        return (widthDp / 200f).roundToInt().coerceIn(3, 8)
    }

    private fun usableWidthDp(context: Context): Float {
        val dm: DisplayMetrics = context.resources.displayMetrics
        // Subtract a conservative estimate of the persistent sidebar (the
        // library pages host a ~22% master-detail sidebar) so portrait phones
        // / narrow windows also land on a sensible count.
        val widthPx = dm.widthPixels
        val sidebarPx = (widthPx * 0.25f)
        return (widthPx - sidebarPx) / dm.density
    }
}
