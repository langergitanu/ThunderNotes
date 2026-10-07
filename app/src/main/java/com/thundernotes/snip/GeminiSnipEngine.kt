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
 * Gemini 3 Flash snip engine (online, spec §7.3.6 primary). Calls the
 * `generativelanguage.googleapis.com` REST API with the image (base64) + a
 * per-snip-type prompt → returns `SnipResult.Text` or `SnipResult.LaTeX`.
 *
 * Multi-account: round-robins through [SnipAccounts] Gemini keys. If no key
 * is set, returns `Result.failure` → the fallback chain tries GLM next.
 *
 * Uses OkHttp (already a dependency). Build-verifiable (not unit-testable
 * without network — but the pure layers: preprocessor, cleaner, accounts,
 * fallback chain — are all tested).
 */
class GeminiSnipEngine(
    private val client: OkHttpClient = defaultClient,
) : SnipEngine {

    override val name: String = "Gemini 3 Flash"

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
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.0-flash:generateContent?key=${account.apiKey}")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            runCatching {
                client.newCall(req).execute().use { res ->
                    val json = JSONObject(res.body?.string().orEmpty())
                    val text = json
                        .optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")
                        ?.optJSONObject(0)?.optString("text").orEmpty().trim()
                    if (text.isEmpty()) throw RuntimeException("Gemini returned empty")
                    mapResult(text, type)
                }
            }
        }

    private fun promptFor(type: SnipType): String = when (type) {
        SnipType.TEXT -> "Extract all text from this image. Return ONLY the plain text, no explanation."
        SnipType.EQUATION -> "Recognise the mathematical equation(s) in this image. Return ONLY the LaTeX code. For multi-line, use \\begin{aligned}...\\end{aligned}. No explanation."
        SnipType.CODE -> "Extract the code from this image. Return ONLY the raw code, no markdown fences, no explanation."
        SnipType.DIAGRAM -> "Describe the diagram as a list of shapes (rectangles, circles, lines). Return as JSON: [{\"shape\":\"rect\",\"x\":0,\"y\":0,\"w\":10,\"h\":10}]."
    }

    private fun mapResult(text: String, type: SnipType): SnipResult = when (type) {
        SnipType.EQUATION -> SnipResult.LaTeX(text)
        SnipType.CODE -> SnipResult.Code(text)
        else -> SnipResult.Text(text)
    }

    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

class NoApiKeyException(provider: String) : RuntimeException("No API key set for $provider")
