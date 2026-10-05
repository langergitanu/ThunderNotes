package com.thundernotes.ui.notes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.thundernotes.databinding.FragmentNotesLibraryBinding
import com.thundernotes.ui.create.CreateNoteFragment
import kotlinx.coroutines.launch

/**
 * All Notes page (spec §6.2 NotesLibraryPage).
 *
 * Shows a grid of all active notes, a search field (title-only), and Import Note
 * (blue) + Create Note (red) buttons in the top bar. Note cards show the title,
 * date, page count, and a 3-dots overflow button (Rename / Change Cover / Move /
 * Export / Bookmark / Information / Trash — spec §6.2 says 7 functions).
 */
class NotesLibraryFragment : Fragment() {

    private var _binding: FragmentNotesLibraryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: NotesLibraryViewModel by viewModels()

    private lateinit var noteAdapter: NoteAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNotesLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        noteAdapter = NoteAdapter(
            onItemClick = { note ->
                android.widget.Toast.makeText(
                    requireContext(),
                    "Opening \"${note.displayName}\" — canvas coming in Phase 6",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            },
            onMoreClick = { _, anchor ->
                android.widget.Toast.makeText(
                    requireContext(),
                    "Overflow menu coming in Phase 5b",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        )
        binding.notesGrid.apply {
            layoutManager = GridLayoutManager(requireContext(), 3)
            adapter = noteAdapter
        }

        // Search field (title-only per spec)
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.onSearchQueryChanged(newText.orEmpty())
                return true
            }
        })

        // Create Note button
        binding.createNoteButton.setOnClickListener {
            CreateNoteFragment().show(parentFragmentManager, "create_note")
        }

        // Import button (Phase 5b will wire the file picker)
        binding.importButton.setOnClickListener {
            android.widget.Toast.makeText(
                requireContext(),
                "File picker coming in Phase 5b",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        observeViewModel()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.notes.collect { notes ->
                    noteAdapter.submitList(notes)
                    binding.emptyState.visibility =
                        if (notes.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
