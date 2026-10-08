package com.thundernotes.ui.create

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.thundernotes.R
import com.thundernotes.data.entity.TemplateEntity
import com.thundernotes.data.repository.RepositoryModule
import com.thundernotes.databinding.FragmentCoverSelectionBinding
import com.thundernotes.databinding.ItemCoverCardBinding
import com.thundernotes.ui.canvas.CanvasActivity
import kotlinx.coroutines.launch

/**
 * Templates page (spec §6.1 sidebar "Templates" + §6.8 CoverSelectionPage).
 *
 * **Phase 9j-3:** shows the 60 preinstalled engineering-subject cover templates
 * (seeded by [com.thundernotes.data.seed.TemplatesSeed] on first launch) in a
 * grid. On tap → create a new note with that cover (the template's category +
 * color band become the note's cover) + open the canvas. The user can also
 * download more templates from a future Template Library (the spec mentions
 * this; for now the 60 seeded covers are the full set).
 *
 * Reuses the [FragmentCoverSelectionBinding] layout (the same grid the canvas's
 * Change Cover uses) so the UX is consistent.
 */
class TemplatesFragment : Fragment() {

    private var _binding: FragmentCoverSelectionBinding? = null
    private val binding get() = _binding!!

    private val adapter = TemplateAdapter { template ->
        // Create a new note with this cover + open the canvas.
        viewLifecycleOwner.lifecycleScope.launch {
            val noteId = RepositoryModule.notes.createNote(
                displayName = template.displayName,
            )
            // Apply the template's category as the cover (via the cover path).
            RepositoryModule.notes.changeCover(noteId, template.filePath)
            CanvasActivity.launch(requireContext(), noteId)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCoverSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.coversGrid.apply {
            layoutManager = GridLayoutManager(requireContext(),
                com.thundernotes.ui.common.ResponsiveSpans.tileSpans(requireContext()))
            adapter = this@TemplatesFragment.adapter
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                RepositoryModule.templates.observeAll().collect { templates ->
                    adapter.submitList(templates)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class TemplateAdapter(
        private val onClick: (TemplateEntity) -> Unit,
    ) : ListAdapter<TemplateEntity, TemplateAdapter.VH>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemCoverCardBinding.inflate(
                LayoutInflater.from(parent.context), parent, false,
            )
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) =
            holder.bind(getItem(position), onClick)

        class VH(private val b: ItemCoverCardBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(t: TemplateEntity, onClick: (TemplateEntity) -> Unit) {
                b.templateName.text = t.displayName
                b.templateCategory.text = t.category
                b.colorBand.setBackgroundColor(colorForCategory(t.category, b))
                b.root.setOnClickListener { onClick(t) }
            }

            private fun colorForCategory(category: String, b: ItemCoverCardBinding): Int {
                val res = when (category.lowercase()) {
                    "mathematics" -> R.color.thunder_primary
                    "physics" -> R.color.thunder_secondary
                    "electrical" -> R.color.thunder_tertiary
                    "cse" -> R.color.thunder_accent_blue
                    "chemistry" -> R.color.folder_color_purple
                    else -> R.color.folder_color_teal
                }
                return ContextCompat.getColor(b.root.context, res)
            }
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<TemplateEntity>() {
                override fun areItemsTheSame(a: TemplateEntity, b: TemplateEntity) =
                    a.templateId == b.templateId
                override fun areContentsTheSame(a: TemplateEntity, b: TemplateEntity) = a == b
            }
        }
    }
}
