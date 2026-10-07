package com.thundernotes.inject

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.thundernotes.canvas.inject.ClipboardPayloadCodec
import com.thundernotes.canvas.inject.ThunderClipboard

/**
 * External live-injection ingress (thunder-format-proposal Part B2 — "Expose a
 * documented, permission-protected ingress so an external tool/PC/plugin can
 * push strokes directly into the running app").
 *
 * Receives intent action `com.thundernotes.action.INJECT_CONTENT` carrying a
 * JSON payload ([EXTRA_CLIPBOARD_JSON]) describing a stroke group or textbox.
 * Decodes it via [ClipboardPayloadCodec] + drops it into [ThunderClipboard] —
 * the canvas paste button then glows + the user pastes via the glowing button
 * (spec §7.3: external snip output → clipboard → paste). This is exactly what
 * failed in Notein (Notein README §3: "Notein has no external API to receive
 * new strokes") — we make it first-class because we own the app.
 *
 * Guarded by the signature/`normal` permission `com.thundernotes.permission.INJECT`
 * (declared in the manifest) so only authorized callers can inject. ADB can
 * always inject on debug builds for automated testing:
 *
 * ```
 * adb shell am broadcast \
 *   -a com.thundernotes.action.INJECT_CONTENT \
 *   -n com.thundernotes/.inject.InjectContentReceiver \
 *   --es clipboard_json '{"type":"stroke_group","bbox":[0,0,10,10],"strokes":[{"blob_b64":"..."}]}'
 * ```
 */
class InjectContentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INJECT_CONTENT) return
        val json = intent.getStringExtra(EXTRA_CLIPBOARD_JSON) ?: run {
            Log.w(TAG, "INJECT_CONTENT missing extra $EXTRA_CLIPBOARD_JSON")
            return
        }
        val item = ClipboardPayloadCodec.decode(json) ?: run {
            Log.w(TAG, "INJECT_CONTENT payload failed to decode")
            return
        }
        ThunderClipboard.put(item)
        Log.d(TAG, "Injected ${item::class.simpleName} into ThunderClipboard (paste button will glow).")
    }

    companion object {
        const val ACTION_INJECT_CONTENT = "com.thundernotes.action.INJECT_CONTENT"
        const val EXTRA_CLIPBOARD_JSON = "clipboard_json"
        private const val TAG = "InjectContentReceiver"
    }
}
