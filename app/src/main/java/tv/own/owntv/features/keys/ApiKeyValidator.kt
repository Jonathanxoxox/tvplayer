package tv.own.owntv.features.keys

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** What a key check found. */
sealed interface KeyCheck {
    /** The service accepted the key. */
    data object Valid : KeyCheck

    /** The service rejected the key (or it was empty). */
    data object Invalid : KeyCheck

    /** The service could not be asked: no network, a timeout, or an answer that says nothing about the key. */
    data class Unreachable(val httpCode: Int? = null) : KeyCheck

    /** There is no way to check this service's key yet; it is stored as entered. */
    data object Unknown : KeyCheck
}

/**
 * Asks each service whether a key works, with one cheap read-only request (a Koin single). Never logs
 * the key, and never puts it in a URL except where the service requires it (TMDB's v3 `api_key`).
 */
class ApiKeyValidator(okHttp: OkHttpClient) {

    private val http = okHttp.newBuilder().callTimeout(TIMEOUT_S, TimeUnit.SECONDS).build()

    /** Checks [key] (sanitised first) against [service]. */
    suspend fun check(service: ApiService, key: String): KeyCheck = when (service) {
        ApiService.TMDB -> checkTmdb(key)
        ApiService.ELEVENLABS -> checkElevenLabs(key)
        ApiService.TYPESAFE -> checkTypeSafe(key)
    }

    /**
     * TMDB: `GET /3/configuration`. A v3 key (32 hex digits) goes as `api_key`, a v4 read token as a
     * Bearer header. 200 = valid, 401 = invalid.
     */
    suspend fun checkTmdb(key: String): KeyCheck {
        val clean = ApiKeys.sanitize(key)
        if (clean.isEmpty()) return KeyCheck.Invalid
        val v3 = clean.length == TMDB_V3_LENGTH && clean.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        val request = Request.Builder()
            .url(if (v3) "$TMDB_CONFIGURATION?api_key=$clean" else TMDB_CONFIGURATION)
            .apply { if (!v3) header("Authorization", "Bearer $clean") }
            .get()
            .build()
        return when (val answer = call(request)) {
            null -> KeyCheck.Unreachable()
            else -> when (answer.code) {
                200 -> KeyCheck.Valid
                401 -> KeyCheck.Invalid
                else -> KeyCheck.Unreachable(answer.code)
            }
        }
    }

    /**
     * ElevenLabs: `GET /v1/user` with `xi-api-key`. 200 = valid. A 401 is told apart by its detail:
     * `invalid_api_key` is a wrong key; `missing_permissions` is a real key restricted to other
     * scopes (speech to text only, say), which is valid for voice search.
     */
    suspend fun checkElevenLabs(key: String): KeyCheck {
        val clean = ApiKeys.sanitize(key)
        if (clean.isEmpty()) return KeyCheck.Invalid
        val request = Request.Builder().url(ELEVENLABS_USER).header("xi-api-key", clean).get().build()
        val answer = call(request) ?: return KeyCheck.Unreachable()
        return when (answer.code) {
            200 -> KeyCheck.Valid
            401, 403 -> {
                val status = runCatching { JSONObject(answer.body).optJSONObject("detail")?.optString("status") }.getOrNull()
                if (status == "missing_permissions") KeyCheck.Valid else KeyCheck.Invalid
            }
            else -> KeyCheck.Unreachable(answer.code)
        }
    }

    /**
     * TypeSafe: no read-only endpoint is known that answers for a key without running a paid
     * question, so the key is stored as entered.
     */
    suspend fun checkTypeSafe(key: String): KeyCheck {
        if (ApiKeys.sanitize(key).isEmpty()) return KeyCheck.Invalid
        // TODO: check against a cheap TypeSafe endpoint once one is known.
        return KeyCheck.Unknown
    }

    private class Answer(val code: Int, val body: String)

    private suspend fun call(request: Request): Answer? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(request).execute().use { Answer(it.code, it.body.string().take(MAX_BODY)) }
        }.getOrNull()
    }

    private companion object {
        const val TIMEOUT_S = 10L
        const val MAX_BODY = 4_096
        const val TMDB_V3_LENGTH = 32
        const val TMDB_CONFIGURATION = "https://api.themoviedb.org/3/configuration"
        const val ELEVENLABS_USER = "https://api.elevenlabs.io/v1/user"
    }
}
