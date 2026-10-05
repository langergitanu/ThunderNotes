package com.thundernotes.ui.folders

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
import com.thundernotes.databinding.FragmentFoldersLibraryBinding
import com.thundernotes.ui.create.CreateFolderFragment
import kotlinx.coroutines.launch

/**
 * All Folders page (spec §6.3 FoldersLibraryPage).
 * Grid of all active folders + title-only search + Create Folder button.
 */
class FoldersLibraryFragment : Fragment() {

    private var _binding: FragmentFoldersLibraryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FoldersLibraryViewModel by viewModels()

    private lateinit var folderAdapter: FolderAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFoldersLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        folderAdapter = FolderAdapter(
            onItemClick = { folder ->
                android.widget.Toast.makeText(
                    requireContext(),
                    "Opening folder \"${folder.displayName}\" — filtered view coming in Phase 5b",
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
        binding.foldersGrid.apply {
            layoutManager = GridLayoutManager(requireContext(), 3)
            adapter = folderAdapter
        }

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.onSearchQueryChanged(newText.orEmpty())
                return true
            }
        })

        binding.createFolderButton.setOnClickListener {
            CreateFolderFragment().show(parentFragmentManager, "create_folder")
        }

        observeViewModel()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.folders.collect { folders ->
                    folderAdapter.submitList(folders)
                    binding.emptyState.visibility =
                        if (folders.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
