package com.thundernotes.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.thundernotes.data.entity.FolderEntity
import com.thundernotes.data.entity.NoteEntity
import com.thundernotes.data.repository.RepositoryModule
import com.thundernotes.databinding.FragmentThunderHomeBinding
import com.thundernotes.ui.canvas.CanvasActivity
import com.thundernotes.ui.create.CreateFolderFragment
import com.thundernotes.ui.create.CreateNoteFragment
import com.thundernotes.ui.folders.FolderAdapter
import java.io.File
import com.thundernotes.ui.folders.FolderOverflowBottomSheet
import com.thundernotes.ui.notes.NoteAdapter
import com.thundernotes.ui.notes.NoteOverflowBottomSheet
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
                CanvasActivity.launch(requireContext(), note.noteId)
            },
            onMoreClick = { note, anchor ->
                NoteOverflowBottomSheet.newInstance(note.noteId)
                    .show(childFragmentManager, "note_overflow")
            }
        )
        binding.recentNotesRecycler.apply {
            layoutManager = GridLayoutManager(requireContext(), 3)
            adapter = noteAdapter
        }
    }

    private fun setupFolderScroll() {
        // S2-3 fix: compact=true sets a fixed 180dp width for the horizontal strip.
        folderAdapter = FolderAdapter(
            onItemClick = { folder ->
                android.widget.Toast.makeText(
                    requireContext(),
                    "Opening folder \"${folder.displayName}\" — filtered view coming in Phase 5b",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            },
            onMoreClick = { folder, anchor ->
                FolderOverflowBottomSheet.newInstance(folder.folderId)
                    .show(childFragmentManager, "folder_overflow")
            },
            compact = true  // S2-3: fixed-width cards for horizontal strip
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

        // Import button (spec §6.9 ImportFilePage — pick a .thunder file,
        // copy it into the notes dir, create a NoteEntity, open the canvas).
        binding.importButton.setOnClickListener {
            importPicker.launch(arrayOf("*/*"))
        }
    }

    /** SAF file picker for `.thunder` import (spec §6.9). */
    private val importPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        // Validate the extension (spec §6.9: "error if the user tries to
        // import a PDF or other file types").
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: ""
        if (!name.endsWith(".thunder", ignoreCase = true)) {
            android.widget.Toast.makeText(
                requireContext(),
                "Only .thunder files can be imported",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return@registerForActivityResult
        }
        viewLifecycleOwner.lifecycleScope.launch {
            // Uri → temp file (the repo stays Android-agnostic + testable).
            val temp = File.createTempFile("import", ".thunder", requireContext().cacheDir)
            try {
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { input.copyTo(it) }
                } ?: throw java.io.IOException("Cannot open the picked file")
                val display = name.removeSuffix(".thunder")
                val noteId = RepositoryModule.notes.importThunderFile(temp, display)
                CanvasActivity.launch(requireContext(), noteId)
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    requireContext(),
                    "Import failed: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } finally {
                temp.delete()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
