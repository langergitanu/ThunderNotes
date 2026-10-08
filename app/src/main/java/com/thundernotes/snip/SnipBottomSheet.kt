package com.thundernotes.snip

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.thundernotes.R
import com.thundernotes.canvas.inject.ClipboardItem
import com.thundernotes.canvas.inject.ThunderClipboard
import com.thundernotes.data.entity.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The snip-type selector + pipeline runner (spec §7.3). Shown after the
 * region-selection confirmation step ([SnipRegionDialogFragment]) once the
 * area to recognize has been captured.
 *
 * Shows 4 buttons (Text / Equation / Code / Diagram). On tap the pipeline is:
 *  1. **Preprocess** the captured bitmap ([SnipPreprocessor]): binarize to
 *     pure black-on-white (auto-polarity — handles dark canvases), then
 *     text-row detection + per-line crops for TEXT/EQUATION. **CODE skips
 *     line-splitting** — code indentation would be destroyed by per-line
 *     tight crops, so the whole binarized image is sent in one shot.
 *  2. **Recognize** via [FallbackSnipEngine] (Gemini → GLM → PaddleOCR;
 *     keyless/user-disabled engines are skipped entirely).
 *  3. **Map** the [SnipResult] to a [ClipboardItem]:
 *     - TEXT → [ClipboardItem.TextBox] (Patrick Hand font).
 *     - EQUATION → user edits the OCR'd LaTeX ([LatexEditDialog]) →
 *       [LatexToStrokes] (KaTeX render → centerline trace) → StrokeGroup,
 *       **scaled to page size** by [StrokeGroupScaler]; falls back to a
 *       Patrick Hand italic textbox (never silently drop content).
 *     - CODE → [ClipboardItem.TextBox] + [TextBox.codeLanguage] so the
 *       textbox renderer applies [CodeFormatter] syntax colours.
 *     - DIAGRAM → traced directly from the image ([DiagramTracer]) — no OCR —
 *       downscaled first for speed, then scaled to page size.
 *  4. [ThunderClipboard].put(item) → the paste button glows → the user pastes
 *     via the injection path ([com.thundernotes.ui.canvas.CanvasActivity]).
 *
 * The captured bitmap + the paste-target page size are passed via the
 * [Companion] (a Bitmap is far too large for a Bundle/Intent).
 */
class SnipBottomSheet : BottomSheetDialogFragment() {

