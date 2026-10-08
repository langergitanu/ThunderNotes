package com.thundernotes.ui.notes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.thundernotes.R
import com.thundernotes.data.entity.NoteEntity
import com.thundernotes.data.repository.RepositoryModule
import com.thundernotes.databinding.BottomSheetNoteOverflowBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

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
        binding.rowCover.setOnClickListener { showCoverPicker(note?.noteId.orEmpty()) }
        binding.rowMove.setOnClickListener { showFolderPicker(note?.noteId.orEmpty()) }
        binding.rowExport.setOnClickListener { exportNote(note) }
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

    /** Change Cover: pick from the 60 preinstalled templates → changeCover. */
    private fun showCoverPicker(noteId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val templates = runCatching {
                RepositoryModule.templates.observeAll().first()
            }.getOrDefault(emptyList())
            if (templates.isEmpty()) {
                toast(R.string.overflow_cover_pending); return@launch
            }
            val items = templates.map { it.displayName }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.overflow_change_cover)
                .setItems(items) { _, which ->
                    val t = templates[which]
                    viewLifecycleOwner.lifecycleScope.launch {
                        RepositoryModule.notes.changeCover(noteId, t.templateId)
                    }
                    dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** Move: pick a destination folder (or root) → moveNote. */
    private fun showFolderPicker(noteId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val folders = runCatching {
                RepositoryModule.folders.observeAllFolders().first()
            }.getOrDefault(emptyList())
            val names = arrayOf("Root (no folder)") + folders.map { it.displayName }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.overflow_move)
                .setItems(names) { _, which ->
                    val targetId = if (which == 0) null else folders[which - 1].folderId
                    viewLifecycleOwner.lifecycleScope.launch {
                        RepositoryModule.notes.moveNote(noteId, targetId)
                    }
                    dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** Export: the .thunder file already exists at note.filePath (relative to
     *  getExternalFilesDir). Share it via ACTION_SEND so the user can save to
     *  Downloads / cloud / etc. PDF export needs the Pdfium engine (a
     *  refinement — spec §6.2.5 requires both .thunder + PDF; .thunder ships
     *  first since the format is ready). */
    private fun exportNote(note: NoteEntity?) {
        val n = note ?: run { toast(R.string.overflow_export_pending); return }
        // n.filePath is stored absolute; be robust for a future relative form.
        val file = if (File(n.filePath).isAbsolute) File(n.filePath)
        else File(requireContext().getExternalFilesDir(null), n.filePath)
        if (!file.exists()) {
            toast(R.string.overflow_export_pending)
            dismiss()
            return
        }
        // Share via FileProvider (the .thunder file is a ZIP — mime application/zip).
        val authority = "${requireContext().packageName}.fileprovider"
        val uri = androidx.core.content.FileProvider.getUriForFile(
            requireContext(), authority, file
        )
        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "${n.displayName}.thunder")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(share, "Export ${n.displayName}"))
        dismiss()
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
