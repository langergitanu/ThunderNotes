package com.thundernotes.ui.trash

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.thundernotes.R
import com.thundernotes.data.repository.RepositoryModule
import com.thundernotes.databinding.BottomSheetTrashItemBinding
import kotlinx.coroutines.launch

/**
 * Per-item Trash bottom sheet (spec §6.5: each trashed item can be Restored or
 * permanently Deleted).
 *
 * Pattern adapted from Samsung Notes' recycle-bin option menu
 * (`OptionMenuRecycleBinPresenter` → Restore / Delete). Wired to
 * [com.thundernotes.data.repository.TrashRepository].
 *
 * Handles BOTH notes and folders via the [Kind] arg.
 */
class TrashItemBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetTrashItemBinding? = null
    private val binding get() = _binding!!

    enum class Kind { NOTE, FOLDER }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetTrashItemBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        val kind = Kind.valueOf(args.getString(ARG_KIND).orEmpty())
        val id = args.getString(ARG_ID).orEmpty()
        val name = args.getString(ARG_NAME).orEmpty()

        binding.itemNameTitle.text = name
        binding.btnClose.setOnClickListener { dismiss() }

        binding.rowRestore.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching {
                    when (kind) {
                        Kind.NOTE -> RepositoryModule.trash.restoreNote(id)
                        Kind.FOLDER -> RepositoryModule.trash.restoreFolder(id)
                    }
                }.onSuccess {
                    toast(R.string.trash_restored)
                }.onFailure {
                    toast(R.string.trash_restore_failed)
                }
                dismiss()
            }
        }

        binding.rowDeletePermanently.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching {
                    when (kind) {
                        Kind.NOTE -> RepositoryModule.trash.deleteNotePermanently(id)
                        Kind.FOLDER -> RepositoryModule.trash.deleteFolderPermanently(id)
                    }
                }.onSuccess {
                    toast(R.string.trash_deleted)
                }.onFailure {
                    toast(R.string.trash_delete_failed)
                }
                dismiss()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun toast(resId: Int) {
        Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val ARG_KIND = "kind"
        private const val ARG_ID = "id"
        private const val ARG_NAME = "name"

        fun forNote(noteId: String, name: String): TrashItemBottomSheet =
            new(Kind.NOTE, noteId, name)

        fun forFolder(folderId: String, name: String): TrashItemBottomSheet =
            new(Kind.FOLDER, folderId, name)

        private fun new(kind: Kind, id: String, name: String): TrashItemBottomSheet =
            TrashItemBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_KIND, kind.name)
                    putString(ARG_ID, id)
                    putString(ARG_NAME, name)
                }
            }
    }
}
