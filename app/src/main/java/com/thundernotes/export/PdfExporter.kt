package com.thundernotes.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.view.View
import androidx.core.content.FileProvider
import java.io.File

/**
 * PDF export (spec §6.2.5: "Supports two types — Native export (.thunder) for
 * local backup and PDF for compatibility"). The `.thunder` export (the native
 * ZIP) ships from the library's 3-dot menu; this class handles the PDF side,
 * called from the canvas (where the rendered page views are live).
 *
 * **Approach:** for each canvas page, rasterize the page-root View to a Bitmap
 * via [View.draw] (the Ink GL strokes + textboxes + shapes all render into the
 * View's draw pass) → draw the bitmap onto a [PdfDocument.Page] canvas. The
 * PDF is then written to the app's cache dir + shared via [FileProvider]
 * (ACTION_SEND) so the user can save it to Downloads / cloud / etc.
 *
 * This is a rasterized PDF (each page = one bitmap image), not a vector PDF.
 * A vector PDF (re-drawing each [StrokeRecord] as a PDF path) is a refinement
 * — the spec allows "any reliable engine"; the rasterized version is reliable
 * + produces a faithful visual copy of the canvas.
 *
 * Android-only (PdfDocument + View.draw). No external deps.
 */
object PdfExporter {

    /**
     * Render [pageRoots] (one per canvas page) to a multi-page PDF + share it.
     *
     * @param noteName the note's display name (used for the PDF filename).
     * @return the created PDF [File], or null on failure.
     */
    fun exportAndShare(
        context: Context,
        noteName: String,
        pageRoots: List<View>,
    ): File? {
        if (pageRoots.isEmpty()) return null
        val doc = PdfDocument()
        try {
            for ((idx, root) in pageRoots.withIndex()) {
                val w = root.width.coerceAtLeast(1)
                val h = root.height.coerceAtLeast(1)
                val pageInfo = PdfDocument.PageInfo.Builder(w, h, idx + 1).create()
                val page = doc.startPage(pageInfo)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                // Draw the page's View hierarchy (Ink strokes + textboxes +
                // shapes + grid/ruler overlays — everything visible).
                root.draw(canvas)
                page.canvas.drawBitmap(bmp, 0f, 0f, null)
                doc.finishPage(page)
                bmp.recycle()
            }
            val outFile = File(context.cacheDir, "${sanitize(noteName)}.pdf")
            outFile.outputStream().use { doc.writeTo(it) }
            sharePdf(context, outFile, noteName)
            return outFile
        } catch (e: Exception) {
            return null
        } finally {
            doc.close()
        }
    }

    /** Share the PDF via FileProvider + ACTION_SEND. */
    private fun sharePdf(context: Context, file: File, noteName: String) {
        val authority = "${context.packageName}.fileprovider"
        val uri: Uri = FileProvider.getUriForFile(context, authority, file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "$noteName.pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(share, "Export $noteName as PDF"))
    }

    /** Sanitize a note name → a filesystem-safe PDF filename. */
    private fun sanitize(name: String): String =
        name.trim().ifBlank { "note" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(60)
}
