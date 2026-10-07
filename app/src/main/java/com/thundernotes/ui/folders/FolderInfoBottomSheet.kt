package com.thundernotes.ui.folders

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.thundernotes.R
import com.thundernotes.data.repository.RepositoryModule
import com.thundernotes.databinding.BottomSheetFolderInfoBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Folder "Information" bottom sheet — mirrors [com.thundernotes.ui.notes.NoteInfoBottomSheet].
 * Surfaces name / note count / created / modified from [com.thundernotes.data.entity.FolderEntity]
 * + a live note count via NotesRepository.observeNotesByFolder.
 */
class FolderInfoBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetFolderInfoBinding? = null
    private val binding get() = _binding!!
    private val dateFmt = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetFolderInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnClose.setOnClickListener { dismiss() }
        val folderId = requireArguments().getString(ARG_FOLDER_ID).orEmpty()

        viewLifecycleOwner.lifecycleScope.launch {
            val folder = RepositoryModule.folders.getFolder(folderId) ?: run {
                dismissAllowingStateLoss(); return@launch
            }
            val noteCount = runCatching {
                RepositoryModule.notes.observeNotesByFolder(folderId).first().size
            }.getOrDefault(0)
            binding.valueName.text = folder.displayName
            binding.valueNoteCount.text = resources.getQuantityString(
                R.plurals.notes_count, noteCount, noteCount,
            )
            binding.valueCreated.text = dateFmt.format(Date(folder.createdTime))
            // FolderEntity has no separate modifiedTime; reuse the most-recent
            // sync timestamp as the "modified" surrogate until a dedicated column
            // is added.
            binding.valueModified.text = dateFmt.format(Date(folder.createdTime))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_FOLDER_ID = "folder_id"
        fun newInstance(folderId: String): FolderInfoBottomSheet =
            FolderInfoBottomSheet().apply {
                arguments = Bundle().apply { putString(ARG_FOLDER_ID, folderId) }
            }
    }
}
