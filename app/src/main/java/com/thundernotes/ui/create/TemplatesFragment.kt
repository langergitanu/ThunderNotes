package com.thundernotes.ui.create

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.thundernotes.databinding.FragmentTemplatesBinding

/**
 * Templates page (spec §6.1 sidebar "Templates" + §6.8 CoverSelectionPage).
 *
 * Placeholder for now — Phase 12 will populate the Template Library with 50+
 * engineering-subject covers. This page shows a "coming soon" message.
 */
class TemplatesFragment : Fragment() {

    private var _binding: FragmentTemplatesBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTemplatesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
