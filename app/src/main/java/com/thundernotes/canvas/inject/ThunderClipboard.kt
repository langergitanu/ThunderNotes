package com.thundernotes.canvas.inject

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ThunderNotes' process-scoped, **single-item** clipboard (spec §6.10 Row 2 +
 * §7.3): the paste button glows as long as the clipboard holds exactly one
 * copied item; it stops glowing once that item is pasted, or when the app is
 * restarted (process-scoped, not persisted).
 *
 * This is the **live-injection ingress point**: an external tool (AI snip
 * service, plugin, ADB) pushes a [ClipboardItem] here via
 * [com.thundernotes.inject.InjectContentReceiver] while a note is open; the
 * paste button glows; the user clicks paste → [InkInjector] drops the item
 * onto the canvas. This is exactly what failed in Notein (Notein README §3:
 * "Notein has no external API to receive new strokes") — we make it
 * first-class because we own the app (thunder-format-proposal Part B2).
 *
 * Pure + unit-testable (uses kotlinx.coroutines.flow only). Process-scoped
 * singleton — cleared on process death (no persistence, per spec).
 */
object ThunderClipboard {

    private val _item = MutableStateFlow<ClipboardItem?>(null)
    /** The single held item, or null when empty. Observe to glow the paste button. */
    val item: StateFlow<ClipboardItem?> = _item.asStateFlow()

    /** True iff the clipboard holds an item (paste button should glow). */
    val hasItem: Boolean get() = _item.value != null

    /** Put a single item. Overwrites any existing item (spec: "exactly one"). */
    fun put(item: ClipboardItem) {
        _item.value = item
    }

    /** Take + clear the item (the paste action — stops the glow). Returns null if empty. */
    fun take(): ClipboardItem? {
        val v = _item.value
        _item.value = null
        return v
    }

    /** Clear without pasting (e.g. app reset / explicit clear). */
    fun clear() {
        _item.value = null
    }
}
