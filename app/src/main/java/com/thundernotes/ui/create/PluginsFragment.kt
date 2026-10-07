package com.thundernotes.ui.create

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.thundernotes.R
import com.thundernotes.canvas.inject.ClipboardItem
import com.thundernotes.canvas.inject.ThunderClipboard
import com.thundernotes.data.entity.FontFamily
import com.thundernotes.databinding.FragmentPluginsBinding
import com.thundernotes.plugins.TranslatorPlugin
import kotlinx.coroutines.launch

/**
 * Plugins page (spec §6.1.2 sidebar "Plugins" + §2.4 "Flexibility — PLUGINS").
 *
 * **Phase 9i:** ships the first real plugin — the [TranslatorPlugin]
 * (any language → English, Gemini/GLM fallback chain). The UI: an input
 * EditText + a Translate button + an output TextView + a "Copy to canvas"
 * button that pushes the translation to [ThunderClipboard] as a Patrick Hand
 * textbox (the glowing paste button path — same as a TEXT snip).
 *
 * More plugins can be added behind the same surface without touching the
 * canvas code (spec §2.4: "easily modified to incorporate new functions").
 */
class PluginsFragment : Fragment() {

    private var _binding: FragmentPluginsBinding? = null
    private val binding get() = _binding!!
    private val translator = TranslatorPlugin()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPluginsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.translatorTranslateButton.setOnClickListener {
            val input = binding.translatorInput.text?.toString().orEmpty().trim()
            if (input.isEmpty()) {
                Toast.makeText(requireContext(),
                    R.string.plugin_translator_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.translatorTranslateButton.isEnabled = false
            binding.translatorOutput.text = getString(R.string.plugin_translator_loading)
            viewLifecycleOwner.lifecycleScope.launch {
                val result = translator.translateToEnglish(input)
                binding.translatorTranslateButton.isEnabled = true
                if (result != null) {
                    binding.translatorOutput.text = result
                    binding.translatorCopyButton.isEnabled = true
                } else {
                    // Distinguish "no key" from "call failed" via SnipAccounts.
                    val hasGemini = com.thundernotes.snip.SnipAccounts.getByProvider("gemini").isNotEmpty()
                    val hasGlm = com.thundernotes.snip.SnipAccounts.getByProvider("glm").isNotEmpty()
                    val msg = if (!hasGemini && !hasGlm)
                        R.string.plugin_translator_no_key else R.string.plugin_translator_failed
                    binding.translatorOutput.text = getString(R.string.plugin_translator_output_hint)
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                }
            }
        }
        // Copy the translation to ThunderClipboard as a Patrick Hand textbox
        // (the glowing paste button → user pastes onto the canvas).
        binding.translatorCopyButton.setOnClickListener {
            val text = binding.translatorOutput.text?.toString().orEmpty().trim()
            if (text.isEmpty() || text == getString(R.string.plugin_translator_output_hint) ||
                text == getString(R.string.plugin_translator_loading)) {
                Toast.makeText(requireContext(),
                    R.string.plugin_translator_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ThunderClipboard.put(ClipboardItem.TextBox(
                text = text,
                fontFamily = FontFamily.PATRICK_HAND,  // §6.10 default for snipped content
                bold = false, italic = false, underline = 0,
                x = 50f, y = 50f,
                bbox = floatArrayOf(0f, 0f, 400f, 200f),
            ))
            Toast.makeText(requireContext(),
                R.string.plugin_translator_copy_done, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
