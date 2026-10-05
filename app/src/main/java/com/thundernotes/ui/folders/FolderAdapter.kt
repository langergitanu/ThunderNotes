package com.thundernotes.ui.folders

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.thundernotes.data.entity.FolderEntity
import com.thundernotes.databinding.ItemFolderCardBinding
import com.thundernotes.ui.common.FolderColors

/**
 * RecyclerView adapter for folder cards.
 *
 * S2-1 fix (BigPickle): now reads [FolderEntity.color] and tints the folder
 * icon from the [FolderColors] palette — previously the icon was hardcoded blue
 * and the stored color was ignored.
 *
 * S2-3 fix (BigPickle): [compact] mode sets a fixed 180dp width for horizontal
 * strips (e.g., the Home page's "Recent Folders" scroll). Without this, the
 * card's `match_parent` width in a horizontal LinearLayoutManager makes each
 * card fill the entire viewport, hiding subsequent cards.
 */
class FolderAdapter(
    private val onItemClick: (FolderEntity) -> Unit,
    private val onMoreClick: (FolderEntity, View) -> Unit,
    private val compact: Boolean = false,
) : ListAdapter<FolderEntity, FolderAdapter.FolderViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val binding = ItemFolderCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        // S2-3: for horizontal strips, set a fixed width so all cards are visible.
        if (compact) {
            val width = (180 * parent.context.resources.displayMetrics.density).toInt()
            binding.root.layoutParams = RecyclerView.LayoutParams(
                width, RecyclerView.LayoutParams.WRAP_CONTENT
            )
        }
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
            // S2-1: tint the folder icon from the stored color (was hardcoded blue).
            val colorRes = FolderColors.COLORS.getOrElse(folder.color) { FolderColors.COLORS[0] }
            binding.folderIcon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(itemView.context, colorRes)
            )
            // Bookmark indicator.
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
