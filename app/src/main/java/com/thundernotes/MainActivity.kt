package com.thundernotes

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.thundernotes.databinding.ActivityMainBinding

/**
 * Launcher Activity. Currently hosts a placeholder screen showing the app logo
 * and a phase marker. In phase 2 this becomes a NavHost that routes to the
 * ThunderHomePage (sidebar + dashboard + floating create dock).
 *
 * Config changes are handled in-place (see AndroidManifest: orientation, screen
 * size, keyboard hidden, uiMode, etc.) so the activity is not recreated when
 * the user rotates the tablet — important for an ink-canvas-heavy app where we
 * don't want the canvas view to be destroyed mid-stroke.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }
}
