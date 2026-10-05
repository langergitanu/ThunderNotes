package com.thundernotes.ui.create

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.thundernotes.databinding.FragmentPluginsBinding

/**
 * Plugins page (spec §6.1 sidebar "Plugins" + §2.4 "Flexibility — PLUGINS").
 *
 * Placeholder for now — the Plugin system is under development. The first plugin
 * (a text translator) is being tested. This page shows a "coming soon" message.
 */
class PluginsFragment : Fragment() {

    private var _binding: FragmentPluginsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPluginsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
