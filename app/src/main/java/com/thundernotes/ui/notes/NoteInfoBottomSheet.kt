package com.thundernotes.ui.notes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.thundernotes.R
import com.thundernotes.data.repository.RepositoryModule
import com.thundernotes.databinding.BottomSheetNoteInfoBinding
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Note "Information" bottom sheet (spec §6.2.4 overflow → Information).
 *
 * Surfaces file name / size / pages / folder / created / modified from the
 * NoteEntity row — pattern adapted from Samsung Notes' document-info popup.
 * Pure size/page/folder formatting is delegated to [NoteInfoFormatter] (unit-tested).
 */
class NoteInfoBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetNoteInfoBinding? = null
    private val binding get() = _binding!!

    private val dateFmt = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetNoteInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnClose.setOnClickListener { dismiss() }
        val noteId = requireArguments().getString(ARG_NOTE_ID).orEmpty()

        viewLifecycleOwner.lifecycleScope.launch {
            val note = RepositoryModule.notes.getNote(noteId) ?: run {
                dismissAllowingStateLoss(); return@launch
            }
            val folderName = note.parentFolderId?.let { fid ->
                RepositoryModule.folders.getFolder(fid)?.displayName
            }
            // File on disk = app-external files dir + the relative filePath.
            val actualSize = runCatching {
                File(requireContext().getExternalFilesDir(null), note.filePath).length()
            }.getOrDefault(note.fileSizeBytes)

            binding.valueName.text = note.displayName
            binding.valueFileSize.text = NoteInfoFormatter.formatFileSize(actualSize)
            binding.valuePages.text = NoteInfoFormatter.formatPageCount(note.pageCount)
            binding.valueFolder.text = NoteInfoFormatter.formatFolderLabel(folderName)
            binding.valueCreated.text = dateFmt.format(Date(note.createdTime))
            binding.valueModified.text = dateFmt.format(Date(note.modifiedTime))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_NOTE_ID = "note_id"
        fun newInstance(noteId: String): NoteInfoBottomSheet =
            NoteInfoBottomSheet().apply {
                arguments = Bundle().apply { putString(ARG_NOTE_ID, noteId) }
            }
    }
}
