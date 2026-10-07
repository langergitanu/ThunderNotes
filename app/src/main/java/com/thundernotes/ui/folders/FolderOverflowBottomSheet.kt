package com.thundernotes.ui.folders

import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.thundernotes.R
import com.thundernotes.data.entity.FolderEntity
import com.thundernotes.data.repository.RepositoryModule
import com.thundernotes.databinding.BottomSheetFolderOverflowBinding
import com.thundernotes.ui.common.FolderColors
import kotlinx.coroutines.launch

/**
 * Per-folder overflow bottom sheet (spec §6.3 FoldersLibraryPage three-dot menu).
 *
 * 5 actions per the mock: **Rename · Change Color · Bookmark · Information · Trash**.
 * Mirrors [com.thundernotes.ui.notes.NoteOverflowBottomSheet]; all actions wire
 * to the existing (Samsung-Notes-mirrored) [com.thundernotes.data.repository.FoldersRepository]
 * + [com.thundernotes.data.repository.BookmarksRepository].
 */
class FolderOverflowBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetFolderOverflowBinding? = null
    private val binding get() = _binding!!
    private var folder: FolderEntity? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetFolderOverflowBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val folderId = requireArguments().getString(ARG_FOLDER_ID).orEmpty()
        binding.btnClose.setOnClickListener { dismiss() }

        viewLifecycleOwner.lifecycleScope.launch {
            folder = RepositoryModule.folders.getFolder(folderId)
            folder?.let { bindHeader(it) } ?: run { dismissAllowingStateLoss() }
        }

        binding.rowRename.setOnClickListener { showRename(folder) }
        binding.rowChangeColor.setOnClickListener { showColorPicker(folder) }
        binding.rowBookmark.setOnClickListener { toggleBookmark() }
        binding.rowInfo.setOnClickListener { showInfo(folderId) }
        binding.rowTrash.setOnClickListener { trashFolder(folderId) }
    }

    private fun bindHeader(f: FolderEntity) {
        binding.folderNameTitle.text = f.displayName
        binding.labelBookmark.text = getString(
            if (f.isBookmarked) R.string.overflow_remove_bookmark
            else R.string.overflow_bookmark
        )
    }

    private fun showRename(f: FolderEntity?) {
        val current = f?.displayName.orEmpty()
        val fm = requireActivity().supportFragmentManager
        dismiss()
        // Inline rename dialog (AlertDialog + EditText) → FoldersRepository.renameFolder.
        val input = android.widget.EditText(requireContext()).apply {
            setText(current)
            setSelection(current.length)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            setHint(R.string.rename_hint)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.rename_folder_dialog_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text?.toString().orEmpty().trim()
                if (name.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.rename_empty_blocked, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (f != null && name != current) {
                    lifecycleScope.launch { RepositoryModule.folders.renameFolder(f.folderId, name) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showColorPicker(f: FolderEntity?) {
        if (f == null) return
        val ctx = requireContext()
        val swatchRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(48, 32, 48, 16)
        }
        FolderColors.COLORS.forEachIndexed { idx, colorRes ->
            val swatch = View(ctx).apply {
                val size = 96
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = 24
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(androidx.core.content.ContextCompat.getColor(ctx, colorRes))
                    setStroke(4, if (idx == f.color) 0xFFFFFFFF.toInt() else 0x44888888)
                }
                isClickable = true
                setOnClickListener {
                    lifecycleScope.launch { RepositoryModule.folders.changeFolderColor(f.folderId, idx) }
                    dismiss()  // dismiss the color picker
                }
            }
            swatchRow.addView(swatch)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.change_color_dialog_title)
            .setView(swatchRow)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleBookmark() {
        val f = folder ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            RepositoryModule.bookmarks.toggleFolderBookmark(f.folderId)
            Toast.makeText(requireContext(),
                if (f.isBookmarked) R.string.folder_unbookmarked else R.string.folder_bookmarked,
                Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    private fun showInfo(folderId: String) {
        val fm = requireActivity().supportFragmentManager
        dismiss()
        FolderInfoBottomSheet.newInstance(folderId).show(fm, "folder_info")
    }

    private fun trashFolder(folderId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            RepositoryModule.folders.trashFolder(folderId)
            Toast.makeText(requireContext(), R.string.folder_trashed, Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_FOLDER_ID = "folder_id"
        fun newInstance(folderId: String): FolderOverflowBottomSheet =
            FolderOverflowBottomSheet().apply {
                arguments = Bundle().apply { putString(ARG_FOLDER_ID, folderId) }
            }
    }
}
