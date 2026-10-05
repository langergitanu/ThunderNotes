package com.thundernotes.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.thundernotes.R
import com.thundernotes.databinding.ItemSidebarBinding

/**
 * One item in the persistent left sidebar (Home, Notes, Folders, Bookmarks, Trash,
 * Templates, Plugins). Each item has an icon + label + an optional count badge.
 *
 * The active item (matching the current NavHost destination) gets a crimson
 * background + white icon/label; inactive items get transparent background +
 * muted icon/label.
 *
 * Per spec §6.1: the sidebar is present on all library pages (Home, Notes,
 * Folders, Bookmarks, Trash, Templates, Plugins). It will be hidden on Canvas
 * pages (Phase 6) via a separate canvas activity.
 */
data class SidebarItem(
    val labelResId: Int,
    val iconResId: Int,
    /** NavGraph destination ID (R.id.thunderHomeFragment etc.). Null for items
     *  that don't navigate (e.g., future "Upgrade to Premium" placeholder). */
    val destinationId: Int? = null,
    /** Optional count badge (e.g., "Notes 12"). Null = no badge. */
    val count: Int? = null
)

/**
 * RecyclerView adapter for the sidebar items. Tracks the active position + updates
 * the background/tint when the NavHost destination changes.
 */
class SidebarAdapter(
    private val onItemClick: (SidebarItem) -> Unit
) : ListAdapter<SidebarItem, SidebarAdapter.SidebarViewHolder>(DIFF) {

    /** Index of the currently-active item (or -1 if none). Set by
     *  [setActiveDestination] when the NavHost destination changes. */
    private var activeIndex: Int = -1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SidebarViewHolder {
        val binding = ItemSidebarBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return SidebarViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SidebarViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, position == activeIndex)
    }

    fun setActiveDestination(destinationId: Int) {
        val newIndex = currentList.indexOfFirst { it.destinationId == destinationId }
        if (newIndex == activeIndex) return
        val oldIndex = activeIndex
        activeIndex = newIndex
        if (oldIndex >= 0) notifyItemChanged(oldIndex)
        if (newIndex >= 0) notifyItemChanged(newIndex)
    }

    inner class SidebarViewHolder(
        private val binding: ItemSidebarBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(position))
                }
            }
        }

        fun bind(item: SidebarItem, isActive: Boolean) {
            binding.sidebarItemLabel.setText(item.labelResId)
            binding.sidebarItemIcon.setImageResource(item.iconResId)

            // Active: crimson background + white icon/label.
            // Inactive: transparent background + muted icon/label.
            val (bgRes, tintRes) = if (isActive) {
                R.drawable.bg_sidebar_item_active to R.color.white
            } else {
                android.R.color.transparent to R.color.text_muted
            }
            binding.root.setBackgroundResource(bgRes)
            binding.sidebarItemIcon.imageTintList =
                android.content.res.ColorStateList.valueOf(
                    binding.root.context.getColor(tintRes)
                )
            binding.sidebarItemLabel.setTextColor(
                binding.root.context.getColor(tintRes)
            )

            // Optional count badge.
            if (item.count != null && item.count > 0) {
                binding.sidebarItemBadge.text = item.count.toString()
                binding.sidebarItemBadge.visibility = android.view.View.VISIBLE
            } else {
                binding.sidebarItemBadge.visibility = android.view.View.GONE
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SidebarItem>() {
            override fun areItemsTheSame(a: SidebarItem, b: SidebarItem) =
                a.destinationId == b.destinationId
            override fun areContentsTheSame(a: SidebarItem, b: SidebarItem) =
                a.labelResId == b.labelResId && a.iconResId == b.iconResId &&
                a.destinationId == b.destinationId && a.count == b.count
        }
    }
}
