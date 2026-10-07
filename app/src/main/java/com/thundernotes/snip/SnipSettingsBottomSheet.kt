package com.thundernotes.snip

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.materialswitch.MaterialSwitch
import com.thundernotes.R
import kotlinx.coroutines.launch

/**
 * Snip Settings popup (spec §7.3.6 — "the user can add multi-account keys +
 * disable engines + download the offline model"). Launched from the Canvas
 * Settings sheet's "Snip Settings" row.
 *
 * Three sections:
 *  1. **Gemini accounts** — add/remove Gemini API keys (multi-account,
 *     round-robined by [SnipAccounts.nextAccount]).
 *  2. **GLM accounts** — same for GLM-4.6V-Flash.
 *  3. **Engine toggles** — disable Gemini / GLM / PaddleOCR individually (an
 *     engine is skipped even if it has a key, per [SnipSettings.disableEngine]).
 *  4. **Offline model** — the PaddleOCR-VL-1.6 download (a stub for now — the
 *     model is 1.6GB + downloaded on-device; this button shows the state).
 *
 * Keys persist to SharedPreferences via [SnipAccountsStore] (installed on app
 * startup) so they survive restart. The engine toggles + offline-model state
 * also persist via a small [SnipToggleStore].
 */
class SnipSettingsBottomSheet : BottomSheetDialogFragment() {

