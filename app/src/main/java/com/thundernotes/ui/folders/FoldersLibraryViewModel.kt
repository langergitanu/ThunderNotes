package com.thundernotes.ui.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thundernotes.data.entity.FolderEntity
import com.thundernotes.data.repository.RepositoryModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel for [FoldersLibraryFragment] — the "All Folders" page (spec §6.3).
 * Title-only search applies (same as NotesLibraryPage).
 */
class FoldersLibraryViewModel : ViewModel() {

    private val foldersRepo = RepositoryModule.folders

    private val searchQuery = MutableStateFlow("")

    val folders: StateFlow<List<FolderEntity>> = combine(
        foldersRepo.observeAllFolders(),
        searchQuery
    ) { allFolders, query ->
        if (query.isBlank()) allFolders
        else allFolders.filter { it.displayName.contains(query, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
    }
}