    private var engine: FallbackSnipEngine? = null
    private lateinit var rootLayout: LinearLayout
    private lateinit var codeLangRow: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = inflater.context
        rootLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 48)
        }
        // Title
        rootLayout.addView(TextView(ctx).apply {
            text = getString(R.string.canvas_ai_snip)
            textSize = 16f
            setPadding(0, 0, 0, 16)
        })
        // 4 snip-type buttons
        val labels = listOf(
            SnipType.TEXT to "📝 Text Snip",
            SnipType.EQUATION to "∑ Equation Snip",
            SnipType.CODE to "</> Code Snip",
            SnipType.DIAGRAM to "📊 Diagram Snip",
        )
        for ((type, label) in labels) {
            rootLayout.addView(Button(ctx).apply {
                text = label
                setOnClickListener {
                    if (type == SnipType.CODE) {
                        // Show the language sub-selector first (don't run yet).
                        codeLangRow.visibility = if (codeLangRow.visibility == View.VISIBLE)
                            View.GONE else View.VISIBLE
                    } else {
                        runSnip(type, null)
                    }
                }
            })
        }
        // Code language sub-selector (hidden until Code Snip is tapped).
        codeLangRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(24, 16, 24, 0)
            addView(TextView(ctx).apply {
                text = "Select language:"
                textSize = 13f
                setPadding(0, 0, 0, 8)
            })
        }
        val langGrid = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val langButtons = listOf(
            CodeLanguage.TYPESCRIPT to "TS",
            CodeLanguage.JAVASCRIPT to "JS",
            CodeLanguage.JSX to "JSX",
            CodeLanguage.PYTHON to "Py",
            CodeLanguage.JAVA to "Java",
            CodeLanguage.CPP to "C++",
        )
        for ((lang, lbl) in langButtons) {
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(4, 0, 4, 0)
            langGrid.addView(Button(ctx).apply {
                text = lbl
                setOnClickListener { runSnip(SnipType.CODE, lang) }
                layoutParams = lp
            })
        }
        codeLangRow.addView(langGrid)
        rootLayout.addView(codeLangRow)
        return rootLayout
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Build the engine chain (Gemini → GLM → PaddleOCR; disabled engines
        // skipped). [SnipSettings.shared] so the settings sheet's toggles
        // affect this live chain.
        engine = FallbackSnipEngine(
            listOf(GeminiSnipEngine(), GLMSnipEngine(), PaddleOCRSnipEngine()),
            SnipSettings.shared,
        )
        // Default the paste target to a standard 700×990-dp page when the
        // canvas didn't provide its real page size (overlay-capture flow).
        if (pasteTargetWidthPx <= 0f || pasteTargetHeightPx <= 0f) {
            val d = resources.displayMetrics.density
            pasteTargetWidthPx = 700f * d
            pasteTargetHeightPx = 990f * d
        }
    }

    private fun runSnip(type: SnipType, codeLanguage: CodeLanguage?) {
        val bmp = bitmap
        if (bmp == null) {
            Toast.makeText(requireContext(), "Capture failed", Toast.LENGTH_SHORT).show()
            dismiss(); return
        }
        // Collapse the language row once a language is chosen.
        if (::codeLangRow.isInitialized) codeLangRow.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            Toast.makeText(requireContext(), "Recognizing…", Toast.LENGTH_SHORT).show()
            val result = withContext(Dispatchers.IO) {
                runSnipPipeline(bmp, type, codeLanguage)
            }
            result.fold(
                onSuccess = { item ->
                    ThunderClipboard.put(item)
                    Toast.makeText(requireContext(),
                        getString(R.string.canvas_injected), Toast.LENGTH_SHORT).show()
                },
                onFailure = { e ->
                    Toast.makeText(requireContext(),
                        "Snip failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            )
            dismiss()
        }
    }

    /** The full snip pipeline: preprocess → engine → ClipboardItem. */
    private suspend fun runSnipPipeline(
        bmp: Bitmap, type: SnipType, codeLanguage: CodeLanguage?,
    ): Result<ClipboardItem> {
        return runCatching {
            // 1. Convert Bitmap → SnipImage (pure pixel array).
            val w = bmp.width; val h = bmp.height
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            val img = SnipImage(w, h, pixels)

            // DIAGRAM snips skip the OCR engine entirely — they're traced
            // directly from the image (spec §7.3.4d: "traced result converted
            // to native pen strokes"). The trace runs on a downscaled copy
            // (Zhang-Suen thinning is O(passes × pixels) — a full-screen
            // capture would take tens of seconds), then the traced group is
            // rescaled to a sensible on-page size.
            if (type == SnipType.DIAGRAM) {
                val factor = SnipPreprocessor.downscaleFactorFor(w, h, maxDim = 1600)
                val small = SnipPreprocessor.downscale(img, factor)
                val traced = DiagramTracer.trace(small)
                if (traced.isNotEmpty()) {
                    // Scale from trace-space → page-space so the pasted diagram
                    // fits on the page (target: ~90% page width / ~70% height).
                    val (scaled, bbox) = StrokeGroupScaler.fit(
                        traced, targetW = pasteTargetWidthPx * 0.9f,
                        targetH = pasteTargetHeightPx * 0.7f,
                    )
                    return@runCatching ClipboardItem.StrokeGroup(
                        strokes = scaled,
                        bbox = bbox,
                    )
                }
                throw RuntimeException("Diagram tracing produced no strokes")
            }

            // 2. Preprocess for OCR: binarize → (TEXT/EQUATION only) line
            //    detection + per-line crops + 2× upscale. CODE sends the whole
            //    image in ONE crop — per-line tight crops strip the leading
            //    whitespace that code indentation depends on — and does NOT
            //    upscale (2× on a full-screen capture would allocate a ~96MB
            //    pixel array; the engines handle native resolution fine).
            val bw = SnipPreprocessor.binarize(img)
            val crops = when (type) {
                SnipType.CODE -> listOf(bw)
                else -> {
                    val bands = SnipPreprocessor.detectLineBands(bw, minHeight = 1)
                    if (bands.isNotEmpty()) {
                        bands.map { band -> SnipPreprocessor.cropLine(bw, band) }
                            .map { crop -> SnipPreprocessor.upscale(crop, 2) }
                    } else {
                        // No text rows detected (blank/odd capture) → send the
                        // whole binarized image once, WITHOUT upscaling (a 2×
                        // of a full-screen capture would allocate ~96MB).
                        listOf(bw)
                    }
                }
            }

            // 3. For each crop → PNG bytes → engine.recognize.
            val eng = engine ?: throw RuntimeException("No engine")
            val perLineResults = mutableListOf<SnipResult>()
            for (crop in crops) {
                val cropBmp = Bitmap.createBitmap(
                    crop.width, crop.height, Bitmap.Config.ARGB_8888,
                )
                cropBmp.setPixels(crop.pixels, 0, crop.width, 0, 0, crop.width, crop.height)
                val baos = java.io.ByteArrayOutputStream()
                cropBmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                val pngBytes = baos.toByteArray()

                val r = eng.recognize(pngBytes, type)
                if (r.isSuccess) perLineResults.add(r.getOrNull()!!)
            }
            if (perLineResults.isEmpty()) throw RuntimeException("All engines failed")

            // 4. Map SnipResult → ClipboardItem.
            if (type == SnipType.EQUATION) {
                return@runCatching equationResult(perLineResults)
            }
            mapResultToClipboard(perLineResults, type, codeLanguage)
        }
    }

    /**
     * The EQUATION branch (spec §7.3.4b): OCR'd LaTeX → user edit/re-render
     * dialog → [LatexToStrokes] → **page-scaled** [ClipboardItem.StrokeGroup].
     * Falls back to a Patrick Hand italic textbox (showing the LaTeX) when the
     * user cancels or the trace fails — content is never silently dropped.
     */
    private suspend fun equationResult(perLineResults: List<SnipResult>): ClipboardItem {
        val ocrLatex = LatexCleaner.joinMultiLine(
            perLineResults.map { r -> (r as? SnipResult.LaTeX)?.latex
                ?: (r as? SnipResult.Text)?.text ?: "" }
        )
        if (ocrLatex.isBlank()) {
            return ClipboardItem.TextBox(
                text = "", fontFamily = FontFamily.PATRICK_HAND,
                bold = false, italic = true, underline = 0,
                x = 50f, y = 50f, bbox = floatArrayOf(0f, 0f, 400f, 200f),
            )
        }
        // §7.3.4b edit step: the dialog must run on the UI thread.
        val edited = withContext(Dispatchers.Main) {
            LatexEditDialog.edit(ocrLatex, requireContext())
        }
        // User cancelled → textbox fallback (the LaTeX is preserved, not lost).
        if (edited == null) {
            return ClipboardItem.TextBox(
                text = ocrLatex, fontFamily = FontFamily.PATRICK_HAND,
                bold = false, italic = true, underline = 0,
                x = 50f, y = 50f, bbox = floatArrayOf(0f, 0f, 400f, 200f),
            )
        }
        val strokes = LatexToStrokes.convert(edited, requireContext())
        if (strokes != null && strokes.isNotEmpty()) {
            // Scale the traced group (KaTeX renders at a fixed 1080-px width)
            // so the pasted equation reads naturally on the page (~55% width).
            val (scaled, bbox) = StrokeGroupScaler.fit(
                strokes, targetW = pasteTargetWidthPx * 0.55f,
                targetH = pasteTargetHeightPx * 0.4f,
            )
            return ClipboardItem.StrokeGroup(strokes = scaled, bbox = bbox)
        }
        // Trace failed → Patrick Hand italic textbox (never drop content).
        return ClipboardItem.TextBox(
            text = edited, fontFamily = FontFamily.PATRICK_HAND,
            bold = false, italic = true, underline = 0,
            x = 50f, y = 50f, bbox = floatArrayOf(0f, 0f, 400f, 200f),
        )
    }

    /**
     * Map the recognition result(s) to a ClipboardItem for ThunderClipboard.
     *
     * **Default font = Patrick Hand** for TEXT + CODE (the user can change the
     * textbox font afterward via the textbox editor).
     *
     * For CODE snips, [codeLanguage] (user-selected) is carried in
     * [ClipboardItem.TextBox.codeLanguage] → the textbox renderer applies
     * [CodeFormatter] syntax colours on top of the base font. The colours are
     * font-agnostic spans, so a font change to JetBrains Mono survives.
     */
    private fun mapResultToClipboard(
        results: List<SnipResult>,
        type: SnipType,
        codeLanguage: CodeLanguage?,
    ): ClipboardItem {
        return when (type) {
            SnipType.TEXT -> {
                val text = results.joinToString("\n") { r ->
                    when (r) {
                        is SnipResult.Text -> r.text
                        is SnipResult.Code -> r.rawCode
                        is SnipResult.LaTeX -> r.latex
                        is SnipResult.Strokes -> ""
                    }
                }
                ClipboardItem.TextBox(
                    text = text,
                    fontFamily = FontFamily.PATRICK_HAND,
                    bold = false, italic = false, underline = 0,
                    x = 50f, y = 50f,
                    bbox = floatArrayOf(0f, 0f, 400f, 200f),
                )
            }
            SnipType.CODE -> {
                // Join the recognized code → one TextBox. Markdown fences are
                // stripped defensively — vision models often wrap their answer
                // in ```lang … ``` despite the prompt saying not to.
                val code = stripCodeFences(
                    results.joinToString("\n") { r ->
                        when (r) {
                            is SnipResult.Code -> r.rawCode
                            is SnipResult.Text -> r.text
                            is SnipResult.LaTeX -> r.latex
                            is SnipResult.Strokes -> ""
                        }
                    }
                )
                // Prefer the user-selected language, then the engine's hint.
                val langName: String? = codeLanguage?.displayName
                    ?: (results.firstOrNull { it is SnipResult.Code }
                        as? SnipResult.Code)?.language
                ClipboardItem.TextBox(
                    text = code,
                    fontFamily = FontFamily.PATRICK_HAND,
                    bold = false, italic = false, underline = 0,
                    x = 50f, y = 50f,
                    bbox = floatArrayOf(0f, 0f, 500f, 300f),
                    codeLanguage = langName,
                )
            }
            SnipType.EQUATION -> {
                // Unreachable (equationResult handles this type) — kept as a
                // safe branch in case of future routing changes.
                val latex = results.joinToString("\n") { r ->
                    (r as? SnipResult.LaTeX)?.latex ?: (r as? SnipResult.Text)?.text ?: ""
                }
                ClipboardItem.TextBox(
                    text = latex,
                    fontFamily = FontFamily.PATRICK_HAND,
                    bold = false, italic = true, underline = 0,
                    x = 50f, y = 50f,
                    bbox = floatArrayOf(0f, 0f, 400f, 200f),
                )
            }
            SnipType.DIAGRAM -> {
                // Unreachable (handled at the top of runSnipPipeline).
                ClipboardItem.TextBox(
                    text = "",
                    fontFamily = FontFamily.PATRICK_HAND,
                    bold = false, italic = false, underline = 0,
                    x = 50f, y = 50f,
                    bbox = floatArrayOf(0f, 0f, 100f, 50f),
                )
            }
        }
    }

    /**
     * Remove markdown code fences a model may have added around its answer
     * (delegates to the pure, tested [SnipTextCleaner.stripCodeFences]).
     */
    private fun stripCodeFences(code: String): String =
        SnipTextCleaner.stripCodeFences(code)

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        // Let the capture activity (when we're shown from the overlay flow)
        // finish itself once the whole snip interaction is over.
        onDismissed?.invoke()
        onDismissed = null
    }

    companion object {
        /** Static holder for the captured bitmap (too large for Bundle/Intent). */
        @Volatile
        var bitmap: Bitmap? = null

        /**
         * Optional one-shot callback fired when this sheet is dismissed — used
         * by [SnipCaptureActivity] (the overlay-capture host) to finish itself
         * AFTER the pipeline completes instead of racing a fragment commit
         * against activity destruction (the old bug: the sheet never showed).
         */
        @Volatile
        var onDismissed: (() -> Unit)? = null

        /**
         * The paste-target page size in px (the canvas page the result will be
         * pasted onto). Set by [com.thundernotes.ui.canvas.CanvasActivity]
         * before showing the sheet; defaults to the standard 700×990-dp page
         * (computed from the context when the sheet opens) so the overlay flow
         * (no canvas context) still scales sensibly.
         */
        @Volatile
        var pasteTargetWidthPx: Float = 0f
        @Volatile
        var pasteTargetHeightPx: Float = 0f
    }
}
