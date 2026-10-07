package com.thundernotes.ui.notes

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.thundernotes.R
import com.thundernotes.data.repository.RepositoryModule
import com.thundernotes.databinding.DialogRenameNoteBinding
import kotlinx.coroutines.launch

/**
 * Rename-note dialog (spec §6.2.4 overflow → Rename).
 *
 * AlertDialog wrapping [DialogRenameNoteBinding] (a TextInputLayout + EditText).
 * On OK → [NotesRepository.renameNote]; rejects empty names with a toast.
 */
class RenameNoteDialog : DialogFragment() {

    private var _binding: DialogRenameNoteBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogRenameNoteBinding.inflate(LayoutInflater.from(requireContext()))
        val noteId = requireArguments().getString(ARG_NOTE_ID).orEmpty()
        val currentName = requireArguments().getString(ARG_CURRENT_NAME).orEmpty()
        binding.nameInput.setText(currentName)
        binding.nameInput.requestFocus()
        binding.nameInput.setSelection(currentName.length)

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.rename_dialog_title)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = binding.nameInput.text?.toString().orEmpty().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.rename_empty_blocked, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newName == currentName) { dismiss(); return@setPositiveButton }
                lifecycleScope.launch {
                    RepositoryModule.notes.renameNote(noteId, newName)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_NOTE_ID = "note_id"
        private const val ARG_CURRENT_NAME = "current_name"
        fun newInstance(noteId: String, currentName: String): RenameNoteDialog =
            RenameNoteDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_NOTE_ID, noteId)
                    putString(ARG_CURRENT_NAME, currentName)
                }
            }
    }
}
