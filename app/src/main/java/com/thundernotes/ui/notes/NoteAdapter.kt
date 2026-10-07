package com.thundernotes.ui.notes

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.thundernotes.data.entity.NoteEntity
import com.thundernotes.data.repository.RepositoryModule
import com.thundernotes.databinding.ItemNoteCardBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView adapter for note cards in the library grid.
 *
 * Each card shows: cover preview (placeholder gray rect for now — Phase 6
 * canvas will render a real preview.png), title, date + page count, **the
 * folder the note is in** (spec §6.2.2 — "the note cards must show which
 * folder each note is in"), and a "more" (3-dots) button that opens the
 * 7-function overflow menu (Rename, Change Cover, Move, Export, Bookmark,
 * Information, Trash — per spec §6.2).
 *
 * The folder lookup is async (a DB read per visible card) — done on a
 * short-lived adapter scope, cancelled on onViewRecycled to avoid leaks.
 * Root-level notes (parentFolderId == null) hide the folder label.
 */
class NoteAdapter(
    private val onItemClick: (NoteEntity) -> Unit,
    private val onMoreClick: (NoteEntity, View) -> Unit,
) : ListAdapter<NoteEntity, NoteAdapter.NoteViewHolder>(DIFF) {

    /** Adapter-scoped coroutine for async folder-name lookups. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val binding = ItemNoteCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return NoteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: NoteViewHolder) {
        holder.cancelLookup()
        super.onViewRecycled(holder)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        scope.cancel()
    }

    inner class NoteViewHolder(
        private val binding: ItemNoteCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        /** The current folder-lookup job (cancelled on rebind/recycle). */
        private var lookupJob: kotlinx.coroutines.Job? = null

        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onItemClick(getItem(pos))
            }
            binding.noteMore.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onMoreClick(getItem(pos), binding.noteMore)
            }
        }

        fun bind(note: NoteEntity) {
            binding.noteTitle.text = note.displayName
            binding.noteMetadata.text = formatMetadata(note)
            // Bookmark indicator
            binding.noteBookmarked.visibility =
                if (note.isBookmarked) View.VISIBLE else View.GONE
            // Folder label (spec §6.2.2). Async lookup — cancel any prior job.
            lookupJob?.cancel()
            val folderId = note.parentFolderId
            if (folderId == null) {
                binding.noteFolder.visibility = View.GONE
            } else {
                binding.noteFolder.visibility = View.VISIBLE
                binding.noteFolder.text = "…"  // placeholder while loading
                lookupJob = scope.launch {
                    val name = withContext(Dispatchers.IO) {
                        RepositoryModule.folders.getFolder(folderId)?.displayName
                    }
                    // Guard against the view being rebound to a different note.
                    if (bindingAdapterPosition != RecyclerView.NO_POSITION &&
                        getItem(bindingAdapterPosition).noteId == note.noteId) {
                        if (name != null) {
                            binding.noteFolder.text = "📁 $name"
                            binding.noteFolder.visibility = View.VISIBLE
                        } else {
                            // Folder was deleted; hide the label.
                            binding.noteFolder.visibility = View.GONE
                        }
                    }
                }
            }
        }

        fun cancelLookup() {
            lookupJob?.cancel()
            lookupJob = null
        }

        private fun formatMetadata(note: NoteEntity): String {
            val date = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(note.modifiedTime))
            val pages = if (note.pageCount == 1) "1 page" else "${note.pageCount} pages"
            return "$date · $pages"
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<NoteEntity>() {
            override fun areItemsTheSame(a: NoteEntity, b: NoteEntity) = a.noteId == b.noteId
            override fun areContentsTheSame(a: NoteEntity, b: NoteEntity) = a == b
        }
    }
}
