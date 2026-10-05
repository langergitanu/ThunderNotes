package com.thundernotes.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.thundernotes.data.entity.FolderEntity
import com.thundernotes.data.entity.NoteEntity
import com.thundernotes.databinding.FragmentThunderHomeBinding
import com.thundernotes.ui.create.CreateFolderFragment
import com.thundernotes.ui.create.CreateNoteFragment
import com.thundernotes.ui.folders.FolderAdapter
import com.thundernotes.ui.notes.NoteAdapter
import kotlinx.coroutines.launch

/**
 * Dashboard page (spec §6.1 ThunderHomePage):
 * - Top bar with title, search, import, create-note button.
 * - "Recent Folders" horizontal scroll.
 * - "Recent Notes" grid (3 columns).
 * - Empty state with Create Note + Create Folder buttons (visible when both lists empty).
 * - Floating create dock at the bottom (Create Folder FAB + Create Note FAB).
 *
 * This is the start destination of the NavGraph. Observes [ThunderHomeViewModel]
 * for the notes + folders lists. The create buttons open the CreateNoteFragment /
 * CreateFolderFragment modal BottomSheetDialogFragments.
 */
class ThunderHomeFragment : Fragment() {

    private var _binding: FragmentThunderHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ThunderHomeViewModel by viewModels()

    private lateinit var noteAdapter: NoteAdapter
    private lateinit var folderAdapter: FolderAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentThunderHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupNoteGrid()
        setupFolderScroll()
        observeViewModel()
        setupCreateButtons()
    }

    private fun setupNoteGrid() {
        noteAdapter = NoteAdapter(
            onItemClick = { note ->
                // Phase 6 will open the note in the CanvasActivity.
                // For now, just show a toast.
                android.widget.Toast.makeText(
                    requireContext(),
                    "Opening \"${note.displayName}\" — canvas coming in Phase 6",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            },
            onMoreClick = { note, anchor ->
                // Phase 5b will add the 7-function overflow menu (Rename, Change Cover,
                // Move, Export, Bookmark, Information, Trash). For now, just show a toast.
                android.widget.Toast.makeText(
                    requireContext(),
                    "Overflow menu coming in Phase 5b",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        )
        binding.recentNotesRecycler.apply {
            layoutManager = GridLayoutManager(requireContext(), 3)
            adapter = noteAdapter
        }
    }

    private fun setupFolderScroll() {
        folderAdapter = FolderAdapter(
            onItemClick = { folder ->
                // Navigate to the folder's contents (NotesLibraryPage filtered by parent).
                // For now, just show a toast.
                android.widget.Toast.makeText(
                    requireContext(),
                    "Opening folder \"${folder.displayName}\" — filtered view coming in Phase 5b",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            },
            onMoreClick = { folder, anchor ->
                android.widget.Toast.makeText(
                    requireContext(),
                    "Folder overflow coming in Phase 5b",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        )
        binding.recentFoldersRecycler.apply {
            layoutManager = LinearLayoutManager(
                requireContext(), LinearLayoutManager.HORIZONTAL, false
            )
            adapter = folderAdapter
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.recentNotes.collect { notes ->
                        noteAdapter.submitList(notes)
                        updateVisibility(notes = notes, folders = viewModel.recentFolders.value)
                    }
                }
                launch {
                    viewModel.recentFolders.collect { folders ->
                        folderAdapter.submitList(folders)
                        updateVisibility(notes = viewModel.recentNotes.value, folders = folders)
                    }
                }
            }
        }
    }

    private fun updateVisibility(notes: List<NoteEntity>, folders: List<FolderEntity>) {
        val isEmpty = notes.isEmpty() && folders.isEmpty()
        binding.emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.foldersSection.visibility = if (folders.isNotEmpty()) View.VISIBLE else View.GONE
        binding.notesSection.visibility = if (notes.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun setupCreateButtons() {
        // Top bar Create Note button + empty-state Create Note + FAB Create Note
        // all open the same CreateNoteFragment modal.
        val openCreateNote = View.OnClickListener {
            CreateNoteFragment().show(parentFragmentManager, "create_note")
        }
        binding.createNoteButton.setOnClickListener(openCreateNote)
        binding.emptyCreateNote.setOnClickListener(openCreateNote)
        binding.createNoteFab.setOnClickListener(openCreateNote)

        // Create Folder FAB + empty-state Create Folder
        val openCreateFolder = View.OnClickListener {
            CreateFolderFragment().show(parentFragmentManager, "create_folder")
        }
        binding.createFolderFab.setOnClickListener(openCreateFolder)
        binding.emptyCreateFolder.setOnClickListener(openCreateFolder)

        // Import button (Phase 5b will wire the file picker)
        binding.importButton.setOnClickListener {
            android.widget.Toast.makeText(
                requireContext(),
                "File picker coming in Phase 5b",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
