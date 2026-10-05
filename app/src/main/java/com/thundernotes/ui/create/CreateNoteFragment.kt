package com.thundernotes.ui.create

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.thundernotes.data.entity.PageOrientation
import com.thundernotes.data.entity.PageType
import com.thundernotes.R
import com.thundernotes.data.repository.RepositoryModule
import com.thundernotes.databinding.FragmentCreateNoteBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Create Note modal (spec §6.6 CreateNotePage).
 *
 * A BottomSheetDialogFragment showing 4 page-type × orientation cards (Blank
 * Portrait, Blank Landscape, Lined Portrait, Lined Landscape) + an Infinite
 * Canvas card (disabled — shows a "coming soon" popup per spec).
 *
 * When the user clicks one of the 4 cards, calls [NotesRepository.createNote]
 * with the chosen pageType + orientation, then dismisses. The new note's
 * display name is "Untitled <MMM d HH:mm>" for now (Phase 5b will add a
 * name input + cover selection).
 */
class CreateNoteFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentCreateNoteBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCreateNoteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.closeButton.setOnClickListener { dismiss() }

        // 4 page-type × orientation cards.
        binding.cardBlankPortrait.setOnClickListener {
            createNote(PageType.BLANK, PageOrientation.PORTRAIT)
        }
        binding.cardBlankLandscape.setOnClickListener {
            createNote(PageType.BLANK, PageOrientation.LANDSCAPE)
        }
        binding.cardLinedPortrait.setOnClickListener {
            createNote(PageType.LINED, PageOrientation.PORTRAIT)
        }
        binding.cardLinedLandscape.setOnClickListener {
            createNote(PageType.LINED, PageOrientation.LANDSCAPE)
        }

        // Infinite Canvas — not available yet (spec §6.6: show popup).
        binding.cardInfiniteCanvas.setOnClickListener {
            android.widget.Toast.makeText(
                requireContext(),
                getString(R.string.infinite_canvas_unavailable),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun createNote(pageType: PageType, orientation: PageOrientation) {
        // Disable all cards so the user can't double-tap + create two notes.
        setCardsEnabled(false)

        lifecycleScope.launch {
            try {
                val displayName = "Untitled " + SimpleDateFormat(
                    "MMM d HH:mm", Locale.getDefault()
                ).format(Date())
                RepositoryModule.notes.createNote(
                    displayName = displayName,
                    pageType = pageType,
                    orientation = orientation
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

    private fun setCardsEnabled(enabled: Boolean) {
        binding.cardBlankPortrait.isEnabled = enabled
        binding.cardBlankLandscape.isEnabled = enabled
        binding.cardLinedPortrait.isEnabled = enabled
        binding.cardLinedLandscape.isEnabled = enabled
        binding.cardInfiniteCanvas.isEnabled = enabled
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
