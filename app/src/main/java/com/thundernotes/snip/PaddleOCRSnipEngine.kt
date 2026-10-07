package com.thundernotes.snip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PaddleOCR-VL-1.6 snip engine (offline, spec §7.3.6 final fallback). Uses
 * PP-FormulaNet for equation recognition + the VL model for text.
 *
 * **Phase 9a**: STUB — the model (~1.6GB) is NOT bundled in the base APK
 * (spec §7.3.6: "ship the lightweight text model in base APK + the larger
 * VL/formula model as on-demand downloadable pack"). Returns
 * `Result.failure("PaddleOCR model not downloaded")` → the fallback chain
 * falls through gracefully.
 *
 * When the model is downloaded (Phase 9b+), this will call the PaddleOCR
 * Android SDK (ONNX Runtime Mobile / Paddle-Lite) with the preprocessed
 * image + the line-aware optimization (binarize → line-detect → per-line
 * recognize → rejoin, per `run_formula.py`).
 */
class PaddleOCRSnipEngine : SnipEngine {

    override val name: String = "PaddleOCR-VL-1.6 (offline)"

    override suspend fun recognize(imageBytes: ByteArray, type: SnipType): Result<SnipResult> =
        withContext(Dispatchers.IO) {
            // TODO Phase 9b+: integrate PaddleOCR Android SDK (ONNX Runtime / Paddle-Lite).
            // The preprocessing (binarize → line-detect → crop → upscale) is already
            // done by SnipPreprocessor; the per-line recognition + LaTeX rejoin is in
            // LatexCleaner. This engine will call the model.predict() on each line crop
            // + clean/join the results — mirroring run_formula.py's `--lines` mode.
            Result.failure(RuntimeException("PaddleOCR model not downloaded — get it from Settings → Snip → Download offline model"))
        }
}
