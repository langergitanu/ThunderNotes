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
 *    - Text → ClipboardItem.TextBox (the recognized text).
 *    - LaTeX → (Phase 9c: KaTeX render → OpenCV trace → ClipboardItem.StrokeGroup).
 *      For now, puts the LaTeX as a TextBox (so the user sees the recognized equation).
 *    - Code → ClipboardItem.TextBox (formatted code).
 *    - Strokes → (Phase 9d: diagram tracer). For now, not reached (no Diagram OCR engine).
 * 4. [ThunderClipboard].put(item) → the paste button glows → the user pastes
 *    via the existing Phase 8b injection path.
 *
 * The captured bitmap is passed via [Companion.bitmap] (a static holder — the
 * bitmap is too large for Bundle/Intent).
 */
class SnipBottomSheet : BottomSheetDialogFragment() {

    private var engine: FallbackSnipEngine? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = inflater.context
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 48)
            // Title
            addView(TextView(ctx).apply {
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
                addView(Button(ctx).apply {
                    text = label
                    setOnClickListener { runSnip(type) }
                })
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Build the engine chain (Gemini → GLM → PaddleOCR; disabled engines skipped).
        engine = FallbackSnipEngine(
            listOf(GeminiSnipEngine(), GLMSnipEngine(), PaddleOCRSnipEngine()),
            SnipSettings(),
        )
    }

    private fun runSnip(type: SnipType) {
        val bmp = bitmap
        if (bmp == null) {
            Toast.makeText(requireContext(), "Capture failed", Toast.LENGTH_SHORT).show()
            dismiss(); return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            Toast.makeText(requireContext(), "Recognizing…", Toast.LENGTH_SHORT).show()
            val result = withContext(Dispatchers.IO) {
                runSnipPipeline(bmp, type)
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
    private suspend fun runSnipPipeline(bmp: Bitmap, type: SnipType): Result<ClipboardItem> {
        return runCatching {
            // 1. Convert Bitmap → SnipImage (pure pixel array).
            val w = bmp.width; val h = bmp.height
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            val img = SnipImage(w, h, pixels)

            // 2. Preprocess: binarize → line-detect → crop per line → upscale.
            val bw = SnipPreprocessor.binarize(img)
            val bands = SnipPreprocessor.detectLineBands(bw, minHeight = 1)
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
            // For EQUATION snips: LaTeX → KaTeX render → centerline trace →
            // StrokeGroup (native erasable strokes). Falls back to TextBox
            // (showing the LaTeX) if the trace fails.
            if (type == SnipType.EQUATION) {
                val latex = LatexCleaner.joinMultiLine(
                    perLineResults.map { r -> (r as? SnipResult.LaTeX)?.latex
                        ?: (r as? SnipResult.Text)?.text ?: "" }
                )
                if (latex.isNotBlank()) {
                    val strokes = LatexToStrokes.convert(latex, requireContext())
                    if (strokes != null && strokes.isNotEmpty()) {
                        return@runCatching ClipboardItem.StrokeGroup(
                            strokes = strokes,
                            bbox = floatArrayOf(0f, 0f, 600f, 400f),
                        )
                    }
                    // Fallback: show the LaTeX as a textbox (never silently drop content).
                    return@runCatching ClipboardItem.TextBox(
                        text = latex,
                        fontFamily = 2, bold = false, italic = true, underline = 0,
                        x = 50f, y = 50f,
                        bbox = floatArrayOf(0f, 0f, 400f, 200f),
                    )
                }
            }

            mapResultToClipboard(perLineResults, type)
        }
    }

    /** Map the recognition result(s) to a ClipboardItem for ThunderClipboard. */
    private fun mapResultToClipboard(results: List<SnipResult>, type: SnipType): ClipboardItem {
        return when (type) {
            SnipType.TEXT, SnipType.CODE -> {
                // Join per-line text/code → a single TextBox.
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
                    fontFamily = 0, bold = false, italic = false, underline = 0,
                    x = 50f, y = 50f,
                    bbox = floatArrayOf(0f, 0f, 400f, 200f),
                )
            }
            SnipType.EQUATION -> {
                // Phase 9c: LaTeX → KaTeX → OpenCV trace → StrokeGroup.
                // For now: put the LaTeX as a TextBox so the user sees the result.
                val latex = results.joinToString("\n") { r ->
                    (r as? SnipResult.LaTeX)?.latex ?: (r as? SnipResult.Text)?.text ?: ""
                }
                ClipboardItem.TextBox(
                    text = latex,
                    fontFamily = 2, bold = false, italic = true, underline = 0,
                    x = 50f, y = 50f,
                    bbox = floatArrayOf(0f, 0f, 400f, 200f),
                )
            }
            SnipType.DIAGRAM -> {
                // Phase 9d: OpenCV diagram trace → StrokeGroup.
                // For now, not reached (no Diagram engine).
                ClipboardItem.TextBox(
                    text = "(diagram tracing — Phase 9d)",
                    fontFamily = 0, bold = false, italic = false, underline = 0,
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
