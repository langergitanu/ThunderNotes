package com.thundernotes.snip

import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
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
 * The snip-type selector + pipeline runner (spec §7.3). Launched from
 * [com.thundernotes.ui.canvas.CanvasActivity]'s btnAiSnip after capturing the
 * canvas surface.
 *
 * Shows 4 buttons (Text / Equation / Code / Diagram). On tap:
 * 1. Preprocess the captured bitmap (SnipPreprocessor: binarize → line-detect
 *    → crop per line → optionally upscale — mirrors the user's run_formula.py).
 * 2. Run the [FallbackSnipEngine] (Gemini → GLM → PaddleOCR, skipping disabled/
 *    keyless engines entirely).
 * 3. Map the [SnipResult] to a [ClipboardItem]:
 *    - Text → ClipboardItem.TextBox (recognized text, **Patrick Hand** font).
 *    - LaTeX → (Phase 9c: KaTeX render → centerline trace → StrokeGroup).
 *      Falls back to a Patrick Hand italic textbox (showing the LaTeX) if
 *      the trace fails.
 *    - Code → ClipboardItem.TextBox (formatted code, **Patrick Hand** base font
 *      + codeLanguage carried so the textbox renderer can apply [CodeFormatter]
 *      syntax colours). The colours are font-agnostic spans, so the user can
 *      switch the textbox font to JetBrains Mono afterward without losing
 *      the colours.
 *    - Strokes → (Phase 9d: diagram tracer) traced directly from the image.
 * 4. [ThunderClipboard].put(item) → the paste button glows → the user pastes
 *    via the existing Phase 8b injection path.
 *
 * **Default font (spec §6.10 + user request):** snipped text, equation
 * (textbox fallback), + code all default to **Patrick Hand** (index 4).
 * After pasting, the user can change the textbox font to any of the 10 spec
 * fonts (+ JetBrains Mono for code) via the textbox editor.
 *
 * The captured bitmap is passed via [Companion.bitmap] (a static holder — the
 * bitmap is too large for Bundle/Intent).
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
                        // Show the language sub-selector (don't run yet).
                        codeLangRow.visibility = if (codeLangRow.visibility == View.VISIBLE)
                            View.GONE else View.VISIBLE
                    } else {
                        runSnip(type, null)
                    }
                }
            })
        }
        // Code language sub-selector (hidden until Code Snip is tapped).
        // 6 language buttons + the theme is derived via CodeTheme.forLanguage.
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
        // 6 language buttons in a row (wraps if narrow — tablet width is fine).
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
        // Build the engine chain (Gemini → GLM → PaddleOCR; disabled engines skipped).
        engine = FallbackSnipEngine(
            listOf(GeminiSnipEngine(), GLMSnipEngine(), PaddleOCRSnipEngine()),
            SnipSettings(),
        )
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

            // 2. Preprocess: binarize → line-detect → crop per line → upscale.
            val bw = SnipPreprocessor.binarize(img)
            val bands = SnipPreprocessor.detectLineBands(bw, minHeight = 1)

            // DIAGRAM snips skip the OCR engine entirely — they're traced directly
            // from the image (no text recognition needed). Spec §7.3.4d:
            // "traced result converted to native pen strokes."
            if (type == SnipType.DIAGRAM) {
                val strokes = DiagramTracer.trace(img)
                if (strokes.isNotEmpty()) {
                    return@runCatching ClipboardItem.StrokeGroup(
                        strokes = strokes,
                        bbox = floatArrayOf(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()),
                    )
                }
                throw RuntimeException("Diagram tracing produced no strokes")
            }

            val crops = if (bands.isNotEmpty()) {
                bands.map { band -> SnipPreprocessor.cropLine(bw, band) }
                    .map { crop -> SnipPreprocessor.upscale(crop, 2) }
            } else {
                listOf(SnipPreprocessor.upscale(bw, 2))
            }

            // 3. For each line crop → convert to PNG bytes → engine.recognize.
            val eng = engine ?: return@runCatching Result.failure<ClipboardItem>(
                RuntimeException("No engine")
            ).getOrThrow()
            val perLineResults = mutableListOf<SnipResult>()
            for (crop in crops) {
                // SnipImage → Bitmap → PNG bytes.
                val cropBmp = Bitmap.createBitmap(crop.width, crop.height, Bitmap.Config.ARGB_8888)
                cropBmp.setPixels(crop.pixels, 0, crop.width, 0, 0, crop.width, crop.height)
                val baos = java.io.ByteArrayOutputStream()
                cropBmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                val pngBytes = baos.toByteArray()

                val r = eng.recognize(pngBytes, type)
                if (r.isSuccess) perLineResults.add(r.getOrNull()!!)
            }
            if (perLineResults.isEmpty()) throw RuntimeException("All engines failed")

            // 4. Map SnipResult → ClipboardItem.
            // For EQUATION snips (spec §7.3.4b): the OCR'd LaTeX is shown to
            // the user for editing/re-rendering → on Confirm, the (possibly
            // edited) LaTeX is traced to strokes → StrokeGroup. Falls back to
            // a Patrick Hand italic textbox (showing the LaTeX) if the trace
            // fails or the user cancels (never silently drop content).
            if (type == SnipType.EQUATION) {
                val ocrLatex = LatexCleaner.joinMultiLine(
                    perLineResults.map { r -> (r as? SnipResult.LaTeX)?.latex
                        ?: (r as? SnipResult.Text)?.text ?: "" }
                )
                if (ocrLatex.isNotBlank()) {
                    // §7.3.4b edit step: switch to Main for the dialog.
                    val edited = withContext(Dispatchers.Main) {
                        LatexEditDialog.edit(ocrLatex, requireContext())
                    }
                    // User cancelled → textbox fallback (the LaTeX is preserved).
                    if (edited == null) {
                        return@runCatching ClipboardItem.TextBox(
                            text = ocrLatex,
                            fontFamily = FontFamily.PATRICK_HAND,
                            bold = false, italic = true, underline = 0,
                            x = 50f, y = 50f,
                            bbox = floatArrayOf(0f, 0f, 400f, 200f),
                        )
                    }
                    val strokes = LatexToStrokes.convert(edited, requireContext())
                    if (strokes != null && strokes.isNotEmpty()) {
                        return@runCatching ClipboardItem.StrokeGroup(
                            strokes = strokes,
                            bbox = floatArrayOf(0f, 0f, 600f, 400f),
                        )
                    }
                    // Fallback: Patrick Hand italic textbox (never drop content).
                    return@runCatching ClipboardItem.TextBox(
                        text = edited,
                        fontFamily = FontFamily.PATRICK_HAND,
                        bold = false, italic = true, underline = 0,
                        x = 50f, y = 50f,
                        bbox = floatArrayOf(0f, 0f, 400f, 200f),
                    )
                }
            }

            mapResultToClipboard(perLineResults, type, codeLanguage)
        }
    }

    /**
     * Map the recognition result(s) to a ClipboardItem for ThunderClipboard.
     *
     * **Default font = Patrick Hand** (index 4) for TEXT, CODE, + the
     * EQUATION textbox fallback. The user can change the textbox font
     * afterward via the textbox editor.
     *
     * For CODE snips, [codeLanguage] (user-selected) is carried in the
     * [ClipboardItem.TextBox.codeLanguage] field → the textbox renderer
     * applies [CodeFormatter] syntax colours on top of the base Patrick Hand
     * font. The colours are font-agnostic spans, so a font change to
     * JetBrains Mono (monospace) survives.
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
                        is SnipResult.Strokes -> "(strokes)"
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
                // Join per-line code → a single TextBox. Carry the language so
                // the textbox renderer can apply [CodeFormatter] colours.
                val code = results.joinToString("\n") { r ->
                    when (r) {
                        is SnipResult.Code -> r.rawCode
                        is SnipResult.Text -> r.text
                        is SnipResult.LaTeX -> r.latex
                        is SnipResult.Strokes -> "(strokes)"
                    }
                }
                // Prefer the user-selected language (displayName), then the
                // engine's string hint. Normalise both to String? so the
                // textbox renderer can look up the CodeLanguage by name.
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
                // Phase 9c: LaTeX → KaTeX → centerline trace → StrokeGroup.
                // The textbox fallback (Patrick Hand italic) is handled in
                // runSnipPipeline above; this branch is only reached if the
                // LaTeX was blank — keep a Patrick Hand placeholder.
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
                // Phase 9d: diagram trace → StrokeGroup (handled in
                // runSnipPipeline). Not reached here.
                ClipboardItem.TextBox(
                    text = "(diagram tracing — Phase 9d)",
                    fontFamily = FontFamily.PATRICK_HAND,
                    bold = false, italic = false, underline = 0,
                    x = 50f, y = 50f,
                    bbox = floatArrayOf(0f, 0f, 100f, 50f),
                )
            }
        }
    }

    companion object {
        /** Static holder for the captured bitmap (too large for Bundle/Intent). */
        @Volatile
        var bitmap: Bitmap? = null
    }
}
