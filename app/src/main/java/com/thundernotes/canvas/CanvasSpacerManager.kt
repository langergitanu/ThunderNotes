package com.thundernotes.canvas

import java.util.UUID
import kotlin.math.max

/**
 * In-memory spacer manager for the canvas editor — the Add Writing Space
 * feature (spec §6.10 Row 3c + §7.4). Mirrors the persistence-layer
 * [com.thundernotes.data.spacer.SpacerManager]'s algorithm but operates on
 * ephemeral in-memory state (no Room DB) so the canvas can add/remove space
 * live without a save cycle.
 *
 * **<1000-page guarantee (spec §7.4):** spacers are **page-local** + stored
 * independently per page (a `Map<pageId, List<Spacer>>`). Adding a spacer on
 * page N does NOT touch any other page — no cross-page reflow. Within a page,
 * the cumulative-offset query is O(spacers on that page) via a sorted-list scan
 * (typically <100 spacers/page → effectively instant). The Fenwick prefix-sum
 * variant (used by the DB-backed [SpacerManager] for >1000 spacers/page) is
 * an optimization not needed for the in-memory editor.
 *
 * **Coordinates are page-local + immutable** (thunder-format-proposal Part C):
 * a stroke's stored x/y never changes when space is added — only the RENDER
 * applies the cumulative offset (content at y renders at y + [cumulativeOffsetAbove]).
 *
 * Pure + unit-testable (no Android/Ink dependency).
 */
class CanvasSpacerManager {

    private data class Spacer(
        val id: String,
        val offsetInPage: Float,
        var height: Float,
    )

    private val pageSpacers = mutableMapOf<String, MutableList<Spacer>>()

    /** Insert a spacer of [height] at [offsetInPage] (the y where the gap starts)
     *  on [pageId]. Returns the new spacer id. O(spacers on the page) for the
     *  sorted insert — bounded by spacers-on-page, not by total pages. */
    fun insertSpacer(pageId: String, offsetInPage: Float, height: Float): String {
        val id = UUID.randomUUID().toString()
        val list = pageSpacers.getOrPut(pageId) { mutableListOf() }
        val sp = Spacer(id, offsetInPage, max(0f, height))
        // Insert sorted by offsetInPage (keep the list ordered for the query).
        val pos = list.binarySearchBy(offsetInPage) { it.offsetInPage }
        val insertPos = if (pos < 0) -(pos + 1) else pos
        list.add(insertPos, sp)
        return id
    }

    /** Remove a spacer by id. Returns true if found. */
    fun removeSpacer(spacerId: String): Boolean {
        for ((_, list) in pageSpacers) {
            val idx = list.indexOfFirst { it.id == spacerId }
            if (idx >= 0) { list.removeAt(idx); return true }
        }
        return false
    }

    /** Resize a spacer. Returns true if found. */
    fun resizeSpacer(spacerId: String, newHeight: Float): Boolean {
        for ((_, list) in pageSpacers) {
            val sp = list.firstOrNull { it.id == spacerId } ?: continue
            sp.height = max(0f, newHeight)
            return true
        }
        return false
    }

    /** The cumulative spacer height above y on [pageId] — the render offset
     *  for content at y. O(spacers on the page) (a sorted-list scan; the
     *  Fenwick O(log N) variant is in the DB-backed SpacerManager). */
    fun cumulativeOffsetAbove(pageId: String, y: Float): Float {
        val list = pageSpacers[pageId] ?: return 0f
        var sum = 0f
        for (sp in list) {
            if (sp.offsetInPage < y) sum += sp.height
            else break  // sorted by offsetInPage → early exit
        }
        return sum
    }

    /** The total spacer height on a page. */
    fun totalHeightOnPage(pageId: String): Float {
        return pageSpacers[pageId]?.fold(0f) { acc, s -> acc + s.height } ?: 0f
    }

    /** The number of spacers on a page (for diagnostics / tests). */
    fun spacerCount(pageId: String): Int = pageSpacers[pageId]?.size ?: 0

    /** Drop the in-memory spacer cache for a page (page teardown / re-init). */
    fun invalidate(pageId: String) { pageSpacers.remove(pageId) }
    fun invalidateAll() { pageSpacers.clear() }
}
