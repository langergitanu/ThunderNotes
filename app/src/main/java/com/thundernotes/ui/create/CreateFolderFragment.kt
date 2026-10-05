package com.thundernotes.ui.create

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.thundernotes.R
import com.thundernotes.data.repository.RepositoryModule
import com.thundernotes.databinding.FragmentCreateFolderBinding
import kotlinx.coroutines.launch

/**
 * Create Folder modal (spec §6.7 CreateFolderPage).
 *
 * A BottomSheetDialogFragment with a folder-name text input + 6 color swatches
 * (from the brand palette) + a Create button. When Create is clicked, calls
 * [FoldersRepository.createFolder] with the name + selected color, then dismisses.
 */
class CreateFolderFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentCreateFolderBinding? = null
    private val binding get() = _binding!!

    /** Index into FOLDER_COLORS — the currently-selected swatch. */
    private var selectedColorIndex = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCreateFolderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.closeButton.setOnClickListener { dismiss() }
        setupColorSwatches()

        binding.createButton.setOnClickListener {
            val name = binding.folderNameInput.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                binding.folderNameLayout.error = getString(R.string.hint_folder_name)
                return@setOnClickListener
            }
            binding.folderNameLayout.error = null
            binding.createButton.isEnabled = false

            lifecycleScope.launch {
                try {
                    RepositoryModule.folders.createFolder(
                        displayName = name,
                        color = selectedColorIndex
                    )
                } catch (e: Throwable) {
                    android.widget.Toast.makeText(
                        requireContext(),
                        getString(R.string.err_save_failed),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } finally {
                    dismiss()
                }
            }
        }
    }

    private fun setupColorSwatches() {
        val swatches = listOf(
            binding.swatch0, binding.swatch1, binding.swatch2,
            binding.swatch3, binding.swatch4, binding.swatch5
        )
        swatches.forEachIndexed { index, swatch ->
            swatch.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), FOLDER_COLORS[index])
            )
            swatch.setOnClickListener {
                selectedColorIndex = index
                updateSwatchSelection(swatches, index)
            }
        }
        updateSwatchSelection(swatches, selectedColorIndex)
    }

    private fun updateSwatchSelection(swatches: List<ImageView>, selectedIndex: Int) {
        swatches.forEachIndexed { i, swatch ->
            // Selected: white ring (stroke). Unselected: transparent.
            swatch.background = ContextCompat.getDrawable(
                requireContext(),
                if (i == selectedIndex) R.drawable.bg_swatch_selected
                else R.drawable.bg_swatch_unselected
            )
            swatch.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), FOLDER_COLORS[i])
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** Brand palette colors for folder swatches. */
        private val FOLDER_COLORS = intArrayOf(
            R.color.thunder_primary,        // crimson
            R.color.thunder_secondary,     // emerald
            R.color.thunder_tertiary,      // amber
            R.color.thunder_accent_blue,   // blue
            R.color.thunder_primary_dark,  // dark crimson
            R.color.thunder_secondary_dark  // dark emerald
        )
    }
}
