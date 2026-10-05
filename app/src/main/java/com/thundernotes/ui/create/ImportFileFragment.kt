package com.thundernotes.ui.create

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.thundernotes.R
import com.thundernotes.databinding.FragmentImportFileBinding

/**
 * Import File page (spec §6.9 ImportFilePage).
 *
 * Tells the user to select a .thunder file from local storage. Uses
 * [ActivityResultContracts.OpenDocument] to launch the system file picker.
 * Only .thunder files are accepted; an error message is shown if the user
 * tries to import a PDF or other file types.
 *
 * The actual import (copying the file into the notes directory + inserting a
 * NoteEntity row) will be wired in Phase 5b.
 */
class ImportFileFragment : Fragment() {

    private var _binding: FragmentImportFileBinding? = null
    private val binding get() = _binding!!

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult  // user cancelled
        val mimeType = requireContext().contentResolver.getType(uri)
        val name = uri.lastPathSegment ?: "unknown"
        if (!name.endsWith(".thunder")) {
            android.widget.Toast.makeText(
                requireContext(),
                getString(R.string.err_import_unsupported),
                android.widget.Toast.LENGTH_LONG
            ).show()
            return@registerForActivityResult
        }
        // Phase 5b will copy the file into the notes directory + insert a NoteEntity row.
        android.widget.Toast.makeText(
            requireContext(),
            "Import of \"$name\" — wiring coming in Phase 5b",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentImportFileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.selectFileButton.setOnClickListener {
            // Open the system file picker — only .thunder files.
            filePickerLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
