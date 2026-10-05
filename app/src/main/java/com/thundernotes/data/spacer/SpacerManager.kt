package com.thundernotes.data.spacer

import com.thundernotes.data.dao.SpacerDao
import com.thundernotes.data.entity.SpacerEntity

/**
 * Manages spacer heights for the "Add Extra Writing Space" feature (spec §7.4).
 *
 * Per `docs/thunder-format-proposal.md` Part C:
 * ```
 * globalY = pageLocalY + cumulativePageOffsetUpTo(page)
 *                          + Σ(spacer heights above this point on this page)
 * ```
 *
 * This class handles the **Σ term** — the per-page sum of spacer heights above a
 * given Y coordinate. It maintains an in-memory Fenwick tree (Binary Indexed
 * Tree) per page for O(log N) queries.
 *
 * ## Design (post-Sync-5 fix)
 *
 * **The cache is invalidated on every mutation** (insert/remove/resize). The
 * next query rebuilds the Fenwick tree from the DB via `getOrBuildTree`.
 *
 * This is the simplest correct design — and it's also the same performance as
 * the previous "incremental update" approach, because `FenwickTree.insert`/
 * `remove`/`update` already rebuilt the whole array O(N) from in-memory lists
 * (so the structure was never actually O(log N) on mutation — it was O(N)
 * either way). The cache only helps on **queries**: build once O(N), then
 * O(log N) per query. For a render loop with many queries between mutations,
 * the Fenwick tree is still a win.
 *
 * **Why the previous design was wrong (BigPickle.txt Sync 4 Open Issue 1):**
 * The previous `FenwickTree` keyed spacers by `(offset, height)` — not by
 * `spacerId`. When two spacers shared the same `offset_in_page`:
 *   - `insert()` placed the new spacer at the FIRST index with `offset >=
 *     newOffset`, pushing the existing one to a higher index.
 *   - `remove()` / `update()` only checked the FIRST index — the spacer pushed
 *     to a higher index could never be matched. Remove/update silently failed
 *     in memory while the DB mutated.
 * Result: in-memory tree diverged from DB, every later query was off by the
 * unmatched spacer's height, and the error was sticky (survived later ops).
 *
 * The invalidate-on-mutation fix makes divergence **structurally impossible**:
 * the cache is always either empty (never built) or matches the DB (built from
 * DB after invalidation). Duplicate-offset ambiguity is gone because the
 * Fenwick tree no longer tries to match individual spacers — it just sums
 * whatever the DB has.
 *
 * ## Invariants (pinned by `NoteDatabaseTest`)
 *
 *   1. **Adding a spacer at offset Y shifts the render position of points
 *      STRICTLY BELOW Y (i.e., Y' > Y) by exactly the spacer's height.**
 *      Points at Y or above (Y' <= Y) are unaffected.
 *   2. **Stroke stored coordinates are NEVER mutated by spacer operations.**
 *   3. **Removing a spacer reverses the shift exactly.**
 *   4. **Resizing a spacer by Δ shifts points below by Δ.**
 *   5. **(Sync 5 regression test) Two spacers at the SAME offset:** remove +
 *      resize keep the manager's cumulative height consistent with the DB
 *      ground truth at every step.
 *
 * ## Semantics: "above" vs "at"
 *
 * A spacer at offset O is "above" a point at Y iff `O < Y` (strict less-than).
 * A spacer AT the same offset as the point (O == Y) is NOT counted — it's at
 * the same level, not above. Matches user mental model: "content at Y stays
 * at the top of the new space, content below Y shifts down."
 *
 * ## Lifecycle
 *
 * `SpacerManager` is per-open-note (NOT a global singleton). It's constructed
 * when a note is opened (Phase 6+ will wire it into `NoteEditingSession`) and
 * `invalidateAll()`'d when the note is closed. The in-memory Fenwick trees
 * are lazily built on first query for each page.
 *
 * ## Thread safety
 *
 * NOT thread-safe. The canvas render loop is typically single-threaded per
 * page (or at least serial per page). If concurrent access is needed, wrap
 * `pageTrees` in a `ConcurrentHashMap` + `computeIfAbsent` (future work).
 */
class SpacerManager(private val spacerDao: SpacerDao) {

    /** In-memory cache of Fenwick trees, one per page. Lazily built. */
    private val pageTrees: MutableMap<String, FenwickTree> = mutableMapOf()

    /**
     * Cumulative spacer height above Y on the given page.
     *
     * Returns the sum of heights of all spacers on this page where
     * `spacer.offset_in_page < y`. O(log N) query via Fenwick tree (tree is
     * built O(N) on first access or after invalidation, then O(log N) per
     * query).
     */
    suspend fun cumulativeHeightAbove(pageId: String, y: Float): Float {
        val tree = getOrBuildTree(pageId)
        val count = tree.countSpacersStrictlyAbove(y)
        return tree.prefixSum(count)
    }

    /** Total spacer height on a page (sum of ALL spacers, regardless of offset).
     *  Useful for computing the page's total render height. */
    suspend fun totalHeightOnPage(pageId: String): Float {
        val tree = getOrBuildTree(pageId)
        return tree.prefixSum(tree.size)
    }

