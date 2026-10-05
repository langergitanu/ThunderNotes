package com.thundernotes

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.thundernotes.databinding.ActivityMainBinding
import com.thundernotes.ui.common.SidebarAdapter
import com.thundernotes.ui.common.SidebarItem

/**
 * Launcher Activity + NavHost host.
 *
 * Tablet-first master-detail layout:
 *   - Persistent left sidebar (280dp, RecyclerView with 7 items: Home, Notes,
 *     Folders, Bookmarks, Trash, Templates, Plugins).
 *   - NavHostFragment on the right that swaps between the 9 library pages.
 *
 * The sidebar is always present on library pages. It will be hidden on Canvas
 * pages (Phase 6) via a separate CanvasActivity (the canvas needs the full
 * screen for the pen tray + page minimap + AI snip overlay).
 *
 * The active sidebar item is tracked via [NavController.addOnDestinationChangedListener]
 * + reflects the current destination. Clicking a sidebar item navigates to
 * that destination.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var sidebarAdapter: SidebarAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up NavController for the NavHostFragment. Look it up through the
        // fragment manager rather than findNavController(R.id.nav_host): the
        // FragmentContainerView's own tag is not where Navigation stores the
        // controller, so the activity-level helper throws
        // "Activity ... does not have a NavController set on ...".
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host) as NavHostFragment
        navController = navHostFragment.navController

        // Set up sidebar RecyclerView + adapter.
        sidebarAdapter = SidebarAdapter { item ->
            item.destinationId?.let { destId ->
                navController.navigate(
                    destId,
                    null,
                    NavOptions.Builder().setLaunchSingleTop(true).build()
                )
            }
        }
        binding.sidebarRecycler.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = sidebarAdapter
            itemAnimator = null  // avoid flicker on active-item updates
        }
        sidebarAdapter.submitList(sidebarItems())

        // Track the active destination → highlight the matching sidebar item.
        navController.addOnDestinationChangedListener { _, destination, _ ->
            sidebarAdapter.setActiveDestination(destination.id)
        }
    }

    /**
     * The 7 sidebar items. Per spec §6.1: Home, Notes, Folders, Bookmarks, Trash,
     * Templates, Plugins. The "Upgrade to Premium" item at the bottom of the
     * sidebar is a static TextView in activity_main.xml (not clickable yet).
     */
    private fun sidebarItems(): List<SidebarItem> = listOf(
        SidebarItem(R.string.sidebar_home, R.drawable.ic_sidebar_home, R.id.thunderHomeFragment),
        SidebarItem(R.string.sidebar_notes, R.drawable.ic_sidebar_notes, R.id.notesLibraryFragment),
        SidebarItem(R.string.sidebar_folders, R.drawable.ic_sidebar_folders, R.id.foldersLibraryFragment),
        SidebarItem(R.string.sidebar_bookmarks, R.drawable.ic_sidebar_bookmarks, R.id.bookmarksFragment),
        SidebarItem(R.string.sidebar_trash, R.drawable.ic_sidebar_trash, R.id.trashFragment),
        SidebarItem(R.string.sidebar_templates, R.drawable.ic_sidebar_templates, R.id.templatesFragment),
        SidebarItem(R.string.sidebar_plugins, R.drawable.ic_sidebar_plugins, R.id.pluginsFragment),
    )
}
