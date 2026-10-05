package com.thundernotes.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thundernotes.data.entity.NoteEntity
import com.thundernotes.data.repository.RepositoryModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel for [NotesLibraryFragment] — the "All Notes" page (spec §6.2).
 *
 * Observes all active notes from the app-global DB. The search field filters
 * by title (per spec: "the search field searches titles only").
 */
class NotesLibraryViewModel : ViewModel() {

    private val notesRepo = RepositoryModule.notes

    /** Current search query (empty = show all). */
    private val searchQuery = MutableStateFlow("")

    /** All active (non-trashed) notes, filtered by the search query. */
    val notes: StateFlow<List<NoteEntity>> = combine(
        notesRepo.observeAllNotes(),
        searchQuery
    ) { allNotes, query ->
        if (query.isBlank()) allNotes
        else allNotes.filter { it.displayName.contains(query, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
    }
}
