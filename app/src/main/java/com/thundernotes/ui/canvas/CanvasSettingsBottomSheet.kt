package com.thundernotes.ui.canvas

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.thundernotes.R
import com.thundernotes.databinding.BottomSheetCanvasSettingsBinding
import kotlinx.coroutines.launch

/**
 * Canvas Settings popup (spec §7.5 + canvasSettingsPage) — the floating settings
 * opened from the editor's overflow button. Currently surfaces:
 *  - **Constant scaling** (§7.5): ON → lasso Enlarge/Reduce keeps stroke
 *    thickness; OFF → thickness scales with the resize. Drives the lasso
 *    `StrokeTransforms.scale(..., scaleBrushSize = !constantScaling)` flag.
 *  - **Palm rejection** (§6.1.9, on by default) — a local toggle (the actual
 *    palm-rejection is the AndroidX Ink stylus-only path; this is the setting).
 *
 * Shares the activity's [NoteEditorViewModel] via [activityViewModels] so toggles
 * flow into [EditorUiState.constantScaling] → the lasso menu reads it live.
 */
class CanvasSettingsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetCanvasSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: NoteEditorViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetCanvasSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnClose.setOnClickListener { dismiss() }

        // Push the current state into the switches + observe for external changes.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.switchConstantScaling.isChecked = state.constantScaling
                    // Palm rejection is always-on by default (spec §6.1.9); a future
                    // VM field can persist the toggle. For now reflect the default.
                    binding.switchPalmRejection.isChecked = true
                }
            }
        }

        binding.switchConstantScaling.setOnCheckedChangeListener { _, checked ->
            viewModel.setConstantScaling(checked)
        }
        binding.switchPalmRejection.setOnCheckedChangeListener { _, checked ->
            viewModel.setPalmRejection(checked)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
