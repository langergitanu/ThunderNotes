package com.thundernotes.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thundernotes.data.entity.FolderEntity
import com.thundernotes.data.entity.NoteEntity
import com.thundernotes.data.repository.RepositoryModule
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel for [ThunderHomeFragment] — the dashboard page.
 *
 * Observes the most recent notes + folders from the app-global DB. The dashboard
 * shows a horizontal "Recent Folders" scroll + a grid of "Recent Notes". When
 * both lists are empty, the fragment shows an empty state with Create Note +
 * Create Folder buttons.
 */
class ThunderHomeViewModel : ViewModel() {

    private val notesRepo = RepositoryModule.notes
    private val foldersRepo = RepositoryModule.folders

    /** Most recent notes (sorted by modifiedTime DESC — the DAO does this). */
    val recentNotes: StateFlow<List<NoteEntity>> = notesRepo.observeAllNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** All folders (sorted by displayName ASC — the DAO does this). */
    val recentFolders: StateFlow<List<FolderEntity>> = foldersRepo.observeAllFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