    /**
     * Insert a new spacer. Writes to the DB, then invalidates the cache so
     * the next query rebuilds from the DB.
     *
     * @return the new spacer's ID.
     */
    suspend fun insertSpacer(pageId: String, offsetInPage: Float, height: Float): String {
        val spacer = SpacerEntity(
            anchorPageId = pageId,
            offsetInPage = offsetInPage,
            height = height
        )
        spacerDao.insert(spacer)
        invalidate(pageId)
        return spacer.spacerId
    }

    /**
     * Remove a spacer by ID. Deletes from the DB, then invalidates the cache.
     * Reverses the shift caused by the spacer's height.
     */
    suspend fun removeSpacer(spacerId: String) {
        val spacer = spacerDao.getBySpacerId(spacerId) ?: return
        spacerDao.deleteBySpacerId(spacerId)
        invalidate(spacer.anchorPageId)
    }

    /**
     * Resize a spacer. Updates the DB, then invalidates the cache. The delta
     * (newHeight - oldHeight) is applied to all points STRICTLY BELOW the
     * spacer's offset.
     */
    suspend fun resizeSpacer(spacerId: String, newHeight: Float) {
        val spacer = spacerDao.getBySpacerId(spacerId) ?: return
        spacerDao.updateHeightAndOffset(spacerId, newHeight, spacer.offsetInPage)
        invalidate(spacer.anchorPageId)
    }

    /** Invalidate the in-memory cache for a page (e.g., on page reload).
     *  Also called internally after every mutation. */
    fun invalidate(pageId: String) {
        pageTrees.remove(pageId)
    }

    /** Invalidate all cached trees (e.g., when the note is closed). */
    fun invalidateAll() {
        pageTrees.clear()
    }

    private suspend fun getOrBuildTree(pageId: String): FenwickTree {
        return pageTrees[pageId] ?: buildTree(pageId).also { pageTrees[pageId] = it }
    }

    private suspend fun buildTree(pageId: String): FenwickTree {
        val spacers = spacerDao.getByPageOrdered(pageId)
        return FenwickTree(spacers)
    }
}

/**
 * Read-only Fenwick tree (Binary Indexed Tree) for O(log N) prefix sums of
 * spacer heights.
 *
 * Built once from a list of spacers (sorted by offset ASC). Supports only
 * `countSpacersStrictlyAbove(y)` + `prefixSum(count)` + `size` — NO mutation
 * methods. The parent `SpacerManager` invalidates the whole tree on any
 * mutation and rebuilds on the next query.
 *
 * **Why read-only?** The previous design had `insert/remove/update` methods
 * that tried to incrementally update the tree, but they rebuilt the whole
 * array O(N) anyway (so no perf benefit) AND had a duplicate-offset bug
 * (BigPickle.txt Sync 4 Open Issue 1). The read-only design is simpler +
 * structurally correct.
 *
 * **Indexing:** The tree is indexed by the spacer's position in the
 * sorted-by-offset list (NOT by the offset value itself — offsets are floats,
 * Fenwick needs integer indices).
 *
 * **Why not just a sorted list + linear sum?** That works for typical cases
 * (few spacers per page). The Fenwick tree pays off when there are many
 * spacers on a single page (e.g., a heavily-spaced reference page with 100+
 * insertions) — queries stay O(log N) instead of degrading to O(N).
 */
private class FenwickTree(spacers: List<SpacerEntity>) {

    /** Sorted (offset, height) pairs, ordered by offset ASC. */
    private val offsets: List<Float>
    private val heights: List<Float>

    /** Fenwick tree array (1-indexed). `tree[i]` = sum of a range ending at i. */
    private val tree: FloatArray

    init {
        val sorted = spacers.sortedBy { it.offsetInPage }
        offsets = sorted.map { it.offsetInPage }
        heights = sorted.map { it.height }
        tree = FloatArray(offsets.size + 1)  // 1-indexed; index 0 is unused
        for (i in offsets.indices) {
            addToTree(i + 1, heights[i])
        }
    }

    val size: Int get() = offsets.size

    /**
     * Count of spacers with offset STRICTLY LESS THAN y.
     * (Spacers AT exactly y are NOT counted — they're "at" the point, not above.)
     * Binary search: O(log N).
     */
    fun countSpacersStrictlyAbove(y: Float): Int {
        var lo = 0
        var hi = offsets.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (offsets[mid] < y) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Prefix sum of heights of the first [count] spacers.
     * (count = number of spacers to include from the start of the sorted list.)
     * Standard Fenwick query: O(log N).
     */
    fun prefixSum(count: Int): Float {
        var sum = 0f
        var i = count
        while (i > 0) {
            sum += tree[i]
            i -= i and -i  // strip the lowest set bit
        }
        return sum
    }

    /** Standard Fenwick point-update: add [value] at index [index] (1-indexed). */
    private fun addToTree(index: Int, value: Float) {
        var i = index
        while (i < tree.size) {
            tree[i] += value
            i += i and -i  // add the lowest set bit
        }
    }
}
