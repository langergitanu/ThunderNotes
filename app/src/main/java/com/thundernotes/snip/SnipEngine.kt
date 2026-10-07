package com.thundernotes.snip

/**
 * A pluggable snip-recognition engine (spec §7.3.6: "All engines sit behind a
 * pluggable `SnipEngine` interface, and the chain is tried in the order above
 * until one returns a result").
 *
 * Implementations:
 *  - [GeminiSnipEngine] — online, Gemini 3 Flash (BYO key, multi-account).
 *  - [GLMSnipEngine] — online, GLM-4.6V (BYO key, multi-account).
 *  - [PaddleOCRSnipEngine] — offline, PaddleOCR-VL-1.6 (model downloaded on-device).
 *  - [FallbackSnipEngine] — tries a list of engines in order until one succeeds.
 *
 * The engine receives the preprocessed image (binarized, line-detected, cropped,
 * upscaled per [SnipPreprocessor]) + the [SnipType] (which determines the prompt)
 * and returns a [SnipResult] (Text/LaTeX/Code/Strokes).
 */
interface SnipEngine {
    /** The display name (for the settings UI — "Gemini 3 Flash", "GLM-4.6V", "PaddleOCR"). */
    val name: String

    /**
     * Whether this engine is **ready to try** (has an API key set / model downloaded).
     * If false, [FallbackSnipEngine] SKIPS it entirely — no network call, no trial.
     * The user can disable an engine even if it's ready (via [SnipSettings]).
     */
    fun isEnabled(): Boolean

    /**
     * Recognise the snip.
     * @param imageBytes the preprocessed image as PNG bytes (binarized, cropped
     *   per-line by [SnipPreprocessor], then the activity converts SnipImage →
     *   Bitmap → PNG bytes).
     * @param type the snip type (determines the prompt: text OCR vs equation OCR vs code).
     * @return a [SnipResult] on success, or an exception on failure (the fallback
     *   chain catches + tries the next engine).
     */
    suspend fun recognize(imageBytes: ByteArray, type: SnipType): Result<SnipResult>
}

/**
 * Multi-account management for the online engines (spec §7.3.6: "support
 * multi-account usage for them"). Holds multiple Gemini + GLM API keys.
 * Pure + unit-testable (StateFlow only). Process-scoped (cleared on restart).
 */
data class SnipAccount(
    val id: String,
    val provider: String,   // "gemini" or "glm"
    val apiKey: String,
    val label: String,       // user-facing label ("Personal", "Work", etc.)
)

object SnipAccounts {

    private val _accounts = kotlinx.coroutines.flow.MutableStateFlow<List<SnipAccount>>(emptyList())
    val accounts: kotlinx.coroutines.flow.StateFlow<List<SnipAccount>> = _accounts

    fun add(provider: String, apiKey: String, label: String): String {
        val id = java.util.UUID.randomUUID().toString()
        val acct = SnipAccount(id, provider, apiKey, label)
        _accounts.value = _accounts.value + acct
        return id
    }

    fun remove(id: String) {
        _accounts.value = _accounts.value.filter { it.id != id }
    }

    fun getByProvider(provider: String): List<SnipAccount> =
        _accounts.value.filter { it.provider == provider }

    /** Round-robin: returns the next account for a provider (rotates). */
    private var rrIndex = 0
    fun nextAccount(provider: String): SnipAccount? {
        val accts = getByProvider(provider)
        if (accts.isEmpty()) return null
        val a = accts[rrIndex % accts.size]
        rrIndex++
        return a
    }

    fun clear() { _accounts.value = emptyList(); rrIndex = 0 }
}

/**
 * The fallback decorator (spec §7.3.6: "the chain is tried in the order above
 * until one returns a result"). Tries [engines] in order; the first non-failure
 * result wins. If all fail, returns the last failure.
 *
 * **Disabled engines are SKIPPED entirely** — no network call, no trial. An
 * engine is tried only if both:
 *   1. `engine.isEnabled()` returns true (has an API key / model downloaded),
 *   2. The user hasn't explicitly disabled it (via [SnipSettings]).
 * So if the user doesn't set a Gemini key, Gemini is skipped → GLM is tried
 * first. If GLM has no key either → PaddleOCR is tried (offline).
 */
class FallbackSnipEngine(
    private val engines: List<SnipEngine>,
    private val settings: SnipSettings = SnipSettings(),
) : SnipEngine {

    override val name: String = "Fallback"

    override fun isEnabled(): Boolean = engines.any { it.isEnabled() && settings.isEngineEnabled(it.name) }

    /** The engines that will actually be tried (enabled + not user-disabled). */
    fun activeEngines(): List<SnipEngine> = engines.filter {
        it.isEnabled() && settings.isEngineEnabled(it.name)
    }

    override suspend fun recognize(imageBytes: ByteArray, type: SnipType): Result<SnipResult> {
        val active = activeEngines()
        if (active.isEmpty()) {
            return Result.failure(RuntimeException("No snip engines enabled — set an API key in Settings → Snip"))
        }
        var lastError: Throwable? = null
        for (engine in active) {
            val result = engine.recognize(imageBytes, type)
            if (result.isSuccess) return result
            lastError = result.exceptionOrNull()
        }
        return Result.failure(lastError ?: RuntimeException("All snip engines failed"))
    }
}

/**
 * Per-engine enable/disable toggles (the user can explicitly disable an engine
 * even if it's ready — e.g., "don't use Gemini, only GLM"). Pure + tested.
 */
class SnipSettings {
    private val disabledEngines = mutableSetOf<String>()

    /** Disable an engine by name (it will be skipped by FallbackSnipEngine). */
    fun disableEngine(name: String) { disabledEngines.add(name) }
    fun enableEngine(name: String) { disabledEngines.remove(name) }
    fun isEngineEnabled(name: String): Boolean = name !in disabledEngines
    fun clear() { disabledEngines.clear() }
}