    private val settings = SnipSettings.shared  // shared with the FallbackSnipEngine
    private lateinit var root: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = inflater.context
        root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 48)
        }
        // Title
        root.addView(TextView(ctx).apply {
            text = getString(R.string.snip_settings_title)
            textSize = 18f
            setPadding(0, 0, 0, 16)
        })
        // Gemini section
        root.addView(sectionLabel(ctx, R.string.snip_settings_gemini_section))
        root.addView(accountsList(ctx, "gemini"))
        root.addView(addKeyButton(ctx, "gemini"))
        // GLM section
        root.addView(sectionLabel(ctx, R.string.snip_settings_glm_section))
        root.addView(accountsList(ctx, "glm"))
        root.addView(addKeyButton(ctx, "glm"))
        // Engine toggles
        root.addView(sectionLabel(ctx, R.string.snip_settings_engine_toggles))
        root.addView(engineToggle(ctx, R.string.snip_settings_disable_gemini,
            "Gemini 3 Flash"))
        root.addView(engineToggle(ctx, R.string.snip_settings_disable_glm,
            "GLM-4.6V-Flash"))
        root.addView(engineToggle(ctx, R.string.snip_settings_disable_paddleocr,
            "PaddleOCR-VL-1.6 (offline)"))
        // Offline model download (stub — shows state).
        root.addView(sectionLabel(ctx, R.string.snip_settings_offline_model))
        root.addView(offlineModelRow(ctx))
        // Close
        root.addView(Button(ctx).apply {
            text = getString(android.R.string.ok)
            setOnClickListener { dismiss() }
        })
        // Observe account changes → rebuild the lists live.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                SnipAccounts.accounts.collect { rebuildAccountLists() }
            }
        }
        return root
    }

    private fun sectionLabel(ctx: android.content.Context, resId: Int): TextView =
        TextView(ctx).apply {
            text = getString(resId)
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#888888"))
            setPadding(0, 16, 0, 8)
        }

    private fun accountsList(ctx: android.content.Context, provider: String): LinearLayout {
        val ll = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            tag = "accounts_$provider"
        }
        return ll
    }

    private fun addKeyButton(ctx: android.content.Context, provider: String): Button =
        Button(ctx).apply {
            text = getString(R.string.snip_settings_add_key)
            setOnClickListener { showAddKeyDialog(ctx, provider) }
        }

    private fun showAddKeyDialog(ctx: android.content.Context, provider: String) {
        val keyInput = EditText(ctx).apply {
            hint = getString(R.string.snip_settings_key_hint)
            inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(48, 24, 48, 24)
        }
        val labelInput = EditText(ctx).apply {
            hint = getString(R.string.snip_settings_label_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(48, 24, 48, 24)
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(keyInput); addView(labelInput)
            setPadding(8, 8, 8, 8)
        }
        AlertDialog.Builder(ctx)
            .setTitle(if (provider == "gemini") R.string.snip_settings_gemini_section
                else R.string.snip_settings_glm_section)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val key = keyInput.text?.toString().orEmpty().trim()
                val label = labelInput.text?.toString().orEmpty().ifBlank { provider }
                if (key.isNotEmpty()) {
                    SnipAccounts.add(provider, key, label)
                    Toast.makeText(ctx, R.string.snip_settings_added,
                        Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun engineToggle(ctx: android.content.Context, labelRes: Int, engineName: String): LinearLayout {
        val switch = MaterialSwitch(ctx).apply {
            isChecked = !settings.isEngineEnabled(engineName)  // inverted: checked = disabled
            setOnCheckedChangeListener { _, checked ->
                if (checked) settings.disableEngine(engineName)
                else settings.enableEngine(engineName)
                // Persist the toggle (simple SharedPreferences).
                SnipToggleStore.setDisabled(engineName, checked)
            }
        }
        // Restore persisted toggle state.
        if (SnipToggleStore.isDisabled(engineName)) {
            settings.disableEngine(engineName)
            switch.isChecked = true
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(TextView(ctx).apply {
                text = getString(labelRes)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                textSize = 14f
            })
            addView(switch)
        }
    }

    private var offlineDownloaded = false  // stub state (the real download is deferred)

    private fun offlineModelRow(ctx: android.content.Context): LinearLayout {
        val status = TextView(ctx).apply {
            text = if (offlineDownloaded) getString(R.string.snip_settings_offline_ready)
                else getString(R.string.snip_settings_offline_not_downloaded)
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#888888"))
            tag = "offline_status"
        }
        val btn = Button(ctx).apply {
            text = getString(R.string.snip_settings_offline_download)
            setOnClickListener {
                if (offlineDownloaded) return@setOnClickListener
                text = getString(R.string.snip_settings_offline_downloading)
                // The real download (PaddleOCR-VL-1.6, 1.6GB) is deferred —
                // the PaddleOCRSnipEngine stub returns "model not downloaded".
                // For now, show the downloading state + leave it to the user
                // to confirm. A future phase wires the actual model fetch.
                Toast.makeText(ctx, R.string.snip_settings_offline_downloading,
                    Toast.LENGTH_LONG).show()
            }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(btn)
            addView(status)
        }
    }

    /** Rebuild the Gemini + GLM account lists from the current SnipAccounts. */
    private fun rebuildAccountLists() {
        for (provider in listOf("gemini", "glm")) {
            val ll = root.findViewWithTag<LinearLayout>("accounts_$provider") ?: continue
            ll.removeAllViews()
            val accts = SnipAccounts.getByProvider(provider)
            if (accts.isEmpty()) {
                ll.addView(TextView(ll.context).apply {
                    text = getString(R.string.snip_settings_no_keys)
                    textSize = 12f
                    setTextColor(android.graphics.Color.parseColor("#888888"))
                })
            }
            for (a in accts) {
                val row = LinearLayout(ll.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                row.addView(TextView(ll.context).apply {
                    text = "${a.label}  •  ${a.apiKey.take(8)}…"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    textSize = 13f
                })
                row.addView(Button(ll.context).apply {
                    text = "✕"
                    setOnClickListener {
                        SnipAccounts.remove(a.id)
                        Toast.makeText(context, R.string.snip_settings_removed,
                            Toast.LENGTH_SHORT).show()
                    }
                })
                ll.addView(row)
            }
        }
    }
}

/** Tiny SharedPreferences store for the per-engine disable toggles. */
object SnipToggleStore {
    private const val PREFS = "thunder_snip_toggles"
    private const val PREFIX = "disabled_"
    private var prefs: android.content.SharedPreferences? = null
    fun init(ctx: android.content.Context) {
        if (prefs == null) prefs = ctx.applicationContext
            .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    }
    fun isDisabled(engineName: String): Boolean = prefs?.getBoolean(PREFIX + engineName, false) ?: false
    fun setDisabled(engineName: String, disabled: Boolean) {
        prefs?.edit()?.putBoolean(PREFIX + engineName, disabled)?.apply()
    }
}
