package com.thundernotes.ui.notes

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.thundernotes.data.entity.NoteEntity
import com.thundernotes.databinding.ItemNoteCardBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView adapter for note cards in the library grid.
 *
 * Each card shows: cover preview (placeholder gray rect for now — Phase 6
 * canvas will render a real preview.png), title, date + page count, and a
 * "more" (3-dots) button that opens the 7-function overflow menu
 * (Rename, Change Cover, Move, Export, Bookmark, Information, Trash — per
 * spec §6.2).
 */
class NoteAdapter(
    private val onItemClick: (NoteEntity) -> Unit,
    private val onMoreClick: (NoteEntity, View) -> Unit,
) : ListAdapter<NoteEntity, NoteAdapter.NoteViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val binding = ItemNoteCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return NoteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class NoteViewHolder(
        private val binding: ItemNoteCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

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
