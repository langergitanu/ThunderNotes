package com.thundernotes.snip

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Renders LaTeX to a high-DPI bitmap via KaTeX in an offscreen WebView
 * (spec §7.8 / thunder-format-proposal Part D: "KaTeX render (offscreen
 * WebView, OFFLINE assets, black-on-white, high-DPI)").
 *
 * Creates a WebView, loads a minimal HTML page with KaTeX (CDN for Phase 9c;
 * offline assets bundled in `assets/katex/` is a refinement), renders the
 * LaTeX string, + captures the rendered bitmap.
 *
 * Android-only (WebView). The pure centerline tracer ([CenterlineTracer])
 * is unit-tested; this renderer is build-verifiable.
 */
object KatexRenderer {

    private const val FIXED_WIDTH = 1080  // high-DPI render width (px).

    /**
     * Render [latex] to a black-on-white bitmap. Returns null on failure.
     * Must be called on the main thread (WebView requirement).
     */
    suspend fun render(latex: String, context: Context): Bitmap? =
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                try {
                    createAndRender(latex, context) { bmp ->
                        if (cont.isActive) cont.resume(bmp)
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createAndRender(latex: String, context: Context, onDone: (Bitmap?) -> Unit) {
        val webView = WebView(context)
        webView.settings.javaScriptEnabled = true
        // §7.8 offline assets: allow file:// access for the bundled KaTeX
        // CSS/JS/fonts in assets/katex/.
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.layoutParams = android.widget.FrameLayout.LayoutParams(
            FIXED_WIDTH, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
        )

        // JavaScript interface for the render-complete callback.
        class RenderInterface {
            @JavascriptInterface
            fun onRendered() {
                // KaTeX has rendered — capture the bitmap after a short delay
                // (to let the layout settle).
                Handler(Looper.getMainLooper()).postDelayed({
                    captureBitmap(webView, onDone)
                }, 200)
            }
        }
        webView.addJavascriptInterface(RenderInterface(), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // The KaTeX JS has loaded — the inline script will call
                // Android.onRendered() after katex.render().
            }
        }

        // Build the HTML with the bundled offline KaTeX (§7.8: "KaTeX in an
        // offscreen WebView, assets bundled"). The CSS + JS + fonts ship in
        // assets/katex/ — loaded via file:///android_asset/katex/ so no network
        // is needed. The font @font-face URLs in katex.min.css are relative
        // (fonts/KaTeX_*.woff2) → resolve under the same asset base.
        val escapedLatex = latex
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        val html = """<!DOCTYPE html>
<html><head>
<link rel="stylesheet" href="file:///android_asset/katex/katex.min.css">
<script src="file:///android_asset/katex/katex.min.js"></script>
<style>
body { margin:0; padding:32px; background:#FFFFFF; color:#000000;
       font-size:32px; -webkit-font-smoothing:none; }
#render { display:inline-block; }
</style>
</head><body>
<div id="render"></div>
<script>
try {
  katex.render("$escapedLatex", document.getElementById('render'),
    { displayMode:true, throwOnError:false, output:'html' });
} catch(e) {
  document.getElementById('render').textContent = e.message;
}
Android.onRendered();
</script>
</body></html>"""

        // Measure + layout the WebView at the fixed width.
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(FIXED_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        webView.layout(0, 0, FIXED_WIDTH, webView.measuredHeight.coerceAtLeast(100))

        // Load the HTML (base64 data URL to avoid file:// permission issues).
        val encoded = android.util.Base64.encodeToString(html.toByteArray(), android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
        webView.loadData("data:text/html;base64,$encoded", "text/html", "utf-8")
    }

    private fun captureBitmap(webView: WebView, onDone: (Bitmap?) -> Unit) {
        try {
            // Re-measure to get the actual content height after KaTeX rendered.
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(FIXED_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val w = FIXED_WIDTH
            val h = webView.measuredHeight.coerceIn(100, 4096)
            webView.layout(0, 0, w, h)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            webView.draw(canvas)
            onDone(bmp)
        } catch (e: Exception) {
            onDone(null)
        }
    }
}
