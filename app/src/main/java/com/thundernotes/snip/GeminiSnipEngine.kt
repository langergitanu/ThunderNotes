package com.thundernotes.snip

import android.util.Base64
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
 * Gemini Flash snip engine (online, spec §7.3.6 primary). Calls the
 * `generativelanguage.googleapis.com` REST API with the image (base64) + a
 * per-snip-type prompt → returns `SnipResult.Text` / `SnipResult.LaTeX` /
 * `SnipResult.Code`.
 *
 * Multi-account: round-robins through [SnipAccounts] Gemini keys. If no key
 * is set, `isEnabled()` returns false → the fallback chain skips to GLM.
 *
 * HTTP errors are surfaced with the API's own error message (parsed from
 * the JSON error body) instead of a generic "returned empty" — that makes
 * the fallback chain's final failure message actually diagnosable.
 *
 * Uses OkHttp (already a dependency). Build-verifiable (not unit-testable
 * without network — but the pure layers: preprocessor, cleaner, accounts,
 * fallback chain — are all tested).
 */
class GeminiSnipEngine(
    private val client: OkHttpClient = defaultClient,
) : SnipEngine {

    override val name: String = "Gemini 3 Flash"

    override fun isEnabled(): Boolean = SnipAccounts.getByProvider("gemini").isNotEmpty()

    override suspend fun recognize(imageBytes: ByteArray, type: SnipType): Result<SnipResult> =
        withContext(Dispatchers.IO) {
            val account = SnipAccounts.nextAccount("gemini")
                ?: return@withContext Result.failure(NoApiKeyException("gemini"))
            val prompt = promptFor(type)
            val b64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            val body = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", prompt))
                            put(JSONObject().apply {
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", "image/png")
                                    put("data", b64)
                                })
                            })
                        })
                    })
                })
            }.toString()
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=${account.apiKey}")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            runCatching {
                client.newCall(req).execute().use { res ->
                    val raw = res.body?.string().orEmpty()
                    if (!res.isSuccessful) throw RuntimeException(
                        "Gemini HTTP ${res.code}: ${apiError(raw)}")
                    val json = JSONObject(raw)
                    val text = json
                        .optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")
                        ?.optJSONObject(0)?.optString("text").orEmpty().trim()
                    if (text.isEmpty()) throw RuntimeException("Gemini returned empty")
                    mapResult(text, type)
                }
            }
        }

    /**
     * Snip-type-specific instructions. The CODE prompt explicitly demands
     * indentation preservation (spec §7.3.4c: "formatted code with proper
     * tabs & colors") — vision models tend to flatten indentation unless
     * told otherwise. The fence ban keeps the answer raw (a defensive
     * fence-stripper also exists in SnipBottomSheet).
     */
    private fun promptFor(type: SnipType): String = when (type) {
        SnipType.TEXT -> "Extract all text from this image. Return ONLY the plain text, preserving the line breaks. No explanation."
        SnipType.EQUATION -> "Recognise the mathematical equation(s) in this image. Return ONLY the LaTeX code. For multi-line, use \\begin{aligned}...\\end{aligned}. No explanation."
        SnipType.CODE -> "Extract the source code shown in this image. Return ONLY the raw code with NO markdown fences and NO explanation. PRESERVE the original indentation exactly (leading spaces/tabs per line)."
        SnipType.DIAGRAM -> "Describe the diagram as a list of shapes. Return as JSON."
    }

    private fun mapResult(text: String, type: SnipType): SnipResult = when (type) {
        SnipType.EQUATION -> SnipResult.LaTeX(text)
        SnipType.CODE -> SnipResult.Code(text)
        else -> SnipResult.Text(text)
    }

    /** Extract `error.message` from a Gemini error body (or truncate the raw). */
    private fun apiError(raw: String): String = runCatching {
        JSONObject(raw).optJSONObject("error")?.optString("message") ?: raw.take(200)
    }.getOrDefault(raw.take(200))

    companion object {
        /**
         * The model id sent to the v1beta REST API. Spec §7.3.6 names
         * "Gemini 2.5 / 3.0 Flash" — 2.5-flash is the stable GA id that the
         * API accepts today; swap this constant when a 3.x id goes GA.
         */
        const val MODEL = "gemini-2.5-flash"

        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

class NoApiKeyException(provider: String) : RuntimeException("No API key set for $provider")
