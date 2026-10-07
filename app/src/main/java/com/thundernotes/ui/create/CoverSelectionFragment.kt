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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.thundernotes.R
import com.thundernotes.data.entity.TemplateEntity
import com.thundernotes.data.repository.RepositoryModule
import com.thundernotes.databinding.FragmentCoverSelectionBinding
import com.thundernotes.databinding.ItemCoverCardBinding
import kotlinx.coroutines.launch

/**
 * Cover Selection page (spec §6.8 CoverSelectionPage).
 *
 * Shows a 4-column grid of the 60 preinstalled engineering-subject cover
 * templates (seeded by [com.thundernotes.data.repository.TemplatesRepository.seedPreinstalledTemplates]
 * from [com.thundernotes.data.seed.TemplatesSeed]). The cover preview is a
 * colored band (category → color) + the template name + category label — no
 * real cover-image asset is shipped yet; that's a later content pack. The
 * metadata is enough to populate the picker with 50+ items on first launch.
 *
 * Tapping a cover selects it for the new note (selection wiring lands with the
 * CreateNote → CoverSelection flow in a later phase; today the picker is a
 * browse + a toast on tap).
 */
class CoverSelectionFragment : Fragment() {

    private var _binding: FragmentCoverSelectionBinding? = null
    private val binding get() = _binding!!

    private val adapter = CoverAdapter { template ->
        android.widget.Toast.makeText(
            requireContext(),
            "Selected \"${template.displayName}\" — applied in a later phase",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
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
            layoutManager = GridLayoutManager(requireContext(), 4)
            adapter = this@CoverSelectionFragment.adapter
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

    // ─── adapter ─────────────────────────────────────────────────────────────

    private class CoverAdapter(
        private val onClick: (TemplateEntity) -> Unit,
    ) : ListAdapter<TemplateEntity, CoverAdapter.CoverVH>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CoverVH {
            val b = ItemCoverCardBinding.inflate(
                LayoutInflater.from(parent.context), parent, false,
            )
            return CoverVH(b)
        }

        override fun onBindViewHolder(holder: CoverVH, position: Int) =
            holder.bind(getItem(position), onClick)

        class CoverVH(private val b: ItemCoverCardBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(t: TemplateEntity, onClick: (TemplateEntity) -> Unit) {
                b.templateName.text = t.displayName
                b.templateCategory.text = t.category
                b.colorBand.setBackgroundColor(colorForCategory(t.category, b))
                b.root.setOnClickListener { onClick(t) }
            }

            /** Map a template category → a distinct cover-band color. */
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
            val DIFF = object : DiffUtil.ItemCallback<TemplateEntity>() {
                override fun areItemsTheSame(a: TemplateEntity, b: TemplateEntity) =
                    a.templateId == b.templateId
                override fun areContentsTheSame(a: TemplateEntity, b: TemplateEntity) = a == b
            }
        }
    }
}
