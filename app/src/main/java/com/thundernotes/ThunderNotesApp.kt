package com.thundernotes

import android.app.Application
import android.util.Log
import com.thundernotes.data.db.AppDatabase
import com.thundernotes.data.repository.RepositoryModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ThunderNotes application entry point.
 *
 * The app is intentionally a single-Activity host (MainActivity) with the actual
 * screens reachable via Navigation component. This class is the place where we
 * initialize process-level singletons:
 *
 *   - Phase 1 (done)       — log a startup marker so we can confirm the wiring works.
 *   - Phase 2 (done)       — initialize the Room database (AppDatabase — the app-global
 *                            DB singleton, see `data/db/AppDatabase.kt`). Eagerly
 *                            constructed on a background IO coroutine in [onCreate]
 *                            so it's ready by the time the UI lands (Phase 5+) and so
 *                            BigPickle can verify `/data/data/com.thundernotes/databases/`
 *                            exists on first launch.
 *   - Phase 3 (done)       — .thunder format (protobuf strokes + ZIP container + manifest).
 *   - Phase 4 (done)       — repositories (RepositoryModule manual DI singleton).
 *   - Phase 5 (next)       — UI for 9 library pages (NavHost + fragments + viewmodels).
 *   - Phase 6 (later)      — AndroidX Ink canvas MVP (InkBrushProvider loads the
 *                            brush-family definitions from assets/brushes/<brush-id>.brushfamily).
 *                            (NOTE: never write the literal "/*" sequence inside a Kotlin
 *                            KDoc — Kotlin supports NESTED block comments, so the "*/"
 *                            on the next line would only close the inner comment and leave
 *                            the outer comment open to EOF. See Sync 1 bug A1.)
 *   - Phase 9 (later)      — SnipEngine fallback chain
 *                            (Gemini → GLM-4.6V-Flash → PaddleOCR-VL-1.6).
 *   - Phase 9 (later)      — start SnipOverlayService if the user has already
 *                            granted SYSTEM_ALERT_WINDOW permission.
 *
 * Singletons are exposed via the [get] accessor so any class can reach the
 * application context without holding a static Activity reference.
 *
 * **App-level coroutine scope:** [appScope] uses [SupervisorJob] + [Dispatchers.IO]
 * so background work (DB init, template seeding, future brush loading) can outlive
 * any single Activity but is cancelled when the app process dies. [SupervisorJob]
 * ensures a failure in one child doesn't cancel siblings.
 */
class ThunderNotesApp : Application() {

    /**
     * App-level coroutine scope for background work that should outlive any
     * single Activity but not the app process. Used for DB init, template
     * seeding, and (in Phase 6+) brush-family loading.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Throwable) {
            "?"
        }
        Log.d(TAG, "ThunderNotes v$versionName starting.")

        // Eagerly construct the app-global DB on a background thread. This
        // addresses BigPickle's Open Issue 1 (BigPickle.txt Sync 2 round 3):
        // the Room layer was compiled-but-never-constructed — RepositoryModule
        // was referenced only in its own KDoc, and `/data/data/com.thundernotes/databases/`
        // never existed on device. Now AppDatabase is forced into existence on
        // first launch, so Phase 5 UI can rely on it being ready.
        //
        // We also seed preinstalled templates if the DB is empty (Phase 12
        // will populate `assets/covers/`; for now seedPreinstalledTemplates
        // is a no-op).
        appScope.launch {
            try {
                val db = AppDatabase.get(this@ThunderNotesApp)
                Log.d(TAG, "AppDatabase constructed: ${db.openHelper.databaseName}.")
                RepositoryModule.templates.seedPreinstalledTemplates(this@ThunderNotesApp)
                Log.d(TAG, "Background init complete.")
            } catch (e: Throwable) {
                Log.e(TAG, "Background init failed — DB will be lazily constructed on first UI access.", e)
                // Don't rethrow — the app should still launch even if DB init
                // fails. The first UI access via RepositoryModule will retry.
            }
        }
    }

    companion object {
        private const val TAG = "ThunderNotesApp"

        @Volatile
        private lateinit var instance: ThunderNotesApp

        /** Singleton application context accessor. */
        fun get(): ThunderNotesApp = instance
    }
}
