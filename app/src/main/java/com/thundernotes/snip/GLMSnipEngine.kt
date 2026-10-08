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
 * GLM-4.6V-Flash snip engine (online, spec §7.3.6 fallback). Calls the
 * `open.bigmodel.cn` REST API (Zhipu AI) with the image (base64 data URL) +
 * a per-snip-type prompt → returns `SnipResult.Text` or `SnipResult.LaTeX`.
 *
 * Multi-account: round-robins through [SnipAccounts] GLM keys. If no key
 * is set, returns `Result.failure` → the fallback chain tries PaddleOCR next.
 */
class GLMSnipEngine(
    private val client: OkHttpClient = defaultClient,
) : SnipEngine {

    override val name: String = "GLM-4.6V-Flash"

    override fun isEnabled(): Boolean = SnipAccounts.getByProvider("glm").isNotEmpty()

    override suspend fun recognize(imageBytes: ByteArray, type: SnipType): Result<SnipResult> =
        withContext(Dispatchers.IO) {
            val account = SnipAccounts.nextAccount("glm")
                ?: return@withContext Result.failure(NoApiKeyException("glm"))
            val prompt = promptFor(type)
            val b64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            val body = JSONObject().apply {
                put("model", MODEL)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", prompt)
                            })
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().put("url", "data:image/png;base64,$b64"))
                            })
                        })
                    })
                })
            }.toString()
            val req = Request.Builder()
                .url("https://open.bigmodel.cn/api/paas/v4/chat/completions")
                .addHeader("Authorization", "Bearer ${account.apiKey}")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            runCatching {
                client.newCall(req).execute().use { res ->
                    val raw = res.body?.string().orEmpty()
                    if (!res.isSuccessful) throw RuntimeException(
                        "GLM HTTP ${res.code}: ${apiError(raw)}")
                    val json = JSONObject(raw)
                    val text = json
                        .optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("message")?.optString("content").orEmpty().trim()
                    if (text.isEmpty()) throw RuntimeException("GLM returned empty")
                    mapResult(text, type)
                }
            }
        }

    /**
     * Snip-type-specific instructions — mirrors the Gemini engine's prompts
     * (indentation preservation for CODE, aligned-block hint for multi-line
     * EQUATION) so both engines behave identically in the fallback chain.
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

    companion object {
        /**
         * The GLM vision model id (Zhipu's free tier vision model, matching
         * the spec's "GLM-4.6V-Flash" fallback choice).
         */
        const val MODEL = "glm-4v-flash"

        /** Extract the API's error message from a JSON error body (or the raw text). */
        private fun apiError(raw: String): String = runCatching {
            val err = JSONObject(raw).optJSONObject("error")?.optString("message")
            if (!err.isNullOrEmpty()) return@runCatching err
            val msg = JSONObject(raw).optString("msg")   // Zhipu uses "msg" on some errors
            if (msg.isNotEmpty() && msg != "null") msg else raw.take(200)
        }.getOrDefault(raw.take(200))

        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
