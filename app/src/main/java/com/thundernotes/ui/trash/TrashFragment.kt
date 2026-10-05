package com.thundernotes.ui.trash

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
import com.thundernotes.data.repository.RepositoryModule
import com.thundernotes.data.repository.TrashRepository
import com.thundernotes.data.repository.TrashedItems
import com.thundernotes.databinding.FragmentTrashBinding
import com.thundernotes.ui.folders.FolderAdapter
import com.thundernotes.ui.notes.NoteAdapter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Trash page (spec §6.5 TrashPage).
 *
 * Shows all trashed notes + folders. Each can be restored or permanently deleted.
 * The top bar has "Restore All" + "Empty Trash" buttons.
 */
class TrashFragment : Fragment() {

    private var _binding: FragmentTrashBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TrashViewModel by viewModels()

    private lateinit var noteAdapter: NoteAdapter
    private lateinit var folderAdapter: FolderAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTrashBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        noteAdapter = NoteAdapter(
            onItemClick = { /* no-op — trashed notes don't open */ },
            onMoreClick = { note, _ ->
                // Simple restore for now — Phase 5b will add a per-item popup.
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.restoreNote(note.noteId)
                }
            }
        )
        binding.trashedNotesGrid.apply {
            layoutManager = GridLayoutManager(requireContext(), 3)
            adapter = noteAdapter
        }

        folderAdapter = FolderAdapter(
            onItemClick = { /* no-op */ },
            onMoreClick = { folder, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.restoreFolder(folder.folderId)
                }
            }
        )
        binding.trashedFoldersList.adapter = folderAdapter

        binding.restoreAllButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.restoreAll()
            }
        }
        binding.emptyTrashButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.emptyTrash()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.trashed.collect { items ->
                    noteAdapter.submitList(items.notes)
                    folderAdapter.submitList(items.folders)
                    val isEmpty = items.isEmpty
                    binding.emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
                    binding.notesSection.visibility =
                        if (items.notes.isNotEmpty()) View.VISIBLE else View.GONE
                    binding.foldersSection.visibility =
                        if (items.folders.isNotEmpty()) View.VISIBLE else View.GONE
                    binding.restoreAllButton.isEnabled = !isEmpty
                    binding.emptyTrashButton.isEnabled = !isEmpty
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/** ViewModel for [TrashFragment]. */
class TrashViewModel : ViewModel() {
    private val trashRepo: TrashRepository = RepositoryModule.trash

    val trashed = trashRepo.observeTrashed()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            TrashedItems(emptyList(), emptyList()))

    suspend fun restoreNote(noteId: String) = trashRepo.restoreNote(noteId)
    suspend fun restoreFolder(folderId: String) = trashRepo.restoreFolder(folderId)
    suspend fun restoreAll() = trashRepo.restoreAll()
    suspend fun emptyTrash() = trashRepo.emptyTrash()
}
