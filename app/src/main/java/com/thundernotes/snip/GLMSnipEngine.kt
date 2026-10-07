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

    override suspend fun recognize(imageBytes: ByteArray, type: SnipType): Result<SnipResult> =
        withContext(Dispatchers.IO) {
            val account = SnipAccounts.nextAccount("glm")
                ?: return@withContext Result.failure(NoApiKeyException("glm"))
            val prompt = promptFor(type)
            val b64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            val body = JSONObject().apply {
                put("model", "glm-4v-flash")
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
                    val json = JSONObject(res.body?.string().orEmpty())
                    val text = json
                        .optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("message")?.optString("content").orEmpty().trim()
                    if (text.isEmpty()) throw RuntimeException("GLM returned empty")
                    mapResult(text, type)
                }
            }
        }

    private fun promptFor(type: SnipType): String = when (type) {
        SnipType.TEXT -> "Extract all text from this image. Return ONLY the plain text, no explanation."
        SnipType.EQUATION -> "Recognise the mathematical equation(s) in this image. Return ONLY the LaTeX code. For multi-line, use \\begin{aligned}...\\end{aligned}. No explanation."
        SnipType.CODE -> "Extract the code from this image. Return ONLY the raw code, no markdown fences, no explanation."
        SnipType.DIAGRAM -> "Describe the diagram as a list of shapes. Return as JSON."
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
