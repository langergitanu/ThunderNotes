package com.thundernotes.ui.bookmarks

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.GridLayoutManager
import com.thundernotes.data.repository.BookmarksRepository
import com.thundernotes.data.repository.RepositoryModule
import com.thundernotes.databinding.FragmentBookmarksBinding
import com.thundernotes.ui.folders.FolderAdapter
import com.thundernotes.ui.notes.NoteAdapter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Bookmarks page (spec §6.4 BookmarksPage).
 *
 * Shows all bookmarked notes + folders in a combined list. Uses the existing
 * [NoteAdapter] + [FolderAdapter] in two RecyclerViews (notes grid + folders
 * list stacked vertically).
 */
class BookmarksFragment : Fragment() {

    private var _binding: FragmentBookmarksBinding? = null
    private val binding get() = _binding!!
    private val viewModel: BookmarksViewModel by viewModels()

    private lateinit var noteAdapter: NoteAdapter
    private lateinit var folderAdapter: FolderAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBookmarksBinding.inflate(inflater, container, false)
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
            onMoreClick = { _, _ ->
                android.widget.Toast.makeText(
                    requireContext(),
                    "Overflow menu coming in Phase 5b",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        )
        binding.bookmarkedNotesGrid.apply {
            layoutManager = GridLayoutManager(requireContext(), 3)
            adapter = noteAdapter
        }

        folderAdapter = FolderAdapter(
            onItemClick = { folder ->
                android.widget.Toast.makeText(
                    requireContext(),
                    "Opening folder \"${folder.displayName}\"",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            },
            onMoreClick = { _, _ ->
                android.widget.Toast.makeText(
                    requireContext(),
                    "Folder overflow coming in Phase 5b",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        )
        binding.bookmarkedFoldersList.adapter = folderAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.bookmarked.collect { items ->
                    noteAdapter.submitList(items.notes)
                    folderAdapter.submitList(items.folders)
                    val isEmpty = items.isEmpty
                    binding.emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
                    binding.notesSection.visibility =
                        if (items.notes.isNotEmpty()) View.VISIBLE else View.GONE
                    binding.foldersSection.visibility =
                        if (items.folders.isNotEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/** ViewModel for [BookmarksFragment]. */
class BookmarksViewModel : ViewModel() {
    private val bookmarksRepo: BookmarksRepository = RepositoryModule.bookmarks

    val bookmarked = bookmarksRepo.observeBookmarked()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            com.thundernotes.data.repository.BookmarkedItems(emptyList(), emptyList()))
}
