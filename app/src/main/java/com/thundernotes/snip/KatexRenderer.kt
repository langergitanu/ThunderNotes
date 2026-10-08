package com.thundernotes.snip

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Renders LaTeX to a high-DPI bitmap via KaTeX in an offscreen WebView
 * (spec §7.8 / thunder-format-proposal Part D: "KaTeX render (offscreen
 * WebView, OFFLINE assets, black-on-white, high-DPI)").
 *
 * How it works, step by step:
 *  1. Creates a WebView and **attaches it to the host activity's window**
 *     (the decor view) as an INVISIBLE child — Chromium only runs page
 *     layout reliably for views attached to a window; a never-attached
 *     WebView can render with 0×0 layout on some devices.
 *  2. Loads a minimal HTML page via [WebView.loadDataWithBaseURL] with the
 *     base URL `file:///android_asset/katex/` — the bundled KaTeX CSS/JS
 *     are then referenced with **relative** URLs. (Loading them with
 *     absolute `file://` URLs from a `data:` page is blocked by Chromium
 *     — "Not allowed to load local resource" — which would make `katex`
 *     undefined and silently trace the error message into strokes.)
 *  3. The page's inline script runs `katex.render(...)`, then calls the
 *     `Android.onRendered()` [JavascriptInterface] bridge back into Kotlin.
 *  4. After a short settle delay (KaTeX webfonts load asynchronously), the
 *     WebView is re-measured at its real content height and drawn into a
 *     [Bitmap] (pure black text on a white background — always, regardless
 *     of the canvas theme, per spec §7.8.1).
 *  5. The temp WebView is detached + destroyed — each render uses a fresh
 *     WebView so a failed render never poisons the next one.
 *
 * Android-only (WebView). The pure centerline tracer ([CenterlineTracer])
 * is unit-tested; this renderer is build-verifiable.
 */
object KatexRenderer {

    /** Render width in px — wide enough that glyphs trace cleanly (≈1px stroke). */
    private const val FIXED_WIDTH = 1080

    /** Milliseconds to wait after onRendered before capturing — lets webfonts settle. */
    private const val SETTLE_DELAY_MS = 350L

    /** Safety-net timeout: if the JS bridge never fires, capture whatever is there. */
    private const val TIMEOUT_MS = 5000L

    /**
     * Render [latex] to a black-on-white bitmap. Returns null on failure.
     * Thread-safe: hops to the main thread internally (WebView is main-only).
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
        val appCtx = context.applicationContext
        val webView = WebView(appCtx)
        webView.settings.javaScriptEnabled = true
        // KaTeX ships in assets/katex/ — the base URL below makes relative
        // refs resolve there (offline, no network needed).
        webView.settings.allowFileAccess = true
        webView.layoutParams = FrameLayout.LayoutParams(
            FIXED_WIDTH, ViewGroup.LayoutParams.WRAP_CONTENT,
        )

        // Runs at most once: the JS bridge (fast path) and the timeout safety
        // net can race — this flag guarantees a single result + single
        // teardown + exactly one onDone call.
        val finished = AtomicBoolean(false)
        fun complete(bmp: Bitmap?) {
            if (finished.compareAndSet(false, true)) {
                runCatching {
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    webView.destroy()
                }
                onDone(bmp)
            }
        }

        // Bridge: the page's JS calls Android.onRendered() once katex.render
        // has run (the page catches render errors itself, so the bridge
        // ALWAYS fires — with either the equation or an error message).
        class RenderInterface {
            @JavascriptInterface
            fun onRendered() {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!finished.get()) {
                        captureBitmap(webView) { bmp -> complete(bmp) }
                    }
                }, SETTLE_DELAY_MS)
            }
        }
        webView.addJavascriptInterface(RenderInterface(), "Android")
        webView.webViewClient = object : WebViewClient() {}  // defaults — no URL hijacking

        // Escape the LaTeX for safe embedding inside a JS string literal.
        val escapedLatex = latex
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("</", "<\\/")   // never allow "</script>" to close the tag

        // Minimal page: white background, black text, 32px font, display mode.
        // CSS + JS are RELATIVE to the base URL (assets/katex/) — see class doc.
        val html = """<!DOCTYPE html>
<html><head>
<link rel="stylesheet" href="katex.min.css">
<script src="katex.min.js"></script>
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
} catch (e) {
  try { document.getElementById('render').textContent = String(e.message || e); } catch (e2) {}
}
Android.onRendered();
</script>
</body></html>"""

        // Attach INVISIBLE to the host window so Chromium lays the page out
        // (never-attached WebViews can stall with no layout pass).
        val host: ViewGroup? = (context as? android.app.Activity)
            ?.window?.decorView as? ViewGroup
        if (host != null) {
            webView.visibility = View.INVISIBLE
            host.addView(webView)
        } else {
            // No window to attach to (e.g. a Service context) — fall back to
            // manual measure/layout, which works on most devices.
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(FIXED_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            webView.layout(0, 0, FIXED_WIDTH, webView.measuredHeight.coerceAtLeast(100))
        }

        // Load with the asset base URL → relative CSS/JS/font refs resolve
        // offline. (loadData + absolute file:// refs is blocked by Chromium.)
        webView.loadDataWithBaseURL(
            "file:///android_asset/katex/", html, "text/html", "utf-8", null,
        )

        // Safety net: if the JS bridge never fires (renderer crash, asset
        // problem), don't hang forever — capture whatever is there + finish.
        Handler(Looper.getMainLooper()).postDelayed({
            if (!finished.get()) {
                captureBitmap(webView) { bmp -> complete(bmp) }
            }
        }, TIMEOUT_MS)
    }

    /**
     * Draw the (laid-out) WebView into a new [Bitmap] at its real content
     * height. Re-measures first so multi-line equations aren't clipped.
     * Any stray non-opaque pixel is forced to white so the tracer's
     * binarizer sees a clean background (§7.8.1 "always black-on-white").
     */
    private fun captureBitmap(webView: WebView, onDone: (Bitmap?) -> Unit) {
        try {
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
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            var swept = false
            for (i in px.indices) {
                if (px[i] ushr 24 != 0xFF) { px[i] = 0xFFFFFFFF.toInt(); swept = true }
            }
            if (swept) bmp.setPixels(px, 0, w, 0, 0, w, h)
            onDone(bmp)
        } catch (e: Exception) {
            onDone(null)
        }
    }
}
