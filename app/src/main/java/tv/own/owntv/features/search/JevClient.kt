package tv.own.owntv.features.search

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * TypeSafe's System One endpoint with Jev: named questions about one [state] come back as typed
 * answers (a Noul's yes-probability, a Choice's pick). Nothing here knows about films; [MoodSearch]
 * asks the questions. [apiKey] is read on every call, so a key entered in Settings or the first-run
 * setup works at once; blank = the feature is off.
 */
class JevClient(okHttp: OkHttpClient, private val apiKey: () -> String) {

    private val http = okHttp.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()

    val available: Boolean get() = apiKey().isNotBlank()

    /** The `answers` object keyed by question id, or null when there is no key or the call failed (logged). */
    suspend fun ask(state: JSONObject, questions: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        val key = apiKey()
        if (key.isBlank()) return@withContext null
        val body = JSONObject().put("model", MODEL).put("state", state).put("questions", questions)
            .toString().toRequestBody(JSON_TYPE.toMediaType())
        // 429 and 529 are "come back in a moment"; everything else is final.
        for (attempt in 0 until 3) {
            val request = Request.Builder().url(URL).header("Authorization", "Bearer $key").post(body).build()
            val (code, text) = runCatching { http.newCall(request).execute().use { it.code to it.body.string() } }
                .onFailure { Log.w(TAG, "request failed: ${it.message}") }
                .getOrNull() ?: return@withContext null
            when (code) {
                200 -> return@withContext runCatching { JSONObject(text).optJSONObject("answers") }.getOrNull()
                429, 529 -> delay(600L shl attempt)
                else -> { Log.w(TAG, "HTTP $code: ${text.take(300)}"); return@withContext null }
            }
        }
        null
    }

    companion object {
        private const val URL = "https://api.typesafe.ai/v1/systemone"
        private const val MODEL = "jev-latest"
        private const val JSON_TYPE = "application/json"
        private const val TAG = "Jev"

        fun JSONObject.noul(id: String): Double = optJSONObject(id)?.optDouble("noul", 0.0) ?: 0.0
        fun JSONObject.choice(id: String): String? = optJSONObject(id)?.optString("choice")?.takeIf { it.isNotBlank() }
    }
}
