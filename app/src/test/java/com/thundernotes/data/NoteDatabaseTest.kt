package com.thundernotes.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thundernotes.data.db.NoteDatabase
import com.thundernotes.data.entity.BrushFamily
import com.thundernotes.data.entity.CommentEntity
import com.thundernotes.data.entity.HyperLinkEntity
import com.thundernotes.data.entity.ImageEntity
import com.thundernotes.data.entity.NoteContentEntity
import com.thundernotes.data.entity.NotePageEntity
import com.thundernotes.data.entity.OutlineEntity
import com.thundernotes.data.entity.PageLayerEntity
import com.thundernotes.data.entity.PageOrientation
import com.thundernotes.data.entity.PageType
import com.thundernotes.data.entity.PdfInfoEntity
import com.thundernotes.data.entity.ShapeEntity
import com.thundernotes.data.entity.ShapeType
import com.thundernotes.data.entity.StrokeEntity
import com.thundernotes.data.entity.TextBoxEntity
import com.thundernotes.data.entity.ToolType
import com.thundernotes.data.entity.LineType
import com.thundernotes.data.entity.UnderlineType
import com.thundernotes.data.entity.FontFamily
import com.thundernotes.data.spacer.SpacerManager
import com.thundernotes.format.proto.InkStrokeProto
import com.thundernotes.format.proto.InkStrokeSerializer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Exercises the per-note Room database (NoteDatabase) end-to-end against an
 * in-memory Room DB.
 *
 * **Why this test exists:** BigPickle's Sync 3 round (BigPickle.txt "NEW ISSUE 1")
 * flagged that the 12 NoteDatabase DAOs were compiled-but-never-executed —
 * `AppDatabaseTest` covered the app-global DB, but no test opened `NoteDatabase`
 * or touched `noteContentDao`/`notePageDao`/`layerDao`/`strokeDao`/`shapeDao`/
 * `textBoxDao`/`imageDao`/`outlineDao`/`commentDao`/`hyperLinkDao`/`spacerDao`/
 * `pdfInfoDao`. The uncovered entities include the hardest logic in the project:
 *   - `SpacerEntity` documents an O(log N) Fenwick prefix-sum that produces
 *     the page render offset. A sign error or off-by-one would silently shift
 *     every stroke below the insertion point — no crash, just wrong rendering.
 *   - Strokes are immutable page-local coords by design — that invariant is
 *     worth an explicit test.
 *
 * This test exercises ALL 12 NoteDatabase DAOs + the new `SpacerManager`
 * (Fenwick prefix-sum, implemented in `data/spacer/SpacerManager.kt` as part
 * of this same sync).
 *
 * **Invariants pinned (the most important):**
 *   1. Adding a spacer at offset Y shifts the render position of points
 *      STRICTLY BELOW Y (Y' > Y) by exactly the spacer's height. Points at
 *      Y or above (Y' <= Y) are unaffected.
 *   2. Stroke stored coordinates (bounding-box columns + ink_stroke_blob)
 *      are NEVER mutated by spacer operations — adding a spacer only changes
 *      the offset metadata.
 *   3. Removing a spacer reverses the shift exactly.
 *   4. Resizing a spacer by Δ shifts points below by Δ.
 *
 * Run with: `./gradlew :app:testDebugUnitTest --tests *.NoteDatabaseTest`
 *
 * Robolectric SDK note: `@Config(sdk = [33])` because Robolectric 4.13 supports
 * up to SDK 33; our minSdk is 31.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class NoteDatabaseTest {

    private lateinit var db: NoteDatabase
    private lateinit var spacerManager: SpacerManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NoteDatabase::class.java)
            .allowMainThreadQueries()  // tests only
            .build()
        spacerManager = SpacerManager(db.spacerDao())
    }

    @After
    fun teardown() {
        db.close()
    }

    // ─── Test 1: bootstrap trio (NoteContent + NotePage + PageLayer) ─────────

    @Test
    fun `bootstrap note creates content + page + layer rows`() = runBlocking {
        val noteId = "test-note-001"
        val pageId = UUID.randomUUID().toString()
        val layerId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // Insert the 3 rows that NotesRepository.createNote bootstraps.
        db.noteContentDao().upsert(
            NoteContentEntity(
                noteId = noteId,
                defaultPageId = pageId,
                savedPageIndex = 0,
                activatedPageLayerId = layerId,
                zoom = 1.0f,
                pageOffsetX = 0.0f,
                pageOffsetY = 0.0f,
                unboundedNote = true,
                pdfInfoId = null,
                extrasJson = "{}"
            )
        )
        db.notePageDao().insert(
            NotePageEntity(
                pageId = pageId,
                pageIndex = 0,
                pageType = PageType.BLANK.rawValue,
                orientation = PageOrientation.PORTRAIT.rawValue,
                pageRatio = 0.707f,
                pageHeightPx = 3508,
                pageBackgroundColor = 0xFFFFFFFF.toInt(),
                createdTime = now,
                modifiedTime = now
            )
        )
        db.layerDao().insert(
            PageLayerEntity(
                layerId = layerId,
                pageId = pageId,
                layerName = "Layer 1",
                isVisible = true,
                isLocked = false,
                opacity = 1.0f,
                sortOrder = 0,
                createdTime = now,
                modifiedTime = now
            )
        )

        // Read each back.
        val content = db.noteContentDao().getByNoteId(noteId)
        assertNotNull(content)
        assertEquals(pageId, content?.defaultPageId)
        assertEquals(layerId, content?.activatedPageLayerId)
        assertEquals(1.0f, content?.zoom)
        assertEquals(true, content?.unboundedNote)

        val page = db.notePageDao().getByPageId(pageId)
        assertNotNull(page)
        assertEquals(0, page?.pageIndex)
        assertEquals(PageType.BLANK.rawValue, page?.pageType)
        assertEquals(PageOrientation.PORTRAIT.rawValue, page?.orientation)

        val layer = db.layerDao().getByLayerId(layerId)
        assertNotNull(layer)
        assertEquals("Layer 1", layer?.layerName)
        assertEquals(true, layer?.isVisible)
        assertEquals(0, layer?.sortOrder)

        // Layer-page relationship.
        val layersForPage = db.layerDao().getByPage(pageId)
        assertEquals(1, layersForPage.size)
        assertEquals(layerId, layersForPage[0].layerId)
    }

    // ─── Test 2: StrokeDao insert + bounding-box spatial query ───────────────

    @Test
    fun `stroke insert + bounding box spatial query`() = runBlocking {
        val pageId = "test-page-stroke"
        val layerId = "test-layer-stroke"

        // Insert a stroke with bounding box (10, 20) -> (110, 120).
        val strokeId = "stroke-001"
        db.strokeDao().insert(makeStrokeEntity(strokeId, pageId, layerId, top = 20f, bottom = 120f))

        // Read back by ID.
        val fetched = db.strokeDao().getByStrokeId(strokeId)
        assertNotNull(fetched)
        assertEquals(20f, fetched?.top)
        assertEquals(120f, fetched?.bottom)

        // Spatial query: viewport overlapping the stroke -> 1 result.
        val overlapping = db.strokeDao().getByBoundingBox(
            pageId = pageId,
            viewportLeft = 0f, viewportRight = 200f,
            viewportTop = 0f, viewportBottom = 200f
        )
        assertEquals(1, overlapping.size)
        assertEquals(strokeId, overlapping[0].strokeId)

        // Spatial query: viewport NOT overlapping (above the stroke) -> 0 results.
        val above = db.strokeDao().getByBoundingBox(
            pageId = pageId,
            viewportLeft = 0f, viewportRight = 200f,
            viewportTop = 0f, viewportBottom = 15f  // ends before stroke's top=20
        )
        assertTrue("Viewport above the stroke should return 0 results", above.isEmpty())

        // Spatial query: viewport NOT overlapping (right of the stroke) -> 0.
        val rightSide = db.strokeDao().getByBoundingBox(
            pageId = pageId,
            viewportLeft = 200f, viewportRight = 300f,
            viewportTop = 0f, viewportBottom = 200f
        )
        assertTrue("Viewport right of the stroke should return 0 results", rightSide.isEmpty())

        // Count.
        assertEquals(1, db.strokeDao().countByPage(pageId))
    }

    // ─── Test 3: SpacerManager + stroke immutability invariant (THE BIG ONE) ─

    @Test
    fun `spacer insert + resize + remove via SpacerManager pins stroke immutability`() = runBlocking {
        val pageId = "test-page-spacer"
        val layerId = "test-layer-spacer"

        // Insert a stroke with bounding box top=100, bottom=200.
        val strokeId = "stroke-spacer-test"
        val stroke = makeStrokeEntity(strokeId, pageId, layerId, top = 100f, bottom = 200f)
        db.strokeDao().insert(stroke)

        // ── Initial state: no spacers ────────────────────────────────────────
        assertEquals(0f, spacerManager.cumulativeHeightAbove(pageId, 100f))
        assertEquals(0f, spacerManager.cumulativeHeightAbove(pageId, 300f))
        assertEquals(0f, spacerManager.totalHeightOnPage(pageId))

        // ── Insert spacer ABOVE the stroke (offset=50, height=50) ───────────
        // A point at Y=100 should be shifted by 50 (spacer is above it).
        spacerManager.insertSpacer(pageId, offsetInPage = 50f, height = 50f)

        assertEquals(50f, spacerManager.cumulativeHeightAbove(pageId, 100f))
        assertEquals(50f, spacerManager.cumulativeHeightAbove(pageId, 150f))
        assertEquals(50f, spacerManager.totalHeightOnPage(pageId))

        // ── INVARIANT 1: stroke stored coords UNCHANGED ──────────────────────
        // The stroke's bounding box in the DB must STILL be top=100, bottom=200.
        // Adding a spacer does NOT touch the strokes — only the offset metadata.
        val strokeAfterFirstSpacer = db.strokeDao().getByStrokeId(strokeId)
        assertNotNull(strokeAfterFirstSpacer)
        assertEquals("Stroke top must be unchanged after spacer insert", 100f, strokeAfterFirstSpacer?.top)
        assertEquals("Stroke bottom must be unchanged after spacer insert", 200f, strokeAfterFirstSpacer?.bottom)
        // The blob (ink_stroke_blob) is also unchanged — verified by contentEquals.
        assertTrue("Stroke blob must be byte-identical after spacer insert",
            stroke.inkStrokeBlob.contentEquals(strokeAfterFirstSpacer!!.inkStrokeBlob))

        // ── Insert spacer BELOW the stroke (offset=250, height=30) ───────────
        // The stroke at Y=100 is unaffected by this spacer (it's below).
        spacerManager.insertSpacer(pageId, offsetInPage = 250f, height = 30f)

        // Stroke's Y=100 is above the new spacer, so cumulative stays at 50.
        assertEquals(50f, spacerManager.cumulativeHeightAbove(pageId, 100f))
        // A point at Y=300 (below both spacers) should be shifted by 50 + 30 = 80.
        assertEquals(80f, spacerManager.cumulativeHeightAbove(pageId, 300f))
        assertEquals(80f, spacerManager.totalHeightOnPage(pageId))

        // Stroke still unchanged.
        val strokeAfterSecondSpacer = db.strokeDao().getByStrokeId(strokeId)
        assertEquals(100f, strokeAfterSecondSpacer?.top)
        assertEquals(200f, strokeAfterSecondSpacer?.bottom)

        // ── INVARIANT 4: resize the first spacer (50 -> 80, delta=+30) ───────
        // Points below the first spacer's offset (Y=50) should shift by +30.
        val firstSpacerId = db.spacerDao().getByPageOrdered(pageId)[0].spacerId
        spacerManager.resizeSpacer(firstSpacerId, newHeight = 80f)

        // Stroke at Y=100: above-1-spacer sum is now 80 (was 50).
        assertEquals(80f, spacerManager.cumulativeHeightAbove(pageId, 100f))
        // Point at Y=300: both spacers, 80 + 30 = 110.
        assertEquals(110f, spacerManager.cumulativeHeightAbove(pageId, 300f))
        assertEquals(110f, spacerManager.totalHeightOnPage(pageId))

        // Stroke STILL unchanged — resize only changes spacer metadata.
        val strokeAfterResize = db.strokeDao().getByStrokeId(strokeId)
        assertEquals(100f, strokeAfterResize?.top)
        assertEquals(200f, strokeAfterResize?.bottom)

        // ── INVARIANT 3: remove the first spacer (reverses its shift) ─────────
        spacerManager.removeSpacer(firstSpacerId)

        // Now only the second spacer (30) remains.
        assertEquals(0f, spacerManager.cumulativeHeightAbove(pageId, 100f))
        assertEquals(30f, spacerManager.cumulativeHeightAbove(pageId, 300f))
        assertEquals(30f, spacerManager.totalHeightOnPage(pageId))

        // Stroke STILL unchanged — remove only changes spacer metadata.
        val strokeAfterRemove = db.strokeDao().getByStrokeId(strokeId)
        assertEquals(100f, strokeAfterRemove?.top)
        assertEquals(200f, strokeAfterRemove?.bottom)
        assertTrue("Stroke blob must be byte-identical after all spacer ops",
            stroke.inkStrokeBlob.contentEquals(strokeAfterRemove!!.inkStrokeBlob))
    }

    // ─── Test 4: Shape + TextBox + Image DAOs ────────────────────────────────

    @Test
    fun `shape + textbox + image insert + read`() = runBlocking {
        val pageId = "test-page-content"
        val layerId = "test-layer-content"
        val now = System.currentTimeMillis()

        // ── Shape ─────────────────────────────────────────────────────────────
        val shapeId = "shape-001"
        db.shapeDao().insert(
            ShapeEntity(
                shapeId = shapeId, layerId = layerId, pageId = pageId,
                shapeType = ShapeType.RECTANGLE.rawValue,
                borderWidth = 2.0f, lineType = LineType.STRAIGHT.rawValue,
                cornerRadius = 8.0f, borderColor = 0xFF000000.toInt(),
                fillColor = 0xFFFF0000.toInt(), fillOpacity = 0.5f,
                geometryData = "{}",
                left = 10f, top = 10f, right = 100f, bottom = 100f,
                createdTime = now, modifiedTime = now, syncTimestamp = now
            )
        )
        val shape = db.shapeDao().getByShapeId(shapeId)
        assertNotNull(shape)
        assertEquals(ShapeType.RECTANGLE.rawValue, shape?.shapeType)
        assertEquals(0xFFFF0000.toInt(), shape?.fillColor)
        assertEquals(0.5f, shape?.fillOpacity)

        // ── TextBox ───────────────────────────────────────────────────────────
        val textboxId = "textbox-001"
        db.textBoxDao().insert(
            TextBoxEntity(
                textboxId = textboxId, layerId = layerId, pageId = pageId,
                text = "Hello, world!",
                fontFamilyId = FontFamily.NOTO_SANS, fontSize = 16.0f,
                isBold = true, isItalic = false,
                underlineType = UnderlineType.NONE.rawValue,
                fillColor = 0xFF131317.toInt(), textColor = 0xFFF9FAFB.toInt(),
                left = 10f, top = 10f, right = 200f, bottom = 50f,
                rotation = 0f,
                createdTime = now, modifiedTime = now, syncTimestamp = now
            )
        )
        val textbox = db.textBoxDao().getByTextBoxId(textboxId)
        assertNotNull(textbox)
        assertEquals("Hello, world!", textbox?.text)
        assertEquals(FontFamily.NOTO_SANS, textbox?.fontFamilyId)
        assertEquals(true, textbox?.isBold)

        // ── Image ─────────────────────────────────────────────────────────────
        val imageId = "image-001"
        db.imageDao().insert(
            ImageEntity(
                imageId = imageId, layerId = layerId, pageId = pageId,
                assetPath = "images/test.png",
                originalWidth = 800, originalHeight = 600,
                displayWidth = 400f, displayHeight = 300f,
                left = 10f, top = 10f, right = 410f, bottom = 310f,
                opacity = 1.0f, rotation = 0f,
                createdTime = now, modifiedTime = now, syncTimestamp = now
            )
        )
        val image = db.imageDao().getByImageId(imageId)
        assertNotNull(image)
        assertEquals("images/test.png", image?.assetPath)
        assertEquals(800, image?.originalWidth)
        assertEquals(400f, image?.displayWidth)

        // Verify getAllReferencedAssetPaths (for GC of unreferenced assets).
        val paths = db.imageDao().getAllReferencedAssetPaths()
        assertEquals(1, paths.size)
        assertEquals("images/test.png", paths[0])
    }

    // ─── Test 5: Outline + Comment + HyperLink + PdfInfo DAOs ────────────────

    @Test
    fun `outline + comment + hyperlink + pdfInfo insert + read`() = runBlocking {
        val pageId = "test-page-aux"
        val now = System.currentTimeMillis()

        // ── Outline ───────────────────────────────────────────────────────────
        val outlineId = "outline-001"
        db.outlineDao().insert(
            OutlineEntity(
                outlineId = outlineId, pageId = pageId,
                title = "Section 1: Introduction",
                level = 0, targetPageIndex = 0, targetYOffset = 0f,
                createdTime = now
            )
        )
        val outline = db.outlineDao().getByOutlineId(outlineId)
        assertNotNull(outline)
        assertEquals("Section 1: Introduction", outline?.title)
        assertEquals(0, outline?.level)

        // ── Comment ───────────────────────────────────────────────────────────
        val commentId = "comment-001"
        db.commentDao().insert(
            CommentEntity(
                commentId = commentId, pageId = pageId,
                author = "me", text = "Check this derivation later.",
                anchorX = 100f, anchorY = 100f, anchorW = 50f, anchorH = 50f,
                createdTime = now, modifiedTime = now
            )
        )
        val comment = db.commentDao().getByCommentId(commentId)
        assertNotNull(comment)
        assertEquals("me", comment?.author)
        assertEquals("Check this derivation later.", comment?.text)
        assertEquals(100f, comment?.anchorX)

        // Update comment anchor (e.g., when the lasso moves the anchored region).
        db.commentDao().updateAnchor(commentId, x = 200f, y = 200f, w = 60f, h = 60f)
        val movedComment = db.commentDao().getByCommentId(commentId)
        assertEquals(200f, movedComment?.anchorX)
        assertEquals(200f, movedComment?.anchorY)

        // ── HyperLink ─────────────────────────────────────────────────────────
        val linkId = "link-001"
        db.hyperLinkDao().insert(
            HyperLinkEntity(
                linkId = linkId, pageId = pageId,
                label = "GitHub repo",
                url = "https://github.com/langergitanu/ThunderNotes",
                left = 100f, top = 100f, right = 200f, bottom = 120f,
                createdTime = now
            )
        )
        val link = db.hyperLinkDao().getByLinkId(linkId)
        assertNotNull(link)
        assertEquals("GitHub repo", link?.label)
        assertEquals("https://github.com/langergitanu/ThunderNotes", link?.url)

        // Update link bounds (e.g., when the lasso moves the anchored region).
        db.hyperLinkDao().updateBounds(linkId, l = 200f, t = 200f, r = 300f, b = 220f)
        val movedLink = db.hyperLinkDao().getByLinkId(linkId)
        assertEquals(200f, movedLink?.left)
        assertEquals(300f, movedLink?.right)

        // ── PdfInfo ───────────────────────────────────────────────────────────
        val pdfInfoId = "pdfinfo-001"
        db.pdfInfoDao().insert(
            PdfInfoEntity(
                pdfInfoId = pdfInfoId,
                filePath = "pdfs/lecture-01.pdf",
                pageCount = 42, currentPage = 0,
                scale = 1.0f, rotation = 0,
                offsetX = 0f, offsetY = 0f
            )
        )
        val pdfInfo = db.pdfInfoDao().getByPdfInfoId(pdfInfoId)
        assertNotNull(pdfInfo)
        assertEquals("pdfs/lecture-01.pdf", pdfInfo?.filePath)
        assertEquals(42, pdfInfo?.pageCount)

        // Update current page (resumed on next open).
        db.pdfInfoDao().updateCurrentPage(pdfInfoId, currentPage = 5)
        val updatedPdf = db.pdfInfoDao().getByPdfInfoId(pdfInfoId)
        assertEquals(5, updatedPdf?.currentPage)
    }

    // ─── Test 6: duplicate-offset spacers — regression test for BigPickle Sync 4 Open Issue 1 ─

    /**
     * Regression test for BigPickle.txt Sync 4 Open Issue 1: the previous
     * SpacerManager had a duplicate-offset bug where two spacers at the same
     * `offset_in_page` caused the in-memory Fenwick tree to diverge from the
     * DB. Remove/update silently failed in memory while the DB mutated, and
     * the error was sticky (survived later operations).
     *
     * This test pins the fix (invalidate-on-mutation design): manager's
     * cumulative height must ALWAYS match the DB ground truth, even with
     * duplicate offsets + remove + resize + later insert.
     */
    @Test
    fun `duplicate-offset spacers - remove and resize keep manager consistent with DB`() = runBlocking {
        val pageId = "test-page-dup-offset"

        // Insert two spacers at the SAME offset (50) with different heights.
        val spacerId1 = spacerManager.insertSpacer(pageId, offsetInPage = 50f, height = 50f)
        val spacerId2 = spacerManager.insertSpacer(pageId, offsetInPage = 50f, height = 100f)

        // Ground truth: both spacers are above Y=100, cumulative = 50 + 100 = 150.
        assertManagerMatchesDb(pageId, y = 100f)

        // ── Remove the FIRST spacer (height=50) ───────────────────────────────
        // The old SpacerManager would silently fail to remove this from the
        // in-memory tree (it looked up by (offset, height) at the first index,
        // which was the SECOND spacer). Manager would return 150 (wrong) while
        // DB correctly returned 100.
        spacerManager.removeSpacer(spacerId1)
        assertManagerMatchesDb(pageId, y = 100f)  // should be 100

        // ── Resize the remaining spacer (100 -> 300) ───────────────────────────
        // The old SpacerManager would silently fail to resize in memory (same
        // (offset, height) lookup issue). Manager would return 100 (wrong) while
        // DB correctly returned 300.
        spacerManager.resizeSpacer(spacerId2, newHeight = 300f)
        assertManagerMatchesDb(pageId, y = 100f)  // should be 300

        // ── Insert a third spacer at a DIFFERENT offset to verify no stickiness ──
        // BigPickle's S6 scenario: after the duplicate-offset divergence, a later
        // UNRELATED insert at a different offset should NOT carry forward the
        // stale +50 from the failed remove. The old SpacerManager returned
        // manager=157 (db=107) — the +50 error was sticky. With the
        // invalidate-on-mutation fix, the cache is cleared on every mutation,
        // so stickiness is impossible.
        spacerManager.insertSpacer(pageId, offsetInPage = 200f, height = 30f)
        assertManagerMatchesDb(pageId, y = 100f)   // should be 300 (only offset-50 spacer above)
        assertManagerMatchesDb(pageId, y = 300f)  // should be 330 (both spacers above)

        // ── Final state: verify total height too ───────────────────────────────
        val dbSpacers = db.spacerDao().getByPageOrdered(pageId)
        val dbTotal = dbSpacers.sumOf { it.height.toDouble() }.toFloat()
        assertEquals(dbTotal, spacerManager.totalHeightOnPage(pageId))
        assertEquals(330f, spacerManager.totalHeightOnPage(pageId))
    }

    /**
     * Helper: assert the SpacerManager's cumulativeHeightAbove matches the
     * DB ground truth (sum of heights of all spacers where offset < y).
     */
    private suspend fun assertManagerMatchesDb(pageId: String, y: Float) {
        val managerValue = spacerManager.cumulativeHeightAbove(pageId, y)
        val dbSpacers = db.spacerDao().getByPageOrdered(pageId)
        val dbTruth = dbSpacers
            .filter { it.offsetInPage < y }
            .sumOf { it.height.toDouble() }
            .toFloat()
        assertEquals(
            "Manager ($managerValue) must match DB ground truth ($dbTruth) at y=$y",
            dbTruth, managerValue
        )
    }

    // ─── Helper: construct a StrokeEntity with a valid InkStrokeProto blob ────

    private fun makeStrokeEntity(
        strokeId: String,
        pageId: String,
        layerId: String,
        top: Float,
        bottom: Float
    ): StrokeEntity {
        // Build a minimal valid InkStrokeProto (2 points: (0, top) and (100, bottom)).
        val proto = InkStrokeProto(
            id = strokeId,
            layerId = layerId,
            creationTime = System.currentTimeMillis(),
            brushSize = 2.0f,
            brushColor = 0xFF000000.toInt(),
            brushEpsilon = 0.1f,
            brushFamilyId = BrushFamily.THUNDER_BALLPOINT_V1,
            toolType = ToolType.STYLUS.rawValue,
            strokeUnitLengthCm = 5.0f,
            inputXy = listOf(0f, top, 100f, bottom)  // 2 points
        )
        val blob = InkStrokeSerializer.serialize(proto)
        return StrokeEntity(
            strokeId = strokeId,
            layerId = layerId,
            pageId = pageId,
            creationTime = proto.creationTime,
            brushSize = proto.brushSize,
            brushColor = proto.brushColor,
            brushEpsilon = proto.brushEpsilon,
            brushFamilyId = proto.brushFamilyId,
            toolType = proto.toolType,
            strokeUnitLengthCm = proto.strokeUnitLengthCm,
            inkStrokeBlob = blob,
            left = 0f,
            top = top,
            right = 100f,
            bottom = bottom
        )
    }
}
