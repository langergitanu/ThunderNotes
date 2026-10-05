package com.thundernotes.ui.create

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.ViewGroup as ViewGroupCompat
import com.thundernotes.databinding.FragmentCoverSelectionBinding

/**
 * Cover Selection page (spec §6.8 CoverSelectionPage).
 *
 * Shows a grid of cover templates. Phase 12 will populate the Template Library
 * with 50+ engineering-subject covers; for now, shows placeholder cover cards.
 */
class CoverSelectionFragment : Fragment() {

    private var _binding: FragmentCoverSelectionBinding? = null
    private val binding get() = _binding!!

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
            adapter = PlaceholderCoverAdapter()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** Simple adapter that shows 20 placeholder cover cards (gray rectangles). */
    private class PlaceholderCoverAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroupCompat, viewType: Int): RecyclerView.ViewHolder {
            val view = View(parent.context).apply {
                layoutParams = ViewGroupCompat.LayoutParams(
                    ViewGroupCompat.LayoutParams.MATCH_PARENT,
                    200
                )
                setBackgroundResource(android.R.color.darker_gray)
            }
            return object : RecyclerView.ViewHolder(view) {}
        }
        override fun getItemCount() = 20
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
    }
}
