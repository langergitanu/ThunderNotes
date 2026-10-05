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
 * Tree) per page for O(log N) queries + updates.
 *
 * ## Why this matters (spec §7.4)
 *
 * "If the document has more than 1000 pages, we do not guarantee it will not
 * hang during add/delete writing space (it may slightly hang), but documents
 * smaller than 1000 pages must not hang during this operation."
 *
 * The Fenwick tree is what enforces the "must not hang" requirement. A naive
 * linear scan per query would be O(N) per stroke-render; with 1000 strokes per
 * page and many spacers, that's O(N×M) per viewport redraw — too slow.
 *
 * ## Invariants (pinned by `NoteDatabaseTest`)
 *
 *   1. **Adding a spacer at offset Y shifts the render position of points
 *      STRICTLY BELOW Y (i.e., Y' > Y) by exactly the spacer's height.**
 *      Points at Y or above (Y' <= Y) are unaffected.
 *   2. **Stroke stored coordinates are NEVER mutated by spacer operations.**
 *      Adding a spacer only changes the offset METADATA; the strokes'
 *      `ink_stroke_blob` + bounding-box columns stay byte-identical. This is
 *      the page-local-immutable-coords design (Notein + Samsung both follow
 *      this — see `docs/thunder-format-proposal.md` Part C).
 *   3. **Removing a spacer reverses the shift exactly.**
 *   4. **Resizing a spacer by Δ shifts points below by Δ.**
 *
 * ## Semantics: "above" vs "at"
 *
 * A spacer at offset O is "above" a point at Y iff `O < Y` (strict less-than).
 * A spacer AT the same offset as the point (O == Y) is NOT counted — it's at
 * the same level, not above. This matches the user mental model: "I added
 * space at Y; content at Y stays at the top of the new space, content below
 * Y shifts down."
 *
 * ## Lifecycle
 *
 * `SpacerManager` is per-open-note (NOT a global singleton like the
 * `RepositoryModule` repos). It's constructed when a note is opened (Phase 6+
 * will wire it into `NoteEditingSession`) and `invalidateAll()`'d when the
 * note is closed. The in-memory Fenwick trees are lazily built on first
 * query for each page.
 */
class SpacerManager(private val spacerDao: SpacerDao) {

    /** In-memory cache of Fenwick trees, one per page. Lazily built. */
    private val pageTrees: MutableMap<String, FenwickTree> = mutableMapOf()

    /**
     * Cumulative spacer height above Y on the given page.
     *
     * Returns the sum of heights of all spacers on this page where
     * `spacer.offset_in_page < y`. O(log N) via Fenwick tree.
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
     * Insert a new spacer. Updates the in-memory Fenwick tree + the DB.
     *
     * @return the new spacer's ID.
     */
    suspend fun insertSpacer(pageId: String, offsetInPage: Float, height: Float): String {
        val tree = getOrBuildTree(pageId)
        val spacer = SpacerEntity(
            anchorPageId = pageId,
            offsetInPage = offsetInPage,
            height = height
        )
        spacerDao.insert(spacer)
        tree.insert(offsetInPage, height)
        return spacer.spacerId
    }

    /**
     * Remove a spacer by ID. Updates the in-memory tree + the DB.
     * Reverses the shift caused by the spacer's height.
     */
    suspend fun removeSpacer(spacerId: String) {
        val spacer = spacerDao.getBySpacerId(spacerId) ?: return
        val tree = pageTrees[spacer.anchorPageId]
        tree?.remove(spacer.offsetInPage, spacer.height)
        spacerDao.deleteBySpacerId(spacerId)
    }

    /**
     * Resize a spacer. The delta (newHeight - oldHeight) is applied to all
     * points STRICTLY BELOW the spacer's offset.
     */
    suspend fun resizeSpacer(spacerId: String, newHeight: Float) {
        val spacer = spacerDao.getBySpacerId(spacerId) ?: return
        val oldHeight = spacer.height
        val tree = pageTrees[spacer.anchorPageId]
        tree?.update(spacer.offsetInPage, oldHeight, newHeight)
        spacerDao.updateHeightAndOffset(spacerId, newHeight, spacer.offsetInPage)
    }

    /** Invalidate the in-memory cache for a page (e.g., on page reload). */
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
 * Fenwick tree (Binary Indexed Tree) for O(log N) prefix sums of spacer heights.
 *
 * **Indexing:** The tree is indexed by the spacer's position in the
 * sorted-by-offset list (NOT by the offset value itself — offsets are floats,
 * Fenwick needs integer indices). When a new spacer is inserted, all spacers
 * with offset > newOffset shift down by one index; the tree is rebuilt.
 *
 * **Operations:**
 *   - `countSpacersStrictlyAbove(y)`: binary-search the offset list for the
 *     insertion point of y → O(log N).
 *   - `prefixSum(count)`: standard Fenwick prefix-sum → O(log N).
 *   - `insert` / `remove` / `update`: rebuild the tree → O(N). (Insertions are
 *     rare — the user adds maybe 5-10 spacers per page. A true O(log N)
 *     update would require a balanced-tree index, which is overkill for the
 *     typical case. The rebuild is O(N) but N is small.)
 *
 * **Why not just a sorted list + linear sum?** That works for typical cases
 * (few spacers per page). The Fenwick tree pays off when there are many
 * spacers on a single page (e.g., a heavily-spaced reference page with 100+
 * insertions) — queries stay O(log N) instead of degrading to O(N).
 */
private class FenwickTree(spacers: List<SpacerEntity>) {

    /** Sorted (offset, height) pairs, ordered by offset ASC. */
    private val offsets: MutableList<Float> = mutableListOf()
    private val heights: MutableList<Float> = mutableListOf()

    /** Fenwick tree array (1-indexed). `tree[i]` = sum of a range ending at i. */
    private var tree: FloatArray

    init {
        spacers.sortedBy { it.offsetInPage }.forEach {
            offsets.add(it.offsetInPage)
            heights.add(it.height)
        }
        tree = FloatArray(offsets.size + 1)  // 1-indexed; index 0 is unused
        for (i in offsets.indices) {
            addToTree(i + 1, heights[i])
        }
    }

    val size: Int get() = offsets.size

    /**
     * Count of spacers with offset STRICTLY LESS THAN y.
     * (Spacers AT exactly y are NOT counted — they're "at" the point, not above.)
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

    /** Insert a new spacer at the given offset. Rebuilds the tree (O(N)). */
    fun insert(offset: Float, height: Float) {
        val insertionPoint = countSpacersStrictlyAbove(offset)
        offsets.add(insertionPoint, offset)
        heights.add(insertionPoint, height)
        rebuildTree()
    }

    /** Remove the spacer at the given offset + height. Rebuilds the tree. */
    fun remove(offset: Float, height: Float) {
        val index = countSpacersStrictlyAbove(offset)
        if (index < offsets.size && offsets[index] == offset && heights[index] == height) {
            offsets.removeAt(index)
            heights.removeAt(index)
            rebuildTree()
        }
    }

    /** Update the height of a spacer (offset unchanged). Rebuilds the tree. */
    fun update(offset: Float, oldHeight: Float, newHeight: Float) {
        val index = countSpacersStrictlyAbove(offset)
        if (index < offsets.size && offsets[index] == offset && heights[index] == oldHeight) {
            heights[index] = newHeight
            rebuildTree()
        }
    }

    /** Standard Fenwick point-update: add [value] at index [index] (1-indexed). */
    private fun addToTree(index: Int, value: Float) {
        var i = index
        while (i < tree.size) {
            tree[i] += value
            i += i and -i  // add the lowest set bit
        }
    }

    /** Rebuild the entire tree from the offsets/heights lists. O(N). */
    private fun rebuildTree() {
        tree = FloatArray(offsets.size + 1)
        for (i in offsets.indices) {
            addToTree(i + 1, heights[i])
        }
    }
}
