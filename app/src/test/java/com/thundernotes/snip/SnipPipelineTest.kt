package com.thundernotes.snip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Pure-JVM tests for the snip pipeline's pure components. */
class SnipPipelineTest {

    // ─── SnipImage ─────────────────────────────────────────────────────────

    @Test fun `SnipImage grayAt computes luminance`() {
        val img = SnipImage(2, 1, intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt()))
        assertTrue(img.grayAt(0, 0) == 255)  // white → 255
        assertTrue(img.grayAt(1, 0) == 0)    // black → 0
    }

    @Test fun `SnipImage crop extracts a sub-region`() {
        val img = SnipImage(4, 2, IntArray(8) { 0xFF000000.toInt() or it })  // 8 unique pixels
        val sub = img.crop(1, 0, 2, 2)
        assertEquals(2, sub.width)
        assertEquals(2, sub.height)
        assertEquals(img.pixels[1], sub.pixels[0])  // (1,0) → (0,0) in sub
        assertEquals(img.pixels[5], sub.pixels[2])  // (1,1) → (0,1) in sub
    }

    // ─── SnipPreprocessor: binarize ────────────────────────────────────────

    @Test fun `binarize converts a light image to black ink on white bg`() {
        // 4×4 image: mostly white (200) with one black pixel.
        val px = IntArray(16) { 0xFFC8C8C8.toInt() }  // light gray
        px[5] = 0xFF000000.toInt()  // black ink at (1,1)
        val img = SnipImage(4, 4, px)
        val bw = SnipPreprocessor.binarize(img)
        // The black pixel stays black; the light-gray pixels become white.
        assertEquals(0xFF000000.toInt(), bw.pixels[5])   // ink → black
        assertEquals(0xFFFFFFFF.toInt(), bw.pixels[0])    // bg → white
    }

    @Test fun `binarize auto-inverts when border is dark`() {
        // 4×4 image: dark border (black) + light centre (white) → border mean < 127
        // → inverts → centre becomes black ink, border becomes white.
        val px = IntArray(16) { 0xFFFFFFFF.toInt() }  // all white
        for (i in 0 until 4) {
            px[i] = 0xFF000000.toInt()           // top row black
            px[12 + i] = 0xFF000000.toInt()      // bottom row black
            px[i * 4] = 0xFF000000.toInt()       // left col black
            px[i * 4 + 3] = 0xFF000000.toInt()   // right col black
        }
        val img = SnipImage(4, 4, px)
        val bw = SnipPreprocessor.binarize(img)
        // After inversion + Otsu: border (was black → inverted to white) → white;
        // centre (was white → inverted to black) → black ink.
        assertEquals(0xFFFFFFFF.toInt(), bw.pixels[0])      // border → white
        assertEquals(0xFF000000.toInt(), bw.pixels[5])       // centre → black
    }

    // ─── SnipPreprocessor: detectLineBands ─────────────────────────────────

    @Test fun `detectLineBands finds one band for a single row of ink`() {
        // 4×6 image: row 2 has black ink, rest white.
        val px = IntArray(24) { 0xFFFFFFFF.toInt() }
        for (x in 0 until 4) px[2 * 4 + x] = 0xFF000000.toInt()
        val img = SnipImage(4, 6, px)
        val bands = SnipPreprocessor.detectLineBands(img, minHeight = 1)
        assertEquals(1, bands.size)
        assertTrue("band should contain row 2", bands[0].y0 <= 2 && bands[0].y1 > 2)
    }

    @Test fun `detectLineBands finds no bands for a blank image`() {
        val img = SnipImage(4, 4, IntArray(16) { 0xFFFFFFFF.toInt() })
        val bands = SnipPreprocessor.detectLineBands(img, minHeight = 1)
        assertTrue(bands.isEmpty())
    }

    @Test fun `detectLineBands merges bands separated by a small gap`() {
        // 4×10: rows 1-2 + row 4 have ink (gap of 1 row at row 3, < minGap=12).
        val px = IntArray(40) { 0xFFFFFFFF.toInt() }
        for (y in 1..2) for (x in 0 until 4) px[y * 4 + x] = 0xFF000000.toInt()
        for (x in 0 until 4) px[4 * 4 + x] = 0xFF000000.toInt()
        val img = SnipImage(4, 10, px)
        val bands = SnipPreprocessor.detectLineBands(img, minGap = 12, minHeight = 1)
        // Should merge into one band (gap of 1 row < 12).
        assertEquals(1, bands.size)
    }

    // ─── SnipPreprocessor: cropLine + upscale ──────────────────────────────

    @Test fun `cropLine extracts the band with padding`() {
        // 10×10: ink at row 4-5, columns 3-6.
        val px = IntArray(100) { 0xFFFFFFFF.toInt() }
        for (y in 4..5) for (x in 3..6) px[y * 10 + x] = 0xFF000000.toInt()
        val img = SnipImage(10, 10, px)
        val band = SnipPreprocessor.Band(4, 6)
        val crop = SnipPreprocessor.cropLine(img, band, pad = 1)
        // The crop should contain the ink rows (4-5, padded to 3-7) + tight cols (2-7 padded).
        assertTrue("crop height should cover rows 3-7", crop.height >= 4)
        assertTrue("crop width should be tight", crop.width < 10)
    }

    @Test fun `upscale by 2 doubles dimensions`() {
        val img = SnipImage(2, 3, IntArray(6) { 0xFFFFFFFF.toInt() })
        val up = SnipPreprocessor.upscale(img, 2)
        assertEquals(4, up.width)
        assertEquals(6, up.height)
        assertEquals(24, up.pixels.size)
    }

    @Test fun `upscale by 1 is a no-op`() {
        val img = SnipImage(2, 2, IntArray(4) { 0xFF000000.toInt() })
        val up = SnipPreprocessor.upscale(img, 1)
        assertEquals(img, up)
    }

    // ─── LatexCleaner ───────────────────────────────────────────────────────

    @Test fun `cleanLine strips dollar wrappers`() {
        val r = LatexCleaner.cleanLine("$$\\frac{a}{b}$$")
        assertEquals("\\frac{a}{b}", r)
    }

    @Test fun `cleanLine strips bracket wrappers`() {
        val r = LatexCleaner.cleanLine("\\[x^2 + y^2\\]")
        assertEquals("x^2 + y^2", r)
    }

    @Test fun `cleanLine unwraps single-line aligned`() {
        val r = LatexCleaner.cleanLine("\\begin{aligned} a + b = c \\end{aligned}")
        assertEquals("a + b = c", r)
    }

    @Test fun `cleanLine collapses leading ampersand`() {
        val r = LatexCleaner.cleanLine("& a = b")
        assertEquals("a = b", r)
    }

    @Test fun `joinMultiLine wraps multiple lines in aligned`() {
        val r = LatexCleaner.joinMultiLine(listOf("a = 1", "b = 2"))
        assertTrue(r.startsWith("\\begin{aligned}"))
        assertTrue(r.endsWith("\\end{aligned}"))
        assertTrue(r.contains("a = 1"))
        assertTrue(r.contains("b = 2"))
    }

    @Test fun `joinMultiLine returns single line without aligned wrapper`() {
        val r = LatexCleaner.joinMultiLine(listOf("x^2"))
        assertEquals("x^2", r)
    }

    @Test fun `joinMultiLine returns empty for all-empty lines`() {
        assertEquals("", LatexCleaner.joinMultiLine(listOf("", "  ")))
    }

    // ─── SnipAccounts ──────────────────────────────────────────────────────

    @Before fun setUp() { SnipAccounts.clear() }

    @Test fun `SnipAccounts starts empty`() {
        assertEquals(0, SnipAccounts.accounts.value.size)
    }

    @Test fun `add + getByProvider`() {
        SnipAccounts.add("gemini", "key1", "Personal")
        SnipAccounts.add("glm", "key2", "Work")
        assertEquals(1, SnipAccounts.getByProvider("gemini").size)
        assertEquals(1, SnipAccounts.getByProvider("glm").size)
        assertEquals(0, SnipAccounts.getByProvider("paddle").size)
    }

    @Test fun `remove by id`() {
        val id = SnipAccounts.add("gemini", "key1", "Personal")
        SnipAccounts.remove(id)
        assertEquals(0, SnipAccounts.accounts.value.size)
    }

    @Test fun `nextAccount round-robins`() {
        SnipAccounts.add("gemini", "key1", "A")
        SnipAccounts.add("gemini", "key2", "B")
        val a1 = SnipAccounts.nextAccount("gemini")
        val a2 = SnipAccounts.nextAccount("gemini")
        val a3 = SnipAccounts.nextAccount("gemini")  // wraps back
        assertNotNull(a1); assertNotNull(a2); assertNotNull(a3)
        assertTrue("should alternate", a1!!.apiKey != a2!!.apiKey)
        assertEquals(a1.apiKey, a3!!.apiKey)  // wraps to the first
    }

    @Test fun `nextAccount returns null when no accounts for provider`() {
        assertNull(SnipAccounts.nextAccount("gemini"))
    }

    // ─── FallbackSnipEngine ────────────────────────────────────────────────

    @Test fun `fallback returns the first success`() {
        val failing = object : SnipEngine {
            override val name = "Failing"
            override fun isEnabled() = true
            override suspend fun recognize(imageBytes: ByteArray, type: SnipType) =
                Result.failure<SnipResult>(RuntimeException("fail"))
        }
        val succeeding = object : SnipEngine {
            override val name = "Succeeding"
            override fun isEnabled() = true
            override suspend fun recognize(imageBytes: ByteArray, type: SnipType) =
                Result.success(SnipResult.Text("hello"))
        }
        val fb = FallbackSnipEngine(listOf(failing, succeeding))
        val result = kotlinx.coroutines.runBlocking { fb.recognize(ByteArray(0), SnipType.TEXT) }
        assertTrue(result.isSuccess)
        assertEquals("hello", (result.getOrNull() as SnipResult.Text).text)
    }

    @Test fun `fallback returns the last failure when all fail`() {
        val e1 = object : SnipEngine {
            override val name = "E1"
            override fun isEnabled() = true
            override suspend fun recognize(imageBytes: ByteArray, type: SnipType) =
                Result.failure<SnipResult>(RuntimeException("e1"))
        }
        val e2 = object : SnipEngine {
            override val name = "E2"
            override fun isEnabled() = true
            override suspend fun recognize(imageBytes: ByteArray, type: SnipType) =
                Result.failure<SnipResult>(RuntimeException("e2"))
        }
        val fb = FallbackSnipEngine(listOf(e1, e2))
        val result = kotlinx.coroutines.runBlocking { fb.recognize(ByteArray(0), SnipType.TEXT) }
        assertTrue(result.isFailure)
        assertEquals("e2", result.exceptionOrNull()?.message)
    }

    @Test fun `fallback with empty engine list fails`() {
        val fb = FallbackSnipEngine(emptyList())
        val result = kotlinx.coroutines.runBlocking { fb.recognize(ByteArray(0), SnipType.TEXT) }
        assertTrue(result.isFailure)
    }

    // ─── FallbackSnipEngine: skip disabled engines ────────────────────────

    @Test fun `fallback skips disabled engines entirely (no trial)`() {
        var geminiTried = false
        var glmTried = false
        val gemini = object : SnipEngine {
            override val name = "Gemini"
            override fun isEnabled() = false  // no key → SKIP
            override suspend fun recognize(imageBytes: ByteArray, type: SnipType): Result<SnipResult> {
                geminiTried = true
                return Result.failure<SnipResult>(RuntimeException("should not be tried"))
            }
        }
        val glm = object : SnipEngine {
            override val name = "GLM"
            override fun isEnabled() = true  // has key → TRY
            override suspend fun recognize(imageBytes: ByteArray, type: SnipType): Result<SnipResult> {
                glmTried = true
                return Result.success(SnipResult.Text("from GLM"))
            }
        }
        val fb = FallbackSnipEngine(listOf(gemini, glm))
        val result = kotlinx.coroutines.runBlocking { fb.recognize(ByteArray(0), SnipType.TEXT) }
        assertFalse("disabled engine must not be tried", geminiTried)
        assertTrue("enabled engine must be tried", glmTried)
        assertTrue(result.isSuccess)
        assertEquals("from GLM", (result.getOrNull() as SnipResult.Text).text)
    }

    @Test fun `fallback skips user-disabled engines (SnipSettings)`() {
        var geminiTried = false
        val gemini = object : SnipEngine {
            override val name = "Gemini"
            override fun isEnabled() = true  // key is set
            override suspend fun recognize(imageBytes: ByteArray, type: SnipType): Result<SnipResult> {
                geminiTried = true
                return Result.failure<SnipResult>(RuntimeException("should not be tried"))
            }
        }
        val glm = object : SnipEngine {
            override val name = "GLM"
            override fun isEnabled() = true
            override suspend fun recognize(imageBytes: ByteArray, type: SnipType) =
                Result.success(SnipResult.Text("from GLM"))
        }
        val settings = SnipSettings().apply { disableEngine("Gemini") }  // user disabled Gemini
        val fb = FallbackSnipEngine(listOf(gemini, glm), settings)
        val result = kotlinx.coroutines.runBlocking { fb.recognize(ByteArray(0), SnipType.TEXT) }
        assertFalse("user-disabled engine must not be tried", geminiTried)
        assertTrue(result.isSuccess)
    }

    @Test fun `fallback returns failure when all engines disabled`() {
        val gemini = object : SnipEngine {
            override val name = "Gemini"
            override fun isEnabled() = false  // no key
            override suspend fun recognize(imageBytes: ByteArray, type: SnipType) =
                Result.failure<SnipResult>(RuntimeException("nope"))
        }
        val glm = object : SnipEngine {
            override val name = "GLM"
            override fun isEnabled() = false  // no key
            override suspend fun recognize(imageBytes: ByteArray, type: SnipType) =
                Result.failure<SnipResult>(RuntimeException("nope"))
        }
        val fb = FallbackSnipEngine(listOf(gemini, glm))
        val result = kotlinx.coroutines.runBlocking { fb.recognize(ByteArray(0), SnipType.TEXT) }
        assertTrue(result.isFailure)
    }

    @Test fun `activeEngines returns only enabled + not-user-disabled`() {
        val gemini = object : SnipEngine {
            override val name = "Gemini"
            override fun isEnabled() = true
            override suspend fun recognize(imageBytes: ByteArray, type: SnipType) = Result.success(SnipResult.Text(""))
        }
        val glm = object : SnipEngine {
            override val name = "GLM"
            override fun isEnabled() = false  // no key
            override suspend fun recognize(imageBytes: ByteArray, type: SnipType) = Result.success(SnipResult.Text(""))
        }
        val settings = SnipSettings()  // both user-enabled
        val fb = FallbackSnipEngine(listOf(gemini, glm), settings)
        val active = fb.activeEngines()
        assertEquals(1, active.size)
        assertEquals("Gemini", active[0].name)
    }
}
