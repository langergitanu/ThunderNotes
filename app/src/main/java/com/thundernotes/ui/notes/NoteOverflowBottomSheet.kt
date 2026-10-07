package com.thundernotes.ui.notes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.thundernotes.R
import com.thundernotes.data.entity.NoteEntity
import com.thundernotes.data.repository.RepositoryModule
import com.thundernotes.databinding.BottomSheetNoteOverflowBinding
import kotlinx.coroutines.launch

/**
 * Per-note overflow bottom sheet (spec §6.2.4 three-dot menu).
 *
 * Pattern adapted from Samsung Notes' per-document option menu: a sheet of
 * action rows, one per overflow function. Actions are wired to the app-global
 * repositories (manual DI singletons); deferred actions surface a toast.
 *
 * Shows 7 functions: Rename · Change Cover · Move · Export · Bookmark toggle ·
 * Information · Trash. Of these, **Rename / Bookmark / Information / Trash are
 * live now**; Cover / Move / Export surface "later phase" toasts (engines land
 * with templates / folder-picker / PDF work).
 *
 * Shown from any note-card `onMoreClick`. Pass the noteId via [newInstance].
 */
class NoteOverflowBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetNoteOverflowBinding? = null
    private val binding get() = _binding!!

    private var note: NoteEntity? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetNoteOverflowBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val noteId = requireArguments().getString(ARG_NOTE_ID).orEmpty()

        binding.btnClose.setOnClickListener { dismiss() }

        // Load the note so we can show its name + the correct bookmark toggle state.
        viewLifecycleOwner.lifecycleScope.launch {
            note = RepositoryModule.notes.getNote(noteId)
            note?.let { bindHeader(it) } ?: run {
                // Note was deleted concurrently — nothing to act on.
                dismissAllowingStateLoss()
            }
        }

        binding.rowRename.setOnClickListener { showRename(note) }
        binding.rowCover.setOnClickListener {
            toast(R.string.overflow_cover_pending); dismiss()
        }
        binding.rowMove.setOnClickListener {
            toast(R.string.overflow_move_pending); dismiss()
        }
        binding.rowExport.setOnClickListener {
            toast(R.string.overflow_export_pending); dismiss()
        }
        binding.rowBookmark.setOnClickListener { toggleBookmark() }
        binding.rowInfo.setOnClickListener { showInfo(noteId) }
        binding.rowTrash.setOnClickListener { trashNote(noteId) }
    }

    private fun bindHeader(n: NoteEntity) {
        binding.noteNameTitle.text = n.displayName
        val isBookmarked = n.isBookmarked
        binding.labelBookmark.text = getString(
            if (isBookmarked) R.string.overflow_remove_bookmark
            else R.string.overflow_bookmark
        )
    }

    private fun toggleBookmark() {
        val current = note ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            RepositoryModule.bookmarks.toggleNoteBookmark(current.noteId)
            toast(if (current.isBookmarked) R.string.overflow_unbookmarked
                  else R.string.overflow_bookmarked)
            dismiss()
        }
    }

    private fun trashNote(noteId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            RepositoryModule.notes.trashNote(noteId)
            toast(R.string.overflow_trashed)
            dismiss()
        }
    }

    /** Dismiss the overflow, then surface a Rename dialog with the current name. */
    private fun showRename(n: NoteEntity?) {
        val name = n?.displayName.orEmpty()
        val fm = requireActivity().supportFragmentManager
        dismiss()
        RenameNoteDialog.newInstance(n?.noteId.orEmpty(), name).show(fm, TAG_RENAME)
    }

    /** Dismiss the overflow, then surface the Information sheet. */
    private fun showInfo(noteId: String) {
        val fm = requireActivity().supportFragmentManager
        dismiss()
        NoteInfoBottomSheet.newInstance(noteId).show(fm, TAG_INFO)
    }

    private fun toast(resId: Int) {
        Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_NOTE_ID = "note_id"
        private const val TAG_RENAME = "rename_note"
        private const val TAG_INFO = "note_info"

        fun newInstance(noteId: String): NoteOverflowBottomSheet =
            NoteOverflowBottomSheet().apply {
                arguments = Bundle().apply { putString(ARG_NOTE_ID, noteId) }
            }
    }
}
