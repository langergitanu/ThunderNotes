package com.thundernotes.ui.folders

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.thundernotes.data.entity.FolderEntity
import com.thundernotes.databinding.ItemFolderCardBinding

/**
 * RecyclerView adapter for folder cards in the library grid.
 *
 * Each card shows: folder icon, name, and a "more" button for the overflow
 * menu (Rename, Change Cover, Move, Export, Bookmark, Information, Trash).
 */
class FolderAdapter(
    private val onItemClick: (FolderEntity) -> Unit,
    private val onMoreClick: (FolderEntity, View) -> Unit,
) : ListAdapter<FolderEntity, FolderAdapter.FolderViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val binding = ItemFolderCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return FolderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FolderViewHolder(
        private val binding: ItemFolderCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onItemClick(getItem(pos))
            }
            binding.folderMore.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onMoreClick(getItem(pos), binding.folderMore)
            }
        }

        fun bind(folder: FolderEntity) {
            binding.folderName.text = folder.displayName
            binding.folderBookmarked.visibility =
                if (folder.isBookmarked) View.VISIBLE else View.GONE
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FolderEntity>() {
            override fun areItemsTheSame(a: FolderEntity, b: FolderEntity) = a.folderId == b.folderId
            override fun areContentsTheSame(a: FolderEntity, b: FolderEntity) = a == b
        }
    }
}
