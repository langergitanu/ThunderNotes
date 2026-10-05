package com.thundernotes

import android.app.Application
import android.util.Log

/**
 * ThunderNotes application entry point.
 *
 * The app is intentionally a single-Activity host (MainActivity) with the actual
 * screens reachable via Navigation component. This class is the place where we
 * initialize process-level singletons:
 *
 *   - Phase 1 (now)         — log a startup marker so we can confirm the wiring works.
 *   - Phase 2              — initialize the Room database (AppDatabase —
 *                            the app-global DB singleton, see `data/db/AppDatabase.kt`).
 *   - Phase 3              — initialize the InkBrushProvider (loads the brush-family
 *                            definitions from assets/brushes/<brush-id>.brushfamily).
 *                            (NOTE: never write the literal "/*" sequence inside a Kotlin
 *                            KDoc — Kotlin supports NESTED block comments, so the "*/"
 *                            on the next line would only close the inner comment and leave
 *                            the outer comment open to EOF.)
 *   - Phase 5              — initialize the SnipEngine fallback chain
 *                            (Gemini → GLM-4.6V-Flash → PaddleOCR-VL-1.6).
 *   - Phase 5              — start SnipOverlayService if the user has already
 *                            granted SYSTEM_ALERT_WINDOW permission.
 *
 * Singletons are exposed via the [get] accessor so any class can reach the
 * application context without holding a static Activity reference.
 */
class ThunderNotesApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "ThunderNotes v${packageManager.getPackageInfo(packageName, 0).versionName} starting (phase 1 skeleton).")
    }

    companion object {
        private const val TAG = "ThunderNotesApp"

        @Volatile
        private lateinit var instance: ThunderNotesApp

        /** Singleton application context accessor. */
        fun get(): ThunderNotesApp = instance
    }
}
