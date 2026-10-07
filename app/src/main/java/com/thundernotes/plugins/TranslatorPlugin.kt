package com.thundernotes.plugins

import com.thundernotes.snip.SnipAccounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The text-translator plugin (spec §6.1.2 + §2.4 "Flexibility — PLUGINS": the
 * spec requires "one very simple plugin — a text translator (any language →
 * English) — for testing").
 *
 * Reuses the snip-engine chain's API keys + OkHttp infrastructure (spec §7.3.6
 * — Gemini primary, GLM fallback). Text-only: a `generateContent` call with a
 * "translate to English" prompt + the user's input. Falls back from Gemini →
 * GLM → returns null if neither key is set.
 *
 * **Why this is a plugin:** the spec wants the app to be flexible enough to
 * support plugins in the future (e.g., "snipping chemistry reactions"). This
 * plugin is the proof-of-concept: a self-contained feature that lives behind
 * the Plugins sidebar + calls an external service. More plugins can be added
 * behind the same [Plugin] interface without touching the canvas code.
 *
 * Build-verifiable (not unit-testable without network — but the pure account
 * resolution + prompt building are testable on the JVM via SnipAccounts).
 */
class TranslatorPlugin(
    private val client: OkHttpClient = defaultClient,
) {

    /** The plugin's user-facing name (spec §6.1.2). */
    val name: String = "Text Translator"

    /**
     * Translate [input] (any language) → English. Returns the translated
     * text, or null if no API key is set / the call failed.
     *
     * The caller (PluginsFragment) handles the UI state (loading, error,
     * success). This method is `suspend` + runs on Dispatchers.IO.
     */
    suspend fun translateToEnglish(input: String): String? = withContext(Dispatchers.IO) {
        if (input.isBlank()) return@withContext null
        // Try Gemini first, then GLM (spec §7.3.6 fallback order).
        val gemini = SnipAccounts.nextAccount("gemini")
        if (gemini != null) {
            return@withContext tryGemini(gemini.apiKey, input)
        }
        val glm = SnipAccounts.nextAccount("glm")
        if (glm != null) {
            return@withContext tryGlm(glm.apiKey, input)
        }
        null  // no key set
    }

    /** Gemini generateContent (text-only). Returns the translated text or null. */
    private fun tryGemini(apiKey: String, input: String): String? {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            "gemini-2.5-flash:generateContent?key=$apiKey"
        val prompt = "Translate the following text to English. Reply with ONLY " +
            "the translation, no explanation:\n\n$input"
        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", prompt))
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 2048)
            })
        }.toString().toRequestBody(JSON)
        val req = Request.Builder().url(url).post(body).build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val json = resp.body?.string() ?: return@use null
                JSONObject(json)
                    .optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                    ?.optJSONObject(0)?.optString("text")?.trim()
            }
        }.getOrNull()
    }

    /** GLM-4.6V-Flash text-only call. Returns the translated text or null. */
    private fun tryGlm(apiKey: String, input: String): String? {
        val url = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
        val prompt = "Translate the following text to English. Reply with ONLY " +
            "the translation, no explanation:\n\n$input"
        val body = JSONObject().apply {
            put("model", "glm-4.6-flash")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("temperature", 0.1)
            put("max_tokens", 2048)
        }.toString().toRequestBody(JSON)
        val req = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body).build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val json = resp.body?.string() ?: return@use null
                JSONObject(json)
                    .optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content")?.trim()
            }
        }.getOrNull()
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
